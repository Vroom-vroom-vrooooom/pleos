package io.chagok.app.data

import io.chagok.app.domain.DriveEvent
import io.chagok.app.domain.Place
import io.chagok.app.domain.TripDraft
import io.chagok.app.domain.TripPlan
import io.chagok.app.domain.toJson
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** A non-2xx answer from the backend. [retryAfter] is set for 429. */
class ApiError(val status: Int, message: String, val retryAfter: Int? = null) : Exception(message)

/**
 * Phone side of the Chagok backend (backend repo, /v1, see its README and /docs).
 * Calls block: run them off the main thread. The token comes from the caller (account login only;
 * the team invite key and any Claude key stay on the server and never ship in the app).
 */
class ChagokApi(private val baseUrl: String, private val token: () -> String?) {

    /** POST /v1/auth/login → account access token (24 h). */
    fun login(email: String, password: String): String =
        obj(call("POST", "/v1/auth/login", JSONObject().put("email", email).put("password", password), auth = false))
            .getString("access_token")

    /** GET /v1/devices, Pleos ones only: id to name. */
    fun pleosDevices(): List<Pair<String, String>> {
        val all = arr(call("GET", "/v1/devices"))
        return (0 until all.length()).map { all.getJSONObject(it) }
            .filter { it.optString("kind") == "pleos" }
            .map { it.getString("id") to it.optString("name", "Pleos") }
    }

    /**
     * Saves the trip and returns the server tripId: PUT when [tripId] is known, POST otherwise
     * (also when the old trip is gone). Body = TripDraft fields + visibility, without `v`/`tripId`.
     */
    fun saveTrip(tripId: String?, draft: TripDraft, places: List<Place>): String {
        val body = JSONObject(TripPlan("draft", draft, places).toJson()).apply {
            remove("v"); remove("tripId"); put("visibility", visibility(draft.visibility))
        }
        if (tripId != null) {
            try {
                return obj(call("PUT", "/v1/trips/${enc(tripId)}", body)).getString("tripId")
            } catch (e: ApiError) {
                if (e.status != 404) throw e
            }
        }
        return obj(call("POST", "/v1/trips", body)).getString("tripId")
    }

    /** POST /v1/trips/{id}/stage: queues the TripPlan for that Pleos device. Queued ≠ shown in the car yet. */
    fun stage(tripId: String, deviceId: String): String =
        obj(call("POST", "/v1/trips/${enc(tripId)}/stage?device_id=${enc(deviceId)}")).getString("messageId")

    /** GET /v1/trips/{id}/events: what the car recorded, oldest first. */
    fun events(tripId: String): List<DriveEvent> {
        val all = arr(call("GET", "/v1/trips/${enc(tripId)}/events"))
        return (0 until all.length()).map { DriveEvent.fromJson(all.getJSONObject(it).toString()) }
    }

    /** POST /v1/pairings (account login): an 8-digit code, valid 5 minutes, that the car types in. */
    fun pairingCode(): String = obj(call("POST", "/v1/pairings")).getString("code")

    // ── Pleos side (device token from [claim]) ──

    /** POST /v1/pairings/claim, no login: this car's deviceId and device token (30 days). */
    fun claim(code: String, name: String): Pair<String, String> =
        obj(call("POST", "/v1/pairings/claim", JSONObject().put("code", code).put("name", name).put("kind", "pleos"), auth = false))
            .let { it.getString("deviceId") to it.getString("access_token") }

    /** One queued message: [kind] TripPlan | SpeakCommand, [payload] contract v1 JSON, [createdMs] when the server queued it. */
    class Message(val kind: String, val payload: String, val createdMs: Long)

    /** GET /v1/devices/{id}/messages: up to 100 messages after [cursor], and the cursor to keep once they are handled. */
    fun messages(deviceId: String, cursor: String): Pair<List<Message>, String> {
        val o = obj(call("GET", "/v1/devices/${enc(deviceId)}/messages?after=${enc(cursor)}"))
        val all = o.getJSONArray("messages")
        return (0 until all.length()).map { all.getJSONObject(it) }
            .map { Message(it.getString("kind"), it.getJSONObject("payload").toString(), it.getLong("created") / 1_000_000) } to o.getString("nextCursor")
    }

    /** POST /v1/trips/{id}/places: a place found in the car (not in the plan), so events about it are accepted. */
    fun addPlace(tripId: String, place: Place) { call("POST", "/v1/trips/${enc(tripId)}/places", place.toJson()) }

    /** POST /v1/trips/{id}/events. The same payload twice is stored once. */
    fun postEvent(event: DriveEvent) { call("POST", "/v1/trips/${enc(event.tripId)}/events", JSONObject(event.toJson())) }

    private fun call(method: String, path: String, body: JSONObject? = null, auth: Boolean = true): String {
        val connection = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 15_000; connection.readTimeout = 30_000
            connection.setRequestProperty("Accept", "application/json")
            if (auth) token()?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val status = connection.responseCode
            val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val detail = runCatching { JSONObject(text).opt("detail")?.toString() }.getOrNull() ?: "HTTP $status"
                throw ApiError(status, detail, connection.getHeaderField("Retry-After")?.toIntOrNull())
            }
            return text
        } finally {
            connection.disconnect()
        }
    }

    private fun obj(text: String) = JSONObject(text)
    private fun arr(text: String) = JSONArray(text)
    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")

    companion object {
        /**
         * The phone has three choices, the backend two (private | link).
         * ponytail: "나만" → private, anything shared → link, until the team settles BACKEND.md 공개 범위.
         */
        fun visibility(choice: String) = if (choice == "나만") "private" else "link"
    }
}
