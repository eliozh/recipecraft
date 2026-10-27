package com.elio.recipecraft.ui.library

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.elio.recipecraft.R
import com.elio.recipecraft.ui.PlaceholderScreen

@Composable
fun LibraryScreen(modifier: Modifier = Modifier) {
    PlaceholderScreen(text = stringResource(R.string.library_placeholder), modifier = modifier)
}
