package com.tomoya.bookreader

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File

class PdfPageRenderer(file: File) : Closeable {
  private val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
  private val renderer = PdfRenderer(descriptor)
  val pageCount: Int get() = renderer.pageCount

  fun render(index: Int, targetWidth: Int = 1400): Bitmap? {
    if (index !in 0 until pageCount) return null
    renderer.openPage(index).use { page ->
      val scale = targetWidth.toFloat() / page.width.toFloat()
      val height = (page.height * scale).toInt().coerceAtLeast(1)
      val bitmap = Bitmap.createBitmap(targetWidth, height, Bitmap.Config.ARGB_8888)
      bitmap.eraseColor(Color.WHITE)
      page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
      return bitmap
    }
  }

  override fun close() { renderer.close(); descriptor.close() }
}
