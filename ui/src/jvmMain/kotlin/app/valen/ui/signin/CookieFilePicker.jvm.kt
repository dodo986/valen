/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.signin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter
import kotlin.concurrent.thread

/**
 * External file chooser (`zenity` / `kdialog`) in its own process off the UI thread so GTK is
 * never loaded into Valen's JVM (see `ImagePicker.jvm.kt`). Falls back to Swing's `JFileChooser`.
 */
@Composable
actual fun rememberCookieFilePicker(onPicked: (String) -> Unit): () -> Unit {
    val latest = rememberUpdatedState(onPicked)
    val open = remember { AtomicBoolean(false) }
    return remember {
        {
            if (open.compareAndSet(false, true)) {
                thread(name = "valen-cookie-chooser", isDaemon = true) {
                    try {
                        val chosen = when (val external = chooseCookieFileExternally()) {
                            is CookieChoice.Picked -> external.file
                            CookieChoice.Cancelled -> null
                            CookieChoice.Unavailable -> chooseWithSwing()
                        }
                        if (chosen != null && chosen.isFile) {
                            val path = chosen.absolutePath
                            SwingUtilities.invokeLater { latest.value(path) }
                        }
                    } catch (failure: Throwable) {
                        System.err.println("valen: the cookie file chooser failed: ${failure.message}")
                    } finally {
                        open.set(false)
                    }
                }
            }
        }
    }
}

private sealed interface CookieChoice {
    data class Picked(val file: File) : CookieChoice
    data object Cancelled : CookieChoice
    data object Unavailable : CookieChoice
}

private fun chooseCookieFileExternally(path: String = System.getenv("PATH").orEmpty()): CookieChoice {
    val title = "Import cookies file"
    val commands = listOf(
        listOf("zenity", "--file-selection", "--title=$title", "--file-filter=Cookie files (*.txt, *.json) | *.txt *.json", "--file-filter=All files | *"),
        listOf("kdialog", "--title", title, "--getopenfilename", System.getProperty("user.home"), "Cookie files (*.txt *.json)|All files (*)"),
    )
    for (command in commands) {
        val program = onPath(command.first(), path) ?: continue
        val process = runCatching {
            ProcessBuilder(listOf(program.path) + command.drop(1))
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .also { builder ->
                    builder.environment().remove("LD_LIBRARY_PATH")
                    builder.environment().remove("LD_PRELOAD")
                }
                .start()
        }.getOrNull() ?: continue
        val output = process.inputStream.bufferedReader().readText().trim()
        if (!process.waitFor(10, TimeUnit.MINUTES)) {
            process.destroy()
            return CookieChoice.Cancelled
        }
        return when {
            process.exitValue() == 0 && output.isNotEmpty() -> CookieChoice.Picked(File(output.lines().first()))
            process.exitValue() == 1 -> CookieChoice.Cancelled
            else -> continue
        }
    }
    return CookieChoice.Unavailable
}

private fun onPath(program: String, path: String): File? =
    path.split(File.pathSeparator).filter { it.isNotBlank() }.map { File(it, program) }.firstOrNull { it.canExecute() }

private fun chooseWithSwing(): File? {
    var picked: File? = null
    SwingUtilities.invokeAndWait {
        val chooser = JFileChooser().apply {
            dialogTitle = "Import cookies file"
            fileFilter = FileNameExtensionFilter("Cookie files (*.txt, *.json)", "txt", "json")
            isAcceptAllFileFilterUsed = true
        }
        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) picked = chooser.selectedFile
    }
    return picked
}
