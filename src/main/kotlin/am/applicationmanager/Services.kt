package am.applicationmanager

import java.util.Locale

data class SystemService(
    val name: String,
    val scope: String,
    val description: String,
    val active: String,
    val subState: String,
    val startup: String
) {
    val key: String get() = "$scope:$name"
    val isRunning: Boolean get() = active == "active"
}

data class ServiceCatalog(val services: List<SystemService>, val warnings: List<String>)

object ServiceScanner {
    private data class States(val active: String, val sub: String, val description: String)

    /** Enumerate units and startup states without modifying or starting services. */
    fun scan(): ServiceCatalog {
        val warnings = mutableListOf<String>()
        val result = mutableListOf<SystemService>()
        for ((scope, prefix) in listOf(
            "System" to listOf("systemctl"),
            "User" to listOf("systemctl", "--user")
        )) {
            val (fileStatus, fileOutput) = LinuxBackend.command(
                *(prefix + listOf("list-unit-files", "--type=service", "--all",
                    "--no-legend", "--no-pager", "--plain")).toTypedArray()
            )
            val (unitStatus, unitOutput) = LinuxBackend.command(
                *(prefix + listOf("list-units", "--type=service", "--all",
                    "--no-legend", "--no-pager", "--plain")).toTypedArray()
            )
            if (fileStatus != 0 && unitStatus != 0) {
                warnings += "$scope services are unavailable (systemctl or session bus)."
                continue
            }
            val runtime = HashMap<String, States>()
            if (unitStatus == 0) {
                unitOutput.lineSequence().forEach { line ->
                    val cleaned = line.trim().removePrefix("●").trim()
                    val parts = cleaned.split(Regex("\\s+"), limit = 5)
                    if (parts.size >= 4 && parts[0].endsWith(".service")) {
                        runtime[parts[0]] = States(
                            parts[2], parts[3], parts.getOrNull(4).orEmpty()
                        )
                    }
                }
            }
            val installed = linkedMapOf<String, String>()
            if (fileStatus == 0) {
                fileOutput.lineSequence().forEach { line ->
                    val parts = line.trim().split(Regex("\\s+"))
                    if (parts.size >= 2 && parts[0].endsWith(".service")) {
                        installed[parts[0]] = parts[1]
                    }
                }
            }
            (installed.keys + runtime.keys).distinct().forEach { name ->
                val current = runtime[name]
                result += SystemService(
                    name = name,
                    scope = scope,
                    description = current?.description.orEmpty(),
                    active = current?.active ?: "inactive",
                    subState = current?.sub ?: "not loaded",
                    startup = installed[name] ?: "unknown"
                )
            }
            if (fileStatus != 0 || unitStatus != 0) {
                warnings += "$scope: some service status data could not be retrieved."
            }
        }
        return ServiceCatalog(
            result.distinctBy(SystemService::key).sortedWith(
                compareBy<SystemService> { it.name.lowercase(Locale.ROOT) }.thenBy { it.scope }
            ),
            warnings
        )
    }
}
