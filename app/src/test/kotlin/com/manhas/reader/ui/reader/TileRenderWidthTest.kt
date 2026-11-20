package com.manhas.reader.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure checks for [tileRenderWidth], which trades some zoom width for a tile
 * tall enough to cover the viewport within the device's tile byte budget.
 */
class TileRenderWidthTest {
  private val mib = 1024 * 1024

  private fun budget(
    tileBytes: Int = 16 * mib,
    step: Int = 512,
    margin: Int = 640,
  ) = ReaderRenderBudget(
    pageBytes = 32 * mib,
    pagePixels = 8 * mib,
    tileBytes = tileBytes,
    maxZoomRenderWidth = 2048,
    tileStepPx = step,
    tileMarginPx = margin,
  )

  private val pageWidth = 1080
  private val pageHeight = 20000
  private val viewport = 800
  private val fitWidth = 1080
  private val doubleWidth = 2160

  @Test
  fun highMemoryTwoTimesRequestUsesWiderTileThanFit() {
    val width = tileRenderWidth(
      visibleHeightPx = viewport,
      pageHeightPx = pageHeight,
      pageWidthPx = pageWidth,
      requestedWidthPx = doubleWidth,
      budget = budget(tileBytes = 32 * mib),
    )

    // desiredHeight = 2560; floor(sqrt(32 MiB * 1080 / (4 * 2560))) = 1881.
    assertEquals(1881, width)
    assertTrue(width > fitWidth)
    assertTrue(width < doubleWidth)
  }

  @Test
  fun normalBudgetTwoTimesRequestStillCoversTheViewport() {
    val budget = budget(tileBytes = 16 * mib)
    val width = tileRenderWidth(
      visibleHeightPx = viewport,
      pageHeightPx = pageHeight,
      pageWidthPx = pageWidth,
      requestedWidthPx = doubleWidth,
      budget = budget,
    )

    assertEquals(1330, width)
    assertTrue(width > fitWidth)
    assertTrue(width < doubleWidth)

    val band = tileBand(
      visibleTopPx = 5000,
      visibleHeightPx = viewport,
      pageHeightPx = pageHeight,
      pageWidthPx = pageWidth,
      renderWidthPx = width,
      budget = budget,
    )
    assertNotNull(band)
    assertTrue(band!!.heightPx >= viewport)
    assertTrue(band.topPx <= 5000)
    assertTrue(band.topPx + band.heightPx >= 5000 + viewport)
  }

  @Test
  fun lowRamBudgetWidthStillCoversTheViewport() {
    val budget = budget(tileBytes = 8 * mib, step = 384, margin = 384)
    val width = tileRenderWidth(
      visibleHeightPx = viewport,
      pageHeightPx = pageHeight,
      pageWidthPx = pageWidth,
      requestedWidthPx = 1536,
      budget = budget,
    )

    // desiredHeight = 1920; floor(sqrt(8 MiB * 1080 / (4 * 1920))) = 1086.
    assertEquals(1086, width)
    assertTrue(width > fitWidth)

    val band = tileBand(
      visibleTopPx = 5000,
      visibleHeightPx = viewport,
      pageHeightPx = pageHeight,
      pageWidthPx = pageWidth,
      renderWidthPx = width,
      budget = budget,
    )
    assertNotNull(band)
    assertTrue(band!!.heightPx >= viewport)
    assertTrue(band.topPx <= 5000)
    assertTrue(band.topPx + band.heightPx >= 5000 + viewport)
  }

  @Test
  fun requestedWidthIsNeverExceeded() {
    val budget = budget(tileBytes = 32 * mib)
    assertEquals(
      700,
      tileRenderWidth(viewport, pageHeight, pageWidth, 700, budget),
    )
    assertTrue(
      tileRenderWidth(viewport, pageHeight, pageWidth, doubleWidth, budget) <= doubleWidth,
    )
  }

  @Test
  fun invalidDimensionsOrEmptyBudgetReturnZero() {
    val budget = budget()
    assertEquals(0, tileRenderWidth(0, pageHeight, pageWidth, doubleWidth, budget))
    assertEquals(0, tileRenderWidth(-1, pageHeight, pageWidth, doubleWidth, budget))
    assertEquals(0, tileRenderWidth(viewport, 0, pageWidth, doubleWidth, budget))
    assertEquals(0, tileRenderWidth(viewport, pageHeight, 0, doubleWidth, budget))
    assertEquals(0, tileRenderWidth(viewport, pageHeight, pageWidth, 0, budget))
    assertEquals(0, tileRenderWidth(viewport, pageHeight, pageWidth, -1, budget))
    assertEquals(
      0,
      tileRenderWidth(viewport, pageHeight, pageWidth, doubleWidth, budget(tileBytes = 0)),
    )
    assertEquals(
      0,
      tileRenderWidth(viewport, pageHeight, pageWidth, doubleWidth, budget(tileBytes = 1)),
    )
  }

  @Test
  fun exactFormulaForConcreteCase() {
    val budget = ReaderRenderBudget(
      pageBytes = 32 * mib,
      pagePixels = 8 * mib,
      tileBytes = 1_000_000,
      maxZoomRenderWidth = 2048,
      tileStepPx = 1,
      tileMarginPx = 0,
    )

    // desiredHeight = 1000; floor(sqrt(1_000_000 * 1000 / (4 * 1000))) = 500.
    assertEquals(500, tileRenderWidth(1000, 10_000, 1000, 2000, budget))
  }
}
