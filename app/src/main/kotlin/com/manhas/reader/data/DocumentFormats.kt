package com.manhas.reader.data

/** Rendering backend selected for a document. */
enum class DocumentBackend { PDF, ARCHIVE }

/**
 * Pure classification of library documents from their display name and MIME
 * type. Kept independent of Android so the selection rules are unit tested.
 *
 * Extension wins over MIME so a provider that reports a generic type (for
 * example `application/zip` on a PDF, or `application/octet-stream`) still
 * routes by the file name. Unknown documents fall back to the PDF backend,
 * preserving the pre-archive behavior.
 */
object DocumentFormats {
  private val ARCHIVE_EXTENSIONS = setOf("cbz", "zip")
  private val ARCHIVE_MIME_TYPES = setOf(
    "application/vnd.comicbook+zip",
    "application/x-cbz",
    "application/zip",
  )
  private const val PDF_MIME_TYPE = "application/pdf"

  fun backend(displayName: String?, mimeType: String?): DocumentBackend {
    val extension = extension(displayName)
    return when {
      extension == "pdf" -> DocumentBackend.PDF
      extension in ARCHIVE_EXTENSIONS -> DocumentBackend.ARCHIVE
      mimeType == PDF_MIME_TYPE -> DocumentBackend.PDF
      mimeType in ARCHIVE_MIME_TYPES -> DocumentBackend.ARCHIVE
      else -> DocumentBackend.PDF
    }
  }

  /** True for PDF, CBZ and ZIP entries; other archive formats stay hidden. */
  fun isLibraryDocument(name: String, mimeType: String?): Boolean {
    val extension = extension(name)
    return extension == "pdf" ||
      extension in ARCHIVE_EXTENSIONS ||
      mimeType == PDF_MIME_TYPE ||
      mimeType in ARCHIVE_MIME_TYPES
  }

  /** Leading label for a library row: `DIR`, `PDF`, `CBZ` or `ZIP`. */
  fun libraryLabel(name: String, isDirectory: Boolean): String = when {
    isDirectory -> "DIR"
    extension(name) == "cbz" -> "CBZ"
    extension(name) == "zip" -> "ZIP"
    else -> "PDF"
  }

  private fun extension(name: String?): String =
    name.orEmpty()
      .replace('\\', '/')
      .substringAfterLast('/')
      .substringAfterLast('.', "")
      .lowercase()
}
