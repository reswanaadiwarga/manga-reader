package com.manhas.reader.data

import android.content.Context
import com.manhas.reader.util.PageRotation
import com.manhas.reader.util.ProgressKey

/** Saved rotation for one document: a default plus sparse page overrides. */
data class RotationSettings(
  val documentRotation: Int = 0,
  val pageOverrides: Map<Int, Int> = emptyMap(),
) {
  /** Applied rotation for [page]: its override when present, else the default. */
  fun appliedRotation(page: Int): Int =
    PageRotation.applied(pageOverrides[page], documentRotation)
}

/**
 * Persists user page rotation in the `rotation` preferences file.
 *
 * Documents are identified by a SHA-256 hash of their URI so file names are
 * never written to disk. Pristine settings (default 0, no overrides) are
 * removed instead of stored.
 */
class RotationStore(context: Context) {
  private val prefs = context.getSharedPreferences("rotation", Context.MODE_PRIVATE)

  @Synchronized
  fun load(documentUri: String): RotationSettings {
    val raw = prefs.getString(prefKey(documentUri), null) ?: return RotationSettings()
    return RotationCodec.decode(raw)
  }

  @Synchronized
  fun save(documentUri: String, settings: RotationSettings) {
    val normalized = RotationCodec.normalize(settings)
    val key = prefKey(documentUri)
    if (normalized.documentRotation == 0 && normalized.pageOverrides.isEmpty()) {
      prefs.edit().remove(key).apply()
    } else {
      prefs.edit().putString(key, RotationCodec.encode(normalized)).apply()
    }
  }

  private fun prefKey(documentUri: String): String = "doc:" + ProgressKey.of(documentUri)
}

/**
 * String codec for [RotationSettings], kept separate from Android storage so it
 * can be exercised by JVM unit tests.
 *
 * Format: `"<default>|<page>:<rotation>,<page>:<rotation>"`. Unknown or
 * malformed fields are ignored and all angles are normalized on decode.
 */
internal object RotationCodec {
  fun normalize(settings: RotationSettings): RotationSettings =
    RotationSettings(
      documentRotation = PageRotation.normalize(settings.documentRotation),
      pageOverrides = settings.pageOverrides.entries
        .filter { it.key >= 0 }
        .associate { it.key to PageRotation.normalize(it.value) },
    )

  fun encode(settings: RotationSettings): String {
    val normalized = normalize(settings)
    val pages = normalized.pageOverrides.entries
      .sortedBy { it.key }
      .joinToString(",") { "${it.key}:${it.value}" }
    return "${normalized.documentRotation}|$pages"
  }

  fun decode(raw: String): RotationSettings {
    val separator = raw.indexOf('|')
    if (separator < 0) return RotationSettings()
    val default = raw.substring(0, separator).toIntOrNull() ?: return RotationSettings()
    val overrides = LinkedHashMap<Int, Int>()
    val pages = raw.substring(separator + 1)
    if (pages.isNotEmpty()) {
      pages.split(',').forEach { entry ->
        val colon = entry.indexOf(':')
        if (colon <= 0) return@forEach
        val page = entry.substring(0, colon).toIntOrNull() ?: return@forEach
        val rotation = entry.substring(colon + 1).toIntOrNull() ?: return@forEach
        if (page >= 0) overrides[page] = PageRotation.normalize(rotation)
      }
    }
    return normalize(RotationSettings(PageRotation.normalize(default), overrides))
  }
}
