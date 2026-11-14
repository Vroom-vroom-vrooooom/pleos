package io.chagok.app.domain

import org.json.JSONArray
import org.json.JSONObject

/**
 * Phone ↔ Pleos (+ backend → Pleos) contract, version 1. This file must stay identical in the `phone` and `pleos` repos.
 * Change it in both repos together and bump VERSION when a field changes meaning. See CONTRACT.md.
 * Only the payloads are fixed here; the transport (server relay, etc.) is decided with the backend.
 */
object TripContract {
    const val VERSION = 1
    /** Intent extras the Pleos app accepts for local testing (adb). */
    const val EXTRA_TRIP = "chagok.trip"
    const val EXTRA_SPEAK = "chagok.speak"
    /** Intent extra the phone app accepts for local testing (adb): one DriveEvent JSON. */
    const val EXTRA_EVENT = "chagok.event"
}

/**
 * Backend → Pleos: a line Chagok should say right now while the trip is running.
 * `place` (optional) is the place the line talks about; tapping it saves the place and asks "들렀다 갈까요?" (navi waypoint).
 */
data class SpeakCommand(val tripId: String, val text: String, val place: Place? = null) {
    fun toJson(): String = JSONObject().put("v", TripContract.VERSION).put("tripId", tripId).put("text", text)
        .apply { place?.let { put("place", it.toJson()) } }.toString()

    companion object {
        fun fromJson(json: String): SpeakCommand {
            val o = JSONObject(json)
            require(o.getInt("v") == TripContract.VERSION) { "Unsupported SpeakCommand version ${o.getInt("v")}" }
            val text = o.getString("text").trim()
            require(text.isNotEmpty()) { "SpeakCommand text is empty" }
            return SpeakCommand(o.getString("tripId"), text, o.optJSONObject("place")?.let(::placeFromJson))
        }
    }
}

/** Phone → Pleos: the trip the user confirmed on the phone (P09 "차로 보내기"). */
data class TripPlan(val tripId: String, val draft: TripDraft, val places: List<Place>) {
    fun toJson(): String = JSONObject()
        .put("v", TripContract.VERSION).put("tripId", tripId)
        .put("destination", draft.destination).put("departure", draft.departure).put("endDate", draft.endDate)
        .put("recordRoute", draft.recordRoute)
        .put("companions", JSONArray(draft.companions.toList())).put("interests", JSONArray(draft.interests.toList()))
        .put("mood", draft.mood).put("density", draft.density)
        .put("places", JSONArray(places.map { it.toJson() }))
        .toString()

    companion object {
        fun fromJson(json: String): TripPlan {
            val o = JSONObject(json)
            require(o.getInt("v") == TripContract.VERSION) { "Unsupported TripPlan version ${o.getInt("v")}" }
            val draft = TripDraft(
                destination = o.getString("destination"), departure = o.getString("departure"),
                endDate = o.optString("endDate", "미정"), recordRoute = o.optBoolean("recordRoute"),
                companions = o.getJSONArray("companions").strings().toCollection(linkedSetOf()),
                interests = o.getJSONArray("interests").strings().toCollection(linkedSetOf()),
                mood = o.getString("mood"), density = o.getString("density"),
            )
            val places = o.getJSONArray("places").let { a -> (0 until a.length()).map { placeFromJson(a.getJSONObject(it)) } }
            return TripPlan(o.getString("tripId"), draft, places)
        }
    }
}

/** Pleos → Phone: what happened on the road, so the phone can build the trip record (P10–P13). */
data class DriveEvent(val tripId: String, val type: Type, val placeId: String?, val at: String) {
    enum class Type { TRIP_STARTED, PLACE_SAVED, WAYPOINT_APPROVED, WAYPOINT_DECLINED, TRIP_FINISHED }

    fun toJson(): String = JSONObject()
        .put("v", TripContract.VERSION).put("tripId", tripId).put("type", type.name)
        .put("placeId", placeId ?: JSONObject.NULL).put("at", at)
        .toString()

    companion object {
        fun fromJson(json: String): DriveEvent {
            val o = JSONObject(json)
            require(o.getInt("v") == TripContract.VERSION) { "Unsupported DriveEvent version ${o.getInt("v")}" }
            return DriveEvent(o.getString("tripId"), Type.valueOf(o.getString("type")),
                if (o.isNull("placeId")) null else o.getString("placeId"), o.getString("at"))
        }
    }
}

internal fun Place.toJson() = JSONObject().put("id", id).put("name", name).put("time", time).put("duration", duration)
    .put("source", source).put("group", group).put("reason", reason).put("distance", distance)
    .apply { lat?.let { put("lat", it) }; lng?.let { put("lng", it) }; if (open.isNotEmpty()) put("open", open) }

private fun placeFromJson(o: JSONObject) = Place(o.getString("id"), o.getString("name"), o.optString("time"), o.optString("duration"),
    o.optString("source"), o.optString("group"), o.optString("reason"), o.optString("distance"),
    o.optDouble("lat").takeUnless { it.isNaN() }, o.optDouble("lng").takeUnless { it.isNaN() }, o.optString("open"))

private fun JSONArray.strings() = (0 until length()).map { getString(it) }
