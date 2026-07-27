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
 *   items   条目列表，英文逗号分隔（必填）。
 *           若某一条目本身需要包含逗号，请用转义双引号包裹该条目，如（注意标签本身要求
 *           整个 items 值外层也是双引号，内层引号必须写成 \" 转义，否则会在第一个裸 "
 *           处被截断丢失后续条目）：
 *           items="正常条目,\"含逗号,的条目\",另一条目"
 *
 * 注意：此工具会覆盖整个清单文件，用于初始化或重置。
 * 如需单条修改，请使用 heartbeat_update。
 */
class HeartbeatSetTool(
    private val context: Context,
    private val characterIdProvider: () -> Int,
) : AgentTool {

    override val name = "heartbeat_set"
    // P1 修复（批次2审查报告问题1/2）：原 description 只有一句话，LLM 看不到 items 的
    // 分隔/转义约定，容易在条目内写未转义引号导致 ToolParser 静默截断（见 ToolParser.kt
    // detectUnescapedQuoteTruncation 的说明）。补充格式与转义示例，给 LLM 可直接照抄的写法。
    override val description = "整体写入/覆盖角色的心跳自检清单（定期检查项列表）"
    override val usageNotes = "items 用英文逗号分隔多个条目；若某条目本身含逗号或引号，整个条目要用转义双引号包裹，例如 items=\"正常条目,\\\"含逗号,的条目\\\",另一条目\""

    override val paramKeys = listOf("title", "items")

    private companion object {
        // 修复（第4窗口审查报告问题6）：原实现 itemsRaw.split(",") 无法处理条目内含逗号的情况
        // （逗号会被误判为条目分隔符，导致条目被错误截断）。
        // 支持用双引号包裹单个条目以允许内部逗号（含 \" 转义），未加引号的普通条目
        // 保持原有的逗号分割行为，对绝大多数不含逗号的既有调用完全向后兼容。
        val QUOTED_ITEM_REGEX = Regex(""""((?:[^"\\]|\\.)*)"|([^,]+)""")

        fun parseItems(raw: String): List<String> =
            QUOTED_ITEM_REGEX.findAll(raw)
                .map { m -> (if (m.groups[1] != null) m.groupValues[1] else m.groupValues[2]).trim() }
                .filter { it.isNotEmpty() }
                .toList()
    }

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
                // 批次4-2 修复：charId < 0 表示占位注册（-1），
                // 此时心跳清单写入没有明确归属角色，写入后无法被任何角色读取，
                // 静默返回假成功会误导 LLM。改为直接返回错误提示。
                if (charId < 0) {
                    return@withContext ToolResult(name, false, "", error = "心跳清单需要指定角色，当前会话未绑定角色")
                }
                val items = parseItems(itemsRaw)

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
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                toolFailure(name, "写入心跳清单失败，请稍后重试。", "heartbeat_set_failed", e)
            }
        }

    // P-10 修复：加 per-characterId Mutex，与 HeartbeatUpdateTool/DeleteTool 共享同一 fileLocks，
    // 防止 Set 与 Update/Delete 跨工具并发时整体覆盖 checklist.json。
    // 改为 suspend 以便使用 withLock（原 internal fun 由 execute 的 withContext 调用，改为 suspend 兼容）。
    internal suspend fun writeChecklistFile(characterId: Int, content: String) {
        HeartbeatFileLocks.getMutex(characterId).withLock {
            val dir = File(context.filesDir, "heartbeat/$characterId")
            // #45 修复：原逻辑不检查 mkdirs() 返回值。目录创建失败时（磁盘满、
            // 权限异常等）此前会直接走到 writeText 抛出普通 IOException——外层
            // execute() 的 try-catch 能兜住不崩溃，但 toolFailure 只把异常细节
            // 写进 Logcat，返给 LLM/用户的只是统一的"写入心跳清单失败，请稍后
            // 重试"，看不出根因其实是"目录都没建起来"。
            // 这里显式检查：mkdirs() 返回 false 时，先看 dir 是否已经存在
            // （包括"目录已存在"和"并发场景下已被其他调用创建好"两种正常情况，
            // mkdirs() 对已存在目录也会返回 false），只有确认目录仍不存在才
            // 抛出带明确原因的异常，避免误报正常场景为失败。
            if (!dir.exists() && !dir.mkdirs() && !dir.exists()) {
                throw java.io.IOException("无法创建心跳清单目录: ${dir.path}")
            }
            File(dir, "checklist.json").writeText(content, Charsets.UTF_8)
        }
    }
}
