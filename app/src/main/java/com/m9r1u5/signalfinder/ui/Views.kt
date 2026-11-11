package com.m9r1u5.signalfinder.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.m9r1u5.signalfinder.signal.Level
import com.m9r1u5.signalfinder.signal.SignalMath
import com.m9r1u5.signalfinder.signal.SignalReading

object SignalColors {
    val GOOD = Color.parseColor("#2E9E44")
    val FAIR = Color.parseColor("#E0A800")
    val POOR = Color.parseColor("#EF6C00")
    val BAD = Color.parseColor("#D32F2F")
    val NONE = Color.parseColor("#7A7A7A")

    fun forBars(bars: Int, hasService: Boolean = true): Int {
        if (!hasService) return NONE
        return when (SignalMath.level(bars)) {
            Level.GOOD -> GOOD
            Level.FAIR -> FAIR
            Level.POOR -> POOR
            Level.BAD -> BAD
        }
    }
}

/** Five rising bars, coloured by strength. */
class BarsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var bars = 0
    private var color = SignalColors.NONE
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val empty = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(60, 128, 128, 128) }
    private val rect = RectF()

    fun set(bars: Int, color: Int) {
        this.bars = bars.coerceIn(0, 5)
        this.color = color
        contentDescription = "${this.bars} of 5 bars"
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat() - paddingLeft - paddingRight
        val h = height.toFloat() - paddingTop - paddingBottom
        val gap = w * 0.06f
        val bw = (w - gap * 4) / 5f
        val radius = bw * 0.2f
        fill.color = color
        for (i in 0 until 5) {
            val bh = h * (i + 1) / 5f
            val left = paddingLeft + i * (bw + gap)
            rect.set(left, paddingTop + h - bh, left + bw, paddingTop + h)
            canvas.drawRoundRect(rect, radius, radius, if (i < bars) fill else empty)
        }
    }
}

/** Rolling line graph of the last two minutes of readings. */
class SignalGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var readings: List<SignalReading> = emptyList()
    private var bestDbm: Int? = null
    private val windowMs = 120_000L
    private val minDbm = -130f
    private val maxDbm = -60f

    private val density = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 3f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grid = Paint().apply {
        color = Color.argb(70, 128, 128, 128)
        strokeWidth = 1f * density
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 128, 128, 128)
        textSize = 12f * density
    }
    private val best = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = SignalColors.GOOD
        strokeWidth = 2f * density
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(10f * density, 6f * density), 0f)
    }
    private val bestText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = SignalColors.GOOD
        textSize = 13f * density
        isFakeBoldText = true
    }
    private val noService = Paint().apply { color = Color.argb(60, 211, 47, 47) }

    fun setData(readings: List<SignalReading>, bestDbm: Int?) {
        this.readings = readings
        this.bestDbm = bestDbm
        invalidate()
    }

    private fun yFor(dbm: Int, top: Float, h: Float): Float {
        val c = dbm.toFloat().coerceIn(minDbm, maxDbm)
        return top + (maxDbm - c) / (maxDbm - minDbm) * h
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = 40f * density
        val top = 6f * density
        val right = width - 6f * density
        val bottom = height - 6f * density
        val w = right - left
        val h = bottom - top

        for (g in listOf(-70, -80, -90, -100, -110, -120)) {
            val y = yFor(g, top, h)
            canvas.drawLine(left, y, right, y, grid)
            canvas.drawText("$g", 2f * density, y + 4f * density, label)
        }

        if (readings.isEmpty()) return
        val now = System.currentTimeMillis()
        val start = now - windowMs
        fun xFor(t: Long) = left + ((t - start).toFloat() / windowMs) * w

        var prev: SignalReading? = null
        for (r in readings) {
            if (r.timestamp < start) { prev = r; continue }
            val x = xFor(r.timestamp)
            val d = r.dbm
            if (d == null) {
                canvas.drawRect(x - 2f * density, top, x + 2f * density, bottom, noService)
            } else {
                val y = yFor(d, top, h)
                val color = SignalColors.forBars(r.bars)
                val p = prev
                val pd = p?.dbm
                if (p != null && pd != null && r.timestamp - p.timestamp <= 3000 && p.timestamp >= start) {
                    line.color = color
                    canvas.drawLine(xFor(p.timestamp), yFor(pd, top, h), x, y, line)
                }
                dot.color = color
                canvas.drawCircle(x, y, 2.5f * density, dot)
            }
            prev = r
        }

        bestDbm?.let { b ->
            val y = yFor(b, top, h)
            canvas.drawLine(left, y, right, y, best)
            canvas.drawText("best $b", left + 4f * density, y - 4f * density, bestText)
        }
    }
}
