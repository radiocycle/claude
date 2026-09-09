package dev.radiocycle.llmhub.tools

import android.content.Context
import dev.radiocycle.llmhub.data.repo.SettingsRepository
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Resolves the agent's working directory and the paths the file tools operate on.
 *
 * The workspace is a real filesystem path. When blank it falls back to an app-private directory,
 * which is always readable and writable without any runtime permission. A rooted device or a
 * granted all-files permission lets it point anywhere.
 */
class WorkspaceManager(
    private val context: Context,
    private val settings: SettingsRepository,
) {
    /** The workspace directory, created on demand. */
    fun root(): File {
        val configured = settings.current.tools.workspacePath.trim()
        val dir = if (configured.isNotEmpty()) File(configured) else defaultRoot()
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun defaultRoot(): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "workspace")

    /** A shared-storage path, handy as a quick workspace target on rooted or all-files devices. */
    fun sharedStorageRoot(): File = File("/storage/emulated/0")

    /**
     * Turns a tool-supplied path into a concrete [File]. Relative paths resolve against the
     * workspace; absolute paths are taken as-is. Throws when [restrictToWorkspace] is on and the
     * result would sit outside the workspace, so a confined agent cannot climb out with `..` or an
     * absolute path.
     */
    fun resolve(path: String): File {
        val trimmed = path.trim()
        require(trimmed.isNotEmpty()) { "path is required" }
        val root = root()
        val target = File(trimmed).let { if (it.isAbsolute) it else File(root, trimmed) }

        if (settings.current.tools.restrictToWorkspace) {
            val rootCanon = root.canonicalFile
            val targetCanon = target.canonicalFile
            val within = targetCanon == rootCanon ||
                targetCanon.path.startsWith(rootCanon.path + File.separator)
            require(within) {
                "path escapes the workspace ($trimmed). Turn off \"restrict to workspace\" in " +
                    "Settings to allow paths outside ${rootCanon.path}."
            }
        }
        return target
    }

    /** A workspace-relative label for display, falling back to the absolute path. */
    fun label(file: File): String = runCatching {
        val rootPath = root().canonicalFile.path
        val filePath = file.canonicalFile.path
        when {
            filePath == rootPath -> "."
            filePath.startsWith(rootPath + File.separator) -> filePath.substring(rootPath.length + 1)
            else -> filePath
        }
    }.getOrDefault(file.path)
}

/** Detects and caches whether `su` is available and grants a shell. */
object RootAccess {
    @Volatile private var cached: Boolean? = null

    /** True when a `su` binary sits on a known path — a cheap check that never prompts. */
    fun binaryPresent(): Boolean = SU_PATHS.any { File(it).exists() }

    /**
     * Actually tries to open a root shell (this is what may show the superuser prompt). Result is
     * cached so the prompt appears at most once per process.
     */
    @Synchronized
    fun isGranted(): Boolean {
        cached?.let { return it }
        val granted = runCatching {
            val process = ProcessBuilder("su", "-c", "id -u").redirectErrorStream(true).start()
            val finished = process.waitFor(6, TimeUnit.SECONDS)
            if (!finished) {
                process.destroy()
                false
            } else {
                val output = process.inputStream.bufferedReader().readText().trim()
                process.exitValue() == 0 && output.contains("0")
            }
        }.getOrDefault(false)
        cached = granted
        return granted
    }

    fun invalidate() {
        cached = null
    }

    private val SU_PATHS = listOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
        "/system/app/Superuser.apk", "/data/local/xbin/su", "/data/local/bin/su",
    )
}
