package com.manhas.reader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibrarySearchTest {
  @Test
  fun normalizeLowercasesAndTurnsPunctuationIntoSpaces() {
    assertEquals(
      "the greatest estate developer",
      LibrarySearch.normalize("The Greatest Estate-Developer!"),
    )
    assertEquals("chapter 012 cbz", LibrarySearch.normalize("  Chapter_012.CBZ  "))
  }

  @Test
  fun normalizeCollapsesWhitespace() {
    assertEquals("a b c", LibrarySearch.normalize("a   ---   b\tc"))
    assertEquals("", LibrarySearch.normalize("  \t\n  "))
  }

  @Test
  fun tokensSplitsOnWhitespaceAndIgnoresBlanks() {
    assertEquals(listOf("estate", "12"), LibrarySearch.tokens("  Estate   12 "))
    assertEquals(emptyList<String>(), LibrarySearch.tokens("   "))
  }

  @Test
  fun matchesPathAwareTextCaseInsensitively() {
    val text = LibrarySearch.searchText(
      listOf("The Greatest Estate Developer"),
      "Chapter 012.cbz",
    )

    assertTrue(LibrarySearch.matches("estate", text))
    assertTrue(LibrarySearch.matches("ESTATE", text))
  }

  @Test
  fun matchesRequiresEveryTokenInAnyOrder() {
    val text = LibrarySearch.searchText(
      listOf("The Greatest Estate Developer"),
      "Chapter 012.cbz",
    )

    assertTrue(LibrarySearch.matches("estate 12", text))
    assertTrue(LibrarySearch.matches("12 estate", text))
    assertFalse(LibrarySearch.matches("estate 13", text))
  }

  @Test
  fun matchesTreatsQueryPunctuationAsSpaces() {
    val text = LibrarySearch.searchText(
      listOf("The Greatest Estate Developer"),
      "Chapter 012.cbz",
    )

    assertTrue(LibrarySearch.matches("estate-12", text))
    assertTrue(LibrarySearch.matches("estate/dev", text))
  }

  @Test
  fun matchesRejectsBlankQueries() {
    assertFalse(LibrarySearch.matches("", "estate 12"))
    assertFalse(LibrarySearch.matches("   ", "estate 12"))
    assertFalse(LibrarySearch.matches("---", "estate 12"))
  }

  @Test
  fun matchesUsesNumericSubstrings() {
    val chapter012 = LibrarySearch.searchText(listOf("Series"), "Chapter 012.cbz")
    val chapter120 = LibrarySearch.searchText(listOf("Series"), "Chapter 120.cbz")
    val chapter112 = LibrarySearch.searchText(listOf("Series"), "Chapter 112.cbz")

    assertTrue(LibrarySearch.matches("12", chapter012))
    assertTrue(LibrarySearch.matches("12", chapter120))
    assertTrue(LibrarySearch.matches("12", chapter112))
  }

  @Test
  fun searchTextExcludesTheFileExtension() {
    val text = LibrarySearch.searchText(emptyList(), "Chapter 001.pdf")

    assertFalse(LibrarySearch.matches("pdf", text))
    assertTrue(LibrarySearch.matches("chapter 001", text))
  }

  @Test
  fun fileStemRemovesOnlyTheFinalExtension() {
    assertEquals("Chapter 001", LibrarySearch.fileStem("Chapter 001.cbz"))
    assertEquals("Archive.tar", LibrarySearch.fileStem("Archive.tar.gz"))
    assertEquals("NoExtension", LibrarySearch.fileStem("NoExtension"))
  }
}
