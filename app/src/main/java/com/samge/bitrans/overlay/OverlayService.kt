package com.samge.bitrans.overlay

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.samge.bitrans.translate.TranslateConfig
import kotlin.math.abs

/**
 * Global semi-transparent caption overlay drawn above other apps (e.g. Hilokal).
 * - draggable anywhere on screen
 * - width / font size / background opacity from settings
 * - tap to collapse/expand
 * Content is pushed from MainViewModel via [push].
 */
class OverlayService : Service() {

    private var wm: WindowManager? = null
    private var root: LinearLayout? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (root == null) {
            createOverlay()
        } else {
            applyStyle() // settings may have changed
        }
        return START_STICKY
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createOverlay() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = 0
        lp.y = 120

        val src = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            alpha = 0.85f
        }
        val tgt = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            addView(src)
            addView(tgt)
        }

        val bg = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(Color.argb(alphaPct(), 12, 12, 20))
        }
        box.background = bg

        // drag + tap-to-collapse
        var downX = 0f; var downY = 0f
        var startLpX = 0; var startLpY = 0
        var moved = false
        var collapsed = false
        box.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startLpX = lp.x; startLpY = lp.y; moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).toInt()
                    val dy = (e.rawY - downY).toInt()
                    if (abs(dx) > 8 || abs(dy) > 8) moved = true
                    lp.x = startLpX + dx
                    lp.y = startLpY + dy
                    runCatching { wm?.updateViewLayout(box, lp) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        collapsed = !collapsed
                        src.visibility = if (collapsed) View.GONE else View.VISIBLE
                        if (collapsed) box.alpha = 0.45f else box.alpha = 1f
                    }
                    true
                }
                else -> false
            }
        }

        root = box
        sourceView = src
        targetView = tgt
        wm?.addView(box, lp)
        applyStyle()
        serviceRunning = true
    }

    private fun applyStyle() {
        val box = root ?: return
        val dm = resources.displayMetrics
        val widthPx = (dm.widthPixels * TranslateConfig.overlayWidth(this) / 100)
        val fontSp = TranslateConfig.overlayFont(this).toFloat()
        (box.layoutParams as? WindowManager.LayoutParams)?.let {
            it.width = widthPx
            wm?.updateViewLayout(box, it)
        } ?: run { box.minimumWidth = widthPx }
        targetView?.textSize = fontSp
        sourceView?.textSize = (fontSp * 0.8f)
        box.background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(Color.argb(alphaPct(), 12, 12, 20))
        }
    }

    private fun alphaPct(): Int = (255 * TranslateConfig.overlayAlpha(this) / 100)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        root?.let { runCatching { wm?.removeView(it) } }
        root = null
        serviceRunning = false
        super.onDestroy()
    }

    companion object {
        private const val ACTION_STOP = "com.samge.bitrans.overlay.STOP"
        @Volatile private var serviceRunning = false
        @Volatile private var sourceView: TextView? = null
        @Volatile private var targetView: TextView? = null
        private val ui = Handler(Looper.getMainLooper())

        fun running(): Boolean = serviceRunning

        fun start(ctx: Context) {
            if (!Settings.canDrawOverlays(ctx)) return
            // plain startService: this is NOT a foreground service (no notification),
            // and callers are always in-foreground (Settings pane / listening toggle).
            runCatching { ctx.startService(Intent(ctx, OverlayService::class.java)) }
        }

        fun stop(ctx: Context) {
            runCatching {
                ctx.startService(Intent(ctx, OverlayService::class.java).setAction(ACTION_STOP))
            }
        }

        /** Push a caption to the overlay (safe from any thread). */
        fun push(source: String, target: String) {
            ui.post {
                sourceView?.text = source
                targetView?.text = if (target.isBlank()) "…" else target
            }
        }
    }
}
