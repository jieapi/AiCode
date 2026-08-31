package com.aicode.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import com.aicode.feature.agent.data.local.dao.CheckpointDao
import com.aicode.feature.agent.data.local.dao.LlmCallRecordDao
import com.aicode.feature.agent.data.local.dao.TodoItemDao
import com.aicode.feature.settings.data.local.dao.AIProviderDao
import com.aicode.feature.settings.domain.repository.AIProviderRepository
import com.aicode.feature.agent.data.local.database.AgentDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import com.aicode.feature.agent.data.remote.anthropic.AnthropicApi
import com.aicode.feature.agent.data.remote.gemini.GeminiApi
import com.aicode.feature.agent.data.remote.openai.OpenAIApi
import com.aicode.feature.agent.domain.container.CommandEngine
import com.aicode.feature.agent.domain.container.DelegatingCommandEngine
import com.aicode.feature.agent.domain.container.LinuxContainerEngine
import com.aicode.feature.agent.domain.container.RemoteSshConnection
import com.aicode.feature.agent.domain.container.RemoteSshEngine
import com.aicode.feature.agent.domain.container.SharedPrefsSshHostKeyStore
import com.aicode.feature.agent.domain.container.SharedPrefsSshLoginKeyStore
import com.aicode.feature.agent.domain.container.SshHostKeyStore
import com.aicode.feature.agent.domain.container.SshLoginKeyStore
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.agent.domain.tool.file.ReadFileTool
import com.aicode.feature.agent.domain.tool.file.SendFileTool
import com.aicode.feature.agent.domain.tool.file.ViewImageTool
import com.aicode.feature.agent.domain.tool.file.WriteFileTool
import com.aicode.feature.agent.domain.tool.editor.EditFileTool
import com.aicode.feature.agent.domain.tool.container.ExecuteCommandTool
import com.aicode.feature.agent.domain.tool.container.TerminalSessionTool
import com.aicode.feature.agent.domain.tool.explorer.ListFilesTool
import com.aicode.feature.agent.domain.tool.explorer.SearchCodeTool
import com.aicode.feature.agent.domain.tool.skill.LoadSkillTool
import com.aicode.feature.agent.domain.tool.question.AskUserQuestionTool
import com.aicode.feature.agent.domain.tool.todo.TodoTool
import com.aicode.feature.agent.domain.tool.subagent.TaskTool
import com.aicode.feature.agent.domain.subagent.SubAgentEventBus
import com.aicode.feature.agent.domain.prompt.SystemPromptProvider
import com.aicode.feature.agent.domain.workflow.AgentWorkflow
import com.aicode.feature.agent.domain.tool.ToolPermissionManager
import com.aicode.feature.agent.domain.permission.ToolPermissionPolicyEngine
import com.aicode.feature.agent.domain.tool.ToolRegistry
import com.aicode.feature.agent.domain.tool.ToolOutputStore
import com.aicode.feature.settings.data.remote.ModelMetadataService
import com.aicode.feature.terminal.domain.DelegatingTerminalSessionProvider
import com.aicode.feature.terminal.domain.RemoteTerminalSessionManager
import com.aicode.feature.terminal.domain.TerminalSessionManager
import com.aicode.feature.terminal.domain.TerminalSessionProvider
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.DelegatingFileAccess
import com.aicode.feature.workspace.domain.LocalFileAccess
import com.aicode.feature.workspace.domain.RemoteSftpFileAccess
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

import com.aicode.core.db.MigrationLoader
import com.aicode.core.db.SchemaCatchUp
import com.aicode.feature.agent.domain.checkpoint.CheckpointManager
import com.aicode.feature.agent.domain.session.MessagePersistenceUseCase
import com.aicode.feature.agent.domain.session.SessionUseCase
import com.aicode.feature.agent.domain.tool.mcp.ManageMcpTool
import com.aicode.feature.agent.domain.tool.memory.MemoryTool
import com.aicode.feature.agent.domain.tool.mode.PlanApprovalManager
import com.aicode.feature.agent.domain.tool.mode.SwitchModeTool
import com.aicode.feature.agent.domain.tool.search.WebFetchTool
import com.aicode.feature.agent.domain.tool.search.WebSearchTool
import com.aicode.feature.agent.domain.workflow.ContextCompactor
import com.aicode.feature.agent.domain.workflow.StatefulAgentWorkflow
import com.aicode.feature.settings.data.repository.CompactionModelSettingsRepository
import com.aicode.feature.settings.data.repository.DefaultModelSettingsRepository
import com.aicode.feature.settings.data.repository.TitleModelSettingsRepository
import com.aicode.feature.workspace.data.local.dao.RemoteConnectionDao
import com.aicode.feature.workspace.data.repository.WorkspaceRepository

@Module
@InstallIn(SingletonComponent::class)
object AgentModule {

    @Provides
    @Singleton
    fun provideAgentDatabase(@ApplicationContext context: Context): AgentDatabase {
        return Room.databaseBuilder(
            context,
            AgentDatabase::class.java,
            "aicode_agent_db"
        ).addMigrations(*MigrationLoader.loadMigrations(context))
            .addCallback(object : androidx.room.RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    SchemaCatchUp.ensure(db)
                }
            })
            .fallbackToDestructiveMigration(dropAllTables = false)
            .build()
    }

    @Provides
    @Singleton
    fun provideCheckpointDao(database: AgentDatabase): CheckpointDao {
        return database.checkpointDao()
    }

    @Provides
    @Singleton
    fun provideAgentMessageDao(database: AgentDatabase): AgentMessageDao {
        return database.agentMessageDao()
    }

    @Provides
    @Singleton
    fun provideChatSessionDao(database: AgentDatabase): ChatSessionDao {
        return database.chatSessionDao()
    }

    @Provides
    @Singleton
    fun provideAIProviderDao(database: AgentDatabase): AIProviderDao {
        return database.aiProviderDao()
    }

    @Provides
    @Singleton
    fun provideRemoteConnectionDao(database: AgentDatabase): RemoteConnectionDao {
        return database.remoteConnectionDao()
    }

    @Provides
    @Singleton
    fun provideLlmCallRecordDao(database: AgentDatabase): LlmCallRecordDao {
        return database.llmCallRecordDao()
    }

    @Provides
    @Singleton
    fun provideTodoItemDao(database: AgentDatabase): TodoItemDao {
        return database.todoItemDao()
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        // 流式 SSE 下读超时是「相邻数据块之间」的等待上限；120s 给慢启动/长思考留足空间，
        // 真正卡死由上层阶梯重试（RetryPolicy）兜底。
        return OkHttpClient.Builder()
            .connectTimeout(120, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    @Named("OpenAI")
    fun provideOpenAIRetrofit(client: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl("https://api.openai.com/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    @Named("Anthropic")
    fun provideAnthropicRetrofit(client: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl("https://api.anthropic.com/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun provideOpenAIApi(@Named("OpenAI") retrofit: Retrofit): OpenAIApi {
        return retrofit.create(OpenAIApi::class.java)
    }

    @Provides
    @Singleton
    fun provideAnthropicApi(@Named("Anthropic") retrofit: Retrofit): AnthropicApi {
        return retrofit.create(AnthropicApi::class.java)
    }

    @Provides
    @Singleton
    @Named("Gemini")
    fun provideGeminiRetrofit(client: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl("https://generativelanguage.googleapis.com/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun provideGeminiApi(@Named("Gemini") retrofit: Retrofit): GeminiApi {
        return retrofit.create(GeminiApi::class.java)
    }

    @Provides
    @Singleton
    fun provideCommandEngine(delegate: DelegatingCommandEngine): CommandEngine = delegate

    @Provides
    @Singleton
    fun provideFileAccess(delegate: DelegatingFileAccess): FileAccessProvider = delegate

    @Provides
    @Singleton
    fun provideTerminalSessionProvider(delegate: DelegatingTerminalSessionProvider): TerminalSessionProvider = delegate

    @Provides
    @Singleton
    fun provideDelegatingTerminalSessionProvider(
        modeHolder: ExecutionModeHolder,
        local: TerminalSessionManager,
        remote: RemoteTerminalSessionManager
    ): DelegatingTerminalSessionProvider = DelegatingTerminalSessionProvider(modeHolder, local, remote)

    @Provides
    @Singleton
    fun provideRemoteSftpFileAccess(
        connection: RemoteSshConnection,
        workspaceRepository: WorkspaceRepository
    ): RemoteSftpFileAccess = RemoteSftpFileAccess(connection, workspaceRepository)

    @Provides
    @Singleton
    fun provideSshHostKeyStore(impl: SharedPrefsSshHostKeyStore): SshHostKeyStore = impl

    @Provides
    @Singleton
    fun provideSshLoginKeyStore(impl: SharedPrefsSshLoginKeyStore): SshLoginKeyStore = impl

    @Provides
    @Singleton
    fun provideToolRegistry(
        readFileTool: ReadFileTool,
        sendFileTool: SendFileTool,
        viewImageTool: ViewImageTool,
        writeFileTool: WriteFileTool,
        editFileTool: EditFileTool,
        executeCommandTool: ExecuteCommandTool,
        terminalSessionTool: TerminalSessionTool,
        listFilesTool: ListFilesTool,
        searchCodeTool: SearchCodeTool,
        loadSkillTool: LoadSkillTool,
        askUserQuestionTool: AskUserQuestionTool,
        manageMcpTool: ManageMcpTool,
        webSearchTool: WebSearchTool,
        webFetchTool: WebFetchTool,
        switchModeTool: SwitchModeTool,
        todoTool: TodoTool,
        memoryTool: MemoryTool,
        taskTool: TaskTool
    ): ToolRegistry {
        return ToolRegistry().apply {
            register("readFile", readFileTool)
            register("sendFile", sendFileTool)
            register("viewImage", viewImageTool)
            register("writeFile", writeFileTool)
            register("editFile", editFileTool)
            register("Bash", executeCommandTool)
            register("terminal", terminalSessionTool)
            register("list", listFilesTool)
            register("search", searchCodeTool)
            register("loadSkill", loadSkillTool)
            register("askUserQuestion", askUserQuestionTool)
            register("manageMcp", manageMcpTool)
            register("websearch", webSearchTool)
            register("webfetch", webFetchTool)
            register("switchMode", switchModeTool)
            register("todo", todoTool)
            register("memory", memoryTool)
            register("task", taskTool)
        }
    }

    @Provides
    @Singleton
    fun provideAgentWorkflow(
        toolRegistry: ToolRegistry,
        aiProviderRepository: AIProviderRepository,
        openAIApi: OpenAIApi,
        anthropicApi: AnthropicApi,
        geminiApi: GeminiApi,
        promptProvider: SystemPromptProvider,
        permissionManager: ToolPermissionManager,
        policyEngine: ToolPermissionPolicyEngine,
        contextCompactor: ContextCompactor,
        planApprovalManager: PlanApprovalManager,
        toolOutputStore: ToolOutputStore,
        modelMetadataService: ModelMetadataService,
        compactionModelSettingsRepository: CompactionModelSettingsRepository,
        titleModelSettingsRepository: TitleModelSettingsRepository,
        defaultModelSettingsRepository: DefaultModelSettingsRepository,
        sessionUseCase: SessionUseCase,
        messagePersistenceUseCase: MessagePersistenceUseCase,
        checkpointManager: CheckpointManager,
        llmCallRecordDao: LlmCallRecordDao
    ): AgentWorkflow {
        return StatefulAgentWorkflow(
            toolRegistry,
            aiProviderRepository,
            openAIApi,
            anthropicApi,
            geminiApi,
            promptProvider,
            permissionManager,
            policyEngine,
            contextCompactor,
            planApprovalManager,
            toolOutputStore,
            modelMetadataService,
            compactionModelSettingsRepository,
            titleModelSettingsRepository,
            defaultModelSettingsRepository,
            sessionUseCase,
            messagePersistenceUseCase,
            checkpointManager,
            llmCallRecordDao
        )
    }
}
