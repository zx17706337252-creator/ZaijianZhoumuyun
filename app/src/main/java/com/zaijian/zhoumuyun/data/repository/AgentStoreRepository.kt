package com.zaijian.zhoumuyun.data.repository

import com.zaijian.zhoumuyun.data.db.dao.AgentStoreDao
import com.zaijian.zhoumuyun.data.db.entity.AgentStoreRecordEntity
import com.zaijian.zhoumuyun.data.db.entity.AgentStoreValueType
import org.json.JSONArray
import org.json.JSONObject

class AgentStoreRepository(private val dao: AgentStoreDao) {

    companion object {
        /** 单个角色跨所有 collection 的记录数上限，防止无限增长拖垮查询/占满磁盘。 */
        const val MAX_RECORDS_PER_OWNER = 2000

        /** collection 名/key 名的最大长度，防止把整段用户输入直接当 key 存，污染索引。 */
        const val MAX_NAME_LENGTH = 80

        /** value JSON 序列化后的最大字节数，防止单条记录塞进一份超大文档。 */
        const val MAX_VALUE_BYTES = 32 * 1024 // 32KB，足够一个结构化对象，不足以当文件用

        private val NAME_PATTERN = Regex("^[a-zA-Z0-9_\\-\\u4e00-\\u9fa5]{1,80}$")
    }

    sealed class StoreResult {
        data class Ok(val record: AgentStoreRecordEntity) : StoreResult()
        data class Rejected(val reason: String) : StoreResult()
    }

    /** store_query 支持的比较方式，见 [query]。 */
    sealed class QueryOp {
        object Eq : QueryOp()
        object Gt : QueryOp()
        object Lt : QueryOp()
        object Gte : QueryOp()
        object Lte : QueryOp()
        object Contains : QueryOp()

        companion object {
            fun fromParam(raw: String): QueryOp? = when (raw.trim().lowercase()) {
                "eq" -> Eq
                "gt" -> Gt
                "lt" -> Lt
                "gte" -> Gte
                "lte" -> Lte
                "contains" -> Contains
                else -> null
            }
        }
    }

    /**
     * 写入（upsert）一条记录。
     *
     * @param rawValue 调用方传入的原始值（工具层从 LLM 参数里拿到的字符串，
     *   可能是纯字符串，也可能是 JSON 文本——由工具层决定先 tryParse 再传，
     *   这里只做最终的类型判定和落库）
     */
    suspend fun put(
        characterId: Int,
        collection: String,
        key: String,
        rawValue: String,
    ): StoreResult {
        if (!NAME_PATTERN.matches(collection)) return StoreResult.Rejected("collection 名不合法，仅支持中英文/数字/下划线/连字符，1-80 字符")
        if (!NAME_PATTERN.matches(key)) return StoreResult.Rejected("key 名不合法，仅支持中英文/数字/下划线/连字符，1-80 字符")

        val (valueJson, valueType) = normalizeValue(rawValue)
        if (valueJson.toByteArray(Charsets.UTF_8).size > MAX_VALUE_BYTES) {
            return StoreResult.Rejected("单条记录内容过大（上限 ${MAX_VALUE_BYTES / 1024}KB），建议拆分或改用 note_save/file_export")
        }

        // 配额检查：只在"这是一条新 key"时计数（更新已有 key 不消耗配额）
        val existing = dao.get(characterId, collection, key)
        if (existing == null) {
            val total = dao.countByOwner(characterId)
            if (total >= MAX_RECORDS_PER_OWNER) {
                return StoreResult.Rejected("已达存储上限（$MAX_RECORDS_PER_OWNER 条），请先删除一些不再需要的记录")
            }
        }

        val now = System.currentTimeMillis()
        val record = AgentStoreRecordEntity(
            ownerCharacterId = characterId,
            collection = collection,
            key = key,
            valueJson = valueJson,
            valueType = valueType,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
        )
        dao.upsert(record)
        return StoreResult.Ok(record)
    }

    suspend fun get(characterId: Int, collection: String, key: String): AgentStoreRecordEntity? =
        dao.get(characterId, collection, key)

    suspend fun list(characterId: Int, collection: String, limit: Int = 50): List<AgentStoreRecordEntity> =
        dao.listByCollection(characterId, collection, limit.coerceIn(1, 200))

    suspend fun listByKeyPrefix(characterId: Int, collection: String, prefix: String, limit: Int = 50): List<AgentStoreRecordEntity> {
        // 转义 LIKE 的通配符字符，防止 prefix 本身含 % 或 _ 时匹配到超出预期的记录
        val escaped = prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return dao.listByKeyPrefix(characterId, collection, "$escaped%", limit.coerceIn(1, 200))
    }

    suspend fun delete(characterId: Int, collection: String, key: String): Boolean =
        dao.delete(characterId, collection, key) > 0

    suspend fun count(characterId: Int, collection: String): Int =
        dao.countByCollection(characterId, collection)

    /**
     * 按字段筛选一个 collection 下的记录。
     *
     * 设计边界（对齐 7.2 节"明确不做的事"）：
     * - 只做一层字段访问（field 直接对应 JSON 对象的顶层 key，不支持 a.b.c 路径）
     * - 过滤在应用层做（读出该 collection 全部记录后用 Kotlin 代码过滤），
     *   不做 SQL json_extract——数据量级（几十到几百条/collection）下足够快，
     *   避免在 SQL 层开放任意 JSON path 查询带来的注入/复杂度风险
     * - 只支持单一条件，不支持多条件组合（"且/或"）
     *
     * @param field 为 null 时，对整条记录的 valueJson 原始文本做 op 比较
     *   （此时只有 Contains 有意义，其余 op 会导致所有非数字文本记录被跳过，
     *   由调用方 Tool 层的 usageNotes 提示这种用法组合的局限）
     */
    suspend fun query(
        characterId: Int,
        collection: String,
        field: String?,
        op: QueryOp,
        compareValue: String,
        limit: Int = 50,
    ): List<AgentStoreRecordEntity> {
        // 复用已有的 listByCollection，读一次全量（受 MAX_RECORDS_PER_OWNER=2000
        // 总量上限约束，单个 collection 实际不会超过这个数量级，一次性读入内存可控）
        val all = dao.listByCollection(characterId, collection, limit = 2000)

        fun extractComparable(record: AgentStoreRecordEntity): String? {
            if (field.isNullOrEmpty()) return record.valueJson
            if (record.valueType != AgentStoreValueType.OBJECT) return null
            return try {
                val obj = JSONObject(record.valueJson)
                if (!obj.has(field)) null else obj.get(field).toString()
            } catch (e: Throwable) {
                null
            }
        }

        val matched = all.filter { record ->
            val fieldValue = extractComparable(record) ?: return@filter false
            when (op) {
                QueryOp.Contains -> fieldValue.contains(compareValue, ignoreCase = true)
                QueryOp.Eq -> fieldValue == compareValue ||
                    fieldValue.toDoubleOrNull()?.let { it == compareValue.toDoubleOrNull() } == true
                QueryOp.Gt, QueryOp.Lt, QueryOp.Gte, QueryOp.Lte -> {
                    // 数值比较：两边都必须能转成 Double，转不了的记录视为不匹配（跳过而非报错，
                    // 因为一个 collection 里可能混有格式不规整的历史数据）
                    val a = fieldValue.toDoubleOrNull() ?: return@filter false
                    val b = compareValue.toDoubleOrNull() ?: return@filter false
                    when (op) {
                        QueryOp.Gt -> a > b
                        QueryOp.Lt -> a < b
                        QueryOp.Gte -> a >= b
                        QueryOp.Lte -> a <= b
                        else -> false
                    }
                }
            }
        }
        return matched.sortedByDescending { it.updatedAt }.take(limit.coerceIn(1, 200))
    }

    /** 判定 rawValue 的 JSON 类型并规范化存储形式。非法 JSON 一律当纯字符串处理（用户友好，不强制 LLM 每次都写合法 JSON）。 */
    private fun normalizeValue(rawValue: String): Pair<String, String> {
        val trimmed = rawValue.trim()
        return try {
            when {
                trimmed.startsWith("{") -> JSONObject(trimmed).toString() to AgentStoreValueType.OBJECT
                trimmed.startsWith("[") -> JSONArray(trimmed).toString() to AgentStoreValueType.ARRAY
                trimmed.equals("true", ignoreCase = true) || trimmed.equals("false", ignoreCase = true) ->
                    trimmed.lowercase() to AgentStoreValueType.BOOLEAN
                trimmed.toDoubleOrNull() != null -> trimmed to AgentStoreValueType.NUMBER
                else -> JSONObject.quote(trimmed) to AgentStoreValueType.STRING
            }
        } catch (e: Throwable) {
            // 不是合法 JSON object/array（比如用户就是想存一段纯文本，恰好以 { 开头）
            // → 兜底当字符串存，不因为 JSON 解析失败而拒绝写入
            JSONObject.quote(trimmed) to AgentStoreValueType.STRING
        }
    }
}
