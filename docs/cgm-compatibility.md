# CGM app compatibility

SokeriPilot never talks to a sensor. It reads the value another app already produces, so "support"
means: *does this app's notification (or broadcast) carry a parsable glucose value?*

Two integration paths exist:

| Integration | How the value arrives | Setup inside the CGM app |
| --- | --- | --- |
| `NOTIFICATION` | The app posts a notification containing the value; the listener parses it | None |
| `XDRIP_BROADCAST` | The app sends an xDrip-compatible `BgEstimate` broadcast | Enable the xDrip+ broadcast/companion option |

The catalog lives in `android/app/src/main/java/app/sokeripilot/data/CgmAppCatalog.kt`; the
notification parser and the broadcast mapper are covered by unit tests, so a new format is added
with a fixture, not by hope.

## Status legend

- **Parsed** — a parser fixture covers the notification (or broadcast) this app produces, so the
  value is extracted in a unit test. This is what "supported" means here.
- **Detected only** — the package is in the catalog so the app can be named and pointed at, but no
  fixture exists yet. It may work; if the app's notification text differs, no value is read.

## Catalog entries

| App | Package(s) | Path | Status | Notes |
| --- | --- | --- | --- | --- |
| Dexcom G6 | `com.dexcom.g6` | Notification | Parsed | Value in the ongoing notification body |
| Dexcom G7 | `com.dexcom.g7` | Notification | Parsed | Same pattern, separate package |
| FreeStyle LibreLink | `com.freestylelibre.app.{tr,gb,de,fr,it,es,us,in,br,ch,be,sa,nl,ca,au,nz}` | Notification | Parsed | One package per region |
| FreeStyle Libre 3 | `com.freestylelibre3.app.us` | Notification | Parsed | Additional regional packages can be added the same way |
| xDrip+ | `com.eveningoutpost.dexdrip` | Broadcast (+ notification fallback) | Parsed | Best source; broadcast must be enabled |
| Juggluco | `tk.glucodata` | Broadcast (+ notification fallback) | Parsed | Sends xDrip-compatible broadcasts |
| Diabox | `com.bubblesoft.android.diabox` | Notification | Parsed | No verified broadcast |
| CamAPS FX | `uk.org.cambsdiabetes.camaps` | Notification | **Detected only** | Catalogued for naming; no fixture yet, so parsing is unproven |
| AndroidAPS | `info.nightscout.androidaps` | Notification | **Detected only** | Catalogued for naming; prefer xDrip+ broadcast if both are installed |

The two "detected only" rows were added because their packages are stable and worth naming in the
UI, not because their notification format was verified. A contribution that adds the fixture (and
moves the row to *Parsed*) is welcome — see the steps below.

Packages are matched only against the declared `<queries>` list in `AndroidManifest.xml` — Android 11+
package visibility forbids a blanket scan, and `QUERY_ALL_PACKAGES` is deliberately not used.

## Adding an app

1. Add a `CgmApp(...)` row to `CgmAppCatalog.KNOWN` with the package name and the integration path.
2. If the value arrives by notification, add a parser fixture in
   `CgmNotificationParserTest` for the exact notification text (ticker, title, text and subtext).
3. If it arrives by broadcast, extend `XDripReadingMapper` and its test.
4. Declare the package under `<queries>` in `AndroidManifest.xml`.
5. Run `gradle :app:testDebugUnitTest`; the catalog and parser tests are pure JVM tests.

## What is *not* supported

- Apps that only keep the value in a database and never expose it (no public API, no notification).
- Encrypted/obfuscated notifications that change per app version: they need a new fixture each time.
- Reading another app's storage directly — Android does not allow it, and SokeriPilot does not try.
