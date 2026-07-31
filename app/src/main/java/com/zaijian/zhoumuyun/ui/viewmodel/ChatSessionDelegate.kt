package com.zaijian.zhoumuyun.ui.viewmodel

import android.content.Context
import com.zaijian.zhoumuyun.data.agent.PregnancySettlementScheduler
import com.zaijian.zhoumuyun.data.db.AppDatabase
import com.zaijian.zhoumuyun.data.db.entity.AgentRelationEntity
import com.zaijian.zhoumuyun.data.manager.DaughterIdAllocator
import com.zaijian.zhoumuyun.data.model.DaughterDataException
import com.zaijian.zhoumuyun.data.model.DefaultCharacters
import com.zaijian.zhoumuyun.data.model.toCharacterIdentityEntity
import com.zaijian.zhoumuyun.data.model.toDaughterCharacterData
import com.zaijian.zhoumuyun.data.repository.DaughterCharacterRepository
import com.zaijian.zhoumuyun.data.repository.IdentityRepository
import com.zaijian.zhoumuyun.data.repository.MemoryRepository
import com.zaijian.zhoumuyun.data.repository.MenstrualCycleRepository
import com.zaijian.zhoumuyun.data.repository.MessageRepository
import com.zaijian.zhoumuyun.data.repository.PregnancyRepository
import com.zaijian.zhoumuyun.domain.ChatTagParser
import com.zaijian.zhoumuyun.domain.PresenceEngine
import com.zaijian.zhoumuyun.data.db.entity.RelationshipEntity
import com.zaijian.zhoumuyun.util.ZLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.collections.immutable.toImmutableList

/**
 * 会话初始化与消息加载委托，从 ChatViewModel 中提取。
 *
 * 封装 init(characterId) 的全部逻辑：
 * - Job 取消清理（replyJob / observeJobs / loadMessagesJob / settlementCheckJob / loadCharacterJob）
 * - PresenceEngine 前台标记
 * - 工具注册（toolRegistrar.registerCharacterTools）
 * - 分娩到期结算检查（PregnancySettlementScheduler）
 * - 角色加载 + B-6 死状态补偿
 * - 5 个 Flow 订阅（消息列表 / 主动消息 / 关系状态 / 头像同步 / 背景图）
 */
class ChatSessionDelegate(
    private val _uiState: MutableStateFlow<ChatUiState>,
    private val _streamingContent: MutableStateFlow<String?>,
    private val _streamingPsych: MutableStateFlow<String?>,
    private val _relForHeader: MutableStateFlow<RelationshipEntity?>,
    private val messageRepo: MessageRepository,
    private val daughterRepo: DaughterCharacterRepository,
    private val presenceEngine: PresenceEngine,
    private val identityRepo: IdentityRepository,
    private val pregnancyRepo: PregnancyRepository,
    private val memoryRepo: MemoryRepository,
    // A7-1 修复：B-6 补偿路径需要 cycleRepository 来补做第 5 步 resetAnchorToToday，
    // 与 DaughterRegistrationHelper.onIdentityRegister 的注册步骤对齐。
    private val cycleRepository: MenstrualCycleRepository,
    private val daughterIdAllocator: DaughterIdAllocator,
    private val db: AppDatabase,
    private val backgroundManager: ChatBackgroundManager,
    private val toolRegistrar: ChatToolRegistrar,
    private val viewModelScope: CoroutineScope,
    private val getApplication: () -> Context,
    private val setCurrentCharacterId: (Int) -> Unit,
    private val getCurrentCharacterId: () -> Int,
    private val getReplyJob: () -> Job?,
    private val setReplyJob: (Job?) -> Unit,
) {
    private var observeJobs: List<Job> = emptyList()
    private var loadMessagesJob: Job? = null
    private var settlementCheckJob: Job? = null
    private var loadCharacterJob: Job? = null

    /** 初始化（或切换）会话角色。取消上一次的全部 Job，重新启动观察者。 */
    fun init(characterId: Int) {
        // P1-10-4：切换角色时必须同时取消上一次的 replyJob
        //
        // Fix-孤儿文件（导航打断，配合 ToolCallInterceptor.executeWithTimeout 的
        // Fix-孤儿文件 一起看）：`init(characterId)` 由 ChatScreen 的
        // `LaunchedEffect(characterId) { chatViewModel.init(characterId) }` 触发——
        // 这不仅在"切换到另一个角色"时触发，导航离开聊天页再返回同一个角色
        // （composable 被销毁重建，LaunchedEffect 随之重新执行）同样会调用它。
        // 原实现无条件 `getReplyJob()?.cancel()`：如果用户恰好在 excel_gen/pptx_gen
        // 等耗时工具还在后台生成文件时短暂离开又返回同一角色的聊天页，这次回复会
        // 被无声无息地打断——用户完全不知道自己"离开一下"就导致了这个后果。
        // 只有真正切换到不同角色时，旧角色的 replyJob 才必须被取消（否则旧角色的
        // 回复落库时 getCurrentCharacterId() 已经指向新角色，会串号）；同一角色
        // 重新进入页面，没有理由打断一个仍在正常进行的回复，让它在后台继续跑完，
        // 该来的消息/文件卡稍后会正常出现在列表里。
        val isReenteringSameCharacterWithActiveReply =
            characterId == getCurrentCharacterId() && getReplyJob()?.isActive == true
        if (!isReenteringSameCharacterWithActiveReply) {
            getReplyJob()?.cancel()
            // Task-1 修复（导航打断）：_streamingContent/_streamingPsych 必须与 replyJob
            // 同步保护——若仅保护 replyJob 而清空这两个 StateFlow，StreamingMessageItem
            // 会立即退化成 "…" 占位符（ChatMessageBubble.kt 的 displayContent 逻辑），
            // 用户看到"生成中的对话消失了"，与 replyJob 被 cancel 的视觉效果完全一致。
            // 同角色重入 + replyJob 仍活跃时，保留这两个流的当前值，让气泡继续显示。
            _streamingContent.value = null
            _streamingPsych.value = null
        }
        observeJobs.forEach { it.cancel() }
        loadMessagesJob?.cancel()
        settlementCheckJob?.cancel()
        loadCharacterJob?.cancel()
        setCurrentCharacterId(characterId)
        PresenceEngine.foregroundChatCharacterId = characterId
        loadMessagesJob = null

        try {
            toolRegistrar.registerCharacterTools(characterId)
        } catch (e: Throwable) {
            ZLog.e("ChatSessionDelegate", "registerCharacterTools 失败，工具注册可能不完整", e)
        }

        settlementCheckJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                PregnancySettlementScheduler.runImmediateCheck(
                    context       = getApplication(),
                    pregnancyRepo = pregnancyRepo,
                    memoryRepo    = memoryRepo,
                    daughterRepo  = daughterRepo,
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                ZLog.e("ChatSessionDelegate", "分娩结算检查失败", e)
            }
        }

        loadCharacterJob = viewModelScope.launch(Dispatchers.IO) {
            val char = DefaultCharacters.find { it.id == characterId }
                ?: try {
                    daughterRepo.getCharacterConfig(characterId)
                } catch (e: DaughterDataException) {
                    ZLog.e("ChatSessionDelegate", "characterId=$characterId 女儿数据损坏，无法加载", e)
                    null
                }
            _uiState.update { it.copy(
                character   = char,
                currentMood = presenceEngine.getCachedPresence(characterId)?.mood,
                backgroundImageUri = null,
            ) }

            // B2 审查报告问题 #1 修复：假扮状态惰性恢复。
            // impersonationByCharacter 只存在内存里，进程被杀死重建后复位为
            // emptyMap()——若不恢复，SpeakerContext 会短暂回退到 OWNER_DIRECT，
            // 记忆隔离/关系值跳过/ReplyGuard 三项保护随之短暂失效，直到用户
            // 再次说出"我不是主人，我是XX"。
            //
            // 用 containsKey 而非 `[characterId] == null` 判断"是否已有记录"：
            // 用户主动解除假扮时，ChatMessageOrchestrator 会写入
            // (characterId to null) 这一条目本身（而不是删除 key），标记"已确认
            // 当前未假扮"，避免后续每条消息都重新跑一次 extractClaimedName 检测。
            // 这种情况下 `[characterId]` 同样是 null，但 key 是存在的，不应该
            // 触发恢复（否则会把用户刚解除的假扮状态又拉回来）。只有 key 完全
            // 不存在时（本次进程内还没有为这个角色判定过），才说明是进程刚
            // 重建，需要去 SharedPreferences 查一次。只在真正切换/首次进入时
            // 查一次，不会每次切换角色都多打一次开销，符合惰性恢复的思路。
            if (!_uiState.value.impersonationByCharacter.containsKey(characterId)) {
                val restoredName = ImpersonationStateStore.load(getApplication(), characterId)
                if (restoredName != null) {
                    _uiState.update {
                        it.copy(impersonationByCharacter = it.impersonationByCharacter + (characterId to restoredName))
                    }
                    ZLog.i("ChatSessionDelegate", "问题#1修复：characterId=$characterId 从本地持久化恢复假扮状态 impersonatedName=$restoredName")
                }
            }

            // B-6 修复：死状态2补偿——进程被杀时机恰好在 saveDaughter() 之后、
            // onIdentityRegister 回调之前，daughter_character 行有完整 JSON 但
            // daughterCharacterId 为 null。每次打开母亲角色聊天界面时检查一次，
            // 发现死状态则重新执行注册步骤。onIdentityRegister 内部全部是幂等操作。
            if (characterId in 1..6 || characterId >= 1000) {
                try {
                    val raw = daughterRepo.getByMother(characterId)
                    if (raw != null
                        && raw.daughterCharacterId == null
                        && raw.identityJson.isNotBlank()
                        && raw.stateLayerJson.isNotBlank()
                        && raw.customEnumsJson.isNotBlank()
                    ) {
                        ZLog.w("ChatSessionDelegate", "B-6: 检测到死状态2（母亲=$characterId），重新执行 onIdentityRegister")
                        val daughterData = raw.toDaughterCharacterData()
                        val allocatedId = daughterIdAllocator.allocate()

                        // A7-3 修复：女儿注册时继承母亲 ownerAliasesJson，修正忠诚锚点
                        // 文案中的 ownerName 称呼（否则女儿角色恒回退到"他"）。
                        val motherOwnerAliases = try {
                            identityRepo.getById(daughterData.motherCharacterId)?.ownerAliasesJson
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            ZLog.w("ChatSessionDelegate", "B-6: 查询母亲 ownerAliasesJson 失败，女儿将使用默认空数组", e)
                            null
                        }
                        val identityEntity = daughterData.toCharacterIdentityEntity(
                            allocatedId,
                            motherOwnerAliasesJson = motherOwnerAliases,
                        )

                        // A7-1/A7-2 修复：B-6 补偿路径此前是扁平 try-catch+日志，
                        // 缺少第 5 步 resetAnchorToToday，且中间步骤失败时已写入的行
                        // 不会被回滚。改为与 DaughterRegistrationHelper.onIdentityRegister
                        // 完全相同的 step 计数 + 反序回滚模式，并补上第 5 步。
                        var step = 0  // 1=identity已写  2=agent_relation已写  3=daughter_character已回填
                        try {
                            identityRepo.upsert(identityEntity)
                            step = 1
                            db.agentRelationDao().insert(
                                AgentRelationEntity(
                                    daughterId        = allocatedId,
                                    motherCharacterId = daughterData.motherCharacterId,
                                )
                            )
                            step = 2
                            daughterRepo.updateDaughterCharacterId(
                                motherCharacterId   = characterId,
                                daughterCharacterId = allocatedId,
                            )
                            step = 3
                            // A7-1 修复：第 5 步——初始化周期锚点，与 DaughterRegistrationHelper 对齐。
                            // 用 resetAnchorToToday 而非 initIfAbsent：后者只遍历写死的母亲映射表，
                            // 不认识动态分配的女儿 characterId。
                            cycleRepository.resetAnchorToToday(allocatedId)
                            ZLog.i("ChatSessionDelegate", "B-6: 补偿注册完成，daughterId=$allocatedId")
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            if (step >= 3) runCatching {
                                daughterRepo.clearDaughterCharacterIdForRollback(daughterData.motherCharacterId, allocatedId)
                            }
                            if (step >= 2) runCatching { db.agentRelationDao().deleteByDaughterId(allocatedId) }
                            if (step >= 1) runCatching { db.characterIdentityDao().deleteForRollback(allocatedId) }
                            throw e
                        } catch (e: Throwable) {
                            ZLog.e("ChatSessionDelegate", "B-6: 补偿注册失败，已回滚到 step=$step", e)
                            if (step >= 3) {
                                daughterRepo.clearDaughterCharacterIdForRollback(daughterData.motherCharacterId, allocatedId)
                            }
                            if (step >= 2) db.agentRelationDao().deleteByDaughterId(allocatedId)
                            if (step >= 1) db.characterIdentityDao().deleteForRollback(allocatedId)
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    ZLog.e("ChatSessionDelegate", "B-6: 死状态检测或补偿注册异常", e)
                }
            }
        }

        observeJobs = listOf(
            viewModelScope.launch {
                messageRepo.observeByCharacter(characterId)
                    .flowOn(Dispatchers.IO)
                    .collect { msgs ->
                        try {
                            _uiState.update {
                                it.copy(messages = msgs
                                    // v1.49 修复：过滤掉 file_read 锁死机制用来持久化"文件已读"
                                    // 凭证的内部标记消息——这条只是喂给下一轮 LLM 上下文看的，
                                    // 不该在聊天界面里冒出一条奇怪的系统气泡（见 FILE_READ_MARK_PREFIX 处说明）。
                                    .filterNot { it.content.startsWith(FILE_READ_MARK_PREFIX) }
                                    .map { ChatTagParser.toChatMessage(it) }.toImmutableList())
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            ZLog.e("ChatSessionDelegate", "characterId=$characterId 消息Flow订阅处理失败", e)
                            _uiState.update { it.copy(error = "加载消息失败，请重试") }
                        }
                    }
            },
            viewModelScope.launch {
                PresenceEngine.proactiveMessageFlow.collect { msg ->
                    if (msg.characterId == getCurrentCharacterId()) {
                        val charName = _uiState.value.character?.name ?: "她"
                        _uiState.update { it.copy(pendingProactiveMessage = "「${charName}」：${msg.text}") }
                    }
                }
            },
            viewModelScope.launch {
                db.relationshipDao()
                    .observeFrom("user")
                    .map { list -> list.firstOrNull { it.toId == characterId.toString() } }
                    .flowOn(Dispatchers.IO)
                    .collect { _relForHeader.value = it }
            },
            viewModelScope.launch {
                identityRepo.observeById(characterId)
                    .flowOn(Dispatchers.IO)
                    .collectLatest { entity ->
                        val url = entity?.avatarUrl?.takeIf { it.isNotBlank() } ?: return@collectLatest
                        _uiState.update { state ->
                            state.copy(character = state.character?.copy(avatarUrl = url))
                        }
                    }
            },
            backgroundManager.startObserving(),
        )
    }

    /** 一次性加载消息（供 clearMessages/exportConversation 等写入后刷新使用）。 */
    suspend fun loadMessages(characterId: Int) {
        try {
            val msgs = withContext(Dispatchers.IO) {
                messageRepo.getByCharacter(characterId)
            }
            _uiState.update {
                it.copy(messages = msgs
                    .filterNot { it.content.startsWith(FILE_READ_MARK_PREFIX) }
                    .map { ChatTagParser.toChatMessage(it) }.toImmutableList())
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            ZLog.e("ChatSessionDelegate", "characterId=$characterId 加载消息失败", e)
            _uiState.update { it.copy(error = "加载消息失败，请重试") }
        }
    }

    /** 供其他委托调用的非 suspend 刷新入口。 */
    fun reloadMessages(characterId: Int) {
        viewModelScope.launch { loadMessages(characterId) }
    }
}
