package com.manhas.reader.data

import android.content.Context
import com.manhas.reader.util.ProgressKey

/**
 * Reading state for one document.
 *
 * [pageCount] is recorded after a successful open and drives the library's thin
 * progress bar; it stays null for documents only seen before this feature
 * existed. [finished] is sticky once the last page is reached and is cleared
 * only by an explicit library reset.
 */
data class ReadingState(
  val pageCount: Int? = null,
  val finished: Boolean = false,
) {
  /**
   * Fraction for the library progress bar.
   *
   * Finished documents are always full. Otherwise a known page count turns the
   * zero-based [savedPage] into `(savedPage + 1) / pageCount`, clamped to 0..1;
   * a missing saved page counts as the first page. Returns null when the page
   * count is unknown so the UI can omit the bar.
   */
  fun fraction(savedPage: Int?): Float? {
    if (finished) return 1f
    val count = pageCount?.takeIf { it > 0 } ?: return null
    return (((savedPage ?: 0) + 1).toFloat() / count).coerceIn(0f, 1f)
  }

  /** Records a known page count without disturbing [finished]. */
  fun withPageCount(pageCount: Int): ReadingState =
    if (pageCount <= 0) this else copy(pageCount = pageCount)

  /** Marks the document finished without disturbing [pageCount]. */
  fun finishedState(): ReadingState = copy(finished = true)

  /** Clears both page count and finished state. */
  fun reset(): ReadingState = ReadingState()
}

/**
 * Persists [ReadingState] in the `reading_state` preferences file, keyed by a
 * SHA-256 hash of the document URI so no readable chapter name reaches disk.
 * All values stay in app-private storage and are excluded from backups.
 */
class ReadingStateStore(context: Context) {
  private val prefs = context.getSharedPreferences("reading_state", Context.MODE_PRIVATE)

  @Synchronized
  fun load(documentUri: String): ReadingState {
    val raw = prefs.getString(prefKey(documentUri), null) ?: return ReadingState()
    return ReadingStateCodec.decode(raw)
  }

  @Synchronized
  fun save(documentUri: String, state: ReadingState) {
    val normalized = ReadingStateCodec.normalize(state)
    val key = prefKey(documentUri)
    if (normalized == ReadingState()) {
      prefs.edit().remove(key).apply()
    } else {
      prefs.edit().putString(key, ReadingStateCodec.encode(normalized)).apply()
    }
  }

  /** Records a successfully opened document's page count, preserving finished. */
  @Synchronized
  fun recordPageCount(documentUri: String, pageCount: Int) {
    if (pageCount <= 0) return
    save(documentUri, load(documentUri).withPageCount(pageCount))
  }

  /** Marks the document finished, keeping any known page count. */
  @Synchronized
  fun markFinished(documentUri: String) {
    save(documentUri, load(documentUri).finishedState())
  }

  @Synchronized
  fun clear(documentUri: String) {
    prefs.edit().remove(prefKey(documentUri)).apply()
  }

  private fun prefKey(documentUri: String): String = "doc:" + ProgressKey.of(documentUri)
}

/**
 * String codec for [ReadingState], kept independent of Android storage so JVM
 * tests can exercise it.
 *
 * Format: `"<pageCount>|<finished>"`, where pageCount may be empty and finished
 * is `1` or `0`. Malformed fields degrade to unknown page count / not finished
 * rather than throwing; the whole value is rejected when the separator is
 * missing.
 */
internal object ReadingStateCodec {
  fun normalize(state: ReadingState): ReadingState =
    ReadingState(
      pageCount = state.pageCount?.takeIf { it > 0 },
      finished = state.finished,
    )

  fun encode(state: ReadingState): String {
    val normalized = normalize(state)
    return "${normalized.pageCount ?: ""}|${if (normalized.finished) 1 else 0}"
  }

  fun decode(raw: String): ReadingState {
    val separator = raw.indexOf('|')
    if (separator < 0) return ReadingState()
    val pageCount = raw.substring(0, separator).toIntOrNull()?.takeIf { it > 0 }
    val finished = raw.substring(separator + 1) == "1"
    return ReadingState(pageCount = pageCount, finished = finished)
  }
}
