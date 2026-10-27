package com.elio.recipecraft.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.elio.recipecraft.R
import com.elio.recipecraft.ui.PlaceholderScreen

@Composable
fun ChatScreen(modifier: Modifier = Modifier) {
    PlaceholderScreen(text = stringResource(R.string.chat_placeholder), modifier = modifier)
}
