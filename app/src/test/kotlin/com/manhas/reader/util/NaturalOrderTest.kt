package com.manhas.reader.util

import org.junit.Assert.assertEquals
import org.junit.Test

class NaturalOrderTest {
  @Test
  fun ordersChapterNumbersNaturally() {
    val names = listOf(
      "Chapter 010.pdf",
      "Chapter 2.pdf",
      "Chapter 1.pdf",
      "Chapter 003.pdf",
      "Chapter 10.pdf",
      "Chapter 9.pdf",
    )

    val sorted = names.sortedWith(NaturalOrder)

    assertEquals(
      listOf(
        "Chapter 1.pdf",
        "Chapter 2.pdf",
        "Chapter 003.pdf",
        "Chapter 9.pdf",
        "Chapter 010.pdf",
        "Chapter 10.pdf",
      ),
      sorted,
    )
  }

  @Test
  fun ignoresCaseWhenComparingLetters() {
    val sorted = listOf("chapter 2.pdf", "Chapter 1.pdf").sortedWith(NaturalOrder)
    assertEquals(listOf("Chapter 1.pdf", "chapter 2.pdf"), sorted)
  }

  @Test
  fun handlesNamesWithoutNumbers() {
    val sorted = listOf("Vol 2", "Extra", "Vol 1").sortedWith(NaturalOrder)
    assertEquals(listOf("Extra", "Vol 1", "Vol 2"), sorted)
  }

  @Test
  fun treatsPaddedAndUnpaddedNumbersAsEqual() {
    assertEquals(0, NaturalOrder.compare("Chapter 2.pdf", "Chapter 02.pdf"))
  }
}
