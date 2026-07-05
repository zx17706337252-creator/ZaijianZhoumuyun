package com.zaijian.zhoumuyun.data.agent

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 30 · 心跳检查清单 — 修改单条条目工具
 *
 * 支持三种操作（通过 action 参数指定）：
 *   check   — 勾选（标记已完成）
 *   uncheck — 取消勾选
 *   rename  — 重命名条目文字
 *
 * 标签格式：
 *   <tool:heartbeat_update index="0" action="check" />
 *   <tool:heartbeat_update index="1" action="rename" text="更新后的文字" />
 *
 * 参数说明：
 *   index   要操作的条目序号，从 0 开始（必填）
 *   action  操作类型：check / uncheck / rename（必填）
 *   text    rename 时的新文字（action=rename 时必填）
 */
class HeartbeatUpdateTool(
    private val context: Context,
    private val characterIdProvider: () -> Int,
) : AgentTool {

    override val name = "heartbeat_update"
    override val paramKeys = listOf("index", "action", "text")

    override suspend fun execute(params: Map<String, String>): ToolResult =
        withContext(Dispatchers.IO) {
            val indexStr = params["index"]?.trim()
            val action   = params["action"]?.trim()?.lowercase()

            if (indexStr.isNullOrEmpty()) {
                return@withContext ToolResult(name, false, "", error = "index 参数不能为空")
            }
            val index = indexStr.toIntOrNull()
                ?: return@withContext ToolResult(name, false, "", error = "index 必须是整数")

            if (action.isNullOrEmpty()) {
                return@withContext ToolResult(name, false, "", error = "action 参数不能为空（check / uncheck / rename）")
            }

            try {
                val charId = params["__character_id"]?.toIntOrNull() ?: characterIdProvider()
                // P1-6-10 修复：read → modify → write 三步原先无锁保护。
                // 两个并发工具调用（如同时执行 check 和 rename）均会读到相同的旧 JSON，
                // 后写入的一方整体覆盖先写入的一方，导致 lost update。
                // 改用 per-characterId Mutex 将整个"读-改-写"序列串行化；
                // HeartbeatDeleteTool 共享同一把锁（通过 heartbeatFileMutexes），
                // 保证跨工具的同一角色操作不产生竞态。
                HeartbeatFileLocks.getMutex(charId).withLock {
                    val file = checklistFile(charId)

                    if (!file.exists()) {
                        return@withLock ToolResult(name, false, "", error = "心跳清单不存在，请先用 heartbeat_set 创建")
                    }

                    val json = JSONObject(file.readText(Charsets.UTF_8))
                    val items = json.getJSONArray("items")

                    if (index < 0 || index >= items.length()) {
                        return@withLock ToolResult(
                            name, false, "",
                            error = "index 超出范围，清单共 ${items.length()} 条（0 ~ ${items.length() - 1}）"
                        )
                    }

                    val item = items.getJSONObject(index)
                    val resultDesc: String

                    when (action) {
                        "check" -> {
                            item.put("checked", true)
                            resultDesc = "已勾选第 ${index + 1} 条：「${item.getString("text")}」"
                        }
                        "uncheck" -> {
                            item.put("checked", false)
                            resultDesc = "已取消勾选第 ${index + 1} 条：「${item.getString("text")}」"
                        }
                        "rename" -> {
                            val newText = params["text"]?.trim()
                            if (newText.isNullOrEmpty()) {
                                return@withLock ToolResult(name, false, "", error = "rename 操作需要 text 参数")
                            }
                            val oldText = item.getString("text")
                            item.put("text", newText)
                            resultDesc = "已将第 ${index + 1} 条重命名：「$oldText」→「$newText」"
                        }
                        else -> return@withLock ToolResult(
                            name, false, "",
                            error = "未知 action: $action，支持 check / uncheck / rename"
                        )
                    }

                    json.put("updatedAt", System.currentTimeMillis())
                    file.writeText(json.toString(2), Charsets.UTF_8)

                    ToolResult(
                        toolName = name,
                        success  = true,
                        content  = resultDesc,
                        userHint = "正在更新心跳清单…",
                    )
                }
            } catch (e: Exception) {
                ToolResult(name, false, "", error = "更新失败：${e.message}")
            }
        }

    private fun checklistFile(characterId: Int): File =
        File(context.filesDir, "heartbeat/$characterId/checklist.json")
}

/**
 * HeartbeatUpdateTool 与 HeartbeatDeleteTool 共享的文件锁注册表。
 *
 * 独立 object 而非各自持有私有 Map，是因为两个工具操作同一文件，
 * 必须共享同一把锁，否则跨工具并发仍会产生竞态：
 *   UpdateTool.lock(charId) ≠ DeleteTool.lock(charId) → 锁不互斥
 * object 保证 JVM 单例，两个工具类都引用同一把 Mutex。
 */
internal object HeartbeatFileLocks {
    private val mutexes = ConcurrentHashMap<Int, Mutex>()
    fun getMutex(characterId: Int): Mutex =
        mutexes.computeIfAbsent(characterId) { Mutex() }
}
