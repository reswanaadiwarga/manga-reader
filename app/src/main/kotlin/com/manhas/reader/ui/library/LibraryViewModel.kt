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
import com.manhas.reader.data.LibrarySearch
import com.manhas.reader.data.LibrarySearchIndex
import com.manhas.reader.data.ProgressStore
import com.manhas.reader.data.ReadingState
import com.manhas.reader.data.ReadingStateStore
import com.manhas.reader.data.SearchDocument
import com.manhas.reader.util.ProgressKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class FolderCrumb(val documentId: String, val name: String)

data class ScrollPosition(val index: Int, val offset: Int)

/**
 * One library-wide search hit: the document entry to open, the folder that owns
 * it (used as the reader's Next/Previous context), its display folder path and
 * the progress hash that indexes the shared reading-state maps.
 */
data class SearchResult(
  val entry: LibraryEntry,
  val parentDocumentId: String,
  val folderPath: String,
  val hash: String,
)

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
  /** True while the library-wide search bar is open over the current folder. */
  val searchActive: Boolean = false,
  val searchQuery: String = "",
  /** Documents matching [searchQuery], natural-sorted by relative path. */
  val searchResults: List<SearchResult> = emptyList(),
  val searchLoading: Boolean = false,
  val searchError: String? = null,
  /** Last-read document hash per search result parent folder ID. */
  val searchLastReadHashes: Map<String, String> = emptyMap(),
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
  private var searchIndex: List<SearchDocument>? = null
  private var indexJob: Job? = null
  private var filterJob: Job? = null

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
    clearSearchState()
    // Mark loading immediately so the stale previous root/crumbs cannot enable
    // Search during the folder-display-name lookup before load() starts.
    _uiState.update { it.copy(loading = true, error = null) }
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
    if (state.searchActive) {
      closeSearch()
      return true
    }
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
   * Opens library-wide search over the current root without navigating. The
   * caller is expected to restore the folder list (and its scroll) on close.
   *
   * Refuses while a root is loading or errored: during [setRoot] the previous
   * root's crumbs/entries are still visible, and starting a scan from that stale
   * view would pair old document IDs with the new tree URI.
   */
  fun openSearch() {
    val state = _uiState.value
    if (state.rootUri == null || state.loading || state.error != null) return
    _uiState.update {
      it.copy(
        searchActive = true,
        searchQuery = "",
        searchResults = emptyList(),
        searchLoading = false,
        searchError = null,
        searchLastReadHashes = emptyMap(),
      )
    }
  }

  /**
   * Closes search and cancels any in-flight index/filter work. The cached index
   * is kept so reopening search is cheap; [refresh] and [setRoot] invalidate it.
   */
  fun closeSearch() {
    cancelSearchJobs()
    _uiState.update {
      it.copy(
        searchActive = false,
        searchQuery = "",
        searchResults = emptyList(),
        searchLoading = false,
        searchError = null,
        searchLastReadHashes = emptyMap(),
      )
    }
  }

  /**
   * Updates the query. A blank query cancels indexing/filtering; while the index
   * is still building, new keystrokes only update the query so the build is not
   * restarted. Once the index exists only the filter job is replaced.
   */
  fun setSearchQuery(query: String) {
    val state = _uiState.value
    if (!state.searchActive) return
    _uiState.update { it.copy(searchQuery = query, searchError = null) }
    if (query.isBlank()) {
      cancelSearchJobs()
      _uiState.update { it.copy(searchResults = emptyList(), searchLoading = false) }
      return
    }
    val index = searchIndex
    if (index != null) {
      startFilter(index, query)
    } else if (indexJob?.isActive != true) {
      startIndex()
    }
  }

  /** Rebuilds the index after an error; safe to call while a search is shown. */
  fun retrySearch() {
    val state = _uiState.value
    if (!state.searchActive || state.searchQuery.isBlank()) return
    invalidateSearchIndex()
    _uiState.update { it.copy(searchError = null) }
    startIndex()
  }

  /**
   * Reloads last-read and reading state for the current folder. Called on
   * ON_RESUME so progress, finished flags and the Continue card reflect what
   * happened in the reader. Also invalidates the search index, rebuilding it when
   * search is open with a non-blank query.
   */
  fun refresh() {
    val state = _uiState.value
    val root = state.rootUri ?: return
    invalidateSearchIndex()
    val folder = state.crumbs.lastOrNull()
    if (folder != null) {
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
    if (state.searchActive && state.searchQuery.isNotBlank()) {
      startIndex()
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
    refreshSearchFilter()
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
    refreshSearchFilter()
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

  private fun clearSearchState() {
    invalidateSearchIndex()
    _uiState.update {
      it.copy(
        searchActive = false,
        searchQuery = "",
        searchResults = emptyList(),
        searchLoading = false,
        searchError = null,
        searchLastReadHashes = emptyMap(),
      )
    }
  }

  private fun cancelSearchJobs() {
    indexJob?.cancel()
    indexJob = null
    filterJob?.cancel()
    filterJob = null
  }

  private fun invalidateSearchIndex() {
    cancelSearchJobs()
    searchIndex = null
  }

  /**
   * Builds the recursive index on IO. The root listing error is surfaced as
   * [LibraryUiState.searchError]; subfolder errors are swallowed by the index.
   */
  private fun startIndex() {
    val state = _uiState.value
    val root = state.rootUri ?: return
    val rootId = state.crumbs.firstOrNull()?.documentId ?: return
    indexJob?.cancel()
    filterJob?.cancel()
    searchIndex = null
    indexJob = viewModelScope.launch {
      _uiState.update { it.copy(searchLoading = true, searchError = null) }
      try {
        val documents = withContext(Dispatchers.IO) {
          LibrarySearchIndex.build(
            rootDocumentId = rootId,
            listChildren = { folderId -> repository.searchNodes(root, folderId) },
          )
        }
        // The scan is blocking and cannot be interrupted mid-query; drop its
        // result if a newer root/refresh cancelled this job while it ran.
        if (!isActive) return@launch
        searchIndex = documents
        val query = _uiState.value.searchQuery
        if (_uiState.value.searchActive && query.isNotBlank()) {
          startFilter(documents, query)
        } else {
          _uiState.update { it.copy(searchLoading = false) }
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Exception) {
        _uiState.update {
          it.copy(
            searchLoading = false,
            searchError = error.message ?: "Cannot search library",
          )
        }
      }
    }
  }

  /**
   * Re-runs the current filter after a mark-read/mark-unread, so an older
   * in-flight hydration cannot land last and overwrite the just-updated
   * progress/check state shown in the result rows.
   */
  private fun refreshSearchFilter() {
    val state = _uiState.value
    if (!state.searchActive || state.searchQuery.isBlank()) return
    val index = searchIndex ?: return
    startFilter(index, state.searchQuery)
  }

  /**
   * Filters the cached index off the main thread and resolves reading state for
   * the hits on IO. Only this job is replaced per keystroke; the index job keeps
   * running so a quick typist does not restart the scan.
   */
  private fun startFilter(index: List<SearchDocument>, query: String) {
    filterJob?.cancel()
    filterJob = viewModelScope.launch {
      val rootString = _uiState.value.rootUri?.toString() ?: return@launch
      val filtered = withContext(Dispatchers.Default) {
        index.filter { LibrarySearch.matches(query, it.searchText) }
      }
      val payload = withContext(Dispatchers.IO) {
        val entries = filtered.map { document ->
          SearchEntrySnapshot(
            document = document,
            hash = ProgressKey.of(document.uriString),
            reading = readingStateStore.load(document.uriString),
            savedPage = progressStore.pageFor(document.uriString),
          )
        }
        val lastRead = filtered
          .map { it.parentDocumentId }
          .distinct()
          .mapNotNull { parent ->
            lastReadStore.documentHash(rootString, parent)?.let { parent to it }
          }
          .toMap()
        SearchPayload(entries, lastRead)
      }
      // Drop results computed for a query/root that has since changed.
      val current = _uiState.value
      if (!current.searchActive || current.searchQuery != query) return@launch
      val results = payload.entries.map { snapshot ->
        SearchResult(
          entry = LibraryEntry(
            uri = Uri.parse(snapshot.document.uriString),
            documentId = snapshot.document.documentId,
            name = snapshot.document.name,
            isDirectory = false,
          ),
          parentDocumentId = snapshot.document.parentDocumentId,
          folderPath = snapshot.document.folderPath,
          hash = snapshot.hash,
        )
      }
      _uiState.update { state ->
        val readingAdds = payload.entries
          .filter { it.reading != ReadingState() }
          .associate { it.hash to it.reading }
        val savedAdds = payload.entries
          .mapNotNull { snapshot -> snapshot.savedPage?.let { snapshot.hash to it } }
          .toMap()
        state.copy(
          searchResults = results,
          searchLoading = false,
          searchLastReadHashes = payload.lastRead,
          readingStates = state.readingStates + readingAdds,
          savedPages = state.savedPages + savedAdds,
        )
      }
    }
  }

  private data class SearchEntrySnapshot(
    val document: SearchDocument,
    val hash: String,
    val reading: ReadingState,
    val savedPage: Int?,
  )

  private data class SearchPayload(
    val entries: List<SearchEntrySnapshot>,
    val lastRead: Map<String, String>,
  )

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
