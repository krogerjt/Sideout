import test from 'node:test';
import assert from 'node:assert/strict';
import { demoMatch, withDemo } from '../src/demo.js';
import { contentState } from '../src/provider.js';

const SLOT = 20 * 60 * 1000;
test('demo match is deterministic and advances over time', () => {
  const start = 1000 * SLOT;
  assert.deepEqual(demoMatch(start + 60000), demoMatch(start + 60000));
  const early = demoMatch(start + 60000), later = demoMatch(start + 8 * 60000);
  const points = m => m.sets.reduce((sum, s) => sum + s.home + s.away, 0);
  assert.equal(early.status, 'live'); assert.ok(points(later) > points(early));
});
test('demo match follows volleyball scoring and ends in a final', () => {
  const start = 1000 * SLOT;
  const final = demoMatch(start + SLOT - 1000);
  assert.equal(final.status, 'final');
  assert.equal(Math.max(final.home.setsWon, final.away.setsWon), 3);
  for (const set of final.sets) {
    const target = set.number === 5 ? 15 : 25;
    assert.ok(Math.max(set.home, set.away) >= target && Math.abs(set.home - set.away) >= 2, `set ${set.number} finished`);
  }
  assert.equal(contentState(final).homePoints, null);
});
test('live demo exposes current-set points to the Live Activity', () => {
  const live = demoMatch(1000 * SLOT + 5 * 60000);
  const state = contentState(live);
  assert.equal(state.homePoints, live.sets.at(-1).home); assert.equal(state.awayPoints, live.sets.at(-1).away);
});
test('withDemo routes only the demo id to the simulator', async () => {
  const provider = withDemo({ board: async () => ['real'], match: async (date, division, id) => ({ id }) }, () => 1000 * SLOT);
  assert.equal((await provider.match('2026-09-05', 'd1', '123')).id, '123');
  assert.equal((await provider.match('2026-09-05', 'd1', 'demo')).id, 'demo');
  assert.deepEqual(await provider.board('2026-09-05', 'd1'), ['real']);
});
