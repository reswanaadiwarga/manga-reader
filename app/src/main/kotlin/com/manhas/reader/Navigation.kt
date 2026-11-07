package com.manhas.reader

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.manhas.reader.ui.library.LibraryScreen
import com.manhas.reader.ui.reader.ReaderScreen

@Composable
fun MainNavigation() {
  val backStack = rememberNavBackStack(Library)

  NavDisplay(
    backStack = backStack,
    onBack = { backStack.removeLastOrNull() },
    entryDecorators = listOf(
      rememberSaveableStateHolderNavEntryDecorator(),
      rememberViewModelStoreNavEntryDecorator(),
    ),
    entryProvider =
      entryProvider {
        entry<Library> {
          LibraryScreen(
            onOpenDocument = { entry, treeUri, folderDocumentId ->
              backStack.add(
                Reader(
                  documentUri = entry.uri.toString(),
                  documentId = entry.documentId,
                  folderTreeUri = treeUri,
                  folderDocumentId = folderDocumentId,
                  title = entry.name,
                )
              )
            }
          )
        }
        entry<Reader> { reader ->
          ReaderScreen(
            args = reader,
            onBack = { backStack.removeLastOrNull() },
          )
        }
      },
  )
}
