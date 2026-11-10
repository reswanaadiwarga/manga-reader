package com.manhas.reader.ui.library

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.manhas.reader.data.LastReadStore
import com.manhas.reader.data.LibraryEntry
import com.manhas.reader.data.LibraryRepository
import com.manhas.reader.data.ProgressStore
import com.manhas.reader.data.ReadingState
import com.manhas.reader.data.ReadingStateStore
import com.manhas.reader.util.ProgressKey
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
  val lastReadDocumentHash: String? = null,
  /** Reading state per document hash for the current folder's direct entries. */
  val readingStates: Map<String, ReadingState> = emptyMap(),
  /** Saved page per document hash; zero-based, sourced from [ProgressStore]. */
  val savedPages: Map<String, Int> = emptyMap(),
)

class LibraryViewModel(private val app: Application) : ViewModel() {
  private val prefs = app.getSharedPreferences("library", Context.MODE_PRIVATE)
  private val repository = LibraryRepository(app.contentResolver)
  private val lastReadStore = LastReadStore(app)
  private val progressStore = ProgressStore.get(app)
  private val readingStateStore = ReadingStateStore(app)
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

  /**
   * Reloads last-read and reading state for the current folder. Called on
   * ON_RESUME so progress, finished flags and the Continue card reflect what
   * happened in the reader.
   */
  fun refresh() {
    val state = _uiState.value
    val root = state.rootUri ?: return
    val folder = state.crumbs.lastOrNull() ?: return
    val hash = lastReadStore.documentHash(root.toString(), folder.documentId)
    val snapshots = state.entries.filter { !it.isDirectory }.map(::snapshot)
    _uiState.update {
      it.copy(
        lastReadDocumentHash = hash,
        readingStates = snapshots.associate { snapshot -> snapshot.hash to snapshot.reading },
        savedPages = snapshots.mapNotNull { snapshot ->
          snapshot.savedPage?.let { page -> snapshot.hash to page }
        }.toMap(),
      )
    }
  }

  /**
   * Clears one direct document's saved page and reading state, so the next open
   * starts at page 1 and the row loses its bar and checkmark.
   */
  fun markUnread(entry: LibraryEntry) {
    if (entry.isDirectory) return
    val uri = entry.uri.toString()
    progressStore.clear(uri)
    readingStateStore.clear(uri)
    publishDocument(entry)
  }

  /**
   * Marks one direct document finished. When its page count is known the saved
   * page moves to the last page; otherwise only the finished flag is stored.
   */
  fun markRead(entry: LibraryEntry) {
    if (entry.isDirectory) return
    val uri = entry.uri.toString()
    val pageCount = readingStateStore.load(uri).pageCount
    if (pageCount != null && pageCount > 0) {
      progressStore.save(uri, pageCount - 1)
    }
    readingStateStore.markFinished(uri)
    publishDocument(entry)
  }

  /**
   * Resets the current folder's direct documents: saved pages, finished flags,
   * and the folder's last-read marker. Sub-folders and other folders are
   * untouched.
   */
  fun resetCurrentFolder() {
    val state = _uiState.value
    val root = state.rootUri ?: return
    val folder = state.crumbs.lastOrNull() ?: return
    val documents = state.entries.filter { !it.isDirectory }
    documents.forEach { entry ->
      val uri = entry.uri.toString()
      progressStore.clear(uri)
      readingStateStore.clear(uri)
    }
    lastReadStore.clear(root.toString(), folder.documentId)
    val hashes = documents.mapTo(HashSet()) { ProgressKey.of(it.uri.toString()) }
    _uiState.update {
      it.copy(
        lastReadDocumentHash = null,
        readingStates = it.readingStates - hashes,
        savedPages = it.savedPages - hashes,
      )
    }
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
        withContext(Dispatchers.IO) {
          val entries = repository.list(treeUri, folder.documentId)
          val snapshots = entries.filter { !it.isDirectory }.map(::snapshot)
          FolderSnapshot(
            entries = entries,
            lastReadDocumentHash = lastReadStore.documentHash(
              treeUri.toString(),
              folder.documentId,
            ),
            readingStates = snapshots.associate { snapshot -> snapshot.hash to snapshot.reading },
            savedPages = snapshots.mapNotNull { snapshot ->
              snapshot.savedPage?.let { page -> snapshot.hash to page }
            }.toMap(),
          )
        }
      }
      _uiState.update { state ->
        result.fold(
          onSuccess = { snapshot ->
            state.copy(
              rootUri = treeUri,
              crumbs = crumbs,
              entries = snapshot.entries,
              loading = false,
              error = null,
              lastReadDocumentHash = snapshot.lastReadDocumentHash,
              readingStates = snapshot.readingStates,
              savedPages = snapshot.savedPages,
            )
          },
          onFailure = {
            state.copy(loading = false, error = it.message ?: "Cannot open folder")
          },
        )
      }
    }
  }

  private fun publishDocument(entry: LibraryEntry) {
    val snapshot = snapshot(entry)
    _uiState.update {
      it.copy(
        readingStates = if (snapshot.reading == ReadingState()) {
          it.readingStates - snapshot.hash
        } else {
          it.readingStates + (snapshot.hash to snapshot.reading)
        },
        savedPages = if (snapshot.savedPage != null) {
          it.savedPages + (snapshot.hash to snapshot.savedPage)
        } else {
          it.savedPages - snapshot.hash
        },
      )
    }
  }

  private fun snapshot(entry: LibraryEntry): DocumentSnapshot {
    val uri = entry.uri.toString()
    return DocumentSnapshot(
      hash = ProgressKey.of(uri),
      reading = readingStateStore.load(uri),
      savedPage = progressStore.pageFor(uri),
    )
  }

  private data class DocumentSnapshot(
    val hash: String,
    val reading: ReadingState,
    val savedPage: Int?,
  )

  private data class FolderSnapshot(
    val entries: List<LibraryEntry>,
    val lastReadDocumentHash: String?,
    val readingStates: Map<String, ReadingState>,
    val savedPages: Map<String, Int>,
  )

  private companion object {
    const val KEY_ROOT = "root_uri"
  }
}
