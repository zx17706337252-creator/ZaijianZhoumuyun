package com.zaijian.zhoumuyun.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import com.zaijian.zhoumuyun.BuildConfig
import com.zaijian.zhoumuyun.util.ZLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Phase 29 · 极简 Supabase REST 客户端
 *
 * 只实现 Phase 29 需要的两个能力：
 *   1. 写入本地任务到云端（upsert scheduled_jobs）
 *   2. 拉取未读的云端执行结果（select job_results）
 *
 *   SUPABASE_URL = "https://你的项目ID.supabase.co"
 */
object SupabaseClient {

    // ── 在这里填入你的 Supabase 项目信息 ──────────────────────
    // S3问题3修复：与 ANON_KEY 一致，从 BuildConfig 注入，支持切换实例
    private val SUPABASE_URL: String get() = BuildConfig.SUPABASE_URL
    // 方案 8-12：ANON_KEY 从 BuildConfig 注入，不再硬编码。
    // 开发环境：在 local.properties 中配置 SUPABASE_ANON_KEY，构建时自动注入 BuildConfig。
    // CI 环境：通过 -PSUPABASE_ANON_KEY=xxx 或环境变量传入。
    private val ANON_KEY: String get() = BuildConfig.SUPABASE_ANON_KEY
    // ─────────────────────────────────────────────────────────

    private const val TIMEOUT_MS = 15_000
    // P2-2 修复：整体超时兜底。connectTimeout/readTimeout 仅限制单次
    // connect/read 等待，对慢速持续输出（每个字节都在 readTimeout 内到达
    // 但总时长无限延长）不生效。withTimeout 作为第二层保护，防止协程永久挂起。
    private const val SUPABASE_TOTAL_TIMEOUT_MS = 30_000L

    /**
     * 向 Supabase 写入一个定时任务。
     * 对应 scheduled_jobs 表的 upsert。
     *
     * 日程系统批次1扩展：新增 `description` 形参（工单型任务 mode B 专用），
     * 写入云端 body。云端 scheduled_jobs 表需同步加 `description TEXT` 列
     * （详见 Migration62to63.kt 的云端同步说明），否则此字段会被云端丢弃。
     */
    suspend fun upsertScheduledJob(
        id: String,
        characterId: Int,
        title: String,
        toolName: String,
        toolParams: Map<String, String>,
        repeatIntervalMs: Long?,
        nextRunAt: Long,
        description: String? = null,
        projectId: String? = null,
    ): Boolean = withTimeout(SUPABASE_TOTAL_TIMEOUT_MS) {
        withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply {
                put("id", id)
                put("character_id", characterId)
                put("title", title)
                put("tool_name", toolName)
                put("tool_params", JSONObject(toolParams as Map<*, *>))
                put("repeat_interval_ms", repeatIntervalMs)
                put("next_run_at", nextRunAt)
                put("enabled", true)
                put("description", description)
                // 日程系统第七节：关联项目 ID。云端 scheduled_jobs 表需同步加 project_id 列
                // （详见 Migration63to64.kt 的云端同步说明），否则此字段会被云端丢弃。
                put("project_id", projectId)
            }

            val conn = openConnection("POST", "/rest/v1/scheduled_jobs")
            conn.setRequestProperty("Prefer", "resolution=merge-duplicates")

            try {
                OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
                val code = conn.responseCode
                code in 200..299
            } finally {
                conn.disconnect()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e  // S3问题2修复：rethrow，避免吞掉协程取消信号
        } catch (e: Throwable) {
            ZLog.w("SupabaseClient", "upsertScheduledJob failed", e)
            false
        }
        }
    }
    /**
     * 拉取指定角色的未读执行结果。
     * App 启动时调用，获取云端后台执行完毕的任务结果。
     * C7#27 修复：返回 [FetchResult]，区分"云端确实无未读结果"和"拉取失败"。
     */
    suspend fun fetchUnreadResults(characterId: Int): FetchResult =
        withTimeout(SUPABASE_TOTAL_TIMEOUT_MS) {
        withContext(Dispatchers.IO) {
            val conn = openConnection(
                "GET",
                "/rest/v1/job_results?character_id=eq.${urlEncode(characterId.toString())}&is_read=eq.false&order=created_at.desc&limit=20"
            )
            try {
                val code = conn.responseCode
                if (code !in 200..299) {
                    // C7#27 修复：HTTP 非 2xx 是拉取失败，不是"云端没有数据"，
                    // 此前直接 emptyList() 会让这次同步的失败对调用方完全不可见。
                    ZLog.e("SupabaseClient", "fetchUnreadResults HTTP $code，本次同步未执行")
                    return@withContext FetchResult.Failed
                }

                val json = conn.inputStream.bufferedReader().readText()

                val array = JSONArray(json)
                val results = mutableListOf<CloudJobResult>()

                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    results.add(
                        CloudJobResult(
                            id          = obj.getString("id"),
                            jobId       = obj.getString("job_id"),
                            characterId = obj.getInt("character_id"),
                            toolName    = obj.getString("tool_name"),
                            status      = obj.getString("status"),
                            // M-1 修复：org.json 的 optString(key, null) 在值为 JSON null 时
                            // 返回字符串 "null" 而非 Kotlin null，需用 isNull 显式判断。
                            output      = if (obj.isNull("output")) null else obj.optString("output"),
                            errorMsg    = if (obj.isNull("error_message")) null else obj.optString("error_message"),
                            executedBy  = obj.getString("executed_by"),
                            startedAt   = obj.getLong("started_at"),
                            completedAt = obj.optLong("completed_at"),
                            createdAt   = obj.getLong("created_at"),
                        )
                    )
                }
                FetchResult.Success(results)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e  // S3问题2修复：rethrow，避免吞掉协程取消信号
            } catch (e: Throwable) {
                // C7#27 修复：升级为 error——本次云端任务结果同步已经失败且不会
                // 自动重试（下次冷启动才会再拉一次），需要在诊断日志里留痕。
                ZLog.e("SupabaseClient", "fetchUnreadResults failed，本次同步未执行", e)
                FetchResult.Failed
            } finally {
                conn.disconnect()
            }
        }
        }

    /**
     * 标记云端结果为已读。
     */
    suspend fun markResultRead(resultId: String): Boolean =
        withTimeout(SUPABASE_TOTAL_TIMEOUT_MS) {
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("is_read", true)
            val conn = openConnection("PATCH", "/rest/v1/job_results?id=eq.${urlEncode(resultId)}")
            try {
                OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
                val code = conn.responseCode
                code in 200..299
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e  // S3问题2修复：rethrow，避免吞掉协程取消信号
            } catch (e: Throwable) {
                ZLog.w("SupabaseClient", "markResultRead failed", e)
                false
            } finally {
                conn.disconnect()
            }
        }
        }

    /**
     * Phase 30 · 删除云端定时任务。
     */
    suspend fun deleteScheduledJob(id: String): Boolean = withTimeout(SUPABASE_TOTAL_TIMEOUT_MS) {
        withContext(Dispatchers.IO) {
        val conn = openConnection("DELETE", "/rest/v1/scheduled_jobs?id=eq.${urlEncode(id)}")
        try {
            val code = conn.responseCode
            code in 200..299
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e  // S3问题2修复：rethrow，避免吞掉协程取消信号
        } catch (e: Throwable) {
            ZLog.w("SupabaseClient", "deleteScheduledJob failed", e)
            false
        } finally {
            conn.disconnect()
        }
        }
    }

    /**
     * Phase 30 · 更新云端定时任务字段（PATCH）。
     *
     * 日程系统批次1扩展：新增 `description` 形参，body 里追加 `put("description", description)`，
     * 与 upsertScheduledJob 保持对称。理由：updateJob() 成功后会调用 markCloudSynced(id)
     * 标记本地为已同步，若此 PATCH 不带 description，请求依然返回成功（其他字段都对），
     * 本地被误标为"已同步"后 retryPendingCloudSync() 只重试 cloudSynced=0 的任务，这条
     * 会被永久跳过，description 在云端卡死在错误状态，不会被后续批次自动补上。
     *
     * 日程系统第七节扩展：新增 `projectId` 形参，与 description 同款对称追加。
     * 若 PATCH 不带 project_id，同样的"误标已同步"问题会让 project_id 在云端卡死。
     */
    suspend fun updateScheduledJob(
        id: String,
        title: String,
        toolName: String,
        toolParams: Map<String, String>,
        repeatIntervalMs: Long?,
        nextRunAt: Long,
        description: String? = null,
        projectId: String? = null,
    ): Boolean = withTimeout(SUPABASE_TOTAL_TIMEOUT_MS) {
        withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply {
                put("title", title)
                put("tool_name", toolName)
                put("tool_params", JSONObject(toolParams as Map<*, *>))
                put("repeat_interval_ms", repeatIntervalMs)
                put("next_run_at", nextRunAt)
                put("description", description)
                // 日程系统第七节：与 upsertScheduledJob 对称追加。
                put("project_id", projectId)
            }
            val conn = openConnection("PATCH", "/rest/v1/scheduled_jobs?id=eq.${urlEncode(id)}")
            try {
                OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
                val code = conn.responseCode
                code in 200..299
            } finally {
                conn.disconnect()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e  // S3问题2修复：rethrow，避免吞掉协程取消信号
        } catch (e: Throwable) {
            ZLog.w("SupabaseClient", "updateScheduledJob failed", e)
            false
        }
        }
    }

    /**
     * Phase 30 方案六：上传或更新设备 FCM token。
     * 使用 upsert（on-conflict user_id）避免重复写入。
     *
     * 修复手册 Phase 2.1：原实现返回 Unit，异常和非 2xx 状态码全部内部吞掉，
     * 调用方（FcmTokenUploadWorker）无条件判定成功，失败的 token 永久丢失、
     * 不会触发 WorkManager 重试。现改为返回 Boolean，与本文件内 upsertScheduledJob
     * 的既有错误处理约定保持一致（成功/异常均映射为布尔值，调用方据此决定后续动作）。
     */
    suspend fun upsertDeviceToken(userId: String, fcmToken: String): Boolean {
        return withTimeout(SUPABASE_TOTAL_TIMEOUT_MS) {
        withContext(Dispatchers.IO) {
            try {
                val body = JSONObject().apply {
                    put("user_id",    userId)
                    put("fcm_token",  fcmToken)
                    put("updated_at", System.currentTimeMillis())
                }.toString()

                val conn = openConnection("POST", "/rest/v1/device_tokens")
                conn.setRequestProperty("Prefer", "resolution=merge-duplicates,return=minimal")

                try {
                    OutputStreamWriter(conn.outputStream).use { it.write(body) }
                    val code = conn.responseCode
                    if (code !in 200..299) {
                        ZLog.w("SupabaseClient", "FCM token upsert HTTP $code")
                    }
                    code in 200..299
                } finally {
                    conn.disconnect()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e  // S3问题2修复：rethrow，避免吞掉协程取消信号
            } catch (e: Throwable) {
                ZLog.w("SupabaseClient", "FCM token upload failed", e)
                false
            }
        }
        }
    }

    // ── 内部工具方法 ──────────────────────────────────────────

    /** 安全修复 M-2：对插入到 REST 查询字符串中的值做 URL 编码，避免 &、=、# 等字符篡改过滤条件。 */
    private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun openConnection(method: String, path: String): HttpURLConnection {
        val url = URL("$SUPABASE_URL$path")
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = TIMEOUT_MS
            readTimeout    = TIMEOUT_MS
            setRequestProperty("apikey", ANON_KEY)
            setRequestProperty("Authorization", "Bearer $ANON_KEY")
            setRequestProperty("Content-Type", "application/json")
            if (method in listOf("POST", "PATCH", "PUT")) {
                doOutput = true
            }
        }
    }
}

/**
 * 从 Supabase 拉取的云端执行结果（临时数据结构，写库前使用）。
 */
data class CloudJobResult(
    val id: String,
    val jobId: String,
    val characterId: Int,
    val toolName: String,
    val status: String,
    val output: String?,
    val errorMsg: String?,
    val executedBy: String,
    val startedAt: Long,
    val completedAt: Long?,
    val createdAt: Long,
)

/**
 * C7#27 修复：fetchUnreadResults 的返回值包装。原先无论"云端确实没有未读结果"
 * 还是"网络异常/HTTP 非 2xx 拉取失败"都统一返回空列表，调用方
 * ScheduleRepository.syncCloudResults() 只在 App 冷启动时调用一次，没有下次
 * 重试机会——拉取失败时，本该同步下来的云端任务结果就永久丢失了，且没有
 * 任何用户可见或日志层面的痕迹能说明"这次同步其实失败了"。
 * 只用于区分这两种情形，不引入重试机制（重试策略改动更大，且冷启动路径
 * 本身就应该快速返回，不适合在这里阻塞重试）。
 */
sealed class FetchResult {
    data class Success(val results: List<CloudJobResult>) : FetchResult()
    data object Failed : FetchResult()
}
