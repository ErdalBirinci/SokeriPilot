# Data safety answers — SokeriPilot 1.2.1

Play asks these per app, not per SDK. Answer from what the app actually does; the sources are
`README.md` (§5), `landing/privacy/` and the Android code.

## Data collection and sharing

| Question | Answer | Why |
| --- | --- | --- |
| Does your app collect or share any of the required user data types? | **No** | The app has no server, no analytics SDK, no ads and no crash reporting. |
| Is all of the user data collected by your app encrypted in transit? | Not applicable (no collection) — note in the free-text box that Nightscout requests use HTTPS only | `NightscoutConfig` rejects non-HTTPS and redirects. |
| Do you provide a way for users to request that their data is deleted? | **Yes** — uninstalling removes settings; Nightscout history lives on the user's own server | Settings are private storage; there is no project-side copy. |

If the console forces a "collected" declaration because readings pass through the app, declare
**Health and fitness → Health info**, collected **on device only**, **not shared**, **not used for
advertising**, **optional** (only if the user configures Nightscout), and explain that SokeriPilot
itself stores no history.

## Security practices

| Question | Answer |
| --- | --- |
| Data encrypted in transit | Yes — HTTPS only, cleartext blocked in the manifest and network security config |
| Data deletion request | Uninstall deletes app data; Nightscout is the user's own server; Health Connect records are managed by the user |
| Committed to Play Families policy | Not applicable (18+) |
| Independent security review | No |
| Ads | No |
| Government app | No |

## Permissions to justify in the review notes

| Permission | One-line justification |
| --- | --- |
| `BIND_NOTIFICATION_LISTENER_SERVICE` (notification access) | Reads the CGM value the user's sensor app already posts; the only way Android exposes another app's reading. |
| `POST_NOTIFICATIONS` | Range and stale-data alerts, and the persistent car status notification. |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` | Keeps the car notification current only while the phone is projected into a car. |
| `com.eveningoutpost.dexdrip.permissions.RECEIVE_BG_ESTIMATE` | Optional xDrip+ local broadcast; the permission is declared by xDrip+, not granted to SokeriPilot broadly. |
| `android.permission.health.WRITE_BLOOD_GLUCOSE` | Optional, off by default, write-only Health Connect export; never reads health data back. |
| `REQUEST_REBIND_NOTIFICATION_LISTENER_SERVICE` | Lets the setup screen re-open the system grant after an update. |
| `INTERNET` | Optional Nightscout polling over HTTPS. |

No `QUERY_ALL_PACKAGES`: package visibility is limited to the CGM apps declared in `<queries>`.
