package am.applicationmanager

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import java.awt.Frame
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.Timer

@Composable
internal fun FrameWindowScope.MaximizeOnFirstShow(state: WindowState) {
    if (!System.getProperty("os.name").equals("Linux", ignoreCase = true)) return
    DisposableEffect(window) {
        // GNOME can replace the pre-show placement with its first normal-size configure event.
        // Reapply it once after the window is mapped, then leave resizing to the user.
        val maximize = Timer(150) {
            if (window.isShowing) {
                state.placement = WindowPlacement.Maximized
                window.extendedState = window.extendedState or Frame.MAXIMIZED_BOTH
            }
        }.apply { isRepeats = false }
        val listener = object : WindowAdapter() {
            override fun windowOpened(event: WindowEvent) {
                maximize.start()
            }
        }
        window.addWindowListener(listener)
        if (window.isShowing) maximize.start()
        onDispose {
            maximize.stop()
            window.removeWindowListener(listener)
        }
    }
}
