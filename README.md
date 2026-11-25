# GlucoPilot

A **bright, driver-focused Android Auto cockpit for people with type 1 diabetes**. It turns CGM
data (xDrip+ local broadcasts or Nightscout) into a value that can be read at a glance on the car
screen. It does not recommend doses and does not collect data.

```
android/    Android app (Kotlin + Android for Cars App Library)
landing/    Marketing site (dependency-free HTML/CSS/JS, Android Auto hero shot, signed APK download)
tools/      Executable security and smoke checks
```

> **Medical notice:** GlucoPilot is not a medical device. It does not recommend treatment or
> insulin doses. Alerts are advisory; always rely on your sensor's official app and your
> healthcare professional's guidance.

---

## 1. Open-source foundations

### Android Auto — Google's official Car App Library samples

The most solid, maintained and production-ready foundation is Google's own repository:

- **[android/car-samples](https://github.com/android/car-samples)** — Car App Library
  samples (`car_app_library/showcase`, `navigation`), Apache-2.0.
- **[android/car-codelabs](https://github.com/android/car-codelabs)** — starter/finished
  projects from the official "Car App Library fundamentals" codelab.

GlucoPilot **borrows the architecture, not the code**: `CarAppService` + `Session` + `Screen`,
template-based UI, `automotive_app_desc.xml`, the `com.google.android.gms.car.application`
metadata and testing with the Desktop Head Unit (DHU). Version used:
`androidx.car.app:app:1.7.0` (`app-projected` for Android Auto).

Why this foundation: third-party "Android Auto template" repositories are often abandoned or use
custom drawing that conflicts with Play's driver-safety rules. Car App Library leaves screen
size and theme differences to the host and is the only official path that can pass Play review.

### CGM — notification listener + xDrip+ broadcast contract + Nightscout REST API

- **[NightscoutFoundation/xDrip](https://github.com/NightscoutFoundation/xDrip)** — an open-source
  data hub for Dexcom, Libre, Medtronic and Eversense sensors. The inter-app local broadcast
  (`com.eveningoutpost.dexdrip.BgEstimate`) is documented there.
- **[nightscout/cgm-remote-monitor](https://github.com/nightscout/cgm-remote-monitor)** — the
  Nightscout server; `/api/v1/entries/sgv.json` and the `api-secret` header.

GlucoPilot **does not copy source code** from either project; it implements only the public,
documented protocols. As a result:

- It does not conflict with the sensor maker's official app (xDrip+/Nightscout own the data).
- The user decides where their data lives.
- New sensors are supported when the source app is updated.

The alternatives we compared (Juggluco, AndroidAPS, strimma) use the same ecosystem; the xDrip
broadcast + Nightscout pair offers the widest device coverage and the most mature API.

---

## 2. Features

**Car screen (Android Auto)**

- At a glance: value, trend arrow and band label ("In range", "Low", "Urgent low", …).
- A second line with source, reading age, session-scoped time in range, sensor battery (when
  the CGM app states it) and the CGM app's own warm-up / signal-loss / sensor-end state.
- A trend line for the last 90 minutes over the target band, drawn into the cockpit image slot.
- Clear warnings for stale data (15 min+) and connection errors.
- "Low soon" / "High soon" when the measured rate of change would cross a threshold within
  30–45 minutes; at most once per half hour and muteable like the range alerts.
- "Got it" on an urgent low, which stops the five-minute repeat for half an hour.
- "Refresh" and "Recent readings" (last 12 readings) actions.
- `PaneTemplate` / `ListTemplate` — Car App Library's driver-safety templates.
- While navigation or music is open, a silent, persistent car notification: current value, trend and age.
- One tap on the notification returns to the cockpit; Back returns to the app you came from.
- A short high-priority alert when out of range; a "Mute 30 min" action.

**Phone**

- A bright, card-based setup screen with live value, trend and source age.
- xDrip+ listening switch; Nightscout URL + API key.
- Target range thresholds (low 55–120, high 120–300 mg/dL) and mg/dL ↔ mmol/L display.
- Driving mode switch: background monitoring + car notification.
- "I'm changing my sensor" pause: keeps the "No glucose data" reminder quiet for two hours
  while a replacement sensor warms up.
- Setup check: four rows that show whether notification access, notification permission, a
  live CGM value and the car connection are actually working.
- "Last drive" summary: minutes, time in range, lowest value and low count — four small
  numbers kept on the phone, nothing uploaded.
- Optional write-only Health Connect export and a settings export/import file.
- Notification permission flow (Android 13+).

**Alerts**

- Only outside the target bands; at most one notification per band every 10 minutes.
- An urgent low (54 mg/dL or below) repeats every five minutes, at most three times;
  "Got it" on the notification quiets the repeat for half an hour.
- A projection alert when the current slope crosses the low or high threshold inside the
  horizon, labelled "Low soon" / "High soon" and throttled to once per 30 minutes.
- One quiet "Sensor battery low" notice at 15% or less, once per low-battery episode.
- Car notification via `CarAppExtender`; high-priority channel, silent repeats.
- If a reading that was flowing in this driving session stops for 15 minutes, one **"No glucose
  data"** reminder is posted per stale episode and cleared when fresh data returns, so a silent
  sensor never leaves a stale number looking current. `StaleGuard` keeps it to one alert until
  data flows again. The CGM app's own warm-up or signal-loss notification explains the gap
  instead of a generic warning.
- Only readings that arrive after monitoring started count as flowing data. A sensor change or
  warm-up leaves a value that was already on the phone, even a few minutes old; that is shown as
  an age in the cockpit and notification instead of an alarm.
- The phone's **"I'm changing my sensor"** button, and a **"Sensor change"** action on the
  reminder itself, pause it for two hours. The alert returns on its own if the sensor is still
  silent when the pause ends.

---

## 3. Architecture

```
Android Auto host
   │  (template requests)
   ▼
GlucoPilotCarAppService ──► Session ──► GlucoseScreen / TrendScreen
                                              │ StateFlow
                                              ▼
                                   GlucoseRepository  ◄── XDripBroadcastReceiver
                                     │        │                (local broadcast)
                                     │        └── NightscoutClient ──► HTTPS /api/v1
                                     ▼
                              GlucoseNotifier (bands + throttle)
                                     │
                                     ▼
                          CarAppExtender notification
```

- `GlucoseRepository` is the single source of truth; the xDrip broadcast and Nightscout polling
  write to the same `StateFlow<GlucoseState>`.
- Screens are `LifecycleOwner`s; they collect the flow in `onStart`, call `invalidate()` on
  changes and cancel in `onStop`.
- Android 8.0+ (API 26). Networking runs off the main thread; the default polling interval is 5 minutes.

### Living alongside Android Auto's built-in features

Android Auto does not allow third-party apps to open a persistent window (overlay) on top of
navigation or media. GlucoPilot therefore does not take over the screen; it stays alongside the
default experience through four mechanisms:

1. **Silent status notification** — while driving mode is on, a persistent, silent car
   notification updates with every reading. When Maps or music is in front, the notification
   area shows the value, trend, band and reading age.
2. **Short alert** — when the value leaves the target range, a high-priority notification comes
   forward briefly; it is limited to once every 10 minutes per band and can be silenced with the
   "Mute 30 min" action.
3. **Stale-data reminder** — if readings that were flowing in this session stop for 15
   minutes, one high-priority "No glucose data" notification appears, so a dead sensor never
   leaves an old value looking current. It is cleared as soon as fresh data returns. A gap that
   predates the drive (sensor change, warm-up) stays visible as a reading age instead of an
   alarm, and the phone can pause the reminder for two hours while a new sensor settles.
4. **One-tap cockpit** — tapping the notification opens GlucoPilot's car screen via
   `CarPendingIntent`; Back returns the user to Maps or music.

To keep notifications alive, `GlucoseMonitorService` runs as a `specialUse` foreground service;
Android 14+ requires the subtype declaration in the manifest. The subtype declaration must be
supplied when submitting to Play. Users enable or disable the same behavior from the "Driving mode" card
on the phone.

When the car screen opens, `MonitorLauncher` starts the service automatically; the phone app
does not need to be opened. If notification permission is missing or driving mode is off, a
"Warning" row in the cockpit explains what to do.

---

## 4. Setup

### Requirements

- Android Studio (Ladybug or newer) **or** JDK 17 + Gradle 8.9 + Android SDK 35.
- For Android Auto testing: a phone + car, or the **Desktop Head Unit (DHU)**.
- For CGM data: any CGM app already on the phone (read once through notification access), an
  xDrip+ broadcast, or a reachable Nightscout server.

To build without a system-wide installation, the repository contains a portable toolchain under
`.toolchain/` (git-ignored): Temurin JDK 17.0.21.1, Gradle 8.9 and Android SDK 35 (platform 35,
build-tools 34/35, platform-tools). `sdk.dir` in `android/local.properties` points to this SDK.

### Build

```bash
cd android
export JAVA_HOME="$PWD/../.toolchain/jdk"
export GRADLE_USER_HOME="$PWD/../.toolchain/gradle-home"
export ANDROID_HOME="$(cygpath -m "$PWD/../.toolchain/android-sdk")"   # Windows
../.toolchain/gradle-8.9/bin/gradle :app:assembleDebug :app:testDebugUnitTest
```

If you open the project in Android Studio, `local.properties` is ready; Studio uses its own
Gradle, and `./gradlew` commands work as well. The repository intentionally does not include
`gradle/wrapper/gradle-wrapper.jar`; generate it with `gradle wrapper --gradle-version 8.9` if needed.

### Signed release APK

The release signature is read from `android/keystore.properties` (outside git) and
`android/keystore/glucopilot-release.jks`:

```bash
cd android
../.toolchain/gradle-8.9/bin/gradle :app:assembleRelease
```

Output: `android/app/build/outputs/apk/release/app-release.apk`. Back up both files somewhere
safe: without the same key, you cannot publish an update on Play. The key and password are not
committed; provide them as CI secrets.

### Testing with the DHU

```bash
# Requires Android SDK platform-tools and cmdline-tools
sdkmanager "extras;google;desktop_head_unit" "platforms;android-35"
adb forward tcp:5277 tcp:5277
# Start the DHU (desktop-head-unit inside the SDK)
```

Enable Android Auto developer mode on the phone, allow "Unknown sources", start GlucoPilot and
select the app in the `IOT` category in the DHU.

### CGM connection

**Any CGM app — default, nothing to configure inside it:**

1. Open GlucoPilot once and tap **Allow notification access** (a single system toggle).
2. Keep using the sensor app you already have (Dexcom, Libre 3, xDrip+, Juggluco, Diabox,
   Eversense…).
3. Every new value that app posts is read from its notification and shown on the car screen.

The value, unit (mg/dL or mmol/L), trend arrow and freshness are parsed and validated
(20–600 mg/dL, at most 60 minutes old). Reading happens on the device only; no data is uploaded
and no extra permission is requested.

**Source check on the phone:** when a value does not reach the car, the *Source check* card on
the phone screen shows the last reading together with the app it came from, and the last ten
CGM-looking notifications marked **accepted** or **rejected** with the reason (no text, no value,
not recognised as a CGM app, outside 20–600 mg/dL, older than 60 minutes). The list is kept in
memory only, disappears with the process and is never written to disk or logged, so no glucose
history is created.

**Car-bound monitoring:** GlucoPilot watches the official `androidx.car.app.connection` signal.
The moment the phone is projected into a head unit, monitoring starts in the background and the
current data is refreshed, so the cockpit and the car notification are ready without opening
anything. When the connection ends, the foreground service and its notification are stopped —
nothing runs in the background while the phone is not in a car, and nothing is started at boot.
The "Start automatically when Android Auto connects" switch controls this behavior.

**xDrip+ (optional, best quality):**

1. xDrip+ → Settings → Inter-app settings → enable **Local broadcast**.
2. GlucoPilot → "Listen to xDrip+ broadcasts" is enabled automatically when xDrip+ is installed.
3. When xDrip publishes a new reading, the car screen updates automatically.

**Nightscout:**

1. Define `API_SECRET` on your Nightscout site.
2. In GlucoPilot, enter `https://your-site.example` and the API key.
3. Save → "Try now".

> Only **HTTPS** is accepted. `http://` Nightscout installations on the LAN are rejected by
> design; terminate TLS at a reverse proxy.

**CGM app detection:** on launch, GlucoPilot queries only the known CGM apps declared in the
manifest `<queries>` (no `QUERY_ALL_PACKAGES`):

- **xDrip+ and Juggluco** — verified xDrip broadcast sources; listened to automatically, and their
  notifications are read as a second path.
- **FreeStyle LibreLink** (TR, GB, DE, FR, IT, ES, US, IN, BR, CH, BE, SA, NL, CA, AU, NZ)
  and **Libre 3 (US)** — read automatically from the app's own notification.
- **Diabox** — read automatically from its notification; sending an xDrip `BgEstimate` broadcast
  has **not been verified**, so it is never treated as a broadcast source.
- **Dexcom G6/G7** — read automatically from the app's own notification.

When an xDrip-compatible source is found, the phone shows "Detected: …"; a choice the user made
on the switch is never overridden. The catalog is pure Kotlin and is covered by unit tests
(`CgmAppCatalogTest`).

Android has no public API for one app to read another app's database, so GlucoPilot reads the
notification the CGM app already posts (enabled once through the system notification-access
grant) and keeps the xDrip+ broadcast path when a source publishes one. Values are validated and
stay on the device; the CGM apps themselves need no setting at all.

---

## 5. Security model

| Topic | Implementation |
| --- | --- |
| Transport | `usesCleartextTraffic=false` + network security config; HTTPS only |
| Host validation | All hosts in debug; only Google-signed Android Auto/AAOS allowlist in release |
| URL validation | Rejects scheme, host, port, user info, query/fragment and `..` paths |
| Redirects | `instanceFollowRedirects=false`; 3xx is an error |
| Secret | The raw value is **never stored**; only the SHA-1 digest Nightscout expects |
| Storage | Private SharedPreferences; excluded from cloud backup and device transfer |
| Incoming data | 20–600 mg/dL range, +5 min future tolerance, 24-hour history limit |
| Network response | 512 KB cap; only known fields are parsed |
| Logging | No logging in the data layer; URLs/keys are never leaked |
| Permissions | INTERNET, POST_NOTIFICATIONS, FOREGROUND_SERVICE (+ SPECIAL_USE), the xDrip broadcast permission, the system notification-access binding, and write-only Health Connect blood glucose (no QUERY_ALL_PACKAGES, no boot start) |
| Health Connect | Optional and off by default; grants are checked on every write, nothing is read back, and revoking the permission stops the export |
| Settings export | User-initiated file with the Nightscout URL, target range and units; it carries the SHA-1 digest Nightscout requires, never the raw key |
| Notifications | Two channels: silent status and high-priority alert; 10 min throttle + 30 min mute |
| Background | `specialUse` foreground service, `exported=false`; no overlay permission |

**Limits:** an xDrip broadcast can be spoofed by a malicious app that holds the same permission.
GlucoPilot validates the value and timestamp, but there is no cryptographic proof of the source
(the xDrip protocol does not provide one). For critical decisions, rely on the sensor's official
app. The Nightscout API key travels as a SHA-1 digest; that is the protocol's design, not a strong
password-storage method — choose a long, random key.

---

## 6. Tests

**Unit tests** (JVM, pure logic — no network or Android emulator):

```bash
cd android && ./gradlew :app:testDebugUnitTest
```

Coverage: notification parsing (mg/dL and mmol/L, both trend notations, unit-less values from
known apps, unrelated apps rejected, stale/future timestamps), Nightscout URL security and the
SHA-1 vector, band boundaries, trend thresholds, Nightscout JSON parsing
(malformed/missing/future-dated records), xDrip broadcast validation and slope-unit conversion,
unit conversion, relative time and alert policy (throttle, band change, mute window, urgent
repeats and acknowledgement), stale session gating and the sensor-change pause, rate-of-change
projection, drive summaries, sensor status text, sensor battery parsing, settings export/import
and Health Connect record mapping.

**Static security and code checks** (run on this machine):

```bash
node tools/security-check.mjs
python tools/check_xml.py
```

**Real-device verification (single command):**

```bash
bash tools/verify-device.sh
```

It finds a connected device, installs the debug APK, grants notification permission, opens the
phone app and uses `dumpsys` to check the status notification and the foreground
`GlucoseMonitorService`. Customize with the `ADB`, `PACKAGE` and `APK` environment variables.

**Landing page smoke test:**

```bash
python -m http.server 4173 --bind 127.0.0.1 --directory landing
node tools/smoke.mjs
```

`security-check.mjs` covers manifest hardening, exported components, permissions, HTTPS
enforcement, redirect rejection, secret storage, log leakage, dependency pins and the landing
page's CSP/inline-script/external-resource status.

**Published site check (single command):**

```bash
node tools/live-check.mjs
```

It checks the deployed landing (`https://glucopilot-civgdk.freebuff.site`, override with
`BASE_URL`): the page references the hero image and the APK, the hero image is a real JPEG of the
expected size, and the APK arrives as `application/vnd.android.package-archive` with the exact
release size, a download disposition and a matching SHA-256.

**Car-bound device check (needs a phone):**

```bash
bash tools/verify-device.sh
```

Installs the debug APK, grants notification permission and notification access, then checks that
the driving service is **not** running while unplugged, is running in the foreground with its
status notification once the phone is projected (car mode on), the notification listener is bound,
and a live CGM value is visible in the notification stream. It also prints the connected CGM app
value it found. Without a phone it exits with `No connected device`.

### Verifications run on this machine

- `:app:assembleDebug` — **BUILD SUCCESSFUL**; APK: `android/app/build/outputs/apk/debug/app-debug.apk` (~4.5 MB, minSdk 26 / targetSdk 35; English resources only).
- `:app:testDebugUnitTest` — **132 tests, 0 failures**.
- `node tools/security-check.mjs` — **32/32 PASS**.
- `python tools/check_xml.py` — 19 resource XML files are well formed.
- `node tools/smoke.mjs` — landing page 7/7 HTTP 200, including both extra stylesheets and the signed APK download.
- `node tools/live-check.mjs` — **3/3 PASS** on the published site: hero JPEG 97.7 KB, APK
  `application/vnd.android.package-archive` 898.6 KB (v1.2.0) with SHA-256 `464a15e4…`.
- `apksigner.bat verify --print-certs` (build-tools 35.0.0) — 1.2.0 and 1.1.3 both **Verifies**
  with the same signer certificate, SHA-256 `6d882da0…9f96` (details below).
- Landing page measured at 1024 and 1200 px widths: no horizontal overflow, the notification
  stays inside the map, and the cockpit falls back to a single column on mobile (430 px).

### APK signing continuity (1.1.3 → 1.2.0)

Android only installs an update when the signing certificate matches the installed app, so the
published APK was checked with the SDK's `apksigner.bat` (build-tools 35.0.0, local JDK). 1.1.3 is
no longer on the site; recover it from git first.

```powershell
git show 609a124:landing/download/GlucoPilot-1.1.3.apk > "$env:TEMP\GlucoPilot-1.1.3.apk"

& "$env:ANDROID_HOME\build-tools\35.0.0\apksigner.bat" verify --verbose --print-certs `
  .\landing\download\GlucoPilot-1.2.0.apk
& "$env:ANDROID_HOME\build-tools\35.0.0\apksigner.bat" verify --verbose --print-certs `
  "$env:TEMP\GlucoPilot-1.1.3.apk"
```

| Check | 1.1.3 | 1.2.0 |
|---|---|---|
| APK SHA-256 | `d496ef20…7629` | `464a15e4…52cb` |
| Signer certificate SHA-256 | `6d882da012221b76648e69d22bb951e43ca108b54fe920912b5d43299c929f96` | same |
| Certificate DN | `CN=GlucoPilot, OU=Mobile, O=GlucoPilot, L=Istanbul, C=TR` | same |
| Key | RSA 4096 | RSA 4096 |
| Public key SHA-256 | `6f4d1fa6…3056` | `6f4d1fa6…3056` |
| Signature schemes | v2 only | v2 only |
| `apksigner` result | Verifies, 1 signer | Verifies, 1 signer |

Both APKs carry the same certificate and public key, so 1.2.0 installs as an ordinary update over
1.1.3; there is nothing to re-sign. v2-only is expected here: minSdk is 26, so JAR/v1 signing is
not needed, and AGP signs with the v2 scheme.

If the fingerprints ever differ, the update was signed with another key — a debug keystore, a
regenerated keystore, or a build on a machine without `android/keystore.properties` (that release
build is left unsigned). Android then rejects the update with
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`. The fix is to restore the original release key (encrypted
backup in `backups/`) and rebuild/re-sign with it; deliberately changing keys requires
uninstalling the old app, which deletes its local settings, or Play App Signing key rotation.

**Not verified:** a real car or DHU connection, Play pre-review, a physical CGM source and an
end-to-end Health Connect write (no Health Connect app on this machine) are not available in
this environment. In particular, where and for how long car notifications appear
depends on the host/car software; a "persistent window over the foreground app" is not supported
by Android Auto. If battery optimization kills the foreground service, notification updates stop;
the user can exclude the app from battery optimization.

---

## 7. Play / distribution notes

- Car App Library apps must declare a **supported category** on Play; this project uses
  `androidx.car.app.category.IOT` (a CGM is an IoT health device). Play review may ask for the
  rationale for the category.
- Driver safety: no free drawing, video or keyboard on the car screen; templates only. This is a
  deliberate design constraint.
- The car screen's light/dark theme is controlled by the host; the app's "bright theme" idea is
  carried by the phone UI, icon and notification copy.
- There is no medical-device claim; declare accordingly under Play health policies.

## 8. License and attribution

- Android for Cars App Library and Google samples: Apache-2.0.
- xDrip+ and Nightscout are distributed under their own licenses; their source code is not in
  this repository, only the documented protocols are used.
- GlucoPilot's app code and marketing site were written for this project.
- Android Auto is a trademark of Google LLC.
