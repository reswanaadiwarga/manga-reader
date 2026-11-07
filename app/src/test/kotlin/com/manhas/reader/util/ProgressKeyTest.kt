package com.manhas.reader.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ProgressKeyTest {
  @Test
  fun producesStableSixtyFourCharacterHexKey() {
    val key = ProgressKey.of("content://provider/document/primary%3AManhas%2FChapter%20001.pdf")

    assertEquals(64, key.length)
    assertEquals(key, ProgressKey.of("content://provider/document/primary%3AManhas%2FChapter%20001.pdf"))
    assertEquals(true, key.all { it in "0123456789abcdef" })
  }

  @Test
  fun differentDocumentsGetDifferentKeys() {
    val first = ProgressKey.of("content://provider/document/Chapter%20001.pdf")
    val second = ProgressKey.of("content://provider/document/Chapter%20002.pdf")

    assertNotEquals(first, second)
  }

  @Test
  fun keyDoesNotContainReadableDocumentName() {
    val key = ProgressKey.of("content://provider/document/Secret%20Chapter.pdf")

    assertEquals(false, key.contains("Secret", ignoreCase = true))
  }
}
