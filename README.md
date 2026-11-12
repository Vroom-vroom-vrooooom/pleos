# 차곡 · Pleos 차량 앱

Pleos Connect에서 돌아가는 차곡 차량 앱(C01~C05)입니다. 출발 인사 → 길 위의 라디오(구체) → 장소 저장·경유지 승인 → 주변 발견 → 하루 마무리 흐름을 샘플 데이터로 보여 줍니다. 내비·상태바·하단 독은 Pleos가 그리고, 이 앱은 Pleos가 주는 창 안의 차곡 패널만 그립니다.

- 폰 앱: https://github.com/Vroom-vroom-vrooooom/phone
- 두 앱을 잇는 메시지: [CONTRACT.md](CONTRACT.md) · `domain/TripContract.kt`
- 백엔드가 해야 할 것: [BACKEND.md](BACKEND.md)
- 원본 18개 화면 대응표: [docs/wireframe-map.md](docs/wireframe-map.md)
- 웹 목업(폰·차량 함께 보기): phone 레포의 `mockup/`

## 실행

1. Pleos Connect 2.1.0 에뮬레이터(API 34, x86_64)를 준비합니다: https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator
2. `gradlew.bat assembleDebug` 후 `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
3. Pleos 앱 목록(하단 ⊞)에서 "차곡"을 누르거나, 폰에서 만든 여행을 넣어 실행합니다:

```sh
adb shell am start -n io.chagok.app.pleos/io.chagok.app.ui.CarActivity --es chagok.trip '<TripPlan JSON>'
```

주행 중 이벤트는 `adb logcat -s ChagokContract`로 볼 수 있습니다. 백엔드가 보내는 "이 말 해" 명령(SpeakCommand)은 `--es chagok.speak '<JSON>'`으로 넣으면 차곡이 Pleos TTS로 읽습니다. 자세한 형식은 CONTRACT.md에 있습니다.

## 구조

```text
app/src/main/java/io/chagok/app/
  ui/CarActivity.kt          차량 5개 화면 · TripPlan·SpeakCommand 수신(Pleos TTS SDK), DriveEvent 발신
  ui/CompanionOrbView.kt     파스텔 구체 애니메이션
  ui/ScreenActivity.kt       공통 화면 부품 (phone 레포와 같은 파일)
  domain/TripContract.kt     폰↔Pleos 메시지 (phone 레포와 반드시 같은 파일)
  domain/Models.kt, data/DemoTripRepository.kt
res/layout/activity_car.xml, panel_radio.xml
```

주행 중 허용 앱 선언, 차량 권한, Pleos SDK 연동은 아직 하지 않았습니다.
