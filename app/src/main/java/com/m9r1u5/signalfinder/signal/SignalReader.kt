package com.m9r1u5.signalfinder.signal

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.CellSignalStrengthCdma
import android.telephony.CellSignalStrengthGsm
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.CellSignalStrengthTdscdma
import android.telephony.CellSignalStrengthWcdma
import android.telephony.PhoneStateListener
import android.telephony.SignalStrength
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi

inline fun <T> safe(block: () -> T): T? = try {
    block()
} catch (e: Exception) {
    null
}

/** Reads signal strength, network type, tower and Wi-Fi details from Android. */
class SignalReader(context: Context) {

    private val app = context.applicationContext
    private val baseTm: TelephonyManager = app.getSystemService(TelephonyManager::class.java)
    private val wifiManager: WifiManager? = app.getSystemService(WifiManager::class.java)
    private val cm: ConnectivityManager? = app.getSystemService(ConnectivityManager::class.java)

    @Volatile private var pushedStrength: SignalStrength? = null
    @Volatile private var cachedCells: List<CellInfo>? = null
    private var lastCellRequest = 0L
    private var registeredTm: TelephonyManager? = null
    private var callback: Any? = null

    fun tmFor(subId: Int): TelephonyManager =
        if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID && subId >= 0) {
            safe { baseTm.createForSubscriptionId(subId) } ?: baseTm
        } else baseTm

    /** Ask the modem to push signal changes so readings are always fresh. */
    fun listen(subId: Int) {
        stopListening()
        val tm = tmFor(subId)
        registeredTm = tm
        pushedStrength = null
        cachedCells = null
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                callback = registerModern(tm)
            } else {
                callback = registerLegacy(tm)
            }
        } catch (e: Exception) {
            callback = null
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun registerModern(tm: TelephonyManager): Any {
        val cb = StrengthCallback { pushedStrength = it }
        tm.registerTelephonyCallback(app.mainExecutor, cb)
        return cb
    }

    @Suppress("DEPRECATION")
    private fun registerLegacy(tm: TelephonyManager): Any {
        val listener = object : PhoneStateListener() {
            override fun onSignalStrengthsChanged(signalStrength: SignalStrength?) {
                pushedStrength = signalStrength
            }
        }
        tm.listen(listener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS)
        return listener
    }

    fun stopListening() {
        val tm = registeredTm
        val cb = callback
        if (tm != null && cb != null) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) unregisterModern(tm, cb)
                else unregisterLegacy(tm, cb)
            } catch (e: Exception) {
                // ignore
            }
        }
        callback = null
        registeredTm = null
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun unregisterModern(tm: TelephonyManager, cb: Any) {
        if (cb is TelephonyCallback) tm.unregisterTelephonyCallback(cb)
        else unregisterLegacy(tm, cb)
    }

    @Suppress("DEPRECATION")
    private fun unregisterLegacy(tm: TelephonyManager, cb: Any) {
        if (cb is PhoneStateListener) tm.listen(cb, PhoneStateListener.LISTEN_NONE)
    }

    fun read(subId: Int, simLabel: String): SignalReading {
        val tm = registeredTm ?: tmFor(subId)
        refreshCells(tm)

        val ss: SignalStrength? = pushedStrength ?: safe { tm.signalStrength }
        val strengths = ss?.cellSignalStrengths ?: emptyList()

        val lte = strengths.filterIsInstance<CellSignalStrengthLte>().firstOrNull { validDbm(it.rsrp) }
        val nr = strengths.filterIsInstance<CellSignalStrengthNr>()
            .firstOrNull { validDbm(it.ssRsrp) || validDbm(it.csiRsrp) }
        val wcdma = strengths.filterIsInstance<CellSignalStrengthWcdma>().firstOrNull { validDbm(it.dbm) }
        val tds = strengths.filterIsInstance<CellSignalStrengthTdscdma>().firstOrNull { validDbm(it.dbm) }
        val gsm = strengths.filterIsInstance<CellSignalStrengthGsm>().firstOrNull { validDbm(it.dbm) }
        val cdma = strengths.filterIsInstance<CellSignalStrengthCdma>().firstOrNull { validDbm(it.dbm) }

        val nrRsrp = nr?.let { if (validDbm(it.ssRsrp)) it.ssRsrp else it.csiRsrp.takeIf(::validDbm) }
        val nrRsrq = nr?.let { if (valid(it.ssRsrq)) it.ssRsrq else it.csiRsrq.takeIf(::valid) }
        val nrSinr = nr?.let { if (valid(it.ssSinr)) it.ssSinr else it.csiSinr.takeIf(::valid) }

        val tech: Tech
        val dbm: Int?
        when {
            lte != null -> { tech = Tech.LTE; dbm = lte.rsrp }
            nrRsrp != null -> { tech = Tech.NR; dbm = nrRsrp }
            wcdma != null -> { tech = Tech.WCDMA; dbm = wcdma.dbm }
            tds != null -> { tech = Tech.TDSCDMA; dbm = tds.dbm }
            gsm != null -> { tech = Tech.GSM; dbm = gsm.dbm }
            cdma != null -> { tech = Tech.CDMA; dbm = cdma.dbm }
            else -> { tech = Tech.NONE; dbm = null }
        }

        val dataType = safe { tm.dataNetworkType } ?: TelephonyManager.NETWORK_TYPE_UNKNOWN
        val label = networkLabel(tech, dataType, nrRsrp != null)

        val carrier = safe { tm.networkOperatorName }?.takeIf { it.isNotBlank() }
            ?: safe { tm.simOperatorName }?.takeIf { it.isNotBlank() }
            ?: "Unknown network"

        val (wifiRssi, wifiSsid) = wifi()

        return SignalReading(
            timestamp = System.currentTimeMillis(),
            tech = tech,
            networkLabel = label,
            dbm = dbm,
            bars = SignalMath.bars(tech, dbm),
            score = SignalMath.score(tech, dbm),
            rsrp = lte?.rsrp,
            rsrq = lte?.rsrq?.takeIf(::valid),
            sinr = lte?.rssnr?.takeIf(::valid),
            nrRsrp = nrRsrp,
            nrRsrq = nrRsrq,
            nrSinr = nrSinr,
            carrier = carrier,
            cellId = cellDescription(tm),
            wifiRssi = wifiRssi,
            wifiSsid = wifiSsid,
            simLabel = simLabel,
        )
    }

    private fun networkLabel(tech: Tech, dataType: Int, hasNr: Boolean): String = when {
        tech == Tech.NONE -> "No service"
        tech == Tech.NR || dataType == TelephonyManager.NETWORK_TYPE_NR -> "5G"
        tech == Tech.LTE && hasNr -> "5G NSA (4G + 5G)"
        tech == Tech.LTE -> "4G LTE"
        tech == Tech.WCDMA || tech == Tech.TDSCDMA -> when (dataType) {
            TelephonyManager.NETWORK_TYPE_HSPAP -> "3G HSPA+"
            TelephonyManager.NETWORK_TYPE_HSPA,
            TelephonyManager.NETWORK_TYPE_HSDPA,
            TelephonyManager.NETWORK_TYPE_HSUPA -> "3G HSPA"
            else -> "3G"
        }
        dataType == TelephonyManager.NETWORK_TYPE_EDGE -> "2G EDGE"
        else -> "2G"
    }

    /** Ask for fresh tower info every few seconds (needs precise location permission). */
    private fun refreshCells(tm: TelephonyManager) {
        val now = System.currentTimeMillis()
        if (now - lastCellRequest < 3000) return
        lastCellRequest = now
        try {
            tm.requestCellInfoUpdate(app.mainExecutor, object : TelephonyManager.CellInfoCallback() {
                override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                    cachedCells = cellInfo
                }
            })
        } catch (e: Exception) {
            // no permission or not supported
        }
    }

    private fun cellDescription(tm: TelephonyManager): String {
        val cells = cachedCells ?: safe { tm.allCellInfo }
            ?: return "Needs precise location permission"
        val reg = cells.firstOrNull { it.isRegistered } ?: return "Not registered on a tower"
        return when (reg) {
            is CellInfoLte -> {
                val id = reg.cellIdentity
                buildString {
                    append("4G  CI ").append(v(id.ci))
                    append("  TAC ").append(v(id.tac))
                    append("\n    PCI ").append(v(id.pci))
                    append("  EARFCN ").append(v(id.earfcn))
                    val enb = if (valid(id.ci)) (id.ci shr 8).toString() else "?"
                    append("\n    eNB ").append(enb)
                }
            }
            is CellInfoNr -> {
                val id = reg.cellIdentity as CellIdentityNr
                val nci = if (id.nci != CellInfo.UNAVAILABLE_LONG) id.nci.toString() else "?"
                "5G  NCI $nci  TAC ${v(id.tac)}\n    PCI ${v(id.pci)}  ARFCN ${v(id.nrarfcn)}"
            }
            is CellInfoWcdma -> {
                val id = reg.cellIdentity
                "3G  CID ${v(id.cid)}  LAC ${v(id.lac)}\n    PSC ${v(id.psc)}"
            }
            is CellInfoGsm -> {
                val id = reg.cellIdentity
                "2G  CID ${v(id.cid)}  LAC ${v(id.lac)}"
            }
            else -> "Tower details not available"
        }
    }

    @Suppress("DEPRECATION")
    private fun wifi(): Pair<Int?, String?> {
        var rssi: Int? = null
        val networks = safe { cm?.allNetworks } ?: emptyArray()
        for (n in networks) {
            val caps = safe { cm?.getNetworkCapabilities(n) } ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                val s = caps.signalStrength
                if (s in -126..-1) rssi = s
            }
        }
        val info = safe { wifiManager?.connectionInfo }
        if (rssi == null && info != null && info.networkId != -1 && info.rssi in -126..-1) {
            rssi = info.rssi
        }
        val ssid = info?.ssid?.trim('"')
            ?.takeIf { rssi != null && it.isNotBlank() && it != "<unknown ssid>" }
        return rssi to ssid
    }

    private fun valid(v: Int): Boolean = v != CellInfo.UNAVAILABLE && v != Int.MAX_VALUE
    private fun validDbm(v: Int): Boolean = valid(v) && v in -160..-20
    private fun v(x: Int): String = if (valid(x)) x.toString() else "?"
}

@RequiresApi(Build.VERSION_CODES.S)
private class StrengthCallback(
    private val onStrength: (SignalStrength) -> Unit,
) : TelephonyCallback(), TelephonyCallback.SignalStrengthsListener {
    override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
        onStrength(signalStrength)
    }
}
