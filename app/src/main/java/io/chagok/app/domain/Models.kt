package io.chagok.app.domain

enum class Screen(val board: String, val title: String, val vehicle: Boolean = false) {
    HOME("P01", "여행"), START("P02", "무엇을 할까요?"),
    DESTINATION("P03", "어디로 갈까요?"), COMPANIONS("P04", "누구와 함께 가나요?"),
    INTERESTS("P05", "무엇에 끌리나요?"), MOOD("P06", "어떤 분위기가 좋아요?"),
    IMPORT("P07", "저장한 영상에서 찾아볼까요?"), RECOMMENDATIONS("P08", "추천"),
    ITINERARY("P09", "여행 일정"), ARRIVAL("P10", "장소에 도착했어요"),
    PHOTOS("P11", "사진 고르기"), TIMELINE("P12", "기록"), SUMMARY("P13", "여행 요약"),
    MY("P14", "MY"), RECAP("P15", "한눈에 모아보기"),
    CAR_READY("C01", "오늘 일정", true), CAR_RADIO("C02", "길 위의 라디오", true),
    CAR_APPROVAL("C03", "경유지 추가", true), CAR_DISCOVER("C04", "발견", true),
    CAR_SUMMARY("C05", "도착 요약", true),
}

data class TripDraft(
    var destination: String = "강릉",
    var departure: String = "2026-11-22",
    var endDate: String = "미정",
    var recordRoute: Boolean = false,
    var visibility: String = "나만",
    val companions: MutableSet<String> = linkedSetOf("부모님"),
    val interests: MutableSet<String> = linkedSetOf("지역 이야기", "맛집", "추억"),
    var mood: String = "잔잔한",
    var density: String = "보통",
)

data class Place(val id: String, val name: String, val time: String, val duration: String, val source: String, val group: String, val reason: String, val distance: String)

interface TripTransferPort { fun stageTrip(draft: TripDraft, places: List<Place>): IntegrationResult }
interface NavigationPort { fun requestWaypoint(place: Place): IntegrationResult }
interface MediaImportPort { fun importFrom(provider: String): IntegrationResult }
sealed interface IntegrationResult {
    data class Demo(val message: String) : IntegrationResult
    data class Unavailable(val message: String) : IntegrationResult
}
