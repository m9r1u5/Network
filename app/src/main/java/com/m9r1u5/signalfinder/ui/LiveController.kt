package com.m9r1u5.signalfinder.ui

import android.view.View
import androidx.core.view.isVisible
import com.google.android.material.chip.Chip
import com.m9r1u5.signalfinder.MainActivity
import com.m9r1u5.signalfinder.SimInfo
import com.m9r1u5.signalfinder.databinding.ScreenLiveBinding
import com.m9r1u5.signalfinder.signal.SignalMath
import com.m9r1u5.signalfinder.signal.SignalReading
import com.m9r1u5.signalfinder.signal.Tech

class LiveController(private val act: MainActivity, private val b: ScreenLiveBinding) {

    init {
        b.btnGrant.setOnClickListener { act.requestPermissions() }
        b.btnSaveSpot.setOnClickListener { act.spotActions.promptNewSpot() }
        b.btnSpeedTest.setOnClickListener { act.spotActions.runSpeedTest(null) }
    }

    fun showPermissionCard(show: Boolean) {
        b.permissionCard.isVisible = show
    }

    fun showSims(sims: List<SimInfo>, selectedSubId: Int) {
        b.chipGroupSim.removeAllViews()
        b.chipGroupSim.visibility = if (sims.size > 1) View.VISIBLE else View.GONE
        for (s in sims) {
            val chip = Chip(act).apply {
                text = s.label
                textSize = 16f
                isCheckable = true
                isChecked = s.subId == selectedSubId
                setOnClickListener { act.selectSim(s) }
            }
            b.chipGroupSim.addView(chip)
        }
    }

    fun update(r: SignalReading) {
        val color = SignalColors.forBars(r.bars, r.hasService)
        b.tvCarrier.text = if (r.simLabel.isNotBlank() && act.simCount() > 1) "${r.carrier}  ·  ${r.simLabel.substringBefore(" ·")}" else r.carrier
        b.tvNetwork.text = r.networkLabel
        b.tvDbm.text = r.dbm?.toString() ?: "—"
        b.tvDbm.setTextColor(color)
        b.tvUnit.text = if (r.hasService) "dBm ${r.tech.metric}" else ""
        b.bars.set(r.bars, color)
        b.tvQuality.text = SignalMath.qualityText(r.bars, r.hasService)
        b.tvQuality.setTextColor(color)

        val lines = mutableListOf<String>()
        r.rsrp?.let { lines += "4G RSRP   $it dBm" }
        r.rsrq?.let { lines += "4G RSRQ   $it dB" }
        r.sinr?.let { lines += "4G SINR   $it dB" }
        r.nrRsrp?.let { lines += "5G RSRP   $it dBm" }
        r.nrRsrq?.let { lines += "5G RSRQ   $it dB" }
        r.nrSinr?.let { lines += "5G SINR   $it dB" }
        if (r.tech != Tech.LTE && r.tech != Tech.NR && r.dbm != null) {
            lines += "${r.tech.generation} ${r.tech.metric}   ${r.dbm} dBm"
        }
        if (r.hasService) lines += "Score     ${r.score}/100"
        b.tvDetails.text = if (lines.isEmpty()) "No signal measurements" else lines.joinToString("\n")
        b.tvCell.text = r.cellId.ifBlank { "—" }

        b.tvWifi.text = if (r.wifiRssi != null) {
            val name = r.wifiSsid ?: "Connected"
            "$name   ${r.wifiRssi} dBm  (${SignalMath.wifiBars(r.wifiRssi)}/5 bars)"
        } else "Not connected"

        b.tvGps.text = act.location.describe()
    }
}
