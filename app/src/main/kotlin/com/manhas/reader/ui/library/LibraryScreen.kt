package com.manhas.reader.ui.library

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.manhas.reader.data.LibraryEntry

@OptIn(ExperimentalMaterial3Api::class)
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

  BackHandler(enabled = state.crumbs.size > 1) {
    viewModel.goUp()
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Text(
            text = state.crumbs.lastOrNull()?.name ?: "Manga Reader",
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        },
        navigationIcon = {
          if (state.crumbs.size > 1) {
            TextButton(onClick = { viewModel.goUp() }) {
              Text("Up")
            }
          }
        },
        actions = {
          TextButton(onClick = { picker.launch(null) }) {
            Text("Folder")
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

        state.loading -> CircularProgressIndicator(
          modifier = Modifier.align(Alignment.Center),
        )

        state.error != null -> ErrorState(
          message = state.error.orEmpty(),
          onRetry = viewModel::retry,
          modifier = Modifier.align(Alignment.Center),
        )

        state.entries.isEmpty() -> Text(
          text = "No folders or PDFs here",
          style = MaterialTheme.typography.bodyLarge,
          modifier = Modifier.align(Alignment.Center),
        )

        else -> {
          val folderId = state.crumbs.last().documentId
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
          LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp),
          ) {
            items(state.entries, key = { it.documentId }) { entry ->
              ListItem(
                modifier = Modifier.clickable {
                  if (entry.isDirectory) {
                    viewModel.openFolder(entry)
                  } else {
                    onOpenDocument(
                      entry,
                      state.rootUri.toString(),
                      folderId,
                    )
                  }
                },
                headlineContent = {
                  Text(
                    text = entry.name,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                  )
                },
                leadingContent = {
                  Text(
                    text = if (entry.isDirectory) "DIR" else "PDF",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                  )
                },
              )
            }
          }
        }
      }
    }
  }
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
      text = "Pick the root folder that contains your series folders and chapter PDFs.",
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
