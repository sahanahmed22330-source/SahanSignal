package com.sahan.signal

import android.graphics.Bitmap
import android.graphics.Color
import java.io.File

class Candle(val x: Int, val up: Boolean, val hi: Int, val lo: Int, val top: Int, val bot: Int) {
    val close get() = if (up) top else bot  // y of close (smaller y = higher price)
}
class Sig(val up: Boolean, val key: String, val pct: Int, val hUp: Boolean)

object Analyzer {
    // Chart area as fractions of the screen. Adjust if candles are not detected.
    const val X0 = 0.0f; const val X1 = 0.85f; const val Y0 = 0.14f; const val Y1 = 0.70f

    private fun cls(p: Int): Int {
        val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
        return if (g > r + 50 && g > b + 50) 1 else if (r > g + 70 && r > b + 50) -1 else 0
    }

    fun candles(bm: Bitmap): List<Candle> {
        val x0 = (bm.width * X0).toInt(); val n = (bm.width * X1).toInt() - x0
        val y0 = (bm.height * Y0).toInt(); val hh = (bm.height * Y1).toInt() - y0
        val px = IntArray(n * hh); bm.getPixels(px, 0, n, x0, y0, n, hh)
        val type = IntArray(n); val mn = IntArray(n) { Int.MAX_VALUE }; val mx = IntArray(n) { -1 }
        for (i in 0 until n) {
            var g = 0; var r = 0
            for (y in 0 until hh) { val c = cls(px[y * n + i]); if (c == 1) g++ else if (c == -1) r++ }
            val t = if (g >= 3 && g > r) 1 else if (r >= 3 && r > g) -1 else 0
            type[i] = t
            if (t != 0) for (y in 0 until hh) if (cls(px[y * n + i]) == t) { if (y < mn[i]) mn[i] = y; mx[i] = y }
        }
        val raw = ArrayList<Pair<Candle, Int>>()
        var i = 0
        while (i < n) {
            if (type[i] == 0) { i++; continue }
            var j = i
            while (j + 1 < n && type[j + 1] == type[i]) j++
            if (j - i + 1 >= 5) { val c = (i + j) / 2; val e = i + 1; raw.add(Pair(Candle(c, type[i] == 1, mn[c], mx[c], mn[e], mx[e]), j - i + 1)) }
            i = j + 1
        }
        // Real candle bodies share one width; thin runs (the small price-line marker, a half-visible edge candle) are not candles.
        val med = if (raw.isEmpty()) 0 else raw.map { it.second }.sorted()[raw.size / 2]
        return raw.filter { it.second >= med * 0.6 }.map { it.first }
    }

    // A real chart has many evenly spaced candles; home screens / other apps are rejected.
    fun valid(c: List<Candle>): Boolean {
        if (c.size < 8) return false
        val gaps = c.zipWithNext { a, b -> b.x - a.x }
        val med = gaps.sorted()[gaps.size / 2]
        return med >= 6 && gaps.count { Math.abs(it - med) <= med * 0.4 } >= gaps.size * 0.7
    }

    fun signal(c: List<Candle>, dir: File): Sig? {
        if (!valid(c)) return null
        val cur = c.last(); val prev = c.dropLast(1).takeLast(8); val last = prev.last()
        val avg = prev.map { (it.bot - it.top).toDouble() }.average().coerceAtLeast(2.0)
        val body = (cur.bot - cur.top) / avg
        var streak = 0
        for (k in prev.reversed()) { if (k.up == last.up) streak++ else break }
        val sg = { u: Boolean -> if (u) 1.0 else -1.0 }
        val win = c.takeLast(12)
        val hi = win.minOf { it.hi }; val lo = win.maxOf { it.lo }
        val pos = (cur.close - hi).toDouble() / (lo - hi).coerceAtLeast(1)  // 0 = top of range, 1 = bottom
        val trend = (prev.first().close - cur.close) / avg / prev.size       // > 0 = rising
        var score = sg(cur.up) * minOf(body, 2.0)                                    // current momentum
        score += sg(last.up) * (if (streak >= 4) -0.5 else minOf(streak, 3) * 0.3)  // streak (reverts after 4+)
        score += ((cur.lo - cur.bot) - (cur.top - cur.hi)) / avg * 0.8               // wick rejection
        score += trend.coerceIn(-1.0, 1.0) * 0.6                                     // short trend
        score += if (pos < 0.15) -0.5 else if (pos > 0.85) 0.5 else 0.0             // range extremes revert
        val hUp = score >= 0
        val key = "${if (cur.up) "U" else "D"}${minOf(streak, 3)}${if (last.up) "u" else "d"}b${if (body < 0.5) 0 else if (body < 1.2) 1 else 2}" +
            "t${if (trend > 0.05) "p" else if (trend < -0.05) "n" else "z"}r${if (pos < 0.15) 0 else if (pos > 0.85) 2 else 1}"
        var w = 0; var t = 0
        File(dir, "log.csv").takeIf { it.exists() }?.forEachLine { l ->
            val f = l.split(",")
            if (f.size == 5 && f[1] == key) { t++; if ((f[2] == f[3]) == (f[4] == "1")) w++ }
        }
        val p = (w + 1.0) / (t + 2.0)   // measured win rate of this pattern (starts at 50%)
        val flip = t >= 20 && p < 0.45  // pattern keeps losing -> take the opposite side
        return Sig(if (flip) !hUp else hUp, key, Math.round((if (flip) 1 - p else p) * 100).toInt(), hUp)
    }
}
