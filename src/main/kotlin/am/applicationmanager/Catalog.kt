package am.applicationmanager

import java.io.File
import java.util.Locale

/** A launcher's .desktop path is different from an installed GUI application identity. */
data class InstalledApplication(
    val id: String,
    val name: String,
    val source: String,
    val representative: AppRecord,
    val launchers: List<AppRecord>
)

data class ApplicationCatalog(
    val applications: List<InstalledApplication>,
    val launchers: List<AppRecord>,
    val duplicateLaunchers: List<AppRecord>
)

object CatalogScanner {
    private val appIdPattern = Regex("[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]+){2,}")
    private val genericRunners = setOf("env", "sh", "bash", "python", "python3", "wine", "java", "electron", "gio", "gtk-launch", "xdg-open")

    private fun keyFor(record: AppRecord): String {
        val args = LinuxBackend.tokenize(record.exec)
        val runFlatpak = args.indexOfLast { it == "flatpak" }
        if (runFlatpak >= 0 && args.getOrNull(runFlatpak + 1) == "run") {
            val id = args.drop(runFlatpak + 2).firstOrNull { appIdPattern.matches(it) }
            if (id != null) return "flatpak:$id"
        }
        val snapIndex = args.indexOfLast { it == "snap" }
        if (snapIndex >= 0 && args.getOrNull(snapIndex + 1) == "run") {
            val snap = args.drop(snapIndex + 2).firstOrNull { !it.startsWith("-") }
            if (!snap.isNullOrBlank()) return "snap:${snap.substringBefore('.')}"
        }
        if (record.group == "Flatpak") {
            return "flatpak:${record.entry.removeSuffix(".flatpakref").removeSuffix(".desktop")}"
        }
        if (record.group == "Snap") {
            return "snap:${record.entry.removeSuffix(".snap").removeSuffix(".desktop").substringBefore('_')}"
        }
        if (record.group == "Wine") return "wine:${record.path}"
        val executable = args.firstOrNull().orEmpty()
        val runner = File(executable).name.lowercase(Locale.ROOT)
        return if (runner.isNotBlank() && runner !in genericRunners) {
            "native:$runner"
        } else {
            "desktop:${record.path.ifBlank { record.entry }}"
        }
    }

    fun from(scan: ScanResult): ApplicationCatalog {
        // Launcher records always correspond to actual .desktop files, not synthetic
        // package-manager rows. Keep duplicate shortcuts visible in this section.
        val launchers = scan.all.filter { it.path.isNotBlank() }
            .distinctBy { it.path }
        val launcherKeys = launchers.map(::keyFor).toHashSet()
        val candidates = scan.all.filter { record ->
            record.path.isNotBlank() || record.group == "Flatpak" ||
                (record.group == "Snap" && keyFor(record) in launcherKeys)
        }
        val applications = candidates.groupBy(::keyFor).map { (id, entries) ->
            val ordered = entries.sortedWith(
                compareByDescending<AppRecord> { it.path.isNotBlank() }
                    .thenByDescending { it.icon.isNotBlank() }
                    .thenBy { it.name.lowercase(Locale.ROOT) }
            )
            val main = ordered.first()
            val type = when {
                id.startsWith("flatpak:") -> "Flatpak"
                id.startsWith("snap:") -> "Snap"
                else -> main.group
            }
            InstalledApplication(id, main.name, type, main, ordered.filter { it.path.isNotBlank() })
        }.sortedWith(compareBy<InstalledApplication> { it.name.lowercase(Locale.ROOT) })

        val seen = HashMap<String, AppRecord>()
        val duplicates = launchers.filter { shortcut ->
            val key = shortcut.name.lowercase(Locale.ROOT) + "|" + shortcut.exec.lowercase(Locale.ROOT)
            seen.putIfAbsent(key, shortcut) != null
        }
        return ApplicationCatalog(applications, launchers, duplicates)
    }
}
