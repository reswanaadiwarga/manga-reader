package com.manhas.reader.data

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import com.manhas.reader.util.PageRotation
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * CBZ/ZIP document backend.
 *
 * The content URI is copied once into an app-private cache file, opened with
 * [ZipFile], and never extracted entry-by-entry, so archive entry names cannot
 * write outside the cache. Only supported image entries become pages
 * ([ArchiveEntries]); everything else is ignored. The cached copy is removed by
 * [close] and when another document is opened in this session.
 *
 * [cacheDir] is the archive cache directory (for example
 * `cacheDir/archives`); the caller owns the parent and the app owns the files.
 */
class ArchiveSession(
  private val resolver: ContentResolver,
  private val cacheDir: File,
) : DocumentSession {
  private val mutex = Mutex()
  private var zipFile: ZipFile? = null
  private var tempFile: File? = null
  private var pages: List<ArchivePage> = emptyList()

  override suspend fun open(uri: Uri, size: Long, mimeType: String?): List<Float> =
    mutex.withLock {
      withContext(Dispatchers.IO) {
        closeLocked()
        openLocked(uri)
      }
    }

  override suspend fun render(pageIndex: Int, widthPx: Int, rotation: Int): Bitmap? =
    mutex.withLock {
      val zip = zipFile ?: return@withLock null
      val page = pages.getOrNull(pageIndex) ?: return@withLock null
      withContext(Dispatchers.IO) { renderLocked(zip, page, widthPx, rotation) }
    }

  override suspend fun close() = mutex.withLock {
    withContext(Dispatchers.IO) { closeLocked() }
  }

  private fun openLocked(uri: Uri): List<Float> {
    val target = ArchiveCache.targetFor(cacheDir, uri.toString())
    try {
      ArchiveCache.prepare(target)
      copyToFile(uri, target)
      val zip = ZipFile(target)
      try {
        val entries = zip.entries().asSequence()
          .map { ArchiveEntryName(it.name, it.isDirectory) }
          .toList()
        val selected = ArchiveEntries.buildPages(entries) { name ->
          val entry = zip.getEntry(name) ?: return@buildPages null
          readImageSize(zip, entry)
        }
        if (selected.isEmpty()) {
          throw IllegalArgumentException(EMPTY_ARCHIVE_MESSAGE)
        }
        zipFile = zip
        tempFile = target
        pages = selected
        return selected.map { it.nativeRatio }
      } catch (throwable: Throwable) {
        runCatching { zip.close() }
        throw throwable
      }
    } catch (throwable: Throwable) {
      ArchiveCache.delete(target)
      throw throwable
    }
  }

  private fun copyToFile(uri: Uri, target: File) {
    val input = resolver.openInputStream(uri) ?: throw IOException("Cannot open $uri")
    input.use { source ->
      target.outputStream().use { sink -> source.copyTo(sink) }
    }
  }

  private fun readImageSize(zip: ZipFile, entry: ZipEntry): ArchiveImageSize? =
    runCatching {
      val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      zip.getInputStream(entry).use { stream ->
        BitmapFactory.decodeStream(stream, null, options)
      }
      ArchiveImageSize(options.outWidth, options.outHeight)
    }.getOrNull()

  /**
   * Scales the decoded page uniformly so its displayed width is [widthPx],
   * then rotates it into a fresh bitmap. The target dimensions already use the
   * rotated display ratio, so no crop or stretch is needed. Either dimension is
   * capped at [ArchiveGeometry.MAX_BITMAP_DIMENSION], matching the PDF backend.
   */
  private fun renderLocked(
    zip: ZipFile,
    page: ArchivePage,
    widthPx: Int,
    rotation: Int,
  ): Bitmap? {
    val target = ArchiveGeometry.target(
      pageWidth = page.width,
      pageHeight = page.height,
      widthPx = widthPx,
      rotation = rotation,
    ) ?: return null
    val decoded = decode(zip, page.name, target.sampleSize) ?: return null

    return try {
      val bitmap = Bitmap.createBitmap(target.width, target.height, Bitmap.Config.ARGB_8888)
      val canvas = Canvas(bitmap)
      canvas.save()
      when (PageRotation.normalize(rotation)) {
        90 -> {
          canvas.translate(target.width.toFloat(), 0f)
          canvas.rotate(90f)
        }
        180 -> {
          canvas.translate(target.width.toFloat(), target.height.toFloat())
          canvas.rotate(180f)
        }
        270 -> {
          canvas.translate(0f, target.height.toFloat())
          canvas.rotate(270f)
        }
      }
      canvas.scale(target.scale, target.scale)
      canvas.drawBitmap(decoded, 0f, 0f, PAINT)
      canvas.restore()
      bitmap
    } catch (throwable: Throwable) {
      null
    } finally {
      decoded.recycle()
    }
  }

  private fun decode(zip: ZipFile, name: String, sampleSize: Int): Bitmap? =
    runCatching {
      val entry = zip.getEntry(name) ?: return@runCatching null
      zip.getInputStream(entry).use { stream ->
        val options = BitmapFactory.Options().apply {
          inSampleSize = sampleSize
          inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        BitmapFactory.decodeStream(stream, null, options)
      }
    }.getOrNull()

  private fun closeLocked() {
    val zip = zipFile
    zipFile = null
    runCatching { zip?.close() }
    ArchiveCache.delete(tempFile)
    tempFile = null
    pages = emptyList()
  }

  private companion object {
    const val EMPTY_ARCHIVE_MESSAGE = "Not a supported comic archive (no images)"

    val PAINT = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
  }
}
