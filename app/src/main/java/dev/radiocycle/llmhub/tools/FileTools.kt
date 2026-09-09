package dev.radiocycle.llmhub.tools

import dev.radiocycle.llmhub.data.repo.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
private fun JsonObject.bool(key: String): Boolean? = this[key]?.jsonPrimitive?.booleanOrNull

/** Reads a text file from the workspace, opencode-style, with 1-based line numbers. */
class ReadFileTool(
    private val workspace: WorkspaceManager,
    private val settings: SettingsRepository,
) : AgentTool {

    override val spec = ToolSpec(
        name = "read_file",
        description = "Read a text file and return its contents with line numbers. Paths are " +
            "relative to the workspace unless absolute. Use it before edit_file so you know the " +
            "exact text to replace.",
        parameters = objectSchema(required = listOf("path")) {
            stringProp("path", "File path, relative to the workspace or absolute.")
            intProp("offset", "1-based line to start from (optional).")
            intProp("limit", "Maximum number of lines to return (optional).")
        },
    )

    override suspend fun execute(args: JsonObject): ToolOutcome = withContext(Dispatchers.IO) {
        runCatching {
            val file = workspace.resolve(args.str("path").orEmpty())
            if (!file.exists()) return@runCatching ToolOutcome("File not found: ${workspace.label(file)}", isError = true)
            if (file.isDirectory) return@runCatching ToolOutcome("${workspace.label(file)} is a directory; use list_files.", isError = true)

            val limitChars = settings.current.tools.fileReadCharLimit
            val raw = file.readText()
            val truncatedChars = raw.length > limitChars
            val text = if (truncatedChars) raw.take(limitChars) else raw

            val allLines = text.lines()
            val offset = (args["offset"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 1).coerceAtLeast(1)
            val limit = args["limit"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            val slice = allLines.drop(offset - 1).let { if (limit != null) it.take(limit) else it }
            val width = (offset + slice.size).toString().length

            ToolOutcome(
                buildString {
                    appendLine("${workspace.label(file)} (${allLines.size} lines, ${file.length()} bytes)")
                    slice.forEachIndexed { i, line ->
                        appendLine("${(offset + i).toString().padStart(width)}  $line")
                    }
                    if (truncatedChars) appendLine("… truncated at $limitChars chars")
                }.trimEnd()
            )
        }.getOrElse { ToolOutcome(it.message ?: "read failed", isError = true) }
    }
}

/** Writes a file whole, creating parent directories. Overwrites an existing file. */
class WriteFileTool(private val workspace: WorkspaceManager) : AgentTool {

    override val spec = ToolSpec(
        name = "write_file",
        description = "Create or overwrite a file with the given content. Parent directories are " +
            "created as needed. To change part of an existing file prefer edit_file.",
        parameters = objectSchema(required = listOf("path", "content")) {
            stringProp("path", "File path, relative to the workspace or absolute.")
            stringProp("content", "Full file content to write.")
        },
    )

    override suspend fun execute(args: JsonObject): ToolOutcome = withContext(Dispatchers.IO) {
        runCatching {
            val file = workspace.resolve(args.str("path").orEmpty())
            val content = args.str("content").orEmpty()
            file.parentFile?.mkdirs()
            val existed = file.exists()
            file.writeText(content)
            val verb = if (existed) "Updated" else "Created"
            ToolOutcome("$verb ${workspace.label(file)} (${content.length} chars, ${content.lines().size} lines)")
        }.getOrElse { ToolOutcome(it.message ?: "write failed", isError = true) }
    }
}

/**
 * Replaces an exact string inside a file, opencode-style. The old text must appear exactly once
 * unless [replace_all] is set, so an edit that would be ambiguous fails instead of touching the
 * wrong place.
 */
class EditFileTool(private val workspace: WorkspaceManager) : AgentTool {

    override val spec = ToolSpec(
        name = "edit_file",
        description = "Replace an exact snippet of text in a file. old_string must match the file " +
            "byte-for-byte (copy it from read_file, including indentation) and must be unique " +
            "unless replace_all is true. Set old_string to \"\" to require the file be empty/new.",
        parameters = objectSchema(required = listOf("path", "old_string", "new_string")) {
            stringProp("path", "File path, relative to the workspace or absolute.")
            stringProp("old_string", "Exact text to find. Empty string writes a new/empty file.")
            stringProp("new_string", "Text to replace it with.")
            putBool("replace_all", "Replace every occurrence instead of requiring a unique match.")
        },
    )

    override suspend fun execute(args: JsonObject): ToolOutcome = withContext(Dispatchers.IO) {
        runCatching {
            val file = workspace.resolve(args.str("path").orEmpty())
            val oldStr = args.str("old_string").orEmpty()
            val newStr = args.str("new_string").orEmpty()
            val replaceAll = args.bool("replace_all") ?: false

            if (oldStr.isEmpty()) {
                if (file.exists() && file.readText().isNotEmpty()) {
                    return@runCatching ToolOutcome(
                        "${workspace.label(file)} already has content; empty old_string only creates a new file.",
                        isError = true,
                    )
                }
                file.parentFile?.mkdirs()
                file.writeText(newStr)
                return@runCatching ToolOutcome("Created ${workspace.label(file)}")
            }

            if (!file.exists()) return@runCatching ToolOutcome("File not found: ${workspace.label(file)}", isError = true)
            val text = file.readText()
            val count = text.split(oldStr).size - 1
            when {
                count == 0 -> ToolOutcome("old_string not found in ${workspace.label(file)}.", isError = true)
                count > 1 && !replaceAll -> ToolOutcome(
                    "old_string appears $count times in ${workspace.label(file)}; add more surrounding " +
                        "context to make it unique, or set replace_all.",
                    isError = true,
                )
                else -> {
                    val updated = if (replaceAll) text.replace(oldStr, newStr)
                    else text.replaceFirst(oldStr, newStr)
                    file.writeText(updated)
                    ToolOutcome("Edited ${workspace.label(file)} ($count replacement${if (count == 1) "" else "s"})")
                }
            }
        }.getOrElse { ToolOutcome(it.message ?: "edit failed", isError = true) }
    }
}

/** Deletes a file, or a directory when recursive is set. */
class DeleteFileTool(private val workspace: WorkspaceManager) : AgentTool {

    override val spec = ToolSpec(
        name = "delete_file",
        description = "Delete a file. Set recursive to remove a non-empty directory and everything " +
            "under it.",
        parameters = objectSchema(required = listOf("path")) {
            stringProp("path", "File or directory path, relative to the workspace or absolute.")
            putBool("recursive", "Delete a directory and its contents.")
        },
    )

    override suspend fun execute(args: JsonObject): ToolOutcome = withContext(Dispatchers.IO) {
        runCatching {
            val file = workspace.resolve(args.str("path").orEmpty())
            val recursive = args.bool("recursive") ?: false
            when {
                !file.exists() -> ToolOutcome("Nothing to delete at ${workspace.label(file)}.", isError = true)
                file.isDirectory && !recursive ->
                    ToolOutcome("${workspace.label(file)} is a directory; pass recursive to remove it.", isError = true)
                else -> {
                    val ok = if (file.isDirectory) file.deleteRecursively() else file.delete()
                    if (ok) ToolOutcome("Deleted ${workspace.label(file)}")
                    else ToolOutcome("Could not delete ${workspace.label(file)} (permission?).", isError = true)
                }
            }
        }.getOrElse { ToolOutcome(it.message ?: "delete failed", isError = true) }
    }
}

/** Lists a directory, marking sub-directories and showing file sizes. */
class ListFilesTool(private val workspace: WorkspaceManager) : AgentTool {

    override val spec = ToolSpec(
        name = "list_files",
        description = "List the entries of a directory in the workspace. Omit path to list the " +
            "workspace root.",
        parameters = objectSchema {
            stringProp("path", "Directory path, relative to the workspace or absolute (optional).")
        },
    )

    override suspend fun execute(args: JsonObject): ToolOutcome = withContext(Dispatchers.IO) {
        runCatching {
            val path = args.str("path").orEmpty().ifBlank { "." }
            val dir = workspace.resolve(path)
            if (!dir.exists()) return@runCatching ToolOutcome("Not found: ${workspace.label(dir)}", isError = true)
            if (!dir.isDirectory) return@runCatching ToolOutcome("${workspace.label(dir)} is a file, not a directory.", isError = true)

            val entries = dir.listFiles()?.sortedWith(
                compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() }
            ).orEmpty()

            ToolOutcome(
                buildString {
                    appendLine("${workspace.label(dir)}/  (${entries.size} entries)")
                    entries.forEach { entry ->
                        if (entry.isDirectory) appendLine("  ${entry.name}/")
                        else appendLine("  ${entry.name}  (${entry.length()} bytes)")
                    }
                }.trimEnd()
            )
        }.getOrElse { ToolOutcome(it.message ?: "list failed", isError = true) }
    }
}
