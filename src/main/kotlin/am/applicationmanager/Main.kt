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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.window.application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val rowShape = RoundedCornerShape(10.dp)

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Application Manager",
        state = rememberWindowState(placement = WindowPlacement.Maximized)
    ) {
        MaterialTheme(colorScheme = darkColorScheme()) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                ApplicationManager()
            }
        }
    }
}

private enum class Section(val title: String) {
    Applications("Applications"), Launchers("Launchers"), Services("Services")
}
private data class RemoveRequest(val record: AppRecord, val launcherOnly: Boolean)

@Composable
private fun ApplicationManager() {
    var section by remember { mutableStateOf(Section.Applications) }
    var filter by remember { mutableStateOf("All") }
    var search by remember { mutableStateOf("") }
    var selectedApp by remember { mutableStateOf<String?>(null) }
    var selectedLauncher by remember { mutableStateOf<String?>(null) }
    var selectedService by remember { mutableStateOf<String?>(null) }
    var apps by remember { mutableStateOf(ApplicationCatalog(emptyList(), emptyList(), emptyList())) }
    var services by remember { mutableStateOf<ServiceCatalog?>(null) }
    var appBusy by remember { mutableStateOf(false) }
    var serviceBusy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Ready") }
    var message by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<RemoveRequest?>(null) }
    val scope = rememberCoroutineScope()

    fun refreshApps() {
        if (appBusy) return
        appBusy = true
        status = "Scanning installed applications and launchers…"
        scope.launch {
            try {
                apps = withContext(Dispatchers.IO) { CatalogScanner.from(LinuxBackend.scan()) }
                status = "${apps.applications.size} applications, ${apps.launchers.size} launchers"
            } catch (e: Exception) { message = e.message ?: e.toString() }
            finally { appBusy = false }
        }
    }
    fun refreshServices() {
        if (serviceBusy) return
        serviceBusy = true
        status = "Scanning services…"
        scope.launch {
            try {
                services = withContext(Dispatchers.IO) { ServiceScanner.scan() }
                status = "${services?.services?.size ?: 0} systemd services"
            } catch (e: Exception) { message = e.message ?: e.toString() }
            finally { serviceBusy = false }
        }
    }
    fun launch(rec: AppRecord) {
        try { LinuxBackend.launch(rec) }
        catch (e: Exception) { message = e.message ?: e.toString() }
    }
    fun openFolder(rec: AppRecord) {
        try { LinuxBackend.openFolder(rec) }
        catch (e: Exception) { message = e.message ?: e.toString() }
    }

    LaunchedEffect(Unit) { refreshApps() }
    LaunchedEffect(section) {
        if (section == Section.Services && services == null) refreshServices()
    }

    val filters = when (section) {
        Section.Services -> listOf("All", "Running", "Stopped", "Failed", "System", "User")
        Section.Launchers -> listOf("All", "Duplicates", "System", "User", "Snap", "Flatpak", "Wine")
        Section.Applications -> listOf("All", "System", "User", "Snap", "Flatpak", "Wine")
    }
    val appItems = apps.applications.filter { entry ->
        (filter == "All" || filter == entry.source) &&
            (search.isBlank() || listOf(entry.name, entry.source, entry.id)
                .any { it.contains(search, ignoreCase = true) })
    }
    val launcherItems = (if (filter == "Duplicates") apps.duplicateLaunchers
        else apps.launchers.filter { filter == "All" || it.group == filter })
        .filter { rec -> search.isBlank() || listOf(rec.name, rec.exec, rec.path, rec.group)
            .any { it.contains(search, ignoreCase = true) } }
    val serviceItems = services?.services.orEmpty().filter { unit ->
        val matched = when (filter) {
            "All" -> true
            "Running" -> unit.active == "active"
            "Stopped" -> unit.active !in setOf("active", "failed")
            "Failed" -> unit.active == "failed"
            else -> unit.scope == filter
        }
        matched && (search.isBlank() || listOf(unit.name, unit.scope, unit.description,
            unit.active, unit.startup).any { it.contains(search, ignoreCase = true) })
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(Modifier.width(208.dp).fillMaxHeight(), shape = RoundedCornerShape(16.dp),
                tonalElevation = 2.dp) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("SECTIONS", style = MaterialTheme.typography.labelMedium)
                    Section.entries.forEach { entry ->
                        if (section == entry) {
                            FilledTonalButton(onClick = {}, modifier = Modifier.fillMaxWidth()) {
                                Text(entry.title, modifier = Modifier.fillMaxWidth())
                            }
                        } else {
                            TextButton(onClick = {
                                section = entry
                                filter = "All"
                                search = ""
                            }, modifier = Modifier.fillMaxWidth()) {
                                Text(entry.title, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                    HorizontalDivider()
                    Text(when (section) {
                        Section.Applications -> "Installed GUI programs"
                        Section.Launchers -> "Desktop shortcut files"
                        Section.Services -> "System and user background services"
                    }, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = {
                            if (section == Section.Services) refreshServices() else refreshApps()
                        },
                        enabled = !appBusy && !serviceBusy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (appBusy || serviceBusy) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("Rescan")
                    }
                    Spacer(Modifier.weight(1f))
                    Text("${apps.applications.size} applications", style = MaterialTheme.typography.labelSmall)
                    Text("${apps.launchers.size} launchers", style = MaterialTheme.typography.labelSmall)
                    Text("${services?.services?.size ?: "—"} services",
                        style = MaterialTheme.typography.labelSmall)
                }
            }
            Surface(Modifier.weight(1f).fillMaxHeight(), shape = RoundedCornerShape(16.dp),
                tonalElevation = 2.dp) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(section.title, fontWeight = FontWeight.SemiBold)
                    ScrollableTabRow(selectedTabIndex = filters.indexOf(filter).coerceAtLeast(0),
                        edgePadding = 0.dp) {
                        filters.forEach { label ->
                            Tab(selected = filter == label, onClick = { filter = label },
                                text = { Text(label, maxLines = 1) })
                        }
                    }
                    OutlinedTextField(search, onValueChange = { search = it },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text("Search ${section.title.lowercase()}") },
                        trailingIcon = { if (search.isNotEmpty()) TextButton(onClick = { search = "" }) {
                            Text("Clear")
                        } })
                    val shown = when (section) {
                        Section.Applications -> appItems.size
                        Section.Launchers -> launcherItems.size
                        Section.Services -> serviceItems.size
                    }
                    Text("Shown: ${shown}", style = MaterialTheme.typography.labelMedium)
                    HorizontalDivider()
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(3.dp),
                        modifier = Modifier.fillMaxSize()) {
                        when (section) {
                            Section.Applications -> items(appItems, key = { it.id }) { app ->
                                AppRow(app.representative, selectedApp == app.id,
                                    onClick = { selectedApp = app.id },
                                    onOpen = { launch(app.representative) })
                            }
                            Section.Launchers -> items(launcherItems, key = { it.listKey }) { rec ->
                                AppRow(rec, selectedLauncher == rec.listKey,
                                    onClick = { selectedLauncher = rec.listKey },
                                    onOpen = { launch(rec) })
                            }
                            Section.Services -> items(serviceItems, key = { it.key }) { service ->
                                ServiceRow(service, selectedService == service.key) {
                                    selectedService = service.key
                                }
                            }
                        }
                    }
                }
            }
            Surface(Modifier.width(350.dp).fillMaxHeight(), shape = RoundedCornerShape(16.dp),
                tonalElevation = 2.dp) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Details", style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold)
                    when (section) {
                        Section.Applications -> {
                            val app = apps.applications.firstOrNull { it.id == selectedApp }
                            if (app == null) Text("Select an application")
                            else {
                                val rec = app.representative
                                AppIcon(rec, 64)
                                Text(app.name, fontWeight = FontWeight.Bold)
                                Detail("Source", app.source)
                                Detail("Application identity", app.id)
                                Detail("Command", rec.exec.ifBlank { "—" })
                                Detail("Related launchers", app.launchers.size.toString())
                                if (app.launchers.isNotEmpty()) {
                                    SelectionContainer {
                                        Text(app.launchers.take(5).joinToString("\n") { it.path },
                                            style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                HorizontalDivider()
                                Button(onClick = { launch(rec) }, modifier = Modifier.fillMaxWidth()) {
                                    Text("Launch")
                                }
                                if (app.source != "User") {
                                    OutlinedButton(onClick = {
                                        removing = RemoveRequest(rec, launcherOnly = false)
                                    }, modifier = Modifier.fillMaxWidth()) { Text("Uninstall application") }
                                } else {
                                    Text("No package manager ownership is known. To remove a shortcut, " +
                                        "use the Launchers section.", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        Section.Launchers -> {
                            val rec = apps.launchers.firstOrNull { it.listKey == selectedLauncher }
                            if (rec == null) Text("Select a launcher")
                            else {
                                AppIcon(rec, 64)
                                Text(rec.name, fontWeight = FontWeight.Bold)
                                Detail("Source", rec.group)
                                Detail("Desktop file", rec.path)
                                Detail("Exec", rec.exec.ifBlank { "—" })
                                HorizontalDivider()
                                Button(onClick = { launch(rec) }, modifier = Modifier.fillMaxWidth()) {
                                    Text("Launch")
                                }
                                OutlinedButton(onClick = { openFolder(rec) },
                                    modifier = Modifier.fillMaxWidth()) { Text("Open folder") }
                                OutlinedButton(onClick = {
                                    try { LinuxBackend.openEntry(rec) }
                                    catch (e: Exception) { message = e.message ?: e.toString() }
                                }, modifier = Modifier.fillMaxWidth()) { Text("Open .desktop") }
                                if (LinuxBackend.isUserLauncher(rec)) {
                                    OutlinedButton(onClick = {
                                        removing = RemoveRequest(rec, launcherOnly = true)
                                    }, modifier = Modifier.fillMaxWidth()) { Text("Delete launcher only") }
                                }
                            }
                        }
                        Section.Services -> {
                            val unit = services?.services?.firstOrNull { it.key == selectedService }
                            if (unit == null) Text("Select a service")
                            else {
                                Text(unit.name, fontWeight = FontWeight.Bold)
                                Detail("Scope", unit.scope)
                                Detail("Activity", unit.active)
                                Detail("Runtime", unit.subState)
                                Detail("Startup", unit.startup)
                                Detail("Description", unit.description.ifBlank { "—" })
                                HorizontalDivider()
                                Text("Read-only. No service management actions are performed.",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            services?.warnings?.takeIf { it.isNotEmpty() }?.let {
                                HorizontalDivider()
                                Text(it.joinToString("\n"), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Text(status, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    removing?.let { request ->
        AlertDialog(onDismissRequest = { removing = null },
            title = { Text(if (request.launcherOnly) "Delete launcher" else "Uninstall application") },
            text = { SelectionContainer {
                Text(request.record.name + "\n\n" +
                    if (request.launcherOnly)
                        "Delete only the .desktop shortcut? Installed files are preserved."
                    else LinuxBackend.uninstallDescription(request.record))
            } },
            confirmButton = { TextButton(onClick = {
                removing = null
                scope.launch {
                    try {
                        val (ok, result) = withContext(Dispatchers.IO) {
                            if (request.launcherOnly) LinuxBackend.deleteUserLauncher(request.record)
                            else LinuxBackend.uninstall(request.record)
                        }
                        message = if (ok) "Success:\n$result" else "Failed:\n$result"
                        if (ok) refreshApps()
                    } catch (e: Exception) { message = e.message ?: e.toString() }
                }
            }) { Text(if (request.launcherOnly) "Delete" else "Uninstall") } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } })
    }
    message?.let { content ->
        AlertDialog(onDismissRequest = { message = null }, title = { Text("Application Manager") },
            text = { SelectionContainer { Text(content) } },
            confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } })
    }
}

@Composable
private fun ServiceRow(service: SystemService, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(
        if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        rowShape).clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (service.isRunning) "●" else "○",
            color = if (service.isRunning) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(service.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(service.description.ifBlank { service.subState }, maxLines = 1,
                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        }
        Text(service.scope, style = MaterialTheme.typography.labelSmall)
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
    val image by produceState<ImageBitmap?>(null, rec.icon, rec.entry, rec.name) {
        value = withContext(Dispatchers.IO) { LinuxIconResolver.resolve(rec) }
    }
    if (image != null) Image(image!!, contentDescription = null, modifier = Modifier.size(size.dp))
    else Box(Modifier.size(size.dp).background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center) { Text(rec.name.take(1).uppercase(), fontWeight = FontWeight.Bold) }
}

@Composable
private fun Detail(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer { Text(value, style = MaterialTheme.typography.bodySmall) }
    }
}
