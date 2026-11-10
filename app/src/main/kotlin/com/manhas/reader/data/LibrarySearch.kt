package com.manhas.reader.data

import com.manhas.reader.util.NaturalOrder
import java.util.Locale

/**
 * One child of a library folder, in a form the index builder can traverse
 * without Android types.
 *
 * [uriString] is the document URI as a string (empty when the caller only needs
 * names). [isDirectory] decides whether [LibrarySearchIndex] descends into it.
 */
data class SearchNode(
  val documentId: String,
  val name: String,
  val isDirectory: Boolean,
  val uriString: String = "",
)

/**
 * A searchable document discovered anywhere under the library root.
 *
 * [folderPath] holds the subfolder names below the root joined with `" / "`
 * (empty for a root-level document); [relativePath] is the same path with the
 * file name appended. [searchText] is the normalized folder names plus file
 * stem, ready for [LibrarySearch.matches]. The root's own display name is never
 * part of it.
 */
data class SearchDocument(
  val documentId: String,
  val uriString: String,
  val name: String,
  val parentDocumentId: String,
  val folderPath: String,
  val relativePath: String,
  val searchText: String,
)

/**
 * Pure, Android-free path/file-name matching for library search.
 *
 * Matching is deliberately shallow: the whole relative path is normalized to
 * lowercase words, the query is split on whitespace, and every query token must
 * appear as a substring of the path in any order. There is no fuzzy matching and
 * no content/OCR search. File extensions are excluded so `pdf` cannot match
 * every PDF.
 */
object LibrarySearch {
  /**
   * Lowercases [text] with [Locale.ROOT], turns every non-letter/digit into a
   * space, and collapses runs of whitespace. Leading/trailing spaces are
   * trimmed, so an all-punctuation string normalizes to `""`.
   */
  fun normalize(text: String): String {
    val lowered = text.lowercase(Locale.ROOT)
    val builder = StringBuilder(lowered.length)
    var pendingSpace = false
    for (character in lowered) {
      if (character.isLetterOrDigit()) {
        if (pendingSpace && builder.isNotEmpty()) builder.append(' ')
        pendingSpace = false
        builder.append(character)
      } else {
        pendingSpace = true
      }
    }
    return builder.toString()
  }

  /** Normalized whitespace-separated tokens of [query]; empty for a blank query. */
  fun tokens(query: String): List<String> {
    val normalized = normalize(query)
    if (normalized.isEmpty()) return emptyList()
    return normalized.split(' ').filter { it.isNotEmpty() }
  }

  /**
   * True when every non-empty token of [query] occurs as a substring of
   * [searchText] (expected to already be normalized). A blank query matches
   * nothing.
   */
  fun matches(query: String, searchText: String): Boolean {
    val queryTokens = tokens(query)
    if (queryTokens.isEmpty()) return false
    return queryTokens.all { searchText.contains(it) }
  }

  /**
   * File name without its final extension: `"Chapter 1.cbz"` -> `"Chapter 1"`,
   * `"Archive.tar.gz"` -> `"Archive.tar"`. Names without a dot (or whose only
   * dot is a leading hidden-file dot) are returned unchanged.
   */
  fun fileStem(name: String): String {
    val slash = name.lastIndexOfAny(charArrayOf('/', '\\'))
    val base = if (slash >= 0) name.substring(slash + 1) else name
    val dot = base.lastIndexOf('.')
    return if (dot > 0) base.substring(0, dot) else base
  }

  /**
   * Normalized search text for a document: [folderNames] (subfolders only, never
   * the root display name) followed by the file stem of [fileName].
   */
  fun searchText(folderNames: List<String>, fileName: String): String =
    normalize((folderNames + fileStem(fileName)).joinToString(" "))
}

/**
 * Recursive index builder for the selected library tree.
 *
 * [build] walks [rootDocumentId] depth-first using [listChildren] as the only
 * I/O boundary, collecting documents with their folder paths. Keeping the
 * traversal pure lets the JVM tests drive it with an in-memory tree while the
 * app wires it to `DocumentsContract`.
 *
 * Failure policy: a failure to list the root propagates (the UI turns it into a
 * retryable search error), while a failure to list any subfolder is skipped so
 * one unreadable directory cannot hide the rest of the library. Visited folder
 * IDs and seen document IDs prevent cycles and duplicates, and [maxDepth] caps
 * recursion so a pathological provider cannot loop forever.
 */
object LibrarySearchIndex {
  /**
   * Returns every readable document under [rootDocumentId], natural-sorted by
   * [SearchDocument.relativePath].
   *
   * [maxDepth] is the deepest folder level that may be traversed below the root;
   * `0` lists only the root's direct children.
   *
   * @throws Throwable whatever [listChildren] throws for the root listing.
   */
  fun build(
    rootDocumentId: String,
    listChildren: (String) -> List<SearchNode>,
    maxDepth: Int = 64,
  ): List<SearchDocument> {
    val documents = mutableListOf<SearchDocument>()
    val visitedFolders = HashSet<String>()
    val seenDocuments = HashSet<String>()
    visitedFolders += rootDocumentId

    fun visit(folderId: String, folderNames: List<String>, depth: Int, children: List<SearchNode>) {
      val folderPath = folderNames.joinToString(" / ")
      for (node in children) {
        if (node.isDirectory) {
          if (depth >= maxDepth) continue
          if (!visitedFolders.add(node.documentId)) continue
          val childNames = folderNames + node.name
          val grandChildren = try {
            listChildren(node.documentId)
          } catch (error: Exception) {
            // A single unreadable series folder must not abort the whole index.
            emptyList()
          }
          visit(node.documentId, childNames, depth + 1, grandChildren)
        } else {
          if (!seenDocuments.add(node.documentId)) continue
          documents += SearchDocument(
            documentId = node.documentId,
            uriString = node.uriString,
            name = node.name,
            parentDocumentId = folderId,
            folderPath = folderPath,
            relativePath = if (folderPath.isEmpty()) node.name else "$folderPath / ${node.name}",
            searchText = LibrarySearch.searchText(folderNames, node.name),
          )
        }
      }
    }

    // The root listing is deliberately outside the try/catch: a broken root is a
    // search failure the user must see, not an empty result set.
    val rootChildren = listChildren(rootDocumentId)
    visit(rootDocumentId, emptyList(), 0, rootChildren)

    return documents.sortedWith { a, b -> NaturalOrder.compare(a.relativePath, b.relativePath) }
  }
}
