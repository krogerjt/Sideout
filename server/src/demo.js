import { easternDate } from './provider.js';

// A simulated match for App Review and first-run users when no real match is live.
// It is derived from the clock alone, so every request and the push worker agree on the score.
export const DEMO_ID = 'demo';
const SLOT_MS = 20 * 60 * 1000;
const FINAL_MS = 90 * 1000;

function random(seed) {
  return () => {
    seed = (seed + 0x6D2B79F5) | 0;
    let t = Math.imul(seed ^ (seed >>> 15), 1 | seed);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}
function rallies(slot) {
  const next = random(slot), winners = [];
  let home = 0, away = 0, setsHome = 0, setsAway = 0;
  while (setsHome < 3 && setsAway < 3) {
    const target = setsHome + setsAway === 4 ? 15 : 25;
    const homeWins = next() < 0.5;
    winners.push(homeWins);
    homeWins ? home++ : away++;
    if (Math.max(home, away) >= target && Math.abs(home - away) >= 2) {
      home > away ? setsHome++ : setsAway++;
      home = 0; away = 0;
    }
  }
  return winners;
}
export function demoMatch(now = Date.now()) {
  const slot = Math.floor(now / SLOT_MS);
  const winners = rallies(slot);
  const played = Math.min(winners.length, Math.floor((now - slot * SLOT_MS) / ((SLOT_MS - FINAL_MS) / winners.length)));
  const sets = [{ number: 1, home: 0, away: 0 }];
  let setsHome = 0, setsAway = 0;
  for (const homeWins of winners.slice(0, played)) {
    const set = sets.at(-1);
    homeWins ? set.home++ : set.away++;
    const target = set.number === 5 ? 15 : 25;
    if (Math.max(set.home, set.away) >= target && Math.abs(set.home - set.away) >= 2) {
      set.home > set.away ? setsHome++ : setsAway++;
      if (setsHome < 3 && setsAway < 3) sets.push({ number: set.number + 1, home: 0, away: 0 });
    }
  }
  const final = played === winners.length;
  const ordinal = ['1ST', '2ND', '3RD', '4TH', '5TH'][sets.length - 1];
  const team = (name, abbreviation, setsWon, color) => ({ name, abbreviation, slug: '', setsWon, rank: null, color });
  return {
    id: DEMO_ID, date: easternDate(new Date(now)), division: 'd1',
    away: team('Harbor State (Demo)', 'HARBOR', setsAway, '#2F5C43'),
    home: team('Ridge Valley (Demo)', 'RIDGE', setsHome, '#496653'),
    status: final ? 'final' : 'live',
    statusText: final ? 'FINAL · NEW DEMO SOON' : `DEMO · ${ordinal} SET`,
    startTime: Math.floor(slot * SLOT_MS / 1000), sets, venue: 'Simulated match for trying Sideout',
    checkedAt: Math.floor(now / 1000), detailsAvailable: true, sourceURL: ''
  };
}
// Wraps a real provider so the demo match id resolves everywhere a match is looked up.
export function withDemo(provider, clock = () => Date.now()) {
  return {
    board: (...args) => provider.board(...args),
    standings: (...args) => provider.standings(...args),
    match: async (date, division, id) => id === DEMO_ID ? demoMatch(clock()) : provider.match(date, division, id)
  };
}
