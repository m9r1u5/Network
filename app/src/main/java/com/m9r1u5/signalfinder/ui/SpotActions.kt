package com.m9r1u5.signalfinder.ui

import android.text.InputType
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.m9r1u5.signalfinder.MainActivity
import com.m9r1u5.signalfinder.data.Spot
import com.m9r1u5.signalfinder.signal.SpotSummary
import com.m9r1u5.signalfinder.util.SpeedTest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Saving, re-testing, renaming, deleting spots and running speed tests. */
class SpotActions(private val act: MainActivity) {

    private val dao get() = act.db.spotDao()

    fun promptNewSpot(after: ((Long) -> Unit)? = null) {
        act.lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) { dao.getRanked().size }
            val default = "Spot ${count + 1}"
            nameDialog("Name this spot", default, "Measure 10 s") { name -> sampleAndSave(name, null, after) }
        }
    }

    private fun nameDialog(title: String, initial: String, okLabel: String, onOk: (String) -> Unit) {
        val input = EditText(act).apply {
            setText(initial)
            selectAll()
            textSize = 20f
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            hint = "e.g. Workshop door"
        }
        val container = FrameLayout(act).apply {
            setPadding(act.dp(24), act.dp(8), act.dp(24), 0)
            addView(input)
        }
        val d = MaterialAlertDialogBuilder(act)
            .setTitle(title)
            .setView(container)
            .setPositiveButton(okLabel) { _, _ ->
                onOk(input.text.toString().trim().ifBlank { initial })
            }
            .setNegativeButton("Cancel", null)
            .show()
        input.requestFocus()
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    }

    /** Takes 10 seconds of readings and saves the averages. */
    fun sampleAndSave(name: String, existing: Spot?, after: ((Long) -> Unit)?) {
        var job: Job? = null
        val (dialog, pb) = act.progressDialog("Measuring “$name”", indeterminate = false) { job?.cancel() }
        job = act.lifecycleScope.launch {
            val start = System.currentTimeMillis()
            for (i in 1..SAMPLE_SECONDS) {
                delay(1000)
                val cur = act.monitor.latest
                pb.progress.setProgressCompat(i * 100 / SAMPLE_SECONDS, true)
                pb.tvProgress.text = "Hold the phone as you normally would…\n" +
                    "${SAMPLE_SECONDS - i}s left · now ${cur?.dbm?.let { "$it dBm" } ?: "no service"}"
            }
            val readings = act.monitor.readingsSince(start - 500)
            val sum = SpotSummary.from(readings)
            val last = readings.lastOrNull { it.dbm != null } ?: readings.lastOrNull() ?: act.monitor.latest
            val loc = act.location.fresh()
            val spot = Spot(
                id = existing?.id ?: 0,
                name = name,
                avgDbm = sum.avgDbm,
                minDbm = sum.minDbm,
                maxDbm = sum.maxDbm,
                score = sum.score,
                bars = sum.bars,
                tech = sum.tech.name,
                networkType = sum.networkLabel,
                carrier = last?.carrier ?: "",
                cellId = last?.cellId ?: "",
                rsrp = sum.rsrp,
                rsrq = sum.rsrq,
                sinr = sum.sinr,
                wifiRssi = sum.wifiRssi,
                lat = loc?.latitude ?: existing?.lat,
                lon = loc?.longitude ?: existing?.lon,
                accuracyM = loc?.accuracy ?: existing?.accuracyM,
                simLabel = act.monitor.simLabel,
                samples = sum.samples,
                timestamp = System.currentTimeMillis(),
                downMbps = existing?.downMbps,
                upMbps = existing?.upMbps,
                pingMs = existing?.pingMs,
                speedTimestamp = existing?.speedTimestamp,
            )
            val id = withContext(Dispatchers.IO) {
                if (existing != null) {
                    dao.update(spot)
                    existing.id
                } else dao.insert(spot)
            }
            dialog.dismiss()
            act.toast("Saved “$name”: " + (sum.avgDbm?.let { "$it dBm average" } ?: "no service"))
            after?.invoke(id)
        }
    }

    fun showSpotMenu(spot: Spot) {
        val items = arrayOf("Re-test (10 s)", "Speed test & save to this spot", "Rename", "Show on map", "Delete")
        MaterialAlertDialogBuilder(act)
            .setTitle(spot.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> sampleAndSave(spot.name, spot, null)
                    1 -> runSpeedTest(spot)
                    2 -> rename(spot)
                    3 -> showOnMap(spot)
                    4 -> confirmDelete(spot)
                }
            }
            .show()
    }

    private fun rename(spot: Spot) {
        nameDialog("Rename spot", spot.name, "Save") { newName ->
            act.lifecycleScope.launch(Dispatchers.IO) { dao.update(spot.copy(name = newName)) }
        }
    }

    private fun showOnMap(spot: Spot) {
        val lat = spot.lat
        val lon = spot.lon
        if (lat == null || lon == null) {
            act.toast("No GPS position was saved for this spot (GPS is often weak indoors).")
        } else {
            act.showMapAt(lat, lon)
        }
    }

    private fun confirmDelete(spot: Spot) {
        MaterialAlertDialogBuilder(act)
            .setTitle("Delete “${spot.name}”?")
            .setPositiveButton("Delete") { _, _ ->
                act.lifecycleScope.launch(Dispatchers.IO) { dao.delete(spot) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Runs a speed test. If [target] is given, the result is saved to that spot. */
    fun runSpeedTest(target: Spot?) {
        var job: Job? = null
        val (dialog, pb) = act.progressDialog("Speed test", indeterminate = true) { job?.cancel() }
        pb.tvProgress.text = "Starting…"
        job = act.lifecycleScope.launch {
            try {
                val res = SpeedTest.run { msg -> withContext(Dispatchers.Main) { pb.tvProgress.text = msg } }
                dialog.dismiss()
                if (target != null) {
                    saveSpeed(target.id, res)
                    showResult(res, savedTo = target.name)
                } else {
                    showResult(res, savedTo = null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                dialog.dismiss()
                MaterialAlertDialogBuilder(act)
                    .setTitle("Speed test failed")
                    .setMessage(
                        "There's no working data connection here, or the test server couldn't be reached.\n\n" +
                            "(${e.javaClass.simpleName})",
                    )
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    private fun verdict(down: Double): String = when {
        down >= 20 -> "Great for downloads and video calls."
        down >= 5 -> "Fine for downloads, streaming and calls."
        down >= 1 -> "OK for WhatsApp, email and voice calls."
        else -> "Very slow – messages only."
    }

    private fun showResult(res: SpeedTest.Result, savedTo: String?) {
        val msg = String.format(
            Locale.US,
            "Download:  %.1f Mbps\nUpload:    %.1f Mbps\nPing:      %d ms\n\n%s",
            res.downMbps, res.upMbps, res.pingMs, verdict(res.downMbps),
        ) + (savedTo?.let { "\n\nSaved to “$it”." } ?: "")
        val b = MaterialAlertDialogBuilder(act)
            .setTitle("Speed test result")
            .setMessage(msg)
            .setPositiveButton("OK", null)
        if (savedTo == null) {
            b.setNeutralButton("Save to a spot…") { _, _ -> chooseSpotFor(res) }
        }
        b.show()
    }

    private fun chooseSpotFor(res: SpeedTest.Result) {
        act.lifecycleScope.launch {
            val spots = withContext(Dispatchers.IO) { dao.getRanked() }
            val names = (listOf("➕ New spot where I'm standing") + spots.map { it.name }).toTypedArray()
            MaterialAlertDialogBuilder(act)
                .setTitle("Save speed result to")
                .setItems(names) { _, which ->
                    if (which == 0) {
                        promptNewSpot { id -> saveSpeed(id, res) }
                    } else {
                        saveSpeed(spots[which - 1].id, res)
                        act.toast("Saved to “${spots[which - 1].name}”")
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun saveSpeed(spotId: Long, res: SpeedTest.Result) {
        act.lifecycleScope.launch(Dispatchers.IO) {
            dao.get(spotId)?.let {
                dao.update(
                    it.copy(
                        downMbps = res.downMbps,
                        upMbps = res.upMbps,
                        pingMs = res.pingMs,
                        speedTimestamp = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

    companion object {
        const val SAMPLE_SECONDS = 10
    }
}
