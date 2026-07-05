package com.zaijian.zhoumuyun.data.provider

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * OpenAI 兼容协议的通用 LLM 提供商实现。
 *
 * 支持：DeepSeek / 火山方舟 / 阿里云百炼 / 自定义 Base URL
 * 协议：OpenAI Chat Completions API（/v1/chat/completions）
 *
 * 使用原生 HttpURLConnection，无需额外依赖。
 * 流式输出解析 SSE（Server-Sent Events）格式。
 *
 * ⚠️ 流式修复（Phase 7）：
 *   原实现在 flow {} 内用 withContext(IO) 阻塞读取再把 delta 缓冲到
 *   list 最后统一 emit，打字机效果失效。
 *   现改为 callbackFlow：IO 读取线程直接 trySend(delta) 到 Channel，
 *   collect 端实时收到每个 token，真正实现逐字打字机效果。
 */
class OpenAICompatProvider(
    override val id: String,
    override val name: String,
    private val baseUrl: String,       // 如 "https://api.deepseek.com"
    private val apiKey: String,
    private val defaultModel: String,
) : LLMProvider {

    override suspend fun chat(
        messages: List<LLMMessage>,
        systemPrompt: String,
        config: LLMConfig,
    ): Flow<String> = callbackFlow {
        // 用一个后台 Job 做阻塞 IO 读取，awaitClose 负责取消它。
        // 这样 callbackFlow 生命周期与 IO 线程完全对齐：
        //   - 读取正常结束 → channel.close()
        //   - 下游取消收集 → awaitClose 的 lambda 取消 job → 断开连接
        val body = buildRequestBody(messages, systemPrompt, config.copy(stream = true))
        // prepareConnection() 只配置参数，不触发网络 IO，可在任何线程安全调用
        val conn = prepareConnection()

        val job = launch(Dispatchers.IO) {
            try {
                // L7 修复：加整体超时上限。readTimeout=60s 只限制单次 readLine() 的等待，
                // 对慢速流式输出（每行都在 60s 内到达但总时长无限延长）无效。
                // withTimeout 从整个流开始计时，保证单次 chat 调用总时长不超过 CHAT_TOTAL_TIMEOUT_MS。
                withTimeout(CHAT_TOTAL_TIMEOUT_MS) {
                // writeBodyAndConnect 是阻塞 IO，必须在此 IO 协程里执行
                conn.writeBodyAndConnect(body)
                val responseCode = conn.responseCode
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    val error = conn.errorStream?.bufferedReader()?.readText()
                        ?: "HTTP $responseCode"
                    close(IllegalStateException("API 错误：$error"))
                    return@withTimeout
                }
                // 实时解析 SSE 流，每个 delta 立即 trySend
                // reasoning_content / thinking 在推理阶段先累积，
                // 首次出现 content 时一次性 flush，之后 content 正常逐 chunk 流式 emit
                val reasoningAccumulated = StringBuilder()
                var reasoningFlushed = false
                BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { reader ->
                    while (true) {
                        // L-2 修复：原先用 var line: String? + line!! 强制断言，
                        // 虽然 while 条件保证循环体内非 null，但断言本身是多余风险点。
                        // 改为局部 val currentLine，readLine() 返回 null 时立即 break，
                        // 循环体内 currentLine 是编译期保证的非空 String，无需断言。
                        val currentLine = reader.readLine() ?: break
                        // Fix-7-1：readLine() 是阻塞 JVM 调用，不检查协程取消标志。
                        // withTimeout 发出取消信号后，下一次 ensureActive() 才真正抛出
                        // CancellationException 跳出循环，使超时机制生效。
                        ensureActive()
                        // SSE 规范允许 data: 无空格，统一处理
                        val data = currentLine.let { l ->
                            if (l.startsWith("data:")) l.removePrefix("data:").trim() else ""
                        }
                        if (data == "[DONE]" || data.isEmpty()) continue
                        try {
                            val deltaObj = JSONObject(data)
                                .getJSONArray("choices")
                                .getJSONObject(0)
                                .getJSONObject("delta")
                            // 读取 content（主输出）
                            val contentDelta = if (deltaObj.isNull("content")) ""
                                        else deltaObj.optString("content", "")
                            // 读取 reasoning_content / thinking（推理模型的思考过程）
                            // DeepSeek-R1 用 reasoning_content，Qwen-QwQ 用 thinking
                            val reasoningDelta = if (deltaObj.isNull("reasoning_content")) ""
                                        else deltaObj.optString("reasoning_content", "")
                            val thinkingDelta = if (deltaObj.has("thinking") && !deltaObj.isNull("thinking"))
                                        deltaObj.optString("thinking", "") else ""
                            // 推理阶段：累积 reasoning/thinking，不立即 emit
                            if (reasoningDelta.isNotEmpty()) reasoningAccumulated.append(reasoningDelta)
                            if (thinkingDelta.isNotEmpty())  reasoningAccumulated.append(thinkingDelta)
                            // 正文阶段：首次出现 contentDelta 时先 flush 全部推理内容，
                            // 确保展示顺序为「完整推理块 → 流式正文」
                            if (contentDelta.isNotEmpty()) {
                                if (!reasoningFlushed && reasoningAccumulated.isNotEmpty()) {
                                    trySend(reasoningAccumulated.toString())
                                    reasoningFlushed = true
                                }
                                trySend(contentDelta)
                            }
                        } catch (_: Exception) { /* 跳过解析失败的行 */ }
                    }
                }
                close()   // 正常结束：关闭 channel，collect 端会收到完成信号
                } // end withTimeout
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                close(IllegalStateException("LLM 流式响应超时（超过 ${CHAT_TOTAL_TIMEOUT_MS / 1000}s）"))
            } catch (e: Exception) {
                close(e)  // 异常结束：把错误传给 collect 端
            } finally {
                conn.disconnect()
            }
        }

        // 当 collect 端取消时，取消 IO job 并立即断开连接
        // job.cancel() 只设置取消标志，readLine() 是阻塞 JVM 调用不检查取消，
        // 因此必须在 awaitClose 中主动 disconnect 释放连接和 IO 线程
        awaitClose {
            job.cancel()
            try { conn.disconnect() } catch (_: Exception) {}
        }
    }

    override suspend fun chatSync(
        messages: List<LLMMessage>,
        systemPrompt: String,
        config: LLMConfig,
    ): String = withContext(Dispatchers.IO) {
        val body = buildRequestBody(messages, systemPrompt, config.copy(stream = false))
        val conn = prepareConnection()
        try {
            conn.writeBodyAndConnect(body)
            val responseCode = conn.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                val error = conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $responseCode"
                throw IllegalStateException("API 错误：$error")
            }
            val responseText = conn.inputStream.bufferedReader().readText()
            JSONObject(responseText)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        } finally {
            conn.disconnect()
        }
    }

    override suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) {
        // defaultModel 为空说明该平台需要用户手动填入模型名（如火山方舟 Endpoint ID），直接返回失败
        if (defaultModel.isEmpty()) return@withContext false
        try {
            chatSync(
                messages = listOf(LLMMessage("user", "Hi")),
                systemPrompt = "Reply with one word.",
                config = LLMConfig(model = defaultModel, maxTokens = 10, stream = false),
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    // ── 内部工具 ─────────────────────────────────────────────

    private fun buildRequestBody(
        messages: List<LLMMessage>,
        systemPrompt: String,
        config: LLMConfig,
    ): String {
        val messagesArr = JSONArray()
        if (systemPrompt.isNotEmpty()) {
            messagesArr.put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })
        }
        // 过滤非法 role：只允许 "user" / "assistant" 进入 messages 列表。
        // system prompt 已通过 systemPrompt 参数单独注入到列表首位，
        // 若 messages 中混入 role="system" 的条目（如历史记录污染），
        // 部分供应商（火山方舟、阿里云百炼等）会返回 400 错误。
        messages
            .filter { it.role == "user" || it.role == "assistant" }
            .forEach { msg ->
                messagesArr.put(JSONObject().apply {
                    put("role", msg.role)
                    put("content", msg.content)
                })
            }
        return JSONObject().apply {
            put("model", config.model.ifEmpty { defaultModel })
            put("messages", messagesArr)
            put("max_tokens", config.maxTokens)
            put("temperature", config.temperature.toDouble())
            put("stream", config.stream)
        }.toString()
    }

    /**
     * 只配置连接参数，不触发任何 IO（不写 body，不 connect）。
     * 实际的 body 写入和 connect() 必须在 IO 线程调用。
     */
    private fun prepareConnection(): HttpURLConnection {
        // P2 修复：归一化 baseUrl 末尾斜杠，避免自定义 URL 以 "/" 结尾时拼接出双斜杠（//chat/completions）
        val normalizedBaseUrl = baseUrl.trimEnd('/')
        val url = URL("$normalizedBaseUrl/chat/completions")
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Accept", "text/event-stream")
            connectTimeout = 15_000
            readTimeout    = 60_000
            doOutput       = true
            // 注意：不在此处写 body、不 connect，留给 IO 线程执行
        }
    }

    /** 在 IO 线程调用：写入 request body，然后触发 connect。*/
    private fun HttpURLConnection.writeBodyAndConnect(body: String) {
        outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        // outputStream.close() 已由 use 处理，HttpURLConnection 会在 getResponseCode() 时自动 connect
    }

    companion object {
        /** 单次 chat() 流式调用的总时长上限（5 分钟）。
         * readTimeout 只限制单次 readLine() 阻塞，对持续慢输出无效；
         * 此常量通过 withTimeout 从整个流开始计时，兜底防止永久挂起。
         */
        const val CHAT_TOTAL_TIMEOUT_MS = 5 * 60 * 1000L
        fun deepSeek(apiKey: String) = OpenAICompatProvider(
            id           = "deepseek",
            name         = "DeepSeek",
            baseUrl      = "https://api.deepseek.com/v1",
            apiKey       = apiKey,
            defaultModel = "deepseek-v4-flash",  // DeepSeek V4 Flash（2026.04.24 起，deepseek-chat 将于 2026.07.24 废弃）
        )

        fun volcEngine(apiKey: String, modelId: String = "") = OpenAICompatProvider(
            id           = "volcengine",
            name         = "火山方舟",
            // 火山方舟 OpenAI 兼容端点路径为 /api/v3
            baseUrl      = "https://ark.cn-beijing.volces.com/api/v3",
            apiKey       = apiKey,
            // 火山方舟必须使用推理接入点 ID（格式：ep-xxxxxxxx-xxxxx），由用户填入
            defaultModel = modelId,
        )

        fun aliyun(apiKey: String) = OpenAICompatProvider(
            id           = "aliyun",
            name         = "阿里云百炼",
            baseUrl      = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            apiKey       = apiKey,
            defaultModel = "deepseek-v4-flash",  // 阿里云百炼 DeepSeek V4 Flash 模型名
        )

        fun opencodeGo(apiKey: String, modelId: String = "") = OpenAICompatProvider(
            id           = "opencodego",
            name         = "opencode go",
            // opencode.ai 端点路径待平台确认，当前保留原配置
            baseUrl      = "https://opencode.ai/zen/go/v1",
            apiKey       = apiKey,
            // opencode go 模型名待平台确认，由用户填写
            defaultModel = modelId,
        )

        fun custom(baseUrl: String, apiKey: String, model: String) = OpenAICompatProvider(
            id           = "custom",
            name         = "自定义",
            baseUrl      = baseUrl,
            apiKey       = apiKey,
            defaultModel = model,
        )
    }
}

/**
 * LLMProvider 的指数退避重试扩展函数（S3 修复）。
 * 遇到 429 限流或 5xx 瞬时错误时，自动重试一次，成本极低。
 * 主流式调用（chat）不适合重试（打字机效果会重置），仅供 chatSync 场景使用。
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
