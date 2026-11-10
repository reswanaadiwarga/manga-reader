package com.manhas.reader.data

import android.content.ContentResolver
import android.net.Uri
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.android.AndroidDrawDevice
import com.manhas.reader.util.PageRotation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import android.graphics.Bitmap
import kotlin.math.min

class MuPdfSession(private val resolver: ContentResolver) : DocumentSession {
  private val mutex = Mutex()
  private var document: Document? = null
  private var documentKey: String = ""

  override suspend fun open(uri: Uri, size: Long, mimeType: String?): List<Float> = mutex.withLock {
    closeLocked()
    val opened = withContext(Dispatchers.IO) {
      Document.openDocument(ContentInputStream(resolver, uri, size), mimeType)
    }
    if (opened.needsPassword()) {
      opened.destroy()
      throw IllegalStateException("Password protected PDFs are not supported yet")
    }
    document = opened
    documentKey = uri.toString()
    val count = opened.countPages()
    val ratios = ArrayList<Float>(count)
    withContext(Dispatchers.IO) {
      for (index in 0 until count) {
        val page = opened.loadPage(index)
        try {
          val bounds = page.getBounds()
          val width = bounds.x1 - bounds.x0
          val height = bounds.y1 - bounds.y0
          ratios += if (width > 0f && height > 0f) height / width else 1.4f
        } finally {
          page.destroy()
        }
      }
    }
    ratios
  }

  /**
   * Renders [pageIndex] fit to [widthPx] wide after applying a clockwise
   * [rotation] of 0, 90, 180 or 270 degrees.
   *
   * The matrix is applied by MuPDF, so quarter turns swap the bitmap's
   * dimensions and the whole page stays inside the result. Large pages are
   * downscaled (never cropped) to respect [MAX_BITMAP_DIMENSION].
   */
  override suspend fun render(pageIndex: Int, widthPx: Int, rotation: Int): Bitmap? =
    mutex.withLock {
      val doc = document ?: return@withLock null
      withContext(Dispatchers.IO) {
        val page = doc.loadPage(pageIndex) ?: return@withContext null
        try {
          val normalized = PageRotation.normalize(rotation)
          val bounds = page.getBounds()
          val width = bounds.x1 - bounds.x0
          val height = bounds.y1 - bounds.y0
          if (width <= 0f || height <= 0f) return@withContext null

          val quarterTurn = PageRotation.isQuarterTurn(normalized)
          // The native span that ends up horizontal in the rotated bitmap.
          val sourceWidth = if (quarterTurn) height else width
          val sourceHeight = if (quarterTurn) width else height
          val maxScale = min(
            MAX_BITMAP_DIMENSION / sourceWidth,
            MAX_BITMAP_DIMENSION / sourceHeight,
          )
          val scale = min(widthPx.coerceAtLeast(1) / sourceWidth, maxScale)
          if (scale <= 0f) return@withContext null

          val matrix = Matrix(scale)
          if (normalized != 0) matrix.rotate(normalized.toFloat())
          AndroidDrawDevice.drawPage(page, matrix)
        } finally {
          page.destroy()
        }
      }
    }

  val key: String get() = documentKey

  override suspend fun close() = mutex.withLock { closeLocked() }

  private fun closeLocked() {
    document?.destroy()
    document = null
    documentKey = ""
  }

  private companion object {
    const val MAX_BITMAP_DIMENSION = 8192f
  }
}
