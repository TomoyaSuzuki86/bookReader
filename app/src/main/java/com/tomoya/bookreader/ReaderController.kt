package com.tomoya.bookreader

import android.content.Context
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import java.io.File
import java.util.concurrent.Executors

class ReaderController(context: Context) {
  private val appContext = context.applicationContext
  private val api = BookApi(BuildConfig.BOOK_SERVER_URL)
  private val executor = Executors.newSingleThreadExecutor()
  private val mutable = mutableStateOf(ReaderUiState())
  val state: State<ReaderUiState> = mutable
  var onReadingChanged: ((Boolean) -> Unit)? = null
  private var renderer: PdfPageRenderer? = null

  fun login(email: String, password: String, register: Boolean = false) = runTask {
    val token = if (register) api.register(email, password) else api.login(email, password)
    val books = api.books(token)
    update { it.copy(token = token, email = email, books = books, loading = false, error = null) }
  }

  fun refreshBooks() {
    val token = mutable.value.token ?: return
    runTask { val books = api.books(token); update { it.copy(books = books, loading = false, error = null) } }
  }

  fun openBook(book: BookSummary) {
    val token = mutable.value.token ?: return
    runTask {
      val file = File(appContext.cacheDir, "${book.id}.pdf")
      if (!file.exists()) api.download(book.id, token, file)
      renderer?.close()
      renderer = PdfPageRenderer(file)
      val start = normalizeSpread(book.lastPage, renderer!!.pageCount)
      update { it.copy(openBook = book, pageCount = renderer!!.pageCount, spreadStart = start, loading = false, error = null) }
      renderSpread(start)
      onReadingChanged?.invoke(true)
    }
  }

  fun closeBook() {
    renderer?.close(); renderer = null
    update { it.copy(openBook = null, pageCount = 0, spreadStart = 0, leftBitmap = null, rightBitmap = null) }
    onReadingChanged?.invoke(false)
  }

  fun nextSpread() = moveTo(mutable.value.spreadStart + 2)
  fun previousSpread() = moveTo(mutable.value.spreadStart - 2)
  fun jumpTo(pageOneBased: Int) = moveTo((pageOneBased - 1).coerceAtLeast(0))

  private fun moveTo(raw: Int) {
    val s = mutable.value
    if (!s.isReading || s.pageCount == 0) return
    val start = normalizeSpread(raw, s.pageCount)
    if (start == s.spreadStart) return
    update { it.copy(spreadStart = start) }
    executor.execute { renderSpread(start) }
  }

  private fun renderSpread(start: Int) {
    val r = renderer ?: return
    val right = r.render(start)
    val left = r.render(start + 1)
    update { it.copy(rightBitmap = right, leftBitmap = left, loading = false) }
    val s = mutable.value
    val token = s.token; val book = s.openBook
    if (token != null && book != null) runCatching { api.saveProgress(book.id, token, start) }
  }

  private fun normalizeSpread(page: Int, count: Int): Int {
    if (count <= 0) return 0
    val clamped = page.coerceIn(0, count - 1)
    return clamped - (clamped % 2)
  }

  private fun runTask(block: () -> Unit) {
    update { it.copy(loading = true, error = null) }
    executor.execute {
      try { block() } catch (t: Throwable) { update { it.copy(loading = false, error = t.message ?: "Unexpected error") } }
    }
  }

  private fun update(block: (ReaderUiState) -> ReaderUiState) {
    android.os.Handler(appContext.mainLooper).post { mutable.value = block(mutable.value) }
  }
}
