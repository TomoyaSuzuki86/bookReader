package com.tomoya.bookreader

import android.graphics.Bitmap

data class BookSummary(val id: String, val title: String, val fileName: String, val lastPage: Int = 0)

data class ReaderUiState(
  val serverUrl: String = BuildConfig.BOOK_SERVER_URL,
  val token: String? = null,
  val email: String = "",
  val books: List<BookSummary> = emptyList(),
  val loading: Boolean = false,
  val error: String? = null,
  val openBook: BookSummary? = null,
  val pageCount: Int = 0,
  val spreadStart: Int = 0,
  val rightBitmap: Bitmap? = null,
  val leftBitmap: Bitmap? = null,
) {
  val isReading: Boolean get() = openBook != null
  val rightPageNumber: Int get() = if (pageCount == 0) 0 else spreadStart + 1
  val leftPageNumber: Int get() = if (spreadStart + 1 < pageCount) spreadStart + 2 else 0
}
