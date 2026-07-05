package com.zaijian.zhoumuyun.data.remote

import kotlinx.coroutines.Dispatchers
import com.zaijian.zhoumuyun.util.ZLog
import kotlinx.coroutines.withContext
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
    private const val SUPABASE_URL = "https://npszynuzemkozojgnsvv.supabase.co"
    private const val ANON_KEY     = "sb_publishable_KwqJtocx1KeGtTwHwGt8Cg_VTCsC22n"
    // ─────────────────────────────────────────────────────────

    private const val TIMEOUT_MS = 15_000

    /**
     * 向 Supabase 写入一个定时任务。
     * 对应 scheduled_jobs 表的 upsert。
     */
    suspend fun upsertScheduledJob(
        id: String,
        characterId: Int,
        title: String,
        toolName: String,
        toolParams: Map<String, String>,
        repeatIntervalMs: Long?,
        nextRunAt: Long,
    ): Boolean = withContext(Dispatchers.IO) {
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
        } catch (e: Exception) {
            ZLog.w("SupabaseClient", "upsertScheduledJob failed", e)
            false
        }
    }

    /**
     * 拉取指定角色的未读执行结果。
     * App 启动时调用，获取云端后台执行完毕的任务结果。
     */
    suspend fun fetchUnreadResults(characterId: Int): List<CloudJobResult> =
        withContext(Dispatchers.IO) {
            val conn = openConnection(
                "GET",
                "/rest/v1/job_results?character_id=eq.${urlEncode(characterId.toString())}&is_read=eq.false&order=created_at.desc&limit=20"
            )
            try {
                val code = conn.responseCode
                if (code !in 200..299) {
                    return@withContext emptyList()
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
                results
            } catch (e: Exception) {
                ZLog.w("SupabaseClient", "fetchUnreadResults failed", e)
                emptyList()
            } finally {
                conn.disconnect()
            }
        }

    /**
     * 标记云端结果为已读。
     */
    suspend fun markResultRead(resultId: String): Boolean =
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("is_read", true)
            val conn = openConnection("PATCH", "/rest/v1/job_results?id=eq.${urlEncode(resultId)}")
            try {
                OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
                val code = conn.responseCode
                code in 200..299
            } catch (e: Exception) {
                ZLog.w("SupabaseClient", "markResultRead failed", e)
                false
            } finally {
                conn.disconnect()
            }
        }

    /**
     * Phase 30 · 删除云端定时任务。
     */
    suspend fun deleteScheduledJob(id: String): Boolean = withContext(Dispatchers.IO) {
        val conn = openConnection("DELETE", "/rest/v1/scheduled_jobs?id=eq.${urlEncode(id)}")
        try {
            val code = conn.responseCode
            code in 200..299
        } catch (e: Exception) {
            ZLog.w("SupabaseClient", "deleteScheduledJob failed", e)
            false
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Phase 30 · 更新云端定时任务字段（PATCH）。
     */
    suspend fun updateScheduledJob(
        id: String,
        title: String,
        toolName: String,
        toolParams: Map<String, String>,
        repeatIntervalMs: Long?,
        nextRunAt: Long,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply {
                put("title", title)
                put("tool_name", toolName)
                put("tool_params", JSONObject(toolParams as Map<*, *>))
                put("repeat_interval_ms", repeatIntervalMs)
                put("next_run_at", nextRunAt)
            }
            val conn = openConnection("PATCH", "/rest/v1/scheduled_jobs?id=eq.${urlEncode(id)}")
            try {
                OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
                val code = conn.responseCode
                code in 200..299
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            ZLog.w("SupabaseClient", "updateScheduledJob failed", e)
            false
        }
    }

    /**
     * Phase 30 方案六：上传或更新设备 FCM token。
     * 使用 upsert（on-conflict user_id）避免重复写入。
     */
    suspend fun upsertDeviceToken(userId: String, fcmToken: String) {
        withContext(Dispatchers.IO) {
            try {
                val body = JSONObject().apply {
                    put("user_id",    userId)
                    put("fcm_token",  fcmToken)
                    put("updated_at", System.currentTimeMillis())
                }.toString()

                val conn = openConnection("POST", "/rest/v1/device_tokens")
                conn.setRequestProperty("Prefer", "resolution=merge-duplicates,return=minimal")
                OutputStreamWriter(conn.outputStream).use { it.write(body) }

                val code = conn.responseCode
                conn.disconnect()
                if (code !in 200..299) {
                    ZLog.w("SupabaseClient", "FCM token upsert HTTP $code")
                }
            } catch (e: Exception) {
                ZLog.w("SupabaseClient", "FCM token upload failed", e)
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
