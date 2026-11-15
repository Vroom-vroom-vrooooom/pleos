#!/usr/bin/env bash
# QA helper for the Chagok Pleos app on a running Pleos Connect emulator. Run with Git Bash.
#   ./qa.sh install                     build + install the app
#   ./qa.sh start                       restart the app with a sample trip (속초, 2 places)
#   ./qa.sh split                       force the half window: navi left, Chagok right
#   ./qa.sh speak "문장" ["장소 이름"]    send a backend SpeakCommand (optionally about a place)
#   ./qa.sh bad                         send a broken SpeakCommand (app must ignore it)
#   ./qa.sh call | hangup               incoming phone call on / off (Chagok must pause, then re-read)
#   ./qa.sh arrive                      pretend the navi reached the destination (arrival summary)
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
# With a phone also connected (relay.sh), talk to the Pleos emulator unless ANDROID_SERIAL says otherwise.
export ANDROID_SERIAL="${ANDROID_SERIAL:-$("$ADB" devices | tr -d '' | awk '$2=="device" && $1 ~ /^emulator-/ {print $1; exit}')}"
APP=io.chagok.app.pleos/io.chagok.app.ui.CarActivity
TRIP_ID=qa-trip

# JSON string escape, then device-shell single-quote escape.
json_str() { printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'; }
send() { local extra=$1 json=$2; "$ADB" shell am start -n "$APP" --es "$extra" "'${json//\'/\'\\\'\'}'" >/dev/null; }
# SpeakCommand goes as a broadcast: an activity start would pull Chagok to the front and Pleos would swap it into the left window.
say() { local json=$1; "$ADB" shell am broadcast -a io.chagok.app.pleos.SPEAK -p io.chagok.app.pleos --es chagok.speak "'${json//\'/\'\\\'\'}'" >/dev/null; }

case "${1:-}" in
  install)
    ./gradlew.bat assembleDebug --console=plain -q
    "$ADB" install -r app/build/outputs/apk/debug/app-debug.apk ;;
  start)
    "$ADB" shell am force-stop io.chagok.app.pleos
    send chagok.trip '{"v":1,"tripId":"'$TRIP_ID'","destination":"속초","departure":"2026-12-01","endDate":"미정","recordRoute":false,"companions":["친구"],"interests":["맛집","자연·바다"],"mood":"활기찬","density":"보통","places":[{"id":"abai","name":"아바이마을","time":"11:00","duration":"40분","source":"저장한 릴스 1개","group":"공유","reason":"맛집 관심사와 맞아요","distance":"2 km · 6분","lat":38.2003,"lng":128.5953,"open":"영업 중 · 20시까지"},{"id":"yeonggeumjeong","name":"영금정","time":"13:00","duration":"30분","source":"좋아요한 쇼츠","group":"좋아요","reason":"바다 산책","distance":"4 km · 10분","lat":38.2131,"lng":128.6007,"open":"항상 열려 있어요"}]}'
    echo "started: 속초 trip ($TRIP_ID)" ;;
  speak)
    text=$(json_str "${2:?usage: ./qa.sh speak \"문장\" [\"장소 이름\"]}")
    place=""
    if [ -n "${3:-}" ]; then
      name=$(json_str "$3")
      place=',"place":{"id":"qa-'$(date +%s)'","name":"'"$name"'","time":"","duration":"","source":"차곡 라디오","group":"공유","reason":"방금 들은 이야기","distance":"12분 추가 · QA","lat":38.2167,"lng":128.5867,"open":"항상 열려 있어요"}'
    fi
    say '{"v":1,"tripId":"'$TRIP_ID'","text":"'"$text"'"'"$place"'}'
    echo "sent SpeakCommand" ;;
  bad)
    say '{"v":1,"tripId":"'$TRIP_ID'","text":""}'
    say '{"v":9,"tripId":"'$TRIP_ID'","text":"버전이 다른 명령"}'
    say 'not json'
    echo "sent 3 invalid SpeakCommands (expect 3 warnings in ./qa.sh events, screen unchanged)" ;;
  split)
    # Half window: navi left, Chagok right. Pleos keeps the app that is in front in the left window when the ⋮ handle
    # is dragged, so bring the navi to the front first. Coordinates are for the 2560×1440 Pleos Connect emulator.
    chagok_x() { "$ADB" shell am stack list | grep -m1 'io.chagok.app.pleos' | sed -nE 's/.*bounds=\[([0-9]+),.*/\1/p'; }
    top_app() { "$ADB" shell am stack list | grep -m1 'taskId=' | grep -oE 'io\.chagok\.app\.pleos|navigation' ; }
    "$ADB" shell pidof io.chagok.app.pleos >/dev/null || { "$0" start; sleep 4; }
    if [ "$(chagok_x)" = 1718 ]; then echo "split: 이미 내비 왼쪽 / 차곡 오른쪽"; exit 0; fi
    if [ "$(chagok_x)" = 853 ] && "$ADB" shell am stack list | grep -q 'io.chagok.app.pleos.*\]\[1694'; then
      "$ADB" shell input swipe 1706 720 2555 720 500; sleep 2   # split the wrong way round: merge back first
    fi
    [ "$(top_app)" = navigation ] || { "$ADB" shell input tap 1155 1370; sleep 2; }   # dock: navigation to the front
    "$ADB" shell input swipe 2547 720 1700 720 600; sleep 2   # drag the ⋮ handle to the middle
    [ "$(chagok_x)" = 1718 ] && echo "split: 내비 왼쪽 / 차곡 오른쪽" || echo "split failed — check the emulator screen" ;;
  arrive)
    # Pretend the navi reported the destination (QA only): shows the arrival summary and sends TRIP_FINISHED.
    "$ADB" shell am broadcast -a io.chagok.app.pleos.ARRIVE -p io.chagok.app.pleos >/dev/null && echo "sent arrive" ;;
  call)
    "$ADB" emu gsm call 01012345678 >/dev/null && echo "incoming call" ;;
  hangup)
    "$ADB" emu gsm cancel 01012345678 >/dev/null && echo "call ended" ;;
  events)
    "$ADB" logcat -T 1 -s ChagokContract:V ChagokNavi:V ;;
  shot)
    mkdir -p qa-shots
    f="qa-shots/${2:-$(date +%H%M%S)}.png"
    "$ADB" exec-out screencap -p > "$f" && echo "saved $f" ;;
  *)
    sed -n 2,11p "$0" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac
