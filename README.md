# 차곡 · Pleos 차량 앱

Pleos Connect에서 돌아가는 차곡 차량 앱입니다. 내비·상태바·왼쪽 Driving view·하단 독은 Pleos가 그리고, 이 앱은 Pleos가 주는 창(내비 옆 반 칸) 안의 차곡 패널만 그립니다.

- **출발 전**: 인사와 오늘 일정. "코스 바꾸기"로 다른 여행을 고르고, "좋아, 출발하자"를 누르면 Pleos 내비에 경로(경유지 최대 2곳)가 들어갑니다.
- **주행 중**: 차곡 구체가 떠 있다가 백엔드가 보낸 말(SpeakCommand)을 화면에 크게 띄우고 Pleos TTS로 읽습니다. 위에는 "다음 장소 · 남은 시간"(내비 값).
- **들렀다 가기**: 말에 장소가 붙어 있으면 장소 칩이 생기고, 누르면 추가 이동시간·방문 가능 여부를 보고 승인 → Pleos 내비 경유지로 들어갑니다.
- **도착**: 내비 도착 이벤트로 요약(경로·거리·시간·들른 곳·들은 이야기·담은 곳)을 보여 주고, "처음으로"로 시작 화면에 돌아갑니다.
- 출발·장소 저장·경유지 승인/거절·도착은 DriveEvent로 폰에 보냅니다.

링크
- 폰 앱: https://github.com/Vroom-vroom-vrooooom/phone
- 두 앱(과 백엔드)을 잇는 메시지: [CONTRACT.md](CONTRACT.md) · `domain/TripContract.kt`
- 백엔드가 해야 할 것: [BACKEND.md](BACKEND.md)
- QA 체크리스트: [QA.md](QA.md)
- Pleos 화면 목업(반 칸 구성): `mockup/pleos-screen.html`

## 실행

1. Pleos Connect 2.1.0 에뮬레이터(API 34, x86_64)를 준비합니다: https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator
2. Git Bash에서:

```sh
./qa.sh install    # 빌드 + 설치
./qa.sh start      # 속초 샘플 여행으로 실행
./qa.sh split      # 내비 왼쪽 / 차곡 오른쪽 반 칸
./qa.sh speak "곧 오른쪽으로 영랑호가 보여요." "영랑호"   # 백엔드 대신 말 보내기
./qa.sh arrive     # 내비 도착 흉내
./qa.sh events     # 이벤트·내비 로그 보기
```

반 칸은 Pleos가 정합니다: 칸 오른쪽 끝 ⋮ 손잡이를 끌면 앞에 있던 앱이 왼쪽, 다른 앱이 오른쪽으로 갑니다. `./qa.sh split`이 이 순서를 대신합니다. 앱을 `am start`로 앞으로 부르면 Pleos가 왼쪽 칸으로 옮기므로, 실행 중인 앱에는 메시지를 broadcast로 넣습니다(CONTRACT.md 로컬 테스트).

### 폰과 연결해 보기 (백엔드 대신 노트북이 중계)

폰에 phone 레포 앱을 설치하고 adb로 연결(USB 또는 무선 `adb tcpip 5555` → `adb connect <폰IP>:5555`)한 뒤:

```sh
./relay.sh   # 폰 "차로 보내기" → 차곡 시작 화면, 차곡 DriveEvent → 폰 기록
```

## 구조

```text
app/src/main/java/io/chagok/app/
  ui/CarActivity.kt          출발 전 · 주행 중 · 들렀다 갈까요? 시트 · 도착 요약, TripPlan/SpeakCommand 수신, DriveEvent 발신
  ui/PleosNavi.kt            Pleos NaviHelper SDK 어댑터 (경로 보내기, 경유지 추가, 남은 시간·도착 이벤트)
  ui/CompanionOrbView.kt     파스텔 구체 애니메이션
  ui/ScreenActivity.kt       공통 화면 부품 (phone 레포와 같은 파일)
  domain/TripContract.kt     폰·Pleos·백엔드 메시지 (phone 레포와 반드시 같은 파일)
  domain/Models.kt, data/DemoTripRepository.kt   (phone 레포와 같은 파일)
res/layout/activity_car.xml
qa.sh, relay.sh, QA.md       QA·중계 도구
```

Pleos SDK: TextToSpeech 3.3.1(on-device), NaviHelper 3.0.5. 주행 중 허용 앱 선언, 음성 명령(Gleo Interface), 실제 백엔드 전송은 아직 하지 않았습니다.
