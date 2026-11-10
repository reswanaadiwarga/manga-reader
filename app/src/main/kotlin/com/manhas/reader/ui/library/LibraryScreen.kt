package com.manhas.reader.ui.library

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.manhas.reader.data.DocumentFormats
import com.manhas.reader.data.LibraryEntry
import com.manhas.reader.data.ReadingState
import com.manhas.reader.util.ProgressKey

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
  onOpenDocument: (LibraryEntry, String, String) -> Unit,
) {
  val app = LocalContext.current.applicationContext as Application
  val viewModel: LibraryViewModel = viewModel { LibraryViewModel(app) }
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
    if (uri != null) viewModel.setRoot(uri)
  }

  var chapterActionsFor by remember { mutableStateOf<LibraryEntry?>(null) }
  var confirmFolderReset by remember { mutableStateOf(false) }

  val searchFocusRequester = remember { FocusRequester() }
  val keyboard = LocalSoftwareKeyboardController.current
  val focusManager = LocalFocusManager.current

  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  // Focus and raise the keyboard as soon as the search bar appears; drop both
  // when search closes so the IME/focus do not linger over the folder list.
  LaunchedEffect(state.searchActive) {
    if (state.searchActive) {
      searchFocusRequester.requestFocus()
      keyboard?.show()
    } else {
      keyboard?.hide()
      focusManager.clearFocus()
    }
  }

  BackHandler(enabled = state.searchActive || state.crumbs.size > 1) {
    if (state.searchActive) {
      viewModel.closeSearch()
    } else {
      viewModel.goUp()
    }
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          if (state.searchActive) {
            LibrarySearchField(
              query = state.searchQuery,
              onQueryChange = viewModel::setSearchQuery,
              focusRequester = searchFocusRequester,
              onSearch = { keyboard?.hide() },
            )
          } else {
            Text(
              text = state.crumbs.lastOrNull()?.name ?: "Manga Reader",
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        },
        navigationIcon = {
          when {
            state.searchActive -> TextButton(onClick = viewModel::closeSearch) {
              Text("Back")
            }
            state.crumbs.size > 1 -> TextButton(onClick = { viewModel.goUp() }) {
              Text("Up")
            }
          }
        },
        actions = {
          if (state.searchActive) {
            if (state.searchQuery.isNotEmpty()) {
              TextButton(onClick = { viewModel.setSearchQuery("") }) {
                Text("Clear")
              }
            }
          } else {
            if (state.rootUri != null && !state.loading && state.error == null) {
              TextButton(onClick = viewModel::openSearch) {
                Text("Search")
              }
            }
            if (state.entries.any { !it.isDirectory }) {
              TextButton(onClick = { confirmFolderReset = true }) {
                Text("Reset")
              }
            }
            TextButton(onClick = { picker.launch(null) }) {
              Text("Folder")
            }
          }
        },
      )
    },
  ) { innerPadding ->
    Box(
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding),
    ) {
      when {
        state.rootUri == null -> ChooseFolder(
          onChoose = { picker.launch(null) },
          modifier = Modifier.align(Alignment.Center),
        )

        state.searchActive -> SearchContent(
          state = state,
          onRetry = viewModel::retrySearch,
          onOpen = { result ->
            keyboard?.hide()
            focusManager.clearFocus()
            onOpenDocument(result.entry, state.rootUri.toString(), result.parentDocumentId)
          },
          onLongPress = { chapterActionsFor = it },
        )

        state.loading -> CircularProgressIndicator(
          modifier = Modifier.align(Alignment.Center),
        )

        state.error != null -> ErrorState(
          message = state.error.orEmpty(),
          onRetry = viewModel::retry,
          modifier = Modifier.align(Alignment.Center),
        )

        state.entries.isEmpty() -> Text(
          text = "No folders or readable files here",
          style = MaterialTheme.typography.bodyLarge,
          modifier = Modifier.align(Alignment.Center),
        )

        else -> {
          val folderId = state.crumbs.last().documentId
          // Resolved once per folder/state change so the card does not hash every
          // row on each recomposition.
          val lastReadEntry = remember(state.entries, state.lastReadDocumentHash) {
            state.lastReadDocumentHash?.let { hash ->
              state.entries.firstOrNull { entry ->
                !entry.isDirectory && ProgressKey.of(entry.uri.toString()) == hash
              }
            }
          }
          val saved = viewModel.getScroll(folderId)
          val listState = remember(folderId) {
            LazyListState(saved.index, saved.offset)
          }
          DisposableEffect(folderId) {
            onDispose {
              viewModel.saveScroll(
                folderId,
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset,
              )
            }
          }
          Column(modifier = Modifier.fillMaxSize()) {
            if (lastReadEntry != null) {
              ContinueCard(
                title = lastReadEntry.name,
                onClick = {
                  onOpenDocument(lastReadEntry, state.rootUri.toString(), folderId)
                },
              )
            }
            LazyColumn(
              state = listState,
              modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
              contentPadding = PaddingValues(vertical = 8.dp),
            ) {
              items(state.entries, key = { it.documentId }) { entry ->
                val documentHash = remember(entry.uri) {
                  ProgressKey.of(entry.uri.toString())
                }
                val isLastRead = !entry.isDirectory &&
                  state.lastReadDocumentHash != null &&
                  documentHash == state.lastReadDocumentHash
                LibraryRow(
                  entry = entry,
                  reading = state.readingStates[documentHash],
                  savedPage = state.savedPages[documentHash],
                  isLastRead = isLastRead,
                  folderPath = null,
                  onClick = {
                    if (entry.isDirectory) {
                      viewModel.openFolder(entry)
                    } else {
                      onOpenDocument(entry, state.rootUri.toString(), folderId)
                    }
                  },
                  onLongClick = if (entry.isDirectory) {
                    null
                  } else {
                    { chapterActionsFor = entry }
                  },
                )
              }
            }
          }
        }
      }
    }
  }

  val actionEntry = chapterActionsFor
  if (actionEntry != null) {
    ChapterActionsDialog(
      name = actionEntry.name,
      onMarkUnread = {
        chapterActionsFor = null
        viewModel.markUnread(actionEntry)
      },
      onMarkRead = {
        chapterActionsFor = null
        viewModel.markRead(actionEntry)
      },
      onDismiss = { chapterActionsFor = null },
    )
  }
  if (confirmFolderReset) {
    FolderResetDialog(
      onConfirm = {
        confirmFolderReset = false
        viewModel.resetCurrentFolder()
      },
      onDismiss = { confirmFolderReset = false },
    )
  }
}

/**
 * Compact single-line search field shown in the top bar while search is active.
 * Enter/Search hides the keyboard; the caller owns focus and result-tap behavior.
 */
@Composable
private fun LibrarySearchField(
  query: String,
  onQueryChange: (String) -> Unit,
  focusRequester: FocusRequester,
  onSearch: () -> Unit,
) {
  BasicTextField(
    value = query,
    onValueChange = onQueryChange,
    modifier = Modifier
      .fillMaxWidth()
      .focusRequester(focusRequester),
    singleLine = true,
    textStyle = MaterialTheme.typography.bodyLarge.copy(
      color = MaterialTheme.colorScheme.onSurface,
    ),
    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
    keyboardActions = KeyboardActions(onSearch = { onSearch() }),
    decorationBox = { innerTextField ->
      Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
          if (query.isEmpty()) {
            Text(
              text = "Search library",
              style = MaterialTheme.typography.bodyLarge,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          innerTextField()
        }
      }
    },
  )
}

/** Results/hint/loading/error panel that replaces the folder list during search. */
@Composable
private fun SearchContent(
  state: LibraryUiState,
  onRetry: () -> Unit,
  onOpen: (SearchResult) -> Unit,
  onLongPress: (LibraryEntry) -> Unit,
  modifier: Modifier = Modifier,
) {
  val listState = rememberLazyListState()
  // A new query should start at the top of its own result set; never touches the
  // folder list's separate restored scroll state.
  LaunchedEffect(state.searchQuery) {
    if (state.searchQuery.isNotEmpty()) listState.scrollToItem(0)
  }
  Box(modifier = modifier.fillMaxSize()) {
    when {
      state.searchError != null -> ErrorState(
        message = state.searchError.orEmpty(),
        onRetry = onRetry,
        modifier = Modifier.align(Alignment.Center),
      )

      state.searchQuery.isBlank() -> Text(
        text = "Search by series or chapter name",
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.align(Alignment.Center),
      )

      state.searchLoading -> Column(
        modifier = Modifier.align(Alignment.Center),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.height(12.dp))
        Text(
          text = "Searching library…",
          style = MaterialTheme.typography.bodyMedium,
        )
      }

      state.searchResults.isEmpty() -> Text(
        text = "No matches for \"${state.searchQuery}\"",
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.align(Alignment.Center),
      )

      else -> LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
      ) {
        items(state.searchResults, key = { it.entry.documentId }) { result ->
          val isLastRead = state.searchLastReadHashes[result.parentDocumentId] == result.hash
          LibraryRow(
            entry = result.entry,
            reading = state.readingStates[result.hash],
            savedPage = state.savedPages[result.hash],
            isLastRead = isLastRead,
            folderPath = result.folderPath.ifEmpty { null },
            onClick = { onOpen(result) },
            onLongClick = { onLongPress(result.entry) },
          )
        }
      }
    }
  }
}

/**
 * One library row, shared by folder browsing and search results so visuals stay
 * identical. Search passes [folderPath] to show the series path above the
 * progress bar; folder browsing passes null.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun LibraryRow(
  entry: LibraryEntry,
  reading: ReadingState?,
  savedPage: Int?,
  isLastRead: Boolean,
  folderPath: String?,
  onClick: () -> Unit,
  onLongClick: (() -> Unit)?,
) {
  val fraction = reading?.fraction(savedPage)
  val finished = reading?.finished == true
  ListItem(
    modifier = Modifier.combinedClickable(
      onClick = onClick,
      onLongClick = onLongClick,
    ),
    headlineContent = {
      Text(
        text = entry.name,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
    },
    leadingContent = {
      Text(
        text = DocumentFormats.libraryLabel(entry.name, entry.isDirectory),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
      )
    },
    supportingContent = if (folderPath != null || fraction != null) {
      {
        Column {
          if (folderPath != null) {
            Text(
              text = folderPath,
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
          if (fraction != null) {
            // Thin determinate bar with no percentage text. It only appears once
            // the page count is known.
            LinearProgressIndicator(
              progress = { fraction },
              modifier = Modifier
                .fillMaxWidth()
                .height(2.dp),
              gapSize = 0.dp,
              drawStopIndicator = {},
            )
          }
        }
      }
    } else {
      null
    },
    trailingContent = if (finished || isLastRead) {
      {
        Row(
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          if (finished) {
            Text(
              text = "✓",
              style = MaterialTheme.typography.titleMedium,
              color = MaterialTheme.colorScheme.primary,
            )
          }
          if (isLastRead) {
            Surface(
              shape = RoundedCornerShape(50),
              color = MaterialTheme.colorScheme.primaryContainer,
              contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
              Text(
                text = "Last read",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
              )
            }
          }
        }
      }
    } else {
      null
    },
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContinueCard(title: String, onClick: () -> Unit) {
  Card(
    onClick = onClick,
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 8.dp),
  ) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
      Text(
        text = "Continue reading",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
      )
      Spacer(modifier = Modifier.height(2.dp))
      Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
private fun ChapterActionsDialog(
  name: String,
  onMarkUnread: () -> Unit,
  onMarkRead: () -> Unit,
  onDismiss: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Text(
        text = name,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
    },
    text = {
      Column {
        TextButton(
          onClick = onMarkUnread,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(
            text = "Mark as unread",
            modifier = Modifier.fillMaxWidth(),
          )
        }
        TextButton(
          onClick = onMarkRead,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(
            text = "Mark as read",
            modifier = Modifier.fillMaxWidth(),
          )
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) {
        Text("Cancel")
      }
    },
  )
}

@Composable
private fun FolderResetDialog(
  onConfirm: () -> Unit,
  onDismiss: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Reset this folder?") },
    text = {
      Text(
        "Clears saved pages, finished marks, and the Continue card for every " +
          "chapter directly in this folder. Other folders are untouched.",
      )
    },
    confirmButton = {
      TextButton(onClick = onConfirm) {
        Text("Reset")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("Cancel")
      }
    },
  )
}

@Composable
private fun ChooseFolder(onChoose: () -> Unit, modifier: Modifier = Modifier) {
  Column(
    modifier = modifier.padding(24.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Text(
      text = "Choose your manga folder",
      style = MaterialTheme.typography.titleLarge,
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
      text = "Pick the root folder that contains your series folders and chapters " +
        "(PDF, CBZ, or ZIP).",
      style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(modifier = Modifier.height(24.dp))
    Button(onClick = onChoose) {
      Text("Choose folder")
    }
  }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
  Column(
    modifier = modifier
      .fillMaxWidth()
      .padding(24.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text(
      text = message,
      style = MaterialTheme.typography.bodyLarge,
    )
    Spacer(modifier = Modifier.height(16.dp))
    Button(onClick = onRetry) {
      Text("Retry")
    }
  }
}
