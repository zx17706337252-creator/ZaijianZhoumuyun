package com.zaijian.zhoumuyun.data.prompt

import com.zaijian.zhoumuyun.data.provider.ChatStreamItem
import com.zaijian.zhoumuyun.data.provider.LLMConfig
import com.zaijian.zhoumuyun.data.provider.LLMMessage
import com.zaijian.zhoumuyun.data.provider.LLMProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * 任务2（P0-3 特征化测试）手写 Fake LLM Provider。
 *
 * 仿 `data/agent/FakeChainRunRepository.kt` 风格，不依赖 MockK——精确控制回复内容，
 * 是 5 个特征化场景的行为核心。每个场景注入不同的 scriptedReply / scriptedChunks。
 *
 * 只实现 [LLMProvider] 接口的 4 个方法 + 2 个属性；`chatSyncWithRetry` 是接口上的
 * 顶层扩展函数，内部调 [chatSync]，因此只要本 Fake 的 [chatSync] 返回预期值，重试
 * 逻辑自动可测。
 */
class FakeLLMProvider(
    private val scriptedReply: String = "（假回复）",
    private val scriptedChunks: List<String> = listOf("（假回复）"),
    override val id: String = "fake",
    override val name: String = "FakeProvider",
    private val connectionResult: Boolean = true,
) : LLMProvider {

    override suspend fun chat(
        messages: List<LLMMessage>,
        systemPrompt: String,
        config: LLMConfig,
    ): Flow<String> = flow {
        scriptedChunks.forEach { emit(it) }
    }

    override suspend fun chatStream(
        messages: List<LLMMessage>,
        systemPrompt: String,
        config: LLMConfig,
    ): Flow<ChatStreamItem> = flow {
        scriptedChunks.forEach { emit(ChatStreamItem.TextDelta(it)) }
        emit(ChatStreamItem.FinishReason("stop"))
    }

    override suspend fun chatSync(
        messages: List<LLMMessage>,
        systemPrompt: String,
        config: LLMConfig,
    ): String = scriptedReply

    override suspend fun testConnection(): Boolean = connectionResult
}