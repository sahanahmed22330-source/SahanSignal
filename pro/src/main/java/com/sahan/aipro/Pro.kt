package com.sahan.aipro

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*
import java.io.File

class MainActivity : Activity() {
    private lateinit var info: TextView
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val d = resources.displayMetrics.density
        info = TextView(this).apply { textSize = 16f; setPadding((16 * d).toInt(), (16 * d).toInt(), (16 * d).toInt(), (16 * d).toInt()) }
        val start = Button(this).apply { text = "START  •  SAHAN AI PRO"; setOnClickListener { go() } }
        val copy = Button(this).apply { text = "COPY LOG"; setOnClickListener { copyLog() } }
        setContentView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(start); addView(copy); addView(info) })
    }
    override fun onResume() { super.onResume(); info.text = stats() }
    private fun lines(): List<String> = File(getExternalFilesDir(null), "votes.csv").takeIf { it.exists() }?.readLines() ?: emptyList()
    private fun stats(): String {
        val l = lines(); val w = l.count { it.endsWith(",1") }
        return if (l.isEmpty()) "No checked signals yet." else "Signals checked: ${l.size}\nCorrect: $w (${w * 100 / l.size}%)"
    }
    private fun copyLog() {
        val t = lines().takeLast(400).joinToString("\n")
        (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("log", t))
        Toast.makeText(this, "Copied ${minOf(lines().size, 400)} lines", Toast.LENGTH_SHORT).show()
    }
    private fun go() {
        if (!Settings.canDrawOverlays(this)) { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))); return }
        startActivityForResult(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent(), 1)
    }
    override fun onActivityResult(r: Int, c: Int, d: Intent?) {
        if (c == RESULT_OK && d != null) { startForegroundService(Intent(this, OverlayService::class.java).putExtra("code", c).putExtra("data", d)); moveTaskToBack(true) }
    }
}

class Candle(val x: Int, val up: Boolean, val hi: Int, val lo: Int, val top: Int, val bot: Int) { val close get() = if (up) top else bot }
class Res(val up: Boolean, val agree: Int, val pct: Int, val strong: Boolean, val key: String, val hUp: Boolean, val votes: IntArray)

object Pro {
    const val X1 = 0.85f; const val Y0 = 0.14f; const val Y1 = 0.70f  // chart area; adjust if candles are missed

    private fun cls(p: Int): Int {
        val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
        return if (g > r + 50 && g > b + 50) 1 else if (r > g + 70 && r > b + 50) -1 else 0
    }

    fun candles(bm: Bitmap, mask: Rect?): List<Candle> {
        val n = (bm.width * X1).toInt(); val y0 = (bm.height * Y0).toInt(); val hh = (bm.height * Y1).toInt() - y0
        val px = IntArray(n * hh); bm.getPixels(px, 0, n, 0, y0, n, hh)
        if (mask != null) for (y in maxOf(mask.top - y0, 0) until minOf(mask.bottom - y0, hh)) for (x in maxOf(mask.left, 0) until minOf(mask.right, n)) px[y * n + x] = 0
        val type = IntArray(n); val mn = IntArray(n); val mx = IntArray(n)
        for (i in 0 until n) {
            var g = 0; var r = 0; var a = Int.MAX_VALUE; var z = -1
            for (y in 0 until hh) { val c = cls(px[y * n + i]); if (c == 1) g++ else if (c == -1) r++ }
            val t = if (g >= 3 && g > r) 1 else if (r >= 3 && r > g) -1 else 0
            if (t != 0) for (y in 0 until hh) if (cls(px[y * n + i]) == t) { if (y < a) a = y; z = y }
            type[i] = t; mn[i] = a; mx[i] = z
        }
        val raw = ArrayList<Pair<Candle, Int>>(); var i = 0
        while (i < n) {
            if (type[i] == 0) { i++; continue }
            var j = i
            while (j + 1 < n && type[j + 1] == type[i]) j++
            if (j - i + 1 >= 5) { val c = (i + j) / 2; val e = i + 1; raw.add(Pair(Candle(c, type[i] == 1, mn[c], mx[c], mn[e], mx[e]), j - i + 1)) }
            i = j + 1
        }
        val med = if (raw.isEmpty()) 0 else raw.map { it.second }.sorted()[raw.size / 2]
        return raw.filter { it.second >= med * 0.6 }.map { it.first }  // drop price marker / edge slivers
    }

    fun valid(c: List<Candle>): Boolean {
        if (c.size < 10) return false
        val g = c.zipWithNext { a, b -> b.x - a.x }; val m = g.sorted()[g.size / 2]
        return m >= 6 && g.count { Math.abs(it - m) <= m * 0.4 } >= g.size * 0.7
    }

    private fun ema(p: DoubleArray, n: Int): Double { val k = 2.0 / (n + 1); var e = p[0]; for (i in 1 until p.size) e = p[i] * k + e * (1 - k); return e }
    private fun rsi(p: DoubleArray, n: Int): Double {
        var g = 0.0; var l = 0.0
        for (i in p.size - n until p.size) { val d = p[i] - p[i - 1]; if (d > 0) g += d else l -= d }
        return if (g + l == 0.0) 50.0 else 100 * g / (g + l)
    }
    private fun s(x: Double, t: Double) = if (x > t) 1 else if (x < -t) -1 else 0
