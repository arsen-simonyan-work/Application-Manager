package am.applicationmanager

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.github.weisj.jsvg.parser.SVGLoader
import com.github.weisj.jsvg.view.ViewBox
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

/** Resolves freedesktop icon names; call on a background dispatcher, not the UI thread. */
internal object LinuxIconResolver {
    private const val pixels = 128
    private const val maxBytes = 8_000_000L
    private val extensions = listOf("png", "svg", "jpg", "jpeg", "gif")
    private val cache = ConcurrentHashMap<String, ImageBitmap>()
    private val missing = ConcurrentHashMap.newKeySet<String>()
    private val roots by lazy {
        val home = System.getProperty("user.home")
        val xdg = System.getenv("XDG_DATA_HOME")?.takeIf(String::isNotBlank)
            ?: home + "/.local/share"
        val shared = (System.getenv("XDG_DATA_DIRS")
            ?: "/usr/local/share:/usr/share").split(':').filter(String::isNotBlank)
        (listOf(home + "/.icons", home + "/.local/share/flatpak/exports/share/icons",
            "/var/lib/flatpak/exports/share/icons", "/var/lib/snapd/desktop/icons")
            + (listOf(xdg) + shared).flatMap { listOf(it + "/icons", it + "/pixmaps") })
            .distinct().map(::File).filter(File::isDirectory)
    }
    private val theme by lazy {
        val home = System.getProperty("user.home")
        val files = listOf(".config/gtk-4.0/settings.ini", ".config/gtk-3.0/settings.ini")
        val configured = files.firstNotNullOfOrNull { relative ->
            try {
                File(home, relative).takeIf(File::isFile)?.useLines { lines ->
                    lines.map(String::trim).firstOrNull { it.startsWith("gtk-icon-theme-name=") }
                        ?.substringAfter('=')?.trim()?.takeIf(String::isNotBlank)
                }
            } catch (_: Exception) { null }
        }
        (System.getenv("GTK_ICON_THEME")?.takeIf(String::isNotBlank)
            ?: configured ?: "").lowercase(Locale.ROOT)
    }
    private val indexed by lazy {
        val map = HashMap<String, MutableList<File>>()
        roots.forEach { root ->
            try {
                root.walkTopDown().maxDepth(9).onFail { _, _ -> }.forEach { file ->
                    if (file.isFile && file.extension.lowercase(Locale.ROOT) in extensions) {
                        map.getOrPut(file.nameWithoutExtension.lowercase(Locale.ROOT)) { ArrayList() }
                            .add(file)
                    }
                }
            } catch (_: Exception) { }
        }
        map
    }

    fun resolve(app: AppRecord): ImageBitmap? {
        val candidates = listOf(app.icon,
            app.entry.removeSuffix(".desktop").removeSuffix(".flatpakref").removeSuffix(".snap"),
            app.name).map(String::trim).filter(String::isNotBlank).distinct()
        if (candidates.isEmpty()) return null
        val key = candidates.joinToString("|")
        cache[key]?.let { return it }
        if (key in missing) return null
        val found = candidates.firstNotNullOfOrNull(::find)
        if (found != null) cache[key] = found else missing.add(key)
        return found
    }

    private fun find(name: String): ImageBitmap? {
        val file = File(name.trim().trim('"'))
        if (file.isAbsolute) return withExtensions(file).firstNotNullOfOrNull(::decode)
        val files = LinkedHashSet<File>()
        roots.forEach { root -> withExtensions(File(root, file.path)).forEach(files::add) }
        indexed[file.nameWithoutExtension.ifBlank { file.name }.lowercase(Locale.ROOT)]
            .orEmpty().forEach(files::add)
        return files.asSequence().sortedWith(compareBy<File>(
            { themeRank(it) }, { sizeRank(it) }, { it.absolutePath }))
            .firstNotNullOfOrNull(::decode)
    }

    private fun withExtensions(file: File): List<File> =
        if (file.extension.isNotBlank()) listOf(file)
        else listOf(file) + extensions.map { ext -> File(file.path + "." + ext) }

    private fun themeRank(file: File): Int {
        val path = file.absolutePath.lowercase(Locale.ROOT)
        return when {
            theme.isNotBlank() && ("/" + theme + "/") in path -> 0
            "/hicolor/" in path -> 1
            "/adwaita/" in path -> 2
            "/pixmaps/" in path -> 3
            else -> 4
        }
    }

    private fun sizeRank(file: File): Int {
        val path = file.path
        return when {
            file.extension.equals("svg", true) || "/scalable/" in path -> 0
            listOf("/128x128/", "/256x256/", "/512x512/").any { it in path } -> 1
            listOf("/64x64/", "/96x96/").any { it in path } -> 2
            "/48x48/" in path -> 3
            else -> 4
        }
    }

    private fun decode(file: File): ImageBitmap? {
        return try {
            if (!file.isFile || file.length() !in 1..maxBytes) return null
            val bitmap = if (file.extension.equals("svg", true)) {
                val svg = SVGLoader().load(file.toURI().toURL()) ?: return null
                BufferedImage(pixels, pixels, BufferedImage.TYPE_INT_ARGB).also { target ->
                    val graphics = target.createGraphics()
                    try {
                        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                            RenderingHints.VALUE_ANTIALIAS_ON)
                        svg.render(null, graphics, ViewBox(0f, 0f, pixels.toFloat(), pixels.toFloat()))
                    } finally { graphics.dispose() }
                }
            } else {
                val original = ImageIO.read(file) ?: return null
                BufferedImage(pixels, pixels, BufferedImage.TYPE_INT_ARGB).also { target ->
                    val graphics = target.createGraphics()
                    try {
                        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                            RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                        val ratio = minOf(pixels.toDouble() / original.width,
                            pixels.toDouble() / original.height)
                        val width = (original.width * ratio).toInt().coerceAtLeast(1)
                        val height = (original.height * ratio).toInt().coerceAtLeast(1)
                        graphics.drawImage(original, (pixels - width) / 2, (pixels - height) / 2,
                            width, height, null)
                    } finally { graphics.dispose() }
                }
            }
            bitmap.toComposeImageBitmap()
        } catch (_: Exception) { null }
    }
}
