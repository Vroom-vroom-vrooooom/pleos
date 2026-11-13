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
import android.content.res.Configuration
import android.graphics.Typeface
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Build
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.*
import io.chagok.app.R
import io.chagok.app.domain.*
import java.time.Instant
import java.time.LocalDate
import java.util.Locale

/**
 * Pleos app. Pleos owns the navigation, status bar, DrivingView and dock; this activity is only the Chagok panel in the
 * window Pleos gives it (half window beside the navi).
 * - start (CAR_READY): greeting + today's route; "코스 바꾸기" shows other trips; "좋아, 출발하자" sends the route to the navi.
 * - live (CAR_RADIO): Chagok floats and says what the backend sends (SpeakCommand). No driver input UI except the place
 *   chip under a line, which opens "들렀다 갈까요?" (approve → Pleos navi waypoint).
 * - arrive (CAR_SUMMARY): shown when the navi reports the destination.
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
    private var showTrips = false
    private var nextEta: String? = null
    private var arrived: Pair<Int, Int>? = null // distance m, duration s from the navi
    private val savedPlaces = linkedSetOf<String>()
    private var sheetPlace: Place? = null
    private var linesHeard = 0

    private var navi: PleosNavi? = null
    private var tts: TextToSpeech? = null
    private var ttsReady: Boolean? = null // null while the engine is still starting
    private var queuedLine: String? = null
    private var current: SpeakCommand? = null // the line on screen
    private var speakingNow = false
    private var freshLine = false
    private var saying = "" // what the TTS was last asked to read
    private var held: Pair<String, Long>? = null // line cut off by a call / Gleo, and when, to re-read afterwards
    private val audio by lazy { getSystemService(AudioManager::class.java) }

    /**
     * Pleos Connect reports mdpi (160 dpi) on its 2560×1440 screen, so dp/sp UI renders tiny.
     * ponytail: draw the panel at 2× (xhdpi); replace with the Pleos design-system scale once we have it.
     */
    override fun attachBaseContext(base: Context) {
        val config = Configuration(base.resources.configuration).apply { densityDpi = DisplayMetrics.DENSITY_XHIGH }
        super.attachBaseContext(base.createConfigurationContext(config))
    }

    override fun onCreate(state: Bundle?) {
        if (state == null) intent.getStringExtra(TripContract.EXTRA_TRIP)?.let(::applyPlan)
        selectTrip(plan)
        super.onCreate(state)
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
        wire()
        if (state == null) intent.getStringExtra(TripContract.EXTRA_SPEAK)?.let(::onSpeak)
        // Backend messages arrive as broadcasts so they don't bring Chagok to the front: Pleos moves a front-launched
        // app into the primary (left) window and would swap it with the navi.
        // ponytail: exported for adb/QA; replace with the backend transport and drop the export.
        val filter = IntentFilter(ACTION_SPEAK).apply { addAction(ACTION_TRIP); addAction(ACTION_ARRIVE) }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, RECEIVER_EXPORTED) else registerReceiver(receiver, filter)
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            intent.getStringExtra(TripContract.EXTRA_TRIP)?.let { applyPlan(it); selectTrip(plan); if (screen != Screen.CAR_READY) navigate(Screen.CAR_READY) else render() }
            intent.getStringExtra(TripContract.EXTRA_SPEAK)?.let(::onSpeak)
            if (intent.action == ACTION_ARRIVE) arrive(null) // QA: pretend the navi reported the destination
        }
    }

    /** Running app: `am start` with singleTop delivers new TripPlans / SpeakCommands here. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(TripContract.EXTRA_TRIP)?.let { applyPlan(it); selectTrip(plan); render() }
        intent.getStringExtra(TripContract.EXTRA_SPEAK)?.let(::onSpeak)
    }

    override fun onDestroy() {
        unregisterReceiver(receiver); navi?.release()
        audio.unregisterAudioRecordingCallback(micWatch)
        if (Build.VERSION.SDK_INT >= 31) audio.removeOnModeChangedListener(modeWatch)
        tts?.removeEventListener(ttsEvents); tts?.release(); super.onDestroy()
    }

    private fun wire() {
        click(R.id.start_change) { showTrips = !showTrips; render() }
        click(R.id.start_go) {
            sendRoute()
            emit(DriveEvent.Type.TRIP_STARTED)
            val first = route.firstOrNull()
            showLine(SpeakCommand(tripId, if (first == null) "출발! 가는 길에 재밌는 이야기가 보이면 들려줄게요."
                else "출발! 먼저 ${ro(first.name)} 가요. 가는 길에 재밌는 이야기가 보이면 들려줄게요."), counts = false)
        }
        click(R.id.start_orb) { speakingNow = true; render(); say("오늘은 ${ro(draft.destination)} 떠나는 날이에요. 준비되면 출발하자고 말해 주세요.") }
        click(R.id.live_orb) { held = null; if (speakingNow) { tts?.stop(); speakingNow = false; render() } }
        click(R.id.live_place) { current?.place?.let(::openSheet) }
        click(R.id.sheet_decline) { decide(approve = false) }
        click(R.id.sheet_approve) { decide(approve = true) }
        click(R.id.sheet_view) { sheetPlace = null; render() }
        click(R.id.arrive_phone) { message("폰의 차곡 ‘기록’에서 사진과 메모를 이어서 남길 수 있어요") }
        click(R.id.arrive_home) { backToStart() }
    }

    // ── trips ──
    private fun selectTrip(p: TripPlan) {
        // Copy: the base class restores saved fields into `draft` in place, which must not touch the sample trips.
        plan = p; draft = p.draft.copy(companions = LinkedHashSet(p.draft.companions), interests = LinkedHashSet(p.draft.interests))
        itineraryIds.clear(); itineraryIds.addAll(p.places.map(Place::id))
        route.clear(); route.addAll(p.places)
        visited = 0; nextEta = null; arrived = null
    }

    /** Phone → Pleos entry point. Until the transport exists, the plan arrives as an intent extra (see CONTRACT.md). */
    private fun applyPlan(json: String) {
        runCatching { TripPlan.fromJson(json) }
            .onSuccess { incoming -> trips.removeAll { it.tripId == incoming.tripId }; trips.add(0, incoming); plan = incoming }
            .onFailure { Log.w("ChagokContract", "Ignoring invalid TripPlan, showing sample trip: ${it.message}") }
    }

    override fun itineraryPlaces() = route

    // ── navi ──
    private fun sendRoute() {
        val nav = navi?.takeIf { it.ready } ?: run { message("내비에 연결하지 못해 경로는 직접 설정해 주세요"); return }
        nav.route(route.drop(visited))?.let(::message)
    }

    private fun onNavi(e: PleosNavi.Event) {
        when (e) {
            is PleosNavi.Event.Ready -> if (!e.ok) Log.w("ChagokNavi", "Navi not available; the route stays in Chagok only")
            is PleosNavi.Event.Routed -> Unit
            // 0 while the navi re-routes (e.g. right after a waypoint is added): keep the last value until a real one arrives
            is PleosNavi.Event.Progress -> if (e.remainS > 0) { nextEta = minutes(e.remainS); if (screen == Screen.CAR_RADIO) render() }
            is PleosNavi.Event.WaypointArrived -> { visited = (visited + 1).coerceAtMost(route.size); nextEta = null; render() }
            is PleosNavi.Event.Arrived -> arrive(e.distanceM to e.durationS)
            is PleosNavi.Event.Failed -> message("내비가 경로를 만들지 못했어요 (${e.reason})")
        }
    }

    /** After arrival: clear the finished trip and go back to the start screen (also the back button on the summary). */
    private fun backToStart() {
        current = null; held = null; savedPlaces.clear(); linesHeard = 0; sheetPlace = null; showTrips = false
        selectTrip(plan); history.clear(); screen = Screen.CAR_READY; render()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when {
            sheetPlace != null -> { sheetPlace = null; render() }
            screen == Screen.CAR_SUMMARY -> backToStart()
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
        if (savedPlaces.add(place.id)) emit(DriveEvent.Type.PLACE_SAVED, place.id)
        sheetPlace = place; render()
    }

    private fun decide(approve: Boolean) {
        val place = sheetPlace ?: return
        sheetPlace = null
        if (!approve) { emit(DriveEvent.Type.WAYPOINT_DECLINED, place.id); message("${place.name}, 기록에만 담아 둘게요"); render(); return }
        val added = navi?.takeIf { it.ready }?.addWaypoint(place) == true
        emit(DriveEvent.Type.WAYPOINT_APPROVED, place.id)
        if (route.none { it.id == place.id }) route.add(visited.coerceAtMost(route.size), place)
        nextEta = null // the navi re-routes; wait for its next progress event
        message(if (added) "${place.name}, 내비 경유지에 넣었어요" else if ((navi?.waypoints ?: 0) >= 2) "내비 경유지는 2곳까지라 기록에만 넣었어요" else "${place.name}, 다음 들를 곳으로 넣었어요")
        render()
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
        held = text to System.currentTimeMillis(); speakingNow = false; render()
        Log.i("ChagokContract", "대화 중: 멈춤")
    }
    private fun onConversationChanged() = runOnUiThread {
        if (isFinishing) return@runOnUiThread
        if (inConversation()) { if (speakingNow) { hold(saying); tts?.stop() } ; return@runOnUiThread }
        val (text, at) = held ?: return@runOnUiThread
        held = null
        if (System.currentTimeMillis() - at > RESUME_WITHIN_MS) { Log.i("ChagokContract", "대화 끝: 오래돼서 넘김"); return@runOnUiThread }
        Log.i("ChagokContract", "대화 끝: 다시 읽기")
        speakingNow = true; render(); say(text)
    }

    /** Pleos → Phone. ponytail: logcat stands in for the transport until the backend relay exists. */
    private fun emit(type: DriveEvent.Type, placeId: String? = null) {
        Log.i("ChagokContract", DriveEvent(tripId, type, placeId, Instant.now().toString()).toJson())
    }

    // ── render ──
    override fun render() {
        fun show(id: Int, on: Boolean) { findViewById<View>(id).visibility = if (on) View.VISIBLE else View.GONE }
        show(R.id.start_view, screen == Screen.CAR_READY)
        show(R.id.live_view, screen == Screen.CAR_RADIO)
        show(R.id.arrive_view, screen == Screen.CAR_SUMMARY)
        show(R.id.sheet_view, screen == Screen.CAR_RADIO && sheetPlace != null)
        when (screen) {
            Screen.CAR_RADIO -> { renderLive(); sheetPlace?.let(::renderSheet) }
            Screen.CAR_SUMMARY -> renderArrive()
            else -> renderStart()
        }
    }

    private fun renderStart() {
        findViewById<CompanionOrbView>(R.id.start_orb).speaking = speakingNow
        val dest = draft.destination
        findViewById<TextView>(R.id.start_hello).text = SpannableStringBuilder("오늘은 ").apply {
            val s = length; append(dest)
            setSpan(StyleSpan(Typeface.BOLD), s, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(getColor(R.color.deep)), s, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            append(ro(dest).removePrefix(dest)).append("\n떠나는 날이에요")
        }
        val who = draft.companions.toList()
        findViewById<TextView>(R.id.start_meta).text =
            monthDay(draft.departure) + if (who.isEmpty() || who == listOf("혼자")) " · 혼자 떠나요" else " · ${wa(who.joinToString(", "))} 함께"
        findViewById<TextView>(R.id.start_mood).text = draft.mood
        findViewById<LinearLayout>(R.id.start_route).apply {
            removeAllViews()
            addView(routeRow("지금", "여기서 출발", route.firstOrNull()?.let(::eta)?.let { "$it 뒤 도착" }.orEmpty(), R.drawable.dot_route_here, first = true, last = route.isEmpty()))
            route.forEachIndexed { i, p ->
                addView(routeRow(p.time, p.name, p.duration, if (i == route.lastIndex) R.drawable.dot_route_last else R.drawable.dot_route, first = false, last = i == route.lastIndex))
            }
        }
        findViewById<TextView>(R.id.start_foot).text = (listOf("${route.size}곳 들러요") + draft.interests).joinToString("  ·  ")
        findViewById<View>(R.id.start_trips_box).visibility = if (showTrips) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.start_change).isSelected = showTrips
        if (showTrips) findViewById<LinearLayout>(R.id.start_trips).apply {
            removeAllViews()
            trips.forEach { t ->
                addView(Button(context, null, 0, R.style.Car_Chip).apply {
                    text = "${t.draft.destination} · ${t.places.size}곳"; isSelected = t.tripId == plan.tripId
                    setOnClickListener { if (t.tripId != plan.tripId) selectTrip(t); showTrips = false; render() }
                }, LinearLayout.LayoutParams(-2, dp(42)).apply { marginEnd = dp(8) })
            }
        }
    }

    private fun renderLive() {
        findViewById<CompanionOrbView>(R.id.live_orb).speaking = speakingNow
        val next = route.getOrNull(visited)
        findViewById<TextView>(R.id.live_next).apply {
            visibility = if (next == null) View.INVISIBLE else View.VISIBLE
            if (next != null) text = SpannableStringBuilder().apply {
                fun muted(t: String) { val s = length; append(t)
                    setSpan(ForegroundColorSpan(getColor(R.color.soft_ink)), s, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(AbsoluteSizeSpan(13, true), s, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
                muted("다음  ")
                val s = length; append(next.name); setSpan(StyleSpan(Typeface.BOLD), s, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                (nextEta ?: eta(next))?.let { muted("  $it") }
            }
        }
        val line = findViewById<TextView>(R.id.live_line)
        line.text = current?.text.orEmpty()
        val target = if (current != null && !speakingNow && held == null) .5f else 1f // dimmed once said, not while held
        if (freshLine) {
            freshLine = false
            line.alpha = 0f; line.translationY = dp(9).toFloat()
            line.animate().alpha(target).translationY(0f).setDuration(450).start()
        } else line.animate().alpha(target).setDuration(600).start()
        findViewById<TextView>(R.id.live_place).apply {
            val place = current?.place
            visibility = if (place == null) View.GONE else View.VISIBLE
            if (place != null) {
                val kept = place.id in savedPlaces
                isSelected = kept
                setTextColor(getColor(if (kept) R.color.deep else R.color.soft_ink))
                text = if (kept) "✓  ${place.name} 담았어요" else "📍  ${place.name}" + place.distance.takeIf { it.isNotBlank() }?.let { "  ·  $it" }.orEmpty()
            }
        }
    }

    private fun renderSheet(place: Place) {
        findViewById<TextView>(R.id.sheet_title).text = "${place.name}, 들렀다 갈까요?"
        findViewById<TextView>(R.id.sheet_reason).text = listOf("담아 뒀어요", place.reason).filter(String::isNotBlank).joinToString(" · ")
        val slots = navi?.waypoints ?: 0
        findViewById<LinearLayout>(R.id.sheet_rows).apply {
            removeAllViews()
            addView(infoRow("추가 이동시간", detour(place)))
            addView(infoRow("방문 가능", place.open.ifBlank { "확인 필요" }))
            addView(infoRow("내비 경유지", if (slots >= 2) "가득 찼어요 (2/2)" else "${slots}/2 · 하나 더 넣을 수 있어요"))
        }
    }

    private fun renderArrive() {
        findViewById<TextView>(R.id.arrive_title).text = "${draft.destination}에 도착했어요"
        findViewById<TextView>(R.id.arrive_route).text = (listOf("출발") + route.map(Place::name)).joinToString("  →  ")
        val (distance, duration) = arrived ?: (null to null)
        findViewById<LinearLayout>(R.id.arrive_stats).apply {
            removeAllViews()
            listOf(
                (distance?.let(::km) ?: "—") to "이동 거리", (duration?.let(::minutes) ?: "—") to "걸린 시간",
                "${route.size}곳" to "들른 장소", "${linesHeard}개" to "들은 이야기",
            ).chunked(2).forEach { pair ->
                addView(LinearLayout(context).apply {
                    pair.forEachIndexed { i, (value, label) ->
                        addView(LinearLayout(context).apply {
                            orientation = LinearLayout.VERTICAL; setBackgroundResource(R.drawable.bg_trip_card); elevation = dp(2).toFloat()
                            setPadding(dp(16), dp(14), dp(16), dp(14))
                            addView(TextView(context).apply { text = value; textSize = 22f; setTextColor(getColor(R.color.ink)); typeface = Typeface.DEFAULT_BOLD })
                            addView(TextView(context).apply { text = label; textSize = 13f; setTextColor(getColor(R.color.soft_ink)) })
                        }, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i == 0) marginEnd = dp(10) })
                    }
                }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
            }
        }
        val kept = (route + trips.flatMap { it.places } + listOfNotNull(current?.place)).filter { it.id in savedPlaces }.distinctBy { it.id }.map(Place::name)
        findViewById<TextView>(R.id.arrive_saved).text = if (kept.isEmpty()) "가는 길에 담은 곳은 없어요" else "담은 곳 · " + kept.joinToString(" · ")
    }

    // ── small views & helpers ──
    private fun click(id: Int, action: () -> Unit) = findViewById<View>(id).setOnClickListener { action() }

    /** One stop on today's route: time · rail with dot · name · note. */
    private fun routeRow(time: String, name: String, note: String, dot: Int, first: Boolean, last: Boolean): View {
        val here = dot == R.drawable.dot_route_here
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, dp(40))
            addView(TextView(context).apply { text = time; textSize = 15f; setTextColor(getColor(R.color.soft_ink)); fontFeatureSettings = "tnum" }, LinearLayout.LayoutParams(dp(59), -2))
            addView(FrameLayout(context).apply {
                fun rail(g: Int) = addView(View(context).apply { setBackgroundColor(0xFFCFDAC8.toInt()) }, FrameLayout.LayoutParams(dp(2), dp(20), g or Gravity.CENTER_HORIZONTAL))
                if (!first) rail(Gravity.TOP)
                if (!last) rail(Gravity.BOTTOM)
                addView(View(context).apply { setBackgroundResource(dot) }, FrameLayout.LayoutParams(dp(11), dp(11), Gravity.CENTER))
            }, LinearLayout.LayoutParams(dp(32), -1))
            addView(TextView(context).apply {
                text = name; textSize = if (here) 16f else 18f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(getColor(if (here) R.color.soft_ink else R.color.ink))
                if (!here) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(TextView(context).apply { text = note; textSize = 14f; setTextColor(getColor(R.color.soft_ink)) })
        }
    }

    private fun infoRow(label: String, value: String) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL; layoutParams = LinearLayout.LayoutParams(-1, dp(40))
        addView(TextView(context).apply { text = label; textSize = 15f; setTextColor(getColor(R.color.soft_ink)) }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(TextView(context).apply { text = value; textSize = 16f; setTextColor(getColor(R.color.ink)); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) })
    }

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
        out.putString("plan", plan.toJson()); out.putString("spoken", current?.toJson())
        out.putStringArrayList("saved", ArrayList(savedPlaces)); out.putInt("visited", visited); out.putInt("heard", linesHeard)
        super.onSaveInstanceState(out)
    }
    override fun restore(state: Bundle) {
        super.restore(state)
        state.getString("plan")?.let { runCatching { TripPlan.fromJson(it) }.getOrNull() }?.let { p -> trips.removeAll { it.tripId == p.tripId }; trips.add(0, p); selectTrip(p) }
        current = state.getString("spoken")?.let { runCatching { SpeakCommand.fromJson(it) }.getOrNull() }
        savedPlaces.addAll(state.getStringArrayList("saved").orEmpty())
        visited = state.getInt("visited"); linesHeard = state.getInt("heard")
    }

    private companion object {
        const val HOTWORD = 1999 // MediaRecorder.AudioSource.HOTWORD (system API)
        const val RESUME_WITHIN_MS = 60_000L // a line cut off longer ago than this is skipped, not re-read
        const val ACTION_SPEAK = "io.chagok.app.pleos.SPEAK"
        const val ACTION_TRIP = "io.chagok.app.pleos.TRIP"
        const val ACTION_ARRIVE = "io.chagok.app.pleos.ARRIVE"

        /** ponytail: sample "my trips" for 코스 바꾸기 until the backend serves the driver's trips. */
        fun sampleTrips(): List<TripPlan> {
            fun p(id: String, name: String, time: String, stay: String, source: String, group: String, reason: String, dist: String, lat: Double, lng: Double, open: String) =
                Place(id, name, time, stay, source, group, reason, dist, lat, lng, open)
            return listOf(
                TripPlan("sample-gangneung", TripDraft(), listOf(
                    p("anmok", "안목 커피거리", "10:30", "60분", "저장한 릴스 2개", "저장", "부모님 · 잔잔한 분위기에 맞아요", "3.2 km · 8분", 37.7722, 128.9473, "영업 중 · 22시까지"),
                    p("chodang", "초당 순두부 거리", "12:30", "70분", "좋아요한 쇼츠 1개", "좋아요", "맛집 관심사와 맞아요", "5.1 km · 12분", 37.7915, 128.9145, "영업 중 · 20시까지"),
                    p("gyeongpo", "경포 호수 산책길", "15:00", "50분", "친구가 보낸 릴스", "보낸", "오후 산책 추천", "8.4 km · 18분", 37.7965, 128.9000, "항상 열려 있어요"))),
                TripPlan("sample-yangyang", TripDraft(destination = "양양", companions = linkedSetOf("친구"), interests = linkedSetOf("자연·바다", "카페"), mood = "활기찬"), listOf(
                    p("surfy", "서피비치", "11:00", "90분", "저장한 릴스 3개", "저장", "바다 관심사와 맞아요", "12 km · 20분", 38.0270, 128.7148, "영업 중 · 18시까지"),
                    p("naksansa", "낙산사", "14:00", "60분", "좋아요한 쇼츠 1개", "좋아요", "바다가 보이는 절", "9 km · 15분", 38.1247, 128.6290, "18시까지 입장"))),
            )
        }
    }
}
