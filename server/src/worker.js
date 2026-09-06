import { contentState } from './provider.js';

export async function pollSubscriptions({ store, provider, apns, now = Date.now(), onError = console.error }) {
  const matches = new Map();
  for (const sub of store.all()) {
    if (sub.expiresAt <= now) { store.remove(sub.id); continue; }
    if (!apns.ready) continue;
    try {
      const key = `${sub.date}/${sub.division}/${sub.matchId}`;
      if (!matches.has(key)) matches.set(key, provider.match(sub.date, sub.division, sub.matchId));
      const match = await matches.get(key);
      const state = contentState(match);
      const fingerprint = JSON.stringify({ ...state, checkedAt: 0 });
      const end = ['final', 'cancelled', 'postponed'].includes(match.status);
      // Heartbeat advances stale-date only after a successful provider fetch.
      if (!end && sub.lastState === fingerprint && now - sub.lastSent < 60000) continue;
      if (store.get(sub.id)?.token !== sub.token) continue;
      const result = await apns.send(sub, state, end);
      if (store.get(sub.id)?.token !== sub.token) continue;
      if (result.expired || end) store.remove(sub.id);
      else store.sent(sub.id, fingerprint, now, sub.token);
    } catch (error) { onError(`Score update failed: ${error.message}`); }
  }
}
