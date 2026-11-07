package com.manhas.reader.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NextEntryTest {
  private val ids = listOf("chapter-1", "chapter-2", "chapter-3")

  @Test
  fun returnsFollowingEntry() {
    assertEquals("chapter-2", nextIdAfter(ids, "chapter-1"))
    assertEquals("chapter-3", nextIdAfter(ids, "chapter-2"))
  }

  @Test
  fun returnsNullAfterLastEntry() {
    assertNull(nextIdAfter(ids, "chapter-3"))
  }

  @Test
  fun returnsNullWhenCurrentIsMissing() {
    assertNull(nextIdAfter(ids, "chapter-9"))
  }

  @Test
  fun returnsNullForEmptyList() {
    assertNull(nextIdAfter(emptyList(), "chapter-1"))
  }
}
