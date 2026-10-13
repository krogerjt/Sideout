import { easternDate } from './provider.js';

export const DIVISIONS = ['d1', 'd2', 'd3'];
const DAY_MS = 86400000;

// The women's volleyball season runs mid-August to mid-December. Out of season, the most recent one is indexed.
export function seasonDates(now = Date.now()) {
  const today = easternDate(new Date(now));
  const year = Number(today.slice(0, 4)) - (Number(today.slice(5, 7)) < 8 ? 1 : 0);
  const dates = [];
  for (let day = Date.UTC(year, 7, 10); day <= Date.UTC(year, 11, 22); day += DAY_MS) dates.push(new Date(day).toISOString().slice(0, 10));
  return dates;
}
function daysBetween(a, b) { return Math.round((Date.parse(a) - Date.parse(b)) / DAY_MS); }

// Teams are whoever appears on a season scoreboard: every board is read once, and the next two weeks are re-read daily
// because schedules still change. Past boards never change, so after the first pass only a handful of requests a day remain.
export class TeamIndex {
  constructor({ provider, store, onError = console.error }) {
    this.provider = provider; this.store = store; this.onError = onError;
    this.retryAt = new Map(); this.tables = new Map();
  }

  // The next board to read, or null when the index is current.
  next(now = Date.now()) {
    const today = easternDate(new Date(now));
    const fetched = this.fetched ??= this.store.teamDays();
    for (const date of seasonDates(now)) {
      for (const division of DIVISIONS) {
        if (this.retryAt.get(`${date}/${division}`) > now) continue;
        const at = fetched.get(`${date}/${division}`);
        const ahead = daysBetween(date, today);
        // A board read before its date had passed may still gain or lose matches.
        const settled = at !== undefined && daysBetween(easternDate(new Date(at)), date) >= 1;
        if (at === undefined || (!settled && ahead >= -1 && ahead <= 14 && now - at > DAY_MS)) return { date, division };
      }
    }
    return null;
  }
  // Reads one board, so callers decide the pace and live score polling keeps priority on the shared provider queue.
  async step(now = Date.now()) {
    const next = this.next(now);
    if (!next) return false;
    try {
      // ncaa-api answers 404 for a day with no matches.
      const matches = await this.provider.board(next.date, next.division).catch(error => { if (/ 404$/.test(error.message)) return []; throw error; });
      const teams = matches.flatMap(m => [m.home, m.away]).filter(t => t.slug)
        .map(({ slug, name, abbreviation, conference = '', conferenceName = '' }) => ({ slug, name, abbreviation, conference, conferenceName }));
      // Final results, kept so standings can be counted when NCAA.com doesn't publish them.
      const games = matches.filter(m => m.status === 'final' && m.home.slug && m.away.slug).map(m => {
        const homeWon = m.home.winner || m.away.winner ? m.home.winner === true : (m.home.setsWon ?? 0) > (m.away.setsWon ?? 0);
        return { id: m.id, home: m.home.slug, away: m.away.slug, winner: homeWon ? m.home.slug : m.away.slug };
      }).filter(g => g.home !== g.away);
      this.store.setTeamDay(next.date, next.division, teams, now, games);
      this.fetched?.set(`${next.date}/${next.division}`, now);
      this.list = null; this.tables.clear();
      this.retryAt.delete(`${next.date}/${next.division}`);
    } catch (error) {
      this.onError(`Team index failed for ${next.date} ${next.division}: ${error.message}`);
      // Skip a failing board for an hour so it doesn't stall the rest.
      this.retryAt.set(`${next.date}/${next.division}`, now + 3600000);
    }
    return true;
  }
  // Complete once every board has been read at least once; daily refreshes don't count as missing.
  complete(now = Date.now()) {
    const fetched = this.fetched ??= this.store.teamDays();
    return seasonDates(now).every(date => DIVISIONS.every(division => fetched.has(`${date}/${division}`)));
  }

  // One entry per team, in the division whose scoreboard lists it most often, since cross-division matches appear on both.
  teams() {
    return this.list ??= this.build();
  }
  build() {
    const counts = new Map();
    for (const { division, teams } of this.store.teamDayRows()) {
      for (const team of teams) {
        const entry = counts.get(team.slug) ?? { team, divisions: {} };
        // Keep the newest name, and any conference a board reported.
        // Slugs sometimes carry a stray hyphen (West Georgia's is "uac-"), so trim them to group with the rest of the conference.
        const conference = (team.conference || '').replace(/^-+|-+$/g, '');
        entry.team = { ...team, conference: conference || entry.team.conference || '', conferenceName: team.conferenceName || entry.team.conferenceName || '' };
        entry.divisions[division] = (entry.divisions[division] ?? 0) + 1;
        counts.set(team.slug, entry);
      }
    }
    return [...counts.values()].map(({ team, divisions }) => ({
      ...team, division: Object.entries(divisions).sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]))[0][0]
    })).sort((a, b) => a.name.localeCompare(b.name));
  }
  // Records counted from final scores, grouped by conference. A match between two teams of the same conference counts
  // as a conference match, which is how NCAA.com counts it too except for the odd non-conference meeting of league rivals.
  standings(division) {
    if (!this.tables.has(division)) this.tables.set(division, this.count(division));
    return this.tables.get(division);
  }
  count(division) {
    const games = new Map();
    let latest = '';
    for (const row of this.store.teamDayRows()) {
      for (const game of row.games) games.set(game.id, game);
      if (row.games.length && row.date > latest) latest = row.date;
    }
    const teams = new Map(this.teams().map(team => [team.slug, team]));
    const records = new Map();
    const record = slug => records.get(slug) ?? records.set(slug, { w: 0, l: 0, cw: 0, cl: 0 }).get(slug);
    for (const game of games.values()) {
      const home = teams.get(game.home), away = teams.get(game.away);
      const conference = home?.conference && home.conference === away?.conference;
      for (const slug of [game.home, game.away]) {
        const r = record(slug), won = game.winner === slug;
        won ? r.w++ : r.l++;
        if (conference) won ? r.cw++ : r.cl++;
      }
    }
    const pct = (w, l) => w + l ? w / (w + l) : 0;
    const conferences = new Map();
    for (const team of teams.values()) {
      if (team.division !== division) continue;
      const r = records.get(team.slug) ?? { w: 0, l: 0, cw: 0, cl: 0 };
      const name = team.conferenceName || conferenceName(team.conference);
      if (!conferences.has(name)) conferences.set(name, []);
      conferences.get(name).push({ team, r });
    }
    return {
      division, source: 'scores', updated: latest ? `final scores through ${latest}` : '',
      conferences: [...conferences.entries()].sort(([a], [b]) => (a === 'Independent') - (b === 'Independent') || a.localeCompare(b)).map(([name, rows]) => ({
        name,
        teams: rows.sort((a, b) => pct(b.r.cw, b.r.cl) - pct(a.r.cw, a.r.cl) || b.r.cw - a.r.cw || pct(b.r.w, b.r.l) - pct(a.r.w, a.r.l) || b.r.w - a.r.w || a.team.name.localeCompare(b.team.name))
          .map(({ team, r }) => ({ school: team.name, conference: `${r.cw}-${r.cl}`, overall: `${r.w}-${r.l}`, stats: [] }))
      }))
    };
  }
  // Paces itself: quick while building, then an hourly check for the boards that need a daily refresh.
  run({ busyMs = 1500, idleMs = 3600000 } = {}) {
    let timer, stopped = false;
    const loop = async () => {
      const worked = await this.step().catch(() => false);
      if (!stopped) timer = setTimeout(loop, worked ? busyMs : idleMs);
    };
    timer = setTimeout(loop, busyMs);
    return () => { stopped = true; clearTimeout(timer); };
  }
}

const ACRONYMS = new Set(['acc', 'sec', 'asun', 'swac', 'meac', 'wac', 'caa', 'maac', 'nec', 'ovc', 'mvc', 'aac', 'cusa', 'usa', 'gliac', 'glvc', 'psac', 'cacc',
  'siac', 'ciaa', 'nsic', 'rmac', 'mec', 'gac', 'lsc', 'ccaa', 'gnac', 'pbc', 'sac', 'sunyac', 'nescac', 'oac', 'uaa', 'ccc', 'miac', 'wiac', 'iiac', 'nacc',
  'ncac', 'odac', 'scac', 'cciw', 'nac', 'mac', 'aec', 'uac', 'sliac', 'usac', 'cunyac', 'nwc', 'ccs', 'hcac', 'mascac', 'amcc']);
// Boards sometimes give only the conference's URL slug, e.g. "big-ten" or "acc".
export function conferenceName(seo) {
  if (!seo) return 'Independent';
  return seo.split('-').filter(Boolean).map(word => ACRONYMS.has(word) ? word.toUpperCase() : word[0].toUpperCase() + word.slice(1)).join(' ');
}
