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
    private lateinit var res: TextView

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, f: Int, id: Int): Int {
        val i = intent ?: return START_NOT_STICKY
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel("sig", "Signal", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "sig").setContentTitle("Signal running").setSmallIcon(android.R.drawable.ic_menu_view).build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        val data = i.getParcelableExtra<Intent>("data") ?: return START_NOT_STICKY
        val proj = getSystemService(MediaProjectionManager::class.java).getMediaProjection(i.getIntExtra("code", 0), data)
        proj.registerCallback(object : MediaProjection.Callback() { override fun onStop() { stopSelf() } }, ui)
        val m = resources.displayMetrics
        reader = ImageReader.newInstance(m.widthPixels, m.heightPixels, PixelFormat.RGBA_8888, 2)
        proj.createVirtualDisplay("cap", m.widthPixels, m.heightPixels, m.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, null)
        overlay()
        return START_NOT_STICKY
    }

    private fun overlay() {
        fun box(t: String, sp: Float) = TextView(this).apply {
            text = t; textSize = sp; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setPadding(24, 14, 24, 14)
            background = GradientDrawable().apply { setColor(0xCC333333.toInt()); cornerRadius = 40f }
        }
        val btn = box("AI", 18f)
        res = box("", 20f).apply { visibility = View.GONE }
        val lay = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(btn); addView(res) }
        btn.setOnClickListener { Thread { run() }.start() }
        val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        getSystemService(WindowManager::class.java).addView(lay, lp)
    }

    private fun show(t: String) { ui.post { res.text = t; res.visibility = View.VISIBLE } }

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

    private fun run() {
        val dir = getExternalFilesDir(null)!!
        val bm = grab() ?: return show("--")
        val s = Analyzer.signal(Analyzer.candles(bm), dir) ?: return show("--")
        show((if (s.up) "UP " else "DOWN ") + s.pct + "%")
        ui.postDelayed({ Thread { judge(s, dir) }.start() }, 75000)
    }

    // ~75s later: the traded candle is the second-last one on screen; log whether the signal won.
    private fun judge(s: Sig, dir: File) {
        val c = Analyzer.candles(grab() ?: return)
        if (c.size < 3) return
        val won = c[c.size - 2].up == s.up
        File(dir, "log.csv").appendText("${System.currentTimeMillis()},${s.key},${s.hUp},${s.up},${if (won) 1 else 0}\n")
    }
}
