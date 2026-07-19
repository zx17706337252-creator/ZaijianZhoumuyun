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

// ─────────────────────────────────────────────────────────────
//  Retry extension
// ─────────────────────────────────────────────────────────────

/**
 * LLMProvider 的指数退避重试扩展函数（S3 修复）。
 * 遇到 429 限流或 5xx 瞬时错误时，自动重试一次，成本极低。
 * 主流式调用（chat）不适合重试（打字机效果会重置），仅供 chatSync 场景使用。
 *
 * (S-6) 从 OpenAICompatProvider.kt 迁至此处：本函数是 LLMProvider 接口的通用扩展，
 * 与具体实现类 OpenAICompatProvider 无关，理应与接口定义放在一起。
 * 迁移前后均在 data.provider 包下，调用方 import 路径不变。
 */
suspend fun LLMProvider.chatSyncWithRetry(
    messages: List<LLMMessage>,
    systemPrompt: String,
    config: LLMConfig,
    maxAttempts: Int = 2,
): String {
    if (maxAttempts <= 0) throw IllegalArgumentException("maxAttempts must be > 0")
    var lastError: Exception? = null
    repeat(maxAttempts) { attempt ->
        try {
            return chatSync(messages, systemPrompt, config)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e  // 不吞 CancellationException
        } catch (e: Exception) {
            lastError = e
            // 指数退避：1s, 2s, 4s, 8s...
            if (attempt < maxAttempts - 1) {
                kotlinx.coroutines.delay(1000L * (1L shl attempt))
            }
        }
    }
    throw lastError ?: IllegalStateException("chatSyncWithRetry: unreachable")
}
