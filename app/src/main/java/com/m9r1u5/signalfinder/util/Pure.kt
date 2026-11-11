package com.m9r1u5.signalfinder.util

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/** CSV helpers (pure, unit-tested). */
object Csv {
    fun cell(v: Any?): String {
        if (v == null) return ""
        val s = v.toString()
        return if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + s.replace("\"", "\"\"") + "\""
        } else s
    }

    fun row(values: List<Any?>): String = values.joinToString(",") { cell(it) }
}

/** Version numbers come from the release tag, e.g. "v1.0.17" → 17. */
object Versions {
    fun codeFromTag(tag: String?): Int? {
        if (tag.isNullOrBlank()) return null
        return tag.trim().removePrefix("v").removePrefix("V").split('.').lastOrNull()?.toIntOrNull()
    }

    fun isNewer(remoteTag: String?, currentCode: Int): Boolean {
        val remote = codeFromTag(remoteTag) ?: return false
        return remote > currentCode
    }
}

/** Slippy-map tile maths for saving OpenStreetMap tiles offline. */
object TileMath {
    fun x(lon: Double, zoom: Int): Int {
        val n = 1 shl zoom
        return floor((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
    }

    fun y(lat: Double, zoom: Int): Int {
        val n = 1 shl zoom
        val latRad = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
        val v = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n
        return floor(v).toInt().coerceIn(0, n - 1)
    }

    data class Tile(val z: Int, val x: Int, val y: Int)

    fun tiles(north: Double, south: Double, east: Double, west: Double, zoom: Int): List<Tile> {
        val x1 = x(west, zoom)
        val x2 = x(east, zoom)
        val y1 = y(north, zoom)
        val y2 = y(south, zoom)
        val out = ArrayList<Tile>()
        for (tx in minOf(x1, x2)..maxOf(x1, x2)) {
            for (ty in minOf(y1, y2)..maxOf(y1, y2)) out.add(Tile(zoom, tx, ty))
        }
        return out
    }

    /** All tiles for a zoom range, dropping the deepest zooms until it fits under maxTiles. */
    fun plan(
        north: Double, south: Double, east: Double, west: Double,
        minZoom: Int, maxZoom: Int, maxTiles: Int,
    ): List<Tile> {
        var top = maxZoom
        while (top >= minZoom) {
            val all = (minZoom..top).flatMap { tiles(north, south, east, west, it) }
            if (all.size <= maxTiles) return all
            top--
        }
        return tiles(north, south, east, west, minZoom).take(maxTiles)
    }
}
