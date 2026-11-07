package com.manhas.reader

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable data object Library : NavKey

@Serializable
data class Reader(
  val documentUri: String,
  val documentId: String,
  val folderTreeUri: String,
  val folderDocumentId: String,
  val title: String,
) : NavKey
