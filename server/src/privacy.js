const escape = value => String(value).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);

// Served at /privacy so the App Store listing can link to it. Keep it in step with what the server stores.
export function privacyPage(contact) {
  const reach = contact ? `email <a href="mailto:${escape(contact)}">${escape(contact)}</a>` : 'use the support contact on the App Store listing';
  return `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Sideout Privacy Policy</title><style>body{font:16px/1.55 -apple-system,system-ui,sans-serif;max-width:680px;margin:40px auto;padding:0 20px;color:#1f3329;background:#f5f2e8}h1{font-family:Georgia,serif}</style></head><body>
<h1>Sideout Privacy Policy</h1>
<p>Sideout shows college volleyball scores and lets you follow a match on your Lock Screen. It has no accounts, ads, analytics or tracking.</p>
<h2>What stays on your iPhone</h2>
<p>Your favorite teams and app settings are stored on your device. If you turn on automatic Lock Screen scoreboards, the app also shares your favorite teams with the Sideout server, as described below.</p>
<h2>On Android</h2>
<p>The Android app keeps your favorite teams, followed matches and settings on your phone only. It asks the server for scores and sends nothing else, so none of the records below are created for Android phones.</p>
<h2>What the Sideout server stores</h2>
<ul>
<li><strong>A random install ID and secret.</strong> The app creates these the first time it needs lock-screen updates, so only your iPhone can start or stop its own followed matches. They are not linked to your name, Apple ID, or any other identifier. An install ID unused for 180 days is deleted.</li>
<li><strong>Live Activity push tokens and the match you follow.</strong> Apple issues the token for one Lock Screen scoreboard. Sideout uses it only to send that match's score updates through Apple Push Notification service. It is deleted when the match ends, when you stop following, or after eight hours at most.</li>
<li><strong>Favorite teams for automatic scoreboards.</strong> If automatic Lock Screen scoreboards are on, the server keeps your favorite teams' identifiers and a push-to-start token Apple issues for Sideout, so it can start a scoreboard 30 minutes before those teams play. It also remembers which matches it started for you for three days, so a dismissed scoreboard is not started again. Turning the setting off or removing all favorites deletes the teams and token.</li>
<li><strong>Network addresses.</strong> Your IP address is held in memory for up to an hour to limit abusive request rates. It is not written to disk or logged with your install ID.</li>
</ul>
<h2>Sharing</h2>
<p>Sideout does not sell or share personal data. Push tokens are sent only to Apple to deliver updates. Score requests go from the Sideout server to its score provider and never include information about you.</p>
<h2>Children</h2>
<p>Sideout does not knowingly collect personal information from children.</p>
<h2>Contact</h2>
<p>To ask a question or request deletion, ${reach}. Deleting the app and its Live Activities also stops all updates; leftover records expire as described above.</p>
</body></html>`;
}
