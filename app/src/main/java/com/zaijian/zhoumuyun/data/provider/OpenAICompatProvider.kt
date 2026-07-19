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
import com.zaijian.zhoumuyun.util.ZLog
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
        //   - 读取正常结束 → close()，下方 job 内部会先 flush 未发送的推理内容
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
                        } catch (e: Exception) {
                            // 跳过解析失败的行；debug 级别避免流式场景下高频刷屏
                            ZLog.d("OpenAICompatProvider", "SSE 行解析失败，已跳过: ${e.message}")
                        }
                    }
                }
                // P3-25 修复：流结束时 flush 未发送的推理内容。
                // 部分模型（如 DeepSeek-R1）可能只输出 reasoning_content 而无
                // content delta，原逻辑仅在 contentDelta 非空时 flush，
                // 导致纯推理输出内容永久丢失。
                if (!reasoningFlushed && reasoningAccumulated.isNotEmpty()) {
                    trySend(reasoningAccumulated.toString())
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
            // 审查项 2.19：补充日志。此处属于收尾清理（连接可能已被 job 内部关闭），
            // 用 debug 级别避免正常场景下产生噪音，仅用于排查异常关闭路径。
            try { conn.disconnect() } catch (e: Exception) {
                ZLog.d("OpenAICompatProvider", "awaitClose 断开连接异常（可能已关闭）: ${e.message}")
            }
        }
    }

    override suspend fun chatSync(
        messages: List<LLMMessage>,
        systemPrompt: String,
        config: LLMConfig,
    ): String = withContext(Dispatchers.IO) {
        // 方案 5-2：withTimeout 保护，防止 httpClient 的 readTimeout 失效时
        // HTTP 连接无限阻塞，导致调用方协程永不返回。
        withTimeout(CHAT_TOTAL_TIMEOUT_MS) {
            val body = buildRequestBody(messages, systemPrompt, config.copy(stream = false))
            val conn = prepareConnection()
            try {
                conn.writeBodyAndConnect(body)
                val responseCode = conn.responseCode
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    // 方案 5-2：错误响应体截断到 500 字符，防止供应商返回超大
                    // HTML 错误页面（如 Nginx 502/504）撑爆日志和异常信息。
                    val error = conn.errorStream?.bufferedReader()?.let { reader ->
                        val raw = reader.readText()
                        if (raw.length > 500) raw.take(500) + "…[truncated]" else raw
                    } ?: "HTTP $responseCode"
                    throw IllegalStateException("API 错误：$error")
                }
                val responseText = conn.inputStream.bufferedReader().readText()
                // W13 问题2修复：HTTP 200 但响应体格式异常（反代返回 HTML、网关包装格式
                // 不标准、choices 为空数组等）时，原先直接抛出无上下文的 JSONException，
                // chatSyncWithRetry 重试耗尽后调用方只能看到"No value for choices"这类
                // 技术性报错。改为捕获后包装成带响应体摘要的错误，与上面第 161-166 行
                // 非 200 路径的截断策略保持一致。
                try {
                    JSONObject(responseText)
                        .getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")
                } catch (e: Exception) {
                    val preview = if (responseText.length > 200) responseText.take(200) + "…" else responseText
                    throw IllegalStateException("API 返回了非预期的响应格式：$preview", e)
                }
            } finally {
                conn.disconnect()
            }
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
            readTimeout    = 120_000
            // 方案 8-2：readTimeout 提升至 120s，适配慢速 LLM 流式输出（如 DeepSeek 冷启动）。
            // 目前仍使用 HttpURLConnection 以保持零外部依赖；
            // 后续若引入 OkHttp，可改用 OkHttpClient 的 connectTimeout / readTimeout /
            // callTimeout 三层超时 + 自动重试，彻底消除 HttpURLConnection 的隐式连接池问题。
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

// chatSyncWithRetry（S3 修复）已迁出至 LLMProvider.kt（S-6），
// 该函数是 LLMProvider 接口的通用扩展，与本文件的具体实现类 OpenAICompatProvider 无关，
// 迁移后仍在同一 data.provider 包下，调用方 import 路径不变。
