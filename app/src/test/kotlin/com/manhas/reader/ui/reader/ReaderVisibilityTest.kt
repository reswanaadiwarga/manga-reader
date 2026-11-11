package com.manhas.reader.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure checks for the reader's visible-page sampling used by progress saves and
 * auto-finish. The list also contains footer/end-of-document items whose
 * indices are >= pageCount; those must never be treated as pages, and a layout
 * with no visible page yields null so callers can skip the update.
 */
class ReaderVisibilityTest {
  @Test
  fun highestVisiblePageForFirstAndMiddlePages() {
    assertEquals(0, lastVisiblePageIndex(listOf(0), pageCount = 10))
    assertEquals(2, lastVisiblePageIndex(listOf(0, 1, 2), pageCount = 10))
    assertEquals(5, lastVisiblePageIndex(listOf(3, 4, 5), pageCount = 10))
  }

  @Test
  fun lastPageWinsWhenFooterIsAlsoVisible() {
    assertEquals(
      9,
      lastVisiblePageIndex(listOf(8, 9, 10), pageCount = 10),
    )
    assertEquals(
      9,
      lastVisiblePageIndex(listOf(8, 9, 10, 11), pageCount = 10),
    )
  }

  @Test
  fun footerAndOutOfRangeIndicesYieldNoPage() {
    assertNull(lastVisiblePageIndex(listOf(10, 11), pageCount = 10))
    assertNull(lastVisiblePageIndex(listOf(-1, 10, 42), pageCount = 10))
  }

  @Test
  fun emptyVisibleListYieldsNoPage() {
    assertNull(lastVisiblePageIndex(emptyList(), pageCount = 10))
  }

  @Test
  fun zeroPageCountYieldsNoPage() {
    assertNull(lastVisiblePageIndex(listOf(0, 1), pageCount = 0))
    assertNull(lastVisiblePageIndex(emptyList(), pageCount = 0))
  }
}
