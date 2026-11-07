package com.manhas.reader.data

import android.content.ContentResolver
import android.net.Uri
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.android.AndroidDrawDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import android.graphics.Bitmap
import kotlin.math.roundToInt

class MuPdfSession(private val resolver: ContentResolver) {
  private val mutex = Mutex()
  private var document: Document? = null
  private var documentKey: String = ""

  suspend fun open(uri: Uri, size: Long, mimeType: String?): List<Float> = mutex.withLock {
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

  suspend fun render(pageIndex: Int, widthPx: Int): Bitmap? = mutex.withLock {
    val doc = document ?: return@withLock null
    withContext(Dispatchers.IO) {
      val page = doc.loadPage(pageIndex) ?: return@withContext null
      try {
        val bounds = page.getBounds()
        val width = bounds.x1 - bounds.x0
        val height = bounds.y1 - bounds.y0
        val ratio = if (width > 0f && height > 0f) height / width else 1.4f
        var renderWidth = widthPx
        if (renderWidth * ratio > MAX_BITMAP_DIMENSION) {
          renderWidth = (MAX_BITMAP_DIMENSION / ratio).roundToInt().coerceAtLeast(1)
        }
        AndroidDrawDevice.drawPageFitWidth(page, renderWidth)
      } finally {
        page.destroy()
      }
    }
  }

  val key: String get() = documentKey

  suspend fun close() = mutex.withLock { closeLocked() }

  private fun closeLocked() {
    document?.destroy()
    document = null
    documentKey = ""
  }

  private companion object {
    const val MAX_BITMAP_DIMENSION = 8192f
  }
}
