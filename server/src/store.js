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
    CREATE TABLE IF NOT EXISTS devices (id TEXT PRIMARY KEY, secretHash TEXT NOT NULL, lastSeen INTEGER NOT NULL)`);
    // Databases created before per-device ownership gain the column; their rows have no owner and expire within eight hours.
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
  pruneDevices(olderThan) { this.db.prepare('DELETE FROM devices WHERE lastSeen < ?').run(olderThan); }
  close() { this.db.close(); }
}
