package com.aicode.feature.agent.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import com.aicode.feature.agent.data.local.dao.GroupChatDao
import com.aicode.feature.agent.data.local.entity.GroupChatRoutineEntity
import com.aicode.feature.agent.domain.groupchat.GroupChatCoordinator
import com.aicode.feature.agent.domain.groupchat.GroupChatMember
import com.aicode.feature.agent.domain.groupchat.GroupChatMessage
import com.aicode.feature.agent.domain.groupchat.GroupChatMentionParser
import com.aicode.feature.agent.domain.groupchat.GroupRoutineScheduler
import com.aicode.feature.agent.domain.model.AgentMode
import com.aicode.feature.agent.domain.session.SessionUseCase
import com.aicode.feature.agent.domain.subagent.AgentDefinitionRepository
import com.aicode.feature.settings.data.repository.SubagentModelSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 群聊房间 UI 状态：房间消息 + 成员 + 运行时状态。
 */
data class GroupRoomUiState(
    val roomId: String,
    val title: String,
    val messages: List<GroupChatMessage> = emptyList(),
    val members: List<GroupChatMember> = emptyList(),
    val running: Boolean = false,
    val turn: String? = null,
    val holds: Set<String> = emptySet(),
    /** 是否有成员发言 @user 请求用户介入。 */
    val needsUser: Boolean = false
)

@HiltViewModel
class GroupChatViewModel @Inject constructor(
    private val coordinator: GroupChatCoordinator,
    private val chatSessionDao: ChatSessionDao,
    private val agentMessageDao: AgentMessageDao,
    private val groupChatDao: GroupChatDao,
    private val routineScheduler: GroupRoutineScheduler,
    private val agentDefinitionRepository: AgentDefinitionRepository,
    private val presetRepository: SubagentModelSettingsRepository,
    private val sessionUseCase: SessionUseCase
) : ViewModel() {

    private val roomId = MutableStateFlow<String?>(null)

    private val messages = MutableStateFlow<List<GroupChatMessage>>(emptyList())
    private val members = MutableStateFlow<List<GroupChatMember>>(emptyList())
    private val title = MutableStateFlow("")
    private val running = MutableStateFlow(false)
    private val turn = MutableStateFlow<String?>(null)
    private val holds = MutableStateFlow<Set<String>>(emptySet())
    private val routines = MutableStateFlow<List<GroupChatRoutineEntity>>(emptyList())

    /** 成员候选：agents 目录下 .md 定义（读取一次）+ 设置里的子代理预设（flow 订阅）。 */
    private val definitionMembers = MutableStateFlow<List<GroupChatMember>>(emptyList())
    private val presetMembers = MutableStateFlow<List<GroupChatMember>>(emptyList())

    /** 成员 → 预设模式（AUTO/PLAN；BUILD 或未配置不记录）。供群聊房间成员条显示模式徽章。 */
    private val memberModes = MutableStateFlow<Map<String, AgentMode>>(emptyMap())

    init {
        definitionMembers.value = agentDefinitionRepository.listAll().map { entry ->
            val def = entry.definition
            GroupChatMember(
                memberKey = def.name,
                title = def.title ?: def.name,
                handle = def.name,
                avatarColor = def.avatarColor,
                avatarShape = def.avatarShape
            )
        }
        viewModelScope.launch {
            presetRepository.presetsFlow.collect { presets ->
                val defKeys = definitionMembers.value.map { it.memberKey.lowercase() }.toSet()
                presetMembers.value = presets
                    .filter { it.name.lowercase() !in defKeys } // 与定义重名时定义优先，避免 memberKey 歧义
                    .map { preset ->
                        GroupChatMember(
                            memberKey = preset.name,
                            title = preset.name,
                            handle = preset.name,
                            presetId = preset.id
                        )
                    }
                memberModes.value = presets.mapNotNull { preset ->
                    val mode = preset.modeReminders.firstOrNull { it == "AUTO" || it == "PLAN" }
                        ?.let { runCatching { AgentMode.valueOf(it) }.getOrNull() }
                    mode?.let { preset.name to it }
                }.toMap()
            }
        }
    }

    /** 房间实时状态。 */
    val uiState: StateFlow<GroupRoomUiState?> = combine(
        combine(roomId, messages, members, title) { rid, msgs, mems, t ->
            GroupRoomUiState(roomId = rid ?: "", title = t, messages = msgs, members = mems)
        },
        combine(running, turn, holds) { r, trn, h -> Triple(r, trn, h) },
        messages
    ) { base, extra, msgs ->
        if (base.roomId.isEmpty()) null
        else base.copy(
            running = extra.first,
            turn = extra.second,
            holds = extra.third,
            needsUser = msgs.lastOrNull { it.kind == "member" && GroupChatMentionParser.mentionsUser(it.text) } != null
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** 房间定时任务列表。 */
    val routinesFlow: StateFlow<List<GroupChatRoutineEntity>> = routines.asStateFlow()

    /** 新建群聊可选成员（agents 目录下 .md 定义 + 子代理预设，预设与定义重名时定义优先）。 */
    val availableMembers: StateFlow<List<GroupChatMember>> =
        combine(definitionMembers, presetMembers) { defs, presets -> defs + presets }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 成员 → 预设模式（房间成员条徽章用）。 */
    val memberModesFlow: StateFlow<Map<String, AgentMode>> = memberModes.asStateFlow()

    /** 当前订阅的房间 id（用于协程内判断，防止旧订阅覆盖新房间）。 */
    private val subscribedRoom = MutableStateFlow<String?>(null)
    /** 订阅协程句柄：切换房间时取消，避免旧房间的 collect 长期挂着。 */
    private val subscriptionJobs = mutableListOf<kotlinx.coroutines.Job>()

    fun openRoom(roomId: String) {
        this.roomId.value = roomId
        viewModelScope.launch {
            refreshRoom()
        }
        if (subscribedRoom.value == roomId) return
        subscriptionJobs.forEach { it.cancel() }
        subscriptionJobs.clear()
        subscribedRoom.value = roomId
        // 订阅房间运行时状态
        subscriptionJobs += viewModelScope.launch {
            coordinator.roomStates.collect { states ->
                if (subscribedRoom.value != roomId) return@collect
                val state = states[roomId] ?: return@collect
                running.value = state.running
                turn.value = state.turn
                holds.value = state.holds.keys
            }
        }
        // 订阅消息变化（成员发言由协调器落库，这里实时刷新）
        subscriptionJobs += viewModelScope.launch {
            agentMessageDao.getMessagesBySession(roomId).collect { entities ->
                if (subscribedRoom.value != roomId) return@collect
                messages.value = entities.map { entity ->
                    val isMember = entity.senderName != null
                    GroupChatMessage(
                        id = entity.id,
                        kind = if (isMember) "member" else "user",
                        sender = entity.senderName ?: "You",
                        text = entity.content,
                        thread = "legacy",
                        at = entity.timestamp
                    )
                }
            }
        }
        // 订阅房间定时任务
        subscriptionJobs += viewModelScope.launch {
            groupChatDao.routinesByRoom(roomId).collect { list ->
                if (subscribedRoom.value != roomId) return@collect
                routines.value = list
            }
        }
    }

    private suspend fun refreshRoom() {
        val rid = roomId.value ?: return
        val room = chatSessionDao.getById(rid) ?: return
        title.value = room.title
        members.value = coordinator.decodeMembers(room)
        val entities = agentMessageDao.getMessagesBySessionOnce(rid)
        messages.value = entities.map { entity ->
            val isMember = entity.senderName != null
            GroupChatMessage(
                id = entity.id,
                kind = if (isMember) "member" else "user",
                sender = entity.senderName ?: "You",
                text = entity.content,
                thread = "legacy",
                at = entity.timestamp
            )
        }
    }

    /** 用户发言：走协调器（不触发普通 workflow）。 */
    fun sendMessage(text: String) {
        val rid = roomId.value ?: return
        viewModelScope.launch {
            coordinator.sendUserMessage(rid, text)
            refreshRoom()
        }
    }

    /** 停止房间。 */
    fun stopRoom() {
        val rid = roomId.value ?: return
        viewModelScope.launch { coordinator.stopRoom(rid) }
    }

    /** 释放全部被暂停成员。 */
    fun releaseAll() {
        val rid = roomId.value ?: return
        viewModelScope.launch { coordinator.releaseMember(rid, null) }
    }

    /** 释放单个成员。 */
    fun releaseMember(memberKey: String) {
        val rid = roomId.value ?: return
        viewModelScope.launch { coordinator.releaseMember(rid, memberKey) }
    }

    /** 删除群聊房间（含成员子会话与定时任务）。 */
    fun deleteRoom(roomId: String) {
        viewModelScope.launch { coordinator.deleteRoom(roomId) }
    }

    /** 向房间添加成员（memberKeys 对应 availableMembers 的 memberKey）。 */
    fun addMembers(memberKeys: List<String>) {
        val rid = roomId.value ?: return
        val candidates = availableMembers.value.associateBy { it.memberKey }
        val newMembers = memberKeys.mapNotNull { candidates[it] }
        if (newMembers.isEmpty()) return
        viewModelScope.launch {
            coordinator.addMembers(rid, newMembers)
            refreshRoom()
        }
    }

    /** 从房间移除成员（子会话保留）。 */
    fun removeMember(memberKey: String) {
        val rid = roomId.value ?: return
        viewModelScope.launch {
            coordinator.removeMember(rid, memberKey)
            refreshRoom()
        }
    }

    /** 创建群聊房间。@return 新房间 id，失败返回 null。 */
    suspend fun createRoom(
        name: String,
        memberKeys: List<String>,
        workspacePath: String
    ): String? = withContext(Dispatchers.IO) {
        if (name.isBlank() || memberKeys.isEmpty()) return@withContext null
        val candidates = availableMembers.value.associateBy { it.memberKey }
        val members = memberKeys.mapNotNull { candidates[it] }
        if (members.isEmpty()) return@withContext null
        val base = sessionUseCase.newSessionEntity(workspacePath = workspacePath)
        val room = com.aicode.feature.agent.data.local.entity.ChatSessionEntity(
            id = base.id,
            title = name,
            createdAt = base.createdAt,
            updatedAt = base.updatedAt,
            workspacePath = workspacePath,
            isGroupChat = true,
            groupMembersJson = coordinator.encodeMembers(members)
        )
        chatSessionDao.upsert(room)
        room.id
    }

    // ── 定时任务（Routines）──────────────────────────────────────────────────

    /** 新建定时任务。@return 是否成功（schedule 非法或指令为空返回 false）。 */
    suspend fun createRoutine(memberKey: String, schedule: String, instruction: String): Boolean {
        val rid = roomId.value ?: return false
        return routineScheduler.createRoutine(rid, memberKey, schedule, instruction)
    }

    fun setRoutineEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch { routineScheduler.setEnabled(id, enabled) }
    }

    fun deleteRoutine(id: String) {
        viewModelScope.launch { routineScheduler.deleteRoutine(id) }
    }

    /** 手动触发一次定时任务（不等待扫描周期）。 */
    fun runRoutine(id: String) {
        viewModelScope.launch { routineScheduler.runNow(id) }
    }
}
