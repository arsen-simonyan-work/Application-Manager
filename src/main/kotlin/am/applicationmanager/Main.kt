package am.applicationmanager

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.imageio.ImageIO

private val rowShape = RoundedCornerShape(10.dp)

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "Application Manager") {
        MaterialTheme(colorScheme = darkColorScheme()) { ApplicationManager() }
    }
}

@Composable
private fun ApplicationManager() {
    var currentTab by remember { mutableStateOf("All") }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<AppRecord?>(null) }
    var result by remember { mutableStateOf(ScanResult(emptyList(), emptyList(), emptyList())) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Ready") }
    var prompt by remember { mutableStateOf<AppRecord?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun reload() {
        if (busy) return
        busy = true
        status = "Scanning applications…"
        selected = null
        scope.launch {
            try {
                result = withContext(Dispatchers.IO) { LinuxBackend.scan() }
                status = "Found ${result.all.size} entries • ${result.unique.size} unique • ${result.duplicates.size} duplicates"
            } catch (e: Exception) { info = "Scanning failed: ${e.message}"; status = "Scan failed" }
            finally { busy = false }
        }
    }

    fun action(block: () -> Unit) {
        try { block() } catch (e: Exception) { info = e.message ?: e.toString() }
    }

    LaunchedEffect(Unit) { reload() }

    val items = remember(result, currentTab, query) {
        val needle = query.trim().lowercase()
        result.forTab(currentTab).filter { rec ->
            needle.isEmpty() || listOf(rec.name, rec.group, rec.entry, rec.exec, rec.path)
                .any { it.contains(needle, ignoreCase = true) }
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Application Manager", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            OutlinedButton(onClick = { reload() }, enabled = !busy) { Text("Rescan") }
        }
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(modifier = Modifier.weight(1f).fillMaxHeight(), shape = RoundedCornerShape(16.dp), tonalElevation = 2.dp) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Applications", fontWeight = FontWeight.SemiBold)
                    ScrollableTabRow(selectedTabIndex = allTabs.indexOf(currentTab).coerceAtLeast(0), edgePadding = 0.dp) {
                        allTabs.forEach { name ->
                            Tab(selected = currentTab == name, onClick = { currentTab = name; selected = null },
                                text = { Text(name, maxLines = 1) })
                        }
                    }
                    OutlinedTextField(query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(),
                        singleLine = true, label = { Text("Search by name, group, command or path") },
                        trailingIcon = { if (query.isNotEmpty()) TextButton(onClick = { query = "" }) { Text("Clear") } })
                    Text("Shown: ${items.size}", style = MaterialTheme.typography.labelMedium)
                    HorizontalDivider()
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.fillMaxSize()) {
                        items(items, key = { it.listKey }) { rec ->
                            AppRow(rec, isSelected = selected == rec, onClick = { selected = rec }, onOpen = { action { LinuxBackend.launch(rec) } })
                        }
                    }
                }
            }
            Surface(Modifier.width(370.dp).fillMaxHeight(), shape = RoundedCornerShape(16.dp), tonalElevation = 2.dp) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Details", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    val rec = selected
                    if (rec == null) {
                        Text("Select an application on the left", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        AppIcon(rec, size = 64)
                        Text(rec.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Detail("Source", rec.group)
                        Detail("Entry", rec.entry)
                        Detail("Command", rec.exec.ifBlank { "—" })
                        Detail("Desktop file", rec.path.ifBlank { "—" })
                        if (rec.reason.isNotBlank()) Detail("Duplicate", rec.reason)
                        HorizontalDivider()
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { action { LinuxBackend.launch(rec) } }, modifier = Modifier.weight(1f)) { Text("Launch") }
                            OutlinedButton(onClick = { action { LinuxBackend.openFolder(rec) } }, modifier = Modifier.weight(1f), enabled = rec.path.isNotBlank()) { Text("Folder") }
                        }
                        OutlinedButton(onClick = { action { LinuxBackend.openEntry(rec) } },
                            modifier = Modifier.fillMaxWidth(), enabled = rec.path.isNotBlank()) { Text("Open .desktop") }
                        Button(onClick = { prompt = rec }, modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Uninstall / Remove") }
                    }
                    Spacer(Modifier.weight(1f))
                    Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    prompt?.let { rec ->
        AlertDialog(onDismissRequest = { prompt = null }, title = { Text("Confirm removal") },
            text = { SelectionContainer { Text("${rec.name}\n\n${LinuxBackend.uninstallDescription(rec)}\n\nContinue?") } },
            confirmButton = { TextButton(onClick = {
                prompt = null
                busy = true
                scope.launch {
                    try {
                        val (ok, message) = withContext(Dispatchers.IO) { LinuxBackend.uninstall(rec) }
                        info = if (ok) "Success:\n$message" else "Uninstall failed:\n$message"
                    } catch (e: Exception) { info = "Uninstall failed: ${e.message}" }
                    finally { busy = false; reload() }
                }
            }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { prompt = null }) { Text("Cancel") } })
    }
    info?.let { message ->
        AlertDialog(onDismissRequest = { info = null }, title = { Text("Application Manager") },
            text = { SelectionContainer { Text(message) } },
            confirmButton = { TextButton(onClick = { info = null }) { Text("OK") } })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppRow(rec: AppRecord, isSelected: Boolean, onClick: () -> Unit, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(
        if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        rowShape).combinedClickable(onClick = onClick, onDoubleClick = onOpen).padding(9.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AppIcon(rec, 36)
        Column(Modifier.weight(1f)) {
            Text(rec.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            Text(rec.exec.ifBlank { rec.entry }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(rec.group, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun AppIcon(rec: AppRecord, size: Int) {
    val image = remember(rec.icon) { resolveIcon(rec.icon) }
    if (image != null) Image(image, contentDescription = null, modifier = Modifier.size(size.dp))
    else Box(Modifier.size(size.dp).background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center) { Text(rec.name.take(1).uppercase(), fontWeight = FontWeight.Bold) }
}

/** Java ImageIO supports PNG/JPEG/GIF. Unsupported SVG/XPM gets a safe fallback. */
private fun resolveIcon(name: String): ImageBitmap? {
    if (name.isBlank()) return null
    val roots = listOf("/usr/share/pixmaps", "${System.getProperty("user.home")}/.icons") +
        listOf(512, 256, 128, 64, 48, 32, 24, 16).flatMap { size ->
            listOf("/usr/share/icons/hicolor/${size}x$size/apps",
                "${System.getProperty("user.home")}/.local/share/icons/hicolor/${size}x$size/apps") }
    val names = if (File(name).isAbsolute) listOf(File(name)) else roots.flatMap { root ->
        listOf("$root/$name", "$root/$name.png", "$root/$name.jpg", "$root/$name.gif").map(::File) }
    return names.firstNotNullOfOrNull { file ->
        try { if (file.isFile && file.length() < 8_000_000) ImageIO.read(file)?.toComposeImageBitmap() else null }
        catch (_: Exception) { null }
    }
}

@Composable
private fun Detail(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer { Text(value, style = MaterialTheme.typography.bodySmall) }
    }
}
