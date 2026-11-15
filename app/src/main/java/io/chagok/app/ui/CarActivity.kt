package io.chagok.app.ui

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.TypefaceSpan
import android.animation.ValueAnimator
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import io.chagok.app.BuildConfig
import io.chagok.app.R
import io.chagok.app.data.ApiError
import io.chagok.app.data.ChagokApi
import io.chagok.app.domain.*
import java.io.IOException
import java.time.Instant
import java.util.concurrent.Executors
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale
import kotlin.math.abs

/**
 * Pleos app. Pleos owns the navigation, status bar, DrivingView and dock; this activity is only the Chagok panel in the
 * window Pleos gives it. Look = design v4 (canvas "차곡 디자인": 차량 다크 / 차량 라이트, follows the car's night mode).
 * - start (CAR_READY): tabs 오늘 일정 / 발견 / 기록. 오늘 일정 = trip photo + today's stops; 코스 바꾸기 = trip cards;
 *   발견 = nearby places with 추가 (goes into the route); 기록 = route, counts, places kept so far. 출발하기 → Pleos navi.
 * - live (CAR_RADIO): the place photo fills the panel; Chagok says what the backend sends (SpeakCommand). The only driver
 *   input is the voice mark (stop) and the place chip, which opens "들렀다 갈까요?" (approve → Pleos navi waypoint).
 * - arrive (CAR_SUMMARY): the 기록 tab as an arrival summary when the navi reports the destination.
 * Half window (~420dp wide) stacks what the design puts side by side; the full window (~840dp) follows the design.
 */
class CarActivity : ScreenActivity() {
    override val layout = R.layout.activity_car
    override val startScreen = Screen.CAR_READY

    /** Trips the driver can switch to with "코스 바꾸기"; a TripPlan from the phone is put first. */
    private val trips = sampleTrips().toMutableList()
    private var plan: TripPlan = trips.first()
    private val tripId get() = plan.tripId
    /** Today's stops in order; approved detours are inserted before the next stop. */
    private val route = mutableListOf<Place>()
    private var visited = 0
    private var tab = TAB_PLAN
    private var showTrips = false
    private var nextEta: String? = null
    private var arrived: Pair<Int, Int>? = null // distance m, duration s from the navi
    private val savedPlaces = linkedSetOf<String>()
    private var sheetPlace: Place? = null
    private var linesHeard = 0
    private var toastText: String? = null

    private var navi: PleosNavi? = null
    private var tts: TextToSpeech? = null
    private var ttsReady: Boolean? = null // null while the engine is still starting
    private var queuedLine: String? = null
    private var current: SpeakCommand? = null // the line on screen
    private var speakingNow = false
    private var freshLine = false
    private var saying = "" // what the TTS was last asked to read
    private var held: Pair<String, Long>? = null // line cut off by a call / Gleo, and when, to re-read afterwards
    /**
     * Backend link after pairing: deviceId, device token (30 days) and the message cursor, in this app's private preferences.
     * ponytail: move the token to Android Keystore before release (Pleos privacy guide). No account login on the car.
     */
    private val session by lazy { getSharedPreferences("chagok-car", MODE_PRIVATE) }
    private val api by lazy { ChagokApi(BuildConfig.API_BASE_URL) { session.getString("token", null) } }
    private val paired get() = session.getString("deviceId", null) != null
    private val net = Executors.newSingleThreadExecutor() // one at a time, so events reach the server in order
    /** DriveEvents (+ a place found in the car to register first) not yet accepted by the server. Main thread only.
     *  ponytail: in memory, lost if the app is killed offline; persist it if that happens on the road. */
    private val outbox = mutableListOf<Pair<DriveEvent, Place?>>()
    private var fetching = false
    private val poll = object : Runnable { override fun run() { fetch(); main.postDelayed(this, POLL_MS) } }
    private var diaryOpen = false // 영상일기 after arrival (example UI: nothing is recorded or sent yet)
    private var diaryMemo: Int? = null // seconds of the 오늘의 한마디; -1 while "recording"
    private var startedAt: String? = null // HH:mm of 출발하기, the diary's first chapter
    // Live photo slideshow: which place it shows, since when, and the two image layers that crossfade.
    private var slideFor: String? = null
    private var slideAt = 0L
    private var slidePics: List<Int> = emptyList()
    private var slideViews: Pair<ImageView, ImageView>? = null
    private val slideTick = Runnable { nextSlide() }
    private var profileOpen = false // 프로필: phone connection (pairing) lives here, parked screens only
    private var pairing: String? = null // digits typed on the pairing pad; null = pad closed
    private val audio by lazy { getSystemService(AudioManager::class.java) }
    private val main = Handler(Looper.getMainLooper())
    private val root by lazy { findViewById<FrameLayout>(R.id.car_root) }
    /** Full app slot (~840dp) gets the design's side-by-side layout; the half window (~420dp) stacks it. */
    private val wide get() = root.width >= dp(600)

    // design v4 tokens (values/colors.xml light, values-night dark)
    private val s0 by lazy { getColor(R.color.c_s0) }
    private val s1 by lazy { getColor(R.color.c_s1) }
    private val s2 by lazy { getColor(R.color.c_s2) }
    private val s3 by lazy { getColor(R.color.c_s3) }
    private val tx by lazy { getColor(R.color.c_tx) }
    private val mu by lazy { getColor(R.color.c_mu) }
    private val inv by lazy { getColor(R.color.c_inv) }
    private val onInv by lazy { getColor(R.color.c_on_inv) }

    /**
     * Pleos Connect reports mdpi (160 dpi) on its 2560×1440 screen, so dp/sp UI renders tiny.
     * ponytail: draw the panel at 2× (xhdpi) = the design's car unit (1u = 2px); replace with the Pleos design-system scale once we have it.
     */
    override fun attachBaseContext(base: Context) {
        val config = Configuration(base.resources.configuration).apply { densityDpi = DisplayMetrics.DENSITY_XHIGH }
        super.attachBaseContext(base.createConfigurationContext(config))
    }

    override fun onCreate(state: Bundle?) {
        if (state == null) intent.getStringExtra(TripContract.EXTRA_TRIP)?.let(::applyPlan)
        selectTrip(plan)
        super.onCreate(state)
        // Re-lay out when Pleos switches between the half window and the full slot.
        root.addOnLayoutChangeListener { _, l, _, r, _, ol, _, or, _ -> if ((r - l >= dp(600)) != (or - ol >= dp(600))) root.post { render() } }
        // Pleos TTS SDK, on-device mode: no network or app registration needed.
        tts = TextToSpeech(applicationContext, Mode.ON_DEVICE).apply {
            initialize(object : OnInitializeListener {
                override fun onSuccess() = runOnUiThread { setLocale(Locale.KOREA); ttsReady = true; flushQueuedLine() }
                override fun onFailure(errorType: ErrorType) = runOnUiThread {
                    Log.w("ChagokContract", "Pleos TTS unavailable ($errorType); lines are shown only")
                    ttsReady = false; flushQueuedLine()
                }
            })
            addEventListener(ttsEvents)
        }
        audio.registerAudioRecordingCallback(micWatch, null)
        if (Build.VERSION.SDK_INT >= 31) audio.addOnModeChangedListener(mainExecutor, modeWatch)
        navi = PleosNavi(this, ::onNavi)
        if (state == null) intent.getStringExtra(TripContract.EXTRA_SPEAK)?.let(::onSpeak)
        // Backend messages arrive as broadcasts so they don't bring Chagok to the front: Pleos moves a front-launched
        // app into the primary (left) window and would swap it with the navi.
        // ponytail: exported for adb/QA (qa.sh); the backend queue (fetch) is the real transport. Drop the export before release.
        val filter = IntentFilter(ACTION_SPEAK).apply { addAction(ACTION_TRIP); addAction(ACTION_ARRIVE) }
        registerReceiver(receiver, filter, RECEIVER_EXPORTED) // minSdk 28 has the flags overload; Pleos is API 34
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            intent.getStringExtra(TripContract.EXTRA_TRIP)?.let(::receivePlan)
            intent.getStringExtra(TripContract.EXTRA_SPEAK)?.let(::onSpeak)
            if (intent.action == ACTION_ARRIVE) arrive(null) // QA: pretend the navi reported the destination
        }
    }

    /** Running app: `am start` with singleTop delivers new TripPlans / SpeakCommands here. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(TripContract.EXTRA_TRIP)?.let(::receivePlan)
        intent.getStringExtra(TripContract.EXTRA_SPEAK)?.let(::onSpeak)
    }

    // Poll the backend only while the panel is on screen (the navi beside it keeps it started).
    override fun onStart() { super.onStart(); main.removeCallbacks(poll); main.post(poll) }
    override fun onStop() { main.removeCallbacks(poll); super.onStop() }

    override fun onDestroy() {
        net.shutdown()
        main.removeCallbacksAndMessages(null)
        unregisterReceiver(receiver); navi?.release()
        audio.unregisterAudioRecordingCallback(micWatch)
        if (Build.VERSION.SDK_INT >= 31) audio.removeOnModeChangedListener(modeWatch)
        tts?.removeEventListener(ttsEvents); tts?.release(); super.onDestroy()
    }

    // ── trips ──
    private fun selectTrip(p: TripPlan) {
        // Copy: the base class restores saved fields into `draft` in place, which must not touch the sample trips.
        plan = p; draft = p.draft.copy(companions = LinkedHashSet(p.draft.companions), interests = LinkedHashSet(p.draft.interests))
        itineraryIds.clear(); itineraryIds.addAll(p.places.map(Place::id))
        route.clear(); route.addAll(p.places)
        visited = 0; nextEta = null; arrived = null
    }

    /** Launch with a TripPlan extra (adb): it becomes the trip before the first render. */
    private fun applyPlan(json: String) {
        runCatching { TripPlan.fromJson(json) }
            .onSuccess { incoming -> trips.removeAll { it.tripId == incoming.tripId }; trips.add(0, incoming); plan = incoming }
            .onFailure { Log.w("ChagokContract", "Ignoring invalid TripPlan, showing sample trip: ${it.message}") }
    }

    /** A TripPlan from the phone (backend queue or adb): shown at once before departure; while driving it waits in 코스 바꾸기. */
    private fun receivePlan(json: String) {
        val incoming = runCatching { TripPlan.fromJson(json) }
            .onFailure { Log.w("ChagokContract", "Ignoring invalid TripPlan: ${it.message}") }.getOrNull() ?: return
        trips.removeAll { it.tripId == incoming.tripId }; trips.add(0, incoming)
        if (screen == Screen.CAR_RADIO) { toast("폰에서 ${incoming.draft.destination} 여행이 왔어요. 코스 바꾸기에 넣어 둘게요"); return }
        selectTrip(incoming); showTrips = false; tab = TAB_PLAN
        if (screen != Screen.CAR_READY) navigate(Screen.CAR_READY) else render()
    }

    // ── backend (after pairing) ──
    /** Picks up queued TripPlans / SpeakCommands, and resends events that failed for a passing reason. */
    private fun fetch() {
        val device = session.getString("deviceId", null) ?: return
        flush()
        if (fetching) return
        fetching = true
        val cursor = session.getString("cursor", "").orEmpty()
        net.execute {
            val result = runCatching { api.messages(device, cursor) }
            runOnUiThread {
                fetching = false
                if (isDestroyed) return@runOnUiThread
                result.onSuccess { (messages, next) ->
                    messages.forEach { m ->
                        when (m.kind) {
                            "TripPlan" -> receivePlan(m.payload)
                            // No expiry on the server: a line queued while the car was off is not news any more.
                            "SpeakCommand" -> if (System.currentTimeMillis() - m.createdMs < STALE_MS) onSpeak(m.payload)
                                else Log.i("ChagokContract", "Skipping an old SpeakCommand")
                        }
                    }
                    session.edit().putString("cursor", next).apply()
                }.onFailure { e ->
                    if (e is ApiError && (e.status == 401 || e.status == 404)) unpair("폰과 연결이 끊겼어요. 다시 연결해 주세요")
                    else Log.w("ChagokApi", "Message poll failed", e)
                }
            }
        }
    }

    /** Sends queued DriveEvents in order; stops at the first one that failed for a passing reason (retried on the next poll). */
    private fun flush() {
        if (outbox.isEmpty()) return
        val batch = outbox.toList(); outbox.clear()
        net.execute {
            val left = batch.dropWhile { (event, found) ->
                runCatching { found?.let { api.addPlace(event.tripId, it) }; api.postEvent(event) }.fold({ true }) { e ->
                    Log.w("ChagokApi", "DriveEvent not sent: ${event.toJson()}", e)
                    !(e is IOException || e is ApiError && (e.status == 429 || e.status >= 500)) // rejected for good: drop it
                }
            }
            if (left.isNotEmpty()) runOnUiThread { outbox.addAll(0, left) }
        }
    }

    private fun claim(code: String) {
        net.execute {
            val result = runCatching { api.claim(code, "Pleos 차량") }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                result.onSuccess { (device, token) ->
                    session.edit().putString("deviceId", device).putString("token", token).putString("cursor", "").apply()
                    pairing = null; toast("폰과 연결했어요. 폰에서 보낸 여행이 여기로 와요"); fetch()
                }.onFailure { e ->
                    pairing = ""
                    toast(when {
                        e is ApiError && e.status in setOf(400, 422) -> "번호가 맞지 않거나 5분이 지났어요"
                        e is ApiError && e.status == 429 -> "잠시 후 다시 입력해 주세요"
                        e is ApiError -> "서버 오류가 났어요 (${e.status})"
                        else -> "인터넷에 연결하지 못했어요"
                    })
                }
            }
        }
    }

    private fun unpair(message: String) {
        session.edit().clear().apply(); outbox.clear(); toast(message)
    }

    override fun itineraryPlaces() = route

    private fun startTrip() {
        showTrips = false; tab = TAB_PLAN
        startedAt = LocalTime.now().let { String.format(Locale.KOREA, "%02d:%02d", it.hour, it.minute) }
        sendRoute()
        emit(DriveEvent.Type.TRIP_STARTED)
        val first = route.firstOrNull()
        showLine(SpeakCommand(tripId, if (first == null) "출발! 가는 길에 재밌는 이야기가 보이면 들려줄게요."
            else "출발! 먼저 ${ro(first.name)} 가요. 가는 길에 재밌는 이야기가 보이면 들려줄게요."), counts = false)
    }

    // ── navi ──
    private fun sendRoute() {
        val nav = navi?.takeIf { it.ready } ?: run { toast("내비에 연결하지 못해 경로는 직접 설정해 주세요"); return }
        nav.route(route.drop(visited))?.let(::toast)
    }

    private fun onNavi(e: PleosNavi.Event) {
        when (e) {
            is PleosNavi.Event.Ready -> if (!e.ok) Log.w("ChagokNavi", "Navi not available; the route stays in Chagok only")
            is PleosNavi.Event.Routed -> Unit
            // 0 while the navi re-routes (e.g. right after a waypoint is added): keep the last value until a real one arrives
            is PleosNavi.Event.Progress -> if (e.remainS > 0) { nextEta = minutes(e.remainS); if (screen == Screen.CAR_RADIO) render() }
            is PleosNavi.Event.WaypointArrived -> { visited = (visited + 1).coerceAtMost(route.size); nextEta = null; render() }
            is PleosNavi.Event.Arrived -> arrive(e.distanceM to e.durationS)
            is PleosNavi.Event.Failed -> toast("내비가 경로를 만들지 못했어요 (${e.reason})")
        }
    }

    /** After arrival: clear the finished trip and go back to the start screen (also the back button on the summary). */
    private fun backToStart() {
        current = null; held = null; savedPlaces.clear(); linesHeard = 0; sheetPlace = null; showTrips = false; tab = TAB_PLAN; diaryOpen = false; diaryMemo = null
        selectTrip(plan); history.clear(); screen = Screen.CAR_READY; render()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when {
            sheetPlace != null -> { sheetPlace = null; render() }
            diaryOpen -> { diaryOpen = false; render() }
            pairing != null -> { pairing = null; render() }
            profileOpen -> { profileOpen = false; render() }
            screen == Screen.CAR_SUMMARY -> backToStart()
            showTrips -> { showTrips = false; render() }
            screen == Screen.CAR_READY && tab != TAB_PLAN -> { tab = TAB_PLAN; render() }
            else -> super.onBackPressed()
        }
    }

    private fun arrive(stats: Pair<Int, Int>?) {
        if (screen == Screen.CAR_SUMMARY) return
        arrived = stats; visited = route.size; sheetPlace = null
        tts?.stop(); speakingNow = false; held = null
        emit(DriveEvent.Type.TRIP_FINISHED)
        navigate(Screen.CAR_SUMMARY)
    }

    // ── lines (backend → Pleos) ──
    private fun onSpeak(json: String) {
        val command = runCatching { SpeakCommand.fromJson(json) }
            .onFailure { Log.w("ChagokContract", "Ignoring invalid SpeakCommand: ${it.message}") }.getOrNull() ?: return
        // A line for another trip (queued earlier, or the driver switched trips) must not be read out.
        if (command.tripId != tripId) { Log.w("ChagokContract", "Ignoring SpeakCommand for another trip (${command.tripId})"); return }
        showLine(command, counts = true)
    }

    private fun showLine(line: SpeakCommand, counts: Boolean) {
        current = line; freshLine = true; speakingNow = true
        if (counts) linesHeard++
        if (screen != Screen.CAR_RADIO) navigate(Screen.CAR_RADIO) else render()
        say(line.text)
    }

    // ── "들렀다 갈까요?" ──
    private fun openSheet(place: Place) {
        if (savedPlaces.add(place.id)) emit(DriveEvent.Type.PLACE_SAVED, place)
        sheetPlace = place; render()
    }

    private fun decide(approve: Boolean) {
        val place = sheetPlace ?: return
        sheetPlace = null
        if (!approve) { emit(DriveEvent.Type.WAYPOINT_DECLINED, place); toast("${place.name}, 기록에만 담아 둘게요"); return }
        val added = navi?.takeIf { it.ready }?.addWaypoint(place) == true
        emit(DriveEvent.Type.WAYPOINT_APPROVED, place)
        // The route follows the navi: a place joins it when the navi took it (or there is no navi to ask). With both
        // waypoints taken it only goes into the record — showing it as "다음" would point where the navi isn't going.
        val full = navi?.takeIf { it.ready } != null && !added
        if (!full && route.none { it.id == place.id }) route.add(visited.coerceAtMost(route.size), place)
        nextEta = null // the navi re-routes; wait for its next progress event
        toast(if (added) "${place.name}, 내비 경유지에 넣었어요" else if ((navi?.waypoints ?: 0) >= 2) "내비 경유지는 2곳까지라 기록에만 넣었어요" else "${place.name}, 다음 들를 곳으로 넣었어요")
    }

    /** 발견 → 추가 before departure: the place joins today's route and goes to the navi with it on 출발하기. */
    private fun addFromFind(place: Place) {
        if (route.any { it.id == place.id }) return
        if (savedPlaces.add(place.id)) emit(DriveEvent.Type.PLACE_SAVED, place)
        // Pleos takes the destination plus at most two waypoints.
        if (route.size >= 3) { toast("경유지는 2곳까지라 기록에만 넣었어요"); return }
        route.add(0, place)
        emit(DriveEvent.Type.WAYPOINT_APPROVED, place)
        toast("${place.name}, 출발하면 내비에 같이 넣을게요")
    }

    // ── TTS ──
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
    private fun say(text: String) {
        if (inConversation()) { hold(text); return }
        when (ttsReady) {
            null -> queuedLine = text
            true -> { saying = text; tts?.speak(text, true) }
            false -> { speakingNow = false; render() } // no Korean voice: text only
        }
    }

    // ── 대화 중엔 일시정지: a call (audio mode) or an open mic (Gleo, call) stops Chagok; it re-reads the line after ──
    private val micWatch = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) = onConversationChanged()
    }
    private val modeWatch = AudioManager.OnModeChangedListener { onConversationChanged() }
    private fun inConversation() = audio.mode != AudioManager.MODE_NORMAL ||
        audio.activeRecordingConfigurations.any { it.clientAudioSource != HOTWORD } // always-on wake word isn't a conversation
    private fun hold(text: String) {
        held = text to System.currentTimeMillis(); speakingNow = false
        Log.i("ChagokContract", "대화 중: 멈춤")
        toast("통화 중이라 잠시 멈춰요")
    }
    private fun onConversationChanged() = runOnUiThread {
        if (isFinishing) return@runOnUiThread
        if (inConversation()) { if (speakingNow) { hold(saying); tts?.stop() } ; return@runOnUiThread }
        val (text, at) = held ?: return@runOnUiThread
        held = null
        if (System.currentTimeMillis() - at > RESUME_WITHIN_MS) { Log.i("ChagokContract", "대화 끝: 오래돼서 넘김"); render(); return@runOnUiThread }
        Log.i("ChagokContract", "대화 끝: 다시 읽기")
        speakingNow = true; render(); say(text)
    }

    /**
     * Pleos → Phone: logged (qa.sh events) and, once paired, sent to the backend for the phone's 기록.
     * Sample trips exist only in the car, so their events stay in the log. A place outside the plan (발견, a radio
     * place) is registered with the trip first, or the backend rejects the event.
     */
    private fun emit(type: DriveEvent.Type, place: Place? = null) {
        val event = DriveEvent(tripId, type, place?.id, Instant.now().toString())
        Log.i("ChagokContract", event.toJson())
        if (!paired || tripId.startsWith("sample-")) return
        outbox.add(event to place?.takeIf { p -> plan.places.none { it.id == p.id } })
        flush()
    }

    private fun toast(text: String) {
        toastText = text; render()
        main.removeCallbacks(hideToast); main.postDelayed(hideToast, 2400)
    }
    private val hideToast = Runnable { toastText = null; if (!isFinishing) render() }

    // ── render: the whole panel is rebuilt from state ──
    override fun render() {
        root.removeAllViews(); main.removeCallbacks(slideTick); slideViews = null
        val live = screen == Screen.CAR_RADIO
        root.addView(when { live -> liveView(); screen == Screen.CAR_SUMMARY && diaryOpen -> diaryView(); else -> tabsView() }, FrameLayout.LayoutParams(MATCH, MATCH))
        sheetPlace?.takeIf { live }?.let { root.addView(sheetView(it), FrameLayout.LayoutParams(MATCH, MATCH)) }
        if (!live) {
            val pad = pairing // the number pad opens over the profile, back returns to it
            if (pad != null) root.addView(pairView(pad), FrameLayout.LayoutParams(MATCH, MATCH))
            else if (profileOpen) root.addView(profileView(), FrameLayout.LayoutParams(MATCH, MATCH))
        }
        toastText?.let { root.addView(toastView(it), FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER_HORIZONTAL or if (live) Gravity.TOP else Gravity.BOTTOM).apply {
            if (live) topMargin = dp(104) else bottomMargin = dp(100)
        }) }
    }

    /** Before departure and on arrival: tabs on top, the tab's content below (padding 24, gap 16). */
    private fun tabsView(): View {
        val arrivedNow = screen == Screen.CAR_SUMMARY
        val shown = if (arrivedNow) TAB_RECORD else tab
        val v = column(pad = 24)
        val head = row()
        // Logo kit R4: the top bar takes the horizontal lockup; the half window only has room for the symbol.
        // Light/dark versions come from drawable / drawable-night, following the car's night mode.
        head.addView(ImageView(this).apply {
            setImageResource(if (wide) R.drawable.logo_horizontal else R.drawable.logo_symbol); contentDescription = "차곡"; scaleType = ImageView.ScaleType.FIT_CENTER
        }, if (wide) lp(dp(104), dp(34)).apply { marginEnd = dp(20) } else lp(dp(30), dp(22)).apply { marginEnd = dp(10) })
        head.addView(segmented(listOf(TAB_PLAN to "오늘 일정", TAB_FIND to "발견", TAB_RECORD to "기록"), shown) { tab = it; showTrips = false; render() })
        head.addView(View(this), lp(0, 1, 1f))
        head.addView(FrameLayout(this).apply {
            background = oval(s2); isClickable = true; isFocusable = true
            contentDescription = if (paired) "프로필, 폰과 연결됨" else "프로필, 폰과 연결하기"
            setOnClickListener { profileOpen = true; render() }
            addView(ImageView(context).apply { setImageResource(R.drawable.ic_profile); imageTintList = ColorStateList.valueOf(tx) }, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
            // Not connected yet: a dot asks for attention, like an unread badge.
            if (!paired) addView(View(context).apply { background = oval(Color.parseColor("#E5484D")) }, FrameLayout.LayoutParams(dp(12), dp(12), Gravity.TOP or Gravity.END))
        }, lp(dp(52), dp(52)).apply { marginEnd = dp(12) })
        head.addView(mark(64, inv, onInv, talking = speakingNow).apply {
            contentDescription = "차곡에게 듣기"
            setOnClickListener { speakingNow = true; render(); say("오늘은 ${ro(draft.destination)} 떠나는 날이에요. 준비되면 출발하자고 말해 주세요.") }
        }, lp(dp(64), dp(64)))
        v.addView(head, lp(MATCH, WRAP).apply { bottomMargin = dp(16) })
        v.addView(when {
            shown == TAB_FIND -> findTab()
            shown == TAB_RECORD -> recordTab(arrivedNow)
            showTrips -> tripsTab()
            else -> planTab()
        }, lp(MATCH, 0, 1f))
        return v
    }

    // 오늘 일정: trip photo with the greeting + the stops card + 코스 바꾸기 / 출발하기
    private fun planTab(): View {
        val who = draft.companions.toList()
        val hero = photo(tripPhoto(), 24,
            top = listOf(tag(monthDay(draft.departure), glass = true), tag(if (who.isEmpty() || who == listOf("혼자")) "혼자 떠나요" else "${wa(who.joinToString(", "))} 함께", glass = true)),
            bottom = label("오늘은 ${ro(draft.destination)}\n떠나는 날이에요", if (wide) 28 else 24, Color.WHITE, 800, spacing = 1.25f))
        val card = column(bg = rounded(s1, 24)).apply { setPadding(dp(20), dp(12), dp(20), dp(12)) }
        card.addView(row().apply {
            addView(label("${draft.destination} 여행 일정", 17, tx, 700), lp(0, WRAP, 1f))
            totalTime()?.let { addView(tag("총 $it"), lp(WRAP, WRAP).apply { marginEnd = dp(8) }) }
            addView(tag(draft.mood))
        }, lp(MATCH, WRAP).apply { bottomMargin = dp(4) })
        card.addView(stopRow("지금", "여기서 출발", route.firstOrNull()?.let(::eta)?.let { "$it 뒤 첫 장소에 도착해요" } ?: "", null, first = true, last = route.isEmpty()))
        route.forEachIndexed { i, p -> card.addView(stopRow(p.time, p.name, "${p.duration} 머물러요", photoOf(p.name), first = false, last = i == route.lastIndex)) }
        card.addView(flow(listOf(label(nm("${route.size}곳 들러요"), 15, tx, 600)) + draft.interests.map { tag(it) }), lp(MATCH, WRAP).apply { topMargin = dp(10) })
        val cta = row().apply {
            addView(button("코스 바꾸기", s2, tx) { showTrips = true; render() }, lp(WRAP, dp(52)))
            addView(button("출발하기", inv, onInv) { startTrip() }, lp(0, dp(52), 1f).apply { marginStart = dp(12) })
        }
        return split(hero, listOf(scroll(card) to 1f), cta, heroTall = 150)
    }

    // 코스 바꾸기: the driver's trips as photo cards
    private fun tripsTab(): View {
        val v = column()
        val list = if (wide) row() else column()
        trips.forEachIndexed { i, t ->
            val sel = t.tripId == plan.tripId
            val card = (if (wide) column() else row()).apply {
                background = rounded(s1, 24); clipToOutline = true; isClickable = true
                if (sel) foreground = rounded(Color.TRANSPARENT, 24, stroke = tx, strokeDp = 3)
                setOnClickListener { if (!sel) { selectTrip(t); render() } }
            }
            val pic = photo(photoOf(t.draft.destination), 0, top = listOf(tag("경유지 ${t.places.size}곳", glass = true)), bottom = label(t.draft.destination, 22, Color.WHITE, 750))
            val info = column().apply { if (wide) setPadding(dp(18), dp(16), dp(18), dp(18)) else setPadding(dp(16), dp(10), dp(12), dp(6)); gravity = Gravity.CENTER_VERTICAL }
            info.addView(label(nm("${monthDay(t.draft.departure)}, ${wa(t.draft.companions.firstOrNull() ?: "혼자")}, ${t.draft.mood}"), 13, mu, single = true), lp(MATCH, WRAP).apply { bottomMargin = dp(if (wide) 8 else 4) })
            t.places.forEach { p -> info.addView(row().apply {
                addView(View(context).apply { background = oval(mu) }, lp(dp(6), dp(6)).apply { marginEnd = dp(8) })
                addView(label(nm("${p.time}  ${p.name}"), 14, tx, 600, single = true, spacing = 1.2f), lp(0, WRAP, 1f))
            }, lp(MATCH, WRAP).apply { bottomMargin = dp(if (wide) 8 else 2) }) }
            if (wide) { card.addView(pic, lp(MATCH, dp(170))); card.addView(info, lp(MATCH, WRAP)) }
            else { card.addView(pic, lp(dp(130), MATCH)); card.addView(info, lp(0, WRAP, 1f)) }
            list.addView(card, if (wide) lp(0, MATCH, 1f).apply { if (i > 0) marginStart = dp(16) } else lp(MATCH, 0, 1f).apply { if (i > 0) topMargin = dp(12) })
        }
        v.addView(list, lp(MATCH, 0, 1f))
        v.addView(row().apply {
            addView(button("닫기", inv, onInv) { showTrips = false; render() }, lp(WRAP, dp(52)))
            addView(button("${draft.destination} 코스로 출발하기", inv, onInv) { startTrip() }, lp(0, dp(52), 1f).apply { marginStart = dp(12) })
        }, lp(MATCH, WRAP).apply { topMargin = dp(16) })
        return v
    }

    // 발견: nearby places to add before departure
    private fun findTab(): View {
        val items = findsFor(draft.destination)
        if (items.isEmpty()) return centered(label("가는 길에 들를 만한 곳을 찾으면 여기 보여 드려요", 15, mu, 600))
        // Our full slot (~840dp) is about the design's half pane, which shows the finds 2 × 2.
        return grid(items.map { f ->
            val added = route.any { it.id == f.id }
            column(bg = rounded(s1, 24)).apply {
                clipToOutline = true
                addView(photo(photoOf(f.name), 0, top = listOf(tag(f.reason, glass = true))), lp(MATCH, 0, 1f))
                val add = button(if (added) "추가됨" else "추가", if (added) inv else s2, if (added) onInv else tx, compact = true) { addFromFind(f) }
                val info = column().apply {
                    addView(label(f.name, 17, tx, 700, single = true))
                    addView(label(nm(listOf(f.distance, f.open).filter(String::isNotBlank).joinToString(", ")), 13, mu, single = true))
                }
                // Design: name and 추가 on one row; the half window is too narrow for that, so 추가 goes under the name.
                addView(if (wide) row().apply {
                    setPadding(dp(16), dp(12), dp(12), dp(12))
                    addView(info, lp(0, WRAP, 1f)); addView(add, lp(WRAP, dp(44)).apply { marginStart = dp(8) })
                } else column().apply {
                    setPadding(dp(14), dp(10), dp(14), dp(12))
                    addView(info, lp(MATCH, WRAP)); addView(add, lp(WRAP, dp(40)).apply { topMargin = dp(8) })
                }, lp(MATCH, WRAP))
            }
        }, 2)
    }

    // 기록 (and the arrival summary): route strip, counts, places kept on the way
    private fun recordTab(arrivedNow: Boolean): View {
        val hero = photo(arrivePhoto(), 24, bottom = label(if (arrivedNow) "${draft.destination}에\n도착했어요" else "오늘의\n기록", 28, Color.WHITE, 800, spacing = 1.3f))
        val strip = column(bg = rounded(s1, 24)).apply { setPadding(dp(12), dp(20), dp(12), dp(16)); addView(routeStrip(listOf("출발") + route.map(Place::name))) }
        val (distance, duration) = arrived ?: (null to null)
        val stats = if (arrivedNow) listOf((distance?.let(::km) ?: "—") to "이동 거리", (duration?.let(::minutes) ?: "—") to "걸린 시간", "${route.size}곳" to "들른 장소", "${linesHeard}개" to "들은 이야기")
            else listOf("${plan.places.size}곳" to "계획한 장소", "${savedPlaces.size}곳" to "담은 곳", "${navi?.waypoints ?: 0}/2" to "경유지", "${linesHeard}개" to "들은 이야기")
        val statGrid = grid(stats.map { (v, k) -> stat(v, k, s1, valueSp = if (wide) 28 else 22) }, 4, gap = if (wide) 12 else 8, fill = false)
        val kept = (route + trips.flatMap { it.places } + findsFor(draft.destination) + listOfNotNull(current?.place)).filter { it.id in savedPlaces }.distinctBy { it.id }
        val saved = if (kept.isEmpty()) centered(label("가는 길에 담은 곳이 여기 모여요", 15, mu, 600)).apply { background = rounded(s1, 24) }
            else grid(kept.take(2).map { photo(photoOf(it.name), 16, top = listOf(tag("담은 곳", glass = true)), bottom = label(it.name, 17, Color.WHITE, 700, single = true), small = true) }, 2)
        val cta = row().apply {
            if (arrivedNow) {
                addView(button("처음으로", s2, tx) { backToStart() }, lp(WRAP, dp(52)).apply { marginEnd = dp(12) })
                addView(button("영상일기 남기기", inv, onInv, icon = R.drawable.ic_play) { diaryOpen = true; diaryMemo = null; render() }, lp(0, dp(52), 1f))
            } else addView(button("폰에서 기록 이어가기", inv, onInv) {
                if (paired) toast("폰의 차곡 기록에서 사진과 메모를 이어서 남길 수 있어요") else { profileOpen = true; render() }
            }, lp(0, dp(52), 1f))
        }
        return if (wide) split(hero, listOf(strip to 0f, statGrid to 0f, saved to 1f), cta, heroTall = 0)
        else column().apply {
            addView(scroll(column().apply {
                addView(hero, lp(MATCH, dp(120)).apply { bottomMargin = dp(16) })
                addView(strip, lp(MATCH, WRAP).apply { bottomMargin = dp(16) })
                addView(statGrid, lp(MATCH, WRAP).apply { bottomMargin = dp(16) })
                addView(saved, lp(MATCH, dp(110)))
            }), lp(MATCH, 0, 1f))
            addView(cta, lp(MATCH, WRAP).apply { topMargin = dp(16) })
        }
    }

    /** Live: the place photo fills the panel, the line in large white type, the voice mark and the place chip. */
    private fun liveView(): View {
        val place = current?.place
        val next = route.getOrNull(visited)
        val frame = FrameLayout(this)
        frame.addView(slideshow(place?.name ?: next?.name ?: draft.destination), FrameLayout.LayoutParams(MATCH, MATCH))
        frame.addView(View(this).apply { background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(shade(.5f), shade(.2f), shade(.62f), shade(.95f))) }, FrameLayout.LayoutParams(MATCH, MATCH))
        val v = column(pad = 24)
        val top = row().apply { gravity = Gravity.TOP }
        if (next != null) top.addView(row(bg = rounded(s1, 999)).apply {
            setPadding(dp(8), 0, dp(24), 0)
            addView(FrameLayout(context).apply {
                background = oval(inv)
                addView(ImageView(context).apply { setImageResource(R.drawable.ic_nav); imageTintList = ColorStateList.valueOf(onInv) }, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
            }, lp(dp(40), dp(40)).apply { marginEnd = dp(12) })
            addView(label("다음", 15, mu, 600), lp(WRAP, WRAP).apply { marginEnd = dp(12) })
            addView(label(next.name, 17, tx, 700, single = true), lp(WRAP, WRAP).apply { marginEnd = dp(12) })
            addView(label(nm(nextEta ?: eta(next) ?: ""), 17, mu, 700, num = true))
        }, lp(WRAP, dp(56)))
        top.addView(View(this), lp(0, 1, 1f))
        val rest = route.drop(visited + 1)
        if (wide && rest.isNotEmpty()) top.addView(column(bg = rounded(s1, 24)).apply {
            setPadding(dp(16), dp(16), dp(16), dp(4))
            rest.forEach { p -> addView(row().apply {
                addView(photo(photoOf(p.name), 12, small = true), lp(dp(40), dp(40)).apply { marginEnd = dp(12) })
                addView(label(p.name, 15, tx, 600, single = true), lp(0, WRAP, 1f))
                addView(label(nm(p.time.ifBlank { eta(p) ?: "" }), 15, mu, 600, num = true))
            }, lp(MATCH, WRAP).apply { bottomMargin = dp(12) }) }
        }, lp(dp(300), WRAP))
        v.addView(top, lp(MATCH, WRAP))
        v.addView(View(this), lp(MATCH, 0, 1f))
        v.addView(row().apply {
            addView(mark(88, if (held != null) s3 else Color.WHITE, Color.parseColor("#101317"), talking = speakingNow).apply {
                contentDescription = "차곡, 말하는 중이면 눌러서 멈추기"
                setOnClickListener { held = null; if (speakingNow) { tts?.stop(); speakingNow = false; render() } }
            }, lp(dp(88), dp(88)).apply { marginEnd = dp(16) })
            addView(label(if (speakingNow) "누르면 멈춰요" else if (held != null) "통화가 끝나면 이어서 읽어요" else "", 15, Color.WHITE, 600).apply { alpha = .8f })
        }, lp(MATCH, WRAP))
        val line = label(current?.text.orEmpty(), if (wide) 30 else 26, Color.WHITE, 700, spacing = 1.5f).apply {
            maxWidth = dp(if (wide) 850 else 600); breakStrategy = android.text.Layout.BREAK_STRATEGY_BALANCED
            val target = if (current != null && !speakingNow && held == null) .72f else 1f // dimmed once said, not while held
            if (freshLine) { freshLine = false; alpha = 0f; translationY = dp(9).toFloat(); animate().alpha(target).translationY(0f).setDuration(450).start() } else alpha = target
        }
        v.addView(line, lp(WRAP, WRAP).apply { topMargin = dp(24) })
        if (place != null) {
            val kept = place.id in savedPlaces
            val ink = if (kept) Color.parseColor("#101317") else tx
            val label = SpannableStringBuilder(place.name).append(nm(if (kept) "  담았어요" else place.distance.takeIf { it.isNotBlank() }?.let { "  $it" } ?: "",
                ink, base = if (kept) Color.parseColor("#99101317") else mu))
            v.addView(button(label, if (kept) Color.WHITE else s1, if (kept) Color.parseColor("#101317") else tx, icon = if (kept) R.drawable.ic_check else R.drawable.ic_pin) { openSheet(place) },
                lp(WRAP, dp(52)).apply { topMargin = dp(24) })
        }
        frame.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
        return frame
    }

    /** "○○, 들렀다 갈까요?": photo, why, 추가 시간 · 방문 가능 · 내비 경유지, 담아 두기 / 경로에 추가. */
    private fun sheetView(place: Place): View {
        val wrap = FrameLayout(this).apply { setBackgroundColor(shade(.6f)); isClickable = true; setOnClickListener { sheetPlace = null; render() } }
        val sheet = column(bg = GradientDrawable().apply { setColor(s1); cornerRadii = FloatArray(8) { if (it < 4) dp(24).toFloat() else 0f } }).apply {
            isClickable = true; setPadding(dp(24), dp(12), dp(24), dp(24))
        }
        sheet.addView(View(this).apply { background = rounded(s3, 999) }, lp(dp(40), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(16) })
        val pic = photo(photoOf(place.name), 24, top = listOf(tag("담아 뒀어요", glass = true, icon = R.drawable.ic_check)), bottom = label("${place.name}, 들렀다 갈까요?", 22, Color.WHITE, 750))
        val slots = navi?.waypoints ?: 0
        val open = place.open.ifBlank { "확인 필요" }
        val stats = grid(listOf(stat(detour(place), "추가 시간", s2), stat(open, "방문 가능", s2, valueSp = if (open.length > 4) 17 else 28), stat("$slots/2", if (slots >= 2) "경유지 가득 참" else "내비 경유지", s2)), 3, gap = 8, fill = false)
        val cta = row().apply {
            addView(button("담아 두기", s2, tx, icon = R.drawable.ic_bookmark) { decide(approve = false) }, lp(WRAP, dp(52)))
            addView(button("경로에 추가", inv, onInv) { decide(approve = true) }, lp(0, dp(52), 1f).apply { marginStart = dp(12) })
        }
        val side = column().apply {
            place.reason.takeIf { it.isNotBlank() }?.let { addView(tag(it), lp(WRAP, WRAP).apply { bottomMargin = dp(16) }) }
            addView(stats, lp(MATCH, if (wide) 0 else WRAP, if (wide) 1f else 0f).apply { bottomMargin = dp(16) })
            addView(cta, lp(MATCH, WRAP))
        }
        if (wide) sheet.addView(row().apply {
            gravity = Gravity.FILL_VERTICAL
            addView(pic, lp(0, dp(180), .38f).apply { marginEnd = dp(16) })
            addView(side, lp(0, MATCH, .62f))
        }, lp(MATCH, WRAP))
        else { sheet.addView(pic, lp(MATCH, dp(150)).apply { bottomMargin = dp(16) }); sheet.addView(side, lp(MATCH, WRAP)) }
        wrap.addView(sheet, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        return wrap
    }

    /** 프로필 (parked only): whether this car is linked to a phone's Chagok account, and the way to link / unlink it. */
    private fun profileView(): View {
        val wrap = FrameLayout(this).apply { setBackgroundColor(shade(.6f)); isClickable = true; setOnClickListener { profileOpen = false; render() } }
        val sheet = column(bg = GradientDrawable().apply { setColor(s1); cornerRadii = FloatArray(8) { if (it < 4) dp(24).toFloat() else 0f } }).apply {
            isClickable = true; setPadding(dp(24), dp(12), dp(24), dp(24))
        }
        sheet.addView(View(this).apply { background = rounded(s3, 999) }, lp(dp(40), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(16) })
        sheet.addView(row().apply {
            addView(FrameLayout(context).apply {
                background = oval(if (paired) inv else s2)
                addView(ImageView(context).apply { setImageResource(R.drawable.ic_profile); imageTintList = ColorStateList.valueOf(if (paired) onInv else tx) }, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER))
            }, lp(dp(64), dp(64)).apply { marginEnd = dp(16) })
            addView(column().apply {
                addView(label("프로필", 22, tx, 750))
                addView(label(if (paired) "폰의 차곡 계정과 연결됐어요" else "아직 폰과 연결하지 않았어요", 15, mu, 600))
            }, lp(0, WRAP, 1f))
        }, lp(MATCH, WRAP).apply { bottomMargin = dp(20) })
        val status = column(bg = rounded(s2, 20)).apply { setPadding(dp(20), dp(16), dp(20), dp(16)) }
        listOf(
            "폰에서 보낸 여행" to if (paired) "오늘 일정에 바로 떠요" else "연결하면 받을 수 있어요",
            "주행 기록" to if (paired) "폰의 기록에 쌓여요" else "이 차에만 남아요",
        ).forEachIndexed { i, (k, v) -> status.addView(row().apply {
            addView(label(k, 15, mu, 600), lp(0, WRAP, 1f)); addView(label(v, 15, tx, 700))
        }, lp(MATCH, WRAP).apply { if (i > 0) topMargin = dp(10) }) }
        sheet.addView(status, lp(MATCH, WRAP).apply { bottomMargin = dp(20) })
        sheet.addView(row().apply {
            addView(button("닫기", s2, tx) { profileOpen = false; render() }, lp(WRAP, dp(52)))
            addView(if (paired) button("연결 끊기", s2, tx) { unpair("폰과 연결을 끊었어요") } else button("폰과 연결하기", inv, onInv) { pairing = ""; render() },
                lp(0, dp(52), 1f).apply { marginStart = dp(12) })
        }, lp(MATCH, WRAP))
        wrap.addView(sheet, FrameLayout.LayoutParams(if (wide) dp(560) else MATCH, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        return wrap
    }

    /** 폰과 연결하기 (parked only): the 8-digit code from the phone (MY → 차량 연결) on a big number pad. */
    private fun pairView(digits: String): View {
        val wrap = FrameLayout(this).apply { setBackgroundColor(shade(.6f)); isClickable = true; setOnClickListener { pairing = null; render() } }
        val sheet = column(bg = GradientDrawable().apply { setColor(s1); cornerRadii = FloatArray(8) { if (it < 4) dp(24).toFloat() else 0f } }).apply {
            isClickable = true; setPadding(dp(24), dp(12), dp(24), dp(24))
        }
        sheet.addView(View(this).apply { background = rounded(s3, 999) }, lp(dp(40), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(16) })
        sheet.addView(label("폰과 연결하기", 22, tx, 750))
        sheet.addView(label("폰의 차곡 앱 MY → 차량 연결에 나온 8자리 번호를 넣어 주세요", 15, mu, 600), lp(MATCH, WRAP).apply { topMargin = dp(4); bottomMargin = dp(16) })
        val shown = digits.padEnd(8, '·').let { "${it.take(4)}  ${it.drop(4)}" }
        sheet.addView(label(shown, 34, tx, 700, num = true).apply { gravity = Gravity.CENTER; letterSpacing = .15f; background = rounded(s2, 16) }, lp(MATCH, dp(64)).apply { bottomMargin = dp(16) })
        fun key(text: String, fill: Int = s2, fg: Int = tx, onClick: () -> Unit) = button(text, fill, fg, compact = true, onClick = onClick).apply { minHeight = dp(56) }
        val keys = (1..9).map { n -> key("$n") { if (digits.length < 8) { pairing = digits + n; render() } } } + listOf(
            key("지우기") { pairing = digits.dropLast(1); render() },
            key("0") { if (digits.length < 8) { pairing = digits + "0"; render() } },
            key("연결", if (digits.length == 8) inv else s3, if (digits.length == 8) onInv else mu) { if (digits.length == 8) { toast("연결하는 중…"); claim(digits) } })
        sheet.addView(grid(keys, 3, gap = 8, fill = false), lp(MATCH, WRAP))
        wrap.addView(sheet, FrameLayout.LayoutParams(if (wide) dp(560) else MATCH, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        return wrap
    }

    private fun toastView(t: String) = TextView(this).apply {
        text = t; textSize = 15f; setTextColor(onInv); typeface = weight(600); gravity = Gravity.CENTER
        background = rounded(inv, 999); minHeight = dp(52); setPadding(dp(24), dp(12), dp(24), dp(12)); maxWidth = dp(560)
        setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_check, 0, 0, 0); compoundDrawablePadding = dp(10)
        compoundDrawableTintList = ColorStateList.valueOf(onInv)
    }

    // ── design v4 building blocks ──
    private fun weight(w: Int): Typeface = Typeface.create(resources.getFont(R.font.suit), w, false) // SUIT (차곡 디자인 v4)
    private fun label(value: CharSequence, sp: Int, color: Int, w: Int = 400, single: Boolean = false, num: Boolean = false, spacing: Float = 1.4f) = TextView(this).apply {
        text = value; textSize = sp.toFloat(); setTextColor(color); typeface = weight(w); setLineSpacing(0f, spacing)
        if (single) { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        if (num) fontFeatureSettings = "tnum"
        if (Build.VERSION.SDK_INT >= 33) lineBreakWordStyle = android.graphics.text.LineBreakConfig.LINE_BREAK_WORD_STYLE_PHRASE
    }
    private fun rounded(fill: Int, radiusDp: Int, stroke: Int? = null, strokeDp: Int = 0) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(radiusDp).toFloat(); stroke?.let { setStroke(dp(strokeDp), it) }
    }
    private fun oval(fill: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(fill) }
    private fun shade(a: Float) = Color.argb((a * 255).toInt(), 7, 9, 12)
    private fun column(pad: Int = 0, bg: GradientDrawable? = null) = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(pad), dp(pad), dp(pad), dp(pad)); background = bg }
    private fun row(bg: GradientDrawable? = null) = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = bg }
    private fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)
    // Focusable itself so a focusable child doesn't scroll the view on first layout.
    private fun scroll(child: View) = ScrollView(this).apply {
        isVerticalScrollBarEnabled = false; isFillViewport = true; isFocusableInTouchMode = true; descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS; addView(child)
    }
    private fun centered(child: View) = FrameLayout(this).apply { addView(child, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER)) }

    /** Full pill button (52dp, 16sp bold); [compact] drops the 112dp minimum for buttons inside cards. */
    private fun button(label: CharSequence, fill: Int, fg: Int, compact: Boolean = false, icon: Int = 0, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 16f; setTextColor(fg); typeface = weight(700); gravity = Gravity.CENTER; maxLines = 1
        background = rounded(fill, 999); setPadding(dp(if (compact) 18 else 26), 0, dp(if (compact) 18 else 26), 0)
        if (!compact) minWidth = dp(112)
        if (icon != 0) { setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0); compoundDrawablePadding = dp(8); compoundDrawableTintList = ColorStateList.valueOf(fg) }
        isClickable = true; isFocusable = true; setOnClickListener { onClick() }
    }
    private fun tag(value: String, glass: Boolean = false, icon: Int = 0) = TextView(this).apply {
        text = nm(value, if (glass) Color.WHITE else tx); textSize = 13f; typeface = weight(600); gravity = Gravity.CENTER_VERTICAL; maxLines = 1
        setTextColor(if (glass) Color.WHITE else mu); background = rounded(if (glass) Color.argb(122, 10, 12, 16) else s2, 999)
        setPadding(dp(12), 0, dp(12), 0); minHeight = dp(30); height = dp(30)
        if (icon != 0) { setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0); compoundDrawablePadding = dp(6); compoundDrawableTintList = ColorStateList.valueOf(currentTextColor) }
    }
    private fun segmented(items: List<Pair<String, String>>, on: String, pick: (String) -> Unit) = row(bg = rounded(s3, 999)).apply {
        setPadding(dp(4), dp(4), dp(4), dp(4))
        items.forEach { (key, label) -> addView(TextView(context).apply {
            text = label; textSize = 15f; typeface = weight(600); gravity = Gravity.CENTER
            dp(if (wide) 20 else 14).let { setPadding(it, 0, it, 0) } // half window: a little tighter so the logo fits
            setTextColor(if (key == on) tx else mu); background = if (key == on) rounded(s1, 999) else null
            isClickable = true; setOnClickListener { pick(key) }
        }, lp(WRAP, dp(40))) }
    }
    private fun mark(sizeDp: Int, circle: Int, bar: Int, talking: Boolean) = VoiceMarkView(this, circle, bar).apply { this.talking = talking; isClickable = true; isFocusable = true }
    private fun stat(value: String, label: String, fill: Int, valueSp: Int = 28) = column(bg = rounded(fill, 24)).apply {
        gravity = Gravity.BOTTOM; setPadding(dp(18), dp(16), dp(18), dp(16))
        // Numbers stand out (design: bigger, 800). Big values stay on one line and shrink to fit a narrow cell (+12분 in the half window).
        addView(label(value, if (valueSp >= 22) valueSp + 4 else valueSp, tx, 800, num = true, spacing = 1.15f).apply {
            if (valueSp >= 22) { maxLines = 1; setAutoSizeTextTypeUniformWithConfiguration(16, valueSp + 4, 1, android.util.TypedValue.COMPLEX_UNIT_SP) }
        }, lp(MATCH, WRAP))
        addView(label(label, 13, mu, 600), lp(WRAP, WRAP).apply { topMargin = dp(4) })
    }

    /** Photo with the design's scrim: tags on top, a title at the bottom ([small] = thumbnail corners and insets). */
    private fun photo(res: Int, radiusDp: Int, top: List<View> = emptyList(), bottom: View? = null, small: Boolean = false) = FrameLayout(this).apply {
        background = rounded(s3, radiusDp); clipToOutline = true
        addView(ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setImageResource(res) }, FrameLayout.LayoutParams(MATCH, MATCH))
        if (top.isNotEmpty() || bottom != null) addView(View(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(shade(.35f), shade(0f), shade(0f), shade(.82f)))
        }, FrameLayout.LayoutParams(MATCH, MATCH))
        val inset = dp(if (small) 12 else 16)
        if (top.isNotEmpty()) addView(row().apply { top.forEachIndexed { i, t -> addView(t, lp(WRAP, dp(30)).apply { if (i > 0) marginStart = dp(6) }) } },
            FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP).apply { setMargins(inset, inset, inset, 0) })
        bottom?.let { addView(it, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply { val b = dp(if (small) 12 else 20); setMargins(b, 0, b, b) }) }
    }

    /** Hero beside the content on the full slot (design), above it in the half window. */
    private fun split(hero: View, parts: List<Pair<View, Float>>, cta: View, heroTall: Int): View {
        val side = column()
        parts.forEachIndexed { i, (view, w) -> side.addView(view, lp(MATCH, if (w > 0f) 0 else WRAP, w).apply { if (i > 0) topMargin = dp(16) }) }
        side.addView(cta, lp(MATCH, WRAP).apply { topMargin = dp(16) })
        return if (wide) row().apply {
            gravity = Gravity.FILL_VERTICAL
            addView(hero, lp(dp(if (heroTall == 0) 300 else 320), MATCH).apply { marginEnd = dp(16) })
            addView(side, lp(0, MATCH, 1f))
        } else column().apply {
            addView(hero, lp(MATCH, dp(heroTall)).apply { bottomMargin = dp(16) })
            addView(side, lp(MATCH, 0, 1f))
        }
    }

    /** Equal-width cells, [cols] per row; [fill] stretches the rows to share the height. */
    private fun grid(cells: List<View>, cols: Int, gap: Int = 12, fill: Boolean = true) = column().apply {
        cells.chunked(cols).forEachIndexed { r, chunk ->
            addView(row().apply {
                gravity = Gravity.FILL_VERTICAL
                chunk.forEachIndexed { i, c -> addView(c, lp(0, MATCH, 1f).apply { if (i > 0) marginStart = dp(gap) }) }
                repeat(cols - chunk.size) { addView(View(context), lp(0, MATCH, 1f).apply { marginStart = dp(gap) }) }
            }, lp(MATCH, if (fill) 0 else WRAP, if (fill) 1f else 0f).apply { if (r > 0) topMargin = dp(gap) })
        }
    }

    /** Wrapping row of small views (tags after the count). */
    private fun flow(items: List<View>) = row().apply {
        // ponytail: one line; the design wraps tags, three short interests fit the panel width.
        items.forEachIndexed { i, v -> addView(v, lp(WRAP, WRAP).apply { if (i > 0) marginStart = dp(8) }) }
    }

    /** One stop of today's plan: time · rail with a ring (first stop filled) · thumbnail · name and stay. */
    private fun stopRow(time: String, name: String, sub: String, thumb: Int?, first: Boolean, last: Boolean) = row().apply {
        minimumHeight = dp(if (wide) 76 else 56)
        addView(label(time, 16, tx, 800, num = true), lp(dp(56), WRAP).apply { marginEnd = dp(14) })
        addView(FrameLayout(context).apply {
            addView(column().apply {
                addView(View(context).apply { setBackgroundColor(if (first) Color.TRANSPARENT else s3) }, lp(dp(2), 0, 1f))
                addView(View(context).apply { setBackgroundColor(if (last) Color.TRANSPARENT else s3) }, lp(dp(2), 0, 1f))
            }, FrameLayout.LayoutParams(dp(2), MATCH, Gravity.CENTER_HORIZONTAL))
            addView(View(context).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (first) tx else s1); setStroke(dp(3), tx) } },
                FrameLayout.LayoutParams(dp(14), dp(14), Gravity.CENTER))
        }, lp(dp(16), MATCH).apply { marginEnd = dp(14) })
        if (thumb != null) addView(photo(thumb, 16), lp(dp(if (wide) 52 else 44), dp(if (wide) 52 else 44)).apply { marginEnd = dp(14) })
        addView(column().apply {
            addView(label(name, 17, tx, 700, single = true))
            if (sub.isNotBlank()) addView(label(nm(sub), 13, mu, single = true))
        }, lp(0, WRAP, 1f))
    }

    /** 출발 → … → destination as dots on one line (design `.route`). */
    private fun routeStrip(names: List<String>) = FrameLayout(this).apply {
        addView(View(context).apply { background = rounded(inv, 999) }, FrameLayout.LayoutParams(MATCH, dp(3)).apply { setMargins(dp(24), dp(7), dp(24), 0) })
        addView(row().apply {
            gravity = Gravity.TOP
            names.forEachIndexed { i, n -> addView(column().apply {
                gravity = Gravity.CENTER_HORIZONTAL
                addView(View(context).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (i == names.lastIndex) inv else s1); setStroke(dp(4), inv) } }, lp(dp(17), dp(17)))
                addView(label(n, 13, tx, 700, single = true).apply { gravity = Gravity.CENTER }, lp(MATCH, WRAP).apply { topMargin = dp(10) })
            }, lp(0, WRAP, 1f)) }
        }, FrameLayout.LayoutParams(MATCH, WRAP))
    }

    /**
     * Numbers stand out (design): times, distances, counts — 8분, 1시간, 22시, 2곳, 11:00, 1/2, 3.2 km — 18% larger, weight 800,
     * in [ink] (the strongest text colour; white on photos). [base] colours the rest of the text when it differs from the view's.
     */
    private fun nm(text: String, ink: Int = tx, base: Int? = null): CharSequence = SpannableStringBuilder(text).apply {
        base?.let { setSpan(ForegroundColorSpan(it), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        NUMBER.findAll(text).forEach { m ->
            val (a, b) = m.range.first to m.range.last + 1
            setSpan(RelativeSizeSpan(1.18f), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(TypefaceSpan(weight(800)), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(ink), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /** "1시간 20분" / "40분" → minutes. */
    private fun mins(v: String) = Regex("""(?:(\d+)\s*시간)?\s*(?:(\d+)\s*분)?""").find(v)?.let { (it.groupValues[1].toIntOrNull() ?: 0) * 60 + (it.groupValues[2].toIntOrNull() ?: 0) } ?: 0
    /** Today's course: the drive to each stop + the time spent there (sample estimates; the navi has the real drive). */
    private fun totalTime(): String? = route.sumOf { mins(eta(it) ?: "") + mins(it.duration) }.takeIf { it > 0 }
        ?.let { if (it >= 60) "${it / 60}시간" + (if (it % 60 > 0) " ${it % 60}분" else "") else "${it}분" }

    /**
     * The place photos crossfading slowly (8 s each, 1.4 s fade) behind the live line and the diary preview. The slide follows
     * the clock since the subject changed, so a re-render shows the same photo instead of starting over. Static when the system
     * turns animations off. ponytail: the design's slight zoom is left out — every render would restart it.
     */
    private fun slideshow(subject: String, pics: List<Int> = galleryOf(subject)): FrameLayout {
        if (slideFor != subject) { slideFor = subject; slideAt = SystemClock.uptimeMillis() }
        slidePics = pics
        val gone = SystemClock.uptimeMillis() - slideAt
        val back = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setImageResource(pics[(gone / SLIDE_MS).toInt() % pics.size]) }
        val front = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
        slideViews = back to front
        if (pics.size > 1 && ValueAnimator.areAnimatorsEnabled()) main.postDelayed(slideTick, SLIDE_MS - gone % SLIDE_MS)
        return FrameLayout(this).apply { addView(back, FrameLayout.LayoutParams(MATCH, MATCH)); addView(front, FrameLayout.LayoutParams(MATCH, MATCH)) }
    }
    private fun nextSlide() {
        val (back, front) = slideViews ?: return
        if (!back.isAttachedToWindow || slidePics.size < 2) return
        val pic = slidePics[((SystemClock.uptimeMillis() - slideAt) / SLIDE_MS).toInt() % slidePics.size]
        front.setImageResource(pic); front.alpha = 0f
        front.animate().alpha(1f).setDuration(1400).withEndAction { back.setImageResource(pic); front.alpha = 0f }.start()
        main.postDelayed(slideTick, SLIDE_MS)
    }

    /**
     * 영상일기 (after arrival, parked): the trip as a short film — preview of the photos with the last story line as a caption,
     * the chapters (출발 + stops + places kept), options, and 오늘의 한마디 in the driver's voice; editing/sharing on the phone.
     * ponytail: example UI from the design — nothing is recorded or sent yet; the buttons say so.
     */
    private fun diaryView(): View {
        val kept = (trips.flatMap { it.places } + findsFor(draft.destination) + listOfNotNull(current?.place)).filter { it.id in savedPlaces && route.none { r -> r.id == it.id } }.distinctBy { it.id }
        val chapters = listOf(Triple(startedAt ?: "출발", "출발", tripPhoto())) +
            route.map { Triple(it.time.ifBlank { "들른 곳" }, it.name, photoOf(it.name)) } + kept.map { Triple("담은 곳", it.name, photoOf(it.name)) }
        val seconds = 12 + 15 * chapters.size
        val v = column(pad = 24)
        v.addView(row().apply {
            addView(column().apply {
                addView(label("오늘의 영상일기", 22, tx, 750))
                addView(label(nm("${draft.destination} · ${monthDay(draft.departure)} · 장면 ${chapters.size}개 · 이야기 ${linesHeard}개"), 13, mu, 600, single = true))
            }, lp(0, WRAP, 1f))
            addView(label(String.format(Locale.KOREA, "%d:%02d", seconds / 60, seconds % 60), 30, tx, 800, num = true))
        }, lp(MATCH, WRAP).apply { bottomMargin = dp(16) })
        val preview = FrameLayout(this).apply {
            background = rounded(s3, 24); clipToOutline = true
            addView(slideshow("diary", chapters.map { it.third }.distinct()), FrameLayout.LayoutParams(MATCH, MATCH))
            addView(View(context).apply { background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(shade(.35f), shade(0f), shade(.3f), shade(.85f))) }, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(tag("미리보기 · 예시", glass = true), FrameLayout.LayoutParams(WRAP, dp(30), Gravity.TOP or Gravity.START).apply { setMargins(dp(16), dp(16), 0, 0) })
            addView(FrameLayout(context).apply {
                background = oval(Color.argb(235, 255, 255, 255))
                addView(ImageView(context).apply { setImageResource(R.drawable.ic_play); imageTintList = ColorStateList.valueOf(Color.parseColor("#101317")) }, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER).apply { leftMargin = dp(3) })
            }, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER).apply { bottomMargin = dp(24) })
            addView(column().apply {
                addView(label("“${current?.text ?: "${ro(draft.destination)} 가는 오늘의 길을 짧은 영상으로 묶었어요."}”", 15, Color.WHITE, 700, spacing = 1.35f).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
                addView(row().apply { chapters.indices.forEach { i -> addView(View(context).apply { background = rounded(if (i == 0) Color.WHITE else Color.argb(90, 255, 255, 255), 999) }, lp(0, dp(4), 1f).apply { if (i > 0) marginStart = dp(5) }) } },
                    lp(MATCH, WRAP).apply { topMargin = dp(12) })
            }, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply { setMargins(dp(20), 0, dp(20), dp(18)) })
        }
        val strip = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row().apply {
                gravity = Gravity.TOP
                chapters.forEachIndexed { i, (time, name, pic) -> addView(column().apply {
                    addView(photo(pic, 16, small = true), lp(dp(96), dp(64)))
                    addView(label(time, 15, tx, 800, num = true), lp(WRAP, WRAP).apply { topMargin = dp(6) })
                    addView(label(name, 13, mu, 600, single = true), lp(dp(96), WRAP))
                }, lp(WRAP, WRAP).apply { if (i > 0) marginStart = dp(12) }) }
            })
        }
        val memo = row(bg = rounded(s1, 20)).apply {
            setPadding(dp(16), dp(12), dp(16), dp(12))
            val recording = diaryMemo == -1
            addView(FrameLayout(context).apply {
                background = oval(if (recording) Color.parseColor("#E5484D") else inv); isClickable = !recording
                contentDescription = "오늘의 한마디 남기기"
                setOnClickListener {
                    diaryMemo = -1; render()
                    main.postDelayed({ if (diaryMemo == -1) { diaryMemo = 8; if (diaryOpen) render() } }, 3000) // example: no audio is captured
                }
                addView(ImageView(context).apply { setImageResource(R.drawable.ic_mic); imageTintList = ColorStateList.valueOf(if (recording) Color.WHITE else onInv) }, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
            }, lp(dp(48), dp(48)).apply { marginEnd = dp(14) })
            addView(column().apply {
                when (val m = diaryMemo) {
                    -1 -> { addView(label("듣고 있어요", 16, tx, 700)); addView(label("오늘 어땠는지 한마디 해 주세요", 13, mu, 600)) }
                    null -> { addView(label("오늘의 한마디 남기기", 16, tx, 700)); addView(label("영상 마지막에 내 목소리로 들어가요", 13, mu, 600)) }
                    else -> { addView(label(nm("마지막 장면에 ${m}초 한마디를 넣었어요"), 16, tx, 700)); addView(label("다시 누르면 새로 남겨요", 13, mu, 600)) }
                }
            }, lp(0, WRAP, 1f))
        }
        val options = flow(listOf(tag("이야기 자막", icon = R.drawable.ic_check), tag("배경음 잔잔한", icon = R.drawable.ic_check), tag("길 위의 소리")))
        val details = scroll(column().apply {
            addView(strip, lp(MATCH, WRAP).apply { bottomMargin = dp(14) })
            addView(options, lp(MATCH, WRAP).apply { bottomMargin = dp(14) })
            addView(memo, lp(MATCH, WRAP))
        })
        val cta = row().apply {
            addView(button("닫기", s2, tx) { diaryOpen = false; render() }, lp(WRAP, dp(52)))
            addView(button("폰으로 보내기", inv, onInv) { toast("예시 화면이에요. 영상일기를 폰으로 보내는 기능은 준비 중이에요") }, lp(0, dp(52), 1f).apply { marginStart = dp(12) })
        }
        v.addView(if (wide) row().apply {
            gravity = Gravity.FILL_VERTICAL
            addView(preview, lp(0, MATCH, .56f).apply { marginEnd = dp(16) })
            addView(column().apply { addView(details, lp(MATCH, 0, 1f)); addView(cta, lp(MATCH, WRAP).apply { topMargin = dp(16) }) }, lp(0, MATCH, .44f))
        } else column().apply {
            addView(preview, lp(MATCH, dp(170)).apply { bottomMargin = dp(14) })
            addView(details, lp(MATCH, 0, 1f))
            addView(cta, lp(MATCH, WRAP).apply { topMargin = dp(14) })
        }, lp(MATCH, 0, 1f))
        return v
    }

    // ── photos ── ponytail: sample photos by place name until places carry a photo URL (contract change in both repos).
    private fun photoOf(name: String) = PHOTOS[name] ?: FALLBACK[abs(name.hashCode()) % FALLBACK.size]
    private fun galleryOf(name: String) = GALLERY[name] ?: listOf(photoOf(name))
    private fun tripPhoto() = photoOf(draft.destination)
    private fun arrivePhoto() = ARRIVE[draft.destination] ?: tripPhoto()

    // ── helpers ──
    private fun detour(p: Place) = Regex("(\\d+)\\s*분\\s*추가").find(p.distance + " " + p.reason)?.let { "+${it.groupValues[1]}분" } ?: "확인 필요"
    /** "2 km · 6분" → "6분"; a bare value stays as is. */
    private fun eta(p: Place) = p.distance.substringAfterLast("·").trim().ifBlank { null }
    private fun minutes(s: Int) = (s / 60).let { if (it >= 60) "${it / 60}시간 ${it % 60}분" else "${it.coerceAtLeast(1)}분" }
    private fun km(m: Int) = if (m >= 1000) String.format(Locale.US, "%.1f km", m / 1000.0) else "$m m"
    private fun monthDay(date: String) = runCatching { LocalDate.parse(date).let { "${it.monthValue}월 ${it.dayOfMonth}일" } }.getOrDefault(date)
    /** Korean particle: 받침 → "으로" (but ㄹ → "로"), else "로". 속초로 / 강릉으로 / 서울로. */
    private fun ro(word: String): String { val j = jong(word); return word + if (j != null && j != 0 && j != 8) "으로" else "로" }
    /** 부모님과 / 친구와. */
    private fun wa(word: String): String { val j = jong(word); return word + if (j != null && j != 0) "과" else "와" }
    private fun jong(word: String): Int? = word.trim().lastOrNull()?.takeIf { it in '가'..'힣' }?.let { (it - '가') % 28 }

    override fun onSaveInstanceState(out: Bundle) {
        out.putString("plan", plan.toJson()); out.putString("spoken", current?.toJson()); out.putString("tab", tab)
        out.putStringArrayList("saved", ArrayList(savedPlaces)); out.putInt("visited", visited); out.putInt("heard", linesHeard)
        super.onSaveInstanceState(out)
    }
    override fun restore(state: Bundle) {
        super.restore(state)
        state.getString("plan")?.let { runCatching { TripPlan.fromJson(it) }.getOrNull() }?.let { p -> trips.removeAll { it.tripId == p.tripId }; trips.add(0, p); selectTrip(p) }
        current = state.getString("spoken")?.let { runCatching { SpeakCommand.fromJson(it) }.getOrNull() }
        tab = state.getString("tab") ?: TAB_PLAN
        savedPlaces.addAll(state.getStringArrayList("saved").orEmpty())
        visited = state.getInt("visited"); linesHeard = state.getInt("heard")
    }

    private companion object {
        const val HOTWORD = 1999 // MediaRecorder.AudioSource.HOTWORD (system API)
        const val RESUME_WITHIN_MS = 60_000L // a line cut off longer ago than this is skipped, not re-read
        const val POLL_MS = 5_000L // backend message queue (backend docs: every 5–10 s)
        const val STALE_MS = 120_000L // a queued line older than this is skipped
        const val SLIDE_MS = 8_000L // live slideshow: one photo this long
        val NUMBER = Regex("""\+?\d+(?:[:./]\d+)?(?:\s?(?:시간|분|초|km|m|곳|개|시|일|월|장))?""")
        const val ACTION_SPEAK = "io.chagok.app.pleos.SPEAK"
        const val ACTION_TRIP = "io.chagok.app.pleos.TRIP"
        const val ACTION_ARRIVE = "io.chagok.app.pleos.ARRIVE"
        const val TAB_PLAN = "plan"; const val TAB_FIND = "find"; const val TAB_RECORD = "record"
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        val PHOTOS = mapOf(
            "속초" to R.drawable.photo_harbor1, "강릉" to R.drawable.photo_gnbeach, "양양" to R.drawable.photo_surf,
            "아바이마을" to R.drawable.photo_harboralley, "영금정" to R.drawable.photo_pavilion, "영랑호" to R.drawable.photo_lakesun1,
            "갯배 선착장" to R.drawable.photo_harbor2, "속초등대전망대" to R.drawable.photo_lighthouse1, "청초호 둘레길" to R.drawable.photo_lakesun2,
            "속초중앙시장" to R.drawable.photo_market2, "안목 커피거리" to R.drawable.photo_cafe2, "초당 순두부 거리" to R.drawable.photo_tofu,
            "경포 호수 산책길" to R.drawable.photo_lakesun1, "경포대" to R.drawable.photo_waves, "오죽헌" to R.drawable.photo_rock,
            "주문진 수산시장" to R.drawable.photo_market2, "정동진" to R.drawable.photo_lighthouse1, "서피비치" to R.drawable.photo_surf,
            "낙산사" to R.drawable.photo_rock, "하조대" to R.drawable.photo_waves, "남애항" to R.drawable.photo_harbor2,
        )
        val ARRIVE = mapOf("속초" to R.drawable.photo_sokcho, "강릉" to R.drawable.photo_lakesun1, "양양" to R.drawable.photo_waves)
        /** Several photos per place for the live slideshow (samples until places carry photo URLs). */
        val GALLERY = mapOf(
            "청초호 둘레길" to listOf(R.drawable.photo_lakesun2, R.drawable.photo_lakesun1, R.drawable.photo_sokcho),
            "갯배 선착장" to listOf(R.drawable.photo_harbor2, R.drawable.photo_harbor1, R.drawable.photo_harboralley),
            "속초중앙시장" to listOf(R.drawable.photo_market2, R.drawable.photo_tofu, R.drawable.photo_harboralley),
            "속초등대전망대" to listOf(R.drawable.photo_lighthouse1, R.drawable.photo_waves, R.drawable.photo_rock),
            "아바이마을" to listOf(R.drawable.photo_harboralley, R.drawable.photo_harbor2, R.drawable.photo_market2),
            "영금정" to listOf(R.drawable.photo_pavilion, R.drawable.photo_waves, R.drawable.photo_rock),
            "영랑호" to listOf(R.drawable.photo_lakesun1, R.drawable.photo_lakesun2, R.drawable.photo_waves),
            "경포대" to listOf(R.drawable.photo_waves, R.drawable.photo_lakesun1, R.drawable.photo_gnbeach),
            "안목 커피거리" to listOf(R.drawable.photo_cafe2, R.drawable.photo_gnbeach, R.drawable.photo_waves),
        )
        val FALLBACK = listOf(R.drawable.photo_waves, R.drawable.photo_lakesun2, R.drawable.photo_harbor1, R.drawable.photo_gnbeach)

        /** ponytail: sample nearby places for 발견 until the backend recommends them along the route. */
        fun findsFor(dest: String): List<Place> {
            fun f(id: String, name: String, reason: String, extra: String, lat: Double, lng: Double, open: String) =
                Place(id, name, "", "", "가는 길 추천", "", reason, extra, lat, lng, open) // group: backend takes only 좋아요/재생목록/구독/공유/""
            return when (dest) {
                "속초" -> listOf(
                    f("find-gaetbae", "갯배 선착장", "가는 길 바로 옆", "2분 추가", 38.2047, 128.5947, "22시까지"),
                    f("find-lighthouse", "속초등대전망대", "바다 관심사와 맞아요", "7분 추가", 38.2125, 128.6000, "18시까지"),
                    f("find-cheongcho", "청초호 둘레길", "해 질 무렵 산책", "8분 추가", 38.1906, 128.5930, "항상 열림"),
                    f("find-market", "속초중앙시장", "맛집 관심사와 맞아요", "5분 추가", 38.2040, 128.5905, "20시까지"))
                "강릉" -> listOf(
                    f("find-gyeongpodae", "경포대", "지역 이야기와 맞아요", "6분 추가", 37.7953, 128.8964, "항상 열림"),
                    f("find-ojukheon", "오죽헌", "역사 이야기", "9분 추가", 37.7790, 128.8786, "18시까지"),
                    f("find-jumunjin", "주문진 수산시장", "맛집 관심사와 맞아요", "15분 추가", 37.8946, 128.8297, "21시까지"),
                    f("find-jeongdongjin", "정동진", "일출 명소", "25분 추가", 37.6908, 129.0345, "항상 열림"))
                "양양" -> listOf(
                    f("find-hajodae", "하조대", "바다 관심사와 맞아요", "6분 추가", 38.0234, 128.7255, "항상 열림"),
                    f("find-namae", "남애항", "카페 관심사와 맞아요", "10분 추가", 37.9449, 128.7883, "항상 열림"))
                else -> emptyList()
            }
        }

        /** ponytail: sample "my trips" for 코스 바꾸기 until the backend serves the driver's trips. */
        fun sampleTrips(): List<TripPlan> {
            fun p(id: String, name: String, time: String, stay: String, source: String, group: String, reason: String, dist: String, lat: Double, lng: Double, open: String) =
                Place(id, name, time, stay, source, group, reason, dist, lat, lng, open)
            return listOf(
                TripPlan("sample-gangneung", TripDraft(), listOf(
                    p("anmok", "안목 커피거리", "10:30", "60분", "좋아요한 쇼츠 2개", "좋아요", "부모님 · 잔잔한 분위기에 맞아요", "3.2 km · 8분", 37.7722, 128.9473, "영업 중 · 22시까지"),
                    p("chodang", "초당 순두부 거리", "12:30", "70분", "재생목록 ‘강릉 맛집’ 영상 1개", "재생목록", "맛집 관심사와 맞아요", "5.1 km · 12분", 37.7915, 128.9145, "영업 중 · 20시까지"),
                    p("gyeongpo", "경포 호수 산책길", "15:00", "50분", "구독 채널 영상 1개", "구독", "오후 산책 추천", "8.4 km · 18분", 37.7965, 128.9000, "항상 열려 있어요"))),
                TripPlan("sample-yangyang", TripDraft(destination = "양양", companions = linkedSetOf("친구"), interests = linkedSetOf("자연·바다", "카페"), mood = "활기찬"), listOf(
                    p("surfy", "서피비치", "11:00", "90분", "재생목록 ‘바다 여행’ 영상 3개", "재생목록", "바다 관심사와 맞아요", "12 km · 20분", 38.0270, 128.7148, "영업 중 · 18시까지"),
                    p("naksansa", "낙산사", "14:00", "60분", "좋아요한 쇼츠 1개", "좋아요", "바다가 보이는 절", "9 km · 15분", 38.1247, 128.6290, "18시까지 입장"))),
            )
        }
    }
}
