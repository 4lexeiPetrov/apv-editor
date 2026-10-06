package com.apv.editor.session

import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Persists [SessionSnapshot] + draft buffers to an OS-appropriate app data dir.
 * Writes are debounced and atomic (tmp file → rename). Buffer texts for dirty
 * tabs live in side files under `drafts/`; orphans are pruned on every write.
 */
class SessionStore(
    val rootDir: File = defaultRootDir(),
    private val delayMs: Long = 800,
) {
    private val sessionFile = File(rootDir, "session.json")
    private val draftsDir = File(rootDir, "drafts")

    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "session-store").also { it.isDaemon = true }
    }
    private val pending = AtomicReference<ScheduledFuture<*>?>(null)
    private val latest = AtomicReference<(() -> SessionWrite)?>(null)
    private val writeLock = Any()

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    fun load(): SessionSnapshot? {
        if (!sessionFile.isFile) return null
        return try {
            json.decodeFromString(SessionSnapshot.serializer(), sessionFile.readText())
        } catch (_: Throwable) {
            null
        }
    }

    fun readDraft(relativePath: String): String? = try {
        val f = File(rootDir, relativePath)
        if (f.isFile) f.readText() else null
    } catch (_: Throwable) {
        null
    }

    /** Last-modified time of a draft side file, or null when it does not exist. */
    fun draftModified(relativePath: String): Long? = try {
        val f = File(rootDir, relativePath)
        if (f.isFile) f.lastModified() else null
    } catch (_: Throwable) {
        null
    }

    /**
     * Debounced schedule. The most recent producer wins — by the time the
     * runnable fires (or [flush] runs), it computes a fresh [SessionWrite]
     * against current state.
     */
    fun schedule(producer: () -> SessionWrite) {
        latest.set(producer)
        val future = scheduler.schedule(
            ::runLatest,
            delayMs,
            TimeUnit.MILLISECONDS,
        )
        pending.getAndSet(future)?.cancel(false)
    }

    /** Run the most recently scheduled producer synchronously. Safe to call on UI thread. */
    fun flush() {
        pending.getAndSet(null)?.cancel(false)
        runLatest()
    }

    fun shutdown() {
        flush()
        scheduler.shutdownNow()
    }

    private fun runLatest() {
        val producer = latest.getAndSet(null) ?: return
        try {
            writeNow(producer())
        } catch (_: Throwable) {
            // best-effort persistence; do not crash the app
        }
    }

    private fun writeNow(write: SessionWrite) {
        synchronized(writeLock) {
            rootDir.mkdirs()
            draftsDir.mkdirs()

            val keep = mutableSetOf<String>()
            for ((relPath, text) in write.drafts) {
                val target = File(rootDir, relPath)
                target.parentFile?.mkdirs()
                atomicWriteText(target, text)
                keep += target.canonicalPath
            }
            for (relPath in write.keepDrafts) {
                keep += File(rootDir, relPath).canonicalPath
            }
            draftsDir.listFiles()?.forEach { f ->
                if (f.canonicalPath !in keep && !f.name.endsWith(".tmp")) {
                    f.delete()
                }
            }

            val text = json.encodeToString(SessionSnapshot.serializer(), write.snapshot)
            atomicWriteText(sessionFile, text)
        }
    }

    private fun atomicWriteText(target: File, text: String) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(text)
        try {
            Files.move(
                tmp.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: Throwable) {
            Files.move(
                tmp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    companion object {
        fun defaultRootDir(): File {
            val os = System.getProperty("os.name").orEmpty().lowercase()
            val home = System.getProperty("user.home")
            return when {
                os.contains("mac") -> File("$home/Library/Application Support/APV Editor")
                os.contains("win") -> {
                    val appdata = System.getenv("APPDATA") ?: "$home/AppData/Roaming"
                    File("$appdata/APV Editor")
                }
                else -> {
                    val xdg = System.getenv("XDG_DATA_HOME") ?: "$home/.local/share"
                    File("$xdg/apv-editor")
                }
            }
        }
    }
}
