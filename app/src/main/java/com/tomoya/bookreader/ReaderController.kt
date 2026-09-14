package com.tomoya.bookreader

import android.content.Context
import android.os.Handler
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import java.io.File
import java.util.concurrent.Executors

class ReaderController(context: Context) {
  private val appContext = context.applicationContext
  private val handler = Handler(appContext.mainLooper)
  private val prefs = appContext.getSharedPreferences("bookreader", Context.MODE_PRIVATE)
  private val executor = Executors.newSingleThreadExecutor()
  private val initialServerUrl = prefs.getString("serverUrl", BuildConfig.BOOK_SERVER_URL) ?: BuildConfig.BOOK_SERVER_URL
  private val mutable = mutableStateOf(
    ReaderUiState(
      serverUrl = initialServerUrl,
      token = prefs.getString("token", null),
      email = prefs.getString("email", "") ?: "",
    )
  )
  val state: State<ReaderUiState> = mutable
  var onReadingChanged: ((Boolean) -> Unit)? = null
  private var renderer: PdfPageRenderer? = null

  init {
    if (mutable.value.token != null && isValidServerUrl(initialServerUrl)) refreshBooks()
  }

  fun login(email: String, password: String, serverUrl: String, register: Boolean = false) {
    val normalizedUrl = normalizeServerUrl(serverUrl)
    if (!isValidServerUrl(normalizedUrl)) {
      update { it.copy(error = "Server URL must start with http:// or https://") }
      return
    }
    runTask {
      val api = BookApi(normalizedUrl)
      val token = if (register) api.register(email, password) else api.login(email, password)
      val books = api.books(token)
      prefs.edit().putString("serverUrl", normalizedUrl).putString("token", token).putString("email", email).apply()
      update { it.copy(serverUrl = normalizedUrl, token = token, email = email, books = books, loading = false, error = null) }
    }
  }

  fun logout() {
    closeBook()
    prefs.edit().remove("token").remove("email").apply()
    update { it.copy(token = null, email = "", books = emptyList(), error = null) }
  }

  fun refreshBooks() {
    val snapshot = mutable.value
    val token = snapshot.token ?: return
    if (!isValidServerUrl(snapshot.serverUrl)) return
    runTask {
      val books = BookApi(snapshot.serverUrl).books(token)
      update { it.copy(books = books, loading = false, error = null) }
    }
  }

  fun openBook(book: BookSummary) {
    val snapshot = mutable.value
    val token = snapshot.token ?: return
    val serverUrl = snapshot.serverUrl
    runTask {
      val pdfDir = File(appContext.filesDir, "pdfs").apply { mkdirs() }
      val file = File(pdfDir, "${book.id}.pdf")
      if (!file.exists() || file.length() == 0L) BookApi(serverUrl).download(book.id, token, file)
      renderer?.close()
      renderer = PdfPageRenderer(file)
      val pageCount = renderer!!.pageCount
      val start = normalizeSpread(book.lastPage, pageCount)
      update { it.copy(openBook = book, pageCount = pageCount, spreadStart = start, loading = false, error = null) }
      renderSpread(start, token, book, serverUrl)
      onMain { onReadingChanged?.invoke(true) }
    }
  }

  fun closeBook() {
    renderer?.close()
    renderer = null
    update { it.copy(openBook = null, pageCount = 0, spreadStart = 0, leftBitmap = null, rightBitmap = null) }
    onMain { onReadingChanged?.invoke(false) }
  }

  fun nextSpread() = moveTo(mutable.value.spreadStart + 2)
  fun previousSpread() = moveTo(mutable.value.spreadStart - 2)
  fun jumpTo(pageOneBased: Int) = moveTo((pageOneBased - 1).coerceAtLeast(0))

  private fun moveTo(raw: Int) {
    val s = mutable.value
    val token = s.token ?: return
    val book = s.openBook ?: return
    if (s.pageCount == 0) return
    val start = normalizeSpread(raw, s.pageCount)
    if (start == s.spreadStart) return
    update { it.copy(spreadStart = start) }
    executor.execute { renderSpread(start, token, book, s.serverUrl) }
  }

  private fun renderSpread(start: Int, token: String, book: BookSummary, serverUrl: String) {
    val r = renderer ?: return
    val right = r.render(start)
    val left = r.render(start + 1)
    update { it.copy(rightBitmap = right, leftBitmap = left, loading = false) }
    runCatching { BookApi(serverUrl).saveProgress(book.id, token, start) }
  }

  private fun normalizeSpread(page: Int, count: Int): Int {
    if (count <= 0) return 0
    val clamped = page.coerceIn(0, count - 1)
    return clamped - (clamped % 2)
  }

  private fun runTask(block: () -> Unit) {
    update { it.copy(loading = true, error = null) }
    executor.execute {
      try {
        block()
      } catch (t: Throwable) {
        update { it.copy(loading = false, error = t.message ?: "Unexpected error") }
      }
    }
  }

  private fun normalizeServerUrl(value: String) = value.trim().trimEnd('/')
  private fun isValidServerUrl(value: String) = value.startsWith("https://") || value.startsWith("http://")
  private fun onMain(block: () -> Unit) = handler.post(block)
  private fun update(block: (ReaderUiState) -> ReaderUiState) = handler.post { mutable.value = block(mutable.value) }
}
