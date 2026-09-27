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

    private data class TextBlock(
        val text: String,
        val bounds: Rect
    )

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

            val text = TextChunker.normalizeSource(
                intent.getStringExtra(CaptureService.EXTRA_OCR_TEXT).orEmpty()
            )

            if (text.length < MIN_TEXT_LENGTH) {
                finishWithMessage("本文を取得できませんでした。OCR補助を有効にしているか確認してください")
                return
            }

            if (pendingAfterPageTurn && samePage(text, lastPageText)) {
                finishWithMessage("次ページを確認できませんでした")
                return
            }

            val source = intent.getStringExtra(
                CaptureService.EXTRA_OCR_SOURCE
            ).orEmpty().ifBlank { "OCR" }
            val visionError = intent.getStringExtra(
                CaptureService.EXTRA_OCR_ERROR
            ).orEmpty()

            if (visionError.isNotBlank()) {
                Toast.makeText(
                    this@ReaderAccessibilityService,
                    "Cloud Vision失敗 → ML Kit\n$visionError",
                    Toast.LENGTH_LONG
                ).show()
            }

            pendingAfterPageTurn = false
            startPage(
                text,
                if (visionError.isBlank()) {
                    source
                } else {
                    "$source（Vision失敗）"
                }
            )
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

        val blocks = collectBestBlocks(root)
        if (blocks.isEmpty()) return ""

        val verticalRatio = blocks.count {
            it.bounds.height() > it.bounds.width() * 1.2f
        }.toFloat() / blocks.size

        val ordered = if (verticalRatio >= 0.45f) {
            blocks.sortedWith(
                compareByDescending<TextBlock> { it.bounds.centerX() }
                    .thenBy { it.bounds.top }
            )
        } else {
            blocks.sortedWith(
                compareBy<TextBlock> { it.bounds.top }
                    .thenBy { it.bounds.left }
            )
        }

        val parts = mutableListOf<String>()
        var previousCompact: String? = null

        for (block in ordered) {
            val value = TextChunker.normalizeSource(block.text)
            if (value.isBlank()) continue

            val compact = normalizeForCompare(value)
            if (compact.isBlank()) continue
            if (compact == previousCompact) continue

            parts += value
            previousCompact = compact
        }

        return TextChunker.stitchFragments(parts)
    }

    private fun collectBestBlocks(node: AccessibilityNodeInfo): List<TextBlock> {
        if (!node.isVisibleToUser || !isReadingArea(node)) return emptyList()

        val childBlocks = mutableListOf<TextBlock>()
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            childBlocks += collectBestBlocks(child)
        }

        val ownText = node.text?.toString()?.takeIf { it.isNotBlank() }
            ?: node.contentDescription?.toString()?.takeIf { it.isNotBlank() }

        if (ownText == null || !looksLikeBodyText(node, ownText)) {
            return childBlocks
        }

        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val normalizedOwn = TextChunker.normalizeSource(ownText)
        if (normalizedOwn.isBlank()) return childBlocks

        if (childBlocks.isEmpty()) {
            return listOf(TextBlock(normalizedOwn, bounds))
        }

        val childText = TextChunker.stitchFragments(childBlocks.map { it.text })
        val ownCompact = normalizeForCompare(normalizedOwn)
        val childCompact = normalizeForCompare(childText)

        val screenArea = resources.displayMetrics.widthPixels.toLong() *
            resources.displayMetrics.heightPixels.toLong()
        val nodeArea = bounds.width().toLong().coerceAtLeast(0L) *
            bounds.height().toLong().coerceAtLeast(0L)
        val almostFullScreen = screenArea > 0L &&
            nodeArea.toDouble() / screenArea.toDouble() > 0.82

        val parentLooksLikeParagraph =
            !almostFullScreen &&
            ownCompact.length >= PARAGRAPH_MIN_LENGTH &&
            childCompact.isNotBlank() &&
            ownCompact.length >= (childCompact.length * 0.72f).toInt()

        return if (parentLooksLikeParagraph) {
            listOf(TextBlock(normalizedOwn, bounds))
        } else {
            childBlocks
        }
    }

    private fun looksLikeBodyText(node: AccessibilityNodeInfo, raw: String): Boolean {
        val text = TextChunker.normalizeSource(raw)
        if (text.length < 2) return false

        val className = node.className?.toString().orEmpty()
        if (className.contains("Button", ignoreCase = true)) return false
        if (className.contains("ImageView", ignoreCase = true)) return false
        if (node.isClickable && text.length < 16) return false

        if (text.matches(Regex("^[0-9０-９%％/・:：.\\-]+$"))) return false

        val readableChars = text.count { ch ->
            ch.isLetterOrDigit() ||
                ch in '\u3040'..'\u30ff' ||
                ch in '\u3400'..'\u9fff'
        }
        return readableChars >= 2
    }

    private fun isReadingArea(node: AccessibilityNodeInfo): Boolean {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val height = resources.displayMetrics.heightPixels
        val width = resources.displayMetrics.widthPixels
        if (bounds.isEmpty) return false

        val topCut = (height * 0.035f).toInt()
        val bottomCut = (height * 0.97f).toInt()
        if (bounds.bottom < topCut || bounds.top > bottomCut) return false
        if (bounds.right < 0 || bounds.left > width) return false
        return true
    }

    private fun startPage(text: String, source: String) {
        val cleaned = TextChunker.normalizeSource(text)
        val chunks = TextChunker.chunk(cleaned)

        if (chunks.isEmpty()) {
            finishWithMessage("表示できる文章がありません")
            return
        }

        lastPageText = normalizeForCompare(cleaned)
        showReader(chunks, source)
    }

    private fun showReader(chunks: List<String>, source: String) {
        removeReaderOverlay()
        bubble?.visibility = View.GONE

        val prefs = getSharedPreferences("reader", Context.MODE_PRIVATE)
        var speed = prefs.getInt("chars_per_minute", 650).coerceIn(200, 1400)
        var swipeLeft = prefs.getBoolean(PREF_SWIPE_LEFT, true)
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
            setPadding(dp(12), dp(54), dp(12), dp(54))
        }

        val speedLabel = TextView(this).apply {
            setTextColor(Color.rgb(190, 198, 210))
            textSize = 14f
            gravity = Gravity.CENTER
        }

        val directionButton = Button(this).apply {
            textSize = 14f
            minHeight = 0
            minimumHeight = 0
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

        fun refreshDirection() {
            directionButton.text = if (swipeLeft) {
                "自動ページ送り：← 左へスワイプ"
            } else {
                "自動ページ送り：右へスワイプ →"
            }
        }

        fun refreshLabels(extra: String? = null) {
            index = index.coerceIn(0, chunks.lastIndex)
            word.text = chunks[index]
            progress.text = "${index + 1} / ${chunks.size}  ·  $source"
            speedLabel.text = extra ?: "$speed 文字/分  ·  読了時に自動で次ページ"
            refreshDirection()
        }

        fun scheduleNext() {
            mainHandler.removeCallbacks(tick)
            if (!playing) return

            if (index >= chunks.lastIndex) {
                refreshLabels("次ページを取得中…")
                mainHandler.postDelayed({ advancePage(swipeLeft) }, 220)
                return
            }

            val current = chunks[index]
            val base = (60_000.0 * current.length.coerceAtLeast(2) / speed).toLong()
            val punctuationBonus = when {
                current.endsWith('。') || current.endsWith('！') || current.endsWith('？') -> 280L
                current.endsWith('、') -> 130L
                else -> 0L
            }
            mainHandler.postDelayed(
                tick,
                (base + punctuationBonus).coerceIn(150L, 1900L)
            )
        }

        tick = Runnable {
            if (!playing) return@Runnable

            if (index < chunks.lastIndex) {
                index++
                refreshLabels()
                scheduleNext()
            } else {
                scheduleNext()
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

        directionButton.setOnClickListener {
            swipeLeft = !swipeLeft
            prefs.edit().putBoolean(PREF_SWIPE_LEFT, swipeLeft).apply()
            refreshDirection()
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
            controls.addView(
                button,
                LinearLayout.LayoutParams(0, dp(52), 1f).apply {
                    marginStart = dp(2)
                    marginEnd = dp(2)
                }
            )
        }

        content.addView(
            progress,
            LinearLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )
        content.addView(
            word,
            LinearLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )
        content.addView(
            speedLabel,
            LinearLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(8)
            }
        )
        content.addView(
            directionButton,
            LinearLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(46)
            ).apply {
                bottomMargin = dp(8)
            }
        )
        content.addView(
            controls,
            LinearLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(
            content,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

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

    private fun advancePage(swipeLeft: Boolean) {
        removeReaderOverlay()
        bubble?.visibility = View.GONE

        mainHandler.postDelayed({
            dispatchPageGesture(swipeLeft) {
                waitForNewPage(
                    attempt = 0,
                    allowSemanticFallback = true
                )
            }
        }, 100)
    }

    private fun performScrollForward(): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = findScrollableForwardNode(root) ?: return false
        return target.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    }

    private fun findScrollableForwardNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val supportsForward = node.actionList.any {
            it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        }
        if (node.isScrollable || supportsForward) return node

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findScrollableForwardNode(child)
            if (result != null) return result
        }
        return null
    }

    private fun waitForNewPage(
        attempt: Int,
        allowSemanticFallback: Boolean
    ) {
        mainHandler.postDelayed({
            val nextText = extractCurrentPageText()

            if (
                nextText.length >= MIN_TEXT_LENGTH &&
                !samePage(nextText, lastPageText)
            ) {
                pendingAfterPageTurn = false
                startPage(nextText, "本文")
                return@postDelayed
            }

            if (attempt < 2) {
                waitForNewPage(
                    attempt = attempt + 1,
                    allowSemanticFallback = allowSemanticFallback
                )
                return@postDelayed
            }

            if (allowSemanticFallback && performScrollForward()) {
                waitForNewPage(
                    attempt = 0,
                    allowSemanticFallback = false
                )
            } else {
                requestOcrFallback(afterPageTurn = true)
            }
        }, if (attempt == 0) 650L else 350L)
    }

    private fun dispatchPageGesture(
        swipeLeft: Boolean,
        onDone: () -> Unit
    ) {
        val metrics = resources.displayMetrics
        val y = metrics.heightPixels * 0.55f
        val startX = if (swipeLeft) {
            metrics.widthPixels * 0.82f
        } else {
            metrics.widthPixels * 0.18f
        }
        val endX = if (swipeLeft) {
            metrics.widthPixels * 0.18f
        } else {
            metrics.widthPixels * 0.82f
        }

        val path = Path().apply {
            moveTo(startX, y)
            lineTo(endX, y)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    path,
                    0,
                    260
                )
            )
            .build()

        val accepted = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(
                    gestureDescription: GestureDescription?
                ) {
                    mainHandler.postDelayed(onDone, 120)
                }

                override fun onCancelled(
                    gestureDescription: GestureDescription?
                ) {
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
        mainHandler.postDelayed(ocrTimeout, 18_000)

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

    private fun samePage(
        candidate: String,
        previousNormalized: String
    ): Boolean {
        if (previousNormalized.isBlank()) return false
        return normalizeForCompare(candidate) == previousNormalized
    }

    private fun normalizeForCompare(text: String): String =
        TextChunker.normalizeSource(text)
            .replace(" ", "")
            .trim()

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
        readerOverlay?.let {
            runCatching { windowManager.removeView(it) }
        }
        readerOverlay = null
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val MIN_TEXT_LENGTH = 18
        private const val PARAGRAPH_MIN_LENGTH = 14
        private const val PREF_SWIPE_LEFT = "page_turn_swipe_left"
    }
}
