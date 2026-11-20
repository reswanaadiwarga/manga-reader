package com.manhas.reader.data

import com.manhas.reader.util.PageRotation
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Bitmap geometry for one archive page render. */
data class ArchiveRenderTarget(
  val width: Int,
  val height: Int,
  val scale: Float,
  val sampleSize: Int,
)

/**
 * Source crop and output geometry for one archive visible band.
 *
 * [sourceLeft]..[sourceBottom] are in the original decoded image's pixel
 * coordinates. [targetWidth] x [targetHeight] is the rotated output bitmap,
 * and [sampleSize] is the subsample the decoder may use while still covering
 * that target.
 */
data class ArchiveBandTarget(
  val sourceLeft: Int,
  val sourceTop: Int,
  val sourceRight: Int,
  val sourceBottom: Int,
  val targetWidth: Int,
  val targetHeight: Int,
  val sampleSize: Int,
)

/**
 * Pure fit-to-width and visible-band geometry for archive pages.
 *
 * The full-page target keeps the page's displayed aspect ratio (quarter turns
 * swap the axes), caps either dimension at [MAX_BITMAP_DIMENSION], and can also
 * respect the reader's page pixel budget. The band target maps a vertical
 * fraction of the rotated display back onto the undecoded source image so a
 * tall image can be rendered sharply with [android.graphics.BitmapRegionDecoder].
 *
 * Both targets choose the largest power-of-two subsample that still covers the
 * output, so decode memory stays proportional to the visible result.
 */
object ArchiveGeometry {
  const val MAX_BITMAP_DIMENSION = 8192f

  /** Reject a single band larger than this before asking for the allocation. */
  const val MAX_BAND_BYTES = 64L * 1024 * 1024

  /**
   * Returns the fit-to-width bitmap geometry for one archive page.
   *
   * [maxPagePixels] and [maxPageDimension] mirror the PDF backend's adaptive
   * caps; their defaults preserve the original uncapped-dimension behaviour.
   */
  fun target(
    pageWidth: Int,
    pageHeight: Int,
    widthPx: Int,
    rotation: Int,
    maxPagePixels: Long = Long.MAX_VALUE,
    maxPageDimension: Float = MAX_BITMAP_DIMENSION,
  ): ArchiveRenderTarget? {
    if (pageWidth <= 0 || pageHeight <= 0) return null
    val quarterTurn = PageRotation.isQuarterTurn(rotation)
    val displayWidth = if (quarterTurn) pageHeight else pageWidth
    val displayHeight = if (quarterTurn) pageWidth else pageHeight

    val requestedScale = widthPx.coerceAtLeast(1).toDouble() / displayWidth.toDouble()
    val dimensionScale = minOf(
      maxPageDimension.toDouble() / displayWidth.toDouble(),
      maxPageDimension.toDouble() / displayHeight.toDouble(),
    )
    // Area cap: scale^2 * pageWidth * pageHeight <= maxPagePixels.
    val pixelScale = sqrt(
      maxPagePixels.coerceAtLeast(0L).toDouble() /
        (pageWidth.toDouble() * pageHeight.toDouble()),
    )
    val scale = minOf(requestedScale, dimensionScale, pixelScale)
    if (!scale.isFinite() || scale <= 0.0) return null

    val targetWidth = (displayWidth * scale).roundToInt().coerceAtLeast(1)
    val targetHeight = (displayHeight * scale).roundToInt().coerceAtLeast(1)
    return ArchiveRenderTarget(
      width = targetWidth,
      height = targetHeight,
      scale = scale.toFloat(),
      sampleSize = sampleSize(pageWidth, pageHeight, quarterTurn, targetWidth, targetHeight),
    )
  }

  /**
   * Maps a vertical fraction of the rotated display back to a source crop.
   *
   * [topFraction] and [heightFraction] are clamped to 0..1 and the slice end is
   * clamped to the page. Returns null for invalid page/width/fraction inputs,
   * an empty slice, a target above [MAX_BAND_BYTES], or a crop that rounds away
   * to zero pixels.
   */
  fun band(
    pageWidth: Int,
    pageHeight: Int,
    widthPx: Int,
    rotation: Int,
    topFraction: Float,
    heightFraction: Float,
  ): ArchiveBandTarget? {
    if (pageWidth <= 0 || pageHeight <= 0 || widthPx <= 0) return null
    if (!topFraction.isFinite() || !heightFraction.isFinite()) return null

    val top = topFraction.coerceIn(0f, 1f)
    val requestedHeight = heightFraction.coerceIn(0f, 1f)
    val end = (top + requestedHeight).coerceAtMost(1f)
    val height = end - top
    if (height <= 0f) return null

    val normalized = PageRotation.normalize(rotation)
    val quarterTurn = PageRotation.isQuarterTurn(normalized)
    val displayWidth = if (quarterTurn) pageHeight else pageWidth
    val displayHeight = if (quarterTurn) pageWidth else pageHeight

    val targetWidth = widthPx
    val targetHeight = (widthPx.toDouble() * height * displayHeight / displayWidth)
      .roundToInt()
      .coerceAtLeast(1)
    if (targetWidth.toLong() * targetHeight.toLong() * 4L > MAX_BAND_BYTES) return null

    val sourceLeft: Int
    val sourceTop: Int
    val sourceRight: Int
    val sourceBottom: Int
    when (normalized) {
      90 -> {
        // Clockwise 90: display top maps to the source's left edge.
        sourceLeft = (top * pageWidth).roundToInt()
        sourceRight = (end * pageWidth).roundToInt()
        sourceTop = 0
        sourceBottom = pageHeight
      }
      180 -> {
        // Half turn: display top maps to the source's bottom edge.
        sourceLeft = 0
        sourceRight = pageWidth
        sourceTop = ((1f - end) * pageHeight).roundToInt()
        sourceBottom = ((1f - top) * pageHeight).roundToInt()
      }
      270 -> {
        // Clockwise 270: display top maps to the source's right edge.
        sourceLeft = ((1f - end) * pageWidth).roundToInt()
        sourceRight = ((1f - top) * pageWidth).roundToInt()
        sourceTop = 0
        sourceBottom = pageHeight
      }
      else -> {
        sourceLeft = 0
        sourceRight = pageWidth
        sourceTop = (top * pageHeight).roundToInt()
        sourceBottom = (end * pageHeight).roundToInt()
      }
    }

    val left = sourceLeft.coerceIn(0, pageWidth)
    val right = sourceRight.coerceIn(0, pageWidth)
    val cropTop = sourceTop.coerceIn(0, pageHeight)
    val cropBottom = sourceBottom.coerceIn(0, pageHeight)
    if (right <= left || cropBottom <= cropTop) return null

    return ArchiveBandTarget(
      sourceLeft = left,
      sourceTop = cropTop,
      sourceRight = right,
      sourceBottom = cropBottom,
      targetWidth = targetWidth,
      targetHeight = targetHeight,
      sampleSize = sampleSize(
        sourceWidth = right - left,
        sourceHeight = cropBottom - cropTop,
        quarterTurn = quarterTurn,
        targetWidth = targetWidth,
        targetHeight = targetHeight,
      ),
    )
  }

  /**
   * Largest power-of-two subsample that still decodes at least the target
   * resolution. Quarter turns map the source width to the target height, hence
   * the swapped comparison axes. [sourceWidth]/[sourceHeight] describe the
   * region being decoded (the full page for [target], a crop for [band]).
   */
  private fun sampleSize(
    sourceWidth: Int,
    sourceHeight: Int,
    quarterTurn: Boolean,
    targetWidth: Int,
    targetHeight: Int,
  ): Int {
    val requiredSourceWidth = if (quarterTurn) targetHeight else targetWidth
    val requiredSourceHeight = if (quarterTurn) targetWidth else targetHeight
    var sample = 1
    while (
      sourceWidth / (sample * 2) >= requiredSourceWidth &&
      sourceHeight / (sample * 2) >= requiredSourceHeight
    ) {
      sample *= 2
    }
    return sample
  }
}
