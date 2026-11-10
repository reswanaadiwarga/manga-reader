package com.manhas.reader.data

import android.content.Context
import com.manhas.reader.util.ProgressKey

class ProgressStore private constructor(context: Context) {
  private val prefs = context.getSharedPreferences("progress", Context.MODE_PRIVATE)
  private val entries = LinkedHashMap<String, Int>()

  init {
    load()
  }

  @Synchronized
  fun pageFor(documentUri: String): Int? = entries[ProgressKey.of(documentUri)]

  /** Forgets the saved page for [documentUri] so a later open starts at page 1. */
  @Synchronized
  fun clear(documentUri: String) {
    val key = ProgressKey.of(documentUri)
    if (entries.remove(key) == null) return
    prefs.edit().putString(KEY_ENTRIES, serialize()).apply()
  }

  @Synchronized
  fun save(documentUri: String, page: Int) {
    if (page < 0) return
    val key = ProgressKey.of(documentUri)
    if (entries[key] == page) return
    entries.remove(key)
    entries[key] = page
    trim()
    prefs.edit().putString(KEY_ENTRIES, serialize()).apply()
  }

  private fun load() {
    val raw = prefs.getString(KEY_ENTRIES, null) ?: return
    raw.lineSequence().forEach { line ->
      val separator = line.lastIndexOf(' ')
      if (separator <= 0) return@forEach
      val key = line.substring(0, separator)
      val page = line.substring(separator + 1).toIntOrNull() ?: return@forEach
      if (key.length == KEY_LENGTH && page >= 0) {
        entries[key] = page
      }
    }
    trim()
  }

  private fun trim() {
    while (entries.size > MAX_ENTRIES) {
      val oldest = entries.keys.firstOrNull() ?: break
      entries.remove(oldest)
    }
  }

  private fun serialize(): String =
    entries.entries.joinToString("\n") { "${it.key} ${it.value}" }

  companion object {
    const val MAX_ENTRIES = 300

    @Volatile
    private var instance: ProgressStore? = null

    /**
     * Returns the process-wide store. The reader and library must share one
     * in-memory map; otherwise the library keeps serving a stale saved page
     * until the process restarts.
     */
    fun get(context: Context): ProgressStore =
      instance ?: synchronized(this) {
        instance ?: ProgressStore(context.applicationContext).also { instance = it }
      }

    private const val KEY_ENTRIES = "entries"
    private const val KEY_LENGTH = 64
  }
}
