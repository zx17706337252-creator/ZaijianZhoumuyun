package com.zaijian.zhoumuyun.data.agent

import com.zaijian.zhoumuyun.data.repository.AgentStoreRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ─────────────────────────────────────────────
//  ① StorePutTool — 写入/更新一条结构化记录
// ─────────────────────────────────────────────

class StorePutTool(
    private val repo: AgentStoreRepository,
    private val characterIdProvider: () -> Int,
) : AgentTool {
    override val name = "store_put"
    override val description = "保存/更新一条结构化数据（分组+键+值），用于记录需要按条目查询的信息"
    override val paramKeys = listOf("collection", "key", "value")
    override val usageNotes =
        "collection 是分组名（如 budget_items），key 是这条记录的唯一标识（如 2026-07）。" +
        "value 可以是纯文本，也可以是 JSON 对象/数组（如 {\"amount\":500,\"note\":\"房租\"}），" +
        "JSON 内部的双引号不需要额外转义，直接按标准 JSON 格式写。" +
        "同一 collection+key 再次写入会覆盖旧值，不会重复。适合存需要之后按条目查/改/删的" +
        "结构化信息（预算记录、阅读清单、待观察条件等）；一次性长文本/文档用 note_save/file_export。"

    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val collection = params["collection"]?.trim()
        val key = params["key"]?.trim()
        val value = params["value"]
        if (collection.isNullOrEmpty() || key.isNullOrEmpty() || value.isNullOrEmpty()) {
            return@withContext ToolResult(name, false, "", "缺少 collection/key/value 参数")
        }
        val charId = characterIdProvider()
        if (charId < 0) return@withContext ToolResult(name, false, "", "角色未初始化")

        try {
            when (val result = repo.put(charId, collection, key, value)) {
                is AgentStoreRepository.StoreResult.Ok ->
                    ToolResult(name, true, "[已保存]\n分组：$collection\n键：$key")
                is AgentStoreRepository.StoreResult.Rejected ->
                    ToolResult(name, false, result.reason, "store_put_rejected")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            toolFailure(name, "保存数据时遇到问题。", "store_put_failed", e)
        }
    }
}

// ─────────────────────────────────────────────
//  ② StoreGetTool — 按 key 精确读取
// ─────────────────────────────────────────────

class StoreGetTool(
    private val repo: AgentStoreRepository,
    private val characterIdProvider: () -> Int,
) : AgentTool {
    override val name = "store_get"
    override val description = "按分组+键精确读取一条之前用 store_put 保存的结构化数据"
    override val paramKeys = listOf("collection", "key")

    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val collection = params["collection"]?.trim()
        val key = params["key"]?.trim()
        if (collection.isNullOrEmpty() || key.isNullOrEmpty()) {
            return@withContext ToolResult(name, false, "", "缺少 collection/key 参数")
        }
        val charId = characterIdProvider()
        if (charId < 0) return@withContext ToolResult(name, false, "", "角色未初始化")

        try {
            val record = repo.get(charId, collection, key)
                ?: return@withContext ToolResult(name, false, "没有找到「$collection/$key」这条记录。")
            ToolResult(name, true, "[记录内容]\n分组：$collection\n键：$key\n值：${record.valueJson}\n更新时间：${record.updatedAt}")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            toolFailure(name, "读取数据时遇到问题。", "store_get_failed", e)
        }
    }
}

// ─────────────────────────────────────────────
//  ③ StoreListTool — 列出/前缀查询一个分组
// ─────────────────────────────────────────────

class StoreListTool(
    private val repo: AgentStoreRepository,
    private val characterIdProvider: () -> Int,
) : AgentTool {
    override val name = "store_list"
    override val description = "列出某个分组下的所有结构化记录，可选按键前缀过滤"
    override val paramKeys = listOf("collection", "key_prefix", "limit")
    override val usageNotes = "key_prefix 可选，不传则列出该分组全部记录（最多 limit 条，默认 50，上限 200）。" +
        "只想看某分组全部内容用这个；想按条件筛（如金额大于500）用 store_query。"

    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val collection = params["collection"]?.trim()
        if (collection.isNullOrEmpty()) {
            return@withContext ToolResult(name, false, "", "缺少 collection 参数")
        }
        val prefix = params["key_prefix"]?.trim()
        val limit = params["limit"]?.trim()?.toIntOrNull() ?: 50
        val charId = characterIdProvider()
        if (charId < 0) return@withContext ToolResult(name, false, "", "角色未初始化")

        try {
            val records = if (prefix.isNullOrEmpty()) {
                repo.list(charId, collection, limit)
            } else {
                repo.listByKeyPrefix(charId, collection, prefix, limit)
            }
            if (records.isEmpty()) {
                return@withContext ToolResult(name, true, "分组「$collection」下没有记录${if (!prefix.isNullOrEmpty()) "（前缀「$prefix」）" else ""}。")
            }
            val body = records.joinToString("\n") { "- ${it.key}：${it.valueJson}" }
            ToolResult(name, true, "[分组「$collection」共 ${records.size} 条]\n$body")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            toolFailure(name, "查询数据时遇到问题。", "store_list_failed", e)
        }
    }
}

// ─────────────────────────────────────────────
//  ④ StoreQueryTool — 按字段条件筛选一个分组
// ─────────────────────────────────────────────

class StoreQueryTool(
    private val repo: AgentStoreRepository,
    private val characterIdProvider: () -> Int,
) : AgentTool {
    override val name = "store_query"
    override val description = "按条件筛选一个分组下的结构化记录（如金额大于500的记录）"
    override val paramKeys = listOf("collection", "field", "op", "value", "limit")
    override val usageNotes =
        "op 只能是 eq/gt/lt/gte/lte/contains 之一。field 是记录内 JSON 对象的顶层字段名" +
        "（如 amount），只支持一层，不支持 a.b.c 多层路径；如果这个分组存的不是 JSON 对象" +
        "而是纯文本，就不传 field，只用 contains 做文本包含匹配。" +
        "只支持单个条件，不支持\"且\"/\"或\"组合多个条件——需要多条件时分两次查。" +
        "简单场景（想看某个分组全部内容）优先用 store_list，不需要用这个工具。"

    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val collection = params["collection"]?.trim()
        val opRaw = params["op"]?.trim()
        val compareValue = params["value"]
        if (collection.isNullOrEmpty() || opRaw.isNullOrEmpty() || compareValue.isNullOrEmpty()) {
            return@withContext ToolResult(name, false, "", "缺少 collection/op/value 参数")
        }
        val op = AgentStoreRepository.QueryOp.fromParam(opRaw)
            ?: return@withContext ToolResult(name, false, "op 参数不合法，只能是 eq/gt/lt/gte/lte/contains 之一", "store_query_bad_op")
        val field = params["field"]?.trim()?.takeIf { it.isNotEmpty() }
        val limit = params["limit"]?.trim()?.toIntOrNull() ?: 50
        val charId = characterIdProvider()
        if (charId < 0) return@withContext ToolResult(name, false, "", "角色未初始化")

        try {
            val records = repo.query(charId, collection, field, op, compareValue, limit)
            if (records.isEmpty()) {
                return@withContext ToolResult(name, true, "分组「$collection」下没有符合条件的记录。")
            }
            val body = records.joinToString("\n") { "- ${it.key}：${it.valueJson}" }
            ToolResult(name, true, "[分组「$collection」筛选结果，共 ${records.size} 条]\n$body")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            toolFailure(name, "筛选数据时遇到问题。", "store_query_failed", e)
        }
    }
}

// ─────────────────────────────────────────────
//  ⑤ StoreDeleteTool — 删除单条记录
// ─────────────────────────────────────────────

class StoreDeleteTool(
    private val repo: AgentStoreRepository,
    private val characterIdProvider: () -> Int,
) : AgentTool {
    override val name = "store_delete"
    override val description = "删除一条之前用 store_put 保存的结构化数据"
    override val paramKeys = listOf("collection", "key")

    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val collection = params["collection"]?.trim()
        val key = params["key"]?.trim()
        if (collection.isNullOrEmpty() || key.isNullOrEmpty()) {
            return@withContext ToolResult(name, false, "", "缺少 collection/key 参数")
        }
        val charId = characterIdProvider()
        if (charId < 0) return@withContext ToolResult(name, false, "", "角色未初始化")

        try {
            val deleted = repo.delete(charId, collection, key)
            if (deleted) ToolResult(name, true, "[已删除]\n分组：$collection\n键：$key")
            else ToolResult(name, false, "没有找到「$collection/$key」这条记录，无需删除。")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            toolFailure(name, "删除数据时遇到问题。", "store_delete_failed", e)
        }
    }
}

// ─────────────────────────────────────────────
//  模块注册入口
// ─────────────────────────────────────────────

/**
 * 注册全部 Agent 结构化存储工具（5个）。
 * 需要在两个地方各调用一次，缺一不可，见第八节 8.10。
 */
fun AgentToolRegistry.registerAgentStoreTools(
    repo: AgentStoreRepository,
    characterIdProvider: () -> Int,
) {
    registerAll(
        StorePutTool(repo, characterIdProvider),
        StoreGetTool(repo, characterIdProvider),
        StoreListTool(repo, characterIdProvider),
        StoreQueryTool(repo, characterIdProvider),
        StoreDeleteTool(repo, characterIdProvider),
    )
}
