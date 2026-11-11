package com.m9r1u5.signalfinder.signal

import kotlin.math.roundToInt

enum class Level { GOOD, FAIR, POOR, BAD }

/** Pure signal maths, kept free of Android classes so it can be unit-tested. */
object SignalMath {

    /** 0–5 bars from a dBm value for a given technology. */
    fun bars(tech: Tech, dbm: Int?): Int {
        if (dbm == null || tech == Tech.NONE) return 0
        val t = thresholds(tech)
        return when {
            dbm >= t[0] -> 5
            dbm >= t[1] -> 4
            dbm >= t[2] -> 3
            dbm >= t[3] -> 2
            dbm >= t[4] -> 1
            else -> 0
        }
    }

    private fun thresholds(tech: Tech): IntArray = when (tech) {
        Tech.LTE, Tech.NR -> intArrayOf(-85, -95, -105, -112, -120)
        Tech.WCDMA, Tech.TDSCDMA -> intArrayOf(-75, -85, -95, -103, -110)
        else -> intArrayOf(-70, -80, -90, -100, -107)
    }

    /** 0–100 score so 2G/3G/4G/5G readings can be ranked against each other. */
    fun score(tech: Tech, dbm: Int?): Int {
        if (dbm == null || tech == Tech.NONE) return 0
        val (lo, hi) = when (tech) {
            Tech.LTE, Tech.NR -> -130 to -75
            Tech.WCDMA, Tech.TDSCDMA -> -115 to -65
            else -> -110 to -60
        }
        val s = (dbm - lo).toDouble() / (hi - lo) * 100.0
        return s.roundToInt().coerceIn(0, 100)
    }

    fun level(bars: Int): Level = when {
        bars >= 4 -> Level.GOOD
        bars == 3 -> Level.FAIR
        bars == 2 -> Level.POOR
        else -> Level.BAD
    }

    fun qualityText(bars: Int, hasService: Boolean = true): String = when {
        !hasService -> "No service here"
        bars >= 5 -> "Excellent – calls & fast downloads"
        bars == 4 -> "Good – calls & downloads OK"
        bars == 3 -> "Fair – calls OK, downloads slower"
        bars == 2 -> "Weak – calls may break up"
        bars == 1 -> "Very weak – calls likely to drop"
        else -> "Barely any signal"
    }

    fun wifiBars(rssi: Int?): Int = when {
        rssi == null -> 0
        rssi >= -55 -> 5
        rssi >= -65 -> 4
        rssi >= -72 -> 3
        rssi >= -80 -> 2
        rssi >= -88 -> 1
        else -> 0
    }

    /** Milliseconds between Geiger clicks: stronger signal = faster clicking. */
    fun geigerIntervalMs(score: Int?): Long {
        if (score == null) return 3000L
        return (2000.0 - score.coerceIn(0, 100) * 18.5).toLong()
    }

    /** A reading only counts as a new best if it beats the old best by a clear margin. */
    fun isNewBest(candidateScore: Int, bestScore: Int?, margin: Int = 3): Boolean =
        bestScore == null || candidateScore >= bestScore + margin
}

/** Averages of a 10-second measurement at one spot. */
data class SpotSummary(
    val avgDbm: Int?,
    val minDbm: Int?,
    val maxDbm: Int?,
    val score: Int,
    val bars: Int,
    val tech: Tech,
    val networkLabel: String,
    val samples: Int,
    val rsrp: Int?,
    val rsrq: Int?,
    val sinr: Int?,
    val wifiRssi: Int?,
) {
    companion object {
        fun from(readings: List<SignalReading>): SpotSummary {
            if (readings.isEmpty()) {
                return SpotSummary(null, null, null, 0, 0, Tech.NONE, "No readings", 0, null, null, null, null)
            }
            val withSignal = readings.filter { it.dbm != null }
            // Most common technology among readings that had signal.
            val tech = withSignal.groupingBy { it.tech }.eachCount().maxByOrNull { it.value }?.key ?: Tech.NONE
            val label = readings.filter { it.tech == tech }.groupingBy { it.networkLabel }.eachCount()
                .maxByOrNull { it.value }?.key ?: "No service"
            val sameTech = withSignal.filter { it.tech == tech }.mapNotNull { it.dbm }
            val avg = sameTech.takeIf { it.isNotEmpty() }?.average()?.roundToInt()
            // Readings without service count as score 0, so patchy spots rank lower.
            val score = readings.map { it.score }.average().roundToInt()
            return SpotSummary(
                avgDbm = avg,
                minDbm = sameTech.minOrNull(),
                maxDbm = sameTech.maxOrNull(),
                score = score,
                bars = SignalMath.bars(tech, avg),
                tech = tech,
                networkLabel = label,
                samples = readings.size,
                rsrp = readings.mapNotNull { it.rsrp }.avgOrNull(),
                rsrq = readings.mapNotNull { it.rsrq }.avgOrNull(),
                sinr = readings.mapNotNull { it.sinr }.avgOrNull(),
                wifiRssi = readings.mapNotNull { it.wifiRssi }.avgOrNull(),
            )
        }

        private fun List<Int>.avgOrNull(): Int? = if (isEmpty()) null else average().roundToInt()
    }
}
