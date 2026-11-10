package com.manhas.reader.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArchiveCacheTest {
  @get:Rule
  val temporaryFolder = TemporaryFolder()

  @Test
  fun derivesAStableZipFilePerDocument() {
    val dir = File(temporaryFolder.root, "archives")

    val first = ArchiveCache.targetFor(dir, "content://reader/volume-1")
    val again = ArchiveCache.targetFor(dir, "content://reader/volume-1")
    val other = ArchiveCache.targetFor(dir, "content://reader/volume-2")

    assertEquals(first, again)
    assertNotEquals(first, other)
    assertTrue(first.name.endsWith(".zip"))
    assertEquals(64 + ".zip".length, first.name.length)
    assertEquals(dir, first.parentFile)
  }

  @Test
  fun prepareCreatesTheDirectoryAndPrunesOtherCopies() {
    val dir = File(temporaryFolder.root, "archives")
    val stale = File(dir, "stale.zip").apply {
      parentFile.mkdirs()
      writeText("old")
    }
    val target = ArchiveCache.targetFor(dir, "content://reader/volume-1")

    ArchiveCache.prepare(target)

    assertTrue(dir.isDirectory)
    assertFalse(stale.exists())
    assertFalse(target.exists())
  }

  @Test
  fun prepareReplacesAnExistingCopyOfTheSameDocument() {
    val target = ArchiveCache.targetFor(temporaryFolder.root, "content://reader/volume-1")
    target.writeText("previous")

    ArchiveCache.prepare(target)

    assertFalse(target.exists())
  }

  @Test
  fun deleteRemovesOnlyTheGivenFile() {
    val target = ArchiveCache.targetFor(temporaryFolder.root, "content://reader/volume-1")
    val other = ArchiveCache.targetFor(temporaryFolder.root, "content://reader/volume-2")
    target.writeText("one")
    other.writeText("two")

    ArchiveCache.delete(target)

    assertFalse(target.exists())
    assertTrue(other.exists())
  }

  @Test
  fun deleteIgnoresMissingFiles() {
    ArchiveCache.delete(null)
    ArchiveCache.delete(File(temporaryFolder.root, "missing.zip"))
  }
}
