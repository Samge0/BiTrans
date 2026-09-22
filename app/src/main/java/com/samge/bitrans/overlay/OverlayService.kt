package com.samge.bitrans.overlay

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
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
 * Global semi-transparent caption overlay. Shows the last N caption pairs
 * (N = user setting, 1..10) — EXACTLY the same pairs as the main transcript
 * (source + final/partial translation), so multi-line overlay mirrors the app.
 * Draggable; tap toggles collapse.
 */
class OverlayService : Service() {

    private var wm: WindowManager? = null
    private var box: LinearLayout? = null
    private var rows: LinearLayout? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (box == null) createOverlay()
        redraw()
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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = 0
        lp.y = 120

        val rowsView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            addView(rowsView)
        }

        var downX = 0f; var downY = 0f
        var startLpX = 0; var startLpY = 0
        var moved = false
        var collapsed = false
        container.setOnTouchListener { _, e ->
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
                    runCatching { wm?.updateViewLayout(container, lp) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        collapsed = !collapsed
                        rowsView.visibility = if (collapsed) View.GONE else View.VISIBLE
                        container.alpha = if (collapsed) 0.45f else 1f
                    }
                    true
                }
                else -> false
            }
        }

        box = container
        rows = rowsView
        wm?.addView(container, lp)
        serviceRunning = true
    }

    private fun applyStyle() {
        val container = box ?: return
        val dm = resources.displayMetrics
        val widthPx = (dm.widthPixels * TranslateConfig.overlayWidth(this) / 100)
        (container.layoutParams as? WindowManager.LayoutParams)?.let {
            if (it.width != widthPx) {
                it.width = widthPx
                runCatching { wm?.updateViewLayout(container, it) }
            }
        } ?: run { container.minimumWidth = widthPx }
        container.background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(Color.argb(alphaPct(), 0x27, 0x27, 0x29))
        }
    }

    private fun redraw() {
        main.post {
            rebuild()
            applyStyle()
        }
    }

    private fun rebuild() {
        val rowsView = rows ?: return
        val fontSp = TranslateConfig.overlayFont(this).toFloat()
        val maxLines = TranslateConfig.overlayLines(this).coerceIn(1, 10)
        rowsView.removeAllViews()
        val snapshot = synchronized(history) { history.toList().takeLast(maxLines) }
        snapshot.forEachIndexed { idx, pair ->
            val (src, tgt) = pair
            val srcView = TextView(this).apply {
                text = src
                setTextColor(0xFFCCCCCC.toInt())
                textSize = fontSp * 0.82f
            }
            val tgtView = TextView(this).apply {
                text = tgt.ifBlank { "…" }
                setTextColor(Color.WHITE)
                textSize = fontSp
            }
            rowsView.addView(srcView)
            rowsView.addView(tgtView)
            if (idx != snapshot.lastIndex) {
                rowsView.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(1),
                    ).also { it.setMargins(0, dp(6), 0, dp(6)) }
                    setBackgroundColor(0x2EFFFFFF)
                })
            }
        }
    }

    private fun alphaPct(): Int = (255 * TranslateConfig.overlayAlpha(this) / 100)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        box?.let { runCatching { wm?.removeView(it) } }
        box = null
        rows = null
        serviceRunning = false
        instance = null
        super.onDestroy()
    }

    companion object {
        private const val ACTION_STOP = "com.samge.bitrans.overlay.STOP"
        @Volatile private var serviceRunning = false
        @Volatile private var instance: OverlayService? = null
        private val ui = Handler(Looper.getMainLooper())

        /**
         * Mirror of the main transcript: finalized (source, translation) pairs,
         * newest last. Maintained by MainViewModel.
         */
        private val history = ArrayList<Pair<String, String>>()

        fun running(): Boolean = serviceRunning

        fun start(ctx: Context) {
            if (!Settings.canDrawOverlays(ctx)) return
            runCatching { ctx.startService(Intent(ctx, OverlayService::class.java)) }
        }

        fun stop(ctx: Context) {
            runCatching {
                ctx.startService(Intent(ctx, OverlayService::class.java).setAction(ACTION_STOP))
            }
        }

        fun clear() {
            synchronized(history) { history.clear() }
        }

        /** Replace the whole history from the app's caption list (kept in sync by VM). */
        fun sync(list: List<Pair<String, String>>) {
            synchronized(history) {
                history.clear()
                history.addAll(list)
            }
            ui.post {
                instance?.rebuild()
                instance?.applyStyle()
            }
        }
    }
}
