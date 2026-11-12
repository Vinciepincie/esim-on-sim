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

### Choosing the way in

The app's own **Settings** (⋮ menu) has **Card access**: **Automatic**, which is the order above, or one way chosen
(`access-method/preferred`). A chosen way is tried first at every start, with a fallback behind it so a working card is
never lost:

| Chosen | Order |
| --- | --- |
| Automatic | Root, Direct, Shizuku, ADB |
| Direct | Direct, Shizuku, ADB |
| Root | Root, Shizuku, ADB, Direct |
| Shizuku | Shizuku, ADB, Direct |
| ADB | ADB, Shizuku, Direct |

- A way behind the chosen one never asks anything: Shizuku only connects when it already allows this app, and root,
  whose app may ask, is never a fallback. On Android 10 and older, ADB behind a chosen way is only tried once the
  phone allowed this app, without its "Allow USB debugging?" question, except from ADB's own setup and Reconnect
  screens (`connect(forAdb = true)`). Direct comes last behind any other, as it only reaches cards that let this app
  in, where the others reach every card. Choosing Direct is how a rooted phone stops the root question at each start.
- ADB that's off or not set up costs nothing at start. A chosen Shizuku waits up to a second for Shizuku to hand over
  its connection; so does a Shizuku behind a chosen Root or ADB, once, where Direct comes next: otherwise Direct would
  win before Shizuku's connection arrived, and hide a second card for the whole run.
- Why the chosen way didn't connect (`CardAccess.preferredFailure`) shows under Card access, and on the setup screens
  when they're about another way. The choice stands until someone changes it: the app never clears it by itself.
- Connected, a pick is saved only once it has connected. Not connected, it's saved at once, as there's nothing to lose.
- The setup buttons ("Use root", "Use Shizuku instead", "Set up Wireless debugging instead") leave Automatic as it is,
  but replace a chosen way, as "instead" says.

**Switching while connected** (`CardAccess.switchTo`) connects the new way first and only then ends the old one, so a
failed switch changes nothing ("Card access stays as it was"). So does Cancel during a switch to Shizuku or Wireless
debugging: a way that connected all the same is let go (`ConnectCancelledException`), and nothing is saved. A
background helper stays for SIM settings when Direct takes over. Automatic tries only the ways ranked above the one in use, asking nothing new. Choosing Wireless debugging
before it's paired opens its setup over Settings (`SetupFor.SWITCH`), and on Android 10 and older the computer steps;
card access carries on underneath until it's done. Afterwards the app forgets every card address, as each way
numbers slots its own way, finds the cards again, and puts the one that was in view (or the one tapped) back in view by
EID.

**A second card that direct access can't open.** Android checks a card's access rules before it opens anything, and an
ordinary SIM with none is refused the same way as an adapter card that only lets its maker's app in. So after a refusal
the app reads that reader's ATR again in a fresh session (a session caches the ATR of the card it was opened on) and
looks for the eUICC bit, as Android's own `AnswerToReset` does: the first TB after a T=15 TD with `0x82` set (ETSI TS
102 221 §6.3.3, SGP.22 §3.4.1). Then the switcher shows **Card in SIM n**, "Doesn't let this app in directly", which
leads to Card access. Phones that declare `FEATURE_TELEPHONY_EUICC` never get this row, as it could be their own chip;
there Settings only asks whether a second card is missing. ATRs are never logged.

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
it, and the card itself stays on direct access. Otherwise Settings › **Mobile data
and roaming** optionally sets up ADB just for them (pairing Wireless debugging, or
on Android 9 and 10 the computer route).


## Shizuku

[Shizuku](https://github.com/RikkaApps/Shizuku) keeps Android's debugging tool (or
root) running for the apps people allow in it, so it's set up once, there. The app
detects it by package name, and connects by itself once it's running:

- Its server hands the app its connection a moment after the app starts, so the
  first connection waits up to a second for it, only where Shizuku is installed. Not where ADB worked last time and can
  go next: with Shizuku stopped, that would cost every start this second.
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
**Settings › eSIM links** (Android 12+ asks for that). The code fills in the
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
  ends the order for good, and the activation code often still works. It's also sent, card and server, when the
  provider got a request but gave no clear answer (a server error, a garbled reply, a dropped connection), so the
  session can't hold up the next try; not after the provider's own refusal, or when the server couldn't be reached.
- Not checked: the server address and SM-DP+ OID in serverSigned1 against the activation code. Refusing a server that
  signs a canonical name rather than the code's alias would turn away real eSIMs that other LPAs install.
- Not checked before install: Profile Policy Rules.
- TLS accepts the system CA store as well as the bundled GSMA production roots (CI1, OISTE GSMA CI G1, Entrust), which
  is broader than 4.5.2.2. Test roots stay out: SGP.26 forbids them in commercial products and their keys are public.
- Telephony APDUs don't carry the logical channel in CLA; the modems tested add it themselves.

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
- Several cards: only with simulated cards on emulators, so what real phones report for a slot they aren't using, and
  whether one card's restart disturbs the other, is unverified.
- Settings (choosing card access, switching while connected, the "Card in SIM n" row, Theme): JVM tests, and an Android
  14 emulator with simulated cards and direct access; the Theme on Android 6 as well. On a real phone, whether its SIM
  service gives OMAPI the card's full ATR is unverified, and so is the Theme on Android 16 and 17 with the accessibility
  colour inversion on.
- The Licenses page: JVM tests and an Android 14 emulator, both builds.
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

- **standalone** (the default): no Play services or ML Kit code; QR codes are scanned with the app's own camera screen. Works on
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
[.github/release-notes.md](../.github/release-notes.md): a short "What's new" for people who use the app, then what to
download and how to allow the install.

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

For each release, raise `versionCode` and `versionName` in `app/build.gradle.kts`, rewrite "What's new" in
`.github/release-notes.md` in plain words (no internals), commit, then tag that commit with the same version and push
both. The workflow stops if the tag and `versionName` differ.

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
`defaultDp`, `rootDs`, `startDelayMs`, `openDelayMs`, `aid`, `cards`, `hangSlot`, `omapi`, `records` (see the class
comment). `records` is Android's memory of the profiles it has seen on cards, for the Card name row in Details, each
with the card's `eid`: `{"records":[{"eid":"<the card's EID>","iccid":"8900000000000000011","name":"5ber","source":1,"id":3}]}`.
`cards` puts several simulated cards in the phone, each with its own eSIMs, for example
`{"cards":[{"slot":0},{"slot":1}],"slots":[{"euicc":true,"token":"a"},{"euicc":true,"token":"b"}]}`.
`slots` is what Android says, not worked out from `cards`, so change both together: a card taken out is
`{"euicc":true,"present":false}` with no token, and a card swapped for another gets a new token.

`omapi` gives the emulator SIM readers for direct access (`FakeOmapi`, also in `src/debug`): while the key is
there the app reaches the same simulated cards itself, without the helper, and otherwise uses the phone's own readers.
Each entry is a reader, `"slot"` from 0 for SIM 1, holding the card `cards` (or `slot`) puts there. `"refuse":true`
turns the app away, as most adapter cards and ordinary SIMs do; `"present":false` empties the reader; `"atr"` is the
card's ATR in hex (none without it). A refusing card whose ATR says eSIM card shows as such even where the emulator
reports an eSIM of its own, which on a real phone hides it. A 5ber in SIM 2 beside a card that lets the app in, which
shows the "Card in SIM 2" row:
`{"cards":[{"slot":0},{"slot":1}],"omapi":[{"slot":0},{"slot":1,"refuse":true,"atr":"3B9F96803FC7828031E073FE211B57AA8660F001001EE1"}]}`.
An empty list is a phone with readers but none for SIM cards. Apps can read `/data/local/tmp` but not write there, so
the app keeps its own copy of each card's state in its files, and reads whichever copy is newer: what the helper
changes shows through direct access, but not the other way round. The files have to be readable by others
(`chmod 644`) for the app to see them.

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
tries. The last ID in that list is the whole standard ISD-R AID in lower case (`Lpa.ISD_R_LOWER`), tried behind all of
them: a card that won't take the 15-byte prefix still selects ISD-R from the full ID, and the lower case still slips past
the Android 13+ telephony check that keeps the exact upper-case ID for the system LPA (it compares as text). A card that
takes the prefix keeps using it, as the prefix wins first. `OmapiApduBackend` opens the full ISD-R AID, or a maker's ID when only that one is allowed. It leaves the lower-case copy out: to a card reader that's the same ID again. Each
try makes Android check the card's access rules on the card (tens of ms), so cards that
refused every AID are remembered by ATR and skipped at later starts (Reconnect asks
them again). Not the ATR of the card that let the app in: two cards of one make share it, and one may refuse where the
other doesn't.

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

The helper reaches the card through the phone's telephony (the calls above). Only when `Lpa.discover` has tried every
slot and every eSIM manager ID through telephony and found no card does it search again through the **Secure Element
HAL** (`android.hardware.secure_element.ISecureElement`, the AIDL HAL, Android 14+; 11-13 have only the older HIDL one,
which the helper doesn't use, and a phone updated to 14 or later may still have only that one): an `open` request with
`"via": "hal"` and the HAL `"reader"` to ask (0 for `SIM1`). A plain `open` is telephony alone, and its answer or
exception goes back to the app unchanged. The HAL is the layer below both telephony and Android's own OMAPI service: a
card the phone's telephony won't open, or won't give the helper at all, can still answer there, below the OMAPI app check
that direct access runs into.

**Why a search of its own (issue #1).** In 0.5 the helper asked the HAL on every telephony miss. On a rooted Pixel 9 Pro
XL telephony missed the first ID, the 15-byte prefix, on the tray's 5ber. The HAL got in before the app had asked for
5ber's own ID, which telephony would have opened. Its `SIM1` was the Pixel's built-in eSIM chip, which took the prefix
too, so the app showed that chip's empty list as the 5ber's. HAL readers can follow the phone's SIM numbers rather than
its trays, and Android doesn't say which. So the app's HAL search:

- asks every slot's reader by its SIM number (`SlotInfo.logical`, as Android's own secure-element service numbers its
  readers) for every ID first, and only then the reader by the slot's own number, a guess;
- skips slots Android calls empty or unused, as whatever a reader finds there is another slot's card;
- reads the EID and turns the card away when Android gives that EID for another slot, or another EID for this slot
  (`Lpa.fitsSlot`); a guessed reader's card counts only when Android gives this slot that EID, or no other SIM is in
  use (`Lpa.alone`);
- marks the card as possibly the phone's own chip (`onBuiltInSlot`, so the app asks) unless Android gives this slot
  that EID, while a built-in chip is in the phone that Android gives no EID for;
- treats a link lost during the search as the connection error it is, not as "no card", and a card that opened on its
  slot's SIM-number reader but failed as that card's error;
- remembers the reader with the card (`Card.reader`), reopens it the same way, and reads the EID again on each reopen,
  since a reader can come to reach another card when the phone numbers its SIMs anew; a refresh asks that reader first
  (`Lpa.again`), and only as that card, by its EID, before searching through telephony again;
- also runs when telephony found only the phone's own chip, which people haven't said is their card: a tray card only a
  reader reaches goes before it, and the chip's EID, just read through telephony, can't pass for that card's. Slots
  Android says hold an ordinary SIM are left out of that, as it would otherwise cost them a SELECT per ID at each refresh;
- also runs when a card answered through telephony but then failed (its transmit refused, say), before that card's error
  is shown;
- writes a reader off after two errors, not one: one can be a passing error.

The look for other cards (`Lpa.scan`) works in two passes: telephony on every slot first (a refused ID is passed over, as
in discovery), then readers, with the same rules, for the slots telephony found no eSIM manager in or refused. So a reader
never takes a card telephony finds in a later slot, and never one already known in another slot (the one in view, unless
Android now calls its slot empty, or one found earlier in the look). A card found that way keeps its reader, so the
switcher keeps it. A slot telephony refused whose reader by SIM number answers "no such applet" to every ID is settled
(and marked as no eSIM manager where Android says it's an ordinary SIM), so it isn't asked again at every look; telephony's
own "no eSIM manager" mark is only kept where the readers agree or have nothing to say.

When the only chip that answered is the phone's own and another slot holds an eSIM card that was busy just then (no free
channel, an unknown error, or it opened and failed; not a refusal), the held-back screen says the card didn't answer and
offers "Try again" (`Lpa.quietSlot`), not "It's my removable card".

`HalDiscoveryTest` runs the real `Lpa` over the real `CardShell` with stand-ins for that Pixel, and for a phone whose
telephony refuses the helper everything.

- **Loading it.** The HAL's Java interface isn't among the classes every process has: it comes inside Android's own
  secure-element app, `com.android.se`. The helper asks the package manager where that system app's APK is
  (`MATCH_SYSTEM_ONLY`), accepts it only on a read-only system partition (`CardShell.systemApk`: never `/data`, so never
  code an app could have written), and loads the interface from it with a `PathClassLoader`, once per process. This
  happens the first time the app asks the HAL, so a phone telephony serves never pays for it (a few milliseconds). Plain
  `Class.forName` can't find the class, which is why the first version of this route never ran.
- **Using it.** The helper looks up `.../SIM1`, `.../SIM2` from `ServiceManager` by the reader the app names, and files
  the channel under the request's slot. It answers as telephony would: status 2 (no such reader, no card in it, or it
  refused the helper, which ends the app's search on that reader), 3 (no such applet, so the app tries its next ID) or 4.
  A HAL channel whose number a telephony channel on that slot already has is given back. It calls `isCardPresent`,
  `openLogicalChannel`, `transmit` and `closeChannel`, and **never `init` or `reset`**: the HAL is shared with Android's own
  secure-element service and other apps' channels, and those calls would reset them. So it works only once that service
  has set the HAL up. The HAL sends the APDU straight to the card, so the helper puts the logical channel in the class byte
  itself (`CardShell.classByte`, ISO 7816-4); a channel the HAL numbers past 19 is given back unused. A failed transmit
  keeps its channel, so the app's close gives it back, as Android's own secure-element app does. A channel is forgotten
  without a close only where it's known to be gone: the HAL's process went away, or the card answered 6881 (channel not
  open, as after it restarted). The HAL may have given that number to someone else by then.
- **Who may use it.** That is a ROM and SELinux matter. AOSP's policy lets ADB shell look the HAL up but not call it: its
  clients are the secure-element app, OMAPI and, on userdebug builds only, `su`. Android reports the refused call as a
  `DeadObjectException`, the same exception as for a HAL (or the phone process) that went away. The helper tells them
  apart by the HAL's binder: still alive after a refusal, when it leaves that reader alone for a minute
  (`HAL_RETRY_MS`); dead after the HAL's process went, when the next open looks it up
  afresh. Neither is taken for the phone process dying. So over Wireless debugging or Shizuku the route helps only on
  makers' builds that let shell call the HAL (users of another app report this on some Xiaomi and OPPO builds), and with
  root where the root manager's policy allows it.
- **Tested** on Android 14, 17 and 17 QPR2 emulators against a stand-in HAL: the helper reads the EID through it when
  SELinux allows the call (permissive, or as root), and answers "no card" when it doesn't. The issue #1 Pixel was
  rebuilt on a rooted Android 14 emulator (stand-in telephony in the helper, stand-in HAL whose `SIM1` is an empty chip):
  0.5 showed "No eSIMs yet", 0.6 lists the card's eSIMs. Not yet on a real phone or a real HAL.

This changes the Android access route only; it does not rewrite the card's access rules, patch Android or defeat the
operator's own profile authentication.

### Card name and maker (Details)

Removable eSIM cards carry a profile of their own besides the eSIMs in their list, which the card turns to when none of
those is on; its provider name is the card's brand (a 5ber says "5ber"). It isn't in the card's eSIM list, and its files
can only be read while it's the one on, so the app takes the name from Android's records instead. The helper's
`cardRecords` request (read-only, no card I/O) returns, for one EID only, Android's record of each profile it has seen on
that card (`ISub.getAllSubInfoList`, matched on `getCardString`, or `getCardId` on Android 9): ICCID, the name Android
shows, where that name came from (from Android 10; 1 and 4 mean read from the card), Android's number for it and whether
it's in use. `CardFacts.name` picks, in this order of trust: a record in use that isn't one of the card's eSIMs; the
earliest such record (one matching the brand of a maker's eSIM manager ID first, where the card answered to one); that
brand itself. The app remembers each card's name and the eSIMs it has seen on it (`ProfileStore`), so a deleted eSIM's
leftover record isn't taken for the card's own, and a name stays known where Android's records can't be read (direct
access, Android 6-8). Limits: no name if the phone never saw the card with no eSIM on; an eSIM deleted before this app saw
it can still pass for the card's own name when it's the earliest record.

"Made by" comes from the EID's first 8 digits, which the maker's GSMA-signed certificate ties to it (only `89086030`,
Eastcompeace, checked so far), then from the card's `sasAcreditationNumber` (GetEUICCInfo2): the GSMA accreditation of the
factory for UICC production, `company-site-UP-expiry`, looked up by company and site (`CardFacts.SITES`, from the GSMA's
own SAS-UP certificates). The expiry in it is the certificate's, written into the card when it was made: an old date is
normal. It names who built the card, not the silicon.

Android 17 QPR2's emulator image (CP41) adds the calling package to `iccTransmitApduLogicalChannelByPort`, after the
slot and port; the helper matches that form too and passes `com.android.shell`. That argument is the calling package:
telephony checks it belongs to the caller (`com.android.shell` passes for shell and for root, another name throws).
Android 17 as released (CE2A) still has the older form. On that QPR2 image telephony also refuses shell every standard
ISD-R identifier, the partial and the lower-case one included: it normalises the ID before comparing it, where CE2A
compares the exact upper-case text only. So there telephony can't reach a standard card at all. It is a pre-release
image; whether phones get the same is unknown.

Complete card operations are serialized on one process-wide thread. Installs and switches run under a short foreground
service and a capped wake lock (`CardWork`), so leaving the app can't cut one off midway. A lost connection aborts the
operation; mutating APDUs are never automatically replayed. Before each switch, turn-off and delete the app re-reads the
list and checks that the ID it's about to use still belongs to that eSIM, and an install first checks it's the same card. Requests time out after 45 s, or
15 s for reads (listing eSIMs, card details), after which card access restarts.
On phones with a built-in eSIM, slots Android reports as a fixed eUICC are tried
last, so a removable card elsewhere wins. If that chip is all it finds, the app holds it back and says so, instead of
showing it as the card, until someone confirms it's their removable card. The partial ISD-R identifier is
still required on the tested phone because its telephony service restricts the
full standard identifier to the system LPA; the lower-case full identifier and the Secure Element HAL route above are
fallbacks for phones where the partial one or telephony itself won't do, and neither has been confirmed on a real
phone yet (the lower-case one doesn't help on Android 17 QPR2's emulator image, see above).

### Several cards

A card is known by its EID; its slot and eSIM manager ID only say where it answers, and only for one way in, as OMAPI
numbers Android's SIM slots and the helper physical ones. One `Lpa` object per card (`Lpa.at`) carries that address, so
an operation, a notice run or a Details read stays on its card whatever comes into view. The card in view is found as
before (`discover`); once its list is on screen, `Lpa.scan` looks through the other slots on the card thread, outside the
busy gate, and only ever changes what the switcher offers, never the view, which may have a dialog or form open. A scan
whose answer arrives after card access dropped or after someone picked a card is thrown away (`lookEpoch`).

The helper's `slots` request now also says, from what Android holds in memory: `present`, `active` and `logical` (through
ports on Android 13+, where the slot's own getters for these throw), the card's `eid` from `getUiccCardsInfo` (or a
32-digit card ID of an eUICC), and a `token`, a short hash of the card ID that changes with the card or its active eSIM.
Each getter is guarded on its own, and a missing field means "doesn't say", never "no". The scan costs the card nothing
for a slot whose token is unchanged, a slot Android says is empty, or one it isn't using (opening one throws on Android
10+). Without tokens (Android 6-8, Android 9 without root, direct access) it asks the usual ID only. An ordinary SIM that turned out to have no
eSIM manager under any ID is remembered by way in, slot and token, until Reconnect.

Android's `removable` flag is a per-tray setting from the phone's maker, not a fact about the card, so it only orders
tries and picks words, and only counts on phones that declare the eSIM feature. The phone's own chip stays out of the
switcher until someone says it's theirs; an inactive chip makes no row, so a tray card on an eSIM phone sees no switcher.

A refresh stays at the card's own address only for a card someone chose from several (`refreshStays`): then its absence
is what gets said, not another card's list. Everyone else's refresh looks for the card as before. The Add eSIM form has a
target card (`addTarget`): the one in view when opened in the app, none for a code from another app while there are two
cards, and Install waits for the first scan, so a single-use code can't go to the wrong card.

After a switch or turn-off with another card's eSIM on, Android may move or clear the default data, calls and texts SIM;
the app reads which ones the old eSIM had (public getters, Android 10+) and points to SIM settings. It never sets calls
or texts itself. "Use for mobile data" starts ticked where mobile data is now, or where no SIM has it, and with two
cards that tick isn't remembered.

### Settings and Theme

Settings is a pane of `MainActivity`, not an activity: it needs the view model, opens the panes that set things up
(mobile data and roaming, switching to ADB), and stays open through a reconnect. The pane below it keeps its state
(half-done setup steps, a pairing, the list's scroll) in a `SaveableStateHolder`; any other move starts a pane afresh.
**Details** keeps only the card's own facts, and **Stop card access** sits at the bottom of Settings.

**Theme** is Same as phone, Light or Dark, kept under `theme` in the `access-method` prefs, which every start reads
anyway. Android 12 and newer keep an app's night mode themselves (`UiModeManager.setApplicationNightMode`, no
permission), from the splash screen on and relaunching the app's screens, so nothing is read at start there; a choice
made on Android 11 is handed over again after an update. Android 6 to 11 get it in `attachBaseContext` of
`MainActivity` and `AboutActivity` as an override configuration with only the night bits (`fontScale = 0f`, or Android 7
would reset the text size), and a pick recreates the screen. Compose's `isSystemInDarkTheme`, dialogs, sheets and the
bar icons follow either way. The scanner stays dark, and `themes.xml` and the manifest don't change. On Android 6 to 11
a cold start shows the phone's colours for a moment when they differ.

## Libraries

ADB: [libadb-android](https://github.com/MuntashirAkon/libadb-android) 3.1.1, which brings
[spake2-android](https://github.com/MuntashirAkon/spake2-java) 2.2.1 and Bouncy Castle 1.81 for pairing, and Bouncy Castle's
Base64 for sending the ADB key. Android 11 pairing:
[HiddenApiBypass](https://github.com/LSPosed/AndroidHiddenApiBypass) 4.3. Shizuku client:
[Shizuku-API](https://github.com/RikkaApps/Shizuku-API) 13.1.5. QR codes: [ZXing](https://github.com/zxing/zxing) core 3.5.4,
and in the google build the
[Google code scanner](https://developers.google.com/ml-kit/vision/barcode-scanning/code-scanner) 16.1.0.

The app's own code is under the [MIT License](../LICENSE). The libraries keep their own, and the "Libraries & licenses"
page, linked from About, shows them, with their texts or, for Google's ML Kit terms, a link, so they reach people with the
APK:

- `ui/Licenses.kt` lists the rows. The google build adds its own through `QrScan.LIBRARIES`.
- `assets/licenses` holds the texts, in `src/main` and in `src/google`. They're read only when someone opens one.
- libadb-android is GPL-3.0-or-later or Apache-2.0, and the app takes Apache-2.0. Four of its files are also BSD-3-Clause
  and one MIT.
- spake2-android is LGPL-3.0, so its notice links its source and this project's. Its native library has ISC, MIT, LGPL-2.1
  and Apache-2.0 parts. The GPL's text is there because the LGPL builds on it.
- Bouncy Castle has its own MIT-style license. Shizuku-API is MIT. HiddenApiBypass, ZXing, Compose, AndroidX, Kotlin and
  Material Icons (most of `res/drawable`) are Apache-2.0. ZXing and kotlinx have NOTICE files. Kotlinx's is in its
  repository at `license/NOTICE.txt`, not in the jar, so a bump has to look there.
- Google's own libraries come under the ML Kit Terms of Service and the Android SDK License, its Firebase and Data
  Transport ones under Apache-2.0, and they carry notices for what's inside them. `google-third-party.txt` merges their
  `third_party_licenses.{json,txt}`, each entry once. Entries that are only the Apache License are a list of names, and
  the rest stay word for word. It adds about 63 KB to the google APK.

`LicensesTest` fails when a direct dependency's version no longer matches its row. On a bump, read that library's license
and NOTICE at the new version, and its dependencies': `:app:dependencies --configuration standaloneReleaseRuntimeClasspath`,
and the same for `googleReleaseRuntimeClasspath`. The APK has no other license files: AGP drops most of them, and the build
drops AndroidX's copies.

### Building with a changed spake2-android

Its license lets people rebuild the app with their own version of it. Its native part is a separate file in the APK, but
its Java part is minified into the app's code, so that takes this project's source:

1. Get its source, with the native part as a submodule:
   `git clone --recursive --branch 2.2.1 https://github.com/MuntashirAkon/spake2-java.git`. Build it with its own Gradle
   wrapper, `:android:assembleRelease`, which needs the NDK and CMake.
2. Put the AAR in `app/libs`, and in `app/build.gradle.kts` replace the libadb-android line with:

   ```kotlin
   implementation("com.github.MuntashirAkon:libadb-android:3.1.1") { exclude(group = "com.github.MuntashirAkon.spake2-java") }
   implementation(files("libs/spake2-android-release.aar"))
   ```

3. Build as under "Build and checks". Your APK has its own signature, so it can't install over a release: uninstall the
   app first, which also loses the pairing.
