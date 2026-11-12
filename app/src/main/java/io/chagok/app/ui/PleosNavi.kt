package io.chagok.app.ui

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.*
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.chagok.app.domain.Place

/**
 * Thin adapter over the Pleos NaviHelper SDK: send the trip to the built-in navi, add an approved waypoint,
 * and report progress back on the main thread. Pleos allows at most two waypoints (WaypointIndex.FIRST/SECOND).
 */
class PleosNavi(context: Context, private val onEvent: (Event) -> Unit) {
    sealed interface Event {
        data class Ready(val ok: Boolean) : Event
        /** Route accepted by the navi: total distance (m) and time (s). */
        data class Routed(val distanceM: Int, val durationS: Int) : Event
        /** Remaining distance (m) / time (s) to the next stop (first waypoint, else the destination). */
        data class Progress(val remainM: Int, val remainS: Int) : Event
        data class WaypointArrived(val name: String) : Event
        data class Arrived(val name: String, val distanceM: Int, val durationS: Int) : Event
        data class Failed(val reason: String) : Event
    }

    private val main = Handler(Looper.getMainLooper())
    private val helper = NaviHelper(context.applicationContext)
    var ready = false; private set
    var waypoints = 0; private set

    private val listener = object : NaviHelperEventListener {
        override fun onRouteStarted(info: RouteStartInfo) = post(Event.Routed(info.distance, info.duration))
        override fun onDrivingInfo(info: DrivingInfo) {
            val next = info.waypoints.firstOrNull() ?: info.destination ?: return
            post(Event.Progress(next.distance, next.duration))
        }
        override fun onWayPointArrived(info: WaypointArrivedInfo) { waypoints = (waypoints - 1).coerceAtLeast(0); post(Event.WaypointArrived(info.name)) }
        override fun onDestinationArrived(info: DestinationArrivedInfo) = post(Event.Arrived(info.name, info.distance, info.duration))
        override fun onError(code: NaviErrorCode) = post(Event.Failed(code.name))
    }

    init {
        helper.initialize(
            { ready = true; helper.addListener(listener); post(Event.Ready(true)) },
            { Log.w("ChagokNavi", "NaviHelper unavailable", it); post(Event.Ready(false)) },
        )
    }

    /**
     * Route to the last place through up to two earlier places. Returns a note for the driver when places had to be
     * dropped (no coordinates, or more than two waypoints), null when everything went in.
     */
    fun route(places: List<Place>): String? {
        val stops = places.filter { it.lat != null && it.lng != null }
        if (stops.isEmpty()) return "좌표가 있는 장소가 없어 경로를 만들 수 없어요"
        val via = stops.dropLast(1).take(2)
        runCatching {
            helper.requestRouteWithWaypoints(RouteWithWaypointsRequest(routeInfo(stops.last()), via.mapIndexed { i, p -> waypoint(p, if (i == 0) WaypointIndex.FIRST else WaypointIndex.SECOND) }))
        }.onFailure { Log.w("ChagokNavi", "requestRouteWithWaypoints failed", it); return "내비에 경로를 보내지 못했어요" }
        waypoints = via.size
        return when {
            stops.size < places.size -> "좌표가 없는 장소는 경로에서 뺐어요"
            stops.size - 1 > 2 -> "내비 경유지는 2곳까지라 처음 두 곳만 넣었어요"
            else -> null
        }
    }

    /** Insert an approved detour as the next stop. False when it has no coordinates or both waypoint slots are taken. */
    fun addWaypoint(place: Place): Boolean {
        if (place.lat == null || place.lng == null || waypoints >= 2) return false
        runCatching { helper.addWaypoint(waypoint(place, WaypointIndex.FIRST)) }
            .onFailure { Log.w("ChagokNavi", "addWaypoint failed", it); return false }
        waypoints++
        return true
    }

    fun release() { runCatching { helper.removeListener(listener); helper.release() } }

    private fun post(e: Event) { main.post { onEvent(e) } }
    private fun routeInfo(p: Place) = RouteInfo(latitude = p.lat!!, longitude = p.lng!!, poiName = p.name, poiId = p.id, address = p.name, poiSubId = "0")
    private fun waypoint(p: Place, index: WaypointIndex) = RequestWaypointInfo(latitude = p.lat!!, longitude = p.lng!!, poiName = p.name, waypointIndex = index, poiId = p.id, address = p.name, poiSubId = "0")
}
