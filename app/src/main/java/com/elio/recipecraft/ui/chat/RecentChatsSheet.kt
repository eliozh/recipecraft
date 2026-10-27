package com.elio.recipecraft.ui.chat

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.elio.recipecraft.R
import com.elio.recipecraft.data.chat.Conversation

/** The stored chats, the latest first; tapping one opens it, and each can be deleted. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentChatsSheet(
    conversations: List<Conversation>,
    openId: Long?,
    onOpen: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag(RecentChatsTestTags.SHEET),
    ) {
        Text(
            stringResource(R.string.chat_recent),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 24.dp).semantics { heading() },
        )
        if (conversations.isEmpty()) {
            Text(
                stringResource(R.string.chat_recent_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
        }
        val context = LocalContext.current
        LazyColumn(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            items(conversations, key = { it.id }) { conversation ->
                val open = conversation.id == openId
                ListItem(
                    headlineContent = {
                        Text(conversationTitle(conversation), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    },
                    supportingContent = {
                        Text(
                            DateUtils.formatDateTime(
                                context,
                                conversation.updatedAt,
                                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_ALL,
                            ),
                        )
                    },
                    trailingContent = {
                        IconButton(
                            onClick = { onDelete(conversation.id) },
                            modifier = Modifier.testTag(RecentChatsTestTags.DELETE),
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_delete),
                                contentDescription = stringResource(R.string.chat_delete_conversation),
                            )
                        }
                    },
                    colors = if (open) {
                        ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                    } else {
                        ListItemDefaults.colors()
                    },
                    modifier = Modifier
                        .semantics { selected = open }
                        .clickable { onOpen(conversation.id) }
                        .testTag(RecentChatsTestTags.ITEM),
                )
            }
        }
    }
}

/** The start of the chat's first turn, or a summary of its composer request in the current language. */
@Composable
private fun conversationTitle(conversation: Conversation): String =
    conversation.firstRequest?.let { generationSummary(it) }
        ?: conversation.title.ifEmpty { stringResource(R.string.chat_new) }

/** Stable handles for UI tests. */
object RecentChatsTestTags {
    const val SHEET = "recent_chats"
    const val ITEM = "recent_chats_item"
    const val DELETE = "recent_chats_delete"
}
