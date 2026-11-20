package com.manhas.reader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure checks for [ArchiveGeometry.band], the geometry behind sharp visible
 * bands for tall CBZ/ZIP pages. All values are derived from the specified crop
 * rules, so the tests do not need Android decoders.
 */
class ArchiveBandGeometryTest {
  @Test
  fun rot0BandMapsTopSliceAndSubsamples() {
    val band = ArchiveGeometry.band(
      pageWidth = 4000,
      pageHeight = 16000,
      widthPx = 1000,
      rotation = 0,
      topFraction = 0.25f,
      heightFraction = 0.5f,
    )!!

    assertEquals(0, band.sourceLeft)
    assertEquals(4000, band.sourceTop)
    assertEquals(4000, band.sourceRight)
    assertEquals(12000, band.sourceBottom)
    assertEquals(1000, band.targetWidth)
    assertEquals(2000, band.targetHeight)
    assertEquals(4, band.sampleSize)
  }

  @Test
  fun rot90BandMapsTopSliceToTheLeftEdge() {
    val band = ArchiveGeometry.band(
      pageWidth = 4000,
      pageHeight = 16000,
      widthPx = 1000,
      rotation = 90,
      topFraction = 0.1f,
      heightFraction = 0.2f,
    )!!

    assertEquals(400, band.sourceLeft)
    assertEquals(0, band.sourceTop)
    assertEquals(1200, band.sourceRight)
    assertEquals(16000, band.sourceBottom)
    assertEquals(1000, band.targetWidth)
    // Rotated display is 16000 x 4000; the slice is 0.2 of the 4000 px height.
    assertEquals(50, band.targetHeight)
    assertEquals(16, band.sampleSize)
  }

  @Test
  fun rot270BandMapsTopSliceToTheRightEdge() {
    val band = ArchiveGeometry.band(
      pageWidth = 4000,
      pageHeight = 16000,
      widthPx = 1000,
      rotation = 270,
      topFraction = 0.1f,
      heightFraction = 0.2f,
    )!!

    assertEquals(2800, band.sourceLeft)
    assertEquals(0, band.sourceTop)
    assertEquals(3600, band.sourceRight)
    assertEquals(16000, band.sourceBottom)
    assertEquals(1000, band.targetWidth)
    assertEquals(50, band.targetHeight)
  }

  @Test
  fun rot180BandMapsTopSliceToTheBottomOfTheSource() {
    val band = ArchiveGeometry.band(
      pageWidth = 4000,
      pageHeight = 16000,
      widthPx = 1000,
      rotation = 180,
      topFraction = 0.1f,
      heightFraction = 0.2f,
    )!!

    assertEquals(0, band.sourceLeft)
    assertEquals(11200, band.sourceTop)
    assertEquals(4000, band.sourceRight)
    assertEquals(14400, band.sourceBottom)
    assertEquals(1000, band.targetWidth)
    assertEquals(800, band.targetHeight)
    assertEquals(4, band.sampleSize)
  }

  @Test
  fun clampsTheSliceEndToThePageBottom() {
    val band = ArchiveGeometry.band(
      pageWidth = 1000,
      pageHeight = 4000,
      widthPx = 500,
      rotation = 0,
      topFraction = 0.8f,
      heightFraction = 0.5f,
    )!!

    assertEquals(3200, band.sourceTop)
    assertEquals(4000, band.sourceBottom)
    // Only 0.2 of the page is visible, not the requested 0.5.
    assertEquals(400, band.targetHeight)
    assertEquals(2, band.sampleSize)
  }

  @Test
  fun clampsNegativeTopToThePageTop() {
    val band = ArchiveGeometry.band(
      pageWidth = 1000,
      pageHeight = 4000,
      widthPx = 500,
      rotation = 0,
      topFraction = -0.1f,
      heightFraction = 0.5f,
    )!!

    assertEquals(0, band.sourceTop)
    assertEquals(2000, band.sourceBottom)
    assertEquals(1000, band.targetHeight)
  }

  @Test
  fun invalidOrEmptySlicesReturnNull() {
    assertNull(ArchiveGeometry.band(0, 4000, 500, 0, 0f, 1f))
    assertNull(ArchiveGeometry.band(1000, 0, 500, 0, 0f, 1f))
    assertNull(ArchiveGeometry.band(1000, 4000, 0, 0, 0f, 1f))
    assertNull(ArchiveGeometry.band(1000, 4000, 500, 0, 0f, 0f))
    assertNull(ArchiveGeometry.band(1000, 4000, 500, 0, 1f, 0.5f))
    assertNull(ArchiveGeometry.band(1000, 4000, 500, 0, -0.5f, -0.5f))
    assertNull(ArchiveGeometry.band(1000, 4000, 500, 0, Float.NaN, 0.5f))
    assertNull(ArchiveGeometry.band(1000, 4000, 500, 0, 0f, Float.NaN))
  }

  @Test
  fun rejectsTargetsAboveTheSafetyCap() {
    // 10000 x 10000 target at 4 bytes per pixel is 400 MB, above 64 MiB.
    assertNull(ArchiveGeometry.band(10000, 10000, 10000, 0, 0f, 1f))
  }

  @Test
  fun targetDimensionsNeverNeedUpsampling() {
    val band = ArchiveGeometry.band(
      pageWidth = 1000,
      pageHeight = 4000,
      widthPx = 2000,
      rotation = 0,
      topFraction = 0.25f,
      heightFraction = 0.5f,
    )!!

    assertEquals(2000, band.targetWidth)
    assertEquals(4000, band.targetHeight)
    // The source slice is only 1000 x 2000, so no subsampling is allowed.
    assertEquals(1, band.sampleSize)
  }

  @Test
  fun targetHeightIsAtLeastOnePixel() {
    val band = ArchiveGeometry.band(
      pageWidth = 1000,
      pageHeight = 10000,
      widthPx = 1,
      rotation = 0,
      topFraction = 0f,
      heightFraction = 0.0001f,
    )!!

    assertEquals(1, band.targetWidth)
    assertEquals(1, band.targetHeight)
    assertEquals(0, band.sourceTop)
    assertEquals(1, band.sourceBottom)
  }
}
