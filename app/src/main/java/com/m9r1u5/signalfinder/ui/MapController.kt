package com.m9r1u5.signalfinder.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.m9r1u5.signalfinder.MainActivity
import com.m9r1u5.signalfinder.data.Reading
import com.m9r1u5.signalfinder.data.Spot
import com.m9r1u5.signalfinder.databinding.ScreenMapBinding
import com.m9r1u5.signalfinder.map.ReadingsOverlay
import com.m9r1u5.signalfinder.map.TileCacher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

class MapController(private val act: MainActivity, private val b: ScreenMapBinding) {

    private val map = b.mapView
    private val readingsOverlay = ReadingsOverlay(act)
    private var myLoc: MyLocationNewOverlay? = null
    private val spotMarkers = mutableListOf<Marker>()
    private var spots: List<Spot> = emptyList()
    private var readings: List<Reading> = emptyList()
    private var centered = false
    private var shown = false

    init {
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.setTilesScaledToDpi(true)
        map.setMinZoomLevel(3.0)
        map.setMaxZoomLevel(20.0)
        map.controller.setZoom(16.0)
        map.controller.setCenter(GeoPoint(-26.2041, 28.0473))
        map.overlays.add(readingsOverlay)

        b.tvLegend.text = legend()
        b.btnMyLocation.setOnClickListener { centerOnMe() }
        b.btnCacheArea.setOnClickListener { cacheArea() }

        act.lifecycleScope.launch {
            act.db.readingDao().observeWithLocation().collect {
                readings = it
                readingsOverlay.readings = it
                refreshStatus()
                map.invalidate()
                maybeCenter()
            }
        }
        act.lifecycleScope.launch {
            act.db.spotDao().observeRanked().collect {
                spots = it
                updateMarkers()
                refreshStatus()
                maybeCenter()
            }
        }
    }

    private fun legend(): CharSequence {
        val sb = SpannableStringBuilder()
        fun add(color: Int, text: String) {
            val start = sb.length
            sb.append("●")
            sb.setSpan(ForegroundColorSpan(color), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append(" ").append(text).append("   ")
        }
        add(SignalColors.GOOD, "strong")
        add(SignalColors.FAIR, "fair")
        add(SignalColors.POOR, "weak")
        add(SignalColors.BAD, "very weak")
        add(SignalColors.NONE, "none")
        return sb
    }

    fun onShown() {
        shown = true
        if (myLoc == null && act.location.hasPermission()) {
            myLoc = MyLocationNewOverlay(GpsMyLocationProvider(act), map).also {
                it.enableMyLocation()
                map.overlays.add(it)
            }
        }
        maybeCenter()
        map.invalidate()
    }

    fun onResume() {
        map.onResume()
        myLoc?.enableMyLocation()
    }

    fun onPause() {
        myLoc?.disableMyLocation()
        map.onPause()
    }

    fun centerOn(lat: Double, lon: Double) {
        centered = true
        map.controller.setZoom(19.0)
        map.controller.animateTo(GeoPoint(lat, lon))
    }

    private fun centerOnMe() {
        val l = act.location.best
        if (l != null) {
            centerOn(l.latitude, l.longitude)
            return
        }
        val g = myLoc?.myLocation
        if (g != null) {
            centerOn(g.latitude, g.longitude)
            return
        }
        act.toast("No GPS fix yet. Step outside or near a window for a moment.")
    }

    private fun maybeCenter() {
        if (centered || !shown) return
        val r = readings.lastOrNull()
        val rLat = r?.lat
        val rLon = r?.lon
        if (rLat != null && rLon != null) { centerOn(rLat, rLon); return }
        val s = spots.firstOrNull { it.lat != null && it.lon != null }
        val sLat = s?.lat
        val sLon = s?.lon
        if (sLat != null && sLon != null) { centerOn(sLat, sLon); return }
        val l = act.location.best
        if (l != null) centerOn(l.latitude, l.longitude)
    }

    private fun updateMarkers() {
        map.overlays.removeAll(spotMarkers.toSet())
        spotMarkers.clear()
        spots.forEachIndexed { i, s ->
            val lat = s.lat ?: return@forEachIndexed
            val lon = s.lon ?: return@forEachIndexed
            val m = Marker(map).apply {
                position = GeoPoint(lat, lon)
                title = "#${i + 1}  ${s.name}"
                snippet = (s.avgDbm?.let { "$it dBm" } ?: "No service") + " · " + s.networkType +
                    (s.downMbps?.let { String.format(java.util.Locale.US, " · ↓%.1f Mbps", it) } ?: "")
                icon = dotDrawable(SignalColors.forBars(s.bars, s.avgDbm != null))
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            }
            spotMarkers.add(m)
            map.overlays.add(m)
        }
        map.invalidate()
    }

    private fun dotDrawable(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke(act.dp(3), Color.WHITE)
        setSize(act.dp(26), act.dp(26))
    }

    private fun refreshStatus() {
        val placed = spots.count { it.lat != null }
        b.tvMapStatus.text = if (readings.isEmpty() && placed == 0) {
            "Nothing plotted yet. Start a walk with GPS on – readings appear as coloured dots. " +
                "Tip: while you still have signal, zoom in on your work area and tap “Save map offline”."
        } else {
            "${readings.size} walk readings · $placed saved spots (big dots). Tap a big dot for details."
        }
    }

    private fun cacheArea() {
        val zoom = map.zoomLevelDouble.toInt()
        if (zoom < 14) {
            act.toast("Zoom in closer to your work area first, then tap Save map offline.")
            return
        }
        val tiles = TileCacher.plan(map.boundingBox, zoom)
        val mb = tiles.size * 20 / 1024.0
        MaterialAlertDialogBuilder(act)
            .setTitle("Save map for offline use?")
            .setMessage(
                String.format(
                    java.util.Locale.US,
                    "Downloads %d map tiles of the area on screen (about %.1f MB) so the map works where there's no signal. Do this while you have a good connection.",
                    tiles.size, mb,
                ),
            )
            .setPositiveButton("Download") { _, _ ->
                var job: Job? = null
                val (dialog, pb) = act.progressDialog("Saving map", indeterminate = false) { job?.cancel() }
                job = act.lifecycleScope.launch {
                    try {
                        val saved = TileCacher.download(tiles) { done, total ->
                            withContext(Dispatchers.Main) {
                                pb.progress.setProgressCompat(done * 100 / total, false)
                                pb.tvProgress.text = "$done of $total tiles"
                            }
                        }
                        dialog.dismiss()
                        map.invalidate()
                        act.toast("Map saved ($saved new tiles). It will now work here offline.")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        dialog.dismiss()
                        act.toast("Map download failed: ${e.message}")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
