package com.manhas.reader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveEntriesTest {
  @Test
  fun supportedExtensionsAreCaseInsensitive() {
    listOf(
      "page.jpg",
      "page.JPG",
      "page.jpeg",
      "page.PnG",
      "page.webp",
      "page.WEBP",
      "page.gif",
      "page.bmp",
    ).forEach { name ->
      assertTrue(name, ArchiveEntries.isSupportedImage(name))
    }
  }

  @Test
  fun rejectsUnsupportedAndExtensionlessEntries() {
    listOf(
      "page.tif",
      "page.tiff",
      "page.cbr",
      "page.rar",
      "notes.txt",
      "README",
      "folder/page",
    ).forEach { name ->
      assertFalse(name, ArchiveEntries.isSupportedImage(name))
    }
  }

  @Test
  fun filtersDirectoriesMacOsMetadataAndHiddenFiles() {
    val entries = listOf(
      ArchiveEntryName("pages/", isDirectory = true),
      ArchiveEntryName("__MACOSX/._page1.jpg"),
      ArchiveEntryName("__macosx/page2.png"),
      ArchiveEntryName(".hidden.jpg"),
      ArchiveEntryName("chapter/.cover.webp"),
      ArchiveEntryName("chapter/page1.jpg"),
      ArchiveEntryName("chapter/page2.png"),
      ArchiveEntryName("chapter/notes.txt"),
    )

    val pages = ArchiveEntries.buildPages(entries) { size ->
      if (ArchiveEntries.isSupportedImage(size)) ArchiveImageSize(100, 150) else null
    }

    assertEquals(listOf("chapter/page1.jpg", "chapter/page2.png"), pages.map { it.name })
  }

  @Test
  fun buildsPagesInNaturalOrderWithNativeRatios() {
    val entries = listOf(
      ArchiveEntryName("page10.webp"),
      ArchiveEntryName("page2.webp"),
      ArchiveEntryName("page1.webp"),
      ArchiveEntryName("page02.webp"),
    )
    val sizes = mapOf(
      "page1.webp" to ArchiveImageSize(100, 150),
      "page2.webp" to ArchiveImageSize(200, 100),
      "page02.webp" to ArchiveImageSize(400, 300),
      "page10.webp" to ArchiveImageSize(50, 200),
    )

    val pages = ArchiveEntries.buildPages(entries) { sizes[it] }

    assertEquals(
      listOf("page1.webp", "page2.webp", "page02.webp", "page10.webp"),
      pages.map { it.name },
    )
    assertEquals(1.5f, pages[0].nativeRatio, 1e-6f)
    assertEquals(0.5f, pages[1].nativeRatio, 1e-6f)
    assertEquals(0.75f, pages[2].nativeRatio, 1e-6f)
    assertEquals(4f, pages[3].nativeRatio, 1e-6f)
  }

  @Test
  fun omitsEntriesWithoutReadableDimensions() {
    val entries = listOf(
      ArchiveEntryName("good.jpg"),
      ArchiveEntryName("missing.png"),
      ArchiveEntryName("empty.webp"),
      ArchiveEntryName("broken.gif"),
    )

    val pages = ArchiveEntries.buildPages(entries) { name ->
      when (name) {
        "good.jpg" -> ArchiveImageSize(120, 180)
        "empty.webp" -> ArchiveImageSize(0, 0)
        else -> null
      }
    }

    assertEquals(listOf("good.jpg"), pages.map { it.name })
  }
}
