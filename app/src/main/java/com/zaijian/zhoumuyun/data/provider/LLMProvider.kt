package com.zaijian.zhoumuyun.data.provider

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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
//  Streaming chunk (P0-5)
// ─────────────────────────────────────────────────────────────

/**
 * 流式输出的单个块。P0-5 修复引入。
 *
 * 原先 [LLMProvider.chat] 只返回 `Flow<String>`，无法携带 SSE 流末尾的
 * `finish_reason` 元数据。OpenAI 协议在最后一个 chunk 中通过 `finish_reason`
 * 标识结束原因：`stop`（正常）、`length`（maxTokens 截断）、`content_filter`
 * （被过滤）。不读取这个字段，截断和正常结束完全无法区分——是"文档发送
 * 失败但无任何报错记录"的协议层根因之一。
 *
 * [ChatStreamItem.TextDelta] 携带增量文本（与原 Flow<String> 的每个 emit 对应），
 * [ChatStreamItem.FinishReason] 在流结束时携带 finish_reason（可能为 null，
 * 表示提供商未返回或流异常中断）。
 */
sealed class ChatStreamItem {
    /** LLM 输出的增量文本 */
    data class TextDelta(val text: String) : ChatStreamItem()

    /**
     * 流结束信号，携带 finish_reason。
     * - "stop"：正常结束
     * - "length"：达到 maxTokens 被截断
     * - "content_filter"：被内容过滤
     * - null：提供商未返回 finish_reason（流异常中断等）
     */
    data class FinishReason(val reason: String?) : ChatStreamItem()
}

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
     * 带元数据的流式输出（P0-5 修复）。
     *
     * 与 [chat] 的差异：返回 [ChatStreamItem] 而非 `String`，在流结束时
     * 额外 emit 一个 [ChatStreamItem.FinishReason] 携带 `finish_reason`。
     * 调用方（[com.zaijian.zhoumuyun.data.agent.ToolCallInterceptor]）据此
     * 判断是否被 maxTokens 截断，并注入续写/自查指令。
     *
     * 默认实现包装 [chat]，不 emit FinishReason（向后兼容不支持 finish_reason
     * 的提供商——当前只有 [OpenAICompatProvider] 覆写此方法）。
     */
    suspend fun chatStream(
        messages: List<LLMMessage>,
        systemPrompt: String,
        config: LLMConfig,
    ): Flow<ChatStreamItem> =
        chat(messages, systemPrompt, config).map { ChatStreamItem.TextDelta(it) }

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
        } catch (e: Throwable) {
            lastError = e
            // 指数退避：1s, 2s, 4s, 8s...
            if (attempt < maxAttempts - 1) {
                kotlinx.coroutines.delay(1000L * (1L shl attempt))
            }
        }
    }
    throw lastError ?: IllegalStateException("chatSyncWithRetry: unreachable")
}
