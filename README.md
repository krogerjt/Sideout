# Sideout

A native iPhone app for NCAA women's volleyball, with set scores, favorites, match details, Lock Screen Live Activities, and Dynamic Island support. Browse Divisions I, II, and III; schedules use Eastern Time.

## What's built

- SwiftUI app targeting iOS 17 or newer, with search, date and division filters, favorite teams, pull to refresh, and foreground refresh every 20 seconds.
- WidgetKit / ActivityKit extension showing sets won and current-set points, with a stale indicator after two minutes without a successful feed check.
- Node.js server that reads NCAA scoreboards and match details, stores activity subscriptions in SQLite, and updates or ends activities using Apple's HTTP/2 APNs service.
- Push token rotation, reconnect retries, subscription restoration on app launch, pending unsubscribe cleanup, and final-score dismissal after 30 minutes.
- Offline server tests and an unsigned iOS build check configured for GitHub Actions.

**Current validation:** The score source was successfully queried during development. Server tests run on Windows. The app and widget have been built and run on a signed iPhone, and Live Activities received real APNs score updates on the Lock Screen. No Apple credentials or hosted server are included in this repository, and no public deployment has been performed.

## Run the score server

Install Node.js 24 or newer. No npm dependencies are required.

```sh
cd server
cp .env.example .env
node -e "console.log(require('node:crypto').randomBytes(32).toString('hex'))"
```

On PowerShell, use `Copy-Item .env.example .env` in place of `cp`. Copy the generated random value into `ACCESS_KEY` in `.env`, then run:

```sh
npm test
npm start
```

The default address is `http://localhost:8787`. `/health` is public; all `/v1` routes require `Authorization: Bearer YOUR_ACCESS_KEY`. An unconfigured APNs server still serves scores, but the app will explain that lock-screen following needs push credentials.

For an actual iPhone, run the server on a persistent host behind HTTPS. Set `HOST=0.0.0.0` if the reverse proxy runs outside the host network. Mount `server/data` on persistent storage. The Dockerfile supports this setup; configure secrets through environment variables and mount the `.p8` key read-only. This is a single-instance personal server; don't scale multiple polling instances against the same subscriptions.

## Build the iPhone app on a Mac

1. Install Xcode (with an iOS 17+ SDK) and XcodeGen: `brew install xcodegen`.
2. Run `cd ios && xcodegen generate`, then open `Sideout.xcodeproj`.
3. Set unique bundle identifiers in `ios/project.yml`: `com.yourname.sideout` for the app and `com.yourname.sideout.activity` for the extension. Regenerate the project after edits.
4. In Xcode, select your Apple development team for both targets. Use an Apple Developer Program account with Push Notifications enabled for the app's identifier. The project includes the push entitlement and Live Activities plist settings.
5. Select the **Sideout** scheme and your iPhone, then build and run. For an unsigned simulator build, use the command in `.github/workflows/check.yml`.
6. Open Sideout Settings, enter your HTTPS server address and access key, and tap **Save & connect**. The simulator can use `http://localhost:8787`; an iPhone's localhost refers to the phone itself.

XcodeGen writes the app and widget plists and entitlements into `ios/Config`. Customize `project.yml` so regeneration preserves your configuration. App Store release preparation still needs an app icon, distribution signing, privacy disclosures, and the remaining device QA below.

## Enable lock-screen updates

Create an APNs signing key in your Apple Developer account and configure these **server-side only**:

```dotenv
APNS_TEAM_ID=YOUR_APPLE_TEAM_ID
APNS_KEY_ID=YOUR_APNS_KEY_ID
APNS_PRIVATE_KEY_PATH=/absolute/path/AuthKey_YOURKEY.p8
APNS_BUNDLE_ID=com.yourname.sideout
APNS_ENVIRONMENT=development
```

Use `production` for TestFlight/App Store builds. Match the APNs environment to the signed app's `aps-environment` entitlement; distribution provisioning normally sets production. Restart the server after changing its environment. Never embed the `.p8` key in the app or commit it.

On iPhone, enable Live Activities for Sideout in Settings. Open a match in progress, tap **Follow on Lock Screen**, and keep the app open until it says **Following on your lock screen**. Lock the phone. The server then polls the feed and sends ActivityKit pushes, so updates don't depend on the app continuing to run. To stop, use **Stop following** in the app or dismiss the Live Activity.

The worker defaults to a 20-second polling interval, sends score changes at priority 5, and refreshes unchanged activities about once per minute. Terminal updates use priority 10. It retries transient errors and removes invalid push tokens. The provider queue caps requests below the community endpoint's published rate limit; many simultaneous followed matches can increase refresh time. Subscriptions expire after eight hours, matching the Live Activity lifetime. Updates can't be guaranteed to be instantaneous or permanently visible: Apple controls delivery and activity lifetime, and the feed may lag the actual match.

## Score source and accuracy

The default adapter uses [henrygd/ncaa-api](https://github.com/henrygd/ncaa-api), an independent service exposing NCAA.com data. Its public endpoint is a demo without a reliability guarantee. For regular use, self-host that service and set `NCAA_BASE_URL`, or replace the adapter with an appropriately licensed feed. `NCAA_HEADER_KEY` supports a private ncaa-api instance. Sideout is not affiliated with the NCAA.

The scoreboard's team scores are **sets won**, not points. The match detail endpoint supplies per-set points. Sideout keeps those values separate and displays a dash when the current set cannot be identified or points are missing. No fabricated scores are shown during outages. `checkedAt` is the time this server successfully fetched the feed, not a guarantee that the upstream scorekeeper has recently entered a rally.

## Tests and device checklist

`cd server && npm test` covers parsing, empty/malformed feeds, points versus sets, Eastern date rollover, HTTP auth and validation, APNs payload shape and JWT signature, token rotation, deduplication, final updates, outage retries, and subscription deletion races. `npm run check` checks server syntax. Tests use recorded/reduced fixtures and make no internet requests.

On a Mac, use Xcode's Test action to run `SideoutTests`; CI builds these tests but does not claim to exercise real APNs. Live Activity delivery has been verified on a signed iPhone (start a live match, wait for registration, lock the phone, and observe an actual score change). Before distributing, also verify:

- Check expanded, compact, and minimal Dynamic Island layouts and large accessibility text.
- Disconnect the network and confirm the stale warning, then reconnect.
- Relaunch the app and confirm the existing activity is restored without duplication.
- Stop following while offline and reopen online to verify pending unsubscribe cleanup.
- Verify final scores end the activity; check sandbox and production push environments separately.

## Security and hosting scope

This implementation is for a personal installation. The server uses one shared access key; anyone with it can manage subscriptions. Use HTTPS and keep the key private. Tokens live in the server's SQLite volume; protect that volume and backups. Do not log requests with authorization headers or activity tokens. For a public multi-user launch, add per-user authentication and authorization, gateway rate limiting, capacity controls, and production feed/hosting arrangements.

Apple references: [Live Activities](https://developer.apple.com/documentation/activitykit/displaying-live-data-with-live-activities) and [ActivityKit push notifications](https://developer.apple.com/documentation/activitykit/starting-and-updating-live-activities-with-activitykit-push-notifications).
