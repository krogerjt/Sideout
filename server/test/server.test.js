import test from 'node:test';
import assert from 'node:assert/strict';
import { createApp } from '../src/server.js';
import { Store } from '../src/store.js';
import { normalizeBoard, enrichMatch } from '../src/provider.js';
import { board, detail } from './fixtures.js';

async function setup(t, push = true) {
  const store = new Store(':memory:');
  const match = enrichMatch(normalizeBoard(board, '2026-09-05', 'd1')[0], detail);
  const provider = { board: async () => [match], match: async () => match };
  const app = createApp({ store, provider, apns: { ready: push }, accessKey: 'test-secret' });
  await new Promise(resolve => app.listen(0, '127.0.0.1', resolve));
  t.after(async () => { await new Promise(resolve => app.close(resolve)); store.close(); });
  const request = (path, options = {}) => fetch(`http://127.0.0.1:${app.address().port}${path}`, { ...options, headers: { authorization: 'Bearer test-secret', 'content-type': 'application/json', ...options.headers } });
  const path = '/v1/activities/11223344-1122-3344-5566-112233445566';
  const data = { matchId: match.id, date: match.date, division: match.division, token: 'ab'.repeat(32) };
  return { store, request, path, data, provider, match };
}
test('health is public; scores require access key', async t => {
  const f = await setup(t);
  assert.equal((await f.request('/health', { headers: { authorization: '' } })).status, 200);
  assert.equal((await f.request('/v1/matches', { headers: { authorization: '' } })).status, 401);
  const response = await f.request('/v1/matches?date=2026-09-05');
  assert.equal(response.status, 200); assert.equal((await response.json()).matches[0].id, f.match.id);
});
test('invalid query, invalid registration and null JSON are rejected', async t => {
  const f = await setup(t);
  assert.equal((await f.request('/v1/matches?date=2026-02-30')).status, 400);
  assert.equal((await f.request('/v1/matches?division=../../')).status, 400);
  assert.equal((await f.request(f.path, { method: 'PUT', body: 'null' })).status, 400);
  assert.equal((await f.request(f.path, { method: 'PUT', body: '{}' })).status, 400);
});
test('APNs configuration is required before registering', async t => {
  const f = await setup(t, false);
  assert.equal((await f.request(f.path, { method: 'PUT', body: JSON.stringify(f.data) })).status, 503);
});
test('registration persists, deletion is idempotent, late registration cannot resurrect it', async t => {
  const f = await setup(t);
  assert.equal((await f.request(f.path, { method: 'PUT', body: JSON.stringify(f.data) })).status, 200);
  assert.equal(f.store.all().length, 1);
  assert.equal((await f.request(f.path, { method: 'DELETE' })).status, 200);
  assert.equal((await f.request(f.path, { method: 'DELETE' })).status, 200);
  assert.equal((await f.request(f.path, { method: 'PUT', body: JSON.stringify(f.data) })).status, 410);
  assert.equal(f.store.all().length, 0);
});
test('match ending while push token is obtained can receive final push', async t => {
  const f = await setup(t); f.match.status = 'final';
  assert.equal((await f.request(f.path, { method: 'PUT', body: JSON.stringify(f.data) })).status, 200);
});
test('upstream failures return actionable error and no fake scores', async t => {
  const f = await setup(t); f.provider.board = async () => { throw new Error('offline'); };
  const response = await f.request('/v1/matches');
  assert.equal(response.status, 502); assert.match((await response.json()).error, /temporarily unavailable/);
});
