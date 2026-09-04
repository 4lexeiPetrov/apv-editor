package com.apv.editor.platform

import java.awt.Desktop
import java.io.File

object FileOpenEvents {
    private val pending = mutableListOf<File>()
    private var listener: ((File) -> Unit)? = null
    private val logFile: File? by lazy {
        runCatching {
            val dir = logDir()
            if (dir.isDirectory || dir.mkdirs()) File(dir, "APV Editor.log") else null
        }.getOrNull()
    }

    private fun logDir(): File {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val home = System.getProperty("user.home")
        return when {
            os.contains("mac") -> File("$home/Library/Logs")
            os.contains("win") -> {
                val local = System.getenv("LOCALAPPDATA") ?: "$home/AppData/Local"
                File("$local/APV Editor/Logs")
            }
            else -> {
                val xdg = System.getenv("XDG_STATE_HOME") ?: "$home/.local/state"
                File("$xdg/apv-editor")
            }
        }
    }

    fun install(args: Array<String> = emptyArray()) {
        log("install() args=${args.toList()}")
        args.forEach { path ->
            val f = File(path)
            if (f.isFile) {
                log("queue from args: ${f.absolutePath}")
                enqueue(f)
            } else {
                log("ignore non-file arg: $path")
            }
        }
        installDesktopHandler()
    }

    private fun installDesktopHandler() {
        if (!Desktop.isDesktopSupported()) {
            log("Desktop API not supported on this platform")
            return
        }
        val desktop = try {
            Desktop.getDesktop()
        } catch (t: Throwable) {
            log("Desktop.getDesktop() failed: $t")
            return
        }
        try {
            desktop.setOpenFileHandler { event ->
                val files = event?.files.orEmpty()
                log("OpenFilesEvent: ${files.map { it.absolutePath }}")
                files.forEach { enqueue(it) }
            }
            log("OpenFileHandler installed")
        } catch (t: Throwable) {
            log("setOpenFileHandler failed: $t")
        }
    }

    @Synchronized
    private fun enqueue(file: File) {
        val l = listener
        if (l == null) {
            pending += file
            log("buffered (no listener yet): ${file.absolutePath}")
        } else {
            log("dispatch to listener: ${file.absolutePath}")
            l(file)
        }
    }

    @Synchronized
    fun setListener(l: (File) -> Unit) {
        listener = l
        val drained = pending.toList()
        pending.clear()
        if (drained.isNotEmpty()) log("draining ${drained.size} pending file(s)")
        drained.forEach(l)
    }

    @Synchronized
    fun clearListener() {
        listener = null
    }

    private fun log(msg: String) {
        val line = "[APV] $msg"
        System.err.println(line)
        runCatching {
            logFile?.appendText("${System.currentTimeMillis()} $line\n")
        }
    }
}
