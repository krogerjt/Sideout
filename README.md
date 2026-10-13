# Sideout

A native iPhone app for NCAA women's volleyball, with set scores, favorites, match details, Lock Screen Live Activities, and Dynamic Island support. Browse Divisions I, II, and III; schedules use Eastern Time.

## What's built

- SwiftUI app targeting iOS 17 or newer, with search, date and division filters, favorite teams, pull to refresh, and foreground refresh every 20 seconds.
- WidgetKit / ActivityKit extension showing sets won and current-set points, with a stale indicator after two minutes without a successful feed check.
- Node.js server that reads NCAA scoreboards and match details, stores activity subscriptions in SQLite, and updates or ends activities using Apple's HTTP/2 APNs service.
- Public score API with per-install credentials, rate limits, a simulated demo match, a hosted privacy policy, and Fly.io deployment config.
- Push token rotation, reconnect retries, subscription restoration on app launch, pending unsubscribe cleanup, and final-score dismissal after 30 minutes.
- Offline server tests and an unsigned iOS build check configured for GitHub Actions.

**Current validation:** The score source was successfully queried during development. Server tests run on Windows. The app and widget have been built and run on a signed iPhone, and Live Activities received real APNs score updates on the Lock Screen. No Apple credentials or hosted server are included in this repository, and no public deployment has been performed.

## Run the score server locally

Install Node.js 24 or newer. No npm dependencies are required.

```sh
cd server
cp .env.example .env
npm test
npm start
```

On PowerShell, use `Copy-Item .env.example .env` in place of `cp`. The default address is `http://localhost:8787`.

The API has no shared password. Scores (`/v1/config`, `/v1/matches`, `/v1/matches/:id`), the team list (`/v1/teams`), standings (`/v1/standings`), `/health` and the privacy policy at `/privacy` are public. The first time an iPhone follows a match, the app calls `POST /v1/devices` to get a random install ID and secret, keeps them in the Keychain, and sends them as `Authorization: Bearer <id>.<secret>` when starting or stopping its own Live Activities. The server stores only a hash of the secret, and one install can't see, change or stop another install's activities. Each install can follow up to 10 matches at once, and `MAX_ACTIVITIES` caps the whole server.

Every client is rate limited by IP: `RATE_LIMIT_PER_MINUTE` (default 120) for API calls and `DEVICE_LIMIT_PER_HOUR` (default 20) for new installs. Behind a proxy, set `CLIENT_IP_HEADER` to the header carrying the real client address, or every user will share one limit.

The server polls each followed match once per cycle and pushes the result to every iPhone following it, over a single reused HTTP/2 connection to Apple. It runs as one instance; don't scale multiple polling instances against the same database.

### Demo match

`/v1/matches/demo` is a simulated live match computed from the clock: about 18 minutes of rallies, a short final, then a new match. The app hides it behind developer settings: tap the version in Settings seven times, open **Developer settings**, and turn on **Show demo match**. It can be followed on the Lock Screen like a real one. This gives App Review, and anyone trying the app out of season, something to follow. Mention it in the App Review notes.

## Host the server

The repository includes a Fly.io configuration (`server/fly.toml`). Any host that runs the Dockerfile with HTTPS, one always-on instance and a persistent volume works too.

1. Install `flyctl` and sign in. From `server/`, run `fly launch --no-deploy --copy-config` and accept or rename the app. If the name changes, update `app` in `fly.toml`.
2. Create the database volume: `fly volumes create sideout_data --size 1`.
3. Add the push credentials as secrets. `fly secrets set` takes the key contents directly, so there's no file to mount:
   ```sh
   fly secrets set APNS_TEAM_ID=... APNS_KEY_ID=... APNS_BUNDLE_ID=com.krogerjt.sideout \
     APNS_PRIVATE_KEY="$(cat AuthKey_XXXX.p8)" PRIVACY_CONTACT=you@example.com
   ```
   `fly.toml` already sets `APNS_ENVIRONMENT=production`, which TestFlight and App Store builds need. For an Xcode debug build on your phone, use a separate server set to `development`.
4. Run `fly deploy`, then check `https://<your-app>.fly.dev/health` and `/privacy`.
5. Put that HTTPS address in `ios/Local.xcconfig` (see "Build the iPhone app on a Mac") and regenerate the Xcode project.

The machine is configured never to stop, because the push worker runs inside the web process. Back up the volume if you want followed matches to survive a lost machine; nothing else on it is precious.

## Build the iPhone app on a Mac

1. Install Xcode (with an iOS 17+ SDK) and XcodeGen: `brew install xcodegen`.
2. Point the app at your server by creating `ios/Local.xcconfig`. It's git-ignored, so the address stays out of this public repository:
   ```
   SIDEOUT_SERVER_URL = https:/$()/your-server.example
   ```
   Write `https:/$()/` instead of `https://`, because `//` starts a comment in xcconfig files. Without this file the app uses a placeholder address and can't load scores. The bundle identifiers (`com.krogerjt.sideout` and `com.krogerjt.sideout.activity`) and team are already set in `project.yml`; change them there if you fork the project.
3. Run `xcodegen generate` in the repository root, then open `Sideout.xcodeproj`.
4. In Xcode, select your Apple development team for both targets. Use an Apple Developer Program account with Push Notifications enabled for the app's identifier. The project includes the push entitlement and Live Activities plist settings.
5. Select the **Sideout** scheme and your iPhone, then build and run. For an unsigned simulator build, use the command in `.github/workflows/check.yml`. To point the simulator at a local server, set `SIDEOUT_SERVER_URL = http:/$()/localhost:8787` in `ios/Local.xcconfig`; an iPhone's localhost refers to the phone itself.

The app has no setup screen: it talks to the server compiled into it. Settings shows Live Activity status, a link to iPhone settings and the privacy policy.

XcodeGen writes the app and widget plists and entitlements into `ios/Config`. Customize `project.yml` so regeneration preserves your configuration. The app icon source is `ios/App/Assets.xcassets/AppIcon.appiconset/AppIcon.png` (1024×1024, no transparency); replace it to change the icon.

## Release to TestFlight from Windows

Builds run on a Mac over SSH with [agent-team](https://github.com/krogerjt/agent-team)'s iOS release tools (Hollis). It finds the XcodeGen spec at the repository root, uploads only tracked and non-ignored files, and generates the project on the Mac. Because `ios/Local.xcconfig` is git-ignored, it never reaches the Mac; give the build the server address instead:

1. Store the server's `https://` address as a secret in the Agent Team Build Keychain (**Options → Mac Build Host → secrets**), and map the environment variable `SIDEOUT_SERVER_URL` to it.
2. Set this repository's preparation command to `sh ios/write-local-xcconfig.sh`. It writes `ios/Local.xcconfig` on the Mac before each build.
3. Follow agent-team's README for the rest: the App Store Connect app record for `com.krogerjt.sideout`, an App Store Connect API key, `npm run ios -- settings --repo <path> --team L67J56VPBG ...`, and the signing and upload grants. Then run `preflight`, `archive`, `export` and `upload`, or ask Hollis.

After Apple processes the upload, add people under **TestFlight → Internal Testing**. Internal testers must first be users in **Users and Access**.

The app declares that it uses no non-exempt encryption, so uploads skip the export-compliance question, and it ships a privacy manifest for its UserDefaults use. A public App Store release would still need App Privacy answers in App Store Connect and the remaining device QA below.

## Enable lock-screen updates

Create an APNs signing key in your Apple Developer account and configure these **server-side only** (on Fly.io, use the secrets in "Host the server" instead):

```dotenv
APNS_TEAM_ID=YOUR_APPLE_TEAM_ID
APNS_KEY_ID=YOUR_APNS_KEY_ID
APNS_PRIVATE_KEY_PATH=/absolute/path/AuthKey_YOURKEY.p8
APNS_BUNDLE_ID=com.krogerjt.sideout
APNS_ENVIRONMENT=development
```

Use `production` for TestFlight/App Store builds. Match the APNs environment to the signed app's `aps-environment` entitlement; distribution provisioning normally sets production. Restart the server after changing its environment. Never embed the `.p8` key in the app or commit it.

On iPhone, enable Live Activities for Sideout in Settings. Open a match in progress, tap **Follow on Lock Screen**, and keep the app open until it says **Following on your lock screen**. Lock the phone. The server then polls the feed and sends ActivityKit pushes, so updates don't depend on the app continuing to run. To stop, use **Stop following** in the app or dismiss the Live Activity.

### Teams and standings

The **Teams** tab lists every team on this season's scoreboards, searchable by division, with a star to save each one. The server builds the list itself: it reads each day's scoreboard for the season (mid-August to late December) once per division, one board every 1.5 seconds behind live score requests, stores the teams it sees in SQLite, and re-reads the next two weeks daily since schedules change. The first pass takes about fifteen minutes after a fresh start; `/v1/teams` reports `complete: false` until then. Teams are keyed by the same NCAA slug the auto-start check matches on, and each is filed under the division whose scoreboard lists it most.

The **Standings** tab shows ncaa-api's `/standings/volleyball-women/:division` (NCAA.com's standings page), filterable by conference, cached for 30 minutes. NCAA.com's volleyball standings page can be empty (it was in October 2026), so when it has no tables the server counts records itself from the final scores the team index stores: overall wins and losses, plus conference records from matches between two teams in the same conference, grouped by each team's conference on the scoreboard. The app says which source it is showing.

### Favorite teams start automatically

Star a team in the Teams tab, or next to its name inside a match, to save it as a favorite. Favorites are stored on the iPhone with Core Data (earlier builds' saved stars move over on first launch) and can be removed in Settings. On iOS 17.2 or later, with **Start automatically** on in Settings, the app sends the server its ActivityKit push-to-start token and the favorite teams' identifiers (`PUT /v1/autostart`; `DELETE` or an empty list removes them). Each poll, the server checks today's scoreboard (and yesterday's before 6 AM Eastern, for late West Coast matches) and sends a push-to-start 30 minutes before a favorite team's scheduled first serve, or as soon as a favorite match is found live. iOS creates the Live Activity even when Sideout is closed and launches the app in the background to register the activity's update token, after which updates work like a manually followed match. Each match is started at most once per install, so a dismissed scoreboard stays dismissed. iOS can't start Live Activities from a local timer while the app is closed, which is why the schedule check lives on the server.

The worker defaults to a 20-second polling interval, sends score changes at priority 5, and refreshes unchanged activities about once per minute. Terminal updates use priority 10. It retries transient errors and removes invalid push tokens. The provider queue caps requests below the community endpoint's published rate limit; many simultaneous followed matches can increase refresh time. Subscriptions expire after eight hours, matching the Live Activity lifetime. Updates can't be guaranteed to be instantaneous or permanently visible: Apple controls delivery and activity lifetime, and the feed may lag the actual match.

## Score source and accuracy

The default adapter uses [henrygd/ncaa-api](https://github.com/henrygd/ncaa-api), an independent service exposing NCAA.com data. Its public endpoint is a demo without a reliability guarantee. For regular use, self-host that service and set `NCAA_BASE_URL`, or replace the adapter with an appropriately licensed feed. `NCAA_HEADER_KEY` supports a private ncaa-api instance. Sideout is not affiliated with the NCAA.

The scoreboard's team scores are **sets won**, not points. The match detail endpoint supplies per-set points. Sideout keeps those values separate and displays a dash when the current set cannot be identified or points are missing. No fabricated scores are shown during outages. `checkedAt` is the time this server successfully fetched the feed, not a guarantee that the upstream scorekeeper has recently entered a rally.

## Tests and device checklist

`cd server && npm test` covers parsing, empty/malformed feeds, points versus sets, Eastern date rollover, per-install auth and ownership, rate limits and caps, request validation, the demo match, APNs payload shape and JWT signature, token rotation, deduplication, final updates, outage retries, and subscription deletion races. `npm run check` checks server syntax. Tests use recorded/reduced fixtures and make no internet requests.

On a Mac, use Xcode's Test action to run `SideoutTests`; CI builds these tests but does not claim to exercise real APNs. Live Activity delivery has been verified on a signed iPhone (start a live match, wait for registration, lock the phone, and observe an actual score change). Before distributing, also verify:

- Check expanded, compact, and minimal Dynamic Island layouts and large accessibility text.
- Disconnect the network and confirm the stale warning, then reconnect.
- Relaunch the app and confirm the existing activity is restored without duplication.
- Stop following while offline and reopen online to verify pending unsubscribe cleanup.
- Verify final scores end the activity; check sandbox and production push environments separately.

## Security and hosting scope

The server is built for one public instance. Scores are public data; following is scoped to per-install credentials, and requests are rate limited per IP. Use HTTPS only. Push tokens and install IDs live in the server's SQLite volume; protect that volume and backups. Do not log requests with authorization headers or activity tokens. If abuse becomes a problem, Apple's App Attest can prove requests come from the real app before `/v1/devices` issues a credential.

The privacy policy served at `/privacy` describes exactly what the server stores. Update `server/src/privacy.js` whenever that changes, and keep the App Store's App Privacy answers in step with it.

Apple references: [Live Activities](https://developer.apple.com/documentation/activitykit/displaying-live-data-with-live-activities) and [ActivityKit push notifications](https://developer.apple.com/documentation/activitykit/starting-and-updating-live-activities-with-activitykit-push-notifications).
