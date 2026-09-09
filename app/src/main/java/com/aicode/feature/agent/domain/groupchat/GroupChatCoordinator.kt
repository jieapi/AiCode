package com.aicode.feature.agent.domain.groupchat

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import com.aicode.feature.agent.data.local.dao.GroupChatDao
import com.aicode.feature.agent.data.local.entity.AgentMessageEntity
import com.aicode.feature.agent.data.local.entity.ChatSessionEntity
import com.aicode.feature.agent.domain.groupchat.GroupChatMentionParser.HISTORY_LIMIT
import com.aicode.feature.agent.domain.groupchat.GroupChatMentionParser.MAX_CONTINUATIONS
import com.aicode.feature.agent.domain.groupchat.GroupChatMentionParser.MAX_MESSAGES
import com.aicode.feature.agent.domain.groupchat.GroupChatMentionParser.MAX_ROUNDS
import com.aicode.feature.agent.domain.session.SessionUseCase
import com.aicode.feature.agent.domain.subagent.AgentDefinitionRepository
import com.aicode.feature.agent.domain.subagent.SubAgentEvent
import com.aicode.feature.agent.domain.subagent.SubAgentEventBus
import com.aicode.feature.agent.domain.subagent.SubAgentEventType
import com.aicode.feature.agent.domain.subagent.SubagentPreset
import com.aicode.feature.agent.presentation.MessageRole
import com.aicode.feature.settings.data.repository.SubagentModelSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 群聊协调器：调度层，复用现有子代理执行链路（SubAgentEventBus SPAWNED →
 * AIAgentViewModel.spawnSubAgentWorkflow → 成员子会话 workflow）。
 *
 * 协调器只负责：轮次选择（纯函数）、成员会话创建（复用 SessionUseCase）、
 * turn prompt 构造、等待成员完成（复用 SubAgentEventBus.awaitAllCompletion）、
 * 提交校验（epoch）与房间消息落库。
 */
@Singleton
class GroupChatCoordinator @Inject constructor(
    private val sessionUseCase: SessionUseCase,
    private val chatSessionDao: ChatSessionDao,
    private val agentMessageDao: AgentMessageDao,
    private val groupChatDao: GroupChatDao,
    private val eventBus: SubAgentEventBus,
    private val agentDefinitionRepository: AgentDefinitionRepository,
    private val presetRepository: SubagentModelSettingsRepository
) {
    companion object {
        private const val TAG = "GroupChatCoordinator"
        /** 房间 drive 的成员 turn 超时等待（ms）。 */
        private const val MEMBER_TURN_WAIT_MS = 300_000L
        /** 旧 drive 退出后新 drive 的启动间隔。 */
        private const val RESTART_SETTLE_MS = 250L
        /** 成员 Bot Chat 私信会话的固定标题（对齐 Hermes canonical Bot Chat）。 */
        const val BOT_CHAT_TITLE = "Bot Chat"
        /** 成员 turn 时注入的私信条数上限。 */
        private const val DM_INJECT_LIMIT = 8
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true }

    /** 房间运行时状态（内存态，重启归零）。 */
    private val _roomStates = MutableStateFlow<Map<String, GroupChatRoomState>>(emptyMap())
    val roomStates: StateFlow<Map<String, GroupChatRoomState>> = _roomStates.asStateFlow()

    /** 房间 drive 正在运行的协程 job 句柄（按房间）。 */
    private val roomJobs = mutableMapOf<String, kotlinx.coroutines.Job>()
    private val pendingRestart = mutableSetOf<String>()

    // ── 房间管理 ─────────────────────────────────────────────────────────────

    /** 解码房间成员列表；非法/空 JSON 返回空列表。 */
    fun decodeMembers(room: ChatSessionEntity): List<GroupChatMember> {
        val raw = room.groupMembersJson ?: return emptyList()
        return runCatching { json.decodeFromString<List<GroupChatMember>>(raw) }.getOrDefault(emptyList())
    }

    /** 编码成员列表（写回 groupMembersJson）。 */
    fun encodeMembers(members: List<GroupChatMember>): String = json.encodeToString(members)

    /** 当前房间状态快照（无则初始化默认态）。 */
    fun roomState(roomId: String): GroupChatRoomState =
        _roomStates.value[roomId] ?: GroupChatRoomState()

    private fun updateRoomState(roomId: String, transform: (GroupChatRoomState) -> GroupChatRoomState) {
        val current = _roomStates.value
        _roomStates.value = current + (roomId to transform(current[roomId] ?: GroupChatRoomState()))
    }

    /** 读取房间全部消息（按时间升序）为群聊 log。 */
    suspend fun roomLog(roomId: String): List<GroupChatMessage> {
        val entities = agentMessageDao.getMessagesBySessionOnce(roomId)
        return entities.map { entity ->
            val isMember = entity.senderName != null
            GroupChatMessage(
                id = entity.id,
                kind = if (isMember) "member" else "user",
                sender = entity.senderName ?: "You",
                text = entity.content,
                thread = "legacy",
                at = entity.timestamp,
                reasoning = entity.reasoning
            )
        }
    }

    /**
     * 解析成员应使用的 provider/model：agents 目录下 .md 定义优先，子代理预设（presetId）兜底。
     * @return Triple(providerId, model, reasoningEffort)
     */
    private suspend fun resolveMemberModel(member: GroupChatMember): Triple<String?, String?, String?> {
        val definition = agentDefinitionRepository.find(member.memberKey)
        if (definition != null) return Triple(definition.providerId, definition.model, definition.reasoningEffort)
        resolveMemberPreset(member)?.let { preset ->
            return Triple(preset.providerId, preset.model, null)
        }
        return Triple(null, null, null)
    }

    /** 成员来自子代理预设时返回该预设（按 presetId 或成员名匹配）；否则 null。 */
    private suspend fun resolveMemberPreset(member: GroupChatMember): SubagentPreset? {
        if (member.presetId == null) return null
        return presetRepository.getPresets().firstOrNull {
            it.id == member.presetId || it.name == member.memberKey
        }
    }

    /**
     * 把预设的 AUTO/PLAN 模式提醒落到子会话 mode（workflow 按 mode 自动注入对应模式全文，
     * 群聊成员在 AUTO 预设下才能免权限弹窗正常工作）。返回自定义文本提醒（供 SPAWNED 透传）。
     */
    private suspend fun applyPresetMode(sessionId: String, member: GroupChatMember): List<String> {
        val preset = resolveMemberPreset(member) ?: return emptyList()
        preset.modeReminders.firstOrNull { it == "AUTO" || it == "PLAN" }?.let { modeKey ->
            sessionUseCase.updateMode(sessionId, modeKey)
        }
        return preset.modeReminders.filter { it != "AUTO" && it != "PLAN" }
    }

    // ── Bot Chat 私信 ────────────────────────────────────────────────────────

    /**
     * 确保成员的 Bot Chat 私信会话存在（房间下的子会话，title="Bot Chat"）。
     * 成员 turn 子会话以 title=成员名区分，故两者可共存。
     */
    private suspend fun ensureBotChatSession(
        room: ChatSessionEntity,
        member: GroupChatMember
    ): ChatSessionEntity? {
        val existing = chatSessionDao.getSubSessionsByParentOnce(room.id)
            .firstOrNull { it.subagentType == member.memberKey && it.title == BOT_CHAT_TITLE }
        if (existing != null) return existing

        val (providerId, model, reasoningEffort) = resolveMemberModel(member)
        val botChat = sessionUseCase.newSubSessionEntity(
            title = BOT_CHAT_TITLE,
            parentId = room.id,
            parent = room,
            subagentType = member.memberKey,
            providerId = providerId,
            model = model,
            reasoningEffort = reasoningEffort
        )
        sessionUseCase.upsertSession(botChat)
        applyPresetMode(botChat.id, member)
        return botChat
    }

    /**
     * 成员间私信：把 [text] 写入目标成员 [toKey] 的 Bot Chat 会话（senderName=fromKey）。
     * @return 是否投递成功（目标成员在房间中存在且会话可建）。
     */
    suspend fun appendDm(roomId: String, fromKey: String, toKey: String, text: String): Boolean {
        val room = chatSessionDao.getById(roomId) ?: return false
        val member = decodeMembers(room).firstOrNull { it.memberKey == toKey } ?: return false
        val botChat = ensureBotChatSession(room, member) ?: return false
        agentMessageDao.insert(
            AgentMessageEntity(
                id = UUID.randomUUID().toString(),
                sessionId = botChat.id,
                role = MessageRole.USER.name,
                content = text,
                timestamp = System.currentTimeMillis(),
                senderName = fromKey
            )
        )
        chatSessionDao.touch(botChat.id, System.currentTimeMillis())
        return true
    }

    /**
     * 读取成员 Bot Chat 私信会话最近 [limit] 条消息（升序，供 turn prompt 注入）。
     * 不存在会话时返回空列表。
     */
    suspend fun botChatRecent(roomId: String, memberKey: String, limit: Int = DM_INJECT_LIMIT): List<GroupChatMessage> {
        val room = chatSessionDao.getById(roomId) ?: return emptyList()
        val botChat = chatSessionDao.getSubSessionsByParentOnce(room.id)
            .firstOrNull { it.subagentType == memberKey && it.title == BOT_CHAT_TITLE }
            ?: return emptyList()
        return agentMessageDao.getMessagesBySessionOnce(botChat.id)
            .takeLast(limit)
            .map { entity ->
                GroupChatMessage(
                    id = entity.id,
                    kind = "member",
                    sender = entity.senderName ?: "user",
                    text = entity.content,
                    thread = BOT_CHAT_TITLE,
                    at = entity.timestamp
                )
            }
    }

    // ── 用户发送 ─────────────────────────────────────────────────────────────

    /**
     * 用户在群聊房间发消息：落库 → bump epoch → 解析 hold 指令 → 启动/重启 drive。
     *
     * @param parseHold 是否按文本中的 stop/pause/resume 关键词解析 hold 指令。
     *   routine 等程序化触发的消息必须传 false——指令正文可能无意含这些词（如
     *   「检查 CI 是否 stop」），误触发会暂停成员导致任务不执行。
     */
    suspend fun sendUserMessage(roomId: String, text: String, parseHold: Boolean = true) {
        val room = chatSessionDao.getById(roomId) ?: return
        if (!room.isGroupChat) return

        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val members = decodeMembers(room)
        if (members.isEmpty()) return

        // 用户消息落库（无 senderName = 用户）
        agentMessageDao.insert(
            AgentMessageEntity(
                id = UUID.randomUUID().toString(),
                sessionId = roomId,
                role = MessageRole.USER.name,
                content = trimmed,
                timestamp = System.currentTimeMillis(),
                senderName = null
            )
        )
        chatSessionDao.touch(roomId, System.currentTimeMillis())

        val mentioned = GroupChatMentionParser.parseMentions(trimmed, members)
        val directive = if (parseHold) {
            GroupChatMentionParser.classifyHoldDirective(trimmed, mentioned.mentioned, mentioned.everyone)
        } else {
            GroupChatMentionParser.HoldDirective()
        }
        updateRoomState(roomId) { state ->
            state.copy(
                epoch = state.epoch + 1,
                holds = GroupChatMentionParser.applyHoldDirective(
                    state.holds,
                    directive,
                    members.map { it.memberKey }
                )
            )
        }

        // drive 已在跑：旧 drive 会在成员边界退出，安排重启
        if (roomJobs[roomId]?.isActive == true) {
            pendingRestart.add(roomId)
        } else {
            startDrive(roomId, members)
        }
    }

    /** 停止房间：bump epoch + hold 全员（用户下次消息释放）+ 中断当前 turn 成员。 */
    suspend fun stopRoom(roomId: String) {
        val room = chatSessionDao.getById(roomId) ?: return
        val members = decodeMembers(room)
        updateRoomState(roomId) { state ->
            state.copy(
                epoch = state.epoch + 1,
                running = false,
                turn = null,
                holds = state.holds + members.filter { it.memberKey !in state.holds }
                    .associate { it.memberKey to System.currentTimeMillis() }
            )
        }
        // 中断当前发言成员（复用 STOPPED 链路）
        val state = roomState(roomId)
        val onTurn = state.turn
        if (onTurn != null) {
            // 排除 Bot Chat 会话（同一 subagentType），只中断真正的成员 turn 会话
            val memberSession = chatSessionDao.getSubSessionsByParentOnce(roomId)
                .firstOrNull { it.subagentType == onTurn && it.title != BOT_CHAT_TITLE }
            if (memberSession != null) {
                eventBus.emit(
                    SubAgentEvent(
                        subSessionId = memberSession.id,
                        parentSessionId = roomId,
                        type = SubAgentEventType.STOPPED
                    )
                )
            }
        }
        roomJobs[roomId]?.cancel()
        roomJobs.remove(roomId)
    }

    /** 释放被 hold 的成员（用户直接点名或 @all resume）。 */
    suspend fun releaseMember(roomId: String, memberKey: String? = null) {
        updateRoomState(roomId) { state ->
            val next = state.holds.toMutableMap()
            if (memberKey == null) next.clear() else next.remove(memberKey)
            state.copy(holds = next)
        }
    }

    /**
     * 向房间添加成员（与已有 memberKey 去重）。更新落库配置并 bump epoch——
     * drive 若在跑会在成员边界按新成员列表重跑；不在跑则等到下条消息。
     * @return 是否成功（房间不存在/非群聊返回 false）。
     */
    suspend fun addMembers(roomId: String, newMembers: List<GroupChatMember>): Boolean {
        val room = chatSessionDao.getById(roomId) ?: return false
        if (!room.isGroupChat || newMembers.isEmpty()) return false
        val existing = decodeMembers(room)
        val keys = existing.map { it.memberKey }.toSet()
        val merged = existing + newMembers.filter { it.memberKey !in keys }
        if (merged.size == existing.size) return true
        chatSessionDao.upsert(room.copy(groupMembersJson = encodeMembers(merged)))
        // 新成员加入视为新输入：bump epoch 让在跑的 drive 重启并覆盖新成员
        updateRoomState(roomId) { it.copy(epoch = it.epoch + 1) }
        return true
    }

    /**
     * 从房间移除成员：更新落库配置，清理其 hold/水线（子会话保留，历史不删）。
     * @return 是否成功（房间不存在/非群聊/成员不在房间返回 false）。
     */
    suspend fun removeMember(roomId: String, memberKey: String): Boolean {
        val room = chatSessionDao.getById(roomId) ?: return false
        if (!room.isGroupChat) return false
        val existing = decodeMembers(room)
        if (existing.none { it.memberKey == memberKey }) return false
        chatSessionDao.upsert(room.copy(groupMembersJson = encodeMembers(existing.filter { it.memberKey != memberKey })))
        updateRoomState(roomId) { state ->
            state.copy(
                epoch = state.epoch + 1,
                holds = state.holds - memberKey,
                watermarks = state.watermarks - memberKey,
                turn = if (state.turn == memberKey) null else state.turn
            )
        }
        return true
    }

    /**
     * 删除群聊房间：停止 drive、清理运行时状态与定时任务，级联删除房间及全部成员子会话（含 Bot Chat）。
     * @return 是否删除成功（房间不存在或不是群聊时返回 false）。
     */
    suspend fun deleteRoom(roomId: String): Boolean {
        val room = chatSessionDao.getById(roomId) ?: return false
        if (!room.isGroupChat) return false
        // 停止正在运行的 drive（runRounds finally 中 isCurrent 失效不会回写状态）
        roomJobs[roomId]?.cancel()
        roomJobs.remove(roomId)
        pendingRestart.remove(roomId)
        _roomStates.value = _roomStates.value - roomId
        groupChatDao.deleteByRoom(roomId)
        sessionUseCase.deleteSession(roomId)
        FileLogger.i(TAG, "群聊房间已删除: $roomId")
        return true
    }

    // ── drive ────────────────────────────────────────────────────────────────

    private fun startDrive(roomId: String, members: List<GroupChatMember>) {
        roomJobs[roomId] = scope.launch {
            runRounds(roomId, members)
        }
    }

    /**
     * 串行轮转驱动：for round in MAX_ROUNDS → responders → 逐个成员 turn。
     * 成员 turn 完全复用现有子代理链路：创建成员会话 → SPAWNED → awaitAllCompletion。
     */
    private suspend fun runRounds(roomId: String, members: List<GroupChatMember>) {
        val startEpoch = roomState(roomId).epoch
        val isCurrent = { roomState(roomId).epoch == startEpoch }
        var posted = 0
        var continuations = 0
        var exit: GroupDriveExit = GroupDriveExit.SETTLED

        try {
            for (round in 0 until MAX_ROUNDS) {
                if (!isCurrent()) { exit = GroupDriveExit.CANCELLED; return }
                val log = roomLog(roomId)
                val responders = GroupChatMentionParser.rotateSpeakers(
                    GroupChatMentionParser.resolveResponders(log, members), round
                )
                var spokeThisRound = 0

                for (member in responders) {
                    if (!isCurrent()) { exit = GroupDriveExit.CANCELLED; return }
                    if (posted >= MAX_MESSAGES) { exit = GroupDriveExit.CAPPED; return }

                    val state = roomState(roomId)
                    val seen = state.watermarks[member.memberKey] ?: 0
                    val delta = log.subList(seen.coerceAtMost(log.size), log.size)
                        .takeLast(HISTORY_LIMIT)
                    if (delta.isEmpty()) continue

                    // hold 检查：被暂停的成员跳过（水线直接推进，避免重复触发）
                    if (state.holds.containsKey(member.memberKey)) {
                        updateRoomState(roomId) { it.copy(watermarks = it.watermarks + (member.memberKey to log.size)) }
                        continue
                    }

                    val roomEntity = chatSessionDao.getById(roomId) ?: return
                    val privateDms = botChatRecent(roomId, member.memberKey)
                    val prompt = GroupTurnPromptBuilder.buildMemberTurnPrompt(
                        groupName = roomEntity.title,
                        members = members,
                        viewer = member,
                        delta = delta,
                        privateDms = privateDms
                    )

                    updateRoomState(roomId) { it.copy(turn = member.memberKey, running = true) }

                    val reply = runMemberTurn(roomId, member, prompt)
                    // 无论本轮是否作废都先清 turn：旧 drive 退出时 finally 不会清理（isCurrent 已失效），
                    // 不回手会让「X 正在思考」残留到新 drive 轮转才被覆盖。
                    updateRoomState(roomId) { it.copy(turn = null) }
                    if (!isCurrent()) {
                        // 新用户消息已 bump epoch：本轮结果丢弃，新 drive 会重跑
                        continue
                    }

                    if (reply != null && !GroupChatMentionParser.isPassText(reply.text)) {
                        // 成员发言落房间（USER + senderName，带思考过程）
                        agentMessageDao.insert(
                            AgentMessageEntity(
                                id = UUID.randomUUID().toString(),
                                sessionId = roomId,
                                role = MessageRole.USER.name,
                                content = reply.text,
                                timestamp = System.currentTimeMillis(),
                                senderName = member.memberKey,
                                reasoning = reply.reasoning
                            )
                        )
                        chatSessionDao.touch(roomId, System.currentTimeMillis())
                        posted += 1
                        spokeThisRound += 1
                    }
                    val logSize = roomLog(roomId).size
                    updateRoomState(roomId) {
                        it.copy(watermarks = it.watermarks + (member.memberKey to logSize))
                    }
                }

                if (spokeThisRound == 0) {
                    // 全员 pass：检查未解决 handoff，跑 continuation 轮
                    val logNow = roomLog(roomId)
                    val pendingKeys = GroupChatMentionParser.unaddressedMentions(logNow, members)
                    continuations += 1
                    if (pendingKeys.isNotEmpty() && continuations <= MAX_CONTINUATIONS && posted < MAX_MESSAGES) {
                        for (member in members.filter { it.memberKey in pendingKeys }) {
                            if (!isCurrent() || posted >= MAX_MESSAGES) break
                            val stateNow = roomState(roomId)
                            val seen = stateNow.watermarks[member.memberKey] ?: 0
                            val delta = logNow.subList(seen.coerceAtMost(logNow.size), logNow.size)
                            if (delta.isEmpty()) continue
                            if (stateNow.holds.containsKey(member.memberKey)) continue

                            val roomEntity = chatSessionDao.getById(roomId) ?: return
                            val privateDms = botChatRecent(roomId, member.memberKey)
                            val prompt = GroupTurnPromptBuilder.buildMemberTurnPrompt(
                                groupName = roomEntity.title,
                                members = members,
                                viewer = member,
                                delta = delta,
                                privateDms = privateDms
                            )
                            updateRoomState(roomId) { it.copy(turn = member.memberKey) }
                            val reply = runMemberTurn(roomId, member, prompt)
                            updateRoomState(roomId) { it.copy(turn = null) }
                            if (!isCurrent()) continue
                            if (reply != null && !GroupChatMentionParser.isPassText(reply.text)) {
                                agentMessageDao.insert(
                                    AgentMessageEntity(
                                        id = UUID.randomUUID().toString(),
                                        sessionId = roomId,
                                        role = MessageRole.USER.name,
                                        content = reply.text,
                                        timestamp = System.currentTimeMillis(),
                                        senderName = member.memberKey,
                                        reasoning = reply.reasoning
                                    )
                                )
                                chatSessionDao.touch(roomId, System.currentTimeMillis())
                                posted += 1
                                spokeThisRound += 1
                            }
                            val logSize = roomLog(roomId).size
                            updateRoomState(roomId) {
                                it.copy(watermarks = it.watermarks + (member.memberKey to logSize))
                            }
                        }
                    }
                    if (spokeThisRound == 0) {
                        if (pendingKeys.isNotEmpty() &&
                            (continuations > MAX_CONTINUATIONS || posted >= MAX_MESSAGES)
                        ) exit = GroupDriveExit.CAPPED
                        return
                    }
                }
            }
            exit = GroupDriveExit.CAPPED
        } finally {
            if (isCurrent()) {
                updateRoomState(roomId) { it.copy(running = false, turn = null) }
            }
            roomJobs.remove(roomId)
            // 排队的新消息：短暂等待后重启 drive
            if (pendingRestart.remove(roomId)) {
                delay(RESTART_SETTLE_MS)
                val room = chatSessionDao.getById(roomId) ?: return
                val membersNow = decodeMembers(room)
                if (membersNow.isNotEmpty() && !isCurrent()) {
                    startDrive(roomId, membersNow)
                }
            }
            FileLogger.d(TAG, "drive exit room=$roomId exit=$exit")
        }
    }

    /**
     * 执行一个成员的 turn：
     * 1. 确保成员子会话存在（parentId=房间，subagentType=memberKey，title=成员名）
     * 2. 发 SPAWNED（detail=turn prompt）→ ViewModel 自动在子会话启动 workflow
     * 3. awaitAllCompletion 等待完成
     * 4. 读子会话最后一条 assistant 消息作为回复
     */
    private suspend fun runMemberTurn(
        roomId: String,
        member: GroupChatMember,
        prompt: String
    ): MemberTurnReply? {
        val room = chatSessionDao.getById(roomId) ?: return null
        val subSession = ensureMemberSession(room, member) ?: return null

        // 关键：子会话跨 turn 复用，先清除上一轮的完成终态——否则 awaitAllCompletion
        // 会立刻读到旧 COMPLETED，成员根本不重新执行（且拿的是最后一次旧回复）。
        eventBus.forget(subSession.id)

        // 预设自定义模式提醒（AUTO/PLAN key 已由 applyPresetMode 落到会话 mode，这里只透传自定义文本）
        val customReminders = applyPresetMode(subSession.id, member)

        eventBus.emit(
            SubAgentEvent(
                subSessionId = subSession.id,
                parentSessionId = roomId,
                type = SubAgentEventType.SPAWNED,
                detail = prompt,
                modeReminders = customReminders
            )
        )

        // 等待成员完成（复用事件总线的退避轮询）
        val results = eventBus.awaitAllCompletion(setOf(subSession.id), MEMBER_TURN_WAIT_MS)
        val type = results[subSession.id]
        if (type != SubAgentEventType.COMPLETED) return null

        // 取最后一条有内容的 assistant 消息（排除只含不可见字符的伪内容，如零宽空格），
        // 连同思考过程一并带回（供房间复用普通聊天的 reasoning 气泡）。
        val messages = agentMessageDao.getMessagesBySessionOnce(subSession.id)
        val last = messages.lastOrNull { message ->
            message.role == MessageRole.ASSISTANT.name && message.content.hasRenderableContent()
        }
        val reply = last?.content
        val reasoning = last?.reasoning?.takeIf { it.hasRenderableContent() }
        FileLogger.i(
            TAG,
            "member turn done: room=$roomId member=${member.memberKey} messages=${messages.size} " +
                "reply=${reply?.take(120)?.replace('\n', ' ')?.let { "\"$it\"" } ?: "<null>"}"
        )
        return if (reply != null) MemberTurnReply(reply, reasoning) else null
    }

    /** 是否有可渲染的可见字符（零宽空格等不可见字符不算）。 */
    private fun String.hasRenderableContent(): Boolean = any { ch ->
        !ch.isWhitespace() &&
            ch != '\u200B' && ch != '\u200C' && ch != '\u200D' && ch != '\uFEFF' &&
            ch.category != CharCategory.FORMAT &&
            ch.category != CharCategory.CONTROL &&
            ch.category != CharCategory.SURROGATE
    }

    /** 确保成员子会话存在；不存在则创建（复用 SessionUseCase）。 */
    private suspend fun ensureMemberSession(
        room: ChatSessionEntity,
        member: GroupChatMember
    ): ChatSessionEntity? {
        val turnTitle = member.title.ifEmpty { member.memberKey }
        val existing = chatSessionDao.getSubSessionsByParentOnce(room.id)
            .firstOrNull { it.subagentType == member.memberKey && it.title == turnTitle }
        if (existing != null) return existing

        val (providerId, model, reasoningEffort) = resolveMemberModel(member)
        val sub = sessionUseCase.newSubSessionEntity(
            title = turnTitle,
            parentId = room.id,
            parent = room,
            subagentType = member.memberKey,
            providerId = providerId,
            model = model,
            reasoningEffort = reasoningEffort
        )
        sessionUseCase.upsertSession(sub)
        // 预设 AUTO/PLAN 模式落到子会话（workflow 注入对应模式全文）
        applyPresetMode(sub.id, member)
        return sub
    }

    /** 供 ViewModel/UI 清理时调用。 */
    fun dispose() {
        scope.cancel()
    }
}
