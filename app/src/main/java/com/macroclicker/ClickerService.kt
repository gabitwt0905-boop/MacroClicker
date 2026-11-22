package com.macroclicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.hypot

/** Uma ação gravada: toque (x1==x2) ou deslize. delay = espera antes de executar (ms). */
data class Act(
    val x1: Float, val y1: Float,
    val x2: Float, val y2: Float,
    val dur: Long, val delay: Long
) {
    val isSwipe: Boolean get() = hypot(x2 - x1, y2 - y1) > 40f
}

class ClickerService : AccessibilityService() {

    companion object {
        @Volatile var instance: ClickerService? = null
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager

    private val acts = mutableListOf<Act>()
    private var recording = false
    private var playing = false
    private var lastEnd = 0L

    private var panel: LinearLayout? = null
    private var panelLp: WindowManager.LayoutParams? = null
    private var overlay: OverlayView? = null
    private var btnRec: TextView? = null
    private var btnPlay: TextView? = null

    // ---------- ciclo de vida ----------
    override fun onServiceConnected() {
        instance = this
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        load()
        showPanel()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        stopPlay()
        recording = false
        removeOverlay()
        hidePanel()
        instance = null
        return super.onUnbind(intent)
    }

    // ---------- helpers ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun dpf(v: Float) = v * resources.displayMetrics.density
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    fun actionCount() = acts.size

    // ---------- painel flutuante ----------
    fun showPanel() {
        if (panel != null) return

        val p = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(0xE6222222.toInt())
                cornerRadius = dpf(28f)
            }
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }

        val handle = TextView(this).apply {
            text = "⠿"
            textSize = 20f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(28))
        }
        p.addView(handle)

        btnRec = makeBtn("●") { toggleRecord() }
        btnPlay = makeBtn("▶") { togglePlay() }
        p.addView(btnRec)
        p.addView(btnPlay)
        p.addView(makeBtn("🗑") { clearActs() })
        p.addView(makeBtn("⚙") {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        })
        p.addView(makeBtn("✕") {
            if (!recording && !playing) {
                hidePanel()
                toast("Painel fechado. Reabra pelo app.")
            }
        })

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(8); y = dp(200)
        }

        handle.setOnTouchListener(object : View.OnTouchListener {
            var sx = 0; var sy = 0; var tx = 0f; var ty = 0f
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { sx = lp.x; sy = lp.y; tx = e.rawX; ty = e.rawY }
                    MotionEvent.ACTION_MOVE -> {
                        lp.x = sx + (e.rawX - tx).toInt()
                        lp.y = sy + (e.rawY - ty).toInt()
                        try { wm.updateViewLayout(p, lp) } catch (_: Exception) {}
                    }
                }
                return true
            }
        })

        panel = p
        panelLp = lp
        wm.addView(p, lp)
    }

    private fun hidePanel() {
        panel?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        panel = null
        panelLp = null
    }

    private fun makeBtn(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 20f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply {
            setMargins(dp(2), dp(2), dp(2), dp(2))
        }
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFF3949AB.toInt())
        }
        setOnClickListener { onClick() }
    }

    private fun bringPanelToFront() {
        val p = panel ?: return
        try { wm.removeView(p) } catch (_: Exception) {}
        wm.addView(p, panelLp)
    }

    // ---------- overlay de tela cheia (gravação / marcadores) ----------
    private fun showOverlay(touchable: Boolean) {
        removeOverlay()
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        )
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        val v = OverlayView(this)
        v.tint = touchable
        wm.addView(v, lp)
        overlay = v
        bringPanelToFront()
    }

    private fun removeOverlay() {
        overlay?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        overlay = null
    }

    inner class OverlayView(c: Context) : View(c) {
        var tint = false
        private var dx = 0f
        private var dy = 0f
        private var dt = 0L

        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xAAE53935.toInt() }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xAAE53935.toInt(); strokeWidth = 6f; style = Paint.Style.STROKE
        }
        private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 34f; textAlign = Paint.Align.CENTER
        }

        override fun onDraw(c: Canvas) {
            if (tint) c.drawColor(0x22FF5252)
            val loc = IntArray(2)
            getLocationOnScreen(loc)
            acts.forEachIndexed { i, a ->
                val x = a.x1 - loc[0]
                val y = a.y1 - loc[1]
                if (a.isSwipe) c.drawLine(x, y, a.x2 - loc[0], a.y2 - loc[1], line)
                c.drawCircle(x, y, 28f, fill)
                c.drawText("${i + 1}", x, y + 12f, txt)
            }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (!recording) return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dx = e.rawX; dy = e.rawY; dt = SystemClock.uptimeMillis()
                }
                MotionEvent.ACTION_UP -> {
                    val now = SystemClock.uptimeMillis()
                    val swipe = hypot(e.rawX - dx, e.rawY - dy) > 40f
                    val dur = (now - dt).coerceIn(if (swipe) 100L else 30L, 5000L)
                    val delay = if (lastEnd == 0L) 0L else (dt - lastEnd).coerceAtLeast(0L)
                    acts.add(
                        Act(
                            dx, dy,
                            if (swipe) e.rawX else dx,
                            if (swipe) e.rawY else dy,
                            dur, delay
                        )
                    )
                    lastEnd = now
                    invalidate()
                }
            }
            return true
        }
    }

    // ---------- gravação ----------
    private fun toggleRecord() {
        if (playing) return
        if (recording) stopRecord() else startRecord()
    }

    private fun startRecord() {
        acts.clear()
        lastEnd = 0L
        recording = true
        btnRec?.text = "■"
        showOverlay(true)
        toast("Gravando: toque/deslize onde quiser")
    }

    private fun stopRecord() {
        recording = false
        btnRec?.text = "●"
        removeOverlay()
        save()
        toast("${acts.size} ações gravadas")
    }

    private fun clearActs() {
        if (recording || playing) return
        acts.clear()
        save()
        toast("Gravação apagada")
    }

    // ---------- reprodução ----------
    private fun togglePlay() {
        if (recording) return
        if (playing) stopPlay() else startPlay()
    }

    private fun startPlay() {
        if (acts.isEmpty()) {
            toast("Grave algo primeiro (●)")
            return
        }
        val cfg = getSharedPreferences("cfg", MODE_PRIVATE)
        val reps = cfg.getInt("reps", 1)
        val speed = cfg.getFloat("speed", 1f).coerceIn(0.1f, 20f)
        val gap = (cfg.getFloat("gap", 0f) * 1000).toLong()

        playing = true
        btnPlay?.text = "■"
        showOverlay(false)
        step(0, 1, reps, speed, gap)
    }

    private fun stopPlay() {
        if (!playing) return
        playing = false
        handler.removeCallbacksAndMessages(null)
        btnPlay?.text = "▶"
        removeOverlay()
    }

    private fun step(i: Int, round: Int, reps: Int, speed: Float, gap: Long) {
        if (!playing) return

        if (i >= acts.size) {
            if (reps != 0 && round >= reps) {
                stopPlay()
                toast("Concluído")
                return
            }
            handler.postDelayed({ step(0, round + 1, reps, speed, gap) }, maxOf(gap, 50L))
            return
        }

        val a = acts[i]
        val wait = if (i == 0) 0L else (a.delay / speed).toLong()

        handler.postDelayed({
            if (!playing) return@postDelayed
            val path = Path().apply {
                moveTo(a.x1, a.y1)
                if (a.isSwipe) lineTo(a.x2, a.y2)
            }
            val dur = (a.dur / speed).toLong().coerceIn(20L, 5000L)
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, dur))
                .build()

            val ok = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) { step(i + 1, round, reps, speed, gap) }
                override fun onCancelled(g: GestureDescription?) { step(i + 1, round, reps, speed, gap) }
            }, null)
            if (!ok) step(i + 1, round, reps, speed, gap)
        }, wait)
    }

    // ---------- salvar / carregar (automático) ----------
    private fun save() {
        val arr = JSONArray()
        acts.forEach {
            arr.put(
                JSONObject()
                    .put("x1", it.x1.toDouble()).put("y1", it.y1.toDouble())
                    .put("x2", it.x2.toDouble()).put("y2", it.y2.toDouble())
                    .put("dur", it.dur).put("delay", it.delay)
            )
        }
        getSharedPreferences("cfg", MODE_PRIVATE).edit().putString("macro", arr.toString()).apply()
    }

    private fun load() {
        val s = getSharedPreferences("cfg", MODE_PRIVATE).getString("macro", null) ?: return
        try {
            val arr = JSONArray(s)
            acts.clear()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                acts.add(
                    Act(
                        o.getDouble("x1").toFloat(), o.getDouble("y1").toFloat(),
                        o.getDouble("x2").toFloat(), o.getDouble("y2").toFloat(),
                        o.getLong("dur"), o.getLong("delay")
                    )
                )
            }
        } catch (_: Exception) {
        }
    }
}
