import { createServer } from 'node:http';
import { createHash, randomBytes, randomUUID, timingSafeEqual } from 'node:crypto';
import { pathToFileURL } from 'node:url';
import { NCAAProvider, validDate, easternDate } from './provider.js';
import { DEMO_ID, withDemo } from './demo.js';
import { APNs } from './apns.js';
import { Store } from './store.js';
import { pollSubscriptions } from './worker.js';
import { privacyPage } from './privacy.js';

const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const hash = value => createHash('sha256').update(value).digest('hex');
const DEVICE_ACTIVITY_LIMIT = 10;

// Fixed-window counters per client and bucket. Single instance, so memory is the source of truth.
export class RateLimiter {
  constructor(limits) { this.limits = limits; this.windows = new Map(); }
  allow(bucket, client, now = Date.now()) {
    const { max, windowMs } = this.limits[bucket];
    const key = `${bucket}:${client}`;
    let entry = this.windows.get(key);
    if (!entry || now - entry.start >= windowMs) {
      if (this.windows.size > 50000) this.prune(now);
      entry = { start: now, count: 0 };
      this.windows.set(key, entry);
    }
    return ++entry.count <= max;
  }
  prune(now = Date.now()) {
    for (const [key, entry] of this.windows) if (now - entry.start >= this.limits[key.split(':')[0]].windowMs) this.windows.delete(key);
  }
}
async function body(req) {
  let result = '';
  for await (const chunk of req) { result += chunk; if (Buffer.byteLength(result) > 8192) throw new Error('Request too large'); }
  return JSON.parse(result);
}
// Credentials are "<deviceId>.<secret>"; only a hash of the secret is stored.
function authenticate(req, store) {
  const match = /^Bearer ([0-9a-f-]{36})\.([0-9a-f]{64})$/i.exec(req.headers.authorization || '');
  const device = match && store.device(match[1].toLowerCase());
  if (!device) return null;
  const supplied = Buffer.from(hash(match[2].toLowerCase()), 'hex'), expected = Buffer.from(device.secretHash, 'hex');
  if (supplied.length !== expected.length || !timingSafeEqual(supplied, expected)) return null;
  store.seen(device.id);
  return device.id;
}
export function createApp({ provider, store, apns, limiter = new RateLimiter(defaultLimits()), clientIPHeader = process.env.CLIENT_IP_HEADER,
  maxActivities = Number(process.env.MAX_ACTIVITIES) || 5000, privacyContact = process.env.PRIVACY_CONTACT }) {
  return createServer(async (req, res) => {
    const send = (status, value, cache = 'no-store') => { res.writeHead(status, { 'content-type': 'application/json', 'cache-control': cache }); res.end(JSON.stringify(value)); };
    try {
      const url = new URL(req.url, 'http://localhost');
      const client = (clientIPHeader && String(req.headers[clientIPHeader.toLowerCase()] || '').split(',')[0].trim()) || req.socket.remoteAddress;
      if (url.pathname === '/health' && req.method === 'GET') return send(200, { ok: true });
      if (url.pathname === '/privacy' && req.method === 'GET') {
        res.writeHead(200, { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'public, max-age=3600' });
        return res.end(privacyPage(privacyContact));
      }
      if (!limiter.allow('requests', client)) return send(429, { error: 'Too many requests. Please wait a minute and retry.' });
      if (url.pathname === '/v1/config' && req.method === 'GET') return send(200, { pushEnabled: apns.ready, source: 'NCAA via ncaa-api', pollSeconds: Number(process.env.POLL_SECONDS || 20), demoMatchId: DEMO_ID });
      if ((url.pathname === '/v1/matches' || /^\/v1\/matches\/(\d+|demo)$/.test(url.pathname)) && req.method === 'GET') {
        const date = url.searchParams.get('date') || easternDate();
        const division = url.searchParams.get('division') || 'd1';
        if (!validDate(date) || !['d1','d2','d3'].includes(division)) return send(400, { error: 'Use date YYYY-MM-DD and division d1, d2, or d3.' });
        try {
          if (url.pathname === '/v1/matches') return send(200, { matches: await provider.board(date, division), source: 'NCAA via ncaa-api' }, 'public, max-age=10');
          return send(200, await provider.match(date, division, url.pathname.split('/').pop()), 'public, max-age=10');
        } catch { return send(502, { error: 'The NCAA score feed is temporarily unavailable. Please retry.' }); }
      }
      if (url.pathname === '/v1/devices' && req.method === 'POST') {
        if (!limiter.allow('devices', client)) return send(429, { error: 'Too many new installs from this network. Please retry later.' });
        const id = randomUUID(), secret = randomBytes(32).toString('hex');
        store.addDevice(id, hash(secret));
        return send(201, { deviceId: id, secret });
      }
      const id = url.pathname.split('/').pop();
      if (url.pathname.startsWith('/v1/activities/') && uuid.test(id) && ['PUT', 'DELETE'].includes(req.method)) {
        const deviceId = authenticate(req, store);
        if (!deviceId) return send(401, { error: 'This iPhone needs to reconnect to Sideout.' });
        const existing = store.get(id);
        // Another device's subscription is indistinguishable from a missing one.
        if (existing && existing.deviceId !== deviceId) return send(404, { error: 'Not found' });
        if (req.method === 'DELETE') { store.cancel(id); return send(200, { ok: true }); }
        if (!apns.ready) return send(503, { error: 'Lock-screen updates are temporarily unavailable.' });
        let data; try { data = await body(req); } catch { return send(400, { error: 'Invalid JSON request.' }); }
        if (!data || typeof data !== 'object') return send(400, { error: 'Invalid JSON request.' });
        if (!/^[a-f0-9]{32,512}$/i.test(data.token || '') || !/^(\d{1,12}|demo)$/.test(data.matchId || '') || !validDate(data.date || '') || !['d1','d2','d3'].includes(data.division)) return send(400, { error: 'Invalid activity registration.' });
        if (existing && (existing.matchId !== data.matchId || existing.date !== data.date || existing.division !== data.division)) return send(409, { error: 'Activity already belongs to another match.' });
        const full = () => store.count(deviceId) >= DEVICE_ACTIVITY_LIMIT ? `You can follow up to ${DEVICE_ACTIVITY_LIMIT} matches at once.`
          : store.count() >= maxActivities ? 'Sideout is at capacity right now. Please try again later.' : null;
        if (!existing && full()) return send(429, { error: full() });
        let match;
        try { match = await provider.match(data.date, data.division, data.matchId); } catch { return send(502, { error: 'Could not verify this match. Retry shortly.' }); }
        if (store.cancelled(id)) return send(410, { error: 'This activity has been stopped.' });
        if (!store.get(id) && full()) return send(429, { error: full() });
        // A match may finish while the iPhone is still obtaining its push token.
        if (!existing && !['live', 'final', 'cancelled', 'postponed'].includes(match.status)) return send(409, { error: 'Live Activities can be started for matches in progress.' });
        store.put({ id, ...data, deviceId, expiresAt: Date.now() + 8 * 60 * 60 * 1000 });
        return send(200, { ok: true });
      }
      send(404, { error: 'Not found' });
    } catch (error) {
      console.error(`Request failed: ${error.message}`);
      if (!res.headersSent) send(500, { error: 'Server error. Please retry.' });
    }
  });
}
export function defaultLimits(env = process.env) {
  return {
    requests: { max: Number(env.RATE_LIMIT_PER_MINUTE) || 120, windowMs: 60000 },
    devices: { max: Number(env.DEVICE_LIMIT_PER_HOUR) || 20, windowMs: 3600000 }
  };
}
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const store = new Store(process.env.DATA_PATH || './data/sideout.sqlite');
  const provider = withDemo(new NCAAProvider()); const apns = new APNs();
  const limiter = new RateLimiter(defaultLimits());
  const app = createApp({ provider, store, apns, limiter });
  let stopped = false, timer;
  async function tick() {
    await pollSubscriptions({ store, provider, apns });
    store.pruneDevices(Date.now() - 180 * 86400000);
    limiter.prune();
    if (!stopped) timer = setTimeout(tick, Math.max(15, Number(process.env.POLL_SECONDS) || 20) * 1000);
  }
  app.listen(Number(process.env.PORT) || 8787, process.env.HOST || '127.0.0.1', () => {
    console.log(`Sideout listening on port ${Number(process.env.PORT) || 8787}. Background pushes: ${apns.ready ? 'enabled' : 'not configured'}.`);
    tick();
  });
  for (const signal of ['SIGTERM', 'SIGINT']) process.on(signal, () => {
    stopped = true; clearTimeout(timer); apns.close?.(); app.close(() => process.exit(0));
  });
}
