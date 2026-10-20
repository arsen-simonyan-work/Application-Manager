package am.applicationmanager

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun main() = application {
    Window(onCloseRequest=::exitApplication,title="Application Manager") {
        MaterialTheme(colorScheme=darkColorScheme()) { ManagerScreen() }
    }
}

@Composable
private fun ManagerScreen() {
    val scope=rememberCoroutineScope()
    var result by remember { mutableStateOf(ScanResult(emptyList(),emptyList(),emptyList())) }
    var tab by remember { mutableStateOf("All") }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<AppRecord?>(null) }
    var loading by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<AppRecord?>(null) }
    fun rescan() {
        scope.launch {
            loading=true
            try {
                result=withContext(Dispatchers.IO) {LinuxBackend.scan()}
                selected=selected?.let { old -> result.all.find { it.identity==old.identity } }
            } catch(e:Exception) {notice=e.message ?: e.toString()}
            finally {loading=false}
        }
    }
    LaunchedEffect(Unit) {rescan()}
    pending?.let { app ->
        AlertDialog(
            onDismissRequest={pending=null},
            title={Text("Confirm removal")},
            text={Text(LinuxBackend.uninstallDescription(app))},
            confirmButton={TextButton(onClick={
                pending=null
                scope.launch {
                    val (success,message)=withContext(Dispatchers.IO) {LinuxBackend.uninstall(app)}
                    notice=message
                    if(success) rescan()
                }
            }) {Text("Remove")}},
            dismissButton={TextButton(onClick={pending=null}) {Text("Cancel")}}
        )
    }
    Column(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            Text("Application Manager",style=MaterialTheme.typography.headlineSmall)
            Button(onClick={rescan},enabled=!loading) {Text(if(loading) "Scanning…" else "Rescan")}
        }
        Row(Modifier.fillMaxSize(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(3f).fillMaxHeight()) {
                ScrollableTabRow(selectedTabIndex=tabs.indexOf(tab).coerceAtLeast(0),edgePadding=0.dp) {
                    tabs.forEach { name ->
                        Tab(selected=tab==name,onClick={tab=name},text={Text(name)})
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(query,{query=it},label={Text("Search applications")},
                    modifier=Modifier.fillMaxWidth(),singleLine=true)
                val visible=result.forTab(tab).filter { app ->
                    query.isBlank() || listOf(app.name,app.group,app.exec,app.path).any {
                        it.contains(query,ignoreCase=true)
                    }
                }
                Text("Shown: ${visible.size}",style=MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(8.dp))
                LazyColumn(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    // Every record has a stable origin identity, including duplicated Wine names.
                    items(items=visible,key={it.identity}) { app ->
                        Surface(onClick={selected=app},modifier=Modifier.fillMaxWidth(),
                            color=if(selected?.identity==app.identity)
                                MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant) {
                            Row(Modifier.padding(12.dp),horizontalArrangement=Arrangement.SpaceBetween) {
                                Column(Modifier.weight(1f)) {
                                    Text(app.name,style=MaterialTheme.typography.titleSmall)
                                    Text(app.exec,style=MaterialTheme.typography.bodySmall,maxLines=1)
                                }
                                Text(app.group,style=MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
            VerticalDivider()
            Column(Modifier.weight(1f).fillMaxHeight(),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Text("Details",style=MaterialTheme.typography.titleLarge)
                val app=selected
                if(app==null) Text("Select an application on the left")
                else {
                    Text(app.name,style=MaterialTheme.typography.titleMedium)
                    Text("Group: ${app.group}")
                    Text("Command: ${app.exec}")
                    Text("Entry: ${app.entry}")
                    Text("Path: ${app.path}")
                    if(app.reason.isNotBlank()) Text(app.reason)
                    Button(onClick={
                        try {LinuxBackend.launch(app)} catch(e:Exception) {
                            notice=e.message ?: "Launch failed"
                        }
                    }) {Text("Launch")}
                    if(app.path.isNotBlank()) {
                        OutlinedButton(onClick={
                            try {LinuxBackend.openFolder(app)} catch(e:Exception) {
                                notice=e.message ?: "Failed"
                            }
                        }) {Text("Open folder")}
                    }
                    OutlinedButton(onClick={pending=app}) {Text("Uninstall / Remove")}
                }
                Spacer(Modifier.weight(1f))
                Text("Found ${result.all.size} entries • ${result.unique.size} unique • ${result.duplicates.size} duplicates",
                    style=MaterialTheme.typography.labelSmall)
                if(notice.isNotBlank()) {
                    Text(notice,style=MaterialTheme.typography.bodySmall)
                    TextButton(onClick={notice=""}) {Text("Dismiss")}
                }
            }
        }
    }
}