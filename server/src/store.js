import { DatabaseSync } from 'node:sqlite';
import { mkdirSync } from 'node:fs';
import { dirname } from 'node:path';

export class Store {
  constructor(path) {
    if (path !== ':memory:') mkdirSync(dirname(path), { recursive: true, mode: 0o700 });
    this.db = new DatabaseSync(path);
    this.db.exec(`PRAGMA journal_mode=WAL; CREATE TABLE IF NOT EXISTS subscriptions (
      id TEXT PRIMARY KEY, matchId TEXT NOT NULL, date TEXT NOT NULL, division TEXT NOT NULL,
      token TEXT NOT NULL, expiresAt INTEGER NOT NULL, lastState TEXT, lastSent INTEGER DEFAULT 0
    ); CREATE TABLE IF NOT EXISTS tombstones (id TEXT PRIMARY KEY, expiresAt INTEGER NOT NULL);
    CREATE TABLE IF NOT EXISTS devices (id TEXT PRIMARY KEY, secretHash TEXT NOT NULL, lastSeen INTEGER NOT NULL);
    CREATE TABLE IF NOT EXISTS autostarts (deviceId TEXT PRIMARY KEY, token TEXT NOT NULL, teams TEXT NOT NULL, updatedAt INTEGER NOT NULL);
    CREATE TABLE IF NOT EXISTS team_days (date TEXT NOT NULL, division TEXT NOT NULL, teams TEXT NOT NULL, fetchedAt INTEGER NOT NULL, PRIMARY KEY (date, division));
    CREATE TABLE IF NOT EXISTS launches (deviceId TEXT NOT NULL, matchId TEXT NOT NULL, at INTEGER NOT NULL, PRIMARY KEY (deviceId, matchId))`);
    // Databases created before per-device ownership gain the column; their rows have no owner and expire within eight hours.
    // Boards indexed before results were kept have no games; they are read again so standings can count them.
    if (!this.db.prepare('PRAGMA table_info(team_days)').all().some(c => c.name === 'games')) this.db.exec('ALTER TABLE team_days ADD COLUMN games TEXT');
    if (!this.db.prepare('PRAGMA table_info(subscriptions)').all().some(c => c.name === 'deviceId')) this.db.exec('ALTER TABLE subscriptions ADD COLUMN deviceId TEXT');
  }
  all() { return this.db.prepare('SELECT * FROM subscriptions').all(); }
  count(deviceId) {
    return deviceId === undefined ? this.db.prepare('SELECT COUNT(*) AS n FROM subscriptions').get().n
      : this.db.prepare('SELECT COUNT(*) AS n FROM subscriptions WHERE deviceId=?').get(deviceId).n;
  }
  put(s) {
    this.db.prepare(`INSERT INTO subscriptions (id,matchId,date,division,token,expiresAt,deviceId) VALUES (?,?,?,?,?,?,?)
      ON CONFLICT(id) DO UPDATE SET token=excluded.token, lastState=NULL, lastSent=0`).run(s.id,s.matchId,s.date,s.division,s.token,s.expiresAt,s.deviceId ?? null);
  }
  get(id) { return this.db.prepare('SELECT * FROM subscriptions WHERE id=?').get(id); }
  cancelled(id) {
    this.db.prepare('DELETE FROM tombstones WHERE expiresAt < ?').run(Date.now());
    return Boolean(this.db.prepare('SELECT id FROM tombstones WHERE id=?').get(id));
  }
  cancel(id) {
    this.db.prepare('INSERT OR REPLACE INTO tombstones (id,expiresAt) VALUES (?,?)').run(id, Date.now() + 86400000);
    this.remove(id);
  }
  remove(id) { this.db.prepare('DELETE FROM subscriptions WHERE id=?').run(id); }
  sent(id, state, timestamp, token) { this.db.prepare('UPDATE subscriptions SET lastState=?,lastSent=? WHERE id=? AND token=?').run(state,timestamp,id,token); }
  addDevice(id, secretHash, now = Date.now()) { this.db.prepare('INSERT INTO devices (id,secretHash,lastSeen) VALUES (?,?,?)').run(id, secretHash, now); }
  device(id) { return this.db.prepare('SELECT * FROM devices WHERE id=?').get(id); }
  seen(id, now = Date.now()) { this.db.prepare('UPDATE devices SET lastSeen=? WHERE id=?').run(now, id); }
  pruneDevices(olderThan) {
    this.db.prepare('DELETE FROM devices WHERE lastSeen < ?').run(olderThan);
    this.db.prepare('DELETE FROM autostarts WHERE deviceId NOT IN (SELECT id FROM devices)').run();
  }
  // Push-to-start registrations: one token per install plus the favorite teams it wants started automatically.
  setAutostart(deviceId, token, teams, now = Date.now()) {
    this.db.prepare(`INSERT INTO autostarts (deviceId,token,teams,updatedAt) VALUES (?,?,?,?)
      ON CONFLICT(deviceId) DO UPDATE SET token=excluded.token, teams=excluded.teams, updatedAt=excluded.updatedAt`).run(deviceId, token, JSON.stringify(teams), now);
  }
  autostart(deviceId) {
    const row = this.db.prepare('SELECT * FROM autostarts WHERE deviceId=?').get(deviceId);
    return row && { ...row, teams: JSON.parse(row.teams) };
  }
  autostarts() { return this.db.prepare('SELECT * FROM autostarts').all().map(row => ({ ...row, teams: JSON.parse(row.teams) })); }
  removeAutostart(deviceId, token) {
    if (token) this.db.prepare('DELETE FROM autostarts WHERE deviceId=? AND token=?').run(deviceId, token);
    else this.db.prepare('DELETE FROM autostarts WHERE deviceId=?').run(deviceId);
  }
  // A match is started at most once per install, so dismissing an auto-started activity keeps it gone.
  launched(deviceId, matchId) { return Boolean(this.db.prepare('SELECT 1 FROM launches WHERE deviceId=? AND matchId=?').get(deviceId, matchId)); }
  launch(deviceId, matchId, now = Date.now()) { this.db.prepare('INSERT OR REPLACE INTO launches (deviceId,matchId,at) VALUES (?,?,?)').run(deviceId, matchId, now); }
  pruneLaunches(olderThan) { this.db.prepare('DELETE FROM launches WHERE at < ?').run(olderThan); }
  // Teams seen on each scoreboard, which together make the searchable team list.
  setTeamDay(date, division, teams, now = Date.now(), games = []) {
    this.db.prepare(`INSERT INTO team_days (date,division,teams,fetchedAt,games) VALUES (?,?,?,?,?)
      ON CONFLICT(date,division) DO UPDATE SET teams=excluded.teams, fetchedAt=excluded.fetchedAt, games=excluded.games`).run(date, division, JSON.stringify(teams), now, JSON.stringify(games));
  }
  teamDays() { return new Map(this.db.prepare('SELECT date,division,fetchedAt FROM team_days WHERE games IS NOT NULL').all().map(r => [`${r.date}/${r.division}`, r.fetchedAt])); }
  teamDayRows() {
    return this.db.prepare('SELECT date,division,teams,games FROM team_days WHERE games IS NOT NULL').all()
      .map(r => ({ date: r.date, division: r.division, teams: JSON.parse(r.teams), games: JSON.parse(r.games) }));
  }
  close() { this.db.close(); }
}
