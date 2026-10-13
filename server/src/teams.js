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
    this.retryAt = new Map();
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
      const teams = matches.flatMap(m => [m.home, m.away]).filter(t => t.slug).map(({ slug, name, abbreviation }) => ({ slug, name, abbreviation }));
      this.store.setTeamDay(next.date, next.division, teams, now);
      this.fetched?.set(`${next.date}/${next.division}`, now);
      this.list = null;
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
        entry.team = team;
        entry.divisions[division] = (entry.divisions[division] ?? 0) + 1;
        counts.set(team.slug, entry);
      }
    }
    return [...counts.values()].map(({ team, divisions }) => ({
      ...team, division: Object.entries(divisions).sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]))[0][0]
    })).sort((a, b) => a.name.localeCompare(b.name));
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
