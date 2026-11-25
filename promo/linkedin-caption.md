SokeriPilot — your glucose in sight, your eyes on the road.

At 80 km/h you have about two seconds to look at a screen. A phone app does not survive that trip.

Android Auto does not let a third-party app keep a window on top of navigation, so SokeriPilot does the two things it can do properly:

→ a silent car notification that updates with every reading, with a one-tap path into the cockpit
→ a full-screen cockpit: current value, 90-minute trend, time in range and sensor status — readable in half a second

It reads the CGM app already on the phone — Dexcom, Libre, Diabox, xDrip+, Juggluco, Eversense — through a single system permission. No account, no server, no analytics; the value never leaves the device. Nightscout and xDrip+ broadcasts stay optional.

1.2 added the parts that matter while driving: a 15-minute projection that warns before you leave range, an urgent-low alert that repeats until acknowledged, a "changing my sensor" pause so a warm-up does not trigger missing-data alarms, a Health Connect export and a settings backup.

Engineering side: Kotlin and the Android for Cars App Library, a foreground specialUse service that starts with the Android Auto connection and stops when you unplug, unit tests over the parser, projection and alert policy, a hash-verified release pipeline, and a landing page served with a strict CSP. The project is MIT-licensed: app, tools and landing page.

The cockpit values in the image are an illustrative render, not a capture from a device. SokeriPilot is not a medical device and gives no dosing advice.

Landing: https://erdalbirinci.github.io/SokeriPilot/
Source: https://github.com/ErdalBirinci/SokeriPilot

If you drive with a sensor: which CGM app should be covered next?

#AndroidAuto #Type1Diabetes #T1D #CGM #Kotlin #HealthTech #BuildInPublic
