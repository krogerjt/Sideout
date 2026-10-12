import test from 'node:test';
import assert from 'node:assert/strict';
import { Store } from '../src/store.js';
import { startFavorites, pollSubscriptions, autostartDates, autostartDue } from '../src/worker.js';
import { startPayload } from '../src/apns.js';
import { createApp, RateLimiter } from '../src/server.js';
import { normalizeBoard } from '../src/provider.js';
import { board } from './fixtures.js';

const START = 1788634800 * 1000; // fixture's scheduled first serve
function setup(t) {
  const store = new Store(':memory:'); t.after(() => store.close());
  store.addDevice('dev-1', 'hash');
  store.setAutostart('dev-1', 'ef'.repeat(32), [{ slug: 'boise-st', division: 'd1' }]);
  const match = { ...normalizeBoard(board, '2026-09-05', 'd1', START)[0], status: 'scheduled', statusText: '7:00 PM ET' };
  const provider = { board: async (date, division) => date === '2026-09-05' && division === 'd1' ? [match] : [] };
  const starts = [], updates = [];
  const apns = { ready: true, start: async (...args) => { starts.push(args); return { ok: true }; }, send: async (...args) => { updates.push(args); return { ok: true }; } };
  return { store, match, provider, apns, starts, updates, onError: () => {} };
}
test('favorite match is started 30 minutes before first serve, once', async t => {
  const f = setup(t);
  await startFavorites({ ...f, now: START - 31 * 60000 }); assert.equal(f.starts.length, 0);
  await startFavorites({ ...f, now: START - 29 * 60000 }); assert.equal(f.starts.length, 1);
  const [token, attributes, state, alert] = f.starts[0];
  assert.equal(token, 'ef'.repeat(32));
  assert.equal(attributes.matchID, f.match.id); assert.equal(attributes.homeName, 'Boise St.');
  assert.equal(state.status, 'scheduled'); assert.match(alert.title, /Idaho St\. at Boise St\./);
  const sub = f.store.get(attributes.subscriptionID);
  assert.equal(sub.deviceId, 'dev-1'); assert.equal(sub.token, '');
  await startFavorites({ ...f, now: START - 28 * 60000 }); assert.equal(f.starts.length, 1);
  f.match.status = 'live';
  await startFavorites({ ...f, now: START }); assert.equal(f.starts.length, 1, 'a dismissed or running activity is not restarted');
});
test('pending auto-started activity gets no updates until its token is registered', async t => {
  const f = setup(t);
  await startFavorites({ ...f, now: START - 10 * 60000 });
  await pollSubscriptions({ ...f, now: START - 10 * 60000 }); assert.equal(f.updates.length, 0);
});
test('non-favorite matches and teams in other divisions are ignored', async t => {
  const f = setup(t);
  f.store.setAutostart('dev-1', 'ef'.repeat(32), [{ slug: 'idaho', division: 'd1' }, { slug: 'boise-st', division: 'd2' }]);
  await startFavorites({ ...f, now: START }); assert.equal(f.starts.length, 0);
});
test('a favorite match already live is started when found', async t => {
  const f = setup(t); f.match.status = 'live'; f.match.startTime = null;
  await startFavorites({ ...f, now: START + 3600000 }); assert.equal(f.starts.length, 1);
  assert.match(f.starts[0][3].body, /Underway/);
});
test('expired push-to-start token removes the registration', async t => {
  const f = setup(t);
  await startFavorites({ ...f, now: START, apns: { ready: true, start: async () => ({ expired: true }) } });
  assert.equal(f.store.autostart('dev-1'), undefined); assert.equal(f.store.all().length, 0);
});
test('failed push-to-start is retried on the next poll', async t => {
  const f = setup(t);
  await startFavorites({ ...f, now: START, apns: { ready: true, start: async () => { throw new Error('503'); } } });
  assert.equal(f.store.launched('dev-1', f.match.id), false);
  await startFavorites({ ...f, now: START }); assert.equal(f.starts.length, 1);
});
test('schedule window and late-night boards', () => {
  const match = { status: 'scheduled', startTime: START / 1000 };
  assert.equal(autostartDue(match, START - 30 * 60000), true);
  assert.equal(autostartDue(match, START - 31 * 60000), false);
  assert.equal(autostartDue(match, START + 4 * 3600000), false);
  assert.equal(autostartDue({ status: 'final', startTime: START / 1000 }, START), false);
  assert.equal(autostartDue({ status: 'scheduled', startTime: null }, START), false);
  assert.deepEqual(autostartDates(Date.parse('2026-09-06T05:30:00Z')), ['2026-09-05', '2026-09-06']); // 1:30 AM Eastern
  assert.deepEqual(autostartDates(Date.parse('2026-09-06T16:00:00Z')), ['2026-09-06']);
});
test('push-to-start payload names the attributes type', () => {
  const payload = startPayload({ matchID: '1' }, { checkedAt: 100 }, { title: 't', body: 'b' }, 105);
  assert.equal(payload.aps.event, 'start'); assert.equal(payload.aps['attributes-type'], 'VolleyballActivityAttributes');
  assert.equal(payload.aps['stale-date'], 220); assert.deepEqual(payload.aps.alert, { title: 't', body: 'b' });
});

async function server(t) {
  const store = new Store(':memory:');
  const match = { ...normalizeBoard(board, '2026-09-05', 'd1')[0], status: 'scheduled' };
  const provider = { board: async () => [match], match: async () => match };
  const limiter = new RateLimiter({ requests: { max: 1000, windowMs: 60000 }, devices: { max: 1000, windowMs: 3600000 } });
  const app = createApp({ store, provider, apns: { ready: true }, limiter });
  await new Promise(resolve => app.listen(0, '127.0.0.1', resolve));
  t.after(async () => { await new Promise(resolve => app.close(resolve)); store.close(); });
  const request = (path, options = {}) => fetch(`http://127.0.0.1:${app.address().port}${path}`, { ...options, headers: { 'content-type': 'application/json', ...options.headers } });
  const { deviceId, secret } = await (await request('/v1/devices', { method: 'POST' })).json();
  return { store, match, request, deviceId, headers: { authorization: `Bearer ${deviceId}.${secret}` } };
}
test('favorites registration is authenticated, validated and removable', async t => {
  const f = await server(t);
  const body = JSON.stringify({ token: 'EF'.repeat(32), teams: [{ slug: 'boise-st', division: 'd1', name: 'ignored' }] });
  assert.equal((await f.request('/v1/autostart', { method: 'PUT', body })).status, 401);
  assert.equal((await f.request('/v1/autostart', { method: 'PUT', body, headers: f.headers })).status, 200);
  assert.deepEqual(f.store.autostart(f.deviceId).teams, [{ slug: 'boise-st', division: 'd1' }]);
  assert.equal(f.store.autostart(f.deviceId).token, 'ef'.repeat(32));
  for (const bad of [{ token: 'x', teams: [] }, { token: 'ef'.repeat(32), teams: [{ slug: '../x', division: 'd1' }] },
    { token: 'ef'.repeat(32), teams: Array(51).fill({ slug: 'a', division: 'd1' }) }, null])
    assert.equal((await f.request('/v1/autostart', { method: 'PUT', body: JSON.stringify(bad), headers: f.headers })).status, 400);
  assert.equal((await f.request('/v1/autostart', { method: 'PUT', body: JSON.stringify({ token: 'ef'.repeat(32), teams: [] }), headers: f.headers })).status, 200);
  assert.equal(f.store.autostart(f.deviceId), undefined);
  f.store.setAutostart(f.deviceId, 'ef'.repeat(32), [{ slug: 'boise-st', division: 'd1' }]);
  assert.equal((await f.request('/v1/autostart', { method: 'DELETE', headers: f.headers })).status, 200);
  assert.equal(f.store.autostart(f.deviceId), undefined);
});
test('an auto-started activity can register its update token before the match is live', async t => {
  const f = await server(t);
  const id = '11223344-1122-3344-5566-112233445566';
  const data = { matchId: f.match.id, date: f.match.date, division: f.match.division, token: 'ab'.repeat(32) };
  assert.equal((await f.request(`/v1/activities/${id}`, { method: 'PUT', body: JSON.stringify(data), headers: f.headers })).status, 409);
  f.store.put({ id, ...data, token: '', deviceId: f.deviceId, expiresAt: Date.now() + 1000 });
  assert.equal((await f.request(`/v1/activities/${id}`, { method: 'PUT', body: JSON.stringify(data), headers: f.headers })).status, 200);
  assert.equal(f.store.get(id).token, data.token);
});
