package am.applicationmanager

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.TimeUnit

private val home = System.getProperty("user.home")
private val userDesktop = "$home/.local/share/applications"
private val flatpakUser = "$home/.local/share/flatpak/exports/share/applications"
private val groups = listOf("System", "User", "Snap", "Flatpak", "Wine", "Other")
val allTabs = listOf("All", "Unique", "Duplicates") + groups

data class AppRecord(
    val name: String,
    val group: String,
    val entry: String,
    val exec: String,
    val path: String,
    val icon: String = "",
    val reason: String = ""
)

data class ScanResult(val all: List<AppRecord>, val unique: List<AppRecord>, val duplicates: List<AppRecord>) {
    fun forTab(tab: String): List<AppRecord> = when (tab) {
        "All" -> all
        "Unique" -> unique
        "Duplicates" -> duplicates
        else -> all.filter { it.group == tab }
    }
}

/** No shell interpolation: every external command is a separate argv element. */
object LinuxBackend {
    private val locations = listOf(
        "/usr/share/applications" to "System",
        "/usr/local/share/applications" to "System",
        userDesktop to "User",
        "/var/lib/snapd/desktop/applications" to "Snap",
        "/var/lib/flatpak/exports/share/applications" to "Flatpak",
        flatpakUser to "Flatpak"
    )
    private val priority = mapOf("System" to 3, "Flatpak" to 3, "Snap" to 3, "User" to 2, "Wine" to 1, "Other" to 0)

    fun command(vararg args: String, timeoutSeconds: Long = 25): Pair<Int, String> = try {
        val process = ProcessBuilder(*args).redirectErrorStream(true).start()
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            -1 to "Command timed out"
        } else process.exitValue() to process.inputStream.bufferedReader().readText()
    } catch (e: Exception) { -1 to (e.message ?: e.toString()) }

    fun start(vararg args: String) {
        ProcessBuilder(*args).redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
    }

    private fun bool(value: String?) = value.equals("true", true) || value.equals("1") || value.equals("yes", true)

    private fun parseDesktop(file: File): Map<String, String> {
        var section = ""
        val map = mutableMapOf<String, String>()
        file.forEachLine(Charsets.UTF_8) { original ->
            val line = original.trim()
            if (line.startsWith("[") && line.endsWith("]")) section = line.removeSurrounding("[", "]")
            else if (section == "Desktop Entry" && !line.startsWith("#")) {
                val index = line.indexOf('=')
                if (index > 0) map[line.substring(0, index).trim()] = line.substring(index + 1).trim()
            }
        }
        return map
    }

    private fun localized(fields: Map<String, String>, key: String): String {
        val userLang = System.getenv("LANGUAGE")?.substringBefore(':')
            ?: System.getenv("LC_ALL")?.takeIf { it.isNotBlank() }
            ?: System.getenv("LANG") ?: Locale.getDefault().toString()
        val lang = userLang.substringBefore('.')
        return listOf(lang, lang.substringBefore('_'), "en_US", "en")
            .firstNotNullOfOrNull { fields["$key[$it]"]?.takeIf(String::isNotBlank) }
            ?: fields[key].orEmpty()
    }

    /** Quote-aware Linux desktop Exec tokenizer. Removes % field codes and supports quoted paths. */
    fun tokenize(input: String): List<String> {
        val parts = mutableListOf<String>()
        val token = StringBuilder()
        var quote = '\u0000'
        var escape = false
        var active = false
        for (ch in input) {
            if (escape) { token.append(ch); escape = false; active = true; continue }
            if (ch == '\\' && quote != '\'') { escape = true; active = true; continue }
            if ((ch == '"' || ch == '\'') && (quote == '\u0000' || quote == ch)) {
                quote = if (quote == '\u0000') ch else '\u0000'; active = true; continue
            }
            if (ch.isWhitespace() && quote == '\u0000') {
                if (active) { parts.add(token.toString()); token.clear(); active = false }
            } else { token.append(ch); active = true }
        }
        if (active) parts.add(token.toString())
        return parts.filter { it.isNotBlank() && !it.matches(Regex("%[fFuUdDnNickvm]")) }
            .map { it.replace("%%", "%").replace(Regex("%[fFuUdDnNickvm]"), "") }.filter(String::isNotBlank)
    }

    private fun launchTokens(exec: String): List<String> {
        val tokens = tokenize(exec)
        if (tokens.firstOrNull() != "env") return tokens
        val firstCommand = tokens.drop(1).indexOfFirst { !it.contains('=') && !it.startsWith("-") }
        return if (firstCommand >= 0) tokens.drop(firstCommand + 1) else emptyList()
    }

    private fun desktopEntries(): List<AppRecord> = locations.flatMap { (folder, group) ->
        File(folder).listFiles { f -> f.isFile && f.extension == "desktop" }?.toList().orEmpty().mapNotNull { file ->
            try {
                val m = parseDesktop(file)
                if (m["Type"]?.let { it != "Application" } == true || bool(m["NoDisplay"]) || bool(m["Hidden"])) null
                else {
                    val exec = m["Exec"].orEmpty()
                    val executable = launchTokens(exec).firstOrNull()
                    if (executable?.startsWith('/') == true && !File(executable).exists()) null
                    else AppRecord(localized(m, "Name").ifBlank { file.nameWithoutExtension },
                        if (file.path.startsWith("$userDesktop/wine/")) "Wine" else group,
                        file.name, exec, file.absolutePath, m["Icon"].orEmpty())
                }
            } catch (_: Exception) { null }
        }
    }

    private fun snapEntries(): List<AppRecord> {
        val (code, output) = command("snap", "list")
        if (code != 0) return emptyList()
        return output.lines().drop(1).mapNotNull { line ->
            val name = line.trim().split(Regex("\\s+")).firstOrNull()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            AppRecord(name, "Snap", "$name.snap", "snap run $name", "")
        }
    }

    private fun flatpakEntries(): List<AppRecord> {
        val (code, output) = command("flatpak", "list", "--app", "--columns=application")
        if (code != 0) return emptyList()
        return output.lines().mapNotNull { id ->
            id.trim().takeIf(String::isNotBlank)?.let { AppRecord(it, "Flatpak", "$it.flatpakref", "flatpak run $it", "") }
        }
    }

    private fun wineEntries(): List<AppRecord> {
        val prefix = System.getenv("WINEPREFIX") ?: "$home/.wine"
        return listOf("Program Files", "Program Files (x86)")
            .flatMap { File("$prefix/drive_c/$it").listFiles()?.filter(File::isDirectory).orEmpty() }
            .map { AppRecord(it.name, "Wine", "${it.name}.wine", "", "") }
    }

    fun scan(onProgress: (Int, Int) -> Unit = { _, _ -> }): ScanResult {
        val records = (desktopEntries() + snapEntries() + flatpakEntries() + wineEntries())
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        val unique = linkedMapOf<Pair<String, String>, AppRecord>()
        val duplicates = mutableListOf<AppRecord>()
        records.forEachIndexed { index, rec ->
            val key = rec.name.trim().lowercase() to rec.exec.trim().lowercase()
            val previous = unique[key]
            if (previous == null) unique[key] = rec
            else if ((priority[rec.group] ?: 0) > (priority[previous.group] ?: 0)) {
                duplicates.add(previous.copy(reason = "Replaced by ${rec.group}"))
                unique[key] = rec
            } else duplicates.add(rec.copy(reason = "Duplicate of ${previous.group}"))
            onProgress(index + 1, records.size)
        }
        return ScanResult(records, unique.values.sortedBy { it.name.lowercase() }, duplicates)
    }

    fun launch(rec: AppRecord) {
        val args = when {
            rec.exec.isNotBlank() -> launchTokens(rec.exec)
            rec.group == "Snap" -> listOf("snap", "run", rec.name)
            rec.group == "Flatpak" -> listOf("flatpak", "run", rec.name)
            rec.group == "Wine" -> listOf("wine", "uninstaller")
            else -> emptyList()
        }
        require(args.isNotEmpty()) { "No launch command available" }
        start(*args.toTypedArray())
    }

    fun openFolder(rec: AppRecord) {
        require(rec.path.isNotBlank()) { "No desktop file associated with this entry" }
        val file = File(rec.path)
        val candidates = listOf(listOf("nautilus", "--select", file.path),
            listOf("dolphin", "--select", file.path), listOf("nemo", "--browser", "--select", file.path),
            listOf("thunar", "--select", file.path), listOf("xdg-open", file.parent))
        for (args in candidates) try { start(*args.toTypedArray()); return } catch (_: Exception) { }
        error("No file manager found")
    }

    fun openEntry(rec: AppRecord) {
        require(rec.path.isNotBlank()) { "No .desktop file associated with this entry" }
        start("xdg-open", rec.path)
    }

    private fun owningPackage(path: String): String? {
        if (path.isBlank()) return null
        if (available("dpkg")) {
            val (status, output) = command("dpkg", "-S", path)
            if (status == 0) return output.lineSequence().firstOrNull()?.substringBefore(':')?.trim()?.takeIf(String::isNotBlank)
        }
        if (available("rpm")) {
            val (status, output) = command("rpm", "-qf", "--queryformat", "%{NAME}", path)
            if (status == 0) return output.trim().takeIf(String::isNotBlank)
        }
        if (available("pacman")) {
            val (status, output) = command("pacman", "-Qo", path)
            if (status == 0) return output.substringAfter(" is owned by ", "").substringBefore(' ').takeIf(String::isNotBlank)
        }
        return null
    }

    private fun available(binary: String): Boolean = (System.getenv("PATH") ?: "")
        .split(File.pathSeparator).any { File(it, binary).canExecute() }

    private fun nativeId(rec: AppRecord): String {
        val tokens = launchTokens(rec.exec)
        if (rec.group == "Snap") {
            val snapIdx = tokens.indexOf("snap")
            if (snapIdx >= 0 && tokens.getOrNull(snapIdx + 1) == "run") return tokens.getOrNull(snapIdx + 2)?.substringBefore('.') ?: rec.name
            return rec.entry.removeSuffix(".desktop").removeSuffix(".snap").substringBefore('_')
        }
        return rec.entry.removeSuffix(".desktop").removeSuffix(".flatpakref").ifBlank { rec.name }
    }

    fun uninstallDescription(rec: AppRecord): String = when (rec.group) {
        "Snap" -> "Remove Snap package: ${nativeId(rec)}"
        "Flatpak" -> "Remove Flatpak application: ${nativeId(rec)}"
        "Wine" -> "Open Wine uninstaller (select the application there)"
        "User" -> "Remove user launcher: ${rec.path}\n(Installed application files are not deleted automatically.)"
        else -> {
            val exec = launchTokens(rec.exec).firstOrNull { it.startsWith('/') }
            val pkg = owningPackage(exec ?: rec.path)
            if (pkg == null) "Package ownership cannot be determined; no files will be deleted."
            else "Remove system package: $pkg"
        }
    }

    fun uninstall(rec: AppRecord): Pair<Boolean, String> {
        if (rec.group == "Wine") {
            start("wine", "uninstaller")
            return true to "Wine uninstaller opened"
        }
        if (rec.group == "User") {
            val target = File(rec.path)
            val allowed = Path.of(userDesktop).toAbsolutePath().normalize()
            require(target.toPath().toAbsolutePath().normalize().startsWith(allowed)) { "Unsafe user launcher path" }
            require(target.extension == "desktop") { "Only .desktop launchers can be removed" }
            Files.delete(target.toPath())
            return true to "User launcher removed (application files preserved)"
        }
        val args = when (rec.group) {
            "Snap" -> listOf("snap", "remove", nativeId(rec))
            "Flatpak" -> listOf("flatpak", "uninstall", "-y", nativeId(rec))
            else -> {
                val exec = launchTokens(rec.exec).firstOrNull { it.startsWith('/') }
                val pkg = owningPackage(exec ?: rec.path) ?: return false to "Owning system package not found"
                when {
                    available("apt") -> listOf("pkexec", "apt", "remove", "-y", pkg)
                    available("dnf") -> listOf("pkexec", "dnf", "remove", "-y", pkg)
                    available("pacman") -> listOf("pkexec", "pacman", "-Rns", "--noconfirm", pkg)
                    available("zypper") -> listOf("pkexec", "zypper", "-n", "rm", pkg)
                    else -> return false to "Unsupported package manager"
                }
            }
        }
        val (code, output) = command(*args.toTypedArray(), timeoutSeconds = 600)
        return (code == 0) to output.ifBlank { if (code == 0) "Completed" else "Command failed ($code)" }
    }
}
