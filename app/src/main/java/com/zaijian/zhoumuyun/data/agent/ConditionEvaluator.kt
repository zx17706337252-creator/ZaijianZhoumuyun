package com.zaijian.zhoumuyun.data.agent

import org.json.JSONObject

/**
 * 灵活自动化编排 · 条件表达式求值器（§4）
 *
 * 受限表达式语法：
 * ```
 * <字段路径> <比较符> <字面量>
 * 字段路径 ::= context 里的点分路径，如 mood.energy、lastReply.minutesAgo
 * 比较符   ::= == | != | < | > | <= | >=
 * 字面量   ::= 数字 | "字符串" | true | false
 * ```
 *
 * 支持用 `&&`/`||` 组合多个子表达式，但**不支持二者混用**——一条 expression 里
 * 只能全用 `&&` 或全用 `||`。混用已在 §7 ChainCreateTool 静态校验阶段拦截，
 * 但 [evaluate] 自身也有防御性兜底：遇到混用输入判 `false` + 记录日志，不抛异常。
 *
 * **永不抛异常**：字段路径缺失、类型不匹配、表达式格式错误等所有无法完成比较的
 * 情况统一判为 `false` 并记录日志（含具体字段、期望类型、实际类型），交由链条
 * 自身的 `onFalse` 分支处理。这样 `ChainEngine.advance()` 调用它时不需要额外
 * 包一层 try-catch，与 §5 代码骨架保持一致。
 *
 * 纯函数，不依赖 Android 环境，可直接 JVM 单测。
 */
object ConditionEvaluator {

    private const val TAG = "ConditionEvaluator"

    /**
     * 对 [expression] 在给定 [context] 下求值，返回 true 或 false。
     *
     * 永不抛异常。所有无法完成比较的情况（字段缺失、类型不匹配、格式错误、
     * &&/|| 混用等）统一返回 false 并记录日志。
     *
     * @param expression Check 节点的 expression 字段，如 "mood.energy < 30"
     * @param context ChainRunEntity.context 解析出的 JSONObject，包含节点间传递的"事实"
     */
    fun evaluate(expression: String, context: JSONObject): Boolean {
        val trimmed = expression.trim()
        if (trimmed.isEmpty()) {
            logWarn("expression 为空，判 false")
            return false
        }

        // 防御性兜底：&& 和 || 混用（理应在 ChainCreateTool 创建阶段被拒绝，
        // 这里遇到属于防御性兜底，判 false + 记录日志，不抛异常）
        if (trimmed.contains("&&") && trimmed.contains("||")) {
            logWarn("expression 同时含 && 和 ||（混用），判 false: $trimmed")
            return false
        }

        // 按 && 或 || 拆分为子表达式
        val clauses = if (trimmed.contains("&&")) {
            trimmed.split("&&")
        } else if (trimmed.contains("||")) {
            trimmed.split("||")
        } else {
            listOf(trimmed)
        }

        val results = clauses.map { clause ->
            evaluateSingle(clause.trim(), context)
        }

        return when {
            trimmed.contains("&&") -> results.all { it }
            trimmed.contains("||") -> results.any { it }
            else -> results.first()
        }
    }

    // ── 单个子表达式求值：<字段路径> <比较符> <字面量> ──────

    /**
     * 对单个子表达式求值。格式：`<字段路径> <比较符> <字面量>`。
     *
     * 字段路径缺失、类型不匹配统一判 false + 记录日志。
     */
    private fun evaluateSingle(clause: String, context: JSONObject): Boolean {
        if (clause.isEmpty()) {
            logWarn("子表达式为空，判 false")
            return false
        }

        val parsed = parseClause(clause)
        if (parsed == null) {
            logWarn("子表达式格式无法解析，判 false: $clause")
            return false
        }

        val (fieldPath, operator, literal) = parsed

        // 从 context 中按点分路径取值
        val actualValue = resolveField(context, fieldPath)
        if (actualValue == null) {
            logWarn("字段路径 '$fieldPath' 在 context 中缺失，判 false")
            return false
        }

        // 类型匹配检查 + 比较
        return compareValues(actualValue, operator, literal, fieldPath)
    }

    // ── 子表达式解析 ──────────────────────────────────────

    /**
     * 解析单个子表达式为三元组：字段路径、比较符、字面量。
     *
     * 支持的比较符（按长度降序匹配，避免 <= 被 < 先吃掉）：
     * `<=` `>=` `==` `!=` `<` `>`
     *
     * 字面量支持：数字（整数/小数）、双引号包裹的字符串、true/false。
     *
     * **字符串字面量内含比较符字符的修复**：当字面量是双引号包裹的字符串
     * 且字符串内容本身含比较符字符时（如 `config.mode == "<="`），
     * 直接对整个 clause 做 indexOf 会错误地把字符串内部的比较符当成分隔符。
     * 修复方式：先判断 clause 是否以双引号结尾，若是则从末尾往前找到配对的起始引号，
     * 将运算符查找范围限制在起始引号之前的区间，不进入字符串内部查找。
     *
     * @return 解析成功返回 Triple(字段路径, 比较符, 字面量字符串)，失败返回 null
     */
    private fun parseClause(clause: String): Triple<String, String, String>? {
        // 按长度降序排列比较符，避免 < 先于 <= 匹配
        val operators = listOf("<=", ">=", "==", "!=", "<", ">")

        // 修复：如果 clause 以双引号结尾，说明字面量是字符串字面量。
        // 从末尾往前找到配对的起始引号，将运算符查找范围限制在起始引号之前，
        // 避免字符串内容中的比较符字符（如 "<=" 里的 <=）被误认为分隔符。
        var searchEnd = clause.length
        val stripped = clause.trimEnd()
        if (stripped.endsWith("\"")) {
            val lastQuoteIdx = stripped.lastIndexOf('"')
            val secondLastQuoteIdx = stripped.lastIndexOf('"', lastQuoteIdx - 1)
            if (secondLastQuoteIdx != -1) {
                searchEnd = secondLastQuoteIdx
            }
        }
        val searchArea = clause.substring(0, searchEnd)

        for (op in operators) {
            val idx = searchArea.indexOf(op)
            if (idx > 0) {
                val fieldPath = clause.substring(0, idx).trim()
                val literalStr = clause.substring(idx + op.length).trim()
                if (fieldPath.isNotEmpty() && literalStr.isNotEmpty()) {
                    return Triple(fieldPath, op, literalStr)
                }
            }
        }

        return null
    }

    // ── 字段路径取值 ──────────────────────────────────────

    /**
     * 按 JSONPath 风格的点分路径从 [context] 中取值。
     *
     * 如 "mood.energy" → context.optJSONObject("mood")?.opt("energy")。
     * 路径中任意一级缺失或类型不对（中间节点不是 JSONObject），返回 null。
     */
    private fun resolveField(context: JSONObject, path: String): Any? {
        val parts = path.split(".")
        if (parts.isEmpty()) return null

        var current: Any? = context
        for (part in parts) {
            current = when (current) {
                is JSONObject -> {
                    if (current.has(part) && !current.isNull(part)) current.get(part) else null
                }
                else -> null
            }
            if (current == null) return null
        }
        return current
    }

    // ── 值比较 ────────────────────────────────────────────

    /**
     * 比较 context 中的实际值与表达式中的字面量。
     *
     * 类型不匹配（如字段是字符串但表达式按数字比较）统一判 false + 记录日志。
     *
     * 字面量类型推断顺序：
     * 1. true/false → 布尔比较
     * 2. 双引号包裹 → 字符串比较
     * 3. 纯数字 → 数值比较
     */
    private fun compareValues(
        actualValue: Any,
        operator: String,
        literalStr: String,
        fieldPath: String,
    ): Boolean {
        // 1. 布尔字面量
        if (literalStr == "true" || literalStr == "false") {
            val expectedBool = literalStr.toBoolean()
            if (actualValue !is Boolean) {
                logWarn(
                    "类型不匹配：字段 '$fieldPath' 期望 Boolean 但实际是 " +
                        "${actualValue::class.simpleName}，判 false",
                )
                return false
            }
            return when (operator) {
                "==" -> actualValue == expectedBool
                "!=" -> actualValue != expectedBool
                else -> {
                    logWarn("Boolean 类型不支持比较符 '$operator'，判 false")
                    false
                }
            }
        }

        // 2. 字符串字面量（双引号包裹）
        if (literalStr.startsWith("\"") && literalStr.endsWith("\"") && literalStr.length >= 2) {
            val expectedStr = literalStr.substring(1, literalStr.length - 1)
            if (actualValue !is String) {
                logWarn(
                    "类型不匹配：字段 '$fieldPath' 期望 String 但实际是 " +
                        "${actualValue::class.simpleName}，判 false",
                )
                return false
            }
            return when (operator) {
                "==" -> actualValue == expectedStr
                "!=" -> actualValue != expectedStr
                else -> {
                    logWarn("String 类型不支持比较符 '$operator'，判 false")
                    false
                }
            }
        }

        // 3. 数值字面量
        val expectedNum = literalStr.toDoubleOrNull()
        if (expectedNum != null) {
            val actualNum = when (actualValue) {
                is Int -> actualValue.toDouble()
                is Long -> actualValue.toDouble()
                is Double -> actualValue
                is Float -> actualValue.toDouble()
                else -> null
            }
            if (actualNum == null) {
                logWarn(
                    "类型不匹配：字段 '$fieldPath' 期望 Number 但实际是 " +
                        "${actualValue::class.simpleName}，判 false",
                )
                return false
            }
            return when (operator) {
                "==" -> actualNum == expectedNum
                "!=" -> actualNum != expectedNum
                "<" -> actualNum < expectedNum
                ">" -> actualNum > expectedNum
                "<=" -> actualNum <= expectedNum
                ">=" -> actualNum >= expectedNum
                else -> {
                    logWarn("未知比较符 '$operator'，判 false")
                    false
                }
            }
        }

        // 字面量不是合法的数字/字符串/布尔
        logWarn("字面量 '$literalStr' 无法识别为合法类型（数字/字符串/布尔），判 false")
        return false
    }

    // ── 日志 ──────────────────────────────────────────────

    /**
     * 记录 warn 日志。使用 ZLog（对齐项目统一日志封装），但在纯 JVM 测试环境
     * 下 ZLog 依赖 android.util.Log 会抛异常，此处用 try-catch 兜底：
     * ZLog 不可用时降级到 System.err.println，不影响求值逻辑。
     */
    private fun logWarn(msg: String) {
        try {
            com.zaijian.zhoumuyun.util.ZLog.w(TAG, msg)
        } catch (e: Throwable) {
            // 纯 JVM 环境（单测）下 ZLog 不可用，降级到 stderr
            System.err.println("[$TAG] $msg")
        }
    }
}
