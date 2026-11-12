package io.chagok.app.data

import io.chagok.app.domain.*

/** Fixed fixtures; not actual recommendations, GPS, photos, or vehicle data. */
class DemoTripRepository : TripTransferPort, NavigationPort, MediaImportPort {
    val places = listOf(
        Place("anmok", "안목 커피거리", "10:30", "60분", "저장한 릴스 2개", "저장", "부모님 · 잔잔한 분위기에 맞아요", "3.2 km · 8분"),
        Place("chodang", "초당 순두부 거리", "12:30", "70분", "좋아요한 쇼츠 1개", "좋아요", "맛집 관심사와 맞아요", "5.1 km · 12분"),
        Place("gyeongpo", "경포 호수 산책길", "15:00", "50분", "친구가 보낸 릴스", "보낸", "이동 40분 · 오전 추천", "8.4 km · 18분"),
        Place("jeongdongjin", "정동진 일출", "06:00", "40분", "저장한 릴스 1개", "저장", "일정과 거리가 멀어요", "28 km · 40분"),
    )
    val radioPlace = Place("gyeongpodae", "경포대", "", "", "라디오에서 나온 장소", "이야기", "12분 추가 · 예시", "12분 추가")
    override fun stageTrip(draft: TripDraft, places: List<Place>): IntegrationResult {
        val plan = TripPlan("demo-${System.currentTimeMillis()}", draft, places).toJson()
        android.util.Log.i("ChagokContract", plan) // ponytail: logcat stands in for the transport until the backend relay exists
        return IntegrationResult.Demo("차량으로 보낼 TripPlan v${TripContract.VERSION}을 만들었어요. 실제 전송은 아직 연결 전이에요.\n\n$plan")
    }
    override fun requestWaypoint(place: Place) = IntegrationResult.Demo("${place.name} 승인 흐름을 확인했어요. 실제 내비게이션 경로는 변경하지 않았어요.")
    override fun importFrom(provider: String) = IntegrationResult.Unavailable("$provider 연동은 구현 전입니다. 현재는 샘플 장소만 표시해요.")
}
