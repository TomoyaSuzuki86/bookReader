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
                    maybeRunGemini(
                        original = bitmap,
                        prepared = prepared,
                        ocrText = text,
                        source = "Cloud Vision"
                    )
                },
                onFailure = {
                    runMlKit(bitmap, prepared)
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
        onFailure: () -> Unit
    ) {
        networkExecutor.execute {
            try {
                val encoded = encodeJpegBase64(bitmap)
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
                                put(
                                    "imageContext",
                                    JSONObject().put(
                                        "languageHints",
                                        JSONArray().put("ja")
                                    )
                                )
                            }
                        )
                    )
                }

                val encodedKey = URLEncoder.encode(
                    apiKey,
                    StandardCharsets.UTF_8.name()
                )
                val connection = (
                    URL(
                        "https://vision.googleapis.com/v1/images:annotate?key=$encodedKey"
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
                            "Cloud Vision HTTP $status"
                        )
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
            } catch (_: Throwable) {
                mainHandler.post(onFailure)
            }
        }
    }

    private fun finishWithOptionalGemini(
        original: Bitmap,
        prepared: Bitmap,
        rawText: String,
        source: String
    ) {
        val normalized = TextChunker.normalizeSource(rawText)
        if (normalized.isBlank()) {
            releaseBitmaps(original, prepared)
            sendOcrResult(null, source)
            return
        }

        val geminiKey = getSharedPreferences(
            MainActivity.GEMINI_PREFS,
            MODE_PRIVATE
        ).getString(
            MainActivity.PREF_GEMINI_API_KEY,
            ""
        ).orEmpty().trim()

        if (geminiKey.isBlank()) {
            releaseBitmaps(original, prepared)
            sendOcrResult(normalized, source)
            return
        }

        runGeminiCorrection(
            bitmap = prepared,
            rawText = normalized,
            apiKey = geminiKey,
            onSuccess = { corrected ->
                val accepted = acceptGeminiCorrection(
                    original = normalized,
                    corrected = corrected
                )
                releaseBitmaps(original, prepared)
                if (accepted != null) {
                    sendOcrResult(accepted, "$source + Gemini")
                } else {
                    sendOcrResult(
                        normalized,
                        "$source（Gemini補正破棄）"
                    )
                }
            },
            onFailure = {
                releaseBitmaps(original, prepared)
                sendOcrResult(
                    normalized,
                    "$source（Gemini失敗）"
                )
            }
        )
    }

    private fun runGeminiCorrection(
        bitmap: Bitmap,
        rawText: String,
        apiKey: String,
        onSuccess: (String) -> Unit,
        onFailure: () -> Unit
    ) {
        networkExecutor.execute {
            try {
                val imageBase64 = encodeJpegBase64(bitmap)
                val prompt = """
                    あなたは日本語書籍OCRの校正器です。
                    添付画像の本文を最優先の根拠として、OCR候補の誤認識だけを修正してください。

                    厳守:
                    - 画像にない文章を追加しない。
                    - 言い換え、要約、説明、補完をしない。
                    - 漢字、かな、数字、句読点を画像どおりにする。
                    - 視覚的な折り返し改行は文章として自然につなぐ。
                    - ステータスバー、ページ番号、ボタン、メニューなど本文以外は除外する。
                    - 判別できない箇所を推測して創作しない。
                    - 出力は修正済み本文だけ。前置きやMarkdownは禁止。

                    OCR候補:
                    $rawText
                """.trimIndent()

                val request = JSONObject().apply {
                    put(
                        "contents",
                        JSONArray().put(
                            JSONObject().put(
                                "parts",
                                JSONArray()
                                    .put(
                                        JSONObject().put(
                                            "inline_data",
                                            JSONObject()
                                                .put("mime_type", "image/jpeg")
                                                .put("data", imageBase64)
                                        )
                                    )
                                    .put(
                                        JSONObject().put("text", prompt)
                                    )
                            )
                        )
                    )
                    put(
                        "generationConfig",
                        JSONObject()
                            .put("temperature", 0)
                            .put("maxOutputTokens", 4096)
                    )
                }

                val connection = (
                    URL(
                        "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent"
                    ).openConnection() as HttpURLConnection
                ).apply {
                    requestMethod = "POST"
                    connectTimeout = GEMINI_CONNECT_TIMEOUT_MS
                    readTimeout = GEMINI_READ_TIMEOUT_MS
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
                            "Gemini HTTP $status"
                        )
                    }

                    val corrected = parseGeminiText(responseText)
                    if (corrected.isBlank()) {
                        throw IllegalStateException(
                            "Gemini returned no text"
                        )
                    }

                    mainHandler.post {
                        onSuccess(corrected)
                    }
                } finally {
                    connection.disconnect()
                }
            } catch (_: Throwable) {
                mainHandler.post(onFailure)
            }
        }
    }

    private fun parseGeminiText(responseText: String): String {
        val parts = JSONObject(responseText)
            .optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?: return ""

        val result = StringBuilder()
        for (i in 0 until parts.length()) {
            val value = parts
                .optJSONObject(i)
                ?.optString("text")
                .orEmpty()
            if (value.isNotBlank()) {
                if (result.isNotEmpty()) result.append('\n')
                result.append(value)
            }
        }
        return result.toString().trim()
    }

    private fun acceptGeminiCorrection(
        original: String,
        corrected: String
    ): String? {
        val base = TextChunker.normalizeSource(original).trim()
        val fixed = TextChunker.normalizeSource(corrected).trim()
        if (base.length < 4 || fixed.length < 4) return null

        val lengthRatio =
            fixed.length.toDouble() / base.length.toDouble()
        if (lengthRatio < 0.72 || lengthRatio > 1.28) {
            return null
        }

        val a = compactForDiff(base)
        val b = compactForDiff(fixed)
        val distance = levenshtein(a, b)
        val denominator = maxOf(a.length, b.length)
            .coerceAtLeast(1)
        val changeRatio =
            distance.toDouble() / denominator.toDouble()

        return if (changeRatio <= 0.32) fixed else null
    }

    private fun compactForDiff(text: String): String =
        text.replace(Regex("[\\s　]+"), "")

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        var previous = IntArray(b.length + 1) { it }

        for (i in a.indices) {
            val current = IntArray(b.length + 1)
            current[0] = i + 1

            for (j in b.indices) {
                val cost = if (a[i] == b[j]) 0 else 1
                current[j + 1] = minOf(
                    current[j] + 1,
                    previous[j + 1] + 1,
                    previous[j] + cost
                )
            }
            previous = current
        }

        return previous[b.length]
    }

    private fun parseCloudVisionText(responseText: String): String {
        val root = JSONObject(responseText)
        val response = root.optJSONArray("responses")
            ?.optJSONObject(0)
            ?: return ""

        if (response.has("error")) return ""

        val fullText = response
            .optJSONObject("fullTextAnnotation")
            ?.optString("text")
            .orEmpty()

        if (fullText.isNotBlank()) return fullText

        return response
            .optJSONArray("textAnnotations")
            ?.optJSONObject(0)
            ?.optString("description")
            .orEmpty()
    }

    private fun maybeRunGemini(
        original: Bitmap,
        prepared: Bitmap,
        ocrText: String,
        source: String
    ) {
        val geminiKey = getSharedPreferences(
            MainActivity.CLOUD_PREFS,
            MODE_PRIVATE
        ).getString(
            MainActivity.PREF_GEMINI_API_KEY,
            ""
        ).orEmpty().trim()

        if (geminiKey.isBlank()) {
            releaseBitmaps(original, prepared)
            sendOcrResult(ocrText, source)
            return
        }

        runGeminiVerification(
            bitmap = prepared,
            ocrText = ocrText,
            apiKey = geminiKey,
            onSuccess = { corrected ->
                val useCorrected = isPlausibleCorrection(
                    original = ocrText,
                    corrected = corrected
                )
                val finalText = if (useCorrected) corrected else ocrText
                val finalSource = if (useCorrected) {
                    source + "＋Gemini"
                } else {
                    source + "（Gemini補正破棄）"
                }
                releaseBitmaps(original, prepared)
                sendOcrResult(finalText, finalSource)
            },
            onFailure = {
                releaseBitmaps(original, prepared)
                sendOcrResult(ocrText, source)
            }
        )
    }

    private fun runGeminiVerification(
        bitmap: Bitmap,
        ocrText: String,
        apiKey: String,
        onSuccess: (String) -> Unit,
        onFailure: () -> Unit
    ) {
        networkExecutor.execute {
            try {
                val encoded = encodeJpegBase64(bitmap)
                val request = JSONObject().apply {
                    put(
                        "contents",
                        JSONArray().put(
                            JSONObject().apply {
                                put("role", "user")
                                put(
                                    "parts",
                                    JSONArray()
                                        .put(
                                            JSONObject().put(
                                                "inline_data",
                                                JSONObject()
                                                    .put("mime_type", "image/jpeg")
                                                    .put("data", encoded)
                                            )
                                        )
                                        .put(
                                            JSONObject().put(
                                                "text",
                                                buildGeminiPrompt(ocrText)
                                            )
                                        )
                                )
                            }
                        )
                    )
                    put(
                        "generationConfig",
                        JSONObject()
                            .put("temperature", 0.0)
                            .put("maxOutputTokens", 4096)
                    )
                }

                val connection = (
                    URL(
                        "https://generativelanguage.googleapis.com/v1beta/models/" +
                            GEMINI_MODEL +
                            ":generateContent"
                    ).openConnection() as HttpURLConnection
                ).apply {
                    requestMethod = "POST"
                    connectTimeout = GEMINI_CONNECT_TIMEOUT_MS
                    readTimeout = GEMINI_READ_TIMEOUT_MS
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
                            "Gemini HTTP " + status
                        )
                    }

                    val corrected = parseGeminiText(responseText)
                    if (corrected.isBlank()) {
                        throw IllegalStateException(
                            "Gemini returned no text"
                        )
                    }

                    mainHandler.post {
                        onSuccess(
                            TextChunker.normalizeSource(corrected)
                        )
                    }
                } finally {
                    connection.disconnect()
                }
            } catch (_: Throwable) {
                mainHandler.post(onFailure)
            }
        }
    }

    private fun buildGeminiPrompt(ocrText: String): String =
        "You are a strict OCR verifier for a Japanese ebook page.\n\n" +
            "Compare the attached screenshot with the OCR candidate below and return ONLY the visible main body text from the screenshot.\n\n" +
            "Rules:\n" +
            "- Correct only characters, punctuation, and joins that are visibly wrong in the OCR candidate.\n" +
            "- Never paraphrase, summarize, translate, simplify, rewrite, or invent text.\n" +
            "- Never complete text that is cropped or not visible.\n" +
            "- Ignore app UI, status bars, page controls, progress labels, and buttons.\n" +
            "- Preserve the original Japanese wording and punctuation exactly when readable.\n" +
            "- Visual line wrapping is layout only; join wrapped lines naturally.\n" +
            "- If a character is genuinely unreadable, keep the OCR candidate rather than guessing.\n" +
            "- Return plain text only. No markdown, no explanation, no quotes.\n\n" +
            "OCR candidate:\n" + ocrText

    private fun parseGeminiText(responseText: String): String {
        val root = JSONObject(responseText)
        val candidates = root.optJSONArray("candidates")
            ?: return ""
        val parts = candidates
            .optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?: return ""

        val output = StringBuilder()
        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            if (part.optBoolean("thought", false)) continue
            val text = part.optString("text")
            if (text.isNotBlank()) {
                if (output.isNotEmpty()) output.append('\n')
                output.append(text)
            }
        }
        return output.toString().trim()
    }

    private fun isPlausibleCorrection(
        original: String,
        corrected: String
    ): Boolean {
        val a = compactForComparison(original)
        val b = compactForComparison(corrected)
        if (a.length < 12 || b.length < 12) return false

        val ratio = b.length.toDouble() / a.length.toDouble()
        if (ratio !in 0.68..1.32) return false

        return bigramDice(a, b) >= 0.46
    }

    private fun compactForComparison(text: String): String =
        TextChunker.normalizeSource(text)
            .replace(Regex("[\\s　]"), "")
            .replace(
                Regex(
                    "[「」『』（）()【】［］\\[\\]、。！？!?,.:：；;]"
                ),
                ""
            )

    private fun bigramDice(a: String, b: String): Double {
        if (a.length < 2 || b.length < 2) {
            return if (a == b) 1.0 else 0.0
        }

        val aCounts = mutableMapOf<String, Int>()
        val bCounts = mutableMapOf<String, Int>()

        for (i in 0 until a.length - 1) {
            val gram = a.substring(i, i + 2)
            aCounts[gram] = (aCounts[gram] ?: 0) + 1
        }
        for (i in 0 until b.length - 1) {
            val gram = b.substring(i, i + 2)
            bCounts[gram] = (bCounts[gram] ?: 0) + 1
        }

        var intersection = 0
        for ((gram, countA) in aCounts) {
            val countB = bCounts[gram] ?: 0
            intersection += minOf(countA, countB)
        }

        val total = aCounts.values.sum() + bCounts.values.sum()
        return if (total == 0) {
            0.0
        } else {
            (2.0 * intersection) / total
        }
    }

    private fun encodeJpegBase64(bitmap: Bitmap): String {
        val output = ByteArrayOutputStream()
        bitmap.compress(
            Bitmap.CompressFormat.JPEG,
            CLOUD_JPEG_QUALITY,
            output
        )
        return Base64.encodeToString(
            output.toByteArray(),
            Base64.NO_WRAP
        )
    }

    private fun runMlKit(
        original: Bitmap,
        prepared: Bitmap
    ) {
        recognizer.process(
            InputImage.fromBitmap(prepared, 0)
        )
            .addOnSuccessListener { result ->
                val text = extractReadingText(
                    result = result,
                    imageWidth = prepared.width,
                    imageHeight = prepared.height
                )
                if (text.isBlank()) {
                    releaseBitmaps(original, prepared)
                    sendOcrResult(null, "ML Kit")
                } else {
                    maybeRunGemini(
                        original = original,
                        prepared = prepared,
                        ocrText = text,
                        source = "ML Kit"
                    )
                }
            }
            .addOnFailureListener {
                releaseBitmaps(original, prepared)
                sendOcrResult(null, "ML Kit")
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

    private fun prepareForOcr(source: Bitmap): Bitmap {
        val top = (source.height * 0.035f).toInt()
        val bottom = (source.height * 0.965f).toInt()
        val cropHeight = (bottom - top).coerceAtLeast(1)

        val cropped = Bitmap.createBitmap(
            source,
            0,
            top.coerceAtLeast(0),
            source.width,
            cropHeight.coerceAtMost(
                source.height - top.coerceAtLeast(0)
            )
        )

        val scale = minOf(
            1.6f,
            1800f / cropped.width.toFloat()
        ).coerceAtLeast(1f)

        if (scale <= 1.01f) return cropped

        val scaled = Bitmap.createScaledBitmap(
            cropped,
            (cropped.width * scale).toInt(),
            (cropped.height * scale).toInt(),
            true
        )
        if (scaled !== cropped) cropped.recycle()
        return scaled
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
        source: String
    ) {
        sendBroadcast(
            Intent(ACTION_OCR_RESULT).apply {
                setPackage(packageName)
                putExtra(EXTRA_OCR_TEXT, text)
                putExtra(EXTRA_OCR_SOURCE, source)
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
                "Cloud Vision＋Gemini照合・ML Kitフォールバック"
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

        private const val CHANNEL_ID = "rsvp_capture"
        private const val NOTIFICATION_ID = 4107
        private const val CLOUD_JPEG_QUALITY = 88
        private const val CLOUD_CONNECT_TIMEOUT_MS = 8_000
        private const val CLOUD_READ_TIMEOUT_MS = 15_000
        private const val GEMINI_CONNECT_TIMEOUT_MS = 8_000
        private const val GEMINI_READ_TIMEOUT_MS = 20_000
        private const val GEMINI_MODEL = "gemini-3.8-flash"
    }
}
