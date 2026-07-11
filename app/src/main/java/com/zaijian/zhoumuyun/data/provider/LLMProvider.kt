package com.zaijian.zhoumuyun.data.provider

import kotlinx.coroutines.flow.Flow

// ─────────────────────────────────────────────────────────────
//  Data models
// ─────────────────────────────────────────────────────────────

data class LLMMessage(
    val role: String,    // "user" | "assistant" | "system"
    val content: String,
)

data class LLMConfig(
    val model: String,
    val maxTokens: Int = 2000,
    val temperature: Float = 0.8f,
    val stream: Boolean = true,
)

// ─────────────────────────────────────────────────────────────
//  Provider interface
// ─────────────────────────────────────────────────────────────

/**
 * 统一 LLM 提供商接口。
 * 所有提供商（DeepSeek / 火山方舟 / 阿里云百炼 / 自定义）实现此接口。
 * 记忆和人设的连续性由本地 Memory Engine 保证，与选哪个 API 无关。
 */
interface LLMProvider {
    val id: String          // 唯一标识，如 "deepseek"
    val name: String        // 显示名称，如 "DeepSeek"

    /**
     * 流式输出（打字机效果）。
     * 每次 emit 一段增量文本（delta），调用方累积拼接。
     */
    suspend fun chat(
        messages: List<LLMMessage>,
        systemPrompt: String,
        config: LLMConfig,
    ): Flow<String>

    /**
     * 同步输出（整段返回）。
     * 用于后台任务、Memory 生成等无需流式的场景。
     */
    suspend fun chatSync(
        messages: List<LLMMessage>,
        systemPrompt: String,
        config: LLMConfig,
    ): String

    /** 测试连接，返回 true = API Key 有效且可达 */
    suspend fun testConnection(): Boolean
}

// ─────────────────────────────────────────────────────────────
//  Supported providers enum (for UI selection)
// ─────────────────────────────────────────────────────────────

enum class ProviderType(val displayName: String, val defaultModel: String) {
    DEEPSEEK(   "DeepSeek",       "deepseek-v4-flash"),
    VOLCENGINE( "火山方舟",       ""),
    ALIYUN(     "阿里云百炼",     "deepseek-v4-flash"),
    OPENCODEGO( "opencode go",    ""),
    CUSTOM(     "自定义",         ""),
}
