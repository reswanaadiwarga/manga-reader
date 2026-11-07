package com.manhas.reader.ui.library

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.manhas.reader.data.LibraryEntry
import com.manhas.reader.data.LibraryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class FolderCrumb(val documentId: String, val name: String)

data class ScrollPosition(val index: Int, val offset: Int)

data class LibraryUiState(
  val rootUri: Uri? = null,
  val crumbs: List<FolderCrumb> = emptyList(),
  val entries: List<LibraryEntry> = emptyList(),
  val loading: Boolean = false,
  val error: String? = null,
)

class LibraryViewModel(private val app: Application) : ViewModel() {
  private val prefs = app.getSharedPreferences("library", Context.MODE_PRIVATE)
  private val repository = LibraryRepository(app.contentResolver)
  private val _uiState = MutableStateFlow(LibraryUiState())
  val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()
  private val scrollPositions = mutableMapOf<String, ScrollPosition>()

  init {
    restoreRoot()
  }

  fun setRoot(uri: Uri) {
    app.contentResolver.takePersistableUriPermission(
      uri,
      Intent.FLAG_GRANT_READ_URI_PERMISSION,
    )
    prefs.edit().putString(KEY_ROOT, uri.toString()).apply()
    scrollPositions.clear()
    val rootId = repository.rootDocumentId(uri)
    viewModelScope.launch {
      val name = withContext(Dispatchers.IO) { repository.folderDisplayName(uri, rootId) }
      load(uri, listOf(FolderCrumb(rootId, name)))
    }
  }

  fun openFolder(entry: LibraryEntry) {
    val state = _uiState.value
    val root = state.rootUri ?: return
    load(root, state.crumbs + FolderCrumb(entry.documentId, entry.name))
  }

  fun goUp(): Boolean {
    val state = _uiState.value
    val root = state.rootUri ?: return false
    if (state.crumbs.size <= 1) return false
    load(root, state.crumbs.dropLast(1))
    return true
  }

  fun retry() {
    val state = _uiState.value
    val root = state.rootUri ?: return
    if (state.crumbs.isEmpty()) return
    load(root, state.crumbs)
  }

  fun getScroll(documentId: String): ScrollPosition =
    scrollPositions[documentId] ?: ScrollPosition(0, 0)

  fun saveScroll(documentId: String, index: Int, offset: Int) {
    scrollPositions[documentId] = ScrollPosition(index, offset)
  }

  private fun restoreRoot() {
    val stored = prefs.getString(KEY_ROOT, null) ?: return
    val uri = Uri.parse(stored)
    val resolver = app.contentResolver
    val hasPermission = resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
    if (!hasPermission) {
      prefs.edit().remove(KEY_ROOT).apply()
      return
    }
    viewModelScope.launch {
      val rootId = repository.rootDocumentId(uri)
      val name = withContext(Dispatchers.IO) {
        runCatching { repository.folderDisplayName(uri, rootId) }.getOrDefault("Library")
      }
      load(uri, listOf(FolderCrumb(rootId, name)))
    }
  }

  private fun load(treeUri: Uri, crumbs: List<FolderCrumb>) {
    val folder = crumbs.lastOrNull() ?: return
    viewModelScope.launch {
      _uiState.update { it.copy(loading = true, error = null, entries = emptyList()) }
      val result = runCatching {
        withContext(Dispatchers.IO) { repository.list(treeUri, folder.documentId) }
      }
      _uiState.update { state ->
        result.fold(
          onSuccess = {
            state.copy(
              rootUri = treeUri,
              crumbs = crumbs,
              entries = it,
              loading = false,
              error = null,
            )
          },
          onFailure = {
            state.copy(loading = false, error = it.message ?: "Cannot open folder")
          },
        )
      }
    }
  }

  private companion object {
    const val KEY_ROOT = "root_uri"
  }
}
