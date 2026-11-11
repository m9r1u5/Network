package com.m9r1u5.signalfinder.signal

import android.content.Context
import android.telephony.SubscriptionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Takes a reading every second and tells listeners. Keeps the last ~2.5 minutes. */
class SignalMonitor(context: Context, private val scope: CoroutineScope) {

    private val reader = SignalReader(context)
    private val listeners = mutableListOf<(SignalReading) -> Unit>()
    private var job: Job? = null

    var subId: Int = SubscriptionManager.getDefaultDataSubscriptionId()
        private set
    var simLabel: String = "SIM"
        private set
    var latest: SignalReading? = null
        private set
    val history = ArrayDeque<SignalReading>()

    fun addListener(l: (SignalReading) -> Unit) { listeners.add(l) }

    fun selectSim(newSubId: Int, label: String) {
        val changed = newSubId != subId
        subId = newSubId
        simLabel = label
        if (changed) history.clear()
        if (job != null) reader.listen(subId)
    }

    /** Re-register with the modem, e.g. after permissions were granted. */
    fun relisten() {
        if (job != null) reader.listen(subId)
    }

    fun start() {
        if (job != null) return
        reader.listen(subId)
        job = scope.launch {
            while (isActive) {
                val id = subId
                val label = simLabel
                val r = withContext(Dispatchers.Default) {
                    try {
                        reader.read(id, label)
                    } catch (e: Exception) {
                        SignalReading(System.currentTimeMillis(), Tech.NONE, "No service", null, 0, 0, simLabel = label)
                    }
                }
                latest = r
                history.addLast(r)
                while (history.size > 150) history.removeFirst()
                listeners.toList().forEach { it(r) }
                delay(1000)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        reader.stopListening()
    }

    fun readingsSince(t: Long): List<SignalReading> = history.filter { it.timestamp >= t }
}
