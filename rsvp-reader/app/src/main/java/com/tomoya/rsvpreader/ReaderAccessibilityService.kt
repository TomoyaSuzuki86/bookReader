package com.tomoya.rsvpreader

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.max

class ReaderAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var windowManager: WindowManager

    private var bubble: TextView? = null
    private var readerOverlay: View? = null
    private var receiverRegistered = false
    private var lastPageText = ""
    private var waitingForOcr = false
    private var pendingAfterPageTurn = false

    private val ocrReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != CaptureService.ACTION_OCR_RESULT) return
            if (!waitingForOcr) return
            waitingForOcr = false
            mainHandler.removeCallbacks(ocrTimeout)

            val text = intent.getStringExtra(CaptureService.EXTRA_OCR_TEXT).orEmpty().trim()
            if (text.length < MIN_TEXT_LENGTH) {
                finishWithMessage("本文を取得できませんでした。OCR補助を有効にしているか確認してください")
                return
            }

            if (pendingAfterPageTurn && samePage(text, lastPageText)) {
                finishWithMessage("次ページを確認できませんでした")
                return
            }

            pendingAfterPageTurn = false
            startPage(text, "OCR")
        }
    }

    private val ocrTimeout = Runnable {
        if (!waitingForOcr) return@Runnable
        waitingForOcr = false
        finishWithMessage("本文を直接取得できませんでした。アプリでOCR補助を開始してください")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        registerOcrReceiver()
        showFloatingBubble()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        removeReaderOverlay()
        bubble?.let { runCatching { windowManager.removeView(it) } }
        bubble = null
        if (receiverRegistered) {
            runCatching { unregisterReceiver(ocrReceiver) }
            receiverRegistered = false
        }
        super.onDestroy()
    }

    private fun registerOcrReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter(CaptureService.ACTION_OCR_RESULT)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(ocrReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(ocrReceiver, filter)
        }
        receiverRegistered = true
    }

    private fun showFloatingBubble() {
        bubble?.let { runCatching { windowManager.removeView(it) } }

        val size = dp(58)
        val view = TextView(this).apply {
            text = "▶"
            textSize = 25f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(22, 28, 38))
            alpha = 0.94f
            elevation = dp(8).toFloat()
        }

        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(12)
            y = dp(250)
        }

        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var dragged = false

        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (abs(dx) > dp(5) || abs(dy) > dp(5)) dragged = true
                    params.x = max(0, startX - dx)
                    params.y = max(0, startY + dy)
                    runCatching { windowManager.updateViewLayout(view, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged) beginReading()
                    true
                }
                else -> false
            }
        }

        bubble = view
        windowManager.addView(view, params)
    }

    private fun beginReading() {
        if (readerOverlay != null || waitingForOcr) return
        bubble?.visibility = View.GONE
        mainHandler.postDelayed({
            val text = extractCurrentPageText()
            if (text.length >= MIN_TEXT_LENGTH) {
                pendingAfterPageTurn = false
                startPage(text, "本文")
            } else {
                requestOcrFallback(afterPageTurn = false)
            }
        }, 120)
    }

    private fun extractCurrentPageText(): String {
        val root = rootInActiveWindow ?: return ""
        if (root.packageName?.toString() == packageName) return ""

        val parts = mutableListOf<String>()
        collectLeafText(root, parts)

        val deduped = mutableListOf<String>()
        for (part in parts) {
            val normalized = part.replace(Regex("\\s+"), " ").trim()
            if (normalized.isBlank()) continue
            if (deduped.lastOrNull() == normalized) continue
            deduped += normalized
        }

        return deduped.joinToString(separator = "")
    }

    private fun collectLeafText(node: AccessibilityNodeInfo, output: MutableList<String>): Boolean {
        if (!node.isVisibleToUser) return false

        var childCollected = false
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            childCollected = collectLeafText(child, output) || childCollected
        }

        val own = node.text?.toString()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: node.contentDescription?.toString()?.trim()?.takeIf { it.isNotBlank() }

        if (!childCollected && own != null && isReadingArea(node)) {
            output += own
            return true
        }

        return childCollected || own != null
    }

    private fun isReadingArea(node: AccessibilityNodeInfo): Boolean {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val height = resources.displayMetrics.heightPixels
        val width = resources.displayMetrics.widthPixels
        if (bounds.isEmpty) return false

        val topCut = (height * 0.04f).toInt()
        val bottomCut = (height * 0.97f).toInt()
        if (bounds.bottom < topCut || bounds.top > bottomCut) return false
        if (bounds.right < 0 || bounds.left > width) return false

        val className = node.className?.toString().orEmpty()
        if (className.contains("Button", ignoreCase = true)) return false
        if (className.contains("ImageView", ignoreCase = true)) return false
        return true
    }

    private fun startPage(text: String, source: String) {
        val chunks = TextChunker.chunk(text)
        if (chunks.isEmpty()) {
            finishWithMessage("表示できる文章がありません")
            return
        }

        lastPageText = normalizeForCompare(text)
        showReader(chunks, source)
    }

    private fun showReader(chunks: List<String>, source: String) {
        removeReaderOverlay()
        bubble?.visibility = View.GONE

        val prefs = getSharedPreferences("reader", Context.MODE_PRIVATE)
        var speed = prefs.getInt("chars_per_minute", 650).coerceIn(200, 1400)
        var index = 0
        var playing = true

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(240, 8, 10, 14))
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }

        val progress = TextView(this).apply {
            setTextColor(Color.rgb(155, 165, 180))
            textSize = 14f
            gravity = Gravity.CENTER
        }

        val word = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 48f
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(60), dp(12), dp(60))
        }

        val speedLabel = TextView(this).apply {
            setTextColor(Color.rgb(190, 198, 210))
            textSize = 14f
            gravity = Gravity.CENTER
        }

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        fun control(label: String, action: () -> Unit): Button = Button(this).apply {
            text = label
            textSize = 16f
            minWidth = 0
            minimumWidth = 0
            setOnClickListener { action() }
        }

        lateinit var tick: Runnable

        fun refreshLabels(extra: String? = null) {
            index = index.coerceIn(0, chunks.lastIndex)
            word.text = chunks[index]
            progress.text = "${index + 1} / ${chunks.size}  ·  $source"
            speedLabel.text = extra ?: "$speed 文字/分  ·  読了時に自動で次ページ"
        }

        fun scheduleNext() {
            mainHandler.removeCallbacks(tick)
            if (!playing) return

            if (index >= chunks.lastIndex) {
                refreshLabels("次ページを取得中…")
                mainHandler.postDelayed({ advancePage() }, 220)
                return
            }

            val current = chunks[index]
            val base = (60_000.0 * current.length.coerceAtLeast(2) / speed).toLong()
            val punctuationBonus = when {
                current.endsWith('。') || current.endsWith('！') || current.endsWith('？') -> 280L
                current.endsWith('、') -> 130L
                else -> 0L
            }
            mainHandler.postDelayed(tick, (base + punctuationBonus).coerceIn(150L, 1900L))
        }

        tick = Runnable {
            if (playing) {
                if (index < chunks.lastIndex) {
                    index++
                    refreshLabels()
                    scheduleNext()
                } else {
                    scheduleNext()
                }
            }
        }

        word.setOnClickListener {
            playing = !playing
            if (playing) {
                refreshLabels()
                scheduleNext()
            } else {
                mainHandler.removeCallbacks(tick)
                refreshLabels("一時停止  ·  $speed 文字/分")
            }
        }

        val slower = control("−") {
            speed = (speed - 100).coerceAtLeast(200)
            prefs.edit().putInt("chars_per_minute", speed).apply()
            refreshLabels()
            scheduleNext()
        }
        val prev = control("◀") {
            index = (index - 1).coerceAtLeast(0)
            refreshLabels()
            scheduleNext()
        }
        val playPause = control("⏯") {
            playing = !playing
            if (playing) {
                refreshLabels()
                scheduleNext()
            } else {
                mainHandler.removeCallbacks(tick)
                refreshLabels("一時停止  ·  $speed 文字/分")
            }
        }
        val next = control("▶") {
            index = (index + 1).coerceAtMost(chunks.lastIndex)
            refreshLabels()
            scheduleNext()
        }
        val faster = control("＋") {
            speed = (speed + 100).coerceAtMost(1400)
            prefs.edit().putInt("chars_per_minute", speed).apply()
            refreshLabels()
            scheduleNext()
        }
        val close = control("×") {
            mainHandler.removeCallbacks(tick)
            closeReader()
        }

        listOf(slower, prev, playPause, next, faster, close).forEach { button ->
            controls.addView(button, LinearLayout.LayoutParams(0, dp(52), 1f).apply {
                marginStart = dp(2)
                marginEnd = dp(2)
            })
        }

        content.addView(progress, LinearLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        content.addView(word, LinearLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        content.addView(speedLabel, LinearLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(12)
        })
        content.addView(controls, LinearLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        root.addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        readerOverlay = root
        windowManager.addView(root, params)
        refreshLabels()
        scheduleNext()
    }

    private fun advancePage() {
        removeReaderOverlay()
        bubble?.visibility = View.GONE

        mainHandler.postDelayed({
            val semanticMoved = performScrollForward()
            if (semanticMoved) {
                waitForNewPage(0, allowGestureFallback = true)
            } else {
                dispatchNextPageGesture {
                    waitForNewPage(0, allowGestureFallback = false)
                }
            }
        }, 100)
    }

    private fun performScrollForward(): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = findScrollableForwardNode(root) ?: return false
        return target.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    }

    private fun findScrollableForwardNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val supportsForward = node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD }
        if (node.isScrollable || supportsForward) return node

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findScrollableForwardNode(child)
            if (result != null) return result
        }
        return null
    }

    private fun waitForNewPage(attempt: Int, allowGestureFallback: Boolean) {
        mainHandler.postDelayed({
            val nextText = extractCurrentPageText()
            if (nextText.length >= MIN_TEXT_LENGTH && !samePage(nextText, lastPageText)) {
                pendingAfterPageTurn = false
                startPage(nextText, "本文")
                return@postDelayed
            }

            if (attempt < 2) {
                waitForNewPage(attempt + 1, allowGestureFallback)
                return@postDelayed
            }

            if (allowGestureFallback) {
                dispatchNextPageGesture {
                    waitForNewPage(0, allowGestureFallback = false)
                }
            } else {
                requestOcrFallback(afterPageTurn = true)
            }
        }, if (attempt == 0) 650L else 350L)
    }

    private fun dispatchNextPageGesture(onDone: () -> Unit) {
        val metrics = resources.displayMetrics
        val y = metrics.heightPixels * 0.55f
        val path = Path().apply {
            moveTo(metrics.widthPixels * 0.82f, y)
            lineTo(metrics.widthPixels * 0.18f, y)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 260))
            .build()

        val accepted = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    mainHandler.postDelayed(onDone, 120)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    finishWithMessage("自動ページ送りに失敗しました")
                }
            },
            mainHandler
        )

        if (!accepted) {
            finishWithMessage("自動ページ送りに失敗しました")
        }
    }

    private fun requestOcrFallback(afterPageTurn: Boolean) {
        waitingForOcr = true
        pendingAfterPageTurn = afterPageTurn
        mainHandler.removeCallbacks(ocrTimeout)
        mainHandler.postDelayed(ocrTimeout, 2200)

        runCatching {
            startService(
                Intent(this, CaptureService::class.java).apply {
                    action = CaptureService.ACTION_CAPTURE_ONCE
                }
            )
        }.onFailure {
            waitingForOcr = false
            mainHandler.removeCallbacks(ocrTimeout)
            finishWithMessage("本文を直接取得できません。アプリでOCR補助を開始してください")
        }
    }

    private fun samePage(candidate: String, previousNormalized: String): Boolean {
        if (previousNormalized.isBlank()) return false
        return normalizeForCompare(candidate) == previousNormalized
    }

    private fun normalizeForCompare(text: String): String =
        text.replace(Regex("\\s+"), "").trim()

    private fun closeReader() {
        waitingForOcr = false
        pendingAfterPageTurn = false
        mainHandler.removeCallbacksAndMessages(null)
        removeReaderOverlay()
        bubble?.visibility = View.VISIBLE
    }

    private fun finishWithMessage(message: String) {
        waitingForOcr = false
        pendingAfterPageTurn = false
        removeReaderOverlay()
        bubble?.visibility = View.VISIBLE
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun removeReaderOverlay() {
        readerOverlay?.let { runCatching { windowManager.removeView(it) } }
        readerOverlay = null
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val MIN_TEXT_LENGTH = 18
    }
}
