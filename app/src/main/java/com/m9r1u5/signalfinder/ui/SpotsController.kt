package com.m9r1u5.signalfinder.ui

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.m9r1u5.signalfinder.MainActivity
import com.m9r1u5.signalfinder.data.Spot
import com.m9r1u5.signalfinder.databinding.ItemSpotBinding
import com.m9r1u5.signalfinder.databinding.ScreenSpotsBinding
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SpotsController(private val act: MainActivity, private val b: ScreenSpotsBinding) {

    private val adapter = SpotAdapter { act.spotActions.showSpotMenu(it) }

    init {
        b.rvSpots.layoutManager = LinearLayoutManager(act)
        b.rvSpots.adapter = adapter
        b.btnAddSpot.setOnClickListener { act.spotActions.promptNewSpot() }
        act.lifecycleScope.launch {
            act.db.spotDao().observeRanked().collect { list ->
                adapter.submit(list)
                b.tvEmpty.isVisible = list.isEmpty()
            }
        }
    }
}

class SpotAdapter(private val onClick: (Spot) -> Unit) : RecyclerView.Adapter<SpotAdapter.VH>() {

    class VH(val b: ItemSpotBinding) : RecyclerView.ViewHolder(b.root)

    private var items: List<Spot> = emptyList()
    private val fmt = SimpleDateFormat("d MMM HH:mm", Locale.getDefault())

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<Spot>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemSpotBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(h: VH, position: Int) {
        val s = items[position]
        val color = SignalColors.forBars(s.bars, s.avgDbm != null)
        h.b.tvRank.text = "#${position + 1}"
        h.b.tvRank.setTextColor(color)
        h.b.tvName.text = s.name
        h.b.tvSpotDbm.text = s.avgDbm?.let { "$it dBm  ·  ${s.score}/100" } ?: "No service"
        h.b.tvSpotDbm.setTextColor(color)
        h.b.tvLine2.text = listOf(s.networkType, s.carrier, fmt.format(Date(s.timestamp)))
            .filter { it.isNotBlank() }.joinToString("  ·  ")
        val range = if (s.minDbm != null && s.maxDbm != null) "Range ${s.minDbm} to ${s.maxDbm}" else "No signal"
        val gps = if (s.lat != null) "GPS ✓" else "No GPS"
        h.b.tvLine3.text = "$range  ·  ${s.samples} samples  ·  $gps"
        if (s.downMbps != null) {
            h.b.tvSpeed.isVisible = true
            h.b.tvSpeed.text = String.format(
                Locale.US, "↓ %.1f Mbps   ↑ %.1f Mbps   %d ms",
                s.downMbps, s.upMbps ?: 0.0, s.pingMs ?: 0,
            )
        } else {
            h.b.tvSpeed.isVisible = false
        }
        h.b.spotBars.set(s.bars, color)
        h.b.root.setOnClickListener { onClick(s) }
    }
}
