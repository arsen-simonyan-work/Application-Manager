package am.applicationmanager

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal enum class FeedbackKind { Success, Error, Information }

internal data class FeedbackMessage(
    val title: String,
    val summary: String,
    val details: String,
    val kind: FeedbackKind,
)

internal fun removalFeedback(
    record: AppRecord,
    launcherOnly: Boolean,
    success: Boolean,
    output: String,
): FeedbackMessage {
    val shortcut = launcherOnly || record.group == "User"
    val wine = !shortcut && record.group == "Wine"
    val name = "“${record.name}”"
    return when {
        !success -> FeedbackMessage(
            title = when {
                shortcut -> "Could not delete shortcut"
                wine -> "Could not open Wine uninstaller"
                else -> "Uninstall did not finish"
            },
            summary = when {
                shortcut -> "The shortcut for $name could not be deleted."
                wine -> "The Wine uninstaller could not be opened."
                else -> "The removal of $name did not complete."
            },
            details = output,
            kind = FeedbackKind.Error,
        )
        shortcut -> FeedbackMessage(
            title = "Shortcut deleted",
            summary = "The shortcut for $name was deleted. The application is still installed.",
            details = output,
            kind = FeedbackKind.Success,
        )
        wine -> FeedbackMessage(
            title = "Wine uninstaller opened",
            summary = "Complete the removal of $name in the Wine uninstaller window.",
            details = output,
            kind = FeedbackKind.Information,
        )
        else -> FeedbackMessage(
            title = "Application uninstalled",
            summary = "$name was uninstalled successfully.",
            details = output,
            kind = FeedbackKind.Success,
        )
    }
}

@Composable
internal fun FeedbackDialog(message: FeedbackMessage, onDismiss: () -> Unit) {
    key(message) {
        var showDetails by remember { mutableStateOf(false) }
        val scrollState = rememberScrollState()
        val accent = when (message.kind) {
            FeedbackKind.Success -> MaterialTheme.colorScheme.tertiary
            FeedbackKind.Error -> MaterialTheme.colorScheme.error
            FeedbackKind.Information -> MaterialTheme.colorScheme.primary
        }

        AlertDialog(
            onDismissRequest = onDismiss,
            icon = {
                Box(Modifier.size(44.dp).background(accent.copy(alpha = 0.15f), CircleShape),
                    contentAlignment = Alignment.Center) {
                    Text(when (message.kind) {
                        FeedbackKind.Success -> "✓"
                        FeedbackKind.Error -> "!"
                        FeedbackKind.Information -> "i"
                    }, color = accent, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                }
            },
            title = { Text(message.title) },
            text = {
                Box(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    Column(
                        Modifier.fillMaxWidth().verticalScroll(scrollState).padding(end = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SelectionContainer {
                            Text(message.summary, style = MaterialTheme.typography.bodyLarge)
                        }
                        if (message.details.isNotBlank()) {
                            TextButton(onClick = { showDetails = !showDetails }) {
                                Text(if (showDetails) "Hide technical details" else "Show technical details")
                            }
                            if (showDetails) {
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                                    shape = RoundedCornerShape(8.dp),
                                ) {
                                    SelectionContainer {
                                        Text(
                                            message.details,
                                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (scrollState.maxValue > 0) {
                        VerticalScrollbar(
                            adapter = rememberScrollbarAdapter(scrollState),
                            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        )
    }
}
