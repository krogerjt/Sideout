import test from 'node:test';
import assert from 'node:assert/strict';
import { Store } from '../src/store.js';
import { TeamIndex, seasonDates, conferenceName } from '../src/teams.js';
import { normalizeStandings } from '../src/provider.js';

const team = (slug, name) => ({ slug, name, abbreviation: name.slice(0, 5) });
const at = date => Date.parse(`${date}T16:00:00Z`);

test('season dates cover the current season, or the last one in the offseason', () => {
  const fall = seasonDates(at('2026-10-13'));
  assert.equal(fall[0], '2026-08-10'); assert.equal(fall.at(-1), '2026-12-22');
  assert.equal(seasonDates(at('2027-03-01'))[0], '2026-08-10');
});
test('the index reads every board once, refreshes the next two weeks daily, and treats 404 as an empty day', async t => {
  const store = new Store(':memory:'); t.after(() => store.close());
  const calls = [];
  let failing = true;
  const provider = { board: async (date, division) => {
    calls.push(`${date}/${division}`);
    if (date === '2026-08-10') throw new Error('Score provider returned 404');
    if (date === '2026-08-11' && division === 'd2' && failing) throw new Error('Score provider returned 500');
    if (date === '2026-10-14' && division === 'd1') return [{ home: team('boise-st', 'Boise St.'), away: team('idaho-st', 'Idaho St.') }];
    if (date === '2026-10-14' && division === 'd2') return [{ home: team('idaho-st', 'Idaho St.'), away: team('mont-st-billings', 'MSU Billings') }];
    if (date === '2026-09-01' && division === 'd2') return [{ home: team('mont-st-billings', 'MSU Billings'), away: team('', 'No slug') }];
    return [];
  } };
  const index = new TeamIndex({ provider, store, onError: () => {} });
  const now = at('2026-10-13');
  while (await index.step(now));
  assert.equal(calls.length, seasonDates(now).length * 3);
  assert.equal(index.complete(now), false, 'the failed board is still missing');
  assert.deepEqual(index.teams().map(t => [t.slug, t.division]), [['boise-st', 'd1'], ['idaho-st', 'd1'], ['mont-st-billings', 'd2']]);
  // A day later, only the unsettled two-week window and the failed board are read again.
  calls.length = 0; failing = false;
  while (await index.step(now + 86400000 + 3600000));
  assert.ok(calls.includes('2026-08-11/d2'));
  assert.ok(calls.includes('2026-10-14/d1') && calls.includes('2026-10-28/d3'));
  assert.ok(!calls.includes('2026-10-29/d1') && !calls.includes('2026-09-01/d1'));
  assert.equal(index.complete(now), true);
});
test('standings keep conference and overall records from merged NCAA headers', () => {
  const standings = normalizeStandings({ updated: 'Oct 12, 2026', data: [
    { conference: 'Big Ten', standings: [{ School: 'Nebraska', 'Conference W': '8', 'Conference L': '0', 'Conference PCT': '1.000', 'Overall W': '18', 'Overall L': '1', 'Overall STREAK': 'Won 9' }] },
    { conference: '', standings: [] }
  ] }, 'd1');
  assert.deepEqual(standings.conferences.map(c => c.name), ['Big Ten']);
  const [row] = standings.conferences[0].teams;
  assert.equal(row.school, 'Nebraska'); assert.equal(row.conference, '8-0'); assert.equal(row.overall, '18-1');
  assert.deepEqual(row.stats.at(-1), { label: 'Overall STREAK', value: 'Won 9' });
  assert.throws(() => normalizeStandings({}, 'd1'));
});

const t2 = (slug, conference) => ({ slug, name: slug.toUpperCase(), abbreviation: slug, conference, conferenceName: '' });
const final = (id, home, away, homeWins, division = 'd1') => ({ id, division, status: 'final', home: { ...home, winner: homeWins, setsWon: homeWins ? 3 : 1 }, away: { ...away, winner: !homeWins, setsWon: homeWins ? 1 : 3 } });
test('standings are counted from final scores by conference when NCAA.com has none', async t => {
  const store = new Store(':memory:'); t.after(() => store.close());
  const neb = t2('nebraska', 'big-ten'), wis = t2('wisconsin', 'big-ten-'), pitt = t2('pittsburgh', 'acc'), ind = t2('free-agents', '');
  const boards = {
    '2026-09-01/d1': [final('1', neb, pitt, true), final('2', wis, ind, true)],
    '2026-10-01/d1': [final('3', neb, wis, true), { id: '4', status: 'scheduled', home: pitt, away: wis }],
    // The same cross-division match shows on both boards and counts once.
    '2026-10-01/d2': [final('3', neb, wis, true)]
  };
  const index = new TeamIndex({ provider: { board: async (date, division) => boards[`${date}/${division}`] ?? [] }, store, onError: () => {} });
  const now = at('2026-10-13');
  while (await index.step(now));
  const table = index.standings('d1');
  assert.equal(table.source, 'scores'); assert.equal(table.updated, 'final scores through 2026-10-01');
  assert.deepEqual(table.conferences.map(c => c.name), ['ACC', 'Big Ten', 'Independent']);
  assert.deepEqual(table.conferences[1].teams.map(r => [r.school, r.conference, r.overall]), [['NEBRASKA', '1-0', '2-0'], ['WISCONSIN', '0-1', '1-1']]);
  assert.deepEqual(table.conferences[0].teams.map(r => [r.conference, r.overall]), [['0-0', '0-1']]);
  assert.equal(conferenceName('atlantic-10'), 'Atlantic 10'); assert.equal(conferenceName('big-ten'), 'Big Ten');
  assert.equal(conferenceName('uac-'), 'UAC'); assert.equal(conferenceName('amcc'), 'AMCC');
});
test('boards indexed before results were kept are read again', () => {
  const store = new Store(':memory:');
  store.db.exec("UPDATE team_days SET games=NULL");
  store.db.prepare("INSERT INTO team_days (date,division,teams,fetchedAt,games) VALUES ('2026-09-01','d1','[]',1,NULL)").run();
  assert.equal(store.teamDays().size, 0);
  store.setTeamDay('2026-09-01', 'd1', [], 2);
  assert.equal(store.teamDays().get('2026-09-01/d1'), 2);
  store.close();
});
