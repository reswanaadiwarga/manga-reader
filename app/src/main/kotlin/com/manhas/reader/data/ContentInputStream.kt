// Adapted from the Artifex MuPDF Android viewer (AGPL-3.0).
package com.manhas.reader.data

import android.content.ContentResolver
import android.net.Uri
import com.artifex.mupdf.fitz.SeekableInputStream
import java.io.IOException
import java.io.InputStream

class ContentInputStream(
  private val resolver: ContentResolver,
  private val uri: Uri,
  size: Long,
) : SeekableInputStream {  private var stream: InputStream? = null
  private var length: Long = size
  private var position: Long = 0
  private var mustReopen = false

  init {
    reopen()
  }

  override fun seek(offset: Long, whence: Int): Long {
    var target = position
    when (whence) {
      SeekableInputStream.SEEK_SET -> target = offset
      SeekableInputStream.SEEK_CUR -> target = position + offset
      SeekableInputStream.SEEK_END -> {
        if (length < 0) {
          val buffer = ByteArray(16384)
          while (true) {
            val n = input().read(buffer)
            if (n <= 0) break
            position += n
          }
          length = position
        }
        target = length + offset
      }
    }
    if (target < position) {
      if (!mustReopen) {
        try {
          input().skip(target - position)
        } catch (_: IOException) {
          mustReopen = true
        }
      }
      if (mustReopen) {
        reopen()
        input().skip(target)
      }
    } else if (target > position) {
      input().skip(target - position)
    }
    position = target
    return position
  }

  override fun position(): Long = position

  override fun read(buffer: ByteArray): Int {
    val n = input().read(buffer)
    if (n > 0) {
      position += n
    } else if (n < 0 && length < 0) {
      length = position
    }
    return n
  }

  private fun input(): InputStream = stream ?: throw IOException("Stream closed")

  private fun reopen() {
    stream?.close()
    stream = resolver.openInputStream(uri) ?: throw IOException("Cannot open $uri")
    position = 0
  }
}
