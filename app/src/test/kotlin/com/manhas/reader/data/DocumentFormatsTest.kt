package com.manhas.reader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentFormatsTest {
  @Test
  fun selectsPdfByExtension() {
    assertEquals(DocumentBackend.PDF, DocumentFormats.backend("Chapter 1.pdf", null))
    assertEquals(DocumentBackend.PDF, DocumentFormats.backend("Chapter 1.PDF", null))
  }

  @Test
  fun selectsPdfByMimeWhenExtensionIsMissing() {
    assertEquals(
      DocumentBackend.PDF,
      DocumentFormats.backend("download", "application/pdf"),
    )
  }

  @Test
  fun selectsArchiveByExtensionCaseInsensitively() {
    assertEquals(DocumentBackend.ARCHIVE, DocumentFormats.backend("Volume 2.cbz", null))
    assertEquals(DocumentBackend.ARCHIVE, DocumentFormats.backend("Volume 2.ZIP", null))
  }

  @Test
  fun selectsArchiveByMimeWhenExtensionIsMissing() {
    listOf(
      "application/vnd.comicbook+zip",
      "application/x-cbz",
      "application/zip",
    ).forEach { mime ->
      assertEquals(mime, DocumentBackend.ARCHIVE, DocumentFormats.backend("download", mime))
    }
  }

  @Test
  fun defaultsUnknownDocumentsToPdf() {
    assertEquals(DocumentBackend.PDF, DocumentFormats.backend("notes.txt", "text/plain"))
    assertEquals(DocumentBackend.PDF, DocumentFormats.backend(null, null))
  }

  @Test
  fun libraryIncludesOnlyPdfCbzAndZip() {
    assertTrue(DocumentFormats.isLibraryDocument("Chapter 1.pdf", null))
    assertTrue(DocumentFormats.isLibraryDocument("Volume.cbz", null))
    assertTrue(DocumentFormats.isLibraryDocument("Volume.zip", null))
    assertTrue(DocumentFormats.isLibraryDocument("scan", "application/pdf"))
    assertTrue(DocumentFormats.isLibraryDocument("scan", "application/x-cbz"))
    assertTrue(DocumentFormats.isLibraryDocument("scan", "application/vnd.comicbook+zip"))
    assertTrue(DocumentFormats.isLibraryDocument("scan", "application/zip"))

    assertFalse(DocumentFormats.isLibraryDocument("Volume.cbr", null))
    assertFalse(DocumentFormats.isLibraryDocument("Volume.rar", null))
    assertFalse(DocumentFormats.isLibraryDocument("notes.txt", "text/plain"))
    assertFalse(DocumentFormats.isLibraryDocument("scan", "application/x-rar-compressed"))
  }

  @Test
  fun labelsFoldersAndSupportedArchives() {
    assertEquals("DIR", DocumentFormats.libraryLabel("Series", isDirectory = true))
    assertEquals("PDF", DocumentFormats.libraryLabel("Chapter 1.pdf", isDirectory = false))
    assertEquals("CBZ", DocumentFormats.libraryLabel("Volume.CBZ", isDirectory = false))
    assertEquals("ZIP", DocumentFormats.libraryLabel("Volume.zip", isDirectory = false))
    assertEquals("PDF", DocumentFormats.libraryLabel("download", isDirectory = false))
  }
}
