package com.manhas.reader.util

fun nextIdAfter(ids: List<String>, currentId: String): String? {
  val index = ids.indexOf(currentId)
  if (index < 0) return null
  return ids.getOrNull(index + 1)
}
