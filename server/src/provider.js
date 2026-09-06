const integer = value => value !== '' && value != null && /^\d+$/.test(String(value)) ? Number(value) : null;
export function validDate(value) {
  return /^\d{4}-\d{2}-\d{2}$/.test(value) && !Number.isNaN(Date.parse(value)) && new Date(value).toISOString().slice(0, 10) === value;
}
export function easternDate(now = new Date()) {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'America/New_York', year: 'numeric', month: '2-digit', day: '2-digit' }).format(now);
}
export function statusOf(value) {
  const state = String(value || '').toLowerCase();
  if (['live', 'i', 'in progress'].includes(state)) return 'live';
  if (['final', 'f', 'o'].includes(state)) return 'final';
  if (['pre', 'p', 'scheduled'].includes(state)) return 'scheduled';
  if (/cancel/.test(state)) return 'cancelled';
  if (/postpon/.test(state)) return 'postponed';
  return 'unknown';
}
function team(raw) {
  if (!raw?.names?.short) throw new Error('Unexpected NCAA team format');
  return { name: raw.names.short, abbreviation: raw.names.char6 || raw.names.short, slug: raw.names.seo || '', setsWon: integer(raw.score), rank: integer(raw.rank), color: '#496653' };
}
export function normalizeBoard(raw, date, division, now = Date.now()) {
  if (!Array.isArray(raw.games)) throw new Error('Unexpected NCAA scoreboard format');
  return raw.games.map(({ game: g }) => {
    if (!g || !/^\d+$/.test(g.gameID)) throw new Error('Unexpected NCAA match format');
    const status = statusOf(g.gameState);
    return {
      id: String(g.gameID), date, division, away: team(g.away), home: team(g.home), status,
      statusText: g.currentPeriod || g.finalMessage || (status === 'scheduled' ? g.startTime || 'Time TBA' : status),
      startTime: integer(g.startTimeEpoch), sets: [], venue: '', checkedAt: Math.floor(now / 1000),
      detailsAvailable: false, sourceURL: `https://www.ncaa.com/game/${g.gameID}`
    };
  });
}
export function enrichMatch(match, raw, now = Date.now()) {
  const contest = raw.contests?.find(c => String(c.id) === match.id);
  if (!contest || !Array.isArray(contest.teams)) throw new Error('Match detail is unavailable');
  const home = contest.teams.find(t => t.isHome === true);
  const away = contest.teams.find(t => t.isHome === false);
  if (!home || !away) throw new Error('Incomplete match detail');
  const updateTeam = (base, t) => ({ ...base, setsWon: integer(t.score), color: /^#[a-fA-F0-9]{6}$/.test(t.color) ? t.color : base.color });
  const sets = (contest.linescores || []).map(s => ({ number: integer(s.period), home: integer(s.home), away: integer(s.visit) }))
    .filter(s => s.number >= 1 && s.number <= 5).sort((a, b) => a.number - b.number);
  return { ...match, home: updateTeam(match.home, home), away: updateTeam(match.away, away), sets,
    status: statusOf(contest.statusCodeDisplay || contest.gameState),
    statusText: contest.currentPeriod || contest.finalMessage || match.statusText,
    venue: contest.location?.venue || '', checkedAt: Math.floor(now / 1000), detailsAvailable: true };
}
export function contentState(match) {
  const period = /([1-5])/.exec(match.statusText)?.[1];
  const current = match.status === 'live' && period ? match.sets.find(s => s.number === Number(period)) : null;
  return { homeSets: match.home.setsWon, awaySets: match.away.setsWon,
    homePoints: current?.home ?? null, awayPoints: current?.away ?? null,
    status: match.status, statusText: match.statusText, checkedAt: match.checkedAt };
}
// Shared queue keeps this personal server below the public provider's 5 req/sec limit.
export class NCAAProvider {
  constructor({ baseURL = process.env.NCAA_BASE_URL || 'https://ncaa-api.henrygd.me', fetcher = fetch } = {}) {
    this.baseURL = baseURL.replace(/\/$/, ''); this.fetcher = fetcher;
    this.cache = new Map(); this.pending = new Map(); this.queue = Promise.resolve();
  }
  async get(path) {
    const cached = this.cache.get(path);
    if (cached && Date.now() - cached.at < 15000) return cached;
    if (this.pending.has(path)) return this.pending.get(path);
    const task = this.queue.then(async () => {
      const response = await this.fetcher(`${this.baseURL}${path}`, {
        signal: AbortSignal.timeout(12000), headers: { accept: 'application/json', ...(process.env.NCAA_HEADER_KEY ? { 'x-ncaa-key': process.env.NCAA_HEADER_KEY } : {}) }
      });
      if (!response.ok) throw new Error(`Score provider returned ${response.status}`);
      const entry = { raw: await response.json(), at: Date.now() };
      this.cache.set(path, entry);
      if (this.cache.size > 300) this.cache.delete(this.cache.keys().next().value);
      return entry;
    });
    this.queue = task.catch(() => {}).then(() => new Promise(resolve => setTimeout(resolve, 350)));
    this.pending.set(path, task);
    try { return await task; } finally { this.pending.delete(path); }
  }
  async board(date, division) {
    const { raw, at } = await this.get(`/scoreboard/volleyball-women/${division}/${date.replaceAll('-', '/')}`);
    return normalizeBoard(raw, date, division, at);
  }
  async match(date, division, id) {
    const board = await this.board(date, division);
    const match = board.find(m => m.id === id);
    if (!match) throw new Error('Match not found in this scoreboard');
    const { raw, at } = await this.get(`/game/${id}`);
    return enrichMatch(match, raw, Math.min(at, match.checkedAt * 1000));
  }
}
