package com.manhas.reader.util

import java.security.MessageDigest

object ProgressKey {
  fun of(documentUri: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
      .digest(documentUri.toByteArray(Charsets.UTF_8))
    val hex = StringBuilder(digest.size * 2)
    for (byte in digest) {
      val value = byte.toInt() and 0xFF
      hex.append(HEX[value ushr 4]).append(HEX[value and 0x0F])
    }
    return hex.toString()
  }

  private const val HEX = "0123456789abcdef"
}
