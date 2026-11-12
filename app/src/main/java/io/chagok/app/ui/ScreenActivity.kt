package io.chagok.app.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.*
import io.chagok.app.R
import io.chagok.app.data.DemoTripRepository
import io.chagok.app.domain.*

/** Shared screen kit for the phone app (src/phone) and the car app (src/car). Screens live in each flavor. */
abstract class ScreenActivity : Activity() {
    protected val repository = DemoTripRepository()
    protected var draft = TripDraft()
    protected val itineraryIds = mutableListOf("anmok", "chodang", "gyeongpo")
    protected var screen = Screen.HOME
    protected val history = mutableListOf<Screen>()
    protected lateinit var content: LinearLayout
    protected val ink = Color.rgb(36, 62, 55)
    protected val muted = Color.rgb(113, 126, 112)

    protected abstract val layout: Int
    protected abstract val startScreen: Screen
    protected abstract fun render()

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(layout)
        screen = startScreen
        if (state != null) restore(state)
        render()
    }

    protected open fun navigate(next: Screen) {
        if (next != screen) history.add(screen)
        screen = next
        render()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (history.isNotEmpty()) { screen = history.removeAt(history.lastIndex); render() }
        else super.onBackPressed()
    }

    protected open fun itineraryPlaces() = itineraryIds.mapNotNull { id -> repository.places.find { it.id == id } }
    protected fun text(value: String, size: Int = 18, color: Int = ink) { add(TextView(this).apply { text = value; textSize = size.toFloat(); setTextColor(color); setLineSpacing(dp(3).toFloat(), 1f) }) }
    protected fun card(title: String, body: String, badge: String = "", action: (() -> Unit)? = null): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(18), dp(20), dp(18)); setBackgroundResource(R.drawable.bg_card)
            if (action != null) { isClickable = true; isFocusable = true; setOnClickListener { action() } }
        }
        fun line(value: String, size: Int, color: Int) { card.addView(TextView(this).apply { text = value; textSize = size.toFloat(); setTextColor(color); setPadding(0, dp(3), 0, dp(3)) }) }
        if (badge.isNotEmpty()) line(badge, 11, Color.rgb(170, 103, 68))
        line(title, if (screen.vehicle) 21 else 17, ink); line(body, 13, muted); add(card); return card
    }
    protected fun choices(labels: List<String>, selected: (String) -> Boolean, action: (String) -> Unit) {
        labels.chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEach { label -> row.addView(makeChoice(label, selected(label)) { action(label) }, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(3), 0, dp(3), 0) }) }; add(row)
        }
    }
    private fun makeChoice(label: String, selected: Boolean, action: () -> Unit) = Button(this).apply {
        text = (if (selected) "✓  " else "") + label; textSize = 14f; isAllCaps = false; minHeight = dp(52)
        setTextColor(ink); setPadding(dp(14), dp(12), dp(14), dp(12)); setBackgroundResource(if (selected) R.drawable.bg_choice_selected else R.drawable.bg_card)
        isSelected = selected; setOnClickListener { action() }
    }
    protected fun choice(label: String, selected: Boolean, action: () -> Unit) { add(makeChoice(label, selected, action)) }
    protected fun button(label: String, primary: Boolean = true, action: () -> Unit) {
        add(Button(this).apply { text = label; textSize = if (screen.vehicle) 18f else 15f; isAllCaps = false
            setTextColor(if (primary) Color.WHITE else ink); setBackgroundResource(if (primary) R.drawable.bg_primary else R.drawable.bg_choice_selected)
            minHeight = dp(if (screen.vehicle) 60 else 52); setPadding(dp(16), dp(12), dp(16), dp(12)); setOnClickListener { action() }
        })
    }
    protected fun add(view: View) { content.addView(view, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) }) }
    protected fun integration(result: IntegrationResult, after: (() -> Unit)? = null) {
        val body = when (result) { is IntegrationResult.Demo -> result.message; is IntegrationResult.Unavailable -> result.message }
        AlertDialog.Builder(this).setTitle("스켈레톤 미리보기").setMessage(body).setPositiveButton("확인") { _, _ -> after?.invoke() }.show()
    }
    protected fun message(value: String) { Toast.makeText(this, value, Toast.LENGTH_SHORT).show() }
    protected fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onSaveInstanceState(out: Bundle) {
        out.putString("screen", screen.name); out.putStringArrayList("history", ArrayList(history.map { it.name }))
        out.putString("destination", draft.destination); out.putString("departure", draft.departure); out.putString("end", draft.endDate)
        out.putBoolean("tracking", draft.recordRoute); out.putString("visibility", draft.visibility)
        out.putStringArrayList("companions", ArrayList(draft.companions)); out.putStringArrayList("interests", ArrayList(draft.interests))
        out.putString("mood", draft.mood); out.putString("density", draft.density); out.putStringArrayList("itinerary", ArrayList(itineraryIds))
        super.onSaveInstanceState(out)
    }
    protected open fun restore(state: Bundle) {
        screen = runCatching { Screen.valueOf(state.getString("screen").orEmpty()) }.getOrDefault(startScreen)
        history.addAll(state.getStringArrayList("history").orEmpty().mapNotNull { runCatching { Screen.valueOf(it) }.getOrNull() })
        draft.destination = state.getString("destination", draft.destination); draft.departure = state.getString("departure", draft.departure); draft.endDate = state.getString("end", draft.endDate)
        draft.recordRoute = state.getBoolean("tracking"); draft.visibility = state.getString("visibility", "나만")
        draft.companions.clear(); draft.companions.addAll(state.getStringArrayList("companions").orEmpty()); draft.interests.clear(); draft.interests.addAll(state.getStringArrayList("interests").orEmpty())
        draft.mood = state.getString("mood", "잔잔한"); draft.density = state.getString("density", "보통")
        itineraryIds.clear(); itineraryIds.addAll(state.getStringArrayList("itinerary").orEmpty())
    }
}
