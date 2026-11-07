package com.manhas.reader.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.manhas.reader.util.NaturalOrder

data class LibraryEntry(
  val uri: Uri,
  val documentId: String,
  val name: String,
  val isDirectory: Boolean,
)

class LibraryRepository(private val resolver: ContentResolver) {

  fun rootDocumentId(treeUri: Uri): String = DocumentsContract.getTreeDocumentId(treeUri)

  fun folderDisplayName(treeUri: Uri, documentId: String): String {
    val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
    resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
      if (cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getString(0)
    }
    return "Library"
  }

  fun list(treeUri: Uri, documentId: String): List<LibraryEntry> {
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
    val entries = mutableListOf<LibraryEntry>()
    resolver.query(childrenUri, PROJECTION, null, null, null)?.use { cursor ->
      val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
      val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
      val mimeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
      while (cursor.moveToNext()) {
        val id = cursor.getString(idColumn) ?: continue
        val name = cursor.getString(nameColumn) ?: continue
        val mime = cursor.getString(mimeColumn)
        val isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR
        if (!isDirectory && !isPdf(name, mime)) continue
        entries += LibraryEntry(
          uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id),
          documentId = id,
          name = name,
          isDirectory = isDirectory,
        )
      }
    }
    return entries.sortedWith(ENTRY_ORDER)
  }

  fun listPdfs(treeUri: Uri, documentId: String): List<LibraryEntry> =
    list(treeUri, documentId).filter { !it.isDirectory }

  fun documentSize(uri: Uri): Long {
    resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
      if (cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getLong(0)
    }
    return -1L
  }

  private fun isPdf(name: String, mime: String?): Boolean =
    mime == "application/pdf" || name.endsWith(".pdf", ignoreCase = true)

  private companion object {
    val PROJECTION = arrayOf(
      DocumentsContract.Document.COLUMN_DOCUMENT_ID,
      DocumentsContract.Document.COLUMN_DISPLAY_NAME,
      DocumentsContract.Document.COLUMN_MIME_TYPE,
    )

    val ENTRY_ORDER: Comparator<LibraryEntry> =
      compareBy<LibraryEntry> { !it.isDirectory }
        .thenComparator { a, b -> NaturalOrder.compare(a.name, b.name) }
  }
}
