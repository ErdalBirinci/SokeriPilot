# Notification reliability

The car value is only as good as the notification that carries it. This is the checklist to run on
a real phone + head unit before a release, and the quirks that have caused "the car notification
disappeared" reports on other drivers' setups.

## What the app relies on

- `NotificationListenerService` for reading the CGM app (system-granted, survives reboots).
- A foreground service with `foregroundServiceType="specialUse"` and the subtype
  `continuous_glucose_monitoring_for_driver_awareness`, started when the Android Auto connection
  appears (`androidx.car.app.connection`) and stopped when it goes away.
- Two channels: **Glucose alerts** (high importance, sound/vibration) and **Driving status**
  (low importance, silent) — see `notify/GlucoseNotifier.kt`.
- An ongoing, non-dismissible status notification while monitoring, so Android keeps the process.

## Device matrix (fill in per release)

| Device / OEM | Android | Head unit | Status notification survives | Silent status while Maps runs | Alerts during DND | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| Pixel (reference) | 15 | DHU | yes | yes | yes (channel importance) | Baseline |
| Samsung One UI | | | | | | Battery "Sleeping apps" can kill the FGS |
| Xiaomi HyperOS | | | | | | Needs Autostart + "No restrictions" |
| OnePlus/Oppo | | | | | | Deep optimisation may defer alerts |
| Huawei EMUI | | | | | | Aggressive FGS limits |

A row is only "verified" when it was tested with the release APK, not a debug build.

## Checklist before publishing

1. Fresh install → grant notification access → notification permission.
2. Connect Android Auto (or Desktop Head Unit: `adb forward tcp:5277 tcp:5277` + `desktop-head-unit`).
3. Open Maps; confirm the silent status notification sits in the car drawer and updates every reading.
4. Trigger a range alert; confirm sound/vibration and that Do Not Disturb does not swallow it.
5. Disconnect; confirm monitoring stops and no notification remains.
6. Reboot the phone; confirm the listener is still approved and the app does not start by itself.
7. Force-stop the CGM app for 20 minutes; confirm the "No glucose data" reminder appears once.
8. Toggle "I'm changing my sensor"; confirm the reminder pauses for two hours and resumes.

## Known traps

- **Android Auto "Unknown sources"**: an app installed outside Play never appears in the car until
  developer mode + Unknown sources are enabled in the Android Auto app.
- **Channel importance is fixed once created.** Changing importance in code does not update an
  existing install; the channel must be deleted and recreated (with a new id) to change it.
- **Battery optimisation** silently drops the foreground service on several OEMs; the install guide
  asks for *Unrestricted*.
- **Notification listener rebinding**: after a force-stop of the CGM app, values resume only when it
  posts again; SokeriPilot cannot poll it.
