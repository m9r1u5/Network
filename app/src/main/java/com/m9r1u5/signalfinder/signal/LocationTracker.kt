package com.m9r1u5.signalfinder.signal

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat

/** Keeps the best recent GPS / network location fix. */
class LocationTracker(private val context: Context) {

    private val lm: LocationManager? = context.getSystemService(LocationManager::class.java)
    private var running = false

    var best: Location? = null
        private set

    // All four methods are overridden: on Android 10 they are not default methods.
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) { accept(location) }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    private fun accept(loc: Location) {
        val cur = best
        if (cur == null || loc.time - cur.time > 10_000 || loc.accuracy <= cur.accuracy) {
            best = loc
        }
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun start() {
        if (running || lm == null || !hasPermission()) return
        running = true
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                if (lm.allProviders.contains(p)) {
                    lm.getLastKnownLocation(p)?.let { accept(it) }
                    lm.requestLocationUpdates(p, 1000L, 0f, listener, Looper.getMainLooper())
                }
            } catch (e: SecurityException) {
                // permission withdrawn
            } catch (e: IllegalArgumentException) {
                // provider missing
            }
        }
    }

    fun stop() {
        if (!running) return
        running = false
        try { lm?.removeUpdates(listener) } catch (e: Exception) { }
    }

    /** A location only if it's recent and accurate enough to be useful. */
    fun fresh(maxAgeMs: Long = 30_000, maxAccuracyM: Float = 60f): Location? {
        val l = best ?: return null
        val age = System.currentTimeMillis() - l.time
        return if (age <= maxAgeMs && l.accuracy <= maxAccuracyM) l else null
    }

    fun describe(): String {
        if (!hasPermission()) return "Location permission not granted"
        val l = best ?: return "Searching for GPS… (often weak indoors)"
        val age = (System.currentTimeMillis() - l.time) / 1000
        val acc = l.accuracy.toInt()
        return when {
            age > 60 -> "Last fix ${age}s ago (±$acc m) – go near a window/outside"
            else -> "±$acc m accuracy"
        }
    }
}
