package com.tomoya.bookreader

import android.content.Context
import android.os.Handler
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import java.io.File
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

class ReaderController(context: Context) {
  private val appContext = context.applicationContext
  private val handler = Handler(appContext.mainLooper)
  private val prefs = appContext.getSharedPreferences("bookreader", Context.MODE_PRIVATE)
  private val executor = Executors.newSingleThreadExecutor()
  private val pdfDir = File(appContext.filesDir, "pdfs").apply { mkdirs() }
  private val initialServerUrl = prefs.getString("serverUrl", BuildConfig.BOOK_SERVER_URL) ?: BuildConfig.BOOK_SERVER_URL
  private val initialToken = prefs.getString("token", null)
  private val initialBooks = if (initialToken != null) loadCachedLibrary() else emptyList()
  private val mutable = mutableStateOf(
    ReaderUiState(
      serverUrl = initialServerUrl,
      token = initialToken,
      email = prefs.getString("email", "") ?: "",
      books = initialBooks,
      cachedBookIds = cachedIds(initialBooks),
    )
  )
  val state: State<ReaderUiState> = mutable
  var onReadingChanged: ((Boolean) -> Unit)? = null
  private var renderer: PdfPageRenderer? = null

  private val autoSyncRunnable = object : Runnable {
    override fun run() {
      val snapshot = mutable.value
      if (
        snapshot.token != null &&
        !snapshot.isReading &&
        !snapshot.loading &&
        !snapshot.librarySyncing &&
        isValidServerUrl(snapshot.serverUrl)
      ) {
        refreshBooks(silent = true)
      }
      handler.postDelayed(this, AUTO_SYNC_INTERVAL_MS)
    }
  }

  init {
    if (initialToken != null && isValidServerUrl(initialServerUrl)) refreshBooks(silent = true)
    handler.postDelayed(autoSyncRunnable, AUTO_SYNC_INTERVAL_MS)
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
      persistLibrary(books)
      prefs.edit()
        .putString("serverUrl", normalizedUrl)
        .putString("token", token)
        .putString("email", email.trim())
        .apply()
      update {
        it.copy(
          serverUrl = normalizedUrl,
          token = token,
          email = email.trim(),
          books = books,
          cachedBookIds = cachedIds(books),
          loading = false,
          librarySyncing = false,
          lastLibrarySyncAt = System.currentTimeMillis(),
          error = null,
        )
      }
    }
  }

  fun logout() {
    closeBook()
    prefs.edit().remove("token").remove("email").remove(KEY_LIBRARY_JSON).apply()
    update {
      it.copy(
        token = null,
        email = "",
        books = emptyList(),
        cachedBookIds = emptySet(),
        librarySyncing = false,
        lastLibrarySyncAt = null,
        error = null,
      )
    }
  }

  fun refreshBooks(silent: Boolean = false) {
    val snapshot = mutable.value
    val token = snapshot.token ?: return
    if (!isValidServerUrl(snapshot.serverUrl) || snapshot.librarySyncing) return

    update {
      it.copy(
        loading = if (silent) it.loading else true,
        librarySyncing = true,
        error = if (silent) it.error else null,
      )
    }

    executor.execute {
      try {
        val books = BookApi(snapshot.serverUrl).books(token)
        persistLibrary(books)
        update {
          it.copy(
            books = books,
            cachedBookIds = cachedIds(books),
            loading = false,
            librarySyncing = false,
            lastLibrarySyncAt = System.currentTimeMillis(),
            error = null,
          )
        }
      } catch (t: Throwable) {
        update {
          it.copy(
            loading = false,
            librarySyncing = false,
            error = if (silent) it.error else (t.message ?: "Library refresh failed"),
          )
        }
      }
    }
  }

  fun openBook(book: BookSummary) {
    val snapshot = mutable.value
    val file = File(pdfDir, "${book.id}.pdf")
    runTask {
      if (!file.exists() || file.length() == 0L) {
        val token = snapshot.token ?: error("Sign in to download this PDF")
        if (!isValidServerUrl(snapshot.serverUrl)) error("A valid server URL is required to download this PDF")
        BookApi(snapshot.serverUrl).download(book.id, token, file)
      }

      renderer?.close()
      renderer = PdfPageRenderer(file)
      val pageCount = renderer!!.pageCount
      val start = normalizeSpread(book.lastPage, pageCount)
      update {
        it.copy(
          openBook = book,
          pageCount = pageCount,
          cachedBookIds = it.cachedBookIds + book.id,
          loading = false,
          error = null,
        )
      }
      renderSpread(start, snapshot.token, book, snapshot.serverUrl)
      onMain { onReadingChanged?.invoke(true) }
    }
  }

  fun closeBook() {
    renderer?.close()
    renderer = null
    update {
      it.copy(
        openBook = null,
        pageCount = 0,
        spreadStart = 0,
        leftBitmap = null,
        rightBitmap = null,
      )
    }
    onMain { onReadingChanged?.invoke(false) }
  }

  fun nextSpread() = moveTo(mutable.value.spreadStart + 2)
  fun previousSpread() = moveTo(mutable.value.spreadStart - 2)
  fun jumpTo(pageOneBased: Int) = moveTo((pageOneBased - 1).coerceAtLeast(0))

  fun dispose() {
    handler.removeCallbacks(autoSyncRunnable)
    renderer?.close()
    renderer = null
    executor.shutdownNow()
  }

  private fun moveTo(raw: Int) {
    val snapshot = mutable.value
    val book = snapshot.openBook ?: return
    if (snapshot.pageCount == 0) return
    val start = normalizeSpread(raw, snapshot.pageCount)
    if (start == snapshot.spreadStart) return
    executor.execute { renderSpread(start, snapshot.token, book, snapshot.serverUrl) }
  }

  private fun renderSpread(start: Int, token: String?, book: BookSummary, serverUrl: String) {
    val activeRenderer = renderer ?: return
    val right = activeRenderer.render(start)
    val left = activeRenderer.render(start + 1)
    val updatedBook = book.copy(lastPage = start)
    update {
      it.copy(
        openBook = updatedBook,
        spreadStart = start,
        rightBitmap = right,
        leftBitmap = left,
        loading = false,
        error = null,
        books = it.books.map { existing -> if (existing.id == book.id) updatedBook else existing },
      )
    }
    persistCachedProgress(book.id, start)
    if (token != null && isValidServerUrl(serverUrl)) {
      runCatching { BookApi(serverUrl).saveProgress(book.id, token, start) }
    }
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
        update { it.copy(loading = false, librarySyncing = false, error = t.message ?: "Unexpected error") }
      }
    }
  }

  private fun loadCachedLibrary(): List<BookSummary> {
    val raw = prefs.getString(KEY_LIBRARY_JSON, null) ?: return emptyList()
    return runCatching {
      val arr = JSONArray(raw)
      (0 until arr.length()).map { index ->
        val item = arr.getJSONObject(index)
        BookSummary(
          id = item.getString("id"),
          title = item.getString("title"),
          fileName = item.optString("fileName"),
          lastPage = item.optInt("lastPage", 0),
        )
      }
    }.getOrDefault(emptyList())
  }

  private fun persistLibrary(books: List<BookSummary>) {
    val arr = JSONArray()
    books.forEach { book ->
      arr.put(
        JSONObject()
          .put("id", book.id)
          .put("title", book.title)
          .put("fileName", book.fileName)
          .put("lastPage", book.lastPage)
      )
    }
    prefs.edit().putString(KEY_LIBRARY_JSON, arr.toString()).apply()
  }

  private fun persistCachedProgress(bookId: String, page: Int) {
    val updated = loadCachedLibrary().map { book ->
      if (book.id == bookId) book.copy(lastPage = page) else book
    }
    if (updated.isNotEmpty()) persistLibrary(updated)
  }

  private fun cachedIds(books: List<BookSummary>): Set<String> =
    books.asSequence()
      .filter { File(pdfDir, "${it.id}.pdf").let { file -> file.exists() && file.length() > 0L } }
      .map { it.id }
      .toSet()

  private fun normalizeServerUrl(value: String) = value.trim().trimEnd('/')
  private fun isValidServerUrl(value: String) = value.startsWith("https://") || value.startsWith("http://")
  private fun onMain(block: () -> Unit) = handler.post(block)
  private fun update(block: (ReaderUiState) -> ReaderUiState) = handler.post { mutable.value = block(mutable.value) }

  private companion object {
    const val AUTO_SYNC_INTERVAL_MS = 5_000L
    const val KEY_LIBRARY_JSON = "libraryJson"
  }
}
