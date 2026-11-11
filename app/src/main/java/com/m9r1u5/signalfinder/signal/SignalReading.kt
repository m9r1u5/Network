package com.m9r1u5.signalfinder.signal

/** Radio technology that the main reading comes from. */
enum class Tech(val generation: String) {
    NR("5G"), LTE("4G"), WCDMA("3G"), TDSCDMA("3G"), GSM("2G"), CDMA("2G"), NONE("—");

    /** Name of the measurement shown as the main dBm figure. */
    val metric: String
        get() = when (this) {
            NR, LTE -> "RSRP"
            WCDMA -> "RSCP"
            NONE -> ""
            else -> "RSSI"
        }
}

/** One snapshot of the phone's signal, taken roughly every second. */
data class SignalReading(
    val timestamp: Long,
    val tech: Tech,
    val networkLabel: String,
    /** Main signal level in dBm (RSRP for 4G/5G, RSCP for 3G, RSSI for 2G). Null = no service. */
    val dbm: Int?,
    val bars: Int,
    /** 0–100, comparable across 2G/3G/4G/5G. */
    val score: Int,
    val rsrp: Int? = null,
    val rsrq: Int? = null,
    val sinr: Int? = null,
    val nrRsrp: Int? = null,
    val nrRsrq: Int? = null,
    val nrSinr: Int? = null,
    val carrier: String = "",
    val cellId: String = "",
    val wifiRssi: Int? = null,
    val wifiSsid: String? = null,
    val simLabel: String = "",
) {
    val hasService: Boolean get() = dbm != null
}
