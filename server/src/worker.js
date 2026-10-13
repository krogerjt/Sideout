import { randomUUID } from 'node:crypto';
import { contentState, easternDate } from './provider.js';

export const AUTOSTART_LEAD_MS = 30 * 60 * 1000;
export const DEVICE_ACTIVITY_LIMIT = 10;

export async function pollSubscriptions({ store, provider, apns, now = Date.now(), onError = console.error, onLog = console.log }) {
  const matches = new Map();
  for (const sub of store.all()) {
    if (sub.expiresAt <= now) { store.remove(sub.id); continue; }
    // Auto-started activities wait here until the iPhone registers their update token.
    if (!apns.ready || !sub.token) continue;
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
      // Score changes go out at high priority: iOS may hold low-priority pushes for minutes, long enough for the activity to go stale.
      const result = await apns.send(sub, state, end, sub.lastState !== fingerprint);
      if (store.get(sub.id)?.token !== sub.token) continue;
      if (result.expired) onLog(`Stopped updating activity ${sub.id.slice(0, 8)} (match ${sub.matchId}): APNs rejected its token (${result.reason || 'expired'}).`);
      else if (!sub.lastSent) onLog(`First update delivered to APNs for activity ${sub.id.slice(0, 8)} (match ${sub.matchId}).`);
      if (result.expired || end) store.remove(sub.id);
      else store.sent(sub.id, fingerprint, now, sub.token);
    } catch (error) { onError(`Score update failed: ${error.message}`); }
  }
}

// Boards to scan: today in Eastern Time, plus yesterday before 6 AM so late West Coast and Hawaii matches are not missed.
export function autostartDates(now = Date.now()) {
  const hour = Number(new Intl.DateTimeFormat('en-US', { timeZone: 'America/New_York', hour: 'numeric', hourCycle: 'h23' }).format(new Date(now)));
  const today = easternDate(new Date(now));
  return hour < 6 ? [easternDate(new Date(now - 86400000)), today] : [today];
}
// Starts at most once per install and match: from 30 minutes before the scheduled first serve, or as soon as a match is live.
export function autostartDue(match, now = Date.now(), lead = AUTOSTART_LEAD_MS) {
  if (match.status === 'live') return true;
  if (match.status !== 'scheduled' || !match.startTime) return false;
  const start = match.startTime * 1000;
  return now >= start - lead && now < start + 3 * 60 * 60 * 1000;
}
export async function startFavorites({ store, provider, apns, now = Date.now(), lead = AUTOSTART_LEAD_MS, onError = console.error }) {
  if (!apns.ready) return;
  const registrations = store.autostarts();
  if (!registrations.length) return;
  const boards = new Map();
  const board = (date, division) => {
    const key = `${date}/${division}`;
    if (!boards.has(key)) boards.set(key, provider.board(date, division).catch(error => { onError(`Auto-start schedule check failed: ${error.message}`); return []; }));
    return boards.get(key);
  };
  for (const registration of registrations) {
    try {
      scan: for (const date of autostartDates(now)) {
        for (const division of new Set(registration.teams.map(team => team.division))) {
          const slugs = new Set(registration.teams.filter(team => team.division === division).map(team => team.slug));
          for (const match of await board(date, division)) {
            if (!slugs.has(match.home.slug) && !slugs.has(match.away.slug)) continue;
            if (!autostartDue(match, now, lead) || store.launched(registration.deviceId, match.id)) continue;
            if (store.count(registration.deviceId) >= DEVICE_ACTIVITY_LIMIT) continue;
            const id = randomUUID();
            const attributes = { subscriptionID: id, matchID: match.id, date: match.date, division: match.division,
              homeName: match.home.name, awayName: match.away.name, homeAbbreviation: match.home.abbreviation, awayAbbreviation: match.away.abbreviation };
            const alert = { title: `${match.away.name} at ${match.home.name}`,
              body: match.status === 'live' ? 'Underway now. Following on your Lock Screen.' : 'Starts soon. Following on your Lock Screen.' };
            const result = await apns.start(registration.token, attributes, contentState(match), alert);
            if (result.expired) { store.removeAutostart(registration.deviceId, registration.token); break scan; }
            store.launch(registration.deviceId, match.id, now);
            // The row owns the activity for this install; its update token arrives when the app registers it.
            store.put({ id, matchId: match.id, date: match.date, division: match.division, token: '', deviceId: registration.deviceId, expiresAt: now + 8 * 60 * 60 * 1000 });
          }
        }
      }
    } catch (error) { onError(`Auto-start failed: ${error.message}`); }
  }
}
