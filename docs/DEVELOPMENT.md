# Development notes

How eSIM On-SIM works, and how to build and release it. For what the app does and how to install it, see the
[README](../README.md).

## How the app reaches the card

The card stays in the phone, and no external SIM reader is required. The app reaches the card the best way the phone
allows, tried in this order at each start:

1. **Root**, on rooted phones: the helper bundled in this APK runs through `su`.
   Nothing to set up; allow the app in the root app (Magisk 31.1+, KernelSU and
   APatch never ask: grant it there beforehand).
2. **Direct**, through Android's own secure element API (OMAPI), when the card's
   access rules let any app in. Nothing to set up. Many adapter cards, 5ber's
   included, only let their maker's app in this way.
3. **Shizuku**, where that app is installed and running: the same helper, started
   by Shizuku's server. Works with any card. Nothing else to set up here; Shizuku
   asks once whether to allow this app. It isn't required, and the app never asks
   people to install it.
4. **ADB**: the same helper, started over an in-app ADB client. Works with any
   card. On Android 11 or newer it uses Wireless debugging and needs the
   setup below; on Android 6 to 10 a computer turns it on (see below).

Requires Android 6.0 (API 23) or newer. Direct access needs Android 9 (OMAPI).

## Setting up ADB

Only ADB needs setting up in this app. Where Shizuku is installed, the checklist
offers it first: **Open Shizuku** while it's stopped, **Use Shizuku** once it runs.
The app walks through ADB as a checklist that detects each setting itself
(Developer options, Wi-Fi, Wireless debugging) and says why it is needed. The
instructions use stock Android's English names on every phone, each with a small
drawing of the Settings screen and the thing to tap circled, and a "search
Settings for …" fallback for phones that rename or move them.

1. Turn on Developer options and Wireless debugging (Android 11 or newer, on Wi-Fi).
2. Tap **Start pairing**. Wireless debugging opens; tap **Pair device with pairing
   code**. The app finds the pop-up over mDNS and its notification drops down with
   **Enter code**: type the 6 digits there, without leaving Settings. Without
   notifications, the code can be typed into the app in split screen instead.
3. The app connects by itself (over loopback) and loads the card. Later starts
   try the last working port first and only search over mDNS when it changed.

Ports are only needed if discovery fails: **Connect with a port number** on the
reconnect screen, or **Can't find the pop-up? Enter its port** while pairing.

On Android 17 the mDNS search needs the *Nearby devices* permission
(`ACCESS_LOCAL_NETWORK`). The app asks for it, with a short explanation, right
before **Start pairing** and before a reconnect whose last port no longer works,
and never searches without it (Android could otherwise put a service picker over
Settings, closing the pairing pop-up). Turned down, pairing and connecting take
the port by hand instead; the loopback connections themselves need no permission.
Android 17 may also show apps 0 for *Developer options* and *USB debugging*;
the app then takes Wireless debugging being on as proof of the first, and says
"Can't check" instead of asking people to turn on what may already be on.

Pairing credentials persist in private app storage and are excluded from backup.
Clearing app data or uninstalling requires pairing again. Android turns Wireless
debugging off after a reboot or Wi-Fi change; the app shows that at once and
reconnects as soon as it is switched back on. Android also forgets a pairing after
7 days without a connection ("Disable adb authorization timeout" stops that); the
app then asks to pair again.

### Android 6 to 10: set up with a computer

These versions have no Wireless debugging. Android's debugging service can still
listen on a TCP port, also on 127.0.0.1, once a computer runs `adb tcpip 5555`;
the app then connects over loopback with plain ADB (no TLS, no pairing, no Wi-Fi)
and starts the same helper. The **Set up with a computer** checklist:

1. Developer options, then **USB debugging** (both detected).
2. On a computer: download Android's SDK Platform-Tools
   (developer.android.com/tools/releases/platform-tools), connect the phone by
   USB and run `adb tcpip 5555`. **Send these steps to your computer** shares
   them as text. LineageOS's *ADB over network* switch does the same.
3. Unplug and tap **Connect**: the phone asks "Allow USB debugging?" for the app
   itself. The app shows its key's fingerprint (MD5 of the ADB public key, as
   Android shows it) so people can check the question is this app's.

The setting lasts until the phone restarts; after that the app says the computer
is needed again. Until then the app reconnects by itself, also after an update.
The helper outlives USB debugging being turned off on Android 6 to 7.1 and ends
with it on 8 to 10. Android 10 forgets allowed keys after 7 days unused; the app
then asks to allow it again. While the port is open, other devices on the network
can ask to connect, so the app only tells people to tap Allow when it asks.
Connections the app starts by itself never offer the key, so the phone only asks
when someone tapped Connect.

With direct access Android doesn't let the app change mobile data or data roaming,
so the switch dialog leaves those choices out and a switch offers the SIM's own
settings page instead. Where Shizuku allows this app, a switch sets them through
it, and the card itself stays on direct access. Otherwise Details › **Mobile data
and roaming** optionally sets up ADB just for them (pairing Wireless debugging, or
on Android 9 and 10 the computer route).


## Shizuku

[Shizuku](https://github.com/RikkaApps/Shizuku) keeps Android's debugging tool (or
root) running for the apps people allow in it, so it's set up once, there. The app
detects it by package name, and connects by itself once it's running:

- Its server hands the app its connection a moment after the app starts, so the
  first connection waits up to a second for it, only where Shizuku is installed.
- Shizuku shows its own question the first time, only with someone in the app, and
  the app waits a minute for the answer, which still counts if it comes later. A
  "Deny" there isn't remembered
  by Shizuku, so the app remembers it, to not ask at each start: after a refusal,
  only **Use Shizuku** asks again, or Reconnect when Shizuku is the way in.
- Shizuku stops when the phone restarts. Started with Wireless debugging it also
  lives only as long as Android's debugging service: without USB debugging on,
  Android stops it when Wi-Fi drops, as it does this app's own background helper.
  The app says so and offers to open it, and reconnects by itself as soon as it runs
  again. On Android 17 stock Shizuku 13.6.0 can only reach an app that comes to the
  foreground, so an app left open reconnects when people come back to it.
- **Stop card access** ends this app's helper, never Shizuku itself.

Sui, Shizuku's Magisk variant, isn't used: finding it costs two binder calls on the
main thread at every start, for everyone, and rooted phones have su.

## Managing eSIMs

- Names are stored on the card (SGP.22 SetNickname), so other phones and LPAs show
  them. The carrier name the eSIM carries (e.g. 1GLOBAL for Saily) stays visible
  next to the ICCID.
- Notes are stored only on this phone.
- Duplicates: an activation code installed before is recognised (by a hash; codes
  are not stored), an eSIM whose ICCID is already on the card is stopped before
  download, and eSIMs sharing a name are pointed out.
- Switching restarts the card; the app waits for it to come back and reconnects
  card access by itself if it dropped.

## Installing an eSIM

Tap **Add eSIM**, then either:

- **Scan QR code** with the camera. The standalone build uses the app's own
  scanner (Camera2 and ZXing, on the phone), which asks for camera access the
  first time. The google build opens Google Play services' scanner instead, which
  needs no camera permission, and falls back to the app's own when Play services
  can't scan (missing, too old, microG, still downloading).
- **Use a screenshot** of the QR code, e.g. from the provider's email. It is
  decoded on the phone and works without Google Play services.
- Paste or type the activation code (`LPA:1$…`). If the provider lists the
  *SM-DP+ address* and the *activation code* separately, enter just the code and
  the app asks for the address.

Afterwards the app offers to name the new eSIM and switch to it.

Codes also come in from other apps: **share** an email, a message or a QR
screenshot to the app, or open an `LPA:1$…` link. "Install on iPhone" links
(`esimsetup.apple.com`) open in the app once they are allowed under
**Details › eSIM links** (Android 12+ asks for that). The code fills in the
Add eSIM form; nothing installs without a tap on **Install**.

**Check for eSIMs sent to this card (SM-DS)** runs only when tapped. It asks up to three discovery servers (SM-DS): the one
the card names, unless that's the GSMA's test server (5ber cards name it), then the GSMA's (lpa.ds.gsma.com) and
Google's (lpa.live.esimdiscovery.com). Each server the card accepts gets the card's EID, in the standalone build too.
The card's default SM-DP+ is listed if it has one.

## Following the standard

The app implements SGP.22's consumer flows, and these are its deliberate or known departures, so nobody has to find
them again:

- Notifications: install, enable, disable and delete notices are sent like a phone's own LPA would, oldest first, and
  cleared from the card once delivered. After a switch the first try often fails, because the phone's connection drops
  with the card restart, so it retries at about 5, 20 and 60 s, and anything left waits for the next card read. Only
  the newest unsent switch notice per eSIM is kept, so a provider whose server has gone can't fill the card.
- After a failed load, CancelSession uses reason timeout(2), not loadBppExecutionError(5) as 3.1.3.3 says: reason 5
  ends the order for good, and the activation code often still works.
- Not checked: the server address and SM-DP+ OID in serverSigned1 against the activation code. Refusing a server that
  signs a canonical name rather than the code's alias would turn away real eSIMs that other LPAs install.
- Not checked before install: Profile Policy Rules.
- TLS accepts the system CA store as well as the bundled GSMA production roots (CI1, OISTE GSMA CI G1, Entrust), which
  is broader than 4.5.2.2. Test roots stay out: SGP.26 forbids them in commercial products and their keys are public.
- Telephony APDUs don't carry the logical channel in CLA; the modems tested add it themselves.
- With two removable cards the app only reaches the first it finds.

## What has been tested

- A POCO F6 on Android 16 with a 5ber card: pairing, automatic discovery, reading the EID and eSIMs, reconnecting,
  reusing credentials, helper request validation. Real switches on that phone too.
- Emulators with the simulated card: see below. An emulator has no card, so a real pairing there ends at "Couldn't find
  the eSIM card".
- The helper's telephony calls for Android 6 to 17 follow AOSP's sources for each release, covered by JVM tests with
  stand-ins for each version's interfaces.
- Shizuku: stock Shizuku 13.6.0 with the simulated card on Android 14 and 17 emulators (asking, allowing, refusing,
  switching, its server dying and coming back, the helper ending with the app), not yet on a phone with a real card. On
  Android 6 only that the app still starts, since current Shizuku needs Android 7.
- Not tested: other makes of card, real downloads after the audit's protocol changes, and vendor telephony
  implementations beyond the one phone.

## Build and checks

Use JDK 21, Android SDK platform 37.2 and build-tools 37.0.0 (Gradle 9.8, AGP 9.4, Kotlin 2.4). Set `sdk.dir` in
local.properties, then run:

```text
gradlew.bat :app:assembleStandaloneDebug :app:testStandaloneDebugUnitTest :app:testGoogleDebugUnitTest :app:lintStandaloneDebug
```

There are two builds (product flavors) with the same app ID and signature, so either installs over the other and keeps
the pairing:

- **standalone** (the default): no Google code at all; QR codes are scanned with the app's own camera screen. Works on
  phones without Google Play services.
- **google**: scans with Google Play services' code scanner first (ML Kit), and with the app's own scanner when that
  can't be used.

`:app:assembleStandaloneRelease` and `:app:assembleGoogleRelease` build the minified APKs (about 1.6 and 1.8 MB, with
dex and native libraries compressed) under `app/build/outputs/apk/<flavor>/release/`. Without a release key they are
signed with the debug key.

The app has no bundled TLS library: `org.conscrypt.OpenSSLProvider` and `org.conscrypt.Conscrypt` in `src/main` are small
shims that libadb finds by name, on top of Android's own TLS. Android 11 has no public way to export keying material, so
pairing there goes through HiddenApiBypass. Re-test pairing on Android 11 after changing either.

`gradle/verification-metadata.xml` pins the JitPack artifacts by SHA-256, and Gradle checks them on every build. Update it
when bumping those dependencies. With the configuration cache on, test it with a real build task such as
`assembleStandaloneRelease`.

## Releasing

Pushing a tag like `v0.3` runs [.github/workflows/release.yml](../.github/workflows/release.yml), which tests, builds and
signs both flavors, checks each APK's signing certificate, and publishes a GitHub Release with `eSIM-On-SIM.apk`,
`eSIM-On-SIM-google.apk`, `SHA256SUMS.txt` and `debug-symbols.zip`. The file names don't change, so the README's download
link always gets the newest version. Each release's description starts with
[.github/release-notes.md](../.github/release-notes.md): what to download and how to allow the install.

Once, before the first release, make the key. Back it up: without it, later versions can't be installed over earlier
ones.

```text
keytool -genkeypair -keystore esim-on-sim-release.jks -alias esim -keyalg RSA -keysize 4096 -validity 36500
```

Then add it to the GitHub repository:

- Secrets: `RELEASE_KEYSTORE_BASE64` (the keystore, base64), `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`,
  `RELEASE_KEY_PASSWORD`.
- Variable: `RELEASE_CERT_SHA256`, the certificate's SHA-256 from `keytool -list -v`. The workflow refuses to publish an
  APK signed with anything else, since a runner's own debug key would give people APKs that can't update over each other.

For each release, raise `versionCode` and `versionName` in `app/build.gradle.kts`, commit, then tag that commit with the
same version and push both. The workflow stops if the tag and `versionName` differ.

Local release builds sign with the same key when the `ESIM_KEYSTORE`, `ESIM_KEYSTORE_PASSWORD`, `ESIM_KEY_ALIAS` and
`ESIM_KEY_PASSWORD` variables are set, or a git-ignored `keystore.properties` has `storeFile`, `storePassword`, `keyAlias`
and `keyPassword`.

Android's developer verification doesn't cover direct downloads yet, but Google plans to extend it to every certified
device in 2027. Unregistered apps will then only install or update through an advanced flow or adb, so decide on
registering before that, and recheck Google's FAQ. Registration needs the release key.

## Testing without a card (emulator)

Debug builds contain a simulated card, `FakeCardShell` (in `src/debug`), which the
app starts in place of `CardShell` while `/data/local/tmp/fakecard.json` exists on
the device. Release builds don't contain it. Pairing and the connection are real
(Wireless debugging works on an Android 11+ emulator); only the card is simulated,
with three eSIMs that persist in `/data/local/tmp/fakecard-state.json`. The JSON
file sets faults and is re-read on every request, for example:

```text
adb shell 'echo "{\"delayMs\":250,\"refreshMs\":6000,\"dropEnableReply\":true}" > /data/local/tmp/fakecard.json'
```

Options: `delayMs`, `jitterMs`, `failRate`, `hangAt`, `exitAt`, `refreshMs`,
`dropEnableReply`, `useSimDelayMs`, `useSimRefuse`, `absent`, `slot`, `slots`,
`defaultDp`, `rootDs`, `startDelayMs`, `openDelayMs`, `aid` (see the class comment).
Turn Developer options on by tapping Build number, not with `settings put`:
otherwise Settings keeps its Developer options page disabled.

The opt-in instrumentation test only reads the card. With an already paired
installation and Wireless debugging enabled:

```text
gradlew.bat :app:connectedStandaloneDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.readCard=true
```

Optional runner arguments: `connectPort`, or `pairPort` and `pairCode` for a live
pairing dialog. Do not save pairing codes in shared logs. Tests are skipped unless
`readCard=true` is supplied.

## Implementation

`CardAccess` picks the way in: `RootApduBackend` (root), `OmapiApduBackend`
(direct), `ShizukuApduBackend` (Shizuku), then `AdbApduBackend` (Wireless
debugging), and remembers the one that worked. All but ADB are tried once per app
start and on Reconnect.
`Lpa.discover` asks every slot for the usual ISD-R ID first, then for makers' own eSIM manager IDs (eSIM.me, 5ber, XeSIM,
LinksField, ESTKme; the list is `CardShell.ISD_R_AIDS`), so a standard card never costs an ordinary SIM those extra
tries. `OmapiApduBackend` opens the full ISD-R AID, or a maker's ID when only that one is allowed. Each
try makes Android check the card's access rules on the card (tens of ms), so cards that
refused every AID are remembered by ATR and skipped at later starts (Reconnect asks
them again).

`RootApduBackend` runs `CardShell` with `su -c` and talks to it over the su
process's own input and output; it ends with the app. `ShizukuApduBackend` does the
same through `IShizukuService.newProcess`, on the shared `PipeApduBackend`, with
`ShizukuClient` as the only code touching Shizuku's library. The helper runs under
`sh -c` without `exec`: when the app dies Shizuku signals only the process it
started, and the helper must see its input end to close the card's channels.
Shizuku's recommended `UserService` isn't used: on its current release it crashes
before any app code runs on MediaTek-based phones, including Xiaomi's. The library
keeps `newProcess` private and plans to remove it, so a server without it answers
null, and the app falls through to its next way in. Shizuku's library declares
Android 7 only for its `remove*Listener` calls (`List.removeIf`), which the app never
makes; `tools:overrideLibrary` keeps Android 6 without desugaring.

`AdbApduBackend` launches `CardShell` from the installed APK using `app_process` over the app's authenticated ADB
stream (Wireless debugging, or `adb tcpip` below Android 11). It starts the helper in the background with a fresh key
sent over that stream. The helper listens on a random port on 127.0.0.1 only. Each connection does mutual HMAC-SHA256
over two nonces, and connections from another uid are refused when `/proc/net/tcp` says who they are; the HMAC is
required regardless. If the background helper can't start, the ADB stream carries requests while Wireless debugging
stays on. The helper ends at a restart, when adbd stops (with USB debugging on it survives Wi-Fi and Wireless debugging
going off), on an app update or uninstall, on **Stop card access**, when a newer helper starts, or after 30 s unused.

A bounded JSON-line protocol supports only eUICC channel open/close, ES10 APDUs, GET RESPONSE, a health check, a
read-only slot list, and applying the mobile data and roaming choices after a switch. There is no exported helper
service; the helper's one socket is loopback-only and authenticated. Nothing exported reaches the card. The one exported
component added for card access is the provider Shizuku's server hands its connection to, which only callers holding
`INTERACT_ACROSS_USERS_FULL` can reach. Channel ownership is tracked per helper session, and channels are closed when its
input ends.

Complete card operations are serialized on one process-wide thread. Installs and switches run under a short foreground
service and a capped wake lock (`CardWork`), so leaving the app can't cut one off midway. A lost connection aborts the
operation; mutating APDUs are never automatically replayed. Before each switch, turn-off and delete the app re-reads the
list and checks that the ID it's about to use still belongs to that eSIM, and an install first checks it's the same card. Requests time out after 45 s, or
15 s for reads (listing eSIMs, card details), after which card access restarts.
On phones with a built-in eSIM, slots Android reports as a fixed eUICC are tried
last, so a removable card elsewhere wins. If that chip is all it finds, the app holds it back and says so, instead of
showing it as the card, until someone confirms it's their removable card. The partial ISD-R identifier is
still required on the tested phone because its telephony service restricts the
full standard identifier to the system LPA.

## Libraries

ADB: [libadb-android](https://github.com/MuntashirAkon/libadb-android) 3.1.1. Android 11 pairing:
[HiddenApiBypass](https://github.com/LSPosed/AndroidHiddenApiBypass) 4.3. Shizuku client:
[Shizuku-API](https://github.com/RikkaApps/Shizuku-API) 13.1.5. QR codes: [ZXing](https://github.com/zxing/zxing) core 3.5.4,
and in the google build the
[Google code scanner](https://developers.google.com/ml-kit/vision/barcode-scanning/code-scanner) 16.1.0.

The app's own code is under the [MIT License](../LICENSE). The libraries keep their own: libadb-android is
GPL-3.0-or-later or Apache-2.0, with an LGPL dependency, HiddenApiBypass and ZXing are Apache-2.0, and Shizuku-API is MIT.
Their licence texts have to reach people with the APK, which the app doesn't do yet.
