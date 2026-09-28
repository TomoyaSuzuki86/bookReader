package com.tomoya.rsvpreader

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import android.util.DisplayMetrics
import android.view.WindowManager
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

class CaptureService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val networkExecutor = Executors.newSingleThreadExecutor()
    private lateinit var windowManager: WindowManager
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var screenWidth = 0
    private var screenHeight = 0
    private var densityDpi = 0

    private val recognizer by lazy {
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CAPTURE_ONCE -> {
                if (projection != null) captureCurrentScreen()
                else sendOcrResult(null, "OCR")
                return START_NOT_STICKY
            }
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        @Suppress("DEPRECATION")
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

        if (resultCode == Int.MIN_VALUE || resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        startProjection(resultCode, resultData)
        return START_NOT_STICKY
    }

    private fun startProjection(resultCode: Int, resultData: Intent) {
        teardownProjection()
        readScreenMetrics()

        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = manager.getMediaProjection(resultCode, resultData)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                mainHandler.post {
                    teardownProjection()
                    stopSelf()
                }
            }
        }, mainHandler)

        imageReader = ImageReader.newInstance(
            screenWidth,
            screenHeight,
            PixelFormat.RGBA_8888,
            3
        )
        virtualDisplay = projection?.createVirtualDisplay(
            "RSVPReaderCapture",
            screenWidth,
            screenHeight,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            mainHandler
        )
    }

    private fun readScreenMetrics() {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        densityDpi = metrics.densityDpi
    }

    private fun captureCurrentScreen() {
        mainHandler.postDelayed({ acquireImageWithRetry(0) }, 140)
    }

    private fun acquireImageWithRetry(attempt: Int) {
        val image = imageReader?.acquireLatestImage()
        if (image == null) {
            if (attempt < 5) {
                mainHandler.postDelayed(
                    { acquireImageWithRetry(attempt + 1) },
                    80
                )
            } else {
                sendOcrResult(null, "OCR")
            }
            return
        }

        val bitmap = try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * screenWidth
            val paddedWidth = screenWidth + rowPadding / pixelStride
            val padded = Bitmap.createBitmap(
                paddedWidth,
                screenHeight,
                Bitmap.Config.ARGB_8888
            )
            padded.copyPixelsFromBuffer(buffer)
            val cropped = Bitmap.createBitmap(
                padded,
                0,
                0,
                screenWidth,
                screenHeight
            )
            if (cropped !== padded) padded.recycle()
            cropped
        } catch (_: Throwable) {
            null
        } finally {
            image.close()
        }

        if (bitmap == null) {
            sendOcrResult(null, "OCR")
            return
        }

        val prepared = prepareForOcr(bitmap)
        val apiKey = getSharedPreferences(
            MainActivity.CLOUD_PREFS,
            MODE_PRIVATE
        ).getString(MainActivity.PREF_API_KEY, "")
            .orEmpty()
            .trim()

        if (apiKey.isNotBlank()) {
            runCloudVision(
                bitmap = prepared,
                apiKey = apiKey,
                onSuccess = { text ->
                    releaseBitmaps(bitmap, prepared)
                    sendOcrResult(text, "Cloud Vision", null)
                },
                onFailure = { error ->
                    runMlKit(bitmap, prepared, error)
                }
            )
        } else {
            runMlKit(bitmap, prepared)
        }
    }

    private fun runCloudVision(
        bitmap: Bitmap,
        apiKey: String,
        onSuccess: (String) -> Unit,
        onFailure: (String) -> Unit
    ) {
        networkExecutor.execute {
            try {
                val encoded = encodePngBase64(bitmap)
                val request = JSONObject().apply {
                    put(
                        "requests",
                        JSONArray().put(
                            JSONObject().apply {
                                put(
                                    "image",
                                    JSONObject().put("content", encoded)
                                )
                                put(
                                    "features",
                                    JSONArray().put(
                                        JSONObject().put(
                                            "type",
                                            "DOCUMENT_TEXT_DETECTION"
                                        )
                                    )
                                )
                                // Let Vision auto-detect the language.
                            }
                        )
                    )
                }

                val connection = (
                    URL(
                        "https://vision.googleapis.com/v1/images:annotate"
                    ).openConnection() as HttpURLConnection
                ).apply {
                    requestMethod = "POST"
                    connectTimeout = CLOUD_CONNECT_TIMEOUT_MS
                    readTimeout = CLOUD_READ_TIMEOUT_MS
                    doOutput = true
                    setRequestProperty(
                        "Content-Type",
                        "application/json; charset=utf-8"
                    )
                    setRequestProperty(
                        "x-goog-api-key",
                        apiKey
                    )
                }

                try {
                    connection.outputStream.use { stream ->
                        stream.write(
                            request.toString()
                                .toByteArray(StandardCharsets.UTF_8)
                        )
                    }

                    val status = connection.responseCode
                    val responseText = (
                        if (status in 200..299) {
                            connection.inputStream
                        } else {
                            connection.errorStream
                        }
                    )?.bufferedReader()
                        ?.use { it.readText() }
                        .orEmpty()

                    if (status !in 200..299) {
                        throw IllegalStateException(
                            parseVisionError(
                                status,
                                responseText
                            )
                        )
                    }

                    val responseError = parseVisionEmbeddedError(
                        responseText
                    )
                    if (responseError != null) {
                        throw IllegalStateException(responseError)
                    }

                    val text = parseCloudVisionText(responseText)
                    if (text.isBlank()) {
                        throw IllegalStateException(
                            "Cloud Vision returned no text"
                        )
                    }

                    mainHandler.post {
                        onSuccess(
                            TextChunker.normalizeSource(text)
                        )
                    }
                } finally {
                    connection.disconnect()
                }
            } catch (t: Throwable) {
                val message = t.message
                    ?.take(220)
                    ?.ifBlank { null }
                    ?: t.javaClass.simpleName
                mainHandler.post {
                    onFailure(message)
                }
            }
        }
    }

    private fun parseVisionEmbeddedError(
        responseText: String
    ): String? {
        return runCatching {
            val error = JSONObject(responseText)
                .optJSONArray("responses")
                ?.optJSONObject(0)
                ?.optJSONObject("error")
                ?: return@runCatching null

            val code = error.optInt("code")
            val status = error.optString("status")
            val message = error.optString("message")

            buildString {
                append("Vision ")
                if (code > 0) append(code)
                if (status.isNotBlank()) {
                    if (code > 0) append(" ")
                    append(status)
                }
                if (message.isNotBlank()) {
                    append(": ")
                    append(message)
                }
            }.take(220)
        }.getOrNull()
    }

    private fun parseVisionError(
        httpStatus: Int,
        responseText: String
    ): String {
        val apiMessage = runCatching {
            val error = JSONObject(responseText)
                .optJSONObject("error")
            val status = error?.optString("status").orEmpty()
            val message = error?.optString("message").orEmpty()
            buildString {
                append("Vision HTTP ")
                append(httpStatus)
                if (status.isNotBlank()) {
                    append(" ")
                    append(status)
                }
                if (message.isNotBlank()) {
                    append(": ")
                    append(message)
                }
            }
        }.getOrNull()

        return apiMessage
            ?.takeIf { it.length > "Vision HTTP $httpStatus".length }
            ?.take(220)
            ?: "Vision HTTP $httpStatus"
    }

    private data class VisionGlyph(
        val text: String,
        val bounds: Rect,
        val size: Double,
        val isKana: Boolean,
        val isKanji: Boolean,
        val isPunctuation: Boolean,
        val breakAfter: String?
    )

    private fun parseCloudVisionText(
        responseText: String
    ): String {
        val root = JSONObject(responseText)
        val response = root
            .optJSONArray("responses")
            ?.optJSONObject(0)
            ?: return ""

        if (response.has("error")) return ""

        val full =
            response.optJSONObject(
                "fullTextAnnotation"
            )

        val filtered = full?.let {
            extractVisionTextWithoutRuby(it)
        }.orEmpty()

        if (filtered.isNotBlank()) {
            return filtered
        }

        val fullText = full
            ?.optString("text")
            .orEmpty()

        if (fullText.isNotBlank()) {
            return fullText
        }

        return response
            .optJSONArray("textAnnotations")
            ?.optJSONObject(0)
            ?.optString("description")
            .orEmpty()
    }

    private fun extractVisionTextWithoutRuby(
        full: JSONObject
    ): String {
        val pages =
            full.optJSONArray("pages")
                ?: return ""

        val glyphs =
            mutableListOf<VisionGlyph>()

        for (
            pageIndex in
            0 until pages.length()
        ) {
            val page =
                pages.optJSONObject(
                    pageIndex
                ) ?: continue

            val blocks =
                page.optJSONArray("blocks")
                    ?: continue

            for (
                blockIndex in
                0 until blocks.length()
            ) {
                val block =
                    blocks.optJSONObject(
                        blockIndex
                    ) ?: continue

                val paragraphs =
                    block.optJSONArray(
                        "paragraphs"
                    ) ?: continue

                for (
                    paragraphIndex in
                    0 until paragraphs.length()
                ) {
                    val paragraph =
                        paragraphs.optJSONObject(
                            paragraphIndex
                        ) ?: continue

                    val words =
                        paragraph.optJSONArray(
                            "words"
                        ) ?: continue

                    for (
                        wordIndex in
                        0 until words.length()
                    ) {
                        val word =
                            words.optJSONObject(
                                wordIndex
                            ) ?: continue

                        val symbols =
                            word.optJSONArray(
                                "symbols"
                            ) ?: continue

                        for (
                            symbolIndex in
                            0 until symbols.length()
                        ) {
                            val symbol =
                                symbols.optJSONObject(
                                    symbolIndex
                                ) ?: continue

                            val text =
                                symbol.optString(
                                    "text"
                                )

                            if (text.isBlank()) {
                                continue
                            }

                            val bounds =
                                boundingRect(
                                    symbol.optJSONObject(
                                        "boundingBox"
                                    )
                                ) ?: continue

                            val size =
                                estimateSymbolSize(
                                    bounds
                                )

                            if (size <= 0.0) {
                                continue
                            }

                            val ch =
                                text.firstOrNull()
                                    ?: continue

                            glyphs +=
                                VisionGlyph(
                                    text = text,
                                    bounds = bounds,
                                    size = size,
                                    isKana =
                                        isKana(ch),
                                    isKanji =
                                        isKanji(ch),
                                    isPunctuation =
                                        isPunctuation(
                                            ch
                                        ),
                                    breakAfter =
                                        detectedBreak(
                                            symbol
                                        )
                                )
                        }
                    }
                }
            }
        }

        if (glyphs.isEmpty()) {
            return ""
        }

        val textGlyphSizes =
            glyphs
                .filterNot {
                    it.isPunctuation
                }
                .map { it.size }
                .filter { it > 0.0 }
                .sorted()

        if (textGlyphSizes.isEmpty()) {
            return ""
        }

        val bodySize =
            percentile(
                textGlyphSizes,
                BODY_SIZE_PERCENTILE
            )

        val kanjiBody =
            glyphs.filter {
                it.isKanji &&
                    it.size >=
                        bodySize *
                            BASE_GLYPH_MIN_RATIO
            }

        val output =
            StringBuilder()

        for (glyph in glyphs) {
            val removeAsRuby =
                isRubyGlyph(
                    candidate = glyph,
                    bodySize = bodySize,
                    kanjiBody = kanjiBody
                )

            if (!removeAsRuby) {
                output.append(
                    glyph.text
                )

                appendDetectedBreak(
                    output,
                    glyph.breakAfter
                )
            }
        }

        return TextChunker
            .normalizeSource(
                output.toString()
            )
    }

    private fun detectedBreak(
        symbol: JSONObject
    ): String? =
        symbol
            .optJSONObject("property")
            ?.optJSONObject(
                "detectedBreak"
            )
            ?.optString("type")
            ?.takeIf {
                it.isNotBlank()
            }

    private fun appendDetectedBreak(
        output: StringBuilder,
        type: String?
    ) {
        when (type) {
            "SPACE",
            "SURE_SPACE" -> {
                // Keep only a provisional separator. TextChunker removes
                // layout spaces around Japanese text before tokenization.
                output.append(' ')
            }

            "EOL_SURE_SPACE",
            "LINE_BREAK",
            "HYPHEN" -> {
                // These are layout/line-wrap signals, not semantic word
                // boundaries for RSVP. In particular, Vision's HYPHEN
                // denotes an end-line hyphen not present in the source text.
            }
        }
    }

    private fun isRubyGlyph(
        candidate: VisionGlyph,
        bodySize: Double,
        kanjiBody: List<VisionGlyph>
    ): Boolean {
        // Never delete punctuation. This also fixes the v0.7
        // regression where punctuation-only Vision words vanished.
        if (candidate.isPunctuation) {
            return false
        }

        if (!candidate.isKana) {
            return false
        }

        if (
            candidate.size >=
                bodySize *
                    RUBY_SIZE_RATIO
        ) {
            return false
        }

        val attachedToKanji =
            kanjiBody.any { base ->
                isRubyAboveBase(
                    ruby =
                        candidate.bounds,
                    base =
                        base.bounds,
                    bodySize =
                        bodySize
                ) ||
                    isRubyBesideVerticalBase(
                        ruby =
                            candidate.bounds,
                        base =
                            base.bounds,
                        bodySize =
                            bodySize
                    )
            }

        if (attachedToKanji) {
            return true
        }

        // Handles Vision responses where each ruby glyph is
        // detached into its own tiny OCR element.
        return candidate.size <
            bodySize *
                EXTREME_RUBY_SIZE_RATIO
    }

    private fun boundingRect(
        boundingBox: JSONObject?
    ): Rect? {
        val vertices =
            boundingBox
                ?.optJSONArray("vertices")
                ?: return null

        if (vertices.length() < 2) {
            return null
        }

        var minX = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var minY = Int.MAX_VALUE
        var maxY = Int.MIN_VALUE

        for (
            i in
            0 until vertices.length()
        ) {
            val vertex =
                vertices.optJSONObject(i)
                    ?: continue

            val x =
                vertex.optInt("x", 0)

            val y =
                vertex.optInt("y", 0)

            minX = minOf(minX, x)
            maxX = maxOf(maxX, x)
            minY = minOf(minY, y)
            maxY = maxOf(maxY, y)
        }

        if (
            minX == Int.MAX_VALUE ||
            minY == Int.MAX_VALUE ||
            maxX <= minX ||
            maxY <= minY
        ) {
            return null
        }

        return Rect(
            minX,
            minY,
            maxX,
            maxY
        )
    }

    private fun estimateSymbolSize(
        bounds: Rect
    ): Double =
        minOf(
            bounds.width(),
            bounds.height()
        )
            .coerceAtLeast(1)
            .toDouble()

    private fun percentile(
        values: List<Double>,
        position: Double
    ): Double {
        if (values.isEmpty()) {
            return 0.0
        }

        val index =
            (
                values.lastIndex *
                    position
                )
                .toInt()
                .coerceIn(
                    0,
                    values.lastIndex
                )

        return values[index]
    }

    private fun isRubyAboveBase(
        ruby: Rect,
        base: Rect,
        bodySize: Double
    ): Boolean {
        val overlap =
            overlapLength(
                ruby.left,
                ruby.right,
                base.left,
                base.right
            )

        val minWidth =
            minOf(
                ruby.width(),
                base.width()
            ).coerceAtLeast(1)

        val overlapRatio =
            overlap.toDouble() /
                minWidth.toDouble()

        val gap =
            base.top -
                ruby.bottom

        return overlapRatio >=
            RUBY_AXIS_OVERLAP_RATIO &&
            gap >=
                -bodySize *
                    0.30 &&
            gap <=
                bodySize *
                    RUBY_MAX_GAP_RATIO
    }

    private fun isRubyBesideVerticalBase(
        ruby: Rect,
        base: Rect,
        bodySize: Double
    ): Boolean {
        val overlap =
            overlapLength(
                ruby.top,
                ruby.bottom,
                base.top,
                base.bottom
            )

        val minHeight =
            minOf(
                ruby.height(),
                base.height()
            ).coerceAtLeast(1)

        val overlapRatio =
            overlap.toDouble() /
                minHeight.toDouble()

        // Kindle vertical text normally places ruby to the
        // right of its base text.
        val gap =
            ruby.left -
                base.right

        return overlapRatio >=
            RUBY_AXIS_OVERLAP_RATIO &&
            gap >=
                -bodySize *
                    0.30 &&
            gap <=
                bodySize *
                    RUBY_MAX_GAP_RATIO
    }

    private fun overlapLength(
        aStart: Int,
        aEnd: Int,
        bStart: Int,
        bEnd: Int
    ): Int =
        (
            minOf(aEnd, bEnd) -
                maxOf(
                    aStart,
                    bStart
                )
            ).coerceAtLeast(0)

    private fun isKana(
        ch: Char
    ): Boolean =
        ch in '\u3040'..'\u30ff'

    private fun isKanji(
        ch: Char
    ): Boolean =
        ch in '\u3400'..'\u9fff' ||
            ch in '\uf900'..'\ufaff'

    private fun isPunctuation(
        ch: Char
    ): Boolean =
        !ch.isLetterOrDigit() &&
            !isKana(ch) &&
            !isKanji(ch)

    private fun encodePngBase64(
        bitmap: Bitmap
    ): String {
        val output = ByteArrayOutputStream()
        bitmap.compress(
            Bitmap.CompressFormat.PNG,
            100,
            output
        )
        return Base64.encodeToString(
            output.toByteArray(),
            Base64.NO_WRAP
        )
    }

    private fun runMlKit(
        original: Bitmap,
        prepared: Bitmap,
        visionError: String? = null
    ) {
        recognizer.process(
            InputImage.fromBitmap(prepared, 0)
        )
            .addOnSuccessListener { result ->
                sendOcrResult(
                    extractReadingText(
                        result = result,
                        imageWidth = prepared.width,
                        imageHeight = prepared.height
                    ),
                    "ML Kit",
                    visionError
                )
            }
            .addOnFailureListener {
                sendOcrResult(null, "ML Kit", visionError)
            }
            .addOnCompleteListener {
                releaseBitmaps(original, prepared)
            }
    }

    private fun releaseBitmaps(
        original: Bitmap,
        prepared: Bitmap
    ) {
        if (prepared !== original && !prepared.isRecycled) {
            prepared.recycle()
        }
        if (!original.isRecycled) {
            original.recycle()
        }
    }

    private fun prepareForOcr(
        source: Bitmap
    ): Bitmap {
        val top =
            (source.height * 0.035f)
                .toInt()
        val bottom =
            (source.height * 0.965f)
                .toInt()
        val safeTop =
            top.coerceAtLeast(0)
        val cropHeight =
            (bottom - safeTop)
                .coerceAtLeast(1)
                .coerceAtMost(
                    source.height -
                        safeTop
                )

        // Keep the screenshot at native resolution. Artificial upscaling
        // can soften thin Japanese strokes and does not add source detail.
        return Bitmap.createBitmap(
            source,
            0,
            safeTop,
            source.width,
            cropHeight
        )
    }

    private fun extractReadingText(
        result: Text,
        imageWidth: Int,
        imageHeight: Int
    ): String {
        data class Item(
            val text: String,
            val box: Rect
        )

        val topCut = (imageHeight * 0.015f).toInt()
        val bottomCut = (imageHeight * 0.985f).toInt()

        val items = result.textBlocks
            .flatMap { it.lines }
            .mapNotNull { line ->
                val box = line.boundingBox
                    ?: return@mapNotNull null
                val value = TextChunker.normalizeSource(
                    line.text
                )
                if (value.isBlank()) {
                    return@mapNotNull null
                }
                if (
                    box.bottom < topCut ||
                    box.top > bottomCut
                ) {
                    return@mapNotNull null
                }
                if (
                    box.right < 0 ||
                    box.left > imageWidth
                ) {
                    return@mapNotNull null
                }
                Item(value, box)
            }

        if (items.isEmpty()) {
            return TextChunker.normalizeSource(
                result.text
            )
        }

        val verticalRatio = items.count {
            it.box.height() >
                it.box.width() * 1.25f
        }.toFloat() / items.size

        val ordered = if (verticalRatio >= 0.45f) {
            items.sortedWith(
                compareByDescending<Item> {
                    it.box.centerX()
                }.thenBy {
                    it.box.top
                }
            )
        } else {
            items.sortedWith(
                compareBy<Item> {
                    it.box.top
                }.thenBy {
                    it.box.left
                }
            )
        }

        return TextChunker.stitchFragments(
            ordered.map { it.text }
        )
    }

    private fun sendOcrResult(
        text: String?,
        source: String,
        error: String? = null
    ) {
        sendBroadcast(
            Intent(ACTION_OCR_RESULT).apply {
                setPackage(packageName)
                putExtra(EXTRA_OCR_TEXT, text)
                putExtra(EXTRA_OCR_SOURCE, source)
                putExtra(EXTRA_OCR_ERROR, error)
            }
        )
    }

    private fun buildNotification(): android.app.Notification {
        val launchIntent = Intent(
            this,
            MainActivity::class.java
        )
        val pending = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or
                PendingIntent.FLAG_UPDATE_CURRENT
        )
        return android.app.Notification.Builder(
            this,
            CHANNEL_ID
        )
            .setContentTitle(
                "RSVP Reader OCR待機中"
            )
            .setContentText(
                "Cloud Vision優先・ML Kitフォールバック"
            )
            .setSmallIcon(
                android.R.drawable.ic_menu_camera
            )
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(
                NOTIFICATION_SERVICE
            ) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "RSVP Reader OCR",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    private fun teardownProjection() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        projection?.stop()
        projection = null
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        teardownProjection()
        recognizer.close()
        networkExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val ACTION_CAPTURE_ONCE =
            "com.tomoya.rsvpreader.CAPTURE_ONCE"
        const val ACTION_OCR_RESULT =
            "com.tomoya.rsvpreader.OCR_RESULT"
        const val EXTRA_OCR_TEXT = "ocr_text"
        const val EXTRA_OCR_SOURCE = "ocr_source"
        const val EXTRA_OCR_ERROR = "ocr_error"

        private const val CHANNEL_ID = "rsvp_capture"
        private const val NOTIFICATION_ID = 4107
        private const val CLOUD_CONNECT_TIMEOUT_MS = 8_000
        private const val CLOUD_READ_TIMEOUT_MS = 15_000

        private const val BODY_SIZE_PERCENTILE = 0.65
        private const val BASE_GLYPH_MIN_RATIO = 0.84
        private const val RUBY_SIZE_RATIO = 0.76
        private const val EXTREME_RUBY_SIZE_RATIO = 0.48
        private const val RUBY_AXIS_OVERLAP_RATIO = 0.20
        private const val RUBY_MAX_GAP_RATIO = 1.45
    }
}
