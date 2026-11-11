package com.m9r1u5.signalfinder.util

import com.m9r1u5.signalfinder.data.Reading
import com.m9r1u5.signalfinder.data.Spot
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CsvExporter {

    private fun time(t: Long?): String =
        if (t == null) "" else SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(t))

    fun write(cacheDir: File, spots: List<Spot>, readings: List<Reading>): List<File> {
        val dir = File(cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())

        val spotsFile = File(dir, "signal_spots_$stamp.csv")
        spotsFile.bufferedWriter().use { w ->
            w.write(
                Csv.row(
                    listOf(
                        "rank", "name", "avg_dbm", "min_dbm", "max_dbm", "score_0_100", "bars", "network",
                        "carrier", "sim", "cell", "rsrp", "rsrq", "sinr", "wifi_rssi", "latitude", "longitude",
                        "gps_accuracy_m", "samples", "measured_at", "download_mbps", "upload_mbps", "ping_ms",
                        "speed_tested_at",
                    ),
                ),
            )
            w.newLine()
            spots.forEachIndexed { i, s ->
                w.write(
                    Csv.row(
                        listOf(
                            i + 1, s.name, s.avgDbm, s.minDbm, s.maxDbm, s.score, s.bars, s.networkType,
                            s.carrier, s.simLabel, s.cellId.replace("\n", " "), s.rsrp, s.rsrq, s.sinr, s.wifiRssi,
                            s.lat, s.lon, s.accuracyM?.toInt(), s.samples, time(s.timestamp),
                            s.downMbps?.let { "%.2f".format(Locale.US, it) },
                            s.upMbps?.let { "%.2f".format(Locale.US, it) },
                            s.pingMs, time(s.speedTimestamp),
                        ),
                    ),
                )
                w.newLine()
            }
        }

        val readingsFile = File(dir, "signal_walk_log_$stamp.csv")
        readingsFile.bufferedWriter().use { w ->
            w.write(
                Csv.row(
                    listOf(
                        "time", "dbm", "score_0_100", "bars", "network", "carrier", "sim", "cell",
                        "latitude", "longitude", "gps_accuracy_m",
                    ),
                ),
            )
            w.newLine()
            readings.forEach { r ->
                w.write(
                    Csv.row(
                        listOf(
                            time(r.timestamp), r.dbm, r.score, r.bars, r.networkType, r.carrier, r.simLabel,
                            r.cellId.replace("\n", " "), r.lat, r.lon, r.accuracyM?.toInt(),
                        ),
                    ),
                )
                w.newLine()
            }
        }
        return listOf(spotsFile, readingsFile)
    }
}
