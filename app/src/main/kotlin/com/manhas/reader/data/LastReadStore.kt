package com.manhas.reader.data

import android.content.Context
import com.manhas.reader.util.ProgressKey

class LastReadStore(context: Context) {
  private val prefs = context.getSharedPreferences("last_read", Context.MODE_PRIVATE)

  fun record(treeUri: String, folderDocumentId: String, documentUri: String) {
    prefs.edit()
      .putString(folderKey(treeUri, folderDocumentId), ProgressKey.of(documentUri))
      .apply()
  }

  fun documentHash(treeUri: String, folderDocumentId: String): String? =
    prefs.getString(folderKey(treeUri, folderDocumentId), null)

  private fun folderKey(treeUri: String, folderDocumentId: String): String =
    "folder:" + ProgressKey.of("$treeUri|$folderDocumentId")
}
