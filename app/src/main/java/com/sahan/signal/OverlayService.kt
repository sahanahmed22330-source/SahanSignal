package com.sahan.signal

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.view.*
import android.widget.*
import java.io.File

class OverlayService : Service() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var reader: ImageReader
    private lateinit var wm: WindowManager
    private lateinit var lay: LinearLayout
    private lateinit var card: TextView
    private var proj: MediaProjection? = null
    private var busy = false
    private val G = 0xFF00FF88.toInt()
    private val R = 0xFFFF4D4D.toInt()
    private val DARK = 0xE6001A0F.toInt()

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, f: Int, id: Int): Int {
        val i = intent ?: return START_NOT_STICKY
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel("sig", "Signal", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "sig").setContentTitle("Signal running").setSmallIcon(android.R.drawable.ic_menu_view).build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        if (::lay.isInitialized) try { wm.removeView(lay) } catch (_: Exception) {}
        proj?.stop()
        val data = i.getParcelableExtra<Intent>("data") ?: return START_NOT_STICKY
        val p = getSystemService(MediaProjectionManager::class.java).getMediaProjection(i.getIntExtra("code", 0), data)
        proj = p
        p.registerCallback(object : MediaProjection.Callback() { override fun onStop() { stopSelf() } }, ui)
        val m = resources.displayMetrics
        reader = ImageReader.newInstance(m.widthPixels, m.heightPixels, PixelFormat.RGBA_8888, 2)
        p.createVirtualDisplay("cap", m.widthPixels, m.heightPixels, m.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, null)
        overlay()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        try { wm.removeView(lay) } catch (_: Exception) {}
        proj?.stop()
        super.onDestroy()
    }

    private fun bg(fill: Int, stroke: Int, r: Float, oval: Boolean = false) = GradientDrawable().apply {
        if (oval) shape = GradientDrawable.OVAL else cornerRadius = r
        setColor(fill); setStroke(4, stroke)
    }

    private fun overlay() {
        wm = getSystemService(WindowManager::class.java)
        val m = resources.displayMetrics; val d = m.density
        val btn = ImageView(this).apply {   // your logo as the floating button
            setImageResource(R.drawable.logo); scaleType = ImageView.ScaleType.CENTER_CROP
            background = bg(DARK, G, 0f, true); clipToOutline = true
            layoutParams = LinearLayout.LayoutParams((60 * d).toInt(), (60 * d).toInt())
        }
        val close = TextView(this).apply {   // tap to remove the floating button
            text = "✕"; textSize = 14f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = bg(0xE6333333.toInt(), 0xFF777777.toInt(), 0f, true)
            layoutParams = LinearLayout.LayoutParams((32 * d).toInt(), (32 * d).toInt()).apply { leftMargin = (6 * d).toInt() }
            setOnClickListener { stopSelf() }
        }
        card = TextView(this).apply {
            textSize = 22f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(G)
            setPadding((18 * d).toInt(), (10 * d).toInt(), (18 * d).toInt(), (10 * d).toInt()); visibility = View.GONE
        }
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; addView(btn); addView(close) }
        lay = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(row); addView(card) }
        val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.TOP or Gravity.START; lp.x = m.widthPixels - (140 * d).toInt(); lp.y = m.heightPixels / 2
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false
        btn.setOnTouchListener { _, e ->   // drag to move, tap to analyze
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    if (Math.abs(e.rawX - sx) > 12 || Math.abs(e.rawY - sy) > 12) moved = true
                    if (moved) { lp.x = ox + (e.rawX - sx).toInt(); lp.y = oy + (e.rawY - sy).toInt(); wm.updateViewLayout(lay, lp) }
                }
                MotionEvent.ACTION_UP -> if (!moved && !busy) { busy = true; Thread { run() }.start() }
            }
            true
        }
        wm.addView(lay, lp)
    }

    private fun setCard(t: String, c: Int) { ui.post { card.text = t; card.setTextColor(c); card.background = bg(DARK, c, 40f); card.visibility = View.VISIBLE } }

    private fun grab(): Bitmap? {
        repeat(30) {
            val img = reader.acquireLatestImage()
            if (img != null) {
                val p = img.planes[0]; val w = img.width; val h = img.height
                val bm = Bitmap.createBitmap(p.rowStride / p.pixelStride, h, Bitmap.Config.ARGB_8888)
                bm.copyPixelsFromBuffer(p.buffer); img.close()
                return Bitmap.createBitmap(bm, 0, 0, w, h)
            }
            Thread.sleep(100)
        }
        return null
    }

    // Hide our own overlay for a moment so its colours never end up in the screenshot.
    private fun grabClean(): Bitmap? {
        ui.post { lay.visibility = View.INVISIBLE }
        Thread.sleep(250)
        val bm = grab()
        ui.post { lay.visibility = View.VISIBLE }
        return bm
    }

    private fun run() {
        try {
            val dir = getExternalFilesDir(null)!!
            val bm = grabClean()
            setCard("SCANNING...", G)
            val s = bm?.let { Analyzer.signal(Analyzer.candles(it), dir) }
            Thread.sleep(1200)  // short animation pause
            if (s == null) { setCard("--", Color.LTGRAY); return }
            setCard(if (s.up) "UP ▲\n${s.pct}%" else "DOWN ▼\n${s.pct}%", if (s.up) G else R)
            ui.postDelayed({ Thread { judge(s, dir) }.start() }, 75000)
        } finally { busy = false }
    }

    // ~75s later: the traded candle is the second-last one on screen; log whether the signal won.
    private fun judge(s: Sig, dir: File) {
        val c = Analyzer.candles(grabClean() ?: return)
        if (!Analyzer.valid(c)) return
        val won = c[c.size - 2].up == s.up
        File(dir, "log.csv").appendText("${System.currentTimeMillis()},${s.key},${s.hUp},${s.up},${if (won) 1 else 0}\n")
    }
}
