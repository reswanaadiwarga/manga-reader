package com.manhas.reader.ui.reader

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.manhas.reader.data.LibraryEntry
import com.manhas.reader.data.LibraryRepository
import com.manhas.reader.data.MuPdfSession
import com.manhas.reader.data.ProgressStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

data class ReaderUiState(
  val title: String = "",
  val loading: Boolean = true,
  val error: String? = null,
  val pageRatios: List<Float> = emptyList(),
  val previousTitle: String? = null,
  val nextTitle: String? = null,
  val initialPage: Int = 0,
  val documentToken: Int = 0,
)

class ReaderViewModel(private val app: Application) : ViewModel() {
  private val repository = LibraryRepository(app.contentResolver)
  private val session = MuPdfSession(app.contentResolver)
  private val progressStore = ProgressStore(app)
  private val _uiState = MutableStateFlow(ReaderUiState())
  val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

  private var treeUri: Uri? = null
  private var folderDocumentId: String = ""
  private var currentDocumentId: String = ""
  private var currentDocumentKey: String = ""
  private var siblings: List<LibraryEntry> = emptyList()
  private var openJob: Job? = null
  private var started = false
  private var lastVisiblePage = 0

  private val cache = object : LruCache<String, Bitmap>(cacheSizeBytes()) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
  }

  fun start(
    documentUri: String,
    documentId: String,
    folderTreeUri: String,
    folderDocumentId: String,
    title: String,
  ) {
    if (started) return
    started = true
    treeUri = Uri.parse(folderTreeUri)
    this.folderDocumentId = folderDocumentId
    open(documentUri, documentId, title, resume = true)
  }

  fun openNext() {
    val index = siblings.indexOfFirst { it.documentId == currentDocumentId }
    if (index < 0) return
    val next = siblings.getOrNull(index + 1) ?: return
    open(next.uri.toString(), next.documentId, next.name, resume = false)
  }

  fun openPrevious() {
    val index = siblings.indexOfFirst { it.documentId == currentDocumentId }
    if (index <= 0) return
    val previous = siblings.getOrNull(index - 1) ?: return
    open(previous.uri.toString(), previous.documentId, previous.name, resume = false)
  }

  fun retry() {
    if (currentDocumentKey.isEmpty()) return
    open(currentDocumentKey, currentDocumentId, _uiState.value.title, resume = true)
  }

  fun onPageVisible(page: Int) {
    lastVisiblePage = page.coerceAtLeast(0)
  }

  fun saveProgressNow() {
    val key = currentDocumentKey
    val ratios = _uiState.value.pageRatios
    if (key.isEmpty() || ratios.isEmpty()) return
    progressStore.save(key, lastVisiblePage.coerceIn(0, ratios.lastIndex))
  }

  suspend fun renderPage(pageIndex: Int, widthPx: Int): Bitmap? {
    val cacheKey = "$currentDocumentKey:$pageIndex:$widthPx"
    cache.get(cacheKey)?.let { return it }
    val bitmap = session.render(pageIndex, widthPx) ?: return null
    cache.put(cacheKey, bitmap)
    return bitmap
  }

  private fun open(documentUri: String, documentId: String, title: String, resume: Boolean) {
    val tree = treeUri ?: return
    if (currentDocumentKey.isNotEmpty() && currentDocumentKey != documentUri) {
      saveProgressNow()
    }
    openJob?.cancel()
    currentDocumentId = documentId
    currentDocumentKey = documentUri
    openJob = viewModelScope.launch {
      _uiState.value = ReaderUiState(
        title = title,
        loading = true,
        documentToken = _uiState.value.documentToken,
      )
      cache.evictAll()
      val uri = Uri.parse(documentUri)
      val resolver = app.contentResolver
      val result = runCatching {
        val folderEntries = withContext(Dispatchers.IO) {
          repository.listPdfs(tree, folderDocumentId)
        }
        val size = withContext(Dispatchers.IO) { repository.documentSize(uri) }
        val mimeType = resolver.getType(uri)?.takeIf { it != "application/octet-stream" }
          ?: if (title.endsWith(".pdf", ignoreCase = true)) "application/pdf" else null
        val ratios = session.open(uri, size, mimeType)
        val currentIndex = folderEntries.indexOfFirst { it.documentId == documentId }
        val previous = if (currentIndex > 0) folderEntries.getOrNull(currentIndex - 1) else null
        val next = if (currentIndex >= 0) folderEntries.getOrNull(currentIndex + 1) else null
        val lastIndex = (ratios.size - 1).coerceAtLeast(0)
        val initialPage = if (resume) {
          (progressStore.pageFor(documentUri) ?: 0).coerceIn(0, lastIndex)
        } else {
          0
        }
        ReaderUiState(
          title = title,
          loading = false,
          error = null,
          pageRatios = ratios,
          previousTitle = previous?.name,
          nextTitle = next?.name,
          initialPage = initialPage,
          documentToken = _uiState.value.documentToken + 1,
        ) to folderEntries
      }
      result.onSuccess { (state, entries) ->
        siblings = entries
        lastVisiblePage = state.initialPage
        _uiState.value = state
      }.onFailure { error ->
        _uiState.value = ReaderUiState(
          title = title,
          loading = false,
          error = error.message ?: "Could not open this document",
          documentToken = _uiState.value.documentToken,
        )
      }
    }
  }

  override fun onCleared() {
    saveProgressNow()
    openJob?.cancel()
    cache.evictAll()
    runBlocking(Dispatchers.IO) { session.close() }
    super.onCleared()
  }

  private fun cacheSizeBytes(): Int {
    val quarterHeap = Runtime.getRuntime().maxMemory() / 4
    return quarterHeap.coerceIn(16L * 1024 * 1024, 64L * 1024 * 1024).toInt()
  }
}
