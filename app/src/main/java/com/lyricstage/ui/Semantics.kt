package com.lyricstage.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

fun Modifier.semanticsDescription(text: String): Modifier = semantics { contentDescription = text }
