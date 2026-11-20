package com.manhas.reader.data

import android.graphics.Bitmap
import android.net.Uri

/**
 * One open document backend.
 *
 * [open] replaces any previously opened document, [render] produces one page
 * bitmap at the requested width and clockwise quarter-turn rotation, and
 * [close] releases the document. Implementations serialize their own work, so
 * callers may invoke the suspend functions from any dispatcher.
 */
interface DocumentSession {
  /**
   * Opens [uri] and returns one native height / width ratio per page.
   *
   * [size] and [mimeType] are hints from the content provider; a backend may
   * ignore them. Throws when the document cannot be opened.
   */
  suspend fun open(uri: Uri, size: Long, mimeType: String?): List<Float>

  /**
   * Renders [pageIndex] fit to [widthPx] after a clockwise [rotation] of 0,
   * 90, 180 or 270 degrees. Returns null when the page cannot be decoded; the
   * reader shows its placeholder in that case.
   */
  suspend fun render(pageIndex: Int, widthPx: Int, rotation: Int = 0): Bitmap?

  /**
   * Renders the vertical fraction of [pageIndex]'s rotated page that starts at
   * [topFraction] and spans [heightFraction], both in 0..1 of the page height,
   * fit to [widthPx] wide.
   *
   * Returns null when the backend cannot tile (the default), when the
   * fractions are invalid, or when the requested bitmap would exceed the
   * backend's safety limit. Callers fall back to [render] in that case.
   */
  suspend fun renderBand(
    pageIndex: Int,
    widthPx: Int,
    rotation: Int,
    topFraction: Float,
    heightFraction: Float,
  ): Bitmap? = null

  /** Releases the open document; safe to call repeatedly. */
  suspend fun close()
}
