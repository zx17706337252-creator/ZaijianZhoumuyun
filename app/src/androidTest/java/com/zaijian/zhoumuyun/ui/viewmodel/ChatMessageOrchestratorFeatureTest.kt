package com.zaijian.zhoumuyun.ui.viewmodel

import android.content.Context
import com.zaijian.zhoumuyun.data.AppContainer
import com.zaijian.zhoumuyun.data.agent.ToolCallInterceptor
import com.zaijian.zhoumuyun.data.db.AppDatabase
import com.zaijian.zhoumuyun.data.db.entity.PregnancyQuestionType
import com.zaijian.zhoumuyun.data.db.entity.PrivateChatMessageEntity
import com.zaijian.zhoumuyun.data.db.entity.PrivateChatPairEntity
import com.zaijian.zhoumuyun.data.db.entity.PrivateChatSessionEntity
import com.zaijian.zhoumuyun.data.manager.DaughterCharacterGenerator
import com.zaijian.zhoumuyun.data.memory.MemoryEngine
import com.zaijian.zhoumuyun.data.model.CharacterConfig
import com.zaijian.zhoumuyun.data.model.PregnancyState
import com.zaijian.zhoumuyun.data.provider.ProviderManager
import com.zaijian.zhoumuyun.data.prompt.FakeLLMProvider
import com.zaijian.zhoumuyun.data.prompt.PromptOrchestrator
import com.zaijian.zhoumuyun.data.repository.AgentPlanRepository
import com.zaijian.zhoumuyun.data.repository.AgentStoreRepository
import com.zaijian.zhoumuyun.data.repository.ChainRunRepository
import com.zaijian.zhoumuyun.data.repository.CharacterTitleRelationRepository
import com.zaijian.zhoumuyun.data.repository.DaughterCharacterRepository
import com.zaijian.zhoumuyun.data.repository.EventRepository
import com.zaijian.zhoumuyun.data.repository.IdentityRepository
import com.zaijian.zhoumuyun.data.repository.LearningGoalRepository
import com.zaijian.zhoumuyun.data.repository.MemoryRepository
import com.zaijian.zhoumuyun.data.repository.MessageRepository
import com.zaijian.zhoumuyun.data.repository.PrivateChatPairRepository
import com.zaijian.zhoumuyun.data.repository.PrivateChatMessageRepository
import com.zaijian.zhoumuyun.data.repository.PregnancyRepository
import com.zaijian.zhoumuyun.data.repository.ProjectRepository
import com.zaijian.zhoumuyun.data.repository.SkillRepository
import com.zaijian.zhoumuyun.data.repository.TaskRepository
import com.zaijian.zhoumuyun.data.repository.WorkflowRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * 任务2（P0-3 特征化测试）——5 个场景。
 *
 * 方案 D（MockK companion-object mock + 手写 FakeLLMProvider）：
 * sendMessage 内 ProviderManager.instance / AppContainer.instance 是硬编码单例（无构造
 * 注入口），用 mockkObject 精准 mock；叶子 repo 是构造注入，用 relaxed mock + 定向 stub。
 * ToolCallInterceptor 空注册表走快路径，仅场景②需要 mock 其 isToolInFlight。
 */
class ChatMessageOrchestratorFeatureTest {

    @After
    fun tearDown() = unmockkAll()

    private fun buildProviderManager(provider: FakeLLMProvider): ProviderManager {
        val pm = mockk<ProviderManager>(relaxed = true)
        every { pm.activeProvider } returns provider
        return pm
    }

    /** 构造 ChatMessageOrchestrator（全 relaxed mock 叶子依赖，场景按需定向 stub）。 */
    private fun buildOrchestrator(
        provider: FakeLLMProvider,
        characterId: Int = 1,
        uiState: MutableStateFlow<ChatUiState> = MutableStateFlow(ChatUiState()),
        // 场景③：孕期 D3/D4 需要控制 pregnancyRepo.getPregnancy 返回的真实状态。
        pregnancyState: PregnancyState? = null,
        // 场景③：D3/D4 需要自定义 pregnancyDelegate 行为；null 时用默认（返回空 D3 结果）。
        pregnancyDelegate: PregnancyPromptDelegate? = null,
        // 场景③ D4：需要验证 daughterGenerator 被调用；null 时用默认 relaxed mock。
        daughterGenerator: DaughterCharacterGenerator? = null,
        // 场景④：ReplyGuard 越界需假扮识别命中（isPresetName 返回 true）；null 时用默认 relaxed mock。
        characterTitleRelationRepo: CharacterTitleRelationRepository? = null,
    ): ChatMessageOrchestrator {
        mockkObject(ProviderManager.Companion)
        every { ProviderManager.instance } returns buildProviderManager(provider)

        // 纯 JVM 单测无法构造真实 CharacterConfig（accentColor/breathColor 是 Android Color），
        // 且真实 buildSystemPrompt 会深读 character.identityConfig 各字段（沿真实路径会 NPE）。
        // 这里 mock 掉整个 PromptOrchestrator.buildSystemPrompt，把"完整流式流程"的验证聚焦在
        // 流式生成 → 收尾 → 落库 → UI 更新这一段（特征化测试对象），prompt 组装细节不在本场景范围。
        mockkObject(PromptOrchestrator)
        every {
            PromptOrchestrator.buildSystemPrompt(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(),
            )
        } returns ""

        // 真实 buildPregnancyPrompts 侧的回归风险：relaxed mock 返回的
        // PregnancyPromptResult.pregnancyState 是 null，会击穿 finalizeRound 的
        // 非空 PregnancyState 参数（NPE）。默认返回一个真实默认结果（空 D3），
        // 保证正常单轮走通；场景③ 传入自定义 delegate 覆盖此行为。
        val resolvedPregnancyDelegate = pregnancyDelegate ?: run {
            val d = mockk<PregnancyPromptDelegate>(relaxed = true)
            coEvery {
                d.buildPregnancyPrompts(any(), any(), any(), any(), any(), any())
            } returns PregnancyPromptResult(
                pregnancyState = PregnancyState(characterId = characterId),
                pregnancyTriggerPromptPatch = "",
                miscarriageAftermathPatch = "",
                failureContextPatch = "",
                routinePressurePatch = "",
                d3QuestionPatch = "",
                d3PendingAsk = null,
            )
            d
        }

        // 场景③：需要真实孕期状态时，让 pregnancyRepo.getPregnancy 返回该状态。
        val pregnancyRepo = mockk<PregnancyRepository>(relaxed = true)
        if (pregnancyState != null) {
            coEvery { pregnancyRepo.getPregnancy(any()) } returns pregnancyState
        }

        // 功能化的 replyJob 持有者：sendMessage 的 finally 用
        // `getReplyJob() === currentCoroutineContext()[Job]` 判断是否自己仍是当前 job，
        // 若 getReplyJob 恒返回 null（旧 no-op），该守卫失败 → isTyping 永不复位。
        var replyJobHolder: Job? = null
        val replyJobScope = CoroutineScope(Dispatchers.IO)

        return ChatMessageOrchestrator(
            _uiState                = uiState,
            _streamingContent       = MutableStateFlow(null),
            _streamingPsych         = MutableStateFlow(null),
            _streamingThinking      = MutableStateFlow(null),
            messageRepo             = mockk<MessageRepository>(relaxed = true),
            memoryRepo              = mockk<MemoryRepository>(relaxed = true),
            memoryEngine            = mockk<MemoryEngine>(relaxed = true),
            identityRepo            = mockk<IdentityRepository>(relaxed = true),
            relationshipEngine      = mockk(relaxed = true),
            presenceEngine          = mockk(relaxed = true),
            pregnancyRepo           = pregnancyRepo,
            characterStateRepo      = mockk(relaxed = true),
            daughterRepo            = mockk<DaughterCharacterRepository>(relaxed = true),
            agentPlanRepo           = mockk<AgentPlanRepository>(relaxed = true),
            learningGoalRepo        = mockk<LearningGoalRepository>(relaxed = true),
            skillRepo               = mockk<SkillRepository>(relaxed = true),
            taskRepo                = mockk<TaskRepository>(relaxed = true),
            projectRepo             = mockk<ProjectRepository>(relaxed = true),
            workflowRepo            = mockk<WorkflowRepository>(relaxed = true),
            chainRunRepository      = mockk<ChainRunRepository>(relaxed = true),
            eventRepo               = mockk<EventRepository>(relaxed = true),
            pregnancyDelegate       = resolvedPregnancyDelegate,
            agentRelationEngine     = mockk(relaxed = true),
            daughterGenerator       = daughterGenerator ?: mockk<DaughterCharacterGenerator>(relaxed = true),
            characterTitleRelationRepo = characterTitleRelationRepo ?: mockk<CharacterTitleRelationRepository>(relaxed = true),
            db                      = mockk<AppDatabase>(relaxed = true),
            getApplication          = { mockk<Context>(relaxed = true) },
            getCurrentCharacterId   = { characterId },
            getReplyJob             = { replyJobHolder },
            setReplyJob             = { replyJobHolder = it },
            getEvaluationEngine     = { null },
            pendingKeywordTriggerMap = ConcurrentHashMap(),
            lastFertileJudgeAtMap   = ConcurrentHashMap(),
            viewModelScope          = replyJobScope,
            loadMessages            = { },
        )
    }

    // ═══════════════════════════════════════════════════════════
    //  场景②：工具执行中重复点击发送被门控拦截
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `场景② 工具执行中重复点击发送被门控拦截`() = runTest {
        val uiState = MutableStateFlow(ChatUiState())
        val orchestrator = buildOrchestrator(
            provider = FakeLLMProvider(scriptedReply = "不应被调用"),
            characterId = 1,
            uiState = uiState,
        )

        // 工具执行中：isToolInFlight 返回 true，门控应拦下第二次发送。
        mockkObject(ToolCallInterceptor)
        coEvery { ToolCallInterceptor.isToolInFlight(any(), any()) } returns true

        orchestrator.sendMessage("你好")

        // 门控命中：不触发任何 LLM 调用，error 置为"上一个操作还在进行中"。
        assertEquals("上一个操作还在进行中，请稍候再发送", uiState.value.error)
        // 无新消息（未走到流式阶段）。
        assertNull(uiState.value.messages.lastOrNull()?.content)
    }

    // ═══════════════════════════════════════════════════════════
    //  场景①：正常单轮·完整流式流程
    //  （provider 被真实调用 → TextDelta 累积 → 标签剥离 → 落库 → 乐观更新到 UI）
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `场景① 正常单轮 完整流式流程走通并落库`() = runBlocking {
        val uiState = MutableStateFlow(ChatUiState())
        // 真实 CharacterConfig 需 Android Color，纯 JVM 无法构造；relaxed mock 足够——
        // 本场景 buildSystemPrompt 已被 mock 掉，character 仅需非空以通过 loadCharacter 校验。
        uiState.update { it.copy(character = mockk<CharacterConfig>(relaxed = true)) }

        val orchestrator = buildOrchestrator(
            provider = FakeLLMProvider(scriptedChunks = listOf("你好呀")),
            characterId = 1,
            uiState = uiState,
        )

        orchestrator.sendMessage("你好")

        // sendMessage 内部在 viewModelScope(Dispatchers.IO) 异步执行，轮询等待回复登记到 UI。
        withTimeout(5000) {
            while (uiState.value.messages.none { it.content == "你好呀" }) {
                delay(10)
            }
        }

        // 单轮完整产物：助手回复已落库并乐观更新到内存列表。
        val reply = uiState.value.messages.last()
        assertEquals("assistant", reply.role)
        assertEquals("你好呀", reply.content)
        // 正常收尾：无错误、isTyping 复位。
        assertNull(uiState.value.error)
        assertEquals(false, uiState.value.isTyping)
    }

    // ═══════════════════════════════════════════════════════════
    //  场景③：孕期 D3 槎位问答 + D4 女儿生成触发
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `场景③ 孕期 D3 槎位问答 注入与透传后置分析`() = runBlocking {
        val uiState = MutableStateFlow(ChatUiState())
        uiState.update { it.copy(character = mockk<CharacterConfig>(relaxed = true)) }

        val d3Pending = PregnancyQuestionType.PERSONA to 1
        val d3Patch = "D3问题：你希望女儿是什么性格？"
        val pregnantState = PregnancyState(characterId = 1, isPregnant = true)
        val pregnancyDelegate = mockk<PregnancyPromptDelegate>(relaxed = true)
        coEvery {
            pregnancyDelegate.buildPregnancyPrompts(any(), any(), any(), any(), any(), any())
        } returns PregnancyPromptResult(
            pregnancyState = pregnantState,
            pregnancyTriggerPromptPatch = "",
            miscarriageAftermathPatch = "",
            failureContextPatch = "",
            routinePressurePatch = "",
            d3QuestionPatch = d3Patch,
            d3PendingAsk = d3Pending,
        )

        val orchestrator = buildOrchestrator(
            provider = FakeLLMProvider(scriptedChunks = listOf("好的呀")),
            characterId = 1,
            uiState = uiState,
            pregnancyState = pregnantState,
            pregnancyDelegate = pregnancyDelegate,
        )

        orchestrator.sendMessage("你好")

        withTimeout(5000) {
            while (uiState.value.messages.none { it.content == "好的呀" }) { delay(10) }
        }

        // D3：真实孕期状态被读取并传给组装（buildPregnancyPrompts 在流式前同步调用）。
        coVerify {
            pregnancyDelegate.buildPregnancyPrompts(1, "你好", pregnantState, any(), any(), any())
        }
        // D3：d3QuestionPatch / d3PendingAsk 透传到后置分析 runPostReplyAnalysis
        //（finalizeRound 内异步 launch，用 timeout 等待真实异步完成）。
        coVerify(timeout = 5000) {
            pregnancyDelegate.runPostReplyAnalysis(
                characterId = 1,
                aiReply = "好的呀",
                userText = "你好",
                pregnancyState = pregnantState,
                d3Pending = d3Pending,
                d3Patch = d3Patch,
                pendingKeywordTriggerMap = any(),
                lastFertileJudgeAtMap = any(),
                recentMessages = any(),
                character = any(),
                onTriggerD4Generation = any(),
                onFertileWindowConsentDialog = any(),
            )
        }
    }

    @Test
    fun `场景③ 孕期 D4 女儿生成触发`() = runBlocking {
        val uiState = MutableStateFlow(ChatUiState())
        uiState.update { it.copy(character = mockk<CharacterConfig>(relaxed = true)) }

        // 捕获 D4 触发回调：buildPregnancyPrompts 收到的 onTriggerD4Generation 存下，
        // 模拟 D3 全锁后 D4 触发，验证 daughterGenerator.generateForMother 被真实调用。
        val d4Callback = slot<suspend (Map<String, String>) -> Unit>()
        val pregnancyDelegate = mockk<PregnancyPromptDelegate>(relaxed = true)
        coEvery {
            pregnancyDelegate.buildPregnancyPrompts(any(), any(), any(), any(), any(), capture(d4Callback))
        } returns PregnancyPromptResult(
            pregnancyState = PregnancyState(characterId = 1, isPregnant = true),
            pregnancyTriggerPromptPatch = "",
            miscarriageAftermathPatch = "",
            failureContextPatch = "",
            routinePressurePatch = "",
            d3QuestionPatch = "",
            d3PendingAsk = null,
        )
        val daughterGenerator = mockk<DaughterCharacterGenerator>(relaxed = true)

        val orchestrator = buildOrchestrator(
            provider = FakeLLMProvider(scriptedChunks = listOf("好的呀")),
            characterId = 1,
            uiState = uiState,
            pregnancyState = PregnancyState(characterId = 1, isPregnant = true),
            pregnancyDelegate = pregnancyDelegate,
            daughterGenerator = daughterGenerator,
        )

        orchestrator.sendMessage("你好")

        withTimeout(5000) {
            while (uiState.value.messages.none { it.content == "好的呀" }) { delay(10) }
        }

        // 模拟 D4 触发：D3 全锁后调用 onTriggerD4Generation。
        val callback = d4Callback.captured ?: error("onTriggerD4Generation 未被捕获")
        callback(mapOf("name" to "小月"))

        // D4 生成器被调用（daughterGenerator 内部是异步 launch，timeout 等待）。
        coVerify(timeout = 5000) {
            daughterGenerator.generateForMother(any(), any())
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  场景④：ReplyGuard 越界检测——非 owner 发言 + 越界回复 → 兜底模板替换
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `场景④ ReplyGuard 越界 兜底替换候选回复`() = runBlocking {
        val uiState = MutableStateFlow(ChatUiState())
        // 假扮识别命中需要 character.name 供兜底模板引用。
        val character = mockk<CharacterConfig>(relaxed = true).also { every { it.name } returns "露娜" }
        // 预置假扮状态：speakerContext 直接推导为 NON_OWNER，绕过 resolveImpersonationContext 的
        // 持久化/查询副作用（纯 JVM 下也不稳），聚焦 ReplyGuard 越界检测本身。
        uiState.update { it.copy(character = character, impersonationByCharacter = mapOf(1 to "露娜")) }

        // chatStream 产出候选回复（越界内容）；chatSync 返回 "true"（分类器判定越界）。
        val orchestrator = buildOrchestrator(
            provider = FakeLLMProvider(scriptedChunks = listOf("我们今晚在一起吧"), scriptedReply = "true"),
            characterId = 1,
            uiState = uiState,
        )

        orchestrator.sendMessage("你好")

        // 等待协程完成（isTyping 复位）。
        withTimeout(5000) {
            while (uiState.value.isTyping) { delay(10) }
        }

        // 越界候选回复被兜底替换，未展示。
        // 注：兜底模板 "（露娜 像是回过神来…）" 外层是中文全角括号，会被 stripPsychText
        // （PSYCH_TAG_REGEX 匹配全角括号）整段剥离为 "" → 无消息落库。这是生产代码真实行为
        // （非测试引入），特征化如实记录：ReplyGuard 兜底回复在 stripPsychText 后被吞掉。
        assertTrue("越界候选回复不应展示，实际: ${uiState.value.messages.map { it.content }}",
            uiState.value.messages.none { it.content.contains("我们今晚在一起吧") })
        assertEquals(false, uiState.value.isTyping)
    }

    // ═══════════════════════════════════════════════════════════
    //  场景⑤：未读私聊播报——查询未读会话 → 生成 recap → markNotified 标记已告知
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `场景⑤ 未读私聊 播报并标记已告知`() = runBlocking {
        val uiState = MutableStateFlow(ChatUiState())
        uiState.update { it.copy(character = mockk<CharacterConfig>(relaxed = true)) }

        // gatherPrivateChatRecap 走 AppContainer.instance（第二个硬单例），mockkObject 精准 mock。
        val appContainer = mockk<AppContainer>(relaxed = true)
        mockkObject(AppContainer.Companion)
        every { AppContainer.instance } returns appContainer

        val session = PrivateChatSessionEntity(
            sessionId = "s1", pairId = "p1", startedAt = 0L, status = "completed", turnCount = 1,
        )
        coEvery { appContainer.privateChatSessionRepo.getUnnotifiedByCharacter(1, 2) } returns listOf(session)
        coEvery { appContainer.privateChatPairRepo.get("p1") } returns PrivateChatPairEntity(
            pairId = "p1", characterIdA = 1, characterIdB = 2, usedTodayResetAt = 0L,
        )
        coEvery { appContainer.privateChatMessageRepo.getRecentBySession("s1", 2) } returns listOf(
            PrivateChatMessageEntity(
                pairId = "p1", senderCharacterId = 2, content = "你好呀",
                timestamp = 0L, sessionId = "s1", turnIndexInSession = 0, triggerSource = "",
            ),
        )

        val orchestrator = buildOrchestrator(
            provider = FakeLLMProvider(scriptedChunks = listOf("收到")),
            characterId = 1,
            uiState = uiState,
        )

        orchestrator.sendMessage("你好")

        withTimeout(5000) {
            while (uiState.value.messages.none { it.content == "收到" }) { delay(10) }
        }

        // 未读私聊被查询并播报（进入 systemPrompt 的 workflowRecapPatch 槽位，本测试 buildSystemPrompt 已 mock）。
        coVerify { appContainer.privateChatSessionRepo.getUnnotifiedByCharacter(1, 2) }
        // 播报即标记已告知（幂等：同一会话后续轮次不再重复播报）。
        coVerify { appContainer.privateChatSessionRepo.markNotified("s1", 1) }
    }
}