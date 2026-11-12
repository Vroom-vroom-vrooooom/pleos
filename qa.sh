#!/usr/bin/env bash
# QA helper for the Chagok Pleos app on a running Pleos Connect emulator. Run with Git Bash.
#   ./qa.sh install                     build + install the app
#   ./qa.sh start                       restart the app with a sample trip (속초, 2 places)
#   ./qa.sh speak "문장" ["장소 이름"]    send a backend SpeakCommand (optionally about a place)
#   ./qa.sh bad                         send a broken SpeakCommand (app must ignore it)
#   ./qa.sh events                      watch TripPlan / DriveEvent / warnings live (Ctrl+C to stop)
#   ./qa.sh shot [name]                 save a screenshot to qa-shots/
set -euo pipefail
cd "$(dirname "$0")"
export MSYS_NO_PATHCONV=1

ADB="${ADB:-}"
if [ -z "$ADB" ]; then
  for c in "${ANDROID_HOME:-}/platform-tools/adb.exe" "${LOCALAPPDATA:-}/Android/Sdk/platform-tools/adb.exe" "$(command -v adb || true)"; do
    [ -n "$c" ] && [ -x "$c" ] && ADB="$c" && break
  done
fi
[ -n "$ADB" ] || { echo "adb not found. Set ADB=/path/to/adb" >&2; exit 1; }
APP=io.chagok.app.pleos/io.chagok.app.ui.CarActivity
TRIP_ID=qa-trip

# JSON string escape, then device-shell single-quote escape.
json_str() { printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'; }
send() { local extra=$1 json=$2; "$ADB" shell am start -n "$APP" --es "$extra" "'${json//\'/\'\\\'\'}'" >/dev/null; }

case "${1:-}" in
  install)
    ./gradlew.bat assembleDebug --console=plain -q
    "$ADB" install -r app/build/outputs/apk/debug/app-debug.apk ;;
  start)
    "$ADB" shell am force-stop io.chagok.app.pleos
    send chagok.trip '{"v":1,"tripId":"'$TRIP_ID'","destination":"속초","departure":"2026-12-01","endDate":"미정","recordRoute":false,"companions":["친구"],"interests":["맛집","자연·바다"],"mood":"활기찬","density":"보통","places":[{"id":"abai","name":"아바이마을","time":"11:00","duration":"40분","source":"저장한 릴스 1개","group":"저장","reason":"맛집 관심사와 맞아요","distance":"2 km · 6분"},{"id":"yeonggeumjeong","name":"영금정","time":"13:00","duration":"30분","source":"좋아요한 쇼츠","group":"좋아요","reason":"바다 산책","distance":"4 km · 10분"}]}'
    echo "started: 속초 trip ($TRIP_ID)" ;;
  speak)
    text=$(json_str "${2:?usage: ./qa.sh speak \"문장\" [\"장소 이름\"]}")
    place=""
    if [ -n "${3:-}" ]; then
      name=$(json_str "$3")
      place=',"place":{"id":"qa-'$(date +%s)'","name":"'"$name"'","time":"","duration":"","source":"차곡 라디오","group":"이야기","reason":"방금 들은 이야기","distance":"12분 추가 · QA"}'
    fi
    send chagok.speak '{"v":1,"tripId":"'$TRIP_ID'","text":"'"$text"'"'"$place"'}'
    echo "sent SpeakCommand" ;;
  bad)
    send chagok.speak '{"v":1,"tripId":"'$TRIP_ID'","text":""}'
    send chagok.speak '{"v":9,"tripId":"'$TRIP_ID'","text":"버전이 다른 명령"}'
    send chagok.speak 'not json'
    echo "sent 3 invalid SpeakCommands (expect 3 warnings in ./qa.sh events, screen unchanged)" ;;
  events)
    "$ADB" logcat -T 1 -s ChagokContract:V ;;
  shot)
    mkdir -p qa-shots
    f="qa-shots/${2:-$(date +%H%M%S)}.png"
    "$ADB" exec-out screencap -p > "$f" && echo "saved $f" ;;
  *)
    sed -n 2,8p "$0" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac
