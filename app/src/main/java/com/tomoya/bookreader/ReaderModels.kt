package com.tomoya.bookreader

import android.graphics.Bitmap

data class BookSummary(
  val id: String,
  val title: String,
  val fileName: String,
  val lastPage: Int = 0,
)

data class ReaderUiState(
  val serverUrl: String = BuildConfig.BOOK_SERVER_URL,
  val token: String? = null,
  val email: String = "",
  val books: List<BookSummary> = emptyList(),
  val cachedBookIds: Set<String> = emptySet(),
  val loading: Boolean = false,
  val librarySyncing: Boolean = false,
  val lastLibrarySyncAt: Long? = null,
  val error: String? = null,
  val openBook: BookSummary? = null,
  val pageCount: Int = 0,
  val spreadStart: Int = 0,
  val rightBitmap: Bitmap? = null,
  val leftBitmap: Bitmap? = null,
  val previousRightBitmap: Bitmap? = null,
  val previousLeftBitmap: Bitmap? = null,
  val nextRightBitmap: Bitmap? = null,
  val nextLeftBitmap: Bitmap? = null,
) {
  val isReading: Boolean get() = openBook != null
  val rightPageNumber: Int get() = if (pageCount == 0) 0 else spreadStart + 1
  val leftPageNumber: Int get() = if (spreadStart + 1 < pageCount) spreadStart + 2 else 0
  val canGoNext: Boolean get() = pageCount > 0 && spreadStart + 2 < pageCount
  val canGoPrevious: Boolean get() = spreadStart > 0
}
