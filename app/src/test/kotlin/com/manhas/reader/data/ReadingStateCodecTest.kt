package com.manhas.reader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadingStateCodecTest {
  @Test
  fun roundTripsPageCountAndFinished() {
    val state = ReadingState(pageCount = 42, finished = true)

    assertEquals(state, ReadingStateCodec.decode(ReadingStateCodec.encode(state)))
  }

  @Test
  fun roundTripsFinishedWithoutKnownPageCount() {
    val state = ReadingState(pageCount = null, finished = true)

    assertEquals(state, ReadingStateCodec.decode(ReadingStateCodec.encode(state)))
  }

  @Test
  fun ignoresMalformedStorage() {
    assertEquals(ReadingState(), ReadingStateCodec.decode("garbage"))
    assertEquals(
      ReadingState(pageCount = null, finished = true),
      ReadingStateCodec.decode("-4|1"),
    )
    assertEquals(
      ReadingState(pageCount = 12, finished = false),
      ReadingStateCodec.decode("12|not-a-flag"),
    )
  }

  @Test
  fun finishedFractionIsAlwaysOne() {
    assertEquals(1f, ReadingState(pageCount = 100, finished = true).fraction(savedPage = 3))
    assertEquals(1f, ReadingState(pageCount = null, finished = true).fraction(null))
  }

  @Test
  fun partialFractionUsesZeroBasedSavedPage() {
    val fraction = ReadingState(pageCount = 100).fraction(savedPage = 24)

    assertEquals(0.25f, fraction)
  }

  @Test
  fun unknownPageCountHasNoFraction() {
    assertNull(ReadingState().fraction(savedPage = 5))
    assertNull(ReadingState(pageCount = 0).fraction(savedPage = 5))
  }

  @Test
  fun resetClearsPageCountAndFinishedButMarkersPreserveEachOther() {
    val reset = ReadingState(pageCount = 100, finished = true).reset()

    assertEquals(ReadingState(), reset)
    assertNull(reset.fraction(savedPage = 99))
    assertEquals(100, ReadingState(pageCount = 100).finishedState().pageCount)
    assertEquals(200, ReadingState(pageCount = 100, finished = true).withPageCount(200).pageCount)
  }
}
