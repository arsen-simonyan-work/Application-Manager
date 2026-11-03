package am.applicationmanager

import java.awt.Toolkit
import java.lang.reflect.Modifier

internal object AppMetadata {
    const val NAME = "Application Manager"
    const val LINUX_APP_ID = "am.applicationmanager.ApplicationManager"
    val VERSION: String = requireNotNull(AppMetadata::class.java.getResourceAsStream("/VERSION")) {
        "Missing VERSION resource"
    }.bufferedReader().use { it.readText().trim() }
}

internal fun configureLinuxWindowClass() {
    if (!System.getProperty("os.name").equals("Linux", ignoreCase = true)) return
    // AWT otherwise uses the main class as WM_CLASS, so GNOME cannot match the launcher.
    runCatching {
        val toolkit = Toolkit.getDefaultToolkit()
        if (toolkit.javaClass.name != "sun.awt.X11.XToolkit") return@runCatching
        toolkit.javaClass.getDeclaredField("awtAppClassName").apply {
            isAccessible = true
            set(if (Modifier.isStatic(modifiers)) null else toolkit, AppMetadata.LINUX_APP_ID)
        }
    }
}
