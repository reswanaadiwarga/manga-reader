package com.manhas.reader.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageRotationTest {
  @Test
  fun normalizeKeepsCanonicalQuarterTurns() {
    listOf(0, 90, 180, 270).forEach {
      assertEquals(it, PageRotation.normalize(it))
    }
  }

  @Test
  fun normalizeWrapsNegativeAndOverflowingAngles() {
    assertEquals(270, PageRotation.normalize(-90))
    assertEquals(180, PageRotation.normalize(-180))
    assertEquals(270, PageRotation.normalize(-450))
    assertEquals(0, PageRotation.normalize(360))
    assertEquals(90, PageRotation.normalize(450))
    assertEquals(90, PageRotation.normalize(810))
    assertEquals(127, PageRotation.normalize(Int.MAX_VALUE))
    assertEquals(232, PageRotation.normalize(Int.MIN_VALUE))
  }

  @Test
  fun nextAdvancesOneQuarterTurnAndWraps() {
    assertEquals(90, PageRotation.next(0))
    assertEquals(180, PageRotation.next(90))
    assertEquals(270, PageRotation.next(180))
    assertEquals(0, PageRotation.next(270))
    assertEquals(0, PageRotation.next(-90))
    assertEquals(180, PageRotation.next(450))
    assertEquals(217, PageRotation.next(Int.MAX_VALUE))
  }

  @Test
  fun quarterTurnOnlyForNinetyAndTwoSeventy() {
    assertFalse(PageRotation.isQuarterTurn(0))
    assertTrue(PageRotation.isQuarterTurn(90))
    assertFalse(PageRotation.isQuarterTurn(180))
    assertTrue(PageRotation.isQuarterTurn(270))
    assertTrue(PageRotation.isQuarterTurn(450))
    assertFalse(PageRotation.isQuarterTurn(-180))
  }

  @Test
  fun displayRatioSwapsOnlyForQuarterTurns() {
    assertEquals(1.5f, PageRotation.displayRatio(1.5f, 0), 0f)
    assertEquals(1.5f, PageRotation.displayRatio(1.5f, 180), 0f)
    assertEquals(1.5f / 1f, PageRotation.displayRatio(1.5f, 360), 0f)
    assertEquals(1f / 1.5f, PageRotation.displayRatio(1.5f, 90), 1e-6f)
    assertEquals(1f / 1.5f, PageRotation.displayRatio(1.5f, 270), 1e-6f)
    assertEquals(0.5f, PageRotation.displayRatio(2f, 90), 1e-6f)
    assertEquals(2f, PageRotation.displayRatio(2f, 180), 0f)
  }

  @Test
  fun appliedRotationPrefersPageOverrideOverDocumentDefault() {
    assertEquals(90, PageRotation.applied(null, 90))
    assertEquals(180, PageRotation.applied(180, 0))
    assertEquals(270, PageRotation.applied(-90, 90))
  }
}
