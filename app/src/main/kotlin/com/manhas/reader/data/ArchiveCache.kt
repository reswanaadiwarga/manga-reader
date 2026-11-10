package com.manhas.reader.data

import com.manhas.reader.util.ProgressKey
import java.io.File
import java.io.IOException

/**
 * App-private cache-file management for [ArchiveSession].
 *
 * The file name is derived from the document URI, so reopening the same
 * document replaces its copy and different documents never share a path.
 * Preparing a file prunes copies left by a killed process. Kept independent of
 * Android so the cleanup rules are unit tested.
 */
internal object ArchiveCache {
  fun targetFor(cacheDir: File, documentUri: String): File =
    File(cacheDir, "${ProgressKey.of(documentUri)}.zip")

  /**
   * Creates the cache directory and removes every other file plus any previous
   * copy of [target], so [target] is ready for a fresh copy.
   */
  fun prepare(target: File) {
    val parent = target.parentFile ?: throw IOException("No archive cache directory")
    if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
      throw IOException("Cannot create archive cache directory")
    }
    parent.listFiles()?.forEach { stale ->
      if (stale.isFile && stale != target) stale.delete()
    }
    if (target.exists() && !target.delete()) {
      throw IOException("Cannot replace archive cache file")
    }
  }

  /** Deletes [file] when present; missing files and directories are ignored. */
  fun delete(file: File?) {
    if (file != null && file.exists()) file.delete()
  }
}
