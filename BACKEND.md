# 백엔드가 해야 할 것

지금 프론트는 샘플 데이터로만 동작합니다. 실제 서비스로 넘어갈 때 아래 항목을 백엔드가 맡습니다. 앱 쪽 연결 지점은 두 레포 공통 파일 `app/src/main/java/io/chagok/app/domain/Models.kt`의 `TripTransferPort`(폰)·`NavigationPort`(Pleos)·`MediaImportPort`(폰)이고, 현재 샘플 구현은 `data/DemoTripRepository.kt`입니다. 폰↔Pleos 사이에 오가는 메시지는 [CONTRACT.md](CONTRACT.md)에 고정돼 있습니다. 백엔드가 붙으면 이 샘플 구현만 실제 API 호출로 바꿉니다.

| 우선 | 영역 | 할 일 | 프론트 화면 / 연결 지점 |
|---|---|---|---|
| 1 | 계정·기기 | 로그인, 사용자별 폰·차량 기기 등록과 페어링 | 신규 |
| 1 | 여행 저장 | 여행 설정(목적지·날짜·동승자·관심사 최대 3개·분위기·일정 밀도·이동 기록 여부·공개 범위) CRUD, 일정(장소 순서) 저장 | P03~P06, P09 · `TripDraft` |
| 1 | 폰↔Pleos 중계 | 폰의 `TripPlan`을 Pleos로 전달(서버 푸시 또는 차량이 조회), Pleos의 `DriveEvent`를 폰으로 전달. 메시지 형식은 CONTRACT.md 그대로 | P09 → C01, C02~C05 → P10~P13 · `TripTransferPort.stageTrip` |
| 2 | 장소 추천 | 관심사·분위기·동승자·거리 기준 후보, 추천 이유, 출처(저장/좋아요/받은 영상) 반환 | P08, C04 · `Place` |
| 2 | 영상 가져오기 | YouTube OAuth, 공유 링크 수신, 내보내기 파일 분석 → 영상 속 장소 추출. 인스타 직접 연동은 미지원 | P07 · `MediaImportPort.importFrom` |
| 2 | 경유지 | 주행 중 저장·승인한 장소를 여행에 반영, 추가 소요 시간 계산. 실제 경로 변경은 차량 앱의 Pleos SDK가 담당 | C03 · `NavigationPort.requestWaypoint` |
| 3 | 라디오 콘텐츠 | 경로 주변 지역 이야기 스크립트 생성(TTS용), 언급 장소 연결 | C02 |
| 3 | 기록 | 방문 기록, 사진 업로드·보관, 사진 시간·위치로 타임라인 매칭 | P10~P12 |
| 3 | 요약·공유 | 이동 거리·방문 수 통계, 요약 영상·책자 생성, 공개 범위에 따른 공유 링크 | P13, C05 |

`Place` 응답에는 최소 `id`, `name`, `time`, `duration`, `source`, `group`, `reason`, `distance`가 필요합니다. 현재 화면이 이 필드를 그대로 표시합니다.

먼저 합의할 것:

- API 스펙(OpenAPI 등)을 먼저 정하고 앱은 Port 구현만 교체합니다.
- CONTRACT.md 메시지를 나를 전송 방식: 서버 경유로 할지, Pleos 연동 기능으로 할지. Pleos SDK 확인 후 결정합니다. 메시지 내용은 바꾸지 않습니다.
- 위치·이동 경로 수집 동의와 보관 기간(이동 기록 옵션).
- 추천·라디오용 AI 호출을 서버에서 처리할지 여부. 키는 앱에 넣지 않습니다.

이 문서는 `phone`과 `pleos` 레포에 똑같이 있습니다.
