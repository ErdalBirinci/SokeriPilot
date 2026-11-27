# Play Store listing — SokeriPilot

Everything here is ready to paste into the Play Console. Submission itself needs the account
owner: no tool in this repository can create the listing, upload an AAB or answer the review
questions on someone's behalf.

## App name (limit 30)

```
SokeriPilot — CGM in your car
```
27 characters.

## Short description (limit 80)

```
CGM value and alerts beside Android Auto navigation. No account, no ads.
```
71 characters.

## Full description (limit 4000)

```
SokeriPilot shows your CGM data where you actually look while driving: a silent, persistent
Android Auto notification beside Maps or music, and a one-tap cockpit with the value, trend,
band and reading age.

It does not replace your sensor app. It reads the value the CGM app already posts as a
notification — Dexcom, FreeStyle LibreLink, Libre 3, xDrip+, Juggluco, Diabox — after one
Android notification-access grant. Nothing has to be configured inside the sensor app, and
xDrip+ broadcasts or your own Nightscout server stay optional.

WHAT YOU GET

• A car notification that updates with every reading while navigation is open
• A cockpit with value, trend arrow, band, source, reading age, 90-minute trend line and the
  sensor's own warm-up, signal-loss or battery state
• "Low soon" and "High soon" warnings when the current trend would cross your threshold
  (a straight-line projection, not a prediction model)
• Range alerts at most once per band every 10 minutes, urgent-low repeats you can acknowledge,
  and a 30-minute mute
• A "No glucose data" reminder if readings that were flowing stop for 15 minutes
• A "I'm changing my sensor" pause for warm-up gaps
• A last-drive summary of four numbers kept on the phone
• Optional write-only Health Connect export and a settings export/import file

PRIVATE BY DESIGN

No account. No analytics, advertising or third-party SDK. Readings are shown as they arrive and
are not archived by SokeriPilot. Settings stay in the app's private storage and are excluded from
cloud backup. The Nightscout key is stored as the SHA-1 digest the protocol requires, never as the
raw value. Nothing leaves the phone unless you configure your own Nightscout server or turn on
Health Connect.

REQUIREMENTS

Android 8.0 or newer, the Android Auto app and a compatible car or the Desktop Head Unit.
Nightscout needs an HTTPS site.

NOT A MEDICAL DEVICE

SokeriPilot is a viewing and reminder tool. It does not recommend treatment or insulin doses and
does not replace your sensor's official app or your healthcare professional's guidance. Alerts
are advisory.
```

## Store settings

| Field | Value |
| --- | --- |
| App or game | App |
| Category | Health & Fitness |
| Car app category (in-app manifest) | `androidx.car.app.category.IOT` — rationale in `store/category-rationale.md` |
| Pricing | Free |
| Ads | No ads |
| In-app purchases | None |
| Content rating | Complete the questionnaire; declare health/medical content honestly (advisory alerts, no diagnosis) |
| Target audience | 18+ (driving and chronic condition management) |
| Privacy policy URL | `https://erdalbirinci.github.io/SokeriPilot/privacy/` |
| Data safety | Answers below in `store/data-safety.md` |
| Contact email | The developer address on the Play account (not stored in this repository) |
| App access | No login; all functionality available without credentials |

## Upload

Play requires an **AAB**, not the APK the site serves. Build it with the same release key:

```bash
cd android
export JAVA_HOME="$PWD/../.toolchain/jdk"
export GRADLE_USER_HOME="$PWD/../.toolchain/gradle-home"
../.toolchain/gradle-8.9/bin/gradle :app:bundleRelease
```

Output: `android/app/build/outputs/bundle/release/app-release.aab`. The certificate must stay
`6d882da0…9f96`; Play App Signing can keep the same upload key. 1.2.1 was built this way
(1.7 MB) and copied to the git-ignored `dist/` for upload.

## Alternative store: F-Droid / IzzyOnDroid

The app does not depend on Play services, so `metadata/app.glcopilot.yml` holds the F-Droid build
recipe (MIT, `v1.2.1`, `subdir: android`, Gradle build with the release keystore line stripped).
IzzyOnDroid reads the same metadata; both need the version tag to exist before the first build.
The applicationId stays `app.glcopilot` there too, which is what keeps updates working.

## Graphic assets

| Asset | Requirement | State |
| --- | --- | --- |
| Feature graphic | 1024 × 500 PNG, no device frames | `store/assets/feature-graphic.png` — rendered from `store/assets/feature-graphic.html` (`chrome --headless --window-size=1024,500 --screenshot=…`) |
| App icon | 512 × 512 PNG, 32-bit | `store/assets/icon-512.png` — rendered from `store/assets/icon-512.html`, the same droplet mark as the adaptive icon |
| Phone screenshots | 2–8, min 320 px side, at most 2:1 ratio | **Still needs a device.** `bash tools/verify-device.sh` installs the release build; the Robolectric render (`android/app/build/reports/phone-screen.png`, 822 × 4614) is the real phone UI but 5.6:1, outside Play's ratio, and `screenshots/` holds website captures, not app screens. |
| Tablet screenshots | Optional, 7"/10" | Not prepared |

Replace screenshots when the UI changes; Play rejects devices frames, status bars with personal data
and images with burned-in text that is not part of the app.
