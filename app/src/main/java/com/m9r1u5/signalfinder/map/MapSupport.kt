package com.m9r1u5.signalfinder.map

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import com.m9r1u5.signalfinder.BuildConfig
import com.m9r1u5.signalfinder.data.Reading
import com.m9r1u5.signalfinder.ui.SignalColors
import com.m9r1u5.signalfinder.util.TileMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.modules.SqlTileWriter
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

object MapSetup {
    fun init(context: Context) {
        val c = Configuration.getInstance()
        c.load(context, context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
        c.userAgentValue = "SignalFinder/${BuildConfig.VERSION_NAME} (${BuildConfig.APPLICATION_ID})"
        val base = File(context.filesDir, "osmdroid").apply { mkdirs() }
        c.osmdroidBasePath = base
        c.osmdroidTileCache = File(base, "tiles").apply { mkdirs() }
        c.tileFileSystemCacheMaxBytes = 300L * 1024 * 1024
        c.tileFileSystemCacheTrimBytes = 250L * 1024 * 1024
        // Keep saved tiles usable offline for a year.
        c.expirationOverrideDuration = 365L * 24 * 60 * 60 * 1000
    }
}

/** Draws each logged reading as a coloured dot. */
class ReadingsOverlay(context: Context) : Overlay() {
    var readings: List<Reading> = emptyList()
    private val density = context.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = Color.argb(200, 255, 255, 255)
    }
    private val pt = Point()

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val proj = mapView.projection
        val r = 7f * density
        for (rd in readings) {
            val lat = rd.lat ?: continue
            val lon = rd.lon ?: continue
            proj.toPixels(GeoPoint(lat, lon), pt)
            paint.color = SignalColors.forBars(rd.bars, rd.dbm != null)
            paint.alpha = 210
            canvas.drawCircle(pt.x.toFloat(), pt.y.toFloat(), r, paint)
            canvas.drawCircle(pt.x.toFloat(), pt.y.toFloat(), r, stroke)
        }
    }
}

/** Saves OpenStreetMap tiles for the visible area so the map works without signal. */
object TileCacher {
    const val MAX_TILES = 600

    fun plan(box: BoundingBox, zoom: Int): List<TileMath.Tile> {
        val minZ = (zoom - 2).coerceIn(3, 19)
        val maxZ = (zoom + 2).coerceIn(minZ, 19)
        return TileMath.plan(box.latNorth, box.latSouth, box.lonEast, box.lonWest, minZ, maxZ, MAX_TILES)
    }

    /** Returns number of tiles saved (already-saved tiles are skipped). */
    suspend fun download(tiles: List<TileMath.Tile>, onProgress: suspend (Int, Int) -> Unit): Int =
        withContext(Dispatchers.IO) {
            val source = TileSourceFactory.MAPNIK
            val writer = SqlTileWriter()
            var saved = 0
            try {
                tiles.forEachIndexed { i, t ->
                    coroutineContext.ensureActive()
                    val index = MapTileIndex.getTileIndex(t.z, t.x, t.y)
                    if (!writer.exists(source, index)) {
                        val bytes = fetch("https://tile.openstreetmap.org/${t.z}/${t.x}/${t.y}.png")
                        if (bytes != null) {
                            val expires = System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000
                            if (writer.saveFile(source, index, ByteArrayInputStream(bytes), expires)) saved++
                        }
                    }
                    onProgress(i + 1, tiles.size)
                }
            } finally {
                writer.onDetach()
            }
            saved
        }

    private fun fetch(url: String): ByteArray? {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 15000
            setRequestProperty("User-Agent", Configuration.getInstance().userAgentValue)
        }
        return try {
            if (c.responseCode == 200) c.inputStream.use { it.readBytes() } else null
        } catch (e: Exception) {
            null
        } finally {
            c.disconnect()
        }
    }
}
