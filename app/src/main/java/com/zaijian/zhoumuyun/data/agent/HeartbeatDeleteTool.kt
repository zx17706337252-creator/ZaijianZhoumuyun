package com.zaijian.zhoumuyun.data.agent

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Phase 30 · 心跳检查清单 — 删除工具
 *
 * 支持两种粒度（通过 index 参数区分）：
 *   - 不传 index → 删除整个清单文件
 *   - 传入 index → 只删除该序号的条目
 *
 * 标签格式：
 *   <tool:heartbeat_delete />                  <!-- 删除整个清单 -->
 *   <tool:heartbeat_delete index="2" />        <!-- 删除第 3 条 -->
 *
 * 参数说明：
 *   index   要删除的条目序号（可选，从 0 开始；不传则删除整个清单）
 */
class HeartbeatDeleteTool(
    private val context: Context,
    private val characterIdProvider: () -> Int,
) : AgentTool {

    override val name = "heartbeat_delete"
    override val description = "删除心跳自检清单整体或其中一条条目"
    override val paramKeys = listOf("index")

    override suspend fun execute(params: Map<String, String>): ToolResult =
        withContext(Dispatchers.IO) {
            try {
                val charId = params["__character_id"]?.toIntOrNull() ?: characterIdProvider()
                if (charId < 0) {
                    return@withContext ToolResult(name, false, "", error = "心跳清单需要指定角色，当前会话未绑定角色")
                }
                // P1-6-10 修复：与 HeartbeatUpdateTool 共享 HeartbeatFileLocks 中的 per-characterId
                // Mutex，保证跨工具的"读-改-写"序列对同一角色完全串行化。
                HeartbeatFileLocks.getMutex(charId).withLock {
                    val file = checklistFile(charId)

                    if (!file.exists()) {
                        return@withLock ToolResult(name, false, "", error = "心跳清单不存在")
                    }

                    val indexStr = params["index"]?.trim()

                    if (indexStr.isNullOrEmpty()) {
                        // ── 删除整个清单 ──────────────────────────────────
                        // P2 修复：检查 file.delete() 返回值，删除失败时返回错误而非假装成功
                        if (!file.delete()) {
                            return@withLock ToolResult(name, false, "", error = "删除心跳清单文件失败")
                        }
                        ToolResult(
                            toolName = name,
                            success  = true,
                            content  = "心跳清单已删除。",
                            userHint = "正在删除心跳清单…",
                        )
                    } else {
                        // ── 删除单条条目 ──────────────────────────────────
                        val index = indexStr.toIntOrNull()
                            ?: return@withLock ToolResult(name, false, "", error = "index 必须是整数")

                        val json = JSONObject(file.readText(Charsets.UTF_8))
                        val items = json.getJSONArray("items")

                        if (index < 0 || index >= items.length()) {
                            return@withLock ToolResult(
                                name, false, "",
                                error = "index 超出范围，清单共 ${items.length()} 条（0 ~ ${items.length() - 1}）"
                            )
                        }

                        val deletedText = items.getJSONObject(index).getString("text")

                        // 重建剔除该条后的数组
                        val newItems = JSONArray()
                        for (i in 0 until items.length()) {
                            if (i != index) newItems.put(items.getJSONObject(i))
                        }
                        json.put("items", newItems)
                        json.put("updatedAt", System.currentTimeMillis())
                        file.writeText(json.toString(2), Charsets.UTF_8)

                        ToolResult(
                            toolName = name,
                            success  = true,
                            content  = "已删除第 ${index + 1} 条：「$deletedText」，剩余 ${newItems.length()} 条。",
                            userHint = "正在删除清单条目…",
                        )
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                toolFailure(name, "删除心跳清单条目失败，请稍后重试。", "heartbeat_delete_failed", e)
            }
        }

    private fun checklistFile(characterId: Int): File =
        File(context.filesDir, "heartbeat/$characterId/checklist.json")
}
