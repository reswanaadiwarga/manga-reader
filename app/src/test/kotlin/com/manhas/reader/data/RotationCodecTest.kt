package com.manhas.reader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RotationCodecTest {
  @Test
  fun roundTripsDocumentDefaultAndSparseOverrides() {
    val settings = RotationSettings(
      documentRotation = 90,
      pageOverrides = mapOf(0 to 180, 7 to 270),
    )

    val decoded = RotationCodec.decode(RotationCodec.encode(settings))

    assertEquals(90, decoded.documentRotation)
    assertEquals(mapOf(0 to 180, 7 to 270), decoded.pageOverrides)
  }

  @Test
  fun normalizesAnglesAndDropsNegativePages() {
    val normalized = RotationCodec.normalize(
      RotationSettings(
        documentRotation = -90,
        pageOverrides = mapOf(2 to 450, -1 to 90),
      ),
    )

    assertEquals(270, normalized.documentRotation)
    assertEquals(mapOf(2 to 90), normalized.pageOverrides)
  }

  @Test
  fun ignoresMalformedStorage() {
    assertTrue(RotationCodec.decode("garbage").pageOverrides.isEmpty())
    val decoded = RotationCodec.decode("180|4:90,bad,5:x,-2:270")
    assertEquals(180, decoded.documentRotation)
    assertEquals(mapOf(4 to 90), decoded.pageOverrides)
  }
}
