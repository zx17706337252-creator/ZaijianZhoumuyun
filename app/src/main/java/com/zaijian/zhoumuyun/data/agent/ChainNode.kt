package com.zaijian.zhoumuyun.data.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * 灵活自动化编排 · 链条节点（密封类，序列化进 ChainDefinitionEntity.nodesJson，不建表）
 *
 * 对应《灵活自动化编排·改造设计方案》§3.3。四种节点类型对应状态机模型的
 * Wait / Choice / Task / End，是能表达"A → 等 30 分钟 → 查 B → 条件分支 → 做 C"
 * 这条链所需的最小集合，不多加类型。
 *
 * JSON 序列化格式（§7 ChainCreateTool 的 nodes 属性）：
 * ```
 * [
 *   {"type":"wait","id":"n1","durationMs":1800000},
 *   {"type":"check","id":"n2","expression":"mood.energy < 30","checkToolName":"weather","onTrue":"n3","onFalse":"n4"},
 *   {"type":"action","id":"n3","goal":"提醒用户休息","next":"n5"},
 *   {"type":"end","id":"n4","outcome":"CANCELLED"},
 *   {"type":"end","id":"n5","outcome":"COMPLETED"}
 * ]
 * ```
 *
 * §3.3 说明：Wait 节点没有 next 字段——Wait 执行后由 ChainEngine 推进到
 * currentNodeIndex + 1（顺序下一个节点），不像 Action/Check 那样通过节点 id 跳转。
 * 若 LLM 生成的 JSON 里 Wait 节点带了 next 字段（§7 示例里有），反序列化时直接忽略。
 */
sealed class ChainNode {
    abstract val id: String

    /** 等待节点：睡固定时长后唤醒，推进到数组中下一个节点（currentNodeIndex + 1） */
    data class Wait(
        override val id: String,
        val durationMs: Long,          // "过半小时"→ 1_800_000
    ) : ChainNode()

    /** 检查节点：求值条件表达式，结果写入 context，走 onTrue 或 onFalse 分支 */
    data class Check(
        override val id: String,
        val expression: String,        // 见 §4，如 "mood.energy < 30"
        val checkToolName: String? = null, // 若需先执行只读工具获取最新数据，先跑这个工具再求值
        val onTrue: String,             // 下一个节点 id
        val onFalse: String,            // 下一个节点 id，可以指向 End 实现"条件不满足就终止"
    ) : ChainNode()

    /** 动作节点：复用 WorkflowEngine，把 goal 交给它跑一次完整的工具决策循环 */
    data class Action(
        override val id: String,
        val goal: String,               // 支持 {{context.xxx}} 占位符引用之前节点写入 context 的值
        val next: String,
    ) : ChainNode()

    /** 终止节点 */
    data class End(
        override val id: String,
        val outcome: String,            // COMPLETED | CANCELLED，用于 resultSummary 措辞区分
    ) : ChainNode()
}

/** [ChainNode] JSON 中的 type 字段合法取值。 */
object ChainNodeType {
    const val WAIT = "wait"
    const val CHECK = "check"
    const val ACTION = "action"
    const val END = "end"
}

/** [ChainNode.End] 的 outcome 合法取值。 */
object ChainEndOutcome {
    const val COMPLETED = "COMPLETED"
    const val CANCELLED = "CANCELLED"
}

/**
 * ChainNode 列表的 JSON 序列化/反序列化 + 静态校验。
 *
 * 复用项目已有的 org.json 解析能力（同 WorkflowEngine.paramsToJson），不引入
 * Gson/Moshi 等额外依赖。所有方法均为纯函数，不依赖 Android 环境，可直接 JVM 单测。
 */
object ChainNodeCodec {

    // ── 序列化：List<ChainNode> → JSON String ──────────────

    fun serialize(nodes: List<ChainNode>): String {
        val arr = JSONArray()
        for (node in nodes) {
            arr.put(serializeNode(node))
        }
        return arr.toString()
    }

    private fun serializeNode(node: ChainNode): JSONObject = when (node) {
        is ChainNode.Wait -> JSONObject().apply {
            put("type", ChainNodeType.WAIT)
            put("id", node.id)
            put("durationMs", node.durationMs)
        }
        is ChainNode.Check -> JSONObject().apply {
            put("type", ChainNodeType.CHECK)
            put("id", node.id)
            put("expression", node.expression)
            // checkToolName 可空，非空时才写入（省略 JSON 体积，反序列化时缺失即 null）
            node.checkToolName?.let { put("checkToolName", it) }
            put("onTrue", node.onTrue)
            put("onFalse", node.onFalse)
        }
        is ChainNode.Action -> JSONObject().apply {
            put("type", ChainNodeType.ACTION)
            put("id", node.id)
            put("goal", node.goal)
            put("next", node.next)
        }
        is ChainNode.End -> JSONObject().apply {
            put("type", ChainNodeType.END)
            put("id", node.id)
            put("outcome", node.outcome)
        }
    }

    // ── 反序列化：JSON String → List<ChainNode> ────────────

    /**
     * 将 nodesJson 反序列化为 [List<ChainNode>]。
     *
     * @throws IllegalArgumentException JSON 格式错误、type 未知、必填字段缺失时抛出，
     *         由调用方（ChainCreateTool.execute()）捕获后返回用户可读的错误信息。
     */
    fun deserialize(json: String): List<ChainNode> {
        val arr = try {
            JSONArray(json)
        } catch (e: Exception) {
            throw IllegalArgumentException("nodesJson 不是合法的 JSON 数组: ${e.message}")
        }
        if (arr.length() == 0) {
            throw IllegalArgumentException("nodesJson 不能为空数组")
        }
        val nodes = mutableListOf<ChainNode>()
        for (i in 0 until arr.length()) {
            try {
                val obj = arr.getJSONObject(i)
                nodes.add(deserializeNode(obj))
            } catch (e: Exception) {
                throw IllegalArgumentException("nodesJson 第 $i 个节点解析失败: ${e.message}")
            }
        }
        return nodes
    }

    private fun deserializeNode(obj: JSONObject): ChainNode {
        val type = obj.getString("type")
        val id = obj.getString("id")
        return when (type) {
            ChainNodeType.WAIT -> ChainNode.Wait(
                id = id,
                durationMs = obj.getLong("durationMs"),
            )
            ChainNodeType.CHECK -> ChainNode.Check(
                id = id,
                expression = obj.getString("expression"),
                checkToolName = obj.optStringOrNull("checkToolName"),
                onTrue = obj.getString("onTrue"),
                onFalse = obj.getString("onFalse"),
            )
            ChainNodeType.ACTION -> ChainNode.Action(
                id = id,
                goal = obj.getString("goal"),
                next = obj.getString("next"),
            )
            ChainNodeType.END -> ChainNode.End(
                id = id,
                outcome = obj.getString("outcome"),
            )
            else -> throw IllegalArgumentException("未知的节点 type: $type")
        }
    }

    /** org.json 的 optString 返回 "" 而非 null，此处封装为真正的 nullable String。 */
    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) getString(key) else null

    // ── 静态校验（§7 规则 #2 / #3 / #5）─────────────────────

    /**
     * 对节点列表执行 §7 的静态校验。返回 [ChainValidationResult]，
     * valid=false 时 error 包含具体原因（供 ChainCreateTool 返回给 LLM/用户）。
     *
     * 校验规则：
     * - #2：所有 next/onTrue/onFalse 引用的 id 必须存在于数组内（防悬空引用）
     * - #3：从首节点沿引用遍历，至少能走到一个 End 节点（防死循环链条）
     * - #5：Check 节点的 expression 不能同时包含 && 和 ||（见 §4，混用直接判非法）
     *
     * 注意：规则 #1（charId < 0 拒绝）和 #4（checkToolName 白名单校验）属于
     * ChainCreateTool.execute() 运行时校验，不在此处——它们依赖调用上下文
     * （characterId 参数、SAFE_TOOL_NAMES 白名单），不是纯节点结构校验。
     */
    fun validate(nodes: List<ChainNode>): ChainValidationResult {
        if (nodes.isEmpty()) {
            return ChainValidationResult(false, "节点列表不能为空")
        }

        // 规则 #5：Check 节点 expression 不能混用 && 和 ||
        for (node in nodes) {
            if (node is ChainNode.Check) {
                val hasAnd = node.expression.contains("&&")
                val hasOr = node.expression.contains("||")
                if (hasAnd && hasOr) {
                    return ChainValidationResult(
                        false,
                        "Check 节点 ${node.id} 的 expression 不能同时包含 && 和 ||（§4），" +
                            "请用多个 Check 节点串联表达复杂组合",
                    )
                }
            }
        }

        // 收集所有节点 id
        val idSet = nodes.map { it.id }.toSet()
        val idToIndex = nodes.mapIndexed { i, n -> n.id to i }.toMap()

        // 规则 #2：所有 next/onTrue/onFalse 引用的 id 必须存在于数组内
        for (node in nodes) {
            when (node) {
                is ChainNode.Action -> {
                    if (node.next !in idSet) {
                        return ChainValidationResult(
                            false,
                            "Action 节点 ${node.id} 的 next='${node.next}' 不存在于节点数组中",
                        )
                    }
                }
                is ChainNode.Check -> {
                    if (node.onTrue !in idSet) {
                        return ChainValidationResult(
                            false,
                            "Check 节点 ${node.id} 的 onTrue='${node.onTrue}' 不存在于节点数组中",
                        )
                    }
                    if (node.onFalse !in idSet) {
                        return ChainValidationResult(
                            false,
                            "Check 节点 ${node.id} 的 onFalse='${node.onFalse}' 不存在于节点数组中",
                        )
                    }
                }
                is ChainNode.Wait, is ChainNode.End -> {
                    // Wait 无显式出边（推进到 index+1），End 无出边，无需校验引用
                }
            }
        }

        // 规则 #3：从首节点沿引用遍历，至少能走到一个 End 节点
        if (!isEndReachable(nodes, idToIndex)) {
            return ChainValidationResult(
                false,
                "从首节点 ${nodes[0].id} 出发无法到达任何 End 节点，链条可能死循环",
            )
        }

        return ChainValidationResult(true)
    }

    /**
     * 规则 #3 可达性检查：BFS 从首节点（index=0）出发，沿引用遍历。
     *
     * - Wait：推进到 index+1（数组中下一个节点）
     * - Action：跳转到 next 指向的节点
     * - Check：分别走向 onTrue 和 onFalse 指向的节点
     * - End：找到可达的终止节点，返回 true
     *
     * 使用 visited 集合防止环导致的无限遍历。Wait 的"下一个"是 index+1 而非 id 引用，
     * 因为 §3.3 Wait 没有 next 字段——ChainEngine 执行完 Wait 后推进 currentNodeIndex。
     */
    private fun isEndReachable(
        nodes: List<ChainNode>,
        idToIndex: Map<String, Int>,
    ): Boolean {
        val visited = mutableSetOf<Int>()
        val queue = ArrayDeque<Int>()
        queue.add(0) // 从首节点开始

        while (queue.isNotEmpty()) {
            val idx = queue.removeFirst()
            if (idx in visited) continue
            visited.add(idx)

            when (val node = nodes[idx]) {
                is ChainNode.End -> return true
                is ChainNode.Wait -> {
                    // Wait 推进到数组中下一个节点
                    if (idx + 1 < nodes.size) queue.add(idx + 1)
                }
                is ChainNode.Action -> {
                    idToIndex[node.next]?.let { queue.add(it) }
                }
                is ChainNode.Check -> {
                    idToIndex[node.onTrue]?.let { queue.add(it) }
                    idToIndex[node.onFalse]?.let { queue.add(it) }
                }
            }
        }
        return false
    }

    /**
     * 便捷方法：反序列化 + 校验一步到位。
     * 反序列化失败或校验不通过均返回 [ChainParseResult.Failure]。
     */
    fun parseAndValidate(json: String): ChainParseResult {
        val nodes = try {
            deserialize(json)
        } catch (e: IllegalArgumentException) {
            return ChainParseResult.Failure(e.message ?: "nodesJson 解析失败")
        }
        val result = validate(nodes)
        return if (result.valid) {
            ChainParseResult.Success(nodes)
        } else {
            ChainParseResult.Failure(result.error ?: "校验失败")
        }
    }
}

/** [ChainNodeCodec.validate] 的返回值。 */
data class ChainValidationResult(
    val valid: Boolean,
    val error: String? = null,
)

/** [ChainNodeCodec.parseAndValidate] 的返回值。 */
sealed class ChainParseResult {
    data class Success(val nodes: List<ChainNode>) : ChainParseResult()
    data class Failure(val error: String) : ChainParseResult()
}
