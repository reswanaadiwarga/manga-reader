package com.manhas.reader.ui.reader

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.manhas.reader.data.ArchiveSession
import com.manhas.reader.data.DocumentBackend
import com.manhas.reader.data.DocumentFormats
import com.manhas.reader.data.DocumentSession
import com.manhas.reader.data.LastReadStore
import com.manhas.reader.data.LibraryEntry
import com.manhas.reader.data.LibraryRepository
import com.manhas.reader.data.MuPdfSession
import com.manhas.reader.data.ProgressStore
import com.manhas.reader.data.ReadingStateStore
import com.manhas.reader.data.RotationSettings
import com.manhas.reader.data.RotationStore
import com.manhas.reader.util.PageRotation
import java.io.File
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
  val pageRotations: List<Int> = emptyList(),
  val rotationRevision: Int = 0,
  val previousTitle: String? = null,
  val nextTitle: String? = null,
  val initialPage: Int = 0,
  val documentToken: Int = 0,
)

class ReaderViewModel(private val app: Application) : ViewModel() {
  private val repository = LibraryRepository(app.contentResolver)
  private val pdfSession = MuPdfSession(app.contentResolver)
  private val archiveSession = ArchiveSession(
    resolver = app.contentResolver,
    cacheDir = File(app.cacheDir, "archives"),
  )
  private var activeSession: DocumentSession? = null
  private val progressStore = ProgressStore.get(app)
  private val readingStateStore = ReadingStateStore(app)
  private val lastReadStore = LastReadStore(app)
  private val rotationStore = RotationStore(app)
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
  private var documentRotation = 0
  private var pageOverrides: Map<Int, Int> = emptyMap()

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

  /**
   * Marks the current document finished once its last page is visible.
   *
   * The flag is persisted immediately and is sticky: scrolling back does not
   * clear it, and only the library's reset actions can. No-op until a document
   * with pages is open.
   */
  fun markCurrentDocumentFinished() {
    val key = currentDocumentKey
    if (key.isEmpty() || _uiState.value.pageRatios.isEmpty()) return
    if (readingStateStore.load(key).finished) return
    readingStateStore.markFinished(key)
  }

  /**
   * Advances [page] by one clockwise quarter turn and stores the result as a
   * per-page override. No-op until the document has page ratios.
   */
  fun rotateCurrentPage(page: Int) {
    val state = _uiState.value
    if (page !in state.pageRotations.indices) return
    val next = PageRotation.next(state.pageRotations[page])
    pageOverrides = if (next == documentRotation) {
      pageOverrides - page
    } else {
      pageOverrides + (page to next)
    }
    persistRotation()
    publishRotation()
  }

  /**
   * Advances the document default by one clockwise quarter turn relative to
   * [page]'s applied rotation, then drops every page override.
   */
  fun rotateAllPages(page: Int) {
    val state = _uiState.value
    if (state.pageRatios.isEmpty()) return
    val applied = state.pageRotations.getOrNull(page) ?: documentRotation
    documentRotation = PageRotation.next(applied)
    pageOverrides = emptyMap()
    persistRotation()
    publishRotation()
  }

  /** Restores the unrotated document default and clears page overrides. */
  fun resetRotation() {
    documentRotation = 0
    pageOverrides = emptyMap()
    persistRotation()
    publishRotation()
  }

  private fun persistRotation() {
    val key = currentDocumentKey
    if (key.isEmpty()) return
    rotationStore.save(key, RotationSettings(documentRotation, pageOverrides))
  }

  private fun publishRotation() {
    val state = _uiState.value
    if (state.pageRatios.isEmpty()) return
    val rotations = state.pageRatios.indices.map { index ->
      PageRotation.applied(pageOverrides[index], documentRotation)
    }
    _uiState.value = state.copy(
      pageRotations = rotations,
      rotationRevision = state.rotationRevision + 1,
    )
  }

  fun saveProgressNow() {
    val key = currentDocumentKey
    val ratios = _uiState.value.pageRatios
    if (key.isEmpty() || ratios.isEmpty()) return
    progressStore.save(key, lastVisiblePage.coerceIn(0, ratios.lastIndex))
  }

  suspend fun renderPage(pageIndex: Int, widthPx: Int, rotation: Int): Bitmap? {
    val normalized = PageRotation.normalize(rotation)
    val cacheKey = "$currentDocumentKey:$pageIndex:$widthPx:$normalized"
    cache.get(cacheKey)?.let { return it }
    val bitmap = activeSession?.render(pageIndex, widthPx, normalized) ?: return null
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
      documentRotation = 0
      pageOverrides = emptyMap()
      val uri = Uri.parse(documentUri)
      val resolver = app.contentResolver
      val result = runCatching {
        val folderEntries = withContext(Dispatchers.IO) {
          repository.listDocuments(tree, folderDocumentId)
        }
        val size = withContext(Dispatchers.IO) { repository.documentSize(uri) }
        val mimeType = resolver.getType(uri)?.takeIf { it != "application/octet-stream" }
          ?: if (title.endsWith(".pdf", ignoreCase = true)) "application/pdf" else null
        val backend = DocumentFormats.backend(title, mimeType)
        val targetSession = when (backend) {
          DocumentBackend.ARCHIVE -> archiveSession
          DocumentBackend.PDF -> pdfSession
        }
        if (activeSession !== targetSession) {
          activeSession?.close()
          activeSession = targetSession
        }
        val ratios = targetSession.open(uri, size, mimeType)
        val rotation = rotationStore.load(documentUri)
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
          pageRotations = ratios.indices.map { rotation.appliedRotation(it) },
          previousTitle = previous?.name,
          nextTitle = next?.name,
          initialPage = initialPage,
          documentToken = _uiState.value.documentToken + 1,
        ) to (folderEntries to rotation)
      }
      result.onSuccess { (state, side) ->
        val (entries, rotation) = side
        siblings = entries
        lastVisiblePage = state.initialPage
        documentRotation = rotation.documentRotation
        pageOverrides = rotation.pageOverrides
        readingStateStore.recordPageCount(documentUri, state.pageRatios.size)
        lastReadStore.record(tree.toString(), folderDocumentId, documentUri)
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
    runBlocking(Dispatchers.IO) {
      pdfSession.close()
      archiveSession.close()
    }
    super.onCleared()
  }

  private fun cacheSizeBytes(): Int {
    val quarterHeap = Runtime.getRuntime().maxMemory() / 4
    return quarterHeap.coerceIn(16L * 1024 * 1024, 64L * 1024 * 1024).toInt()
  }
}
