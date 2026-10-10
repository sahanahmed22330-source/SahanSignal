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

    private fun ema(p: DoubleArray, n: Int): Double {
        val k = 2.0 / (n + 1)
        var e = p[0]
        for (i in 1 until p.size) {
            e = p[i] * k + e * (1.0 - k)
        }
        return e
    }

    private fun rsi(p: DoubleArray, n: Int): Double {
        var g = 0.0
        var l = 0.0
        for (i in p.size - n until p.size) {
            val d = p[i] - p[i - 1]
            if (d > 0.0) {
                g += d
            } else {
                l -= d
            }
        }
        if (g + l == 0.0) return 50.0
        return 100.0 * g / (g + l)
    }

    private fun sgn(x: Double, t: Double): Int {
        if (x > t) return 1
        if (x < 0.0 - t) return -1
        return 0
    }

    // liveDy: how far the current candle's price moved during the 3-4 s of live watching (in candle-size units, + = up)
    fun analyze(c: List<Candle>, liveDy: Double, dir: File): Res? {
        if (!valid(c)) return null
        val cur = c.last(); val prev = c[c.size - 2]; val h = c.takeLast(16)
        val unit = h.map { (it.lo - it.hi).toDouble() }.average().coerceAtLeast(4.0)
        val p = h.map { -it.close.toDouble() }.toDoubleArray(); val pc = p.last()
        val v = IntArray(8)
        v[0] = if ((cur.bot - cur.top) / unit > 0.15) (if (cur.up) 1 else -1) else 0              // candle momentum
        v[1] = sgn(ema(p, 3) - ema(p, 8), 0.05 * unit)                                               // EMA trend
        val r = rsi(p, 7)
        if (r > 70.0) v[2] = -1 else if (r < 30.0) v[2] = 1 else if (r > 55.0) v[2] = 1 else if (r < 45.0) v[2] = -1   // RSI
        val w = p.takeLast(10); val m = w.average(); val sd = Math.sqrt(w.map { (it - m) * (it - m) }.average())
        if (sd > 0.0) {
            val z = (pc - m) / sd
            if (z > 1.8) v[3] = -1 else if (z < -1.8) v[3] = 1   // Bollinger extremes revert
        }
        val rng = (cur.lo - cur.hi).coerceAtLeast(1).toDouble()
        v[4] = sgn(((cur.lo - cur.bot) - (cur.top - cur.hi)) / rng, 0.3)                              // wick pressure
        var st = 0; for (k in c.dropLast(1).reversed()) { if (k.up == prev.up) st++ else break }
        val pd = if (prev.up) 1 else -1
        if (cur.up != prev.up && (cur.bot - cur.top) > (prev.bot - prev.top) * 1.1) {
            v[5] = if (cur.up) 1 else -1   // engulfing
        } else if (st >= 5) {
            v[5] = -pd                     // long streak: expect a reversal
        } else if (st >= 3) {
            v[5] = pd                      // short streak: expect continuation
        }
        val hiP = h.dropLast(1).maxOf { -it.hi.toDouble() }; val loP = h.dropLast(1).minOf { -it.lo.toDouble() }
        if (pc > hiP + 0.1 * unit) {
            v[6] = 1                       // breakout above resistance
        } else if (pc < loP - 0.1 * unit) {
            v[6] = -1                      // breakdown below support
        } else if (hiP - pc < 0.3 * unit) {
            v[6] = -1                      // sitting under resistance
        } else if (pc - loP < 0.3 * unit) {
            v[6] = 1                       // sitting on support
        }
        v[7] = sgn(liveDy, 0.15)                                                                      // live tick momentum
        val sum = v.sum()
        val hUp: Boolean
        if (sum != 0) hUp = sum > 0 else if (v[7] != 0) hUp = v[7] > 0 else hUp = cur.up
        val d = if (hUp) 1 else -1; val agree = v.count { it == d }
        val key = "a$agree${if (v[1] == d) "w" else "c"}"
        var wn = 0; var t = 0
        File(dir, "log.csv").takeIf { it.exists() }?.forEachLine { l ->
            val f = l.split(","); if (f.size == 5 && f[1] == key) { t++; if ((f[2] == f[3]) == (f[4] == "1")) wn++ }
        }
        val pr = (wn + 1.0) / (t + 2.0); val flip = t >= 30 && pr < 0.45   // measured win rate of this setup
        return Res(if (flip) !hUp else hUp, agree, Math.round((if (flip) 1 - pr else pr) * 100).toInt(), agree >= 5 && Math.abs(sum) >= 3, key, hUp, v)
    }
}

class OverlayService : Service() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var reader: ImageReader
    private lateinit var wm: WindowManager
    private lateinit var lay: LinearLayout
    private lateinit var card: TextView
    private var proj: MediaProjection? = null
    @Volatile private var busy = false
    @Volatile private var mask: Rect? = null
    private val G = 0xFF00FF88.toInt(); private val RED = 0xFFFF4D4D.toInt(); private val AMB = 0xFFFFC857.toInt(); private val DARK = 0xEE0B1026.toInt()

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, f: Int, id: Int): Int {
        val i = intent ?: return START_NOT_STICKY
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("pro", "Sahan AI Pro", NotificationManager.IMPORTANCE_LOW))
        startForeground(1, Notification.Builder(this, "pro").setContentTitle("Sahan AI Pro running").setSmallIcon(android.R.drawable.ic_menu_view).build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        if (::lay.isInitialized) try { wm.removeView(lay) } catch (_: Exception) {}
        proj?.stop()
        val data = i.getParcelableExtra<Intent>("data") ?: return START_NOT_STICKY
        val p = getSystemService(MediaProjectionManager::class.java).getMediaProjection(i.getIntExtra("code", 0), data); proj = p
        p.registerCallback(object : MediaProjection.Callback() { override fun onStop() { stopSelf() } }, ui)
        val m = resources.displayMetrics
        reader = ImageReader.newInstance(m.widthPixels, m.heightPixels, PixelFormat.RGBA_8888, 2)
        p.createVirtualDisplay("cap", m.widthPixels, m.heightPixels, m.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, null)
        overlay(); return START_NOT_STICKY
    }

    override fun onDestroy() { try { wm.removeView(lay) } catch (_: Exception) {}; proj?.stop(); super.onDestroy() }

    private fun bg(fill: Int, stroke: Int, r: Float, oval: Boolean = false) = GradientDrawable().apply {
        if (oval) shape = GradientDrawable.OVAL else cornerRadius = r; setColor(fill); setStroke(5, stroke)
    }

    private fun overlay() {
        wm = getSystemService(WindowManager::class.java); val m = resources.displayMetrics; val d = m.density
        val btn = ImageView(this).apply {
            setImageResource(R.drawable.logo); scaleType = ImageView.ScaleType.CENTER_CROP; background = bg(DARK, 0xFF00E5FF.toInt(), 0f, true); clipToOutline = true
            layoutParams = LinearLayout.LayoutParams((62 * d).toInt(), (62 * d).toInt())
        }
        val close = TextView(this).apply {
            text = "✕"; textSize = 14f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; background = bg(0xEE333333.toInt(), 0xFF777777.toInt(), 0f, true)
            layoutParams = LinearLayout.LayoutParams((32 * d).toInt(), (32 * d).toInt()).apply { leftMargin = (6 * d).toInt() }; setOnClickListener { stopSelf() }
        }
        card = TextView(this).apply {
            textSize = 20f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(G)
            setPadding((16 * d).toInt(), (8 * d).toInt(), (16 * d).toInt(), (8 * d).toInt()); visibility = View.GONE
        }
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; addView(btn); addView(close) }
        lay = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(row); addView(card) }
        val lp = WindowManager.LayoutParams(-2, -2, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.TOP or Gravity.START; lp.x = m.widthPixels - (110 * d).toInt(); lp.y = m.heightPixels / 2
        lay.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> val o = IntArray(2); v.getLocationOnScreen(o); mask = Rect(o[0], o[1], o[0] + v.width, o[1] + v.height) }
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var mv = false
        btn.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y; mv = false }
                MotionEvent.ACTION_MOVE -> { if (Math.abs(e.rawX - sx) > 12 || Math.abs(e.rawY - sy) > 12) mv = true
                    if (mv) { lp.x = ox + (e.rawX - sx).toInt(); lp.y = oy + (e.rawY - sy).toInt(); wm.updateViewLayout(lay, lp) } }
                MotionEvent.ACTION_UP -> {
                    if (!mv && !busy) { busy = true; Thread { run() }.start() }
                }
            }
            true
        }
        wm.addView(lay, lp)
    }

    private fun setCard(t: String, c: Int) { ui.post { card.text = t; card.setTextColor(c); card.background = bg(DARK, c, 44f); card.visibility = View.VISIBLE } }

    private fun grab(): Bitmap? {
        repeat(8) {
            val img = reader.acquireLatestImage()
            if (img != null) {
                val p = img.planes[0]; val w = img.width; val h = img.height
                val bm = Bitmap.createBitmap(p.rowStride / p.pixelStride, h, Bitmap.Config.ARGB_8888)
                bm.copyPixelsFromBuffer(p.buffer); img.close(); return Bitmap.createBitmap(bm, 0, 0, w, h)
            }
            Thread.sleep(80)
        }
        return null
    }

    // 3.5 s of live watching: several frames, last valid frame is analysed, price drift of the current candle gives tick momentum.
    private fun run() {
        try {
            val dir = getExternalFilesDir(null)!!; val t0 = SystemClock.uptimeMillis(); var k = 0
            var first: Candle? = null; var last: List<Candle>? = null
            while (SystemClock.uptimeMillis() - t0 < 3500) {
                setCard("ANALYZING" + ".".repeat(k++ % 4), 0xFF00E5FF.toInt())
                val c = grab()?.let { Pro.candles(it, mask) }
                if (c != null && Pro.valid(c)) { if (first == null) first = c.last(); last = c }
                Thread.sleep(300)
            }
            val l = last
            if (l == null) { setCard("--", Color.LTGRAY); return }
            val f = first!!; val cur = l.last()
            val unit = l.takeLast(16).map { (it.lo - it.hi).toDouble() }.average().coerceAtLeast(4.0)
            val live = if (Math.abs(cur.x - f.x) <= 3) (f.close - cur.close) / unit else 0.0   // ignore if a new candle started
            val r = Pro.analyze(l, live, dir)
            if (r == null) { setCard("--", Color.LTGRAY); return }
            val col = if (!r.strong) AMB else if (r.up) G else RED
            setCard((if (r.up) "UP ▲" else "DOWN ▼") + "  ${r.pct}%\n${r.agree}/8 agree" + (if (r.strong) "" else " • WEAK"), col)
            ui.postDelayed({ Thread { judge(r, dir) }.start() }, 75000)
        } finally { busy = false }
    }

    private fun judge(r: Res, dir: File) {
        val c = grab()?.let { Pro.candles(it, mask) } ?: return
        if (!Pro.valid(c)) return
        val won = if (c[c.size - 2].up == r.up) 1 else 0
        File(dir, "log.csv").appendText("${System.currentTimeMillis()},${r.key},${r.hUp},${r.up},$won\n")
        // votes: momentum,ema,rsi,bollinger,wick,pattern,support/resistance,live  then shown direction (1=up) and result (1=won)
        File(dir, "votes.csv").appendText("${System.currentTimeMillis()},${r.votes.joinToString(",")},${if (r.up) 1 else 0},$won\n")
    }
}
