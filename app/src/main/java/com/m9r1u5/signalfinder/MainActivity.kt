package com.m9r1u5.signalfinder

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.m9r1u5.signalfinder.data.AppDatabase
import com.m9r1u5.signalfinder.databinding.ActivityMainBinding
import com.m9r1u5.signalfinder.databinding.DialogProgressBinding
import com.m9r1u5.signalfinder.signal.LocationTracker
import com.m9r1u5.signalfinder.signal.SignalMonitor
import com.m9r1u5.signalfinder.ui.LiveController
import com.m9r1u5.signalfinder.ui.MapController
import com.m9r1u5.signalfinder.ui.SpotActions
import com.m9r1u5.signalfinder.ui.SpotsController
import com.m9r1u5.signalfinder.ui.WalkController
import com.m9r1u5.signalfinder.util.CsvExporter
import com.m9r1u5.signalfinder.util.Updater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class SimInfo(val subId: Int, val label: String)

class MainActivity : AppCompatActivity() {

    lateinit var b: ActivityMainBinding
        private set
    lateinit var monitor: SignalMonitor
        private set
    lateinit var location: LocationTracker
        private set
    lateinit var spotActions: SpotActions
        private set

    private lateinit var live: LiveController
    private lateinit var walk: WalkController
    private lateinit var spotsCtl: SpotsController
    private lateinit var mapCtl: MapController

    val db: AppDatabase get() = (application as App).db
    val prefs: SharedPreferences by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    private var sims: List<SimInfo> = emptyList()
    private var pendingApk: File? = null

    private val corePermissions = arrayOf(
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onPermissionsChanged() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val s = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            v.setPadding(s.left, s.top, s.right, s.bottom)
            WindowInsetsCompat.CONSUMED
        }
        setSupportActionBar(b.toolbar)

        monitor = SignalMonitor(this, lifecycleScope)
        location = LocationTracker(this)
        spotActions = SpotActions(this)
        live = LiveController(this, b.live)
        walk = WalkController(this, b.walk)
        spotsCtl = SpotsController(this, b.spots)
        mapCtl = MapController(this, b.map)

        monitor.addListener { r ->
            live.update(r)
            walk.onReading(r)
        }

        b.bottomNav.setOnItemSelectedListener {
            showTab(it.itemId)
            true
        }
        showTab(R.id.nav_live)

        if (!hasCorePermissions()) explainPermissions()
        autoCheckUpdate()
    }

    override fun onStart() {
        super.onStart()
        monitor.start()
        location.start()
        refreshSims()
        live.showPermissionCard(!hasCorePermissions())
        val apk = pendingApk
        if (apk != null && Updater.canInstall(this)) {
            pendingApk = null
            Updater.install(this, apk)
        }
    }

    override fun onResume() {
        super.onResume()
        mapCtl.onResume()
    }

    override fun onPause() {
        mapCtl.onPause()
        super.onPause()
    }

    override fun onStop() {
        walk.stopWalk()
        monitor.stop()
        location.stop()
        super.onStop()
    }

    // ---------- tabs ----------

    private fun showTab(id: Int) {
        b.live.root.isVisible = id == R.id.nav_live
        b.walk.root.isVisible = id == R.id.nav_walk
        b.spots.root.isVisible = id == R.id.nav_spots
        b.map.root.isVisible = id == R.id.nav_map
        supportActionBar?.subtitle = when (id) {
            R.id.nav_walk -> "Walk to find the best signal"
            R.id.nav_spots -> "Saved spots, best first"
            R.id.nav_map -> "Signal map"
            else -> "Live signal"
        }
        if (id == R.id.nav_map) mapCtl.onShown()
    }

    fun showMapAt(lat: Double, lon: Double) {
        b.bottomNav.selectedItemId = R.id.nav_map
        mapCtl.centerOn(lat, lon)
    }

    // ---------- permissions ----------

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    fun hasCorePermissions(): Boolean =
        granted(Manifest.permission.READ_PHONE_STATE) && granted(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun explainPermissions() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Two permissions needed")
            .setMessage(
                "• Phone – to read signal strength, network type and choose between SIM cards.\n\n" +
                    "• Precise location – Android only shows cell tower details to apps with location " +
                    "access. It also puts your readings on the map.\n\n" +
                    "Everything stays on your phone. No accounts, no tracking.",
            )
            .setPositiveButton("Continue") { _, _ -> requestPermissions() }
            .setNegativeButton("Not now", null)
            .show()
    }

    fun requestPermissions() {
        val asked = prefs.getBoolean("asked_perms", false)
        val blocked = asked && corePermissions.any { !granted(it) && !shouldShowRequestPermissionRationale(it) }
        if (blocked) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Allow in Settings")
                .setMessage("Open Permissions and allow Phone, and Location set to “Allow only while using the app” with “Use precise location” switched on.")
                .setPositiveButton("Open Settings") { _, _ ->
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")),
                    )
                }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            prefs.edit().putBoolean("asked_perms", true).apply()
            permLauncher.launch(corePermissions)
        }
    }

    private fun onPermissionsChanged() {
        live.showPermissionCard(!hasCorePermissions())
        location.start()
        refreshSims()
        monitor.relisten()
    }

    // ---------- SIM cards ----------

    private fun activeSims(): List<SimInfo> {
        if (!granted(Manifest.permission.READ_PHONE_STATE)) return emptyList()
        val sm = getSystemService(SubscriptionManager::class.java) ?: return emptyList()
        val list = try {
            sm.activeSubscriptionInfoList
        } catch (e: SecurityException) {
            null
        } ?: return emptyList()
        return list.sortedBy { it.simSlotIndex }.map {
            val name = (it.carrierName ?: it.displayName ?: "").toString().ifBlank { "SIM" }
            SimInfo(it.subscriptionId, "SIM ${it.simSlotIndex + 1} · $name")
        }
    }

    fun simCount(): Int = sims.size

    private fun refreshSims() {
        sims = activeSims()
        if (sims.isNotEmpty()) {
            val saved = prefs.getInt("sub_id", -1)
            val def = SubscriptionManager.getDefaultDataSubscriptionId()
            val pick = sims.firstOrNull { it.subId == saved }
                ?: sims.firstOrNull { it.subId == def }
                ?: sims.first()
            monitor.selectSim(pick.subId, pick.label)
        }
        live.showSims(sims, monitor.subId)
    }

    fun selectSim(s: SimInfo) {
        prefs.edit().putInt("sub_id", s.subId).apply()
        monitor.selectSim(s.subId, s.label)
        toast("Now monitoring ${s.label}")
    }

    // ---------- menu ----------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_update -> checkForUpdate(manual = true)
            R.id.action_speed -> spotActions.runSpeedTest(null)
            R.id.action_export -> exportCsv()
            R.id.action_clear_log -> confirmClearLog()
            R.id.action_about -> showAbout()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun showAbout() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Signal Finder v${BuildConfig.VERSION_NAME}")
            .setMessage(
                "Walk around and find where your cellphone signal is best.\n\n" +
                    "dBm guide (4G/5G RSRP):\n" +
                    "  -85 or higher  Excellent\n" +
                    "  -95             Good\n" +
                    "  -105            Fair\n" +
                    "  -112            Weak\n" +
                    "  below -120      Almost none\n\n" +
                    "Closer to zero is better. All data stays on this phone.\n\n" +
                    "Updates: github.com/${BuildConfig.GITHUB_REPO}",
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun confirmClearLog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Clear walk log?")
            .setMessage("Deletes all logged walk readings (the dots on the map). Saved spots are kept.")
            .setPositiveButton("Clear") { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { db.readingDao().deleteAll() }
                    toast("Walk log cleared")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun exportCsv() {
        lifecycleScope.launch {
            try {
                val files = withContext(Dispatchers.IO) {
                    CsvExporter.write(cacheDir, db.spotDao().getRanked(), db.readingDao().getAll())
                }
                val uris = ArrayList(files.map { FileProvider.getUriForFile(this@MainActivity, "$packageName.fileprovider", it) })
                val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "text/csv"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                    putExtra(Intent.EXTRA_SUBJECT, "Signal Finder data")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    val clip = ClipData.newRawUri("signal data", uris[0])
                    uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
                    clipData = clip
                }
                startActivity(Intent.createChooser(intent, "Share signal data"))
            } catch (e: Exception) {
                toast("Export failed: ${e.message}")
            }
        }
    }

    // ---------- updates ----------

    private fun autoCheckUpdate() {
        val last = prefs.getLong("last_update_check", 0)
        if (System.currentTimeMillis() - last > 24L * 60 * 60 * 1000) checkForUpdate(manual = false)
    }

    private fun checkForUpdate(manual: Boolean) {
        lifecycleScope.launch {
            if (manual) toast("Checking for updates…")
            val rel = try {
                Updater.latest()
            } catch (e: Exception) {
                null
            }
            if (rel == null) {
                if (manual) toast("Couldn't reach GitHub. Check your data connection.")
                return@launch
            }
            prefs.edit().putLong("last_update_check", System.currentTimeMillis()).apply()
            if (!Updater.isNewer(rel)) {
                if (manual) {
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle("You're up to date")
                        .setMessage("Signal Finder v${BuildConfig.VERSION_NAME} is the latest version.")
                        .setPositiveButton("OK", null)
                        .show()
                }
                return@launch
            }
            if (manual) {
                confirmUpdate(rel)
            } else {
                Snackbar.make(b.root, "Update available: ${rel.tag}", Snackbar.LENGTH_LONG)
                    .setAnchorView(b.bottomNav)
                    .setAction("Update") { confirmUpdate(rel) }
                    .show()
            }
        }
    }

    private fun confirmUpdate(rel: Updater.Release) {
        val mb = rel.sizeBytes / 1024.0 / 1024.0
        MaterialAlertDialogBuilder(this)
            .setTitle("Update to ${rel.tag}?")
            .setMessage(String.format(java.util.Locale.US, "You have v%s. Download size %.1f MB.", BuildConfig.VERSION_NAME, mb))
            .setPositiveButton("Download & install") { _, _ -> downloadAndInstall(rel) }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun downloadAndInstall(rel: Updater.Release) {
        var job: Job? = null
        val (dialog, pb) = progressDialog("Downloading update", indeterminate = false) { job?.cancel() }
        job = lifecycleScope.launch {
            try {
                val file = Updater.download(this@MainActivity, rel) { pct ->
                    withContext(Dispatchers.Main) {
                        pb.progress.setProgressCompat(pct, true)
                        pb.tvProgress.text = "$pct%"
                    }
                }
                dialog.dismiss()
                installApk(file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                dialog.dismiss()
                toast("Download failed: ${e.message}")
            }
        }
    }

    private fun installApk(file: File) {
        if (Updater.canInstall(this)) {
            Updater.install(this, file)
            return
        }
        pendingApk = file
        MaterialAlertDialogBuilder(this)
            .setTitle("One-time permission")
            .setMessage("Android needs you to allow Signal Finder to install updates. Switch on “Allow from this source”, then press Back.")
            .setPositiveButton("Open Settings") { _, _ ->
                startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")),
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- helpers ----------

    fun progressDialog(
        title: String,
        indeterminate: Boolean,
        onCancel: () -> Unit,
    ): Pair<AlertDialog, DialogProgressBinding> {
        val pb = DialogProgressBinding.inflate(layoutInflater)
        pb.progress.isIndeterminate = indeterminate
        val d = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(pb.root)
            .setCancelable(false)
            .setNegativeButton("Cancel") { _, _ -> onCancel() }
            .show()
        return d to pb
    }

    fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
