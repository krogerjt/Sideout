import test from 'node:test';
import assert from 'node:assert/strict';
import { normalizeBoard, enrichMatch, contentState, validDate, easternDate, NCAAProvider } from '../src/provider.js';
import { board, detail } from './fixtures.js';

test('NCAA sets won and current set points remain distinct', () => {
  const match = enrichMatch(normalizeBoard(board, '2026-09-05', 'd1', 100000)[0], detail, 100000);
  assert.deepEqual(contentState(match), { homeSets: 2, awaySets: 2, homePoints: 1, awayPoints: 1, status: 'live', statusText: '5TH SET', checkedAt: 100 });
  assert.equal(match.sets[0].away, 18);
  assert.equal(match.home.color, '#0032A0');
});
test('missing points are unknown; zero points are valid', () => {
  const raw = structuredClone(detail);
  raw.contests[0].linescores[4] = { period: '5', home: '0', visit: '' };
  const state = contentState(enrichMatch(normalizeBoard(board, '2026-09-05', 'd1')[0], raw));
  assert.equal(state.homePoints, 0); assert.equal(state.awayPoints, null);
});
test('never shows previous set points as current, or shows final points as live', () => {
  const raw = structuredClone(detail); raw.contests[0].linescores.pop();
  const match = enrichMatch(normalizeBoard(board, '2026-09-05', 'd1')[0], raw);
  assert.equal(contentState(match).homePoints, null);
  assert.equal(contentState({ ...match, status: 'final' }).homePoints, null);
});
test('scheduled blank scores do not become zero', () => {
  const raw = structuredClone(board);
  raw.games[0].game.gameState = 'pre'; raw.games[0].game.home.score = '';
  const match = normalizeBoard(raw, '2026-09-05', 'd1')[0];
  assert.equal(match.home.setsWon, null); assert.equal(match.status, 'scheduled');
});
test('malformed upstream is an error, empty schedule is valid', () => {
  assert.throws(() => normalizeBoard({}, '2026-09-05', 'd1'));
  assert.deepEqual(normalizeBoard({ games: [] }, '2026-09-05', 'd1'), []);
  assert.throws(() => enrichMatch(normalizeBoard(board, '2026-09-05', 'd1')[0], { contests: [] }));
});
test('date validation and Eastern date handle UTC rollover', () => {
  assert.equal(validDate('2026-02-30'), false); assert.equal(validDate('2028-02-29'), true);
  assert.equal(validDate('../2026-01-01'), false);
  assert.equal(easternDate(new Date('2026-09-06T01:00:00Z')), '2026-09-05');
});
test('provider coalesces concurrent fetches and preserves cached checkedAt', async () => {
  let requests = 0;
  const provider = new NCAAProvider({ fetcher: async () => { requests++; return { ok: true, json: async () => board }; } });
  const [a, b] = await Promise.all([provider.board('2026-09-05', 'd1'), provider.board('2026-09-05', 'd1')]);
  assert.equal(requests, 1); assert.equal(a[0].checkedAt, b[0].checkedAt);
  const cached = await provider.board('2026-09-05', 'd1');
  assert.equal(cached[0].checkedAt, a[0].checkedAt); assert.equal(requests, 1);
});
test('provider errors are not silently replaced with fabricated data', async () => {
  const provider = new NCAAProvider({ fetcher: async () => ({ ok: false, status: 503 }) });
  await assert.rejects(provider.board('2026-09-05', 'd1'), /503/);
});
