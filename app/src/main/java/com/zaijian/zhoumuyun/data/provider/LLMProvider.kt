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
//  HTTP exception (P1-03)
// ─────────────────────────────────────────────────────────────

/**
 * LLM HTTP 层错误异常，携带 HTTP 状态码。
 *
 * P1-03 修复：原 [chatSyncWithRetry] 对所有异常一视同仁地重试，
 * 导致 401（API Key 无效）、400（请求格式错误）、403（权限不足）等
 * 不可重试错误也被浪费重试次数和退避等待时间。
 *
 * 引入本异常后，[chatSyncWithRetry] 可根据 [isRetryable] 判断：
 *   - 429（限流）、5xx（服务端错误）→ 重试
 *   - 400/401/403/404 等其他 4xx   → 立即抛出，不重试
 *
 * 继承 [IllegalStateException]，保证已有的 `catch (e: IllegalStateException)`
 * 代码路径仍然兼容。
 */
class LLMHttpException(
    val statusCode: Int,
    responseBody: String,
    cause: Throwable? = null,
) : IllegalStateException("API 错误 (HTTP $statusCode)：$responseBody", cause) {

    /** 是否为可重试的 HTTP 状态码：429（限流）和 5xx（服务端错误） */
    val isRetryable: Boolean
        get() = statusCode == 429 || statusCode >= 500
}

// ─────────────────────────────────────────────────────────────
//  Retry extension
// ─────────────────────────────────────────────────────────────

/**
 * LLMProvider 的指数退避重试扩展函数（S3 修复 / P1-03 修复）。
 * 遇到 429 限流或 5xx 瞬时错误时，自动重试一次，成本极低。
 * 主流式调用（chat）不适合重试（打字机效果会重置），仅供 chatSync 场景使用。
 *
 * (S-6) 从 OpenAICompatProvider.kt 迁至此处：本函数是 LLMProvider 接口的通用扩展，
 * 与具体实现类 OpenAICompatProvider 无关，理应与接口定义放在一起。
 * 迁移前后均在 data.provider 包下，调用方 import 路径不变。
 *
 * P1-03 修复：新增 [LLMHttpException] 分支，对不可重试的 HTTP 错误
 * （400/401/403/404 等）立即抛出，不再浪费重试次数和退避等待。
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
        } catch (e: LLMHttpException) {
            // P1-03 修复：不可重试的 HTTP 错误（4xx 除 429）立即抛出，
            // 不浪费重试次数和退避等待时间。只有 429（限流）和 5xx（服务端错误）才重试。
            if (!e.isRetryable) throw e
            lastError = e
            if (attempt < maxAttempts - 1) {
                kotlinx.coroutines.delay(1000L * (1L shl attempt))
            }
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
