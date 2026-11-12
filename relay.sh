#!/usr/bin/env bash
# PC relay: stands in for the backend while the phone is on USB and the Pleos app runs in the emulator.
#   phone  --TripPlan-->   car    (phone "차로 보내기" → Pleos start screen)
#   car    --DriveEvent--> phone  (출발 / 담았어요 → phone P12 기록)
# Both apps log their outgoing message as JSON under the logcat tag ChagokContract; this forwards each line.
# Run with Git Bash: ./relay.sh   (Ctrl+C to stop). ponytail: replaced by the backend relay (BACKEND.md).
set -uo pipefail
export MSYS_NO_PATHCONV=1

ADB="${ADB:-}"
if [ -z "$ADB" ]; then
  for c in "${ANDROID_HOME:-}/platform-tools/adb.exe" "${LOCALAPPDATA:-}/Android/Sdk/platform-tools/adb.exe" "$(command -v adb || true)"; do
    [ -n "$c" ] && [ -x "$c" ] && ADB="$c" && break
  done
fi
[ -n "$ADB" ] || { echo "adb not found. Set ADB=/path/to/adb" >&2; exit 1; }

CAR=$("$ADB" devices | tr -d '\r' | awk '$2=="device" && $1 ~ /^emulator-/ {print $1; exit}')
PHONE=$("$ADB" devices | tr -d '\r' | awk 'NR>1 && $2=="device" && $1 !~ /^emulator-/ {print $1; exit}')
[ -n "$CAR" ] || { echo "Pleos emulator not found (adb devices)" >&2; exit 1; }
[ -n "$PHONE" ] || { echo "Phone not found: connect it by USB with USB debugging on, then accept the prompt on the phone" >&2; exit 1; }
echo "relay: phone $PHONE  <->  car $CAR"

# Quote for the device shell: wrap in '...' and escape inner single quotes.
q() { local j=$1; printf "'%s'" "${j//\'/\'\\\'\'}"; }

phone_to_car() {
  "$ADB" -s "$PHONE" logcat -T 1 -s ChagokContract:I | while IFS= read -r line; do
    json=${line#*ChagokContract: }; json=${json%$'\r'}
    [[ $json == \{*'"destination"'* ]] || continue   # only TripPlans
    # Broadcast while Chagok runs (keeps the half window beside the navi); start it only when it isn't running.
    if "$ADB" -s "$CAR" shell pidof io.chagok.app.pleos >/dev/null; then
      "$ADB" -s "$CAR" shell am broadcast -a io.chagok.app.pleos.TRIP -p io.chagok.app.pleos --es chagok.trip "$(q "$json")" >/dev/null
    else
      "$ADB" -s "$CAR" shell am start -n io.chagok.app.pleos/io.chagok.app.ui.CarActivity --es chagok.trip "$(q "$json")" >/dev/null
    fi
    echo "phone → car  TripPlan $(printf '%s' "$json" | grep -oE '"destination":"[^"]*"')"
  done
}

car_to_phone() {
  "$ADB" -s "$CAR" logcat -T 1 -s ChagokContract:I | while IFS= read -r line; do
    json=${line#*ChagokContract: }; json=${json%$'\r'}
    [[ $json == \{*'"type"'* ]] || continue          # only DriveEvents
    "$ADB" -s "$PHONE" shell am start -n io.chagok.app.phone/io.chagok.app.ui.PhoneActivity --es chagok.event "$(q "$json")" >/dev/null
    echo "car → phone  DriveEvent $(printf '%s' "$json" | grep -oE '"type":"[^"]*"')"
  done
}

trap 'kill 0' INT TERM EXIT
phone_to_car & car_to_phone & wait
