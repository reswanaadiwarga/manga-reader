package com.manhas.reader.data

import com.manhas.reader.util.PageRotation
import kotlin.math.min
import kotlin.math.roundToInt

/** Bitmap geometry for one archive page render. */
data class ArchiveRenderTarget(
  val width: Int,
  val height: Int,
  val scale: Float,
  val sampleSize: Int,
)

/**
 * Pure fit-to-width geometry for archive pages.
 *
 * The target keeps the page's displayed aspect ratio (quarter turns swap the
 * axes), caps either dimension at [MAX_BITMAP_DIMENSION] like the PDF backend,
 * and chooses the largest power-of-two subsample that still covers the target.
 * Rendering itself uses the result to scale and rotate into the bitmap.
 */
object ArchiveGeometry {
  const val MAX_BITMAP_DIMENSION = 8192f

  fun target(
    pageWidth: Int,
    pageHeight: Int,
    widthPx: Int,
    rotation: Int,
  ): ArchiveRenderTarget? {
    if (pageWidth <= 0 || pageHeight <= 0) return null
    val quarterTurn = PageRotation.isQuarterTurn(rotation)
    val displayWidth = if (quarterTurn) pageHeight else pageWidth
    val displayHeight = if (quarterTurn) pageWidth else pageHeight
    val maxScale = min(
      MAX_BITMAP_DIMENSION / displayWidth,
      MAX_BITMAP_DIMENSION / displayHeight,
    )
    val scale = min(widthPx.coerceAtLeast(1) / displayWidth.toFloat(), maxScale)
    if (scale <= 0f) return null

    val targetWidth = (displayWidth * scale).roundToInt().coerceAtLeast(1)
    val targetHeight = (displayHeight * scale).roundToInt().coerceAtLeast(1)
    return ArchiveRenderTarget(
      width = targetWidth,
      height = targetHeight,
      scale = scale,
      sampleSize = sampleSize(pageWidth, pageHeight, quarterTurn, targetWidth, targetHeight),
    )
  }

  /**
   * Largest power-of-two subsample that still decodes at least the target
   * resolution. Quarter turns map the source width to the target height, hence
   * the swapped comparison axes.
   */
  private fun sampleSize(
    pageWidth: Int,
    pageHeight: Int,
    quarterTurn: Boolean,
    targetWidth: Int,
    targetHeight: Int,
  ): Int {
    val requiredSourceWidth = if (quarterTurn) targetHeight else targetWidth
    val requiredSourceHeight = if (quarterTurn) targetWidth else targetHeight
    var sample = 1
    while (
      pageWidth / (sample * 2) >= requiredSourceWidth &&
      pageHeight / (sample * 2) >= requiredSourceHeight
    ) {
      sample *= 2
    }
    return sample
  }
}
