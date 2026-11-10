package com.manhas.reader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveGeometryTest {
  @Test
  fun portraitPageFitsRequestedWidth() {
    val target = ArchiveGeometry.target(pageWidth = 100, pageHeight = 150, widthPx = 300, rotation = 0)!!

    assertEquals(300, target.width)
    assertEquals(450, target.height)
    assertEquals(3f, target.scale, 1e-6f)
    assertEquals(1, target.sampleSize)
  }

  @Test
  fun halfTurnKeepsNativeAxes() {
    val target = ArchiveGeometry.target(100, 150, 300, 180)!!

    assertEquals(300, target.width)
    assertEquals(450, target.height)
  }

  @Test
  fun quarterTurnsSwapTargetAxes() {
    listOf(90, 270).forEach { rotation ->
      val target = ArchiveGeometry.target(100, 150, 300, rotation)!!

      assertEquals("rotation $rotation width", 300, target.width)
      assertEquals("rotation $rotation height", 200, target.height)
    }
  }

  @Test
  fun capsLongPageAtMaxDimension() {
    val target = ArchiveGeometry.target(100, 20_000, 1080, 0)!!

    assertEquals(41, target.width)
    assertEquals(8192, target.height)
    assertEquals(8192f / 20_000f, target.scale, 1e-6f)
  }

  @Test
  fun selectsPowerOfTwoSubsampleForLargePages() {
    val target = ArchiveGeometry.target(4000, 6000, 1000, 0)!!

    assertEquals(1000, target.width)
    assertEquals(1500, target.height)
    assertEquals(4, target.sampleSize)
  }

  @Test
  fun quarterTurnSamplingUsesSwappedAxes() {
    val target = ArchiveGeometry.target(4000, 6000, 1000, 90)!!

    assertEquals(1000, target.width)
    assertEquals(667, target.height)
    assertEquals(4, target.sampleSize)
  }

  @Test
  fun neverUpsamplesBeyondFullResolution() {
    val target = ArchiveGeometry.target(100, 150, 1000, 0)!!

    assertEquals(1, target.sampleSize)
    assertTrue(target.scale > 1f)
  }

  @Test
  fun rejectsPagesWithoutPixels() {
    assertNull(ArchiveGeometry.target(0, 150, 300, 0))
    assertNull(ArchiveGeometry.target(100, 0, 300, 0))
    assertNull(ArchiveGeometry.target(-1, 150, 300, 0))
  }
}
