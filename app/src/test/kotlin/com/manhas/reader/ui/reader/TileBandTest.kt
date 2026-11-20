package com.manhas.reader.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Pure checks for [tileBand], the visible-band planner behind adaptive tiled
 * rendering. The band must cover the viewport plus margin, snap its top to the
 * tile step so scrolling does not re-render per pixel, and stay inside both the
 * page and the tile byte budget.
 */
class TileBandTest {
  private val mib = 1024 * 1024

  private fun budget(
    tileBytes: Int = 64 * mib,
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

  @Test
  fun bandCoversViewportWithMarginAndSnapsTopDown() {
    val band = tileBand(
      visibleTopPx = 2000,
      visibleHeightPx = 500,
      pageHeightPx = 10000,
      pageWidthPx = 1080,
      renderWidthPx = 1080,
      budget = budget(),
    )

    assertNotNull(band)
    // 2000 - 640 = 1360 snaps down to 2 * 512.
    assertEquals(1024, band!!.topPx)
    // Height covers the 500 px viewport plus two 640 px margins, rounded up to
    // the next 512 px step (1780 -> 2048).
    assertEquals(2048, band.heightPx)
  }

  @Test
  fun marginIsClippedAtTheTopOfThePage() {
    val band = tileBand(
      visibleTopPx = 0,
      visibleHeightPx = 500,
      pageHeightPx = 10000,
      pageWidthPx = 1080,
      renderWidthPx = 1080,
      budget = budget(),
    )

    assertEquals(0, band!!.topPx)
    assertEquals(2048, band.heightPx)
  }

  @Test
  fun smallVisibleTopSnapsToZero() {
    val band = tileBand(
      visibleTopPx = 1000,
      visibleHeightPx = 500,
      pageHeightPx = 10000,
      pageWidthPx = 1080,
      renderWidthPx = 1080,
      budget = budget(),
    )

    // 1000 - 640 = 360, below one step.
    assertEquals(0, band!!.topPx)
    assertEquals(2048, band.heightPx)
  }

  @Test
  fun lowRamStepAndMarginAreHonored() {
    val band = tileBand(
      visibleTopPx = 1000,
      visibleHeightPx = 500,
      pageHeightPx = 10000,
      pageWidthPx = 1080,
      renderWidthPx = 1080,
      budget = budget(step = 384, margin = 384),
    )

    // 1000 - 384 = 616 snaps down to 384. Height 500 + 2 * 384 = 1268 rounds
    // up to 4 * 384 = 1536.
    assertEquals(384, band!!.topPx)
    assertEquals(1536, band.heightPx)
  }

  @Test
  fun tallBandIsCappedByTileBytesAndCenteredOnViewport() {
    // 8 MiB / (4 bytes * 1080 px) = 1941 rows; the largest 512-step that fits
    // is 3 * 512 = 1536 rows, which map 1:1 to display pixels here.
    val band = tileBand(
      visibleTopPx = 5000,
      visibleHeightPx = 1000,
      pageHeightPx = 10000,
      pageWidthPx = 1080,
      renderWidthPx = 1080,
      budget = budget(tileBytes = 8 * mib),
    )

    assertEquals(1536, band!!.heightPx)
    // Centered on the visible area, then snapped down to the step:
    // 5000 - (1536 - 1000) / 2 = 4732 -> 4608.
    assertEquals(4608, band.topPx)
  }

  @Test
  fun cappedBandIsClampedToTheBottomOfThePage() {
    // 2 MiB / (4 bytes * 1080 px) = 485 rows, below one step.
    val band = tileBand(
      visibleTopPx = 8900,
      visibleHeightPx = 1000,
      pageHeightPx = 9000,
      pageWidthPx = 1080,
      renderWidthPx = 1080,
      budget = budget(tileBytes = 2 * mib),
    )

    assertEquals(485, band!!.heightPx)
    // Centering would put the top past the page bottom; it clamps to 8515.
    assertEquals(8515, band.topPx)
  }

  @Test
  fun bandIsClampedToTheLastPageEdge() {
    val band = tileBand(
      visibleTopPx = 9500,
      visibleHeightPx = 1000,
      pageHeightPx = 10000,
      pageWidthPx = 1080,
      renderWidthPx = 1080,
      budget = budget(),
    )

    assertEquals(8704, band!!.topPx)
    assertEquals(1296, band.heightPx)
  }

  @Test
  fun bandScalesWithRenderWidth() {
    // A 2x render width halves the display height the same bytes can cover.
    val band = tileBand(
      visibleTopPx = 5000,
      visibleHeightPx = 1000,
      pageHeightPx = 20000,
      pageWidthPx = 1080,
      renderWidthPx = 2160,
      budget = budget(tileBytes = 8 * mib),
    )

    // 8 MiB / (4 * 2160) = 970 rows -> 970 * 1080 / 2160 = 485 display px.
    assertEquals(485, band!!.heightPx)
    // Centered at 5000 - (485 - 1000) / 2 = 5257, snapped down to 5120.
    assertEquals(5120, band.topPx)
  }

  @Test
  fun bandIsStableWhileTheViewportMovesWithinOneStep() {
    fun bandAt(visibleTop: Int) = tileBand(
      visibleTopPx = visibleTop,
      visibleHeightPx = 500,
      pageHeightPx = 10000,
      pageWidthPx = 1080,
      renderWidthPx = 1080,
      budget = budget(),
    )

    // Both tops map to the same snapped start (1024) because
    // 1700 - 640 = 1060 and 2100 - 640 = 1460 are in [1024, 1536).
    val first = bandAt(1700)
    val second = bandAt(2100)
    assertEquals(1024, first!!.topPx)
    assertEquals(2048, first.heightPx)
    assertEquals(first, second)

    // 2200 - 640 = 1560 crosses into the next step, so the band moves.
    val crossed = bandAt(2200)
    assertEquals(1536, crossed!!.topPx)
    assertEquals(2048, crossed.heightPx)
    assertNotEquals(first, crossed)
  }

  @Test
  fun nonPositiveDimensionsYieldNoBand() {
    assertNull(tileBand(0, 0, 10000, 1080, 1080, budget()))
    assertNull(tileBand(0, 500, 0, 1080, 1080, budget()))
    assertNull(tileBand(0, 500, 10000, 0, 1080, budget()))
    assertNull(tileBand(0, 500, 10000, 1080, 0, budget()))
    assertNull(tileBand(0, 500, 10000, 1080, 1080, budget(tileBytes = 0)))
  }

  @Test
  fun offscreenVisibleRangeYieldsNoBand() {
    assertNull(tileBand(10000, 500, 10000, 1080, 1080, budget()))
    assertNull(tileBand(-2000, 500, 10000, 1080, 1080, budget()))
  }

  @Test
  fun partiallyVisibleRangeAtTheTopStillProducesABand() {
    val band = tileBand(
      visibleTopPx = -100,
      visibleHeightPx = 500,
      pageHeightPx = 10000,
      pageWidthPx = 1080,
      renderWidthPx = 1080,
      budget = budget(),
    )

    assertEquals(0, band!!.topPx)
    assertEquals(2048, band.heightPx)
  }
}
