# 폰 ↔ Pleos 연결 계약 v1

차곡은 앱 두 개로 나뉩니다.

| 레포 | 앱 | 화면 |
|---|---|---|
| `phone` | 폰 앱 `io.chagok.app.phone` | P01~P13: 여행 설정, 추천, 일정, 사진, 기록 |
| `pleos` | Pleos 차량 앱 `io.chagok.app.pleos` | C01~C05: 출발 인사, 라디오, 경유지 승인, 발견, 마무리 |

두 앱과 백엔드가 주고받는 것은 아래 세 메시지뿐입니다. 정의는 `app/src/main/java/io/chagok/app/domain/TripContract.kt`이고, **이 파일과 이 문서는 두 레포에서 항상 똑같아야 합니다.** 바꿀 때는 두 레포에 같은 변경을 함께 올리고, 필드 의미가 바뀌면 `VERSION`을 올립니다. 받는 쪽은 모르는 버전을 거절합니다.

전송 방식(서버 중계 등)은 아직 정하지 않았습니다. 백엔드와 정할 때 메시지 내용은 그대로 두고 전달 경로만 붙입니다. 지금은 양쪽 모두 logcat 태그 `ChagokContract`로 메시지를 출력합니다.

## 1. TripPlan · 폰 → Pleos

폰 P09 "차로 보내기"에서 만듭니다. Pleos 앱은 이걸 받아 C01의 목적지·동승자·분위기·일정을 채웁니다.

```json
{
  "v": 1,
  "tripId": "demo-1731400000000",
  "destination": "강릉",
  "departure": "2026-11-22",
  "endDate": "미정",
  "recordRoute": false,
  "companions": ["부모님"],
  "interests": ["지역 이야기", "맛집", "추억"],
  "mood": "잔잔한",
  "density": "보통",
  "places": [
    {"id": "anmok", "name": "안목 커피거리", "time": "10:30", "duration": "60분",
     "source": "저장한 릴스 2개", "group": "저장", "reason": "부모님 · 잔잔한 분위기에 맞아요", "distance": "3.2 km · 8분"}
  ]
}
```

| 필드 | 필수 | 설명 |
|---|---|---|
| `v` | ✓ | 계약 버전. 지금은 `1` |
| `tripId` | ✓ | 여행 ID. DriveEvent가 같은 값을 돌려줍니다. 지금은 폰이 임시 생성하고, 백엔드가 생기면 서버 ID로 바꿉니다 |
| `destination`, `departure`, `mood`, `density` | ✓ | 문자열. 날짜는 `YYYY-MM-DD` |
| `endDate` | | `YYYY-MM-DD` 또는 `미정` |
| `recordRoute` | | 이동 기록 동의 여부. 기본값 `false` |
| `companions`, `interests` | ✓ | 문자열 배열 |
| `places` | ✓ | 방문 순서대로. `id`, `name`은 필수이고 나머지는 표시용 문자열 |

공개 범위(`visibility`)는 폰에서만 쓰는 값이라 보내지 않습니다.

## 2. DriveEvent · Pleos → 폰

Pleos 앱이 주행 중에 일어난 일을 하나씩 보냅니다. 폰은 이걸 모아 P10~P13 기록을 만듭니다.

```json
{"v": 1, "tripId": "demo-1731400000000", "type": "PLACE_SAVED", "placeId": "gyeongpodae", "at": "2026-11-22T05:12:03Z"}
```

| `type` | 언제 | `placeId` |
|---|---|---|
| `TRIP_STARTED` | C01에서 "좋아, 출발하자" / "차곡과 출발하기" | 없음 (`null`) |
| `PLACE_SAVED` | "이 장소 기억해 줘" / "여기 마음에 들어" | 저장한 장소 |
| `WAYPOINT_APPROVED` | C03 "응, 들렀다 가자" | 경유지 장소 |
| `WAYPOINT_DECLINED` | C03 "다음에 가자" | 경유지 장소 |
| `TRIP_FINISHED` | C05에서 폰으로 기록을 넘길 때 | 없음 (`null`) |

`at`은 UTC ISO-8601 시각입니다. 실제 경로 변경은 Pleos 앱이 Pleos SDK로 처리하고, 이 이벤트는 기록용입니다.

## 3. SpeakCommand · 백엔드 → Pleos

여행 중(주행 중)에 백엔드가 "지금 이 말을 해"라고 보내는 명령입니다. Pleos 앱은 받는 즉시 라디오 화면(C02)으로 가서 문장을 띄우고, 구체를 말하는 상태로 바꾸고, Pleos TTS SDK(on-device)로 읽습니다. 다 읽으면 구체가 멈춥니다. 운전자가 "잠깐 쉬어 가자"를 누르면 바로 멈춥니다.

```json
{"v": 1, "tripId": "demo-1731400000000", "text": "곧 오른쪽으로 영랑호가 보여요. 호수 둘레길이 예쁘대요.",
 "place": {"id": "yeongnangho", "name": "영랑호", "distance": "12분 추가"}}
```

| 필드 | 필수 | 설명 |
|---|---|---|
| `v` | ✓ | 계약 버전. 지금은 `1` |
| `tripId` | ✓ | 어느 여행에 대한 말인지. 백엔드가 차량을 찾는 데 씁니다 |
| `text` | ✓ | 읽을 문장. 빈 문자열은 거절합니다. 운전 중에 듣는 말이므로 짧게(한두 문장) 보냅니다 |
| `place` | | 이 말이 가리키는 장소(TripPlan의 place와 같은 모양). 있으면 문장 아래 장소 이름·거리를 보여 주고, "이 장소 기억해 줘"가 이 장소를 저장해 `PLACE_SAVED`로 돌려줍니다. 없으면 샘플 장소(경포대)를 씁니다 |

새 명령이 오면 읽던 문장을 끊고 새 문장을 읽습니다. 음성 엔진을 쓸 수 없으면 글자만 보여 줍니다.

## 로컬 테스트

1. 폰 앱에서 P09 "차로 보내기 · 데모"를 누르면 대화상자와 logcat에 TripPlan JSON이 나옵니다.
2. 그 JSON을 Pleos 에뮬레이터의 차량 앱에 넣어 실행합니다.

```sh
adb shell am start -n io.chagok.app.pleos/io.chagok.app.ui.CarActivity --es chagok.trip '<TripPlan JSON>'
```

3. 차량 앱에서 버튼을 누르면 DriveEvent가 나옵니다: `adb logcat -s ChagokContract`.
4. 앱이 떠 있는 상태에서 백엔드 대신 SpeakCommand를 broadcast로 넣습니다. 앱을 앞으로 부르지 않아서 내비 옆 반 칸 배치가 그대로 유지됩니다(`am start`로 보내면 Pleos가 차곡을 왼쪽 칸으로 옮깁니다). pleos 레포의 `./qa.sh speak "문장" ["장소"]`가 같은 일을 합니다.

```sh
adb shell am broadcast -a io.chagok.app.pleos.SPEAK -p io.chagok.app.pleos --es chagok.speak "'{\"v\":1,\"tripId\":\"demo\",\"text\":\"곧 왼쪽으로 설악산이 보여요.\"}'"
```

잘못된 JSON이나 다른 버전이 들어오면 Pleos 앱은 경고 로그를 남기고 샘플 여행을 보여 줍니다.
