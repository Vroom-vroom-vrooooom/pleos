package io.chagok.app.data

import io.chagok.app.domain.*

/** Fixed fixtures; not actual recommendations, GPS, photos, or vehicle data. */
class DemoTripRepository : TripTransferPort, NavigationPort, MediaImportPort {
    val places = listOf(
        // ponytail: sample coordinates so the Pleos navi can route; the places API will return real ones.
        Place("anmok", "안목 커피거리", "10:30", "60분", "좋아요한 쇼츠 2개", "좋아요", "부모님 · 잔잔한 분위기에 맞아요", "3.2 km · 8분", 37.7722, 128.9473, "영업 중 · 22시까지"),
        Place("chodang", "초당 순두부 거리", "12:30", "70분", "재생목록 ‘강릉 맛집’ 영상 1개", "재생목록", "맛집 관심사와 맞아요", "5.1 km · 12분", 37.7915, 128.9145, "영업 중 · 20시까지"),
        Place("gyeongpo", "경포 호수 산책길", "15:00", "50분", "구독 채널 영상 1개", "구독", "이동 40분 · 오전 추천", "8.4 km · 18분", 37.7965, 128.9000, "항상 열려 있어요"),
        Place("jeongdongjin", "정동진 일출", "06:00", "40분", "좋아요한 쇼츠 1개", "좋아요", "일정과 거리가 멀어요", "28 km · 40분", 37.6908, 129.0345, "항상 열려 있어요"),
    )
    val radioPlace = Place("gyeongpodae", "경포대", "", "", "라디오에서 나온 장소", "이야기", "12분 추가 · 예시", "12분 추가", 37.7953, 128.8964, "항상 열려 있어요")
    override fun stageTrip(draft: TripDraft, places: List<Place>): IntegrationResult {
        val plan = TripPlan("demo-${System.currentTimeMillis()}", draft, places).toJson()
        android.util.Log.i("ChagokContract", plan) // ponytail: logcat stands in for the transport until the backend relay exists
        return IntegrationResult.Demo("차량으로 보낼 TripPlan v${TripContract.VERSION}을 만들었어요. 실제 전송은 아직 연결 전이에요.\n\n$plan")
    }
    override fun requestWaypoint(place: Place) = IntegrationResult.Demo("${place.name} 승인 흐름을 확인했어요. 실제 내비게이션 경로는 변경하지 않았어요.")
    override fun importFrom(provider: String) = IntegrationResult.Unavailable("$provider 연동은 구현 전입니다. 현재는 샘플 장소만 표시해요.")
}
