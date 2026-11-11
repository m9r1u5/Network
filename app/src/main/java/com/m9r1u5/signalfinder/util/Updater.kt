package com.m9r1u5.signalfinder.util

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.m9r1u5.signalfinder.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/** Checks GitHub Releases for a newer APK, downloads it and opens the installer. */
object Updater {

    data class Release(val tag: String, val name: String, val notes: String, val apkUrl: String, val sizeBytes: Long)

    suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        val c = (URL("https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/releases/latest")
            .openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 15000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "SignalFinder")
        }
        try {
            if (c.responseCode != 200) return@withContext null
            val json = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val assets = json.optJSONArray("assets") ?: return@withContext null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val name = a.optString("name")
                if (name.endsWith(".apk", ignoreCase = true)) {
                    return@withContext Release(
                        tag = json.optString("tag_name"),
                        name = json.optString("name"),
                        notes = json.optString("body"),
                        apkUrl = a.optString("browser_download_url"),
                        sizeBytes = a.optLong("size"),
                    )
                }
            }
            null
        } finally {
            c.disconnect()
        }
    }

    fun isNewer(release: Release): Boolean = Versions.isNewer(release.tag, BuildConfig.VERSION_CODE)

    suspend fun download(context: Context, release: Release, onProgress: suspend (Int) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, "SignalFinder-update.apk")
            val c = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 30000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "SignalFinder")
            }
            try {
                val total = if (c.contentLengthLong > 0) c.contentLengthLong else release.sizeBytes
                c.inputStream.use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var lastPct = -1
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt()
                                if (pct != lastPct) {
                                    lastPct = pct
                                    onProgress(pct)
                                }
                            }
                        }
                    }
                }
            } finally {
                c.disconnect()
            }
            file
        }

    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
