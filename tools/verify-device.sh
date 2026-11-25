#!/usr/bin/env bash
#
# End-to-end device verification:
#   bash tools/verify-device.sh
#
# Installs the debug APK, grants notification permission + notification access, then
# verifies the car-bound contract:
#   - the CGM notification listener is approved (the data path)
#   - phone NOT connected to Android Auto -> the driving service must not run
#   - phone connected (car mode on)      -> the service runs in the foreground,
#     the status notification exists and a live CGM notification is readable
#   - Android Auto (gearhead) is installed
#
# Environment variables (optional):
#   ADB=adb          path to adb
#   PACKAGE=...      package name (default: app.sokeripilot.debug)
#   APK=...          path to the APK
#   WAIT=3           seconds to let the system settle after each step
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PACKAGE="${PACKAGE:-app.sokeripilot.debug}"
APK="${APK:-$ROOT/android/app/build/outputs/apk/debug/app-debug.apk}"
LISTENER="$PACKAGE/app.sokeripilot.data.CgmNotificationListener"
WAIT="${WAIT:-3}"

if [ -z "${ADB:-}" ]; then
  if [ -x "$ROOT/.toolchain/android-sdk/platform-tools/adb.exe" ]; then
    ADB="$ROOT/.toolchain/android-sdk/platform-tools/adb.exe"
  elif command -v adb >/dev/null 2>&1; then
    ADB="adb"
  else
    echo "FAIL  adb not found (.toolchain/android-sdk/platform-tools or PATH)."
    exit 1
  fi
fi

pass=0
fail=0
ok() { echo "PASS  $1"; pass=$((pass + 1)); }
bad() { echo "FAIL  $1"; fail=$((fail + 1)); }
warn() { echo "WARN  $1"; }

if [ ! -f "$APK" ]; then
  bad "APK not found: $APK (run :app:assembleDebug first)"
  exit 1
fi
ok "APK found: $(basename "$APK")"

device="$("$ADB" devices | awk 'NR > 1 && $2 == "device" { print $1; exit }')"
if [ -z "$device" ]; then
  bad "No connected device. Enable USB debugging or wireless pairing."
  exit 1
fi
ok "Device connected: $device"

install_output="$("$ADB" install -r -g "$APK" 2>&1)"
if echo "$install_output" | grep -q "Success"; then
  ok "APK installed (including declared permissions)"
else
  bad "Installation failed: $install_output"
  exit 1
fi

sdk="$("$ADB" shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')"
if [ "${sdk:-0}" -ge 33 ]; then
  if "$ADB" shell pm grant "$PACKAGE" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 ||
    "$ADB" shell dumpsys package "$PACKAGE" 2>/dev/null | grep -q "android.permission.POST_NOTIFICATIONS: granted=true"; then
    ok "Notification permission granted"
  else
    bad "Could not grant notification permission (screen lock or permission policy)"
  fi
else
  ok "Notification permission not required (API $sdk)"
fi

# --- data path: notification access ---------------------------------------------
access_grant="$("$ADB" shell cmd notification allow_listener "$LISTENER" 2>&1)"
sleep 1
listeners="$("$ADB" shell dumpsys notification 2>/dev/null || true)"
if echo "$listeners" | grep -q "app.sokeripilot"; then
  ok "Notification listener is approved and bound (CGM reading path)"
else
  if echo "$access_grant" | grep -qi "unknown\|error\|exception"; then
    warn "Could not grant notification access over adb; enable it manually: Settings → Apps → Special access → Notification access → SokeriPilot"
  else
    bad "Notification listener is not bound (CGM values cannot be read)"
  fi
fi

# --- Android Auto presence ------------------------------------------------------
if "$ADB" shell pm list packages 2>/dev/null | grep -q "com.google.android.projection.gearhead"; then
  ok "Android Auto (gearhead) is installed on the phone"
  has_gearhead=1
else
  warn "Android Auto (gearhead) not found; the car checks below cannot pass."
  has_gearhead=0
fi

# --- connection state -----------------------------------------------------------
uimode="$("$ADB" shell dumpsys uimode 2>/dev/null || true)"
car_mode="false"
if echo "$uimode" | grep -q "mCarModeEnabled=true"; then
  car_mode="true"
fi
echo "      car mode (projection): $car_mode"

"$ADB" shell am start -W -n "$PACKAGE/.phone.MainActivity" >/dev/null 2>&1
sleep "$WAIT"

services="$("$ADB" shell dumpsys activity services "$PACKAGE" 2>/dev/null || true)"
notifications="$("$ADB" shell dumpsys notification --noredact 2>/dev/null || true)"
service_running=0
if echo "$services" | grep -q "GlucoseMonitorService"; then
  service_running=1
fi

if [ "$car_mode" = "true" ]; then
  if [ "$service_running" -eq 1 ]; then
    ok "Driving service is running while projected into the car"
  else
    bad "Driving service is NOT running although the phone is projected"
  fi

  if echo "$services" | grep -q "isForeground=true"; then
    ok "Service is in foreground mode"
  else
    bad "Service is not in foreground mode"
  fi

  if echo "$notifications" | grep -q "glucose_status"; then
    ok "Car status notification is active (glucose_status)"
  else
    warn "Status notification missing: notification permission, channel, or CGM source may be missing"
  fi
else
  if [ "$service_running" -eq 0 ]; then
    ok "Driving service is NOT running while the phone is not connected (car-bound)"
  else
    bad "Driving service is running without a car connection"
  fi

  if echo "$notifications" | grep -q "glucose_status"; then
    bad "Status notification is present without a car connection"
  else
    ok "No status notification while disconnected"
  fi

  echo "      Connect the phone to the head unit (or start the DHU) and run again for the driving checks."
fi

# --- live CGM value -------------------------------------------------------------
cgm_line="$(echo "$notifications" | grep -Eio '.{0,60}([0-9]{2,3} ?mg/dL|[0-9]+[.,][0-9] ?mmol/L).{0,40}' | head -1)"
if [ -n "$cgm_line" ]; then
  ok "A live CGM notification value is present: $(echo "$cgm_line" | tr -s ' ')"
else
  warn "No CGM value found in notifications yet; open/start the sensor app and rerun."
fi

echo
echo "RESULT: $pass passed, $fail failed."
[ "$fail" -eq 0 ] || exit 1
