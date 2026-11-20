package com.manhas.reader.ui.reader

import android.app.ActivityManager
import android.content.Context
import com.manhas.reader.data.ReaderRenderLimits
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Device-adaptive memory limits for reader page rendering.
 *
 * [pageBytes] caps the single full-page bitmap that lays out the document;
 * [pagePixels] is that cap expressed in ARGB pixels. [tileBytes] caps the
 * high-resolution visible-band bitmap drawn on top of tall pages, and
 * [maxZoomRenderWidth] bounds the width of any render so zooming in never
 * allocates an unbounded bitmap.
 *
 * [tileStepPx] and [tileMarginPx] control band planning in [tileBand]: the
 * margin keeps sharp pixels just outside the viewport, and the step snaps band
 * tops so ordinary scrolling does not re-render on every pixel.
 *
 * Instances are plain data; callers may compute them once per device.
 */
data class ReaderRenderBudget(
  val pageBytes: Int,
  val pagePixels: Int,
  val tileBytes: Int,
  val maxZoomRenderWidth: Int,
  val tileStepPx: Int,
  val tileMarginPx: Int,
) {
  companion object {
    /**
     * Builds a budget for the current device.
     *
     * Reads [ActivityManager.isLowRamDevice] and [ActivityManager.memoryClass]
     * and the runtime heap ceiling ([Runtime.maxMemory]). The smaller of the
     * runtime heap and the declared heap class is used, so a device that
     * reports a larger runtime heap than its class cannot inflate the budget.
     * Falls back to the runtime heap when the activity manager is unavailable.
     */
    fun create(context: Context): ReaderRenderBudget {
      val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
      val runtimeMax = Runtime.getRuntime().maxMemory()
      val classMax = (activityManager?.memoryClass ?: 0).toLong() * MIB
      val maxMemory = if (classMax > 0L) minOf(runtimeMax, classMax) else runtimeMax
      return forDevice(
        maxMemory = maxMemory,
        isLowRam = activityManager?.isLowRamDevice == true,
        memoryClass = activityManager?.memoryClass ?: 0,
      )
    }

    /**
     * Derives a budget from [maxMemory] and the device class.
     *
     * This function is pure so the limits can be unit-tested without an
     * Android runtime. [memoryClass] is carried for diagnostics; the rules
     * below depend only on [maxMemory] and [isLowRam].
     */
    fun forDevice(maxMemory: Long, isLowRam: Boolean, memoryClass: Int): ReaderRenderBudget {
      val pageBytes = (maxMemory / 8).coerceIn(16 * MIB, 64 * MIB).toInt()
      val tileBytes = (maxMemory / 16).coerceIn(8 * MIB, 32 * MIB).toInt()
      return ReaderRenderBudget(
        pageBytes = pageBytes,
        pagePixels = pageBytes / 4,
        tileBytes = tileBytes,
        maxZoomRenderWidth = when {
          isLowRam -> 1536
          maxMemory >= 512 * MIB -> 3072
          else -> 2048
        },
        tileStepPx = if (isLowRam) 384 else 512,
        tileMarginPx = if (isLowRam) 384 else 640,
      )
    }
  }
}

/** A vertical slice of the rotated page, in display pixels. */
data class TileBand(val topPx: Int, val heightPx: Int)

/**
 * Plans the high-resolution band to render for the current viewport.
 *
 * The band covers the visible area plus [ReaderRenderBudget.tileMarginPx] on
 * both sides. Both its start and its height are snapped to
 * [ReaderRenderBudget.tileStepPx] multiples, so scrolling within one step
 * produces the same band (and therefore no re-render); only a scroll that
 * crosses a step boundary moves it. Its bitmap height is capped by
 * [ReaderRenderBudget.tileBytes] for a [renderWidthPx]-wide render; when that
 * cap applies the largest step-sized height is used and the band is centered
 * on the viewport.
 *
 * Returns null when the page or viewport has no area, the viewport is entirely
 * off the page, the tile budget cannot hold a single pixel row, or no positive
 * band remains after clamping.
 */
fun tileBand(
  visibleTopPx: Int,
  visibleHeightPx: Int,
  pageHeightPx: Int,
  pageWidthPx: Int,
  renderWidthPx: Int,
  budget: ReaderRenderBudget,
): TileBand? {
  if (pageWidthPx <= 0 || pageHeightPx <= 0 || renderWidthPx <= 0) return null
  if (visibleHeightPx <= 0) return null
  if (visibleTopPx >= pageHeightPx) return null
  if (visibleTopPx.toLong() + visibleHeightPx.toLong() <= 0L) return null

  val margin = budget.tileMarginPx.coerceAtLeast(0)
  val step = budget.tileStepPx.coerceAtLeast(1)

  var top = ((visibleTopPx - margin).coerceAtLeast(0) / step) * step
  val desiredHeight = roundUpToStep(visibleHeightPx.toLong() + 2L * margin, step)
  val bottom = minOf(pageHeightPx.toLong(), top.toLong() + desiredHeight).toInt()
  if (bottom <= top) return null
  var height = bottom - top

  val maxBitmapHeight = budget.tileBytes.toLong().coerceAtLeast(0L) / (4L * renderWidthPx)
  val maxDisplayHeight = (maxBitmapHeight * pageWidthPx / renderWidthPx).toInt()
  if (maxDisplayHeight <= 0) return null
  if (height > maxDisplayHeight) {
    // Largest step-sized height that still fits the byte budget.
    height = ((maxDisplayHeight / step) * step)
      .coerceAtLeast(step)
      .coerceAtMost(maxDisplayHeight)
    top = ((visibleTopPx - (height - visibleHeightPx) / 2).coerceAtLeast(0) / step) * step
  }

  top = top.coerceIn(0, (pageHeightPx - height).coerceAtLeast(0))
  height = height.coerceAtMost(pageHeightPx - top)
  return if (height > 0) TileBand(top, height) else null
}

/** Rounds [value] up to the next multiple of [step]. */
private fun roundUpToStep(value: Long, step: Int): Long {
  val size = step.toLong()
  return (value + size - 1) / size * size
}

/**
 * Chooses a tile render width that lets one band cover the viewport within
 * [ReaderRenderBudget.tileBytes].
 *
 * At a fixed byte budget, a narrower tile has fewer bitmap rows, so the same
 * memory covers more display height. This returns the widest width, capped at
 * [requestedWidthPx], for which the planned band still fits the budget. The
 * caller then plans the band with [tileBand] at this width and renders it
 * through the backend's band renderer.
 *
 * Returns 0 when any dimension is non-positive or the budget cannot hold a
 * single pixel row.
 */
fun tileRenderWidth(
  visibleHeightPx: Int,
  pageHeightPx: Int,
  pageWidthPx: Int,
  requestedWidthPx: Int,
  budget: ReaderRenderBudget,
): Int {
  if (visibleHeightPx <= 0 || pageHeightPx <= 0 || pageWidthPx <= 0 || requestedWidthPx <= 0) {
    return 0
  }
  val margin = budget.tileMarginPx.coerceAtLeast(0)
  val step = budget.tileStepPx.coerceAtLeast(1)
  val desiredHeight = minOf(
    pageHeightPx.toLong(),
    roundUpToStep(visibleHeightPx.toLong() + 2L * margin, step),
  )
  if (desiredHeight <= 0L) return 0

  // bytes = 4 * width^2 * desiredHeight / pageWidthPx  ->  solve for width.
  val bytes = budget.tileBytes.toDouble().coerceAtLeast(0.0)
  val fitWidth = floor(
    sqrt(bytes * pageWidthPx.toDouble() / (4.0 * desiredHeight.toDouble())),
  ).toInt()
  if (fitWidth <= 0) return 0
  return fitWidth.coerceAtMost(requestedWidthPx)
}

/**
 * Returns the width of the full-page bitmap the PDF backend will actually
 * produce for this page, after the edge and pixel budgets are applied.
 *
 * The request is capped by the 8192 px edge limit (`MAX_PAGE_DIMENSION /
 * ratio`) and by the page pixel budget (`sqrt(pagePixels / ratio)`), where
 * `ratio` is the displayed page height divided by its width. The result is at
 * least 1 when the inputs are positive. Returns 0 for non-positive inputs.
 */
fun baseRenderWidth(
  pageHeightPx: Int,
  pageWidthPx: Int,
  requestedWidthPx: Int,
  budget: ReaderRenderBudget,
): Int {
  if (pageHeightPx <= 0 || pageWidthPx <= 0 || requestedWidthPx <= 0) return 0
  val ratio = pageHeightPx.toDouble() / pageWidthPx.toDouble()
  val byDimension = ReaderRenderLimits.MAX_PAGE_DIMENSION.toDouble() / ratio
  val byPixels = sqrt(budget.pagePixels.coerceAtLeast(0).toDouble() / ratio)
  return minOf(requestedWidthPx.toDouble(), byDimension, byPixels)
    .roundToInt()
    .coerceAtLeast(1)
}

private const val MIB = 1024L * 1024L
