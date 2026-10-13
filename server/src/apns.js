import { connect } from 'node:http2';
import { createPrivateKey, sign } from 'node:crypto';
import { readFileSync } from 'node:fs';

export function activityPayload(state, end = false, now = Math.floor(Date.now() / 1000)) {
  const aps = { timestamp: now, event: end ? 'end' : 'update', 'content-state': state };
  if (end) aps['dismissal-date'] = now + 1800;
  else aps['stale-date'] = state.checkedAt + 120;
  return { aps };
}
// Push-to-start (iOS 17.2+): the system creates the Live Activity from these attributes, then wakes the app to register its update token.
export function startPayload(attributes, state, alert, now = Math.floor(Date.now() / 1000)) {
  return { aps: { timestamp: now, event: 'start', 'content-state': state, 'stale-date': state.checkedAt + 120,
    'attributes-type': 'VolleyballActivityAttributes', attributes, alert } };
}
export class APNs {
  constructor(env = process.env) {
    this.env = env;
    this.ready = Boolean(env.APNS_TEAM_ID && env.APNS_KEY_ID && (env.APNS_PRIVATE_KEY || env.APNS_PRIVATE_KEY_PATH) && env.APNS_BUNDLE_ID);
    if (this.ready) {
      if (!['development', 'production'].includes(env.APNS_ENVIRONMENT)) throw new Error('Set APNS_ENVIRONMENT to development or production');
      // Hosts that only take secrets as environment variables can pass the .p8 contents directly.
      this.key = createPrivateKey(env.APNS_PRIVATE_KEY ? env.APNS_PRIVATE_KEY.replaceAll('\\n', '\n') : readFileSync(env.APNS_PRIVATE_KEY_PATH));
    }
  }
  // One HTTP/2 connection per APNs host is reused for every push, as Apple recommends; it reopens after errors or GOAWAY.
  connection(environment = this.env.APNS_ENVIRONMENT) {
    this.clients ??= new Map();
    const existing = this.clients.get(environment);
    if (existing && !existing.closed && !existing.destroyed) return existing;
    const client = connect(`https://${environment === 'production' ? 'api.push.apple.com' : 'api.sandbox.push.apple.com'}`);
    const drop = () => { if (this.clients.get(environment) === client) this.clients.delete(environment); };
    client.on('error', drop); client.on('goaway', drop); client.on('close', drop);
    client.setTimeout(10 * 60 * 1000, () => client.close());
    this.clients.set(environment, client);
    return client;
  }
  close() { for (const client of this.clients?.values() ?? []) client.close(); this.clients?.clear(); }
  jwt() {
    const now = Math.floor(Date.now() / 1000);
    if (this.cachedJWT && now - this.issuedAt < 3000) return this.cachedJWT;
    const encode = value => Buffer.from(JSON.stringify(value)).toString('base64url');
    const unsigned = `${encode({ alg: 'ES256', kid: this.env.APNS_KEY_ID })}.${encode({ iss: this.env.APNS_TEAM_ID, iat: now })}`;
    this.cachedJWT = `${unsigned}.${sign('sha256', Buffer.from(unsigned), { key: this.key, dsaEncoding: 'ieee-p1363' }).toString('base64url')}`;
    this.issuedAt = now;
    return this.cachedJWT;
  }
  // Score changes go out at priority 10 so iOS shows them promptly (the app declares frequent updates); unchanged heartbeats use 5.
  send(subscription, state, end = false, { changed = true } = {}) {
    return this.push(subscription.token, activityPayload(state, end), { priority: end || changed ? '10' : '5', ttl: end ? 3600 : 120, collapseId: subscription.id });
  }
  start(token, attributes, state, alert) {
    return this.push(token, startPayload(attributes, state, alert), { priority: '10', ttl: 600 });
  }
  // Xcode builds get sandbox tokens and TestFlight/App Store builds get production ones. A token Apple rejects as BadDeviceToken
  // is retried once on the other host, and the host that accepts it is remembered, so a server set to the wrong
  // APNS_ENVIRONMENT keeps working instead of silently dropping every activity it was asked to update.
  async push(token, payload, options) {
    if (!this.ready) throw new Error('APNs is not configured');
    this.tokenEnvironments ??= new Map();
    const configured = this.env.APNS_ENVIRONMENT, other = configured === 'production' ? 'development' : 'production';
    const first = this.tokenEnvironments.get(token) ?? configured;
    const result = await this.request(first, token, payload, options);
    if (result.reason !== 'BadDeviceToken') return result;
    const second = first === configured ? other : configured;
    let retry;
    try { retry = await this.request(second, token, payload, options); } catch (error) { retry = { error }; }
    if (retry.ok) {
      if (this.tokenEnvironments.size > 20000) this.tokenEnvironments.clear();
      this.tokenEnvironments.set(token, second);
      if (second !== configured && !this.warned) {
        this.warned = true;
        console.warn(`APNs: a device token belongs to the ${second} environment but APNS_ENVIRONMENT is ${configured}. Sending to ${second} for those tokens; set APNS_ENVIRONMENT=${second} to match TestFlight/App Store builds.`);
      }
      return retry;
    }
    return result;
  }
  request(environment, token, payload, { priority, ttl, collapseId }) {
    const body = JSON.stringify(payload);
    return new Promise((resolve, reject) => {
      const client = this.connection(environment);
      let settled = false;
      const finish = (error, result) => {
        if (settled) return; settled = true;
        clearTimeout(timer); client.off('error', onClientError);
        if (error && !request.closed) request.close();
        error ? reject(error) : resolve(result);
      };
      const onClientError = error => finish(error);
      const timer = setTimeout(() => { finish(new Error('APNs timed out')); client.destroy(); }, 12000);
      client.on('error', onClientError);
      const request = client.request({ ':method': 'POST', ':path': `/3/device/${token}`,
        authorization: `bearer ${this.jwt()}`, 'apns-topic': `${this.env.APNS_BUNDLE_ID}.push-type.liveactivity`,
        'apns-push-type': 'liveactivity', 'apns-priority': priority,
        'apns-expiration': String(Math.floor(Date.now() / 1000) + ttl),
        ...(collapseId ? { 'apns-collapse-id': collapseId } : {}), 'content-type': 'application/json' });
      let status, response = '';
      request.on('response', headers => { status = headers[':status']; });
      request.setEncoding('utf8'); request.on('data', data => { response += data; });
      request.on('error', error => finish(error));
      request.on('end', () => {
        let reason = ''; try { reason = JSON.parse(response).reason || ''; } catch {}
        if (status === 200) finish(null, { ok: true });
        else if (status === 410 || ['BadDeviceToken', 'Unregistered'].includes(reason)) finish(null, { expired: true, reason });
        else finish(new Error(`APNs ${status}: ${reason || 'request rejected'}`));
      });
      request.end(body);
    });
  }
}
