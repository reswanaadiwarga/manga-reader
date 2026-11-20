package com.manhas.reader.data

import android.content.ContentResolver
import android.net.Uri
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.RectI
import com.artifex.mupdf.fitz.android.AndroidDrawDevice
import com.manhas.reader.util.PageRotation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import android.graphics.Bitmap
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * MuPDF-backed [DocumentSession].
 *
 * [render] produces one fit-to-width bitmap per page, optionally bounded by
 * [maxPagePixels] and [maxPageDimension] so the reader's device budget can cap
 * the layout bitmap. [renderBand] produces a short vertical slice at the same
 * width so tall pages can show their visible band at native resolution instead
 * of a single downscaled bitmap.
 *
 * Both render calls serialize on one mutex because MuPDF objects are not
 * thread-safe. [maxPagePixels] defaults to unlimited and [maxPageDimension] to
 * [ReaderRenderLimits.MAX_PAGE_DIMENSION], preserving the untuned behaviour.
 */
class MuPdfSession(
  private val resolver: ContentResolver,
  private val maxPagePixels: Long = Long.MAX_VALUE,
  private val maxPageDimension: Float = ReaderRenderLimits.MAX_PAGE_DIMENSION,
) : DocumentSession {
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
   * downscaled (never cropped) to respect [maxPageDimension] and the
   * [maxPagePixels] area budget. Returns null when the page cannot be decoded
   * or the computed scale is not positive and finite.
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
          val requestedScale = widthPx.coerceAtLeast(1).toDouble() / sourceWidth.toDouble()
          val dimensionScale = minOf(
            maxPageDimension.toDouble() / sourceWidth.toDouble(),
            maxPageDimension.toDouble() / sourceHeight.toDouble(),
          )
          // Area cap: scale^2 * sourceWidth * sourceHeight <= maxPagePixels.
          val pixelScale = sqrt(
            maxPagePixels.coerceAtLeast(0L).toDouble() /
              (sourceWidth.toDouble() * sourceHeight.toDouble()),
          )
          val scale = minOf(requestedScale, dimensionScale, pixelScale)
          if (!scale.isFinite() || scale <= 0.0) return@withContext null

          val matrix = Matrix(scale.toFloat())
          if (normalized != 0) matrix.rotate(normalized.toFloat())
          AndroidDrawDevice.drawPage(page, matrix)
        } finally {
          page.destroy()
        }
      }
    }

  /**
   * Renders the top [topFraction]..[topFraction] + [heightFraction] slice of the
   * rotated page at [widthPx] wide.
   *
   * The tile is placed at display coordinates (0, band top) so the reader can
   * overlay it on the full-page bitmap without any extra offset math. Returns
   * null when the page cannot be decoded, the fractions are non-finite or
   * empty, or the bitmap would exceed [MAX_BAND_BYTES]; it never throws on an
   * allocation failure.
   */
  override suspend fun renderBand(
    pageIndex: Int,
    widthPx: Int,
    rotation: Int,
    topFraction: Float,
    heightFraction: Float,
  ): Bitmap? = mutex.withLock {
    val doc = document ?: return@withLock null
    withContext(Dispatchers.IO) {
      if (widthPx <= 0 || !topFraction.isFinite() || !heightFraction.isFinite()) {
        return@withContext null
      }
      val page = doc.loadPage(pageIndex) ?: return@withContext null
      try {
        val normalized = PageRotation.normalize(rotation)
        val bounds = page.getBounds()
        val pageWidth = bounds.x1 - bounds.x0
        val pageHeight = bounds.y1 - bounds.y0
        if (pageWidth <= 0f || pageHeight <= 0f) return@withContext null

        val quarterTurn = PageRotation.isQuarterTurn(normalized)
        val sourceWidth = if (quarterTurn) pageHeight else pageWidth
        val scale = widthPx.toFloat() / sourceWidth
        if (!scale.isFinite() || scale <= 0f) return@withContext null

        val matrix = Matrix(scale)
        if (normalized != 0) matrix.rotate(normalized.toFloat())
        val ibox = RectI(page.getBounds().transform(matrix))
        val fullWidth = ibox.x1 - ibox.x0
        val fullHeight = ibox.y1 - ibox.y0
        if (fullWidth <= 0 || fullHeight <= 0) return@withContext null

        val top = topFraction.coerceIn(0f, 1f)
        val fraction = heightFraction.coerceIn(0f, 1f)
        if (fraction <= 0f) return@withContext null
        var bandTop = ibox.y0 + (fullHeight.toFloat() * top).roundToInt()
        var bandBottom = ibox.y0 + (fullHeight.toFloat() * (top + fraction)).roundToInt()
        bandTop = bandTop.coerceIn(ibox.y0, ibox.y1)
        bandBottom = bandBottom.coerceIn(ibox.y0, ibox.y1)
        val bandHeight = bandBottom - bandTop
        if (bandHeight <= 0) return@withContext null

        val bytes = fullWidth.toLong() * bandHeight.toLong() * 4L
        if (bytes > MAX_BAND_BYTES) return@withContext null

        val bitmap = try {
          Bitmap.createBitmap(fullWidth, bandHeight, Bitmap.Config.ARGB_8888)
        } catch (throwable: Throwable) {
          return@withContext null
        }
        try {
          val device = AndroidDrawDevice(
            bitmap,
            ibox.x0,
            bandTop,
            0,
            0,
            bitmap.width,
            bitmap.height,
            true,
          )
          try {
            page.run(device, matrix, null)
          } finally {
            try {
              device.close()
            } finally {
              device.destroy()
            }
          }
          bitmap
        } catch (throwable: Throwable) {
          bitmap.recycle()
          null
        }
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
    /** Reject a single tile larger than this before asking for the allocation. */
    const val MAX_BAND_BYTES = 64L * 1024 * 1024
  }
}
