package io.chagok.app.ui

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.widget.*
import io.chagok.app.R
import io.chagok.app.domain.*
import java.time.Instant
import java.util.Locale

/**
 * Pleos app: C01–C05. Pleos owns the navigation, status and dock; this activity is only the Chagok panel
 * inside the window Pleos gives it. It receives a TripPlan and SpeakCommands and emits DriveEvents (domain/TripContract.kt).
 */
class CarActivity : ScreenActivity() {
    override val layout = R.layout.activity_car
    override val startScreen = Screen.CAR_READY
    private val savedPlaces = linkedSetOf<String>()
    private var pendingPlace: Place? = null
    private var vehicleDetail: String? = null
    private var discoveryIndex = 0
    private var plan: TripPlan? = null
    private val tripId get() = plan?.tripId ?: "demo"
    private var tts: TextToSpeech? = null
    private var ttsReady: Boolean? = null // null while the engine is still starting
    private var queuedLine: String? = null
    private var spokenLine: String? = null
    private var speakingNow = false

    override fun onCreate(state: Bundle?) {
        if (state == null) intent.getStringExtra(TripContract.EXTRA_TRIP)?.let(::applyPlan)
        super.onCreate(state)
        // Pleos TTS SDK, on-device mode: no network or app registration needed.
        tts = TextToSpeech(applicationContext, Mode.ON_DEVICE).apply {
            initialize(object : OnInitializeListener {
                override fun onSuccess() = runOnUiThread { setLocale(Locale.KOREA); ttsReady = true; flushQueuedLine() }
                override fun onFailure(errorType: ErrorType) = runOnUiThread {
                    Log.w("ChagokContract", "Pleos TTS unavailable ($errorType); SpeakCommand text is shown only")
                    ttsReady = false; flushQueuedLine()
                }
            })
            addEventListener(ttsEvents)
        }
        if (state == null) intent.getStringExtra(TripContract.EXTRA_SPEAK)?.let(::onSpeak)
    }

    /** Running app: `am start` with singleTop delivers new TripPlans / SpeakCommands here. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(TripContract.EXTRA_TRIP)?.let { applyPlan(it); render() }
        intent.getStringExtra(TripContract.EXTRA_SPEAK)?.let(::onSpeak)
    }

    override fun onDestroy() { tts?.removeEventListener(ttsEvents); tts?.release(); super.onDestroy() }

    private val ttsEvents = object : EventListener {
        override fun onReady() = Unit
        override fun onStart() = Unit
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onDone() = finishSpeaking()
        override fun onStop() = finishSpeaking()
        override fun onError(errorType: ErrorType) { Log.w("ChagokContract", "Pleos TTS error $errorType"); finishSpeaking() }
    }
    private fun finishSpeaking() = runOnUiThread { speakingNow = false; if (!isFinishing) render() }
    private fun flushQueuedLine() { queuedLine?.let { queuedLine = null; say(it) } }

    /** Backend → Pleos. During a trip Chagok switches to the radio (C02), shows the line and says it. */
    private fun onSpeak(json: String) {
        val command = runCatching { SpeakCommand.fromJson(json) }
            .onFailure { Log.w("ChagokContract", "Ignoring invalid SpeakCommand", it) }.getOrNull() ?: return
        spokenLine = command.text; speakingNow = true
        if (screen != Screen.CAR_RADIO || vehicleDetail != null) navigate(Screen.CAR_RADIO) else render()
        say(command.text)
    }

    private fun say(text: String) {
        when (ttsReady) {
            null -> queuedLine = text
            true -> tts?.speak(text, true)
            false -> { speakingNow = false; render() } // no Korean voice: text only
        }
    }

    /** Phone → Pleos entry point. Until the transport exists, the plan arrives as an intent extra (see CONTRACT.md). */
    private fun applyPlan(json: String) {
        runCatching { TripPlan.fromJson(json) }
            .onSuccess { plan = it; draft = it.draft; itineraryIds.clear(); itineraryIds.addAll(it.places.map(Place::id)) }
            .onFailure { Log.w("ChagokContract", "Ignoring invalid TripPlan, showing sample trip", it) }
    }

    override fun itineraryPlaces() = plan?.places ?: super.itineraryPlaces()

    /** Pleos → Phone. ponytail: logcat stands in for the transport until the backend relay exists. */
    private fun emit(type: DriveEvent.Type, placeId: String? = null) {
        Log.i("ChagokContract", DriveEvent(tripId, type, placeId, Instant.now().toString()).toJson())
    }

    override fun navigate(next: Screen) { vehicleDetail = null; super.navigate(next) }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (vehicleDetail != null) { vehicleDetail = null; render() } else super.onBackPressed()
    }

    override fun render() {
        content = findViewById(R.id.vehicle_content)
        content.removeAllViews()
        findViewById<TextView>(R.id.vehicle_destination).text = if (screen == Screen.CAR_SUMMARY) "오늘의 기록" else draft.destination
        findViewById<Button>(R.id.vehicle_schedule).setOnClickListener { vehicleDetail = "schedule"; render() }
        findViewById<Button>(R.id.vehicle_discover).setOnClickListener { navigate(Screen.CAR_DISCOVER) }
        findViewById<Button>(R.id.vehicle_record).setOnClickListener { navigate(Screen.CAR_SUMMARY) }
        if (vehicleDetail != null) {
            button("차곡으로 돌아가기", false) { vehicleDetail = null; render() }
            if (vehicleDetail == "schedule") {
                text("오늘의 작은 여행", 23)
                text("${draft.departure} · ${draft.destination} · ${draft.mood}", 13, muted)
                itineraryPlaces().forEach { text("${it.time}   ${it.name}", 17) }
                button("차곡과 출발하기") { emit(DriveEvent.Type.TRIP_STARTED); navigate(Screen.CAR_RADIO) }
            } else {
                text("차곡차곡 쌓인 오늘", 23)
                text("214 km · 들른 곳 3곳 · 이야기 2개", 18)
                text("예시 기록 · 실제 위치·사진은 연결 전", 12, muted)
                text((repository.places + repository.radioPlace).filter { it.id in savedPlaces }
                    .joinToString(" · ") { it.name }.ifEmpty { "아직 저장한 장소가 없어요." }, 15)
                button("폰에서 기록 이어가기") { continueOnPhone() }
            }
        } else companion()
        findViewById<ScrollView>(R.id.vehicle_scroll).scrollTo(0, 0)
    }

    private fun companion() {
        layoutInflater.inflate(R.layout.panel_radio, content, true)
        val orb = findViewById<CompanionOrbView>(R.id.companion_orb)
        val status = findViewById<TextView>(R.id.companion_status)
        val line = findViewById<TextView>(R.id.companion_line)
        val context = findViewById<TextView>(R.id.companion_context)
        val secondary = findViewById<Button>(R.id.companion_secondary)
        val primary = findViewById<Button>(R.id.companion_primary)
        fun response(button: Button, label: String, action: () -> Unit) {
            button.text = label; button.setOnClickListener { action() }
        }
        when (screen) {
            Screen.CAR_READY -> {
                status.text = "오늘의 여정을 준비했어요"
                line.text = "${draft.destination}에서 만날 ${itineraryIds.size}곳의 이야기,\n함께 떠나 볼까요?"
                context.text = "${draft.companions.joinToString(" · ")} · ${draft.mood} 분위기로 준비했어요."
                response(secondary, "어디로 가는지 알려줘") { vehicleDetail = "schedule"; render() }
                response(primary, "좋아, 출발하자") { emit(DriveEvent.Type.TRIP_STARTED); navigate(Screen.CAR_RADIO) }
            }
            Screen.CAR_RADIO -> {
                spokenLine?.let {
                    status.text = if (speakingNow) "차곡이 이야기하고 있어요" else "차곡이 전한 이야기"
                    line.text = it
                    context.text = "${draft.destination} 가는 길 · 길 위의 라디오"
                }
                response(primary, "이 장소 기억해 줘") { saveVehiclePlace(repository.radioPlace) }
                add(Button(this).apply {
                    text = "도착 화면 보기"; textSize = 12f; isAllCaps = false
                    setBackgroundColor(Color.TRANSPARENT); setTextColor(muted)
                    setOnClickListener { navigate(Screen.CAR_SUMMARY) }
                })
            }
            Screen.CAR_APPROVAL -> {
                val place = pendingPlace ?: repository.radioPlace
                status.text = "마음에 담아 두었어요"
                line.text = "${place.name}에\n잠깐 들렀다 갈까요?"
                context.text = "경유지로 추가하면 ${detourMinutes(place)}분 더 걸려요. · 예시"
                response(secondary, "다음에 가자") { emit(DriveEvent.Type.WAYPOINT_DECLINED, place.id); navigate(Screen.CAR_RADIO) }
                response(primary, "응, 들렀다 가자") {
                    repository.requestWaypoint(place)
                    emit(DriveEvent.Type.WAYPOINT_APPROVED, place.id)
                    navigate(Screen.CAR_RADIO)
                    message("경유지 승인 화면 예시예요. 실제 내비 경로는 바뀌지 않아요.")
                }
            }
            Screen.CAR_DISCOVER -> {
                val options = repository.places.take(3)
                val place = options[discoveryIndex % options.size]
                status.text = "이런 곳은 어떠세요?"
                line.text = "${place.name}에서\n조금 쉬어 가도 좋겠어요."
                context.text = "${place.source} · ${place.distance}"
                response(secondary, "다른 곳도 알려줘") { discoveryIndex = (discoveryIndex + 1) % options.size; render() }
                response(primary, "여기 마음에 들어") { saveVehiclePlace(place) }
            }
            Screen.CAR_SUMMARY -> {
                status.text = "오늘도 함께해 줘서 고마워요"
                line.text = "오늘, 가장 오래 기억하고 싶은\n순간은 무엇인가요?"
                context.text = "함께 달린 214 km · 들른 곳 3곳 · 예시 기록"
                response(secondary, "오늘을 돌아볼래") { vehicleDetail = "record"; render() }
                response(primary, "사진으로 남겨 볼래") { continueOnPhone() }
            }
            else -> Unit
        }
        orb.speaking = speakingNow
        if (speakingNow && screen == Screen.CAR_RADIO) secondary.text = "잠깐 쉬어 가자"
        val idleStatus = if (speakingNow) "차곡이 전한 이야기" else status.text
        fun toggleSpeaking() {
            orb.speaking = !orb.speaking
            if (!orb.speaking && speakingNow) { tts?.stop(); speakingNow = false }
            status.text = if (orb.speaking) "차곡이 이야기하는 모습 · 음성 연결 전" else idleStatus
            if (screen == Screen.CAR_RADIO) secondary.text = if (orb.speaking) "잠깐 쉬어 가자" else "말하는 모습 보기"
        }
        orb.setOnClickListener { toggleSpeaking() }
        if (screen == Screen.CAR_RADIO) secondary.setOnClickListener { toggleSpeaking() }
    }

    private fun continueOnPhone() { emit(DriveEvent.Type.TRIP_FINISHED); message("폰 앱에서 기록을 이어가요. 폰↔차량 연동은 연결 전이에요.") }
    private fun saveVehiclePlace(place: Place) { pendingPlace = place; savedPlaces.add(place.id); emit(DriveEvent.Type.PLACE_SAVED, place.id); navigate(Screen.CAR_APPROVAL) }
    private fun detourMinutes(place: Place) = when (place.id) { "anmok" -> 8; "gyeongpo" -> 18; else -> 12 }

    override fun onSaveInstanceState(out: Bundle) {
        out.putInt("discoveryIndex", discoveryIndex); out.putString("vehicleDetail", vehicleDetail)
        out.putStringArrayList("saved", ArrayList(savedPlaces)); out.putString("pending", pendingPlace?.id); out.putString("plan", plan?.toJson()); out.putString("spoken", spokenLine)
        super.onSaveInstanceState(out)
    }
    override fun restore(state: Bundle) {
        super.restore(state)
        state.getString("plan")?.let { plan = runCatching { TripPlan.fromJson(it) }.getOrNull() }
        spokenLine = state.getString("spoken")
        discoveryIndex = state.getInt("discoveryIndex", 0).coerceAtLeast(0)
        vehicleDetail = state.getString("vehicleDetail")?.takeIf { it == "schedule" || it == "record" }
        savedPlaces.addAll(state.getStringArrayList("saved").orEmpty())
        pendingPlace = (repository.places + repository.radioPlace).find { it.id == state.getString("pending") }
    }
}
