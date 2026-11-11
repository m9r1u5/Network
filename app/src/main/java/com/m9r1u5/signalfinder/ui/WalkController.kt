package com.m9r1u5.signalfinder.ui

import android.location.Location
import android.view.WindowManager
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.m9r1u5.signalfinder.MainActivity
import com.m9r1u5.signalfinder.R
import com.m9r1u5.signalfinder.data.Reading
import com.m9r1u5.signalfinder.databinding.ScreenWalkBinding
import com.m9r1u5.signalfinder.signal.SignalMath
import com.m9r1u5.signalfinder.signal.SignalReading
import com.m9r1u5.signalfinder.util.Geiger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class WalkController(private val act: MainActivity, private val b: ScreenWalkBinding) {

    private val geiger = Geiger(act).apply {
        scoreProvider = { act.monitor.latest?.takeIf { it.hasService }?.score }
    }

    var walking = false
        private set
    private var startedAt = 0L
    private var best: SignalReading? = null
    private var bestLoc: Location? = null
    private var alertedScore: Int? = null
    private var lastLog = 0L
    private var logged = 0
    private val hideAlert = Runnable { b.tvBestAlert.isVisible = false }

    init {
        val saved = act.prefs.getString("geiger_mode", Geiger.Mode.SOUND.name) ?: Geiger.Mode.SOUND.name
        geiger.mode = runCatching { Geiger.Mode.valueOf(saved) }.getOrDefault(Geiger.Mode.SOUND)
        b.toggleFeedback.check(
            when (geiger.mode) {
                Geiger.Mode.SOUND -> R.id.optSound
                Geiger.Mode.VIBRATE -> R.id.optVibrate
                Geiger.Mode.BOTH -> R.id.optBoth
                Geiger.Mode.OFF -> R.id.optOff
            },
        )
        b.toggleFeedback.addOnButtonCheckedListener { _, id, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            geiger.mode = when (id) {
                R.id.optVibrate -> Geiger.Mode.VIBRATE
                R.id.optBoth -> Geiger.Mode.BOTH
                R.id.optOff -> Geiger.Mode.OFF
                else -> Geiger.Mode.SOUND
            }
            act.prefs.edit().putString("geiger_mode", geiger.mode.name).apply()
        }
        b.btnWalk.setOnClickListener { if (walking) stopWalk() else startWalk() }
        b.btnWalkSave.setOnClickListener { act.spotActions.promptNewSpot() }
        updateBestText()
    }

    fun startWalk() {
        walking = true
        startedAt = System.currentTimeMillis()
        best = null
        bestLoc = null
        alertedScore = null
        logged = 0
        b.btnWalk.text = "Stop walk"
        act.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        geiger.start()
        updateBestText()
    }

    fun stopWalk() {
        if (!walking) return
        walking = false
        b.btnWalk.text = "Start walk"
        act.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        geiger.stop()
        updateBestText()
    }

    fun onReading(r: SignalReading) {
        val color = SignalColors.forBars(r.bars, r.hasService)
        b.tvWalkDbm.text = r.dbm?.toString() ?: "—"
        b.tvWalkDbm.setTextColor(color)
        b.tvWalkNetwork.text = if (r.hasService) "dBm ${r.tech.metric} · ${r.networkLabel}" else "No service"
        b.walkBars.set(r.bars, color)

        if (walking) {
            if (r.hasService && r.timestamp - startedAt > 2500) {
                val cur = best
                if (cur == null || r.score > cur.score) {
                    best = r
                    bestLoc = act.location.fresh()
                }
                if (alertedScore == null) {
                    alertedScore = r.score
                } else if (SignalMath.isNewBest(r.score, alertedScore)) {
                    alertedScore = r.score
                    announceBest(r)
                }
            }
            if (r.timestamp - lastLog >= 2000) {
                lastLog = r.timestamp
                log(r)
            }
        }

        b.graph.setData(act.monitor.history.toList(), if (walking) best?.dbm else null)
        updateBestText()
    }

    private fun announceBest(r: SignalReading) {
        geiger.celebrate()
        b.tvBestAlert.text = "New best!  ${r.dbm} dBm"
        b.tvBestAlert.isVisible = true
        b.tvBestAlert.removeCallbacks(hideAlert)
        b.tvBestAlert.postDelayed(hideAlert, 3000)
    }

    private fun log(r: SignalReading) {
        val loc = act.location.fresh(maxAgeMs = 15_000, maxAccuracyM = 50f)
        val reading = Reading(
            timestamp = r.timestamp,
            dbm = r.dbm,
            score = r.score,
            bars = r.bars,
            tech = r.tech.name,
            networkType = r.networkLabel,
            carrier = r.carrier,
            cellId = r.cellId,
            lat = loc?.latitude,
            lon = loc?.longitude,
            accuracyM = loc?.accuracy,
            simLabel = r.simLabel,
        )
        logged++
        act.lifecycleScope.launch(Dispatchers.IO) { act.db.readingDao().insert(reading) }
        b.tvWalkInfo.text = "Logged $logged readings this walk · GPS: ${act.location.describe()}"
    }

    private fun updateBestText() {
        val bst = best
        b.tvBest.text = when {
            !walking && bst == null ->
                "Tap Start walk, then walk slowly. The clicks get faster as the signal gets stronger."
            bst == null -> "Best so far: measuring…"
            else -> {
                val ago = (System.currentTimeMillis() - bst.timestamp) / 1000
                val agoText = if (ago < 60) "${ago}s ago" else "${ago / 60} min ago"
                val gps = bestLoc?.let { " · GPS ±${it.accuracy.toInt()} m" } ?: ""
                "Best so far: ${bst.dbm} dBm · ${bst.networkLabel}\n$agoText$gps" +
                    if (!walking) "  (walk ended)" else ""
            }
        }
    }
}
