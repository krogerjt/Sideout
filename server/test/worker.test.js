import test from 'node:test';
import assert from 'node:assert/strict';
import { Store } from '../src/store.js';
import { pollSubscriptions } from '../src/worker.js';
import { activityPayload, APNs } from '../src/apns.js';
import { normalizeBoard, enrichMatch } from '../src/provider.js';
import { board, detail } from './fixtures.js';
import { generateKeyPairSync, verify } from 'node:crypto';

function setup(t) {
  const store = new Store(':memory:'); t.after(() => store.close());
  const sub = { id: 'aa', matchId: '6625873', date: '2026-09-05', division: 'd1', token: 'ab'.repeat(32), expiresAt: 9999999 };
  store.put(sub);
  let calls = 0;
  const match = enrichMatch(normalizeBoard(board, sub.date, sub.division, 100000)[0], detail, 100000);
  const provider = { match: async () => { calls++; return match; } };
  const pushes = [];
  const apns = { ready: true, send: async (...args) => { pushes.push(args); return { ok: true }; } };
  return { store, sub, match, provider, pushes, apns, calls: () => calls, now: 100000, onError: () => {} };
}
test('sends changes, suppresses duplicate updates, sends freshness heartbeat', async t => {
  const f = setup(t);
  await pollSubscriptions(f); assert.equal(f.pushes.length, 1);
  await pollSubscriptions({ ...f, now: 120000 }); assert.equal(f.pushes.length, 1);
  await pollSubscriptions({ ...f, now: 161000 }); assert.equal(f.pushes.length, 2);
  f.match.home.setsWon = 3;
  await pollSubscriptions({ ...f, now: 162000 }); assert.equal(f.pushes.length, 3);
});
test('final ends activity and removes subscription after successful push', async t => {
  const f = setup(t); f.match.status = 'final';
  await pollSubscriptions(f);
  assert.equal(f.pushes[0][2], true); assert.equal(f.store.all().length, 0);
});
test('failed final push is retried, not discarded', async t => {
  const f = setup(t); f.match.status = 'final';
  await pollSubscriptions({ ...f, apns: { ready: true, send: async () => { throw new Error('503'); } } });
  assert.equal(f.store.all().length, 1);
  await pollSubscriptions(f); assert.equal(f.store.all().length, 0);
});
test('outage does not advance freshness or send stale data as fresh', async t => {
  const f = setup(t);
  await pollSubscriptions({ ...f, provider: { match: async () => { throw new Error('offline'); } } });
  assert.equal(f.pushes.length, 0); assert.equal(f.store.get(f.sub.id).lastSent, 0);
});
test('same match is fetched once for multiple activities in a poll', async t => {
  const f = setup(t); f.store.put({ ...f.sub, id: 'bb' });
  await pollSubscriptions(f); assert.equal(f.calls(), 1); assert.equal(f.pushes.length, 2);
});
test('expired tokens and eight-hour subscriptions are cleaned up', async t => {
  const f = setup(t);
  await pollSubscriptions({ ...f, apns: { ready: true, send: async () => ({ expired: true }) } });
  assert.equal(f.store.all().length, 0);
  f.store.put({ ...f.sub, expiresAt: 50 }); await pollSubscriptions(f);
  assert.equal(f.store.all().length, 0); assert.equal(f.pushes.length, 0);
});
test('token rotation resets delivery state without extending lifetime', t => {
  const f = setup(t);
  f.store.sent(f.sub.id, 'old-state', 100, f.sub.token);
  f.store.put({ ...f.sub, token: 'cd'.repeat(32), expiresAt: 999999999 });
  assert.equal(f.store.get(f.sub.id).lastState, null);
  assert.equal(f.store.get(f.sub.id).expiresAt, f.sub.expiresAt);
});
test('APNs payload uses Unix timestamps, stale deadline and terminal dismissal', () => {
  const state = { checkedAt: 100 };
  assert.deepEqual(activityPayload(state, false, 105), { aps: { timestamp: 105, event: 'update', 'content-state': state, 'stale-date': 220 } });
  assert.equal(activityPayload(state, true, 105).aps['dismissal-date'], 1905);
  assert.equal(activityPayload(state, true, 105).aps.event, 'end');
});
test('APNs JWT is verifiable ES256 with raw 64-byte signature', () => {
  const { privateKey, publicKey } = generateKeyPairSync('ec', { namedCurve: 'P-256' });
  const apns = new APNs({ APNS_TEAM_ID: 'TEAM', APNS_KEY_ID: 'KEY' }); apns.key = privateKey;
  const token = apns.jwt(); const [header, claims, signature] = token.split('.');
  assert.equal(JSON.parse(Buffer.from(header, 'base64url')).alg, 'ES256');
  assert.equal(JSON.parse(Buffer.from(claims, 'base64url')).iss, 'TEAM');
  assert.equal(Buffer.from(signature, 'base64url').length, 64);
  assert.equal(verify('sha256', Buffer.from(`${header}.${claims}`), { key: publicKey, dsaEncoding: 'ieee-p1363' }, Buffer.from(signature, 'base64url')), true);
  assert.equal(apns.jwt(), token);
});
test('score changes are sent at priority 10 and unchanged heartbeats at 5', async t => {
  const f = setup(t);
  await pollSubscriptions(f); assert.deepEqual(f.pushes[0][3], { changed: true });
  await pollSubscriptions({ ...f, now: 161000 }); assert.deepEqual(f.pushes[1][3], { changed: false });
  const apns = new APNs({ APNS_ENVIRONMENT: 'production' }); apns.ready = true;
  const sent = []; apns.request = async (env, token, payload, options) => { sent.push(options.priority); return { ok: true }; };
  await apns.send(f.sub, {}, false, { changed: true }); await apns.send(f.sub, {}, false, { changed: false }); await apns.send(f.sub, {}, true);
  assert.deepEqual(sent, ['10', '5', '10']);
});
test('a token from the other APNs environment is retried there and remembered', async t => {
  const apns = new APNs({ APNS_ENVIRONMENT: 'development' }); apns.ready = true;
  const hosts = [];
  apns.request = async env => { hosts.push(env); return env === 'production' ? { ok: true } : { expired: true, reason: 'BadDeviceToken' }; };
  const warn = console.warn; console.warn = () => {}; t.after(() => { console.warn = warn; });
  assert.deepEqual(await apns.push('ab', {}, {}), { ok: true });
  assert.deepEqual(await apns.push('ab', {}, {}), { ok: true });
  assert.deepEqual(hosts, ['development', 'production', 'production']);
});
test('a token rejected by both APNs environments is reported expired', async t => {
  const apns = new APNs({ APNS_ENVIRONMENT: 'production' }); apns.ready = true;
  apns.request = async () => ({ expired: true, reason: 'BadDeviceToken' });
  assert.equal((await apns.push('ab', {}, {})).expired, true);
  apns.request = async () => ({ expired: true, reason: 'Unregistered' });
  let calls = 0; const request = apns.request; apns.request = async (...a) => { calls++; return request(...a); };
  assert.equal((await apns.push('cd', {}, {})).expired, true); assert.equal(calls, 1);
});
