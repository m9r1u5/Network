package com.m9r1u5.signalfinder.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/** Quick download/upload/ping test against Cloudflare's public speed-test servers. */
object SpeedTest {

    data class Result(val downMbps: Double, val upMbps: Double, val pingMs: Int)

    private const val BASE = "https://speed.cloudflare.com"
    private const val TEST_SECONDS = 7.0

    suspend fun run(onProgress: suspend (String) -> Unit): Result = withContext(Dispatchers.IO) {
        onProgress("Measuring ping…")
        val ping = ping()

        onProgress("Testing download…")
        val down = download()

        onProgress("Testing upload…")
        val up = upload()

        Result(down, up, ping)
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 10000
            setRequestProperty("User-Agent", "SignalFinder")
        }

    private suspend fun ping(): Int {
        var best = Long.MAX_VALUE
        repeat(4) {
            coroutineContext.ensureActive()
            val c = open("$BASE/__down?bytes=0")
            val t0 = System.nanoTime()
            try {
                c.inputStream.use { it.readBytes() }
            } finally {
                c.disconnect()
            }
            val ms = (System.nanoTime() - t0) / 1_000_000
            if (ms < best) best = ms
        }
        return best.toInt()
    }

    private suspend fun download(): Double {
        val c = open("$BASE/__down?bytes=100000000")
        val buf = ByteArray(64 * 1024)
        var total = 0L
        val t0 = System.nanoTime()
        try {
            c.inputStream.use { input ->
                while (true) {
                    coroutineContext.ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if ((System.nanoTime() - t0) / 1e9 >= TEST_SECONDS) break
                }
            }
        } finally {
            c.disconnect()
        }
        val secs = (System.nanoTime() - t0) / 1e9
        return if (secs <= 0) 0.0 else total * 8 / secs / 1_000_000
    }

    private suspend fun upload(): Double {
        val c = open("$BASE/__up")
        c.requestMethod = "POST"
        c.doOutput = true
        c.setChunkedStreamingMode(64 * 1024)
        c.setRequestProperty("Content-Type", "application/octet-stream")
        val chunk = ByteArray(64 * 1024) { (it % 251).toByte() }
        val maxBytes = 40L * 1024 * 1024
        var total = 0L
        val t0 = System.nanoTime()
        try {
            c.outputStream.use { out ->
                while (total < maxBytes && (System.nanoTime() - t0) / 1e9 < TEST_SECONDS) {
                    coroutineContext.ensureActive()
                    out.write(chunk)
                    total += chunk.size
                }
                out.flush()
            }
            val secs = (System.nanoTime() - t0) / 1e9
            c.responseCode // finish the request
            return if (secs <= 0) 0.0 else total * 8 / secs / 1_000_000
        } finally {
            c.disconnect()
        }
    }
}
