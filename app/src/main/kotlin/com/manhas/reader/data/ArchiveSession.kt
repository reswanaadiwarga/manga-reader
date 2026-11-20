package com.manhas.reader.data

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
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
 * Tall pages can additionally be rendered as a sharp visible band through
 * [renderBand], which region-decodes only the source crop that maps to the
 * display. Formats that [BitmapRegionDecoder] cannot handle fall back to the
 * base bitmap.
 *
 * [cacheDir] is the archive cache directory (for example
 * `cacheDir/archives`); the caller owns the parent and the app owns the files.
 */
class ArchiveSession(
  private val resolver: ContentResolver,
  private val cacheDir: File,
  private val maxPagePixels: Long = Long.MAX_VALUE,
  private val maxPageDimension: Float = ArchiveGeometry.MAX_BITMAP_DIMENSION,
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

  /**
   * Decodes the visible source crop with [BitmapRegionDecoder] instead of the
   * whole page, so tall archive images get the same sharp-band treatment as
   * PDFs. Returns null for formats/failures the region decoder cannot handle;
   * the reader then keeps the full-page base bitmap.
   */
  override suspend fun renderBand(
    pageIndex: Int,
    widthPx: Int,
    rotation: Int,
    topFraction: Float,
    heightFraction: Float,
  ): Bitmap? = mutex.withLock {
    val zip = zipFile ?: return@withLock null
    val page = pages.getOrNull(pageIndex) ?: return@withLock null
    withContext(Dispatchers.IO) {
      renderBandLocked(zip, page, widthPx, rotation, topFraction, heightFraction)
    }
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
   * capped at [ArchiveGeometry.MAX_BITMAP_DIMENSION] and the session's
   * [maxPagePixels], matching the PDF backend.
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
      maxPagePixels = maxPagePixels,
      maxPageDimension = maxPageDimension,
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

  /**
   * Region-decodes [target]'s source crop and draws it into a rotated target
   * bitmap. Every failure (unsupported format, decode error, allocation error)
   * returns null rather than propagating, so the base image remains visible.
   */
  private fun renderBandLocked(
    zip: ZipFile,
    page: ArchivePage,
    widthPx: Int,
    rotation: Int,
    topFraction: Float,
    heightFraction: Float,
  ): Bitmap? {
    val target = ArchiveGeometry.band(
      pageWidth = page.width,
      pageHeight = page.height,
      widthPx = widthPx,
      rotation = rotation,
      topFraction = topFraction,
      heightFraction = heightFraction,
    ) ?: return null
    if (target.targetWidth.toLong() * target.targetHeight * 4L > ArchiveGeometry.MAX_BAND_BYTES) {
      return null
    }

    val entry = zip.getEntry(page.name) ?: return null
    val data = runCatching {
      zip.getInputStream(entry).use { it.readBytes() }
    }.getOrNull() ?: return null
    val decoder = runCatching {
      BitmapRegionDecoder.newInstance(data, 0, data.size, false)
    }.getOrNull() ?: return null

    val decoded = try {
      val options = BitmapFactory.Options().apply {
        inSampleSize = target.sampleSize
        inPreferredConfig = Bitmap.Config.ARGB_8888
      }
      val rect = Rect(
        target.sourceLeft,
        target.sourceTop,
        target.sourceRight,
        target.sourceBottom,
      )
      runCatching { decoder.decodeRegion(rect, options) }.getOrNull()
    } finally {
      runCatching { decoder.recycle() }
    }
    if (decoded == null) return null

    return try {
      drawBand(decoded, target, rotation)
    } finally {
      decoded.recycle()
    }
  }

  /**
   * Scales and rotates the decoded source crop into a fresh ARGB bitmap.
   * Quarter turns map the crop's width to the output height, hence the swapped
   * scale axes.
   */
  private fun drawBand(
    decoded: Bitmap,
    target: ArchiveBandTarget,
    rotation: Int,
  ): Bitmap? {
    if (decoded.width <= 0 || decoded.height <= 0) return null
    val bitmap = try {
      Bitmap.createBitmap(target.targetWidth, target.targetHeight, Bitmap.Config.ARGB_8888)
    } catch (throwable: Throwable) {
      return null
    }
    return try {
      val canvas = Canvas(bitmap)
      canvas.save()
      when (PageRotation.normalize(rotation)) {
        90 -> {
          canvas.translate(target.targetWidth.toFloat(), 0f)
          canvas.rotate(90f)
          canvas.scale(
            target.targetWidth.toFloat() / decoded.height,
            target.targetHeight.toFloat() / decoded.width,
          )
        }
        180 -> {
          canvas.translate(target.targetWidth.toFloat(), target.targetHeight.toFloat())
          canvas.rotate(180f)
          canvas.scale(
            target.targetWidth.toFloat() / decoded.width,
            target.targetHeight.toFloat() / decoded.height,
          )
        }
        270 -> {
          canvas.translate(0f, target.targetHeight.toFloat())
          canvas.rotate(270f)
          canvas.scale(
            target.targetWidth.toFloat() / decoded.height,
            target.targetHeight.toFloat() / decoded.width,
          )
        }
        else -> {
          canvas.scale(
            target.targetWidth.toFloat() / decoded.width,
            target.targetHeight.toFloat() / decoded.height,
          )
        }
      }
      canvas.drawBitmap(decoded, 0f, 0f, PAINT)
      canvas.restore()
      bitmap
    } catch (throwable: Throwable) {
      bitmap.recycle()
      null
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
