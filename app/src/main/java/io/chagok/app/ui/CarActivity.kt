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
import android.os.Build
import android.graphics.Typeface
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
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.chagok.app.R
import io.chagok.app.domain.*
import java.time.Instant
import java.time.LocalDate
import java.util.Locale

/**
 * Pleos app. Pleos owns the navigation, status bar, DrivingView and dock; this activity is only the Chagok panel
 * inside the window Pleos gives it (portrait = the half window beside the navi).
 *
 * Two states, same as mockup/pleos-screen.html:
 * - start (CAR_READY): before departure — greeting, today's route, "좋아, 출발하자".
 * - live (CAR_RADIO): Chagok floats and says what the backend sends (SpeakCommand). No driver input UI;
 *   the only touch is the place chip under a line ("담았어요" → PLACE_SAVED).
 */
class CarActivity : ScreenActivity() {
    override val layout = R.layout.activity_car
    override val startScreen = Screen.CAR_READY
    private val savedPlaces = linkedSetOf<String>()
    private var plan: TripPlan? = null
    private val tripId get() = plan?.tripId ?: "demo"
    private var tts: TextToSpeech? = null
    private var ttsReady: Boolean? = null // null while the engine is still starting
    private var queuedLine: String? = null
    private var current: SpeakCommand? = null // the line on screen
    private var speakingNow = false
    private var freshLine = false

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
        findViewById<View>(R.id.start_go).setOnClickListener {
            emit(DriveEvent.Type.TRIP_STARTED)
            val first = itineraryPlaces().firstOrNull()
            showLine(SpeakCommand(tripId, if (first == null) "출발! 가는 길에 재밌는 이야기가 보이면 들려줄게요."
                else "출발! 먼저 ${ro(first.name)} 가요. 가는 길에 재밌는 이야기가 보이면 들려줄게요."))
        }
        findViewById<View>(R.id.start_change).setOnClickListener { message("코스는 폰의 차곡에서 바꿀 수 있어요") }
        findViewById<View>(R.id.start_orb).setOnClickListener {
            speakingNow = true; render(); say("오늘은 ${ro(draft.destination)} 떠나는 날이에요. 준비되면 출발하자고 말해 주세요.")
        }
        findViewById<View>(R.id.live_orb).setOnClickListener { if (speakingNow) { tts?.stop(); speakingNow = false; render() } }
        findViewById<View>(R.id.live_place).setOnClickListener {
            val place = current?.place ?: return@setOnClickListener
            if (savedPlaces.add(place.id)) emit(DriveEvent.Type.PLACE_SAVED, place.id)
            render()
        }
        if (state == null) intent.getStringExtra(TripContract.EXTRA_SPEAK)?.let(::onSpeak)
        // SpeakCommands arrive as a broadcast so they don't bring Chagok to the front:
        // Pleos moves a front-launched app into the primary (left) window and would swap it with the navi.
        // ponytail: exported for adb/QA; replace with the backend transport and drop the export.
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(speakReceiver, IntentFilter(ACTION_SPEAK), RECEIVER_EXPORTED)
        else registerReceiver(speakReceiver, IntentFilter(ACTION_SPEAK))
    }

    private val speakReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { intent.getStringExtra(TripContract.EXTRA_SPEAK)?.let(::onSpeak) }
    }

    /** Running app: `am start` with singleTop delivers new TripPlans / SpeakCommands here. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(TripContract.EXTRA_TRIP)?.let { applyPlan(it); render() }
        intent.getStringExtra(TripContract.EXTRA_SPEAK)?.let(::onSpeak)
    }

    override fun onDestroy() { unregisterReceiver(speakReceiver); tts?.removeEventListener(ttsEvents); tts?.release(); super.onDestroy() }

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

    /** Backend → Pleos. During a trip (or assuming one), Chagok shows the line under the orb and says it. */
    private fun onSpeak(json: String) {
        val command = runCatching { SpeakCommand.fromJson(json) }
            .onFailure { Log.w("ChagokContract", "Ignoring invalid SpeakCommand: ${it.message}") }.getOrNull() ?: return
        showLine(command)
    }

    private fun showLine(line: SpeakCommand) {
        current = line; speakingNow = true; freshLine = true
        if (screen != Screen.CAR_RADIO) navigate(Screen.CAR_RADIO) else render()
        say(line.text)
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
            .onFailure { Log.w("ChagokContract", "Ignoring invalid TripPlan, showing sample trip: ${it.message}") }
    }

    override fun itineraryPlaces() = plan?.places ?: super.itineraryPlaces()

    /** Pleos → Phone. ponytail: logcat stands in for the transport until the backend relay exists. */
    private fun emit(type: DriveEvent.Type, placeId: String? = null) {
        Log.i("ChagokContract", DriveEvent(tripId, type, placeId, Instant.now().toString()).toJson())
    }

    override fun render() {
        val live = screen == Screen.CAR_RADIO
        findViewById<View>(R.id.start_view).visibility = if (live) View.GONE else View.VISIBLE
        findViewById<View>(R.id.live_view).visibility = if (live) View.VISIBLE else View.GONE
        if (live) renderLive() else renderStart()
    }

    // ── start ──
    private fun renderStart() {
        findViewById<CompanionOrbView>(R.id.start_orb).speaking = speakingNow
        val dest = draft.destination
        findViewById<TextView>(R.id.start_hello).text = SpannableStringBuilder("오늘은 ").apply {
            val s = length
            append(dest)
            setSpan(StyleSpan(Typeface.BOLD), s, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(getColor(R.color.deep)), s, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            append(ro(dest).removePrefix(dest)).append("\n떠나는 날이에요")
        }
        val who = draft.companions.toList()
        findViewById<TextView>(R.id.start_meta).text =
            monthDay(draft.departure) + if (who.isEmpty() || who == listOf("혼자")) " · 혼자 떠나요" else " · ${wa(who.joinToString(", "))} 함께"
        findViewById<TextView>(R.id.start_mood).text = draft.mood
        val places = itineraryPlaces()
        findViewById<LinearLayout>(R.id.start_route).apply {
            removeAllViews()
            addView(routeRow("지금", "여기서 출발", places.firstOrNull()?.let(::eta)?.let { "$it 뒤 도착" }.orEmpty(), R.drawable.dot_route_here, first = true, last = places.isEmpty()))
            places.forEachIndexed { i, p ->
                addView(routeRow(p.time, p.name, p.duration, if (i == places.lastIndex) R.drawable.dot_route_last else R.drawable.dot_route, first = false, last = i == places.lastIndex))
            }
        }
        findViewById<TextView>(R.id.start_foot).text = (listOf("${places.size}곳 들러요") + draft.interests).joinToString("  ·  ")
    }

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
                text = name; textSize = if (here) 16f else 18f
                setTextColor(getColor(if (here) R.color.soft_ink else R.color.ink))
                if (!here) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(TextView(context).apply { text = note; textSize = 14f; setTextColor(getColor(R.color.soft_ink)) })
        }
    }

    // ── live ──
    private fun renderLive() {
        findViewById<CompanionOrbView>(R.id.live_orb).speaking = speakingNow
        val next = itineraryPlaces().firstOrNull()
        findViewById<TextView>(R.id.live_next).apply {
            visibility = if (next == null) View.INVISIBLE else View.VISIBLE
            if (next != null) text = SpannableStringBuilder().apply {
                fun muted(t: String) { val s = length; append(t)
                    setSpan(ForegroundColorSpan(getColor(R.color.soft_ink)), s, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(AbsoluteSizeSpan(13, true), s, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
                muted("다음  ")
                val s = length; append(next.name); setSpan(StyleSpan(Typeface.BOLD), s, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                eta(next)?.let { muted("  $it") }
            }
        }
        val line = findViewById<TextView>(R.id.live_line)
        line.text = current?.text.orEmpty()
        val target = if (current != null && !speakingNow) .5f else 1f // dimmed once said
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

    private companion object { const val ACTION_SPEAK = "io.chagok.app.pleos.SPEAK" }

    // ── small helpers ──
    /** "2 km · 6분" → "6분"; a bare value stays as is. */
    private fun eta(p: Place) = p.distance.substringAfterLast("·").trim().ifBlank { null }
    private fun monthDay(date: String) = runCatching { LocalDate.parse(date).let { "${it.monthValue}월 ${it.dayOfMonth}일" } }.getOrDefault(date)
    /** Korean particle: 받침 → "으로" (but ㄹ → "로"), else "로". 속초로 / 강릉으로 / 서울로. */
    private fun ro(word: String): String { val j = jong(word); return word + if (j != null && j != 0 && j != 8) "으로" else "로" }
    /** 부모님과 / 친구와. */
    private fun wa(word: String): String { val j = jong(word); return word + if (j != null && j != 0) "과" else "와" }
    private fun jong(word: String): Int? = word.trim().lastOrNull()?.takeIf { it in '가'..'힣' }?.let { (it - '가') % 28 }

    override fun onSaveInstanceState(out: Bundle) {
        out.putStringArrayList("saved", ArrayList(savedPlaces)); out.putString("plan", plan?.toJson()); out.putString("spoken", current?.toJson())
        super.onSaveInstanceState(out)
    }
    override fun restore(state: Bundle) {
        super.restore(state)
        state.getString("plan")?.let { plan = runCatching { TripPlan.fromJson(it) }.getOrNull() }
        current = state.getString("spoken")?.let { runCatching { SpeakCommand.fromJson(it) }.getOrNull() }
        savedPlaces.addAll(state.getStringArrayList("saved").orEmpty())
    }
}
