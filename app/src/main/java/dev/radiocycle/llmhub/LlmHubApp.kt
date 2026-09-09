package dev.radiocycle.llmhub

import android.app.Application
import dev.radiocycle.llmhub.data.repo.ConversationRepository
import dev.radiocycle.llmhub.data.repo.ProviderRepository
import dev.radiocycle.llmhub.data.repo.SettingsRepository
import dev.radiocycle.llmhub.rotation.ChatEngine
import dev.radiocycle.llmhub.rotation.RotationEngine
import dev.radiocycle.llmhub.tools.DeleteFileTool
import dev.radiocycle.llmhub.tools.EditFileTool
import dev.radiocycle.llmhub.tools.ExecJsTool
import dev.radiocycle.llmhub.tools.JsSandbox
import dev.radiocycle.llmhub.tools.ListFilesTool
import dev.radiocycle.llmhub.tools.ReadFileTool
import dev.radiocycle.llmhub.tools.ShellTool
import dev.radiocycle.llmhub.tools.ToolRegistry
import dev.radiocycle.llmhub.tools.WebFetchTool
import dev.radiocycle.llmhub.tools.WebSearchTool
import dev.radiocycle.llmhub.tools.WorkspaceManager
import dev.radiocycle.llmhub.tools.WriteFileTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers

/** Hand-rolled dependency container — the graph is small enough that a framework would only add noise. */
class LlmHubApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

class AppContainer(app: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val providers = ProviderRepository(app, scope)
    val settings = SettingsRepository(app, scope)
    val conversations = ConversationRepository(app, scope)

    val rotation = RotationEngine(providers, settings)

    private val jsSandbox = JsSandbox(app)
    val workspace = WorkspaceManager(app, settings)
    val toolRegistry = ToolRegistry(
        settings = settings,
        tools = listOf(
            WebSearchTool(settings),
            WebFetchTool(settings),
            ExecJsTool(jsSandbox, settings),
            ReadFileTool(workspace, settings),
            WriteFileTool(workspace),
            EditFileTool(workspace),
            DeleteFileTool(workspace),
            ListFilesTool(workspace),
            ShellTool(workspace, settings),
        ),
    )

    val chatEngine = ChatEngine(settings, rotation, toolRegistry)
}
