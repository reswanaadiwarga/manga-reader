package com.manhas.reader.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure checks for [baseRenderWidth] and the decision to overlay an adaptive
 * tile. A tile is only used when the full-page base bitmap is actually capped
 * *and* the tile is more than one pixel wider than that base; otherwise the
 * base is already at least as sharp and the overlay is rejected.
 */
class BaseRenderWidthTest {
  private val mib = 1024 * 1024

  private fun budget(
    tileBytes: Int = 16 * mib,
    pagePixels: Int = 8 * mib,
    step: Int = 512,
    margin: Int = 640,
  ) = ReaderRenderBudget(
    pageBytes = pagePixels * 4,
    pagePixels = pagePixels,
    tileBytes = tileBytes,
    maxZoomRenderWidth = 2048,
    tileStepPx = step,
    tileMarginPx = margin,
  )

  private data class TileDecision(
    val baseWidthPx: Int,
    val candidateWidthPx: Int,
    val tileWidthPx: Int,
  )

  /** Mirrors the ReaderPage guard: capped base + tile at least 2 px wider. */
  private fun decide(
    visibleHeightPx: Int,
    pageHeightPx: Int,
    pageWidthPx: Int,
    requestedWidthPx: Int,
    budget: ReaderRenderBudget,
  ): TileDecision {
    val base = baseRenderWidth(pageHeightPx, pageWidthPx, requestedWidthPx, budget)
    val needsTile = base in 1 until requestedWidthPx
    val candidate = if (needsTile) {
      tileRenderWidth(visibleHeightPx, pageHeightPx, pageWidthPx, requestedWidthPx, budget)
    } else {
      0
    }
    val tile = if (candidate > base + 1) candidate else 0
    return TileDecision(base, candidate, tile)
  }

  @Test
  fun sixToOnePageRejectsTileNarrowerThanTheBase() {
    // 1080 x 6480 page, 16 MiB tile budget, 2500 px viewport.
    val decision = decide(
      visibleHeightPx = 2500,
      pageHeightPx = 6480,
      pageWidthPx = 1080,
      requestedWidthPx = 1220,
      budget = budget(),
    )

    // Base is pixel-capped at sqrt(8 MiB / 6) = 1182.
    assertEquals(1182, decision.baseWidthPx)
    // Tile is budget-capped at 1051, which is narrower than the base.
    assertEquals(1051, decision.candidateWidthPx)
    // Rejected: the base stays, because the overlay would be blurrier.
    assertEquals(0, decision.tileWidthPx)
  }

  @Test
  fun tenToOnePageUsesTileBecauseTheBaseIsMoreCapped() {
    // 1080 x 10800 page, same request and viewport as the 6:1 case.
    val decision = decide(
      visibleHeightPx = 2500,
      pageHeightPx = 10800,
      pageWidthPx = 1080,
      requestedWidthPx = 1220,
      budget = budget(),
    )

    // The 8192 px dimension cap binds first: 8192 / 10 = 819.
    assertEquals(819, decision.baseWidthPx)
    assertEquals(1051, decision.candidateWidthPx)
    // Used: the tile is visibly wider than the heavily capped base.
    assertEquals(1051, decision.tileWidthPx)
  }

  @Test
  fun sixToOnePageAtDoubleZoomUsesTileBecauseItBeatsTheBase() {
    // Same 6:1 page and 800 px viewport, 2x render request 2048.
    val decision = decide(
      visibleHeightPx = 800,
      pageHeightPx = 6480,
      pageWidthPx = 1080,
      requestedWidthPx = 2048,
      budget = budget(),
    )

    assertEquals(1182, decision.baseWidthPx)
    // desiredHeight = 2560 -> floor(sqrt(16 MiB * 1080 / (4 * 2560))) = 1330.
    assertEquals(1330, decision.candidateWidthPx)
    // Used: 1330 is 148 px wider than the base.
    assertEquals(1330, decision.tileWidthPx)
  }

  @Test
  fun highMemorySixToOneAtFitWidthHasNoTile() {
    // 16 MiB page budget: the base is not capped at fit width.
    val decision = decide(
      visibleHeightPx = 800,
      pageHeightPx = 6480,
      pageWidthPx = 1080,
      requestedWidthPx = 1080,
      budget = budget(tileBytes = 32 * mib, pagePixels = 16 * mib),
    )

    assertEquals(1080, decision.baseWidthPx)
    assertEquals(0, decision.candidateWidthPx)
    assertEquals(0, decision.tileWidthPx)
  }

  @Test
  fun invalidDimensionsReturnZero() {
    val budget = budget()
    assertEquals(0, baseRenderWidth(0, 1080, 1220, budget))
    assertEquals(0, baseRenderWidth(-6480, 1080, 1220, budget))
    assertEquals(0, baseRenderWidth(6480, 0, 1220, budget))
    assertEquals(0, baseRenderWidth(6480, -1080, 1220, budget))
    assertEquals(0, baseRenderWidth(6480, 1080, 0, budget))
    assertEquals(0, baseRenderWidth(6480, 1080, -1220, budget))
  }
}
