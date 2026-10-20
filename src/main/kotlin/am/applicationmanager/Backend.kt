package am.applicationmanager

import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

data class AppRecord(
    val name: String, val group: String, val entry: String,
    val exec: String, val path: String, val icon: String = "",
    val reason: String = ""
) {
    val identity: String get() = if (path.isNotBlank()) "file:" + File(path).absolutePath
        else "$group:$entry:$exec"
}

data class ScanResult(val all: List<AppRecord>, val unique: List<AppRecord>,
                      val duplicates: List<AppRecord>) {
    fun forTab(tab: String) = when(tab) {
        "All" -> all
        "Unique" -> unique
        "Duplicates" -> duplicates
        else -> all.filter { it.group == tab }
    }
}
val tabs = listOf("All", "Unique", "Duplicates", "System", "User", "Snap", "Flatpak", "Wine")

object LinuxBackend {
    private val home = System.getProperty("user.home")
    private val userDesktop = "$home/.local/share/applications"
    private val locations = listOf(
        "/usr/share/applications" to "System",
        "/usr/local/share/applications" to "System",
        userDesktop to "User",
        "/var/lib/snapd/desktop/applications" to "Snap",
        "/var/lib/flatpak/exports/share/applications" to "Flatpak",
        "$home/.local/share/flatpak/exports/share/applications" to "Flatpak"
    )
    private fun command(vararg args: String): Pair<Int,String> = try {
        val p=ProcessBuilder(*args).redirectErrorStream(true).start()
        if (!p.waitFor(12, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            -1 to "Timed out"
        } else p.exitValue() to p.inputStream.bufferedReader().readText()
    } catch (e:Exception) { -1 to (e.message ?: "Failed") }
    private fun parse(file:File): Map<String,String> {
        var section=""
        val result=mutableMapOf<String,String>()
        file.forEachLine(Charsets.UTF_8) { raw ->
            val s=raw.trim()
            if (s.startsWith("[") && s.endsWith("]")) section=s.substring(1,s.length-1)
            else if (section=="Desktop Entry" && !s.startsWith("#")) {
                val sep=s.indexOf('=')
                if(sep>0) result[s.substring(0,sep).trim()]=s.substring(sep+1).trim()
            }
        }
        return result
    }
    private fun localName(properties:Map<String,String>, fallback:String):String {
        val locale=(System.getenv("LC_ALL")?.takeIf { it.isNotBlank() }
            ?: System.getenv("LC_MESSAGES")?.takeIf { it.isNotBlank() }
            ?: System.getenv("LANG") ?: Locale.getDefault().toString()).substringBefore('.')
        return listOf(locale,locale.substringBefore('_')).firstNotNullOfOrNull {
            properties["Name[$it]"]?.takeIf(String::isNotBlank)
        } ?: properties["Name"]?.takeIf(String::isNotBlank) ?: fallback
    }
    private fun desktop():List<AppRecord> = locations.flatMap { (folder, origin) ->
        File(folder).listFiles { f -> f.isFile && f.extension=="desktop" }.orEmpty()
            .mapNotNull { file ->
                try {
                    val m=parse(file)
                    if (m["Hidden"].equals("true",true) || m["NoDisplay"].equals("true",true)
                        || m["Type"]?.let { it!="Application" }==true) null
                    else {
                        val group=when {
                            file.path.contains("/applications/wine/") -> "Wine"
                            origin=="System" && file.name.startsWith("snap-") -> "Snap"
                            else -> origin
                        }
                        AppRecord(localName(m,file.nameWithoutExtension),group,file.name,
                            m["Exec"].orEmpty(),file.absolutePath,m["Icon"].orEmpty())
                    }
                } catch (_:Exception) { null }
            }
    }
    private fun flatpaks():List<AppRecord> {
        // The name column is user-facing; the application column is the stable ID.
        val (status,out)=command("flatpak","list","--app","--columns=application,name")
        if(status!=0) return emptyList()
        return out.lines().mapNotNull { line ->
            val columns=line.trim().split('\t')
            val id=columns.firstOrNull()?.trim().orEmpty()
            if(id.isBlank()) null else AppRecord(columns.getOrNull(1)?.trim()?.ifBlank { id } ?: id,
                "Flatpak","$id.flatpakref","flatpak run $id","","")
        }
    }
    private fun snaps():List<AppRecord> {
        val (status,out)=command("snap","list")
        if(status!=0) return emptyList()
        return out.lines().drop(1).mapNotNull { line ->
            val id=line.trim().split(Regex("\\s+")).firstOrNull().orEmpty()
            if (id.isBlank()) null else AppRecord(id,"Snap","$id.snap","snap run $id","")
        }
    }
    fun scan():ScanResult {
        // Wine Program Files directories are NOT applications. Only actual desktop launchers are indexed.
        val apps=(desktop()+flatpaks()+snaps()).distinctBy { it.identity }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        val unique=linkedMapOf<String,AppRecord>()
        val duplicate=mutableListOf<AppRecord>()
        apps.forEach { app ->
            val key=app.name.trim().lowercase(Locale.ROOT)
            val prior=unique[key]
            if(prior==null) unique[key]=app
            else duplicate.add(app.copy(reason="Also listed as ${prior.group}"))
        }
        return ScanResult(apps,unique.values.toList(),duplicate)
    }
    private fun tokens(input:String):List<String> {
        val out=mutableListOf<String>()
        val current=StringBuilder()
        var quoted=false
        var escape=false
        for(ch in input) {
            when {
                escape -> {current.append(ch);escape=false}
                ch=='\\' -> escape=true
                ch=='"' -> quoted=!quoted
                ch.isWhitespace() && !quoted -> {
                    if(current.isNotEmpty()) {out.add(current.toString());current.clear()}
                }
                else -> current.append(ch)
            }
        }
        if(current.isNotEmpty()) out.add(current.toString())
        return out.filterNot { it.matches(Regex("%[a-zA-Z]")) }
            .map { it.replace(Regex("%[a-zA-Z]"),"") }.filter(String::isNotBlank)
    }
    fun launch(app:AppRecord) {
        val argv=when {
            app.group=="Flatpak" -> listOf("flatpak","run",app.entry.removeSuffix(".flatpakref"))
            app.group=="Snap" -> listOf("snap","run",app.entry.removeSuffix(".snap"))
            app.exec.isNotBlank() -> tokens(app.exec)
            else -> emptyList()
        }
        require(argv.isNotEmpty()) { "No launch command" }
        ProcessBuilder(argv).start()
    }
    fun openEntry(app:AppRecord) {
        require(app.path.isNotBlank()) { "No desktop file" }
        ProcessBuilder("xdg-open",app.path).start()
    }
    fun openFolder(app:AppRecord) {
        require(app.path.isNotBlank()) { "No desktop file" }
        ProcessBuilder("xdg-open",File(app.path).parent).start()
    }
    fun uninstallDescription(app:AppRecord):String = when(app.group) {
        "Flatpak" -> "Uninstall Flatpak ${app.entry.removeSuffix(".flatpakref")}?"
        "Snap" -> "Remove Snap ${app.entry.removeSuffix(".snap")}?"
        "Wine" -> "Open Wine uninstaller?"
        "User" -> "Delete this user launcher only? Application files will be preserved."
        else -> "System packages must be removed with the system package manager."
    }
    fun uninstall(app:AppRecord):Pair<Boolean,String> = try {
        when(app.group) {
            "User" -> {
                val file=File(app.path).canonicalFile
                val base=File(userDesktop).canonicalFile
                require(file.toPath().startsWith(base.toPath()) && file.extension=="desktop")
                val done=file.delete()
                done to if(done) "Launcher removed" else "Failed to remove launcher"
            }
            "Flatpak" -> {
                val (code,out)=command("flatpak","uninstall","--noninteractive",app.entry.removeSuffix(".flatpakref"))
                (code==0) to out
            }
            "Snap" -> {
                val (code,out)=command("snap","remove",app.entry.removeSuffix(".snap"))
                (code==0) to out
            }
            "Wine" -> {
                ProcessBuilder("wine","uninstaller").start()
                true to "Wine uninstaller opened"
            }
            else -> false to "Use your system package manager for system applications."
        }
    } catch(e:Exception) {false to (e.message ?: e.toString())}
}
