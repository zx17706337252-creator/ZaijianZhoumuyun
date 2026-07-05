package com.zaijian.zhoumuyun.data.agent

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Phase 30 · 心跳检查清单 — 写入/全量更新工具
 *
 * 心跳清单（heartbeat checklist）是角色的"自检文档"：
 * 记录角色需定期检查的项目（如：今天是否完成每日任务、规则是否需要更新等）。
 * 数据持久化为本地 JSON 文件，路径：<filesDir>/heartbeat/<characterId>/checklist.json
 *
 * 标签格式（整体写入/覆盖）：
 *   <tool:heartbeat_set
 *     title="今日心跳"
 *     items="检查网络状态,回顾学习目标,确认任务队列"
 *   />
 *
 * 参数说明：
 *   title   清单标题（必填）
 *   items   条目列表，英文逗号分隔（必填）
 *
 * 注意：此工具会覆盖整个清单文件，用于初始化或重置。
 * 如需单条修改，请使用 heartbeat_update。
 */
class HeartbeatSetTool(
    private val context: Context,
    private val characterIdProvider: () -> Int,
) : AgentTool {

    override val name = "heartbeat_set"
    override val paramKeys = listOf("title", "items")

    override suspend fun execute(params: Map<String, String>): ToolResult =
        withContext(Dispatchers.IO) {
            val title = params["title"]?.trim()
            val itemsRaw = params["items"]?.trim()

            if (title.isNullOrEmpty()) {
                return@withContext ToolResult(name, false, "", error = "title 参数不能为空")
            }
            if (itemsRaw.isNullOrEmpty()) {
                return@withContext ToolResult(name, false, "", error = "items 参数不能为空")
            }

            try {
                val charId = params["__character_id"]?.toIntOrNull() ?: characterIdProvider()
                val items = itemsRaw.split(",").map { it.trim() }.filter { it.isNotEmpty() }

                val json = JSONObject().apply {
                    put("characterId", charId)
                    put("title", title)
                    put("updatedAt", System.currentTimeMillis())
                    put("items", JSONArray().also { arr ->
                        items.forEach { text ->
                            arr.put(JSONObject().apply {
                                put("text", text)
                                put("checked", false)
                            })
                        }
                    })
                }

                writeChecklistFile(charId, json.toString(2))

                ToolResult(
                    toolName = name,
                    success  = true,
                    content  = "心跳清单「$title」已写入，共 ${items.size} 条。",
                    userHint = "正在写入心跳清单…",
                )
            } catch (e: Exception) {
                ToolResult(name, false, "", error = "写入失败：${e.message}")
            }
        }

    // P-10 修复：加 per-characterId Mutex，与 HeartbeatUpdateTool/DeleteTool 共享同一 fileLocks，
    // 防止 Set 与 Update/Delete 跨工具并发时整体覆盖 checklist.json。
    // 改为 suspend 以便使用 withLock（原 internal fun 由 execute 的 withContext 调用，改为 suspend 兼容）。
    internal suspend fun writeChecklistFile(characterId: Int, content: String) {
        HeartbeatFileLocks.getMutex(characterId).withLock {
            val dir = File(context.filesDir, "heartbeat/$characterId")
            dir.mkdirs()
            File(dir, "checklist.json").writeText(content, Charsets.UTF_8)
        }
    }
}
