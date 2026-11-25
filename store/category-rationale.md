# Car app category rationale — `androidx.car.app.category.IOT`

Play review can ask why SokeriPilot declares the IOT category instead of navigation or parking.
The answer is in the app's purpose and in what the car screen is allowed to do.

## What the app is

SokeriPilot displays the reading of a continuous glucose monitor (a body-worn IoT health sensor)
inside Android Auto, and raises an advisory reminder when the value leaves the user's target
range or when readings stop arriving. It is a viewer and reminder for a connected health device.

## Why IOT and not another category

- **Not navigation**: the app never provides routing, maps or turn-by-turn guidance. It is
  designed to run *beside* navigation; Android Auto keeps Maps in front.
- **Not parking/charging/fuel**: unrelated to vehicle state.
- **Not messaging/media**: no communication or playback.
- **IOT** covers apps that surface a connected device's state in the car. A CGM is exactly that:
  a sensor the user wears, whose state the driver needs at a glance without touching the phone.

## Driver-safety constraints the app respects

- Templates only (`PaneTemplate`, `ListTemplate`); no custom drawing, video or keyboard.
- No persistent overlay on top of Maps or media — Android Auto forbids it, so the app uses a
  silent status notification and a short out-of-range alert instead.
- Alerts are rate-limited (one per band per 10 minutes, projection at most every 30 minutes,
  urgent low at most three repeats) and muteable for 30 minutes.
- The car notification stops when the phone is unprojected; nothing runs at boot.

## Medical framing

The app is not a medical device, gives no dosing advice and claims no diagnosis. Its alerts are
advisory and the copy says so on the phone, in the notification and on the listing.

## Evidence to attach if asked

- `README.md` §2 (features), §5 (security model), §7 (distribution notes).
- `landing/privacy/` for the data statement.
- `android/app/src/main/res/xml/automotive_app_desc.xml` (`template`), the manifest's IOT
  category line, and the absence of `SYSTEM_ALERT_WINDOW`.
