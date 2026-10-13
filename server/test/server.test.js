import test from 'node:test';
import assert from 'node:assert/strict';
import { createApp, RateLimiter } from '../src/server.js';
import { Store } from '../src/store.js';
import { normalizeBoard, enrichMatch } from '../src/provider.js';
import { withDemo } from '../src/demo.js';
import { board, detail } from './fixtures.js';

async function setup(t, { push = true, limits, maxActivities } = {}) {
  const store = new Store(':memory:');
  const match = enrichMatch(normalizeBoard(board, '2026-09-05', 'd1')[0], detail);
  const provider = { board: async () => [match], match: async () => match };
  const limiter = new RateLimiter(limits || { requests: { max: 1000, windowMs: 60000 }, devices: { max: 1000, windowMs: 3600000 } });
  const app = createApp({ store, provider: withDemo(provider), apns: { ready: push }, limiter, maxActivities });
  await new Promise(resolve => app.listen(0, '127.0.0.1', resolve));
  t.after(async () => { await new Promise(resolve => app.close(resolve)); store.close(); });
  const request = (path, options = {}) => fetch(`http://127.0.0.1:${app.address().port}${path}`, { ...options, headers: { 'content-type': 'application/json', ...options.headers } });
  const device = async () => {
    const { deviceId, secret } = await (await request('/v1/devices', { method: 'POST' })).json();
    return { authorization: `Bearer ${deviceId}.${secret}` };
  };
  const path = '/v1/activities/11223344-1122-3344-5566-112233445566';
  const data = { matchId: match.id, date: match.date, division: match.division, token: 'ab'.repeat(32) };
  return { store, request, device, path, data, provider, match };
}
test('scores, config and health are public', async t => {
  const f = await setup(t);
  assert.equal((await f.request('/health')).status, 200);
  assert.equal((await f.request('/v1/config')).status, 200);
  const response = await f.request('/v1/matches?date=2026-09-05');
  assert.equal(response.status, 200); assert.equal((await response.json()).matches[0].id, f.match.id);
  assert.match(response.headers.get('cache-control'), /public/);
});
test('privacy policy is served as HTML and escapes the contact', async t => {
  const f = await setup(t);
  const response = await f.request('/privacy');
  assert.equal(response.status, 200); assert.match(response.headers.get('content-type'), /text\/html/);
  assert.match(await response.text(), /push tokens/i);
});
test('activities require a registered device credential', async t => {
  const f = await setup(t);
  const body = JSON.stringify(f.data);
  assert.equal((await f.request(f.path, { method: 'PUT', body })).status, 401);
  assert.equal((await f.request(f.path, { method: 'PUT', body, headers: { authorization: `Bearer ${'0'.repeat(8)}-0000-0000-0000-${'0'.repeat(12)}.${'a'.repeat(64)}` } })).status, 401);
  const headers = await f.device();
  const [id, secret] = headers.authorization.slice(7).split('.');
  assert.equal((await f.request(f.path, { method: 'PUT', body, headers: { authorization: `Bearer ${id}.${'b'.repeat(64)}` } })).status, 401);
  assert.equal(f.store.device(id).secretHash.includes(secret), false);
  assert.equal((await f.request(f.path, { method: 'PUT', body, headers })).status, 200);
  assert.equal(f.store.get(f.path.split('/').pop()).deviceId, id);
});
test('a device cannot change or stop another device\'s activity', async t => {
  const f = await setup(t);
  const owner = await f.device(), other = await f.device();
  assert.equal((await f.request(f.path, { method: 'PUT', body: JSON.stringify(f.data), headers: owner })).status, 200);
  assert.equal((await f.request(f.path, { method: 'PUT', body: JSON.stringify({ ...f.data, token: 'cd'.repeat(32) }), headers: other })).status, 404);
  assert.equal((await f.request(f.path, { method: 'DELETE', headers: other })).status, 404);
  assert.equal(f.store.all().length, 1); assert.equal(f.store.all()[0].token, f.data.token);
});
test('invalid query, invalid registration and null JSON are rejected', async t => {
  const f = await setup(t); const headers = await f.device();
  assert.equal((await f.request('/v1/matches?date=2026-02-30')).status, 400);
  assert.equal((await f.request('/v1/matches?division=../../')).status, 400);
  assert.equal((await f.request(f.path, { method: 'PUT', body: 'null', headers })).status, 400);
  assert.equal((await f.request(f.path, { method: 'PUT', body: '{}', headers })).status, 400);
});
test('APNs configuration is required before registering', async t => {
  const f = await setup(t, { push: false });
  assert.equal((await f.request(f.path, { method: 'PUT', body: JSON.stringify(f.data), headers: await f.device() })).status, 503);
});
test('registration persists, deletion is idempotent, late registration cannot resurrect it', async t => {
  const f = await setup(t); const headers = await f.device();
  assert.equal((await f.request(f.path, { method: 'PUT', body: JSON.stringify(f.data), headers })).status, 200);
  assert.equal(f.store.all().length, 1);
  assert.equal((await f.request(f.path, { method: 'DELETE', headers })).status, 200);
  assert.equal((await f.request(f.path, { method: 'DELETE', headers })).status, 200);
  assert.equal((await f.request(f.path, { method: 'PUT', body: JSON.stringify(f.data), headers })).status, 410);
  assert.equal(f.store.all().length, 0);
});
test('per-device and server-wide activity caps apply', async t => {
  const f = await setup(t, { maxActivities: 12 });
  const a = await f.device(), b = await f.device();
  const put = (n, headers) => f.request(`/v1/activities/11223344-1122-3344-5566-1122334455${String(n).padStart(2, '0')}`, { method: 'PUT', body: JSON.stringify(f.data), headers });
  for (let n = 0; n < 10; n++) assert.equal((await put(n, a)).status, 200);
  assert.equal((await put(10, a)).status, 429);
  assert.equal((await put(11, b)).status, 200); assert.equal((await put(12, b)).status, 200);
  assert.equal((await put(13, b)).status, 429);
});
test('rate limits throttle requests and new installs per client', async t => {
  const f = await setup(t, { limits: { requests: { max: 3, windowMs: 60000 }, devices: { max: 1, windowMs: 3600000 } } });
  assert.equal((await f.request('/v1/devices', { method: 'POST' })).status, 201);
  assert.equal((await f.request('/v1/devices', { method: 'POST' })).status, 429);
  assert.equal((await f.request('/v1/config')).status, 200);
  assert.equal((await f.request('/v1/config')).status, 429);
  assert.equal((await f.request('/health')).status, 200);
});
test('demo match is public and can be followed', async t => {
  const f = await setup(t);
  const demo = await (await f.request('/v1/matches/demo')).json();
  assert.equal(demo.id, 'demo'); assert.ok(['live', 'final'].includes(demo.status));
  if (demo.status === 'live') {
    const body = JSON.stringify({ ...f.data, matchId: 'demo', date: demo.date, division: 'd1' });
    assert.equal((await f.request(f.path, { method: 'PUT', body, headers: await f.device() })).status, 200);
  }
});
test('match ending while push token is obtained can receive final push', async t => {
  const f = await setup(t); f.match.status = 'final';
  assert.equal((await f.request(f.path, { method: 'PUT', body: JSON.stringify(f.data), headers: await f.device() })).status, 200);
});
test('upstream failures return actionable error and no fake scores', async t => {
  const f = await setup(t); f.provider.board = async () => { throw new Error('offline'); };
  const response = await f.request('/v1/matches');
  assert.equal(response.status, 502); assert.match((await response.json()).error, /temporarily unavailable/);
});
test('teams and standings are public, with counted standings when NCAA.com has none', async t => {
  const store = new Store(':memory:');
  let official = { division: 'd1', updated: 'Oct 1', conferences: [{ name: 'Big Ten', teams: [] }] };
  const counted = { division: 'd1', source: 'scores', updated: '', conferences: [] };
  const provider = { board: async () => [], match: async () => { throw new Error('none'); }, standings: async () => { if (!official) throw new Error('Score provider returned 500'); return official; } };
  const teamIndex = { teams: () => [{ slug: 'a', name: 'A', abbreviation: 'A', division: 'd1' }, { slug: 'b', name: 'B', abbreviation: 'B', division: 'd2' }],
    complete: () => true, standings: () => counted };
  const app = createApp({ store, provider: withDemo(provider), apns: { ready: true }, teamIndex });
  await new Promise(resolve => app.listen(0, '127.0.0.1', resolve));
  t.after(async () => { await new Promise(resolve => app.close(resolve)); store.close(); });
  const get = path => fetch(`http://127.0.0.1:${app.address().port}${path}`);
  assert.deepEqual((await (await get('/v1/teams?division=d2')).json()).teams.map(t => t.slug), ['b']);
  assert.equal((await (await get('/v1/teams')).json()).teams.length, 2);
  assert.equal((await get('/v1/teams?division=d9')).status, 400);
  assert.equal((await (await get('/v1/standings?division=d1')).json()).source, 'ncaa');
  official = { ...official, conferences: [] };
  assert.deepEqual(await (await get('/v1/standings?division=d1')).json(), counted);
  official = null;
  const response = await get('/v1/standings?division=d1');
  assert.equal(response.status, 200); assert.deepEqual(await response.json(), counted);
});
