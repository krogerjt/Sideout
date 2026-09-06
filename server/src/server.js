import { createServer } from 'node:http';
import { timingSafeEqual } from 'node:crypto';
import { pathToFileURL } from 'node:url';
import { NCAAProvider, validDate, easternDate } from './provider.js';
import { APNs } from './apns.js';
import { Store } from './store.js';
import { pollSubscriptions } from './worker.js';

const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
function authorized(req, accessKey) {
  const supplied = Buffer.from(req.headers.authorization || '');
  const expected = Buffer.from(`Bearer ${accessKey}`);
  return supplied.length === expected.length && timingSafeEqual(supplied, expected);
}
async function body(req) {
  let result = '';
  for await (const chunk of req) { result += chunk; if (Buffer.byteLength(result) > 8192) throw new Error('Request too large'); }
  return JSON.parse(result);
}
export function createApp({ provider, store, apns, accessKey }) {
  return createServer(async (req, res) => {
    const send = (status, value) => { res.writeHead(status, { 'content-type': 'application/json', 'cache-control': 'no-store' }); res.end(JSON.stringify(value)); };
    try {
      const url = new URL(req.url, 'http://localhost');
      if (url.pathname === '/health' && req.method === 'GET') return send(200, { ok: true });
      if (!authorized(req, accessKey)) return send(401, { error: 'Enter the correct server access key in Settings.' });
      if (url.pathname === '/v1/config' && req.method === 'GET') return send(200, { pushEnabled: apns.ready, source: 'NCAA via ncaa-api', pollSeconds: Number(process.env.POLL_SECONDS || 20) });
      if ((url.pathname === '/v1/matches' || /^\/v1\/matches\/\d+$/.test(url.pathname)) && req.method === 'GET') {
        const date = url.searchParams.get('date') || easternDate();
        const division = url.searchParams.get('division') || 'd1';
        if (!validDate(date) || !['d1','d2','d3'].includes(division)) return send(400, { error: 'Use date YYYY-MM-DD and division d1, d2, or d3.' });
        try {
          if (url.pathname === '/v1/matches') return send(200, { matches: await provider.board(date, division), source: 'NCAA via ncaa-api' });
          return send(200, await provider.match(date, division, url.pathname.split('/').pop()));
        } catch { return send(502, { error: 'The NCAA score feed is temporarily unavailable. Please retry.' }); }
      }
      const id = url.pathname.split('/').pop();
      if (url.pathname.startsWith('/v1/activities/') && uuid.test(id)) {
        if (req.method === 'DELETE') { store.cancel(id); return send(200, { ok: true }); }
        if (req.method === 'PUT') {
          if (!apns.ready) return send(503, { error: 'Background updates require APNs configuration on your server.' });
          let data; try { data = await body(req); } catch { return send(400, { error: 'Invalid JSON request.' }); }
          if (!data || typeof data !== 'object') return send(400, { error: 'Invalid JSON request.' });
          if (!/^[a-f0-9]{32,512}$/i.test(data.token || '') || !/^\d{1,12}$/.test(data.matchId || '') || !validDate(data.date || '') || !['d1','d2','d3'].includes(data.division)) return send(400, { error: 'Invalid activity registration.' });
          const existing = store.get(id);
          if (existing && (existing.matchId !== data.matchId || existing.date !== data.date || existing.division !== data.division)) return send(409, { error: 'Activity already belongs to another match.' });
          if (!existing && store.all().length >= 20) return send(429, { error: 'This personal server supports up to 20 active activities.' });
          let match;
          try { match = await provider.match(data.date, data.division, data.matchId); } catch { return send(502, { error: 'Could not verify this match. Retry shortly.' }); }
          if (store.cancelled(id)) return send(410, { error: 'This activity has been stopped.' });
          if (!store.get(id) && store.all().length >= 20) return send(429, { error: 'This personal server supports up to 20 active activities.' });
          // A match may finish while the iPhone is still obtaining its push token.
          if (!existing && !['live', 'final', 'cancelled', 'postponed'].includes(match.status)) return send(409, { error: 'Live Activities can be started for matches in progress.' });
          store.put({ id, ...data, expiresAt: Date.now() + 8 * 60 * 60 * 1000 });
          return send(200, { ok: true });
        }
      }
      send(404, { error: 'Not found' });
    } catch (error) {
      console.error(`Request failed: ${error.message}`);
      if (!res.headersSent) send(500, { error: 'Server error. Please retry.' });
    }
  });
}
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const accessKey = process.env.ACCESS_KEY;
  if (!accessKey || accessKey.length < 32 || accessKey.startsWith('replace-')) throw new Error('Set ACCESS_KEY to at least 32 random characters in .env');
  const store = new Store(process.env.DATA_PATH || './data/sideout.sqlite');
  const provider = new NCAAProvider(); const apns = new APNs();
  const app = createApp({ provider, store, apns, accessKey });
  let stopped = false, timer;
  async function tick() {
    await pollSubscriptions({ store, provider, apns });
    if (!stopped) timer = setTimeout(tick, Math.max(15, Number(process.env.POLL_SECONDS) || 20) * 1000);
  }
  app.listen(Number(process.env.PORT) || 8787, process.env.HOST || '127.0.0.1', () => {
    console.log(`Sideout listening on port ${Number(process.env.PORT) || 8787}. Background pushes: ${apns.ready ? 'enabled' : 'not configured'}.`);
    tick();
  });
  for (const signal of ['SIGTERM', 'SIGINT']) process.on(signal, () => {
    stopped = true; clearTimeout(timer); app.close(() => process.exit(0));
  });
}
