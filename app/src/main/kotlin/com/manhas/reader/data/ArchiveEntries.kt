package com.manhas.reader.data

import com.manhas.reader.util.NaturalOrder

/** Android-free view of one ZIP entry used by the archive page selection. */
data class ArchiveEntryName(val name: String, val isDirectory: Boolean = false)

/** Pixel dimensions reported by the image decoder for one archive entry. */
data class ArchiveImageSize(val width: Int, val height: Int)

/** One decodable archive image, ordered by its archive entry name. */
data class ArchivePage(val name: String, val width: Int, val height: Int) {
  /** Native height / width ratio used for page layout before rotation. */
  val nativeRatio: Float get() = height.toFloat() / width
}

/**
 * Pure filtering and ordering rules for comic archives.
 *
 * A page is any non-directory entry with a supported image extension whose
 * dimensions the decoder can read. macOS metadata, hidden files and other
 * formats (TIFF, CBR/RAR, text) are skipped rather than shown as blank pages.
 * Pages are natural-sorted by full entry name so multi-digit page numbers keep
 * a deterministic reading order.
 */
object ArchiveEntries {
  private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")

  fun isSupportedImage(name: String): Boolean = extension(name) in IMAGE_EXTENSIONS

  fun isIgnored(name: String): Boolean {
    val normalized = name.replace('\\', '/')
    if (normalized.startsWith("__MACOSX/", ignoreCase = true)) return true
    return normalized.substringAfterLast('/').startsWith(".")
  }

  fun buildPages(
    entries: Iterable<ArchiveEntryName>,
    sizeOf: (String) -> ArchiveImageSize?,
  ): List<ArchivePage> =
    entries.asSequence()
      .filterNot { it.isDirectory }
      .map { it.name }
      .filter { name -> !isIgnored(name) && isSupportedImage(name) }
      .mapNotNull { name ->
        val size = sizeOf(name) ?: return@mapNotNull null
        if (size.width <= 0 || size.height <= 0) {
          null
        } else {
          ArchivePage(name, size.width, size.height)
        }
      }
      .sortedWith(compareBy(NaturalOrder) { it.name })
      .toList()

  private fun extension(name: String): String =
    name.replace('\\', '/')
      .substringAfterLast('/')
      .substringAfterLast('.', "")
      .lowercase()
}
