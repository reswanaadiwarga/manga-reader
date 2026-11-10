package com.manhas.reader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LibrarySearchIndexTest {
  private fun node(
    documentId: String,
    name: String,
    isDirectory: Boolean = false,
    uriString: String = "content://doc/$documentId",
  ) = SearchNode(
    documentId = documentId,
    name = name,
    isDirectory = isDirectory,
    uriString = uriString,
  )

  private fun tree(
    root: String = "root",
    children: Map<String, List<SearchNode>>,
  ): List<SearchDocument> = LibrarySearchIndex.build(
    rootDocumentId = root,
    listChildren = { documentId -> children[documentId] ?: emptyList() },
  )

  @Test
  fun walksNestedFoldersAndRootLevelDocuments() {
    val documents = tree(
      children = mapOf(
        "root" to listOf(
          node("estate", "The Greatest Estate Developer", isDirectory = true),
          node("other", "Another Series", isDirectory = true),
          node("root-1", "Chapter 001.pdf"),
          node("root-2", "Chapter 002.cbz"),
        ),
        "estate" to listOf(
          node("estate-12", "Chapter 012.cbz"),
          node("estate-120", "Chapter 120.cbz"),
        ),
        "other" to listOf(node("other-12", "Chapter 012.pdf")),
      ),
    )

    assertEquals(5, documents.size)
    assertEquals(
      listOf(
        "Another Series / Chapter 012.pdf",
        "Chapter 001.pdf",
        "Chapter 002.cbz",
        "The Greatest Estate Developer / Chapter 012.cbz",
        "The Greatest Estate Developer / Chapter 120.cbz",
      ),
      documents.map { it.relativePath },
    )

    val rootDocument = documents.first { it.documentId == "root-1" }
    assertEquals("root", rootDocument.parentDocumentId)
    assertEquals("", rootDocument.folderPath)
    assertEquals("Chapter 001.pdf", rootDocument.relativePath)
    assertEquals("chapter 001", rootDocument.searchText)

    val estateChapter = documents.first { it.documentId == "estate-12" }
    assertEquals("estate", estateChapter.parentDocumentId)
    assertEquals("The Greatest Estate Developer", estateChapter.folderPath)
    assertEquals(
      "The Greatest Estate Developer / Chapter 012.cbz",
      estateChapter.relativePath,
    )
    assertEquals(
      "the greatest estate developer chapter 012",
      estateChapter.searchText,
    )
  }

  @Test
  fun neverIncludesTheRootNameInSearchText() {
    val documents = tree(
      root = "hidden-root",
      children = mapOf(
        "hidden-root" to listOf(
          node("series", "Series", isDirectory = true),
          node("root-doc", "Chapter 001.pdf"),
        ),
        "series" to listOf(node("doc", "Chapter 002.pdf")),
      ),
    )

    assertTrue(documents.all { !it.searchText.contains("hidden") })
    assertEquals("chapter 001", documents.first { it.documentId == "root-doc" }.searchText)
    assertEquals("series chapter 002", documents.first { it.documentId == "doc" }.searchText)
  }

  @Test
  fun preservesUriStrings() {
    val documents = tree(
      children = mapOf(
        "root" to listOf(
          node("doc", "Chapter 001.cbz", uriString = "content://tree/root/doc%2F1"),
        ),
      ),
    )

    assertEquals("content://tree/root/doc%2F1", documents.single().uriString)
  }

  @Test
  fun visitedFolderIdsBreakCycles() {
    val documents = tree(
      children = mapOf(
        "root" to listOf(
          node("a", "Series A", isDirectory = true),
          node("b", "Series B", isDirectory = true),
        ),
        "a" to listOf(node("b", "Series B", isDirectory = true), node("a-1", "One.cbz")),
        "b" to listOf(node("a", "Series A", isDirectory = true), node("b-1", "Two.cbz")),
      ),
    )

    assertEquals(listOf("a-1", "b-1"), documents.map { it.documentId }.sorted())
  }

  @Test
  fun duplicateDocumentIdsAppearOnlyOnce() {
    val documents = tree(
      children = mapOf(
        "root" to listOf(
          node("series", "Series", isDirectory = true),
          node("shared", "Shared.pdf"),
        ),
        "series" to listOf(node("shared", "Shared.pdf")),
      ),
    )

    assertEquals(listOf("shared"), documents.map { it.documentId })
  }

  @Test
  fun skipsUnreadableChildFolders() {
    val documents = LibrarySearchIndex.build(
      rootDocumentId = "root",
      listChildren = { documentId ->
        when (documentId) {
          "root" -> listOf(
            node("broken", "Broken", isDirectory = true),
            node("good", "Good", isDirectory = true),
            node("root-doc", "Root.cbz"),
          )
          "broken" -> throw IllegalStateException("cannot list broken")
          "good" -> listOf(node("good-doc", "Inside.cbz"))
          else -> emptyList()
        }
      },
    )

    assertEquals(
      listOf("good-doc", "root-doc"),
      documents.map { it.documentId }.sorted(),
    )
  }

  @Test
  fun rootListingFailurePropagates() {
    assertThrows(IllegalStateException::class.java) {
      LibrarySearchIndex.build(
        rootDocumentId = "root",
        listChildren = { throw IllegalStateException("root down") },
      )
    }
  }

  @Test
  fun stopsAtTheDepthCap() {
    val children = mapOf(
      "root" to listOf(node("l1", "Level1", isDirectory = true), node("root-doc", "Root.cbz")),
      "l1" to listOf(node("l2", "Level2", isDirectory = true), node("a", "A.cbz")),
      "l2" to listOf(node("l3", "Level3", isDirectory = true), node("b", "B.cbz")),
      "l3" to listOf(node("c", "C.cbz")),
    )

    val documents = LibrarySearchIndex.build(
      rootDocumentId = "root",
      listChildren = { documentId -> children[documentId] ?: emptyList() },
      maxDepth = 2,
    )

    assertEquals(
      listOf("a", "b", "root-doc"),
      documents.map { it.documentId }.sorted(),
    )
  }

  @Test
  fun naturalSortsRelativePaths() {
    val documents = tree(
      children = mapOf(
        "root" to listOf(
          node("c12", "Chapter 12.cbz"),
          node("c2", "Chapter 2.cbz"),
          node("c1", "Chapter 1.cbz"),
          node("c120", "Chapter 120.cbz"),
        ),
      ),
    )

    assertEquals(
      listOf(
        "Chapter 1.cbz",
        "Chapter 2.cbz",
        "Chapter 12.cbz",
        "Chapter 120.cbz",
      ),
      documents.map { it.relativePath },
    )
  }

  @Test
  fun unmatchedQueryFiltersNothingUnexpectedly() {
    val documents = tree(
      children = mapOf(
        "root" to listOf(node("estate", "The Greatest Estate Developer", isDirectory = true)),
        "estate" to listOf(node("chapter-12", "Chapter 012.cbz")),
      ),
    )

    assertEquals(1, documents.count { LibrarySearch.matches("estate 12", it.searchText) })
    assertEquals(0, documents.count { LibrarySearch.matches("estate 13", it.searchText) })
    assertFalse(documents.any { LibrarySearch.matches("pdf", it.searchText) })
  }
}
