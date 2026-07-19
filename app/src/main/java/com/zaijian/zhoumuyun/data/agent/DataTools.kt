package com.zaijian.zhoumuyun.data.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Fix-17 拆分 · DataTools.kt
 *
 * ═══════════════════════════════════════════════════════════════
 * 数据计算工具（3个）
 * ═══════════════════════════════════════════════════════════════
 *
 * 工具列表：
 *   ① CalculatorTool   — 本地计算器（calculator）
 *   ② UnitConvertTool  — 单位/汇率换算（unit_convert）
 *   ③ CountdownTool    — 日期差/倒计时（countdown）
 *
 * 注册方式（在 ZaijianApp.onCreate 中）：
 * ```kotlin
 * AgentToolRegistry.registerAll(
 *     CalculatorTool(),
 *     UnitConvertTool(),
 *     CountdownTool(),
 * )
 * ```
 *
 * 原位置：BuiltinTools.kt ②⑩⑪（Phase 13 / Phase 18）
 * ═══════════════════════════════════════════════════════════════
 */

// ─────────────────────────────────────────────────────────────
//  ① CalculatorTool
// ─────────────────────────────────────────────────────────────

/**
 * 本地计算器工具。
 *
 * 标签格式：<tool:calculator expr="(100 + 200) * 1.13"/>
 *
 * 支持：
 *   - 四则运算：+ - * /
 *   - 幂运算：^（如 2^10）
 *   - 括号
 *   - 数学函数：sqrt, sin, cos, tan, log, ln, abs, ceil, floor, round, exp
 *   - 常数：pi, e
 *   - 百分号：50% → 0.5（在表达式中使用）
 *
 * 单位换算（识别关键词，转换为数字表达式）：
 *   - 货币：仅占位返回提示（无 Key 的免费汇率 API 精度不足，避免误导）
 *   - 长度/重量/温度：内置换算系数
 *
 * 注意：表达式求值使用递归下降解析器（纯 Kotlin，无第三方依赖）。
 */
class CalculatorTool : AgentTool {

    override val name = "calculator"
    override val description = "本地四则运算/函数计算器，用于数学表达式求值，不联网"
    override val paramKeys = listOf("expr")

    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val expr = params["expr"]?.trim()
        if (expr.isNullOrEmpty()) {
            return@withContext ToolResult(name, false, "", "缺少 expr 参数")
        }

        try {
            // 先检查是否为单位换算快捷指令
            val unitResult = tryUnitConversion(expr)
            if (unitResult != null) {
                return@withContext ToolResult(name, true, "[计算结果]\n$expr = $unitResult")
            }

            // 通用数学表达式求值
            val result = ExpressionEvaluator.evaluate(expr)
            val formatted = formatResult(result)
            ToolResult(
                toolName = name,
                success  = true,
                content  = "[计算结果]\n$expr = $formatted",
            )
        } catch (e: Exception) {
            // 修复（第3窗口审查报告问题6）：失败路径统一为 content="" + error="描述"，
            // 与项目其他工具（UnitConvertTool/CountdownTool/WebSearchTool 等）保持一致，
            // 避免 ToolCallInterceptor 回注 LLM 时出现冗余/混乱的双重错误文本。
            ToolResult(
                toolName = name,
                success  = false,
                content  = "",
                error    = "表达式「$expr」无法计算：${e.message}",
            )
        }
    }

    private fun formatResult(value: Double): String {
        return if (value == value.toLong().toDouble() && !value.isInfinite()) {
            value.toLong().toString()
        } else {
            "%.6g".format(value).trimEnd('0').trimEnd('.')
        }
    }

    /**
     * 单位换算快捷检测。
     *
     * 支持格式：`100 km to miles`、`30 celsius to fahrenheit`、`5 kg to lbs`
     */
    private fun tryUnitConversion(expr: String): String? {
        val pattern = Regex(
            """^([\d.]+)\s*([a-zA-Z°]+)\s+(?:to|转|换|→)\s+([a-zA-Z°]+)$""",
            RegexOption.IGNORE_CASE,
        )
        val match = pattern.find(expr.trim()) ?: return null
        val value  = match.groupValues[1].toDoubleOrNull() ?: return null
        val fromU  = match.groupValues[2].lowercase()
        val toU    = match.groupValues[3].lowercase()

        val result = convertUnit(value, fromU, toU) ?: return null
        return formatResult(result) + " $toU"
    }

    private fun convertUnit(value: Double, from: String, to: String): Double? {
        // 长度（统一到米）
        val toMeter = mapOf(
            "km" to 1000.0, "m" to 1.0, "cm" to 0.01, "mm" to 0.001,
            "mile" to 1609.344, "miles" to 1609.344,
            "yard" to 0.9144, "yards" to 0.9144,
            "foot" to 0.3048, "feet" to 0.3048, "ft" to 0.3048,
            "inch" to 0.0254, "inches" to 0.0254, "in" to 0.0254,
        )
        // 重量（统一到克）
        val toGram = mapOf(
            "kg" to 1000.0, "g" to 1.0, "mg" to 0.001,
            "lb" to 453.592, "lbs" to 453.592, "pound" to 453.592, "pounds" to 453.592,
            "oz" to 28.3495, "ounce" to 28.3495, "ounces" to 28.3495,
            "ton" to 1_000_000.0, "tonne" to 1_000_000.0,
        )
        // 温度（特殊处理）
        if (from in listOf("celsius", "°c", "c") && to in listOf("fahrenheit", "°f", "f")) {
            return value * 9.0 / 5.0 + 32
        }
        if (from in listOf("fahrenheit", "°f", "f") && to in listOf("celsius", "°c", "c")) {
            return (value - 32) * 5.0 / 9.0
        }
        if (from in listOf("celsius", "°c", "c") && to in listOf("kelvin", "k")) {
            return value + 273.15
        }
        if (from in listOf("kelvin", "k") && to in listOf("celsius", "°c", "c")) {
            return value - 273.15
        }

        val fromMeter = toMeter[from]
        val toMeter2  = toMeter[to]
        if (fromMeter != null && toMeter2 != null) {
            return value * fromMeter / toMeter2
        }

        val fromGram = toGram[from]
        val toGram2  = toGram[to]
        if (fromGram != null && toGram2 != null) {
            return value * fromGram / toGram2
        }

        return null
    }
}

// ─────────────────────────────────────────────────────────────
//  递归下降表达式求值器（CalculatorTool 内部使用）
// ─────────────────────────────────────────────────────────────

/**
 * 简单递归下降解析器，支持：
 *   expr   → term (('+' | '-') term)*
 *   term   → power (('*' | '/') power)*
 *   power  → unary ('^' unary)*
 *   unary  → '-' unary | primary
 *   primary→ number | constant | func '(' expr ')' | '(' expr ')'
 */
internal object ExpressionEvaluator {

    fun evaluate(expr: String): Double {
        // 预处理：百分号 → /100，去空格
        val processed = expr
            .trim()
            .replace(Regex("""(\d+\.?\d*)%""")) { mr -> "(${mr.groupValues[1]}/100)" }
        val parser = Parser(processed)
        val result = parser.parseExpr()
        if (!parser.isEnd()) throw IllegalArgumentException("意外字符：${parser.peek()}")
        return result
    }

    private class Parser(private val input: String) {
        private var pos = 0

        fun isEnd() = pos >= input.length
        fun peek() = if (isEnd()) '\u0000' else input[pos]

        private fun consume() = input[pos++]

        private fun skipWs() { while (!isEnd() && input[pos].isWhitespace()) pos++ }

        fun parseExpr(): Double {
            skipWs()
            var result = parseTerm()
            while (true) {
                skipWs()
                when (peek()) {
                    '+' -> { consume(); skipWs(); result += parseTerm() }
                    '-' -> { consume(); skipWs(); result -= parseTerm() }
                    else -> break
                }
            }
            return result
        }

        private fun parseTerm(): Double {
            var result = parsePower()
            while (true) {
                skipWs()
                when (peek()) {
                    '*' -> { consume(); skipWs(); result *= parsePower() }
                    '/' -> {
                        consume(); skipWs()
                        val divisor = parsePower()
                        if (divisor == 0.0) throw ArithmeticException("除数不能为零")
                        result /= divisor
                    }
                    else -> break
                }
            }
            return result
        }

        private fun parsePower(): Double {
            var base = parseUnary()
            skipWs()
            if (peek() == '^') {
                consume(); skipWs()
                val exp = parseUnary()
                base = base.pow(exp)
            }
            return base
        }

        private fun parseUnary(): Double {
            skipWs()
            return if (peek() == '-') { consume(); -parsePrimary() }
            else parsePrimary()
        }

        private fun parsePrimary(): Double {
            skipWs()
            return when {
                peek() == '(' -> {
                    consume()
                    val v = parseExpr()
                    skipWs()
                    if (peek() != ')') throw IllegalArgumentException("缺少右括号")
                    consume()
                    v
                }
                peek().isLetter() -> parseIdentifier()
                peek().isDigit() || peek() == '.' -> parseNumber()
                else -> throw IllegalArgumentException("无法解析：'${peek()}'")
            }
        }

        private fun parseNumber(): Double {
            val start = pos
            while (!isEnd() && (input[pos].isDigit() || input[pos] == '.')) pos++
            // 科学计数法支持：1.5e10
            if (!isEnd() && (input[pos] == 'e' || input[pos] == 'E')) {
                pos++
                if (!isEnd() && (input[pos] == '+' || input[pos] == '-')) pos++
                while (!isEnd() && input[pos].isDigit()) pos++
            }
            return input.substring(start, pos).toDouble()
        }

        private fun parseIdentifier(): Double {
            val start = pos
            while (!isEnd() && input[pos].isLetter()) pos++
            val id = input.substring(start, pos).lowercase()
            skipWs()

            // 常数
            if (id == "pi") return Math.PI
            if (id == "e" && peek() != '(') return Math.E

            // 函数（必须跟括号）
            if (peek() != '(') throw IllegalArgumentException("未知标识符：$id")
            consume()
            val arg = parseExpr()
            skipWs()
            if (peek() != ')') throw IllegalArgumentException("函数 $id 缺少右括号")
            consume()

            return when (id) {
                "sqrt"  -> sqrt(arg)
                "sin"   -> sin(arg)
                "cos"   -> cos(arg)
                "tan"   -> tan(arg)
                "log"   -> log10(arg)
                "log10" -> log10(arg)
                "ln"    -> ln(arg)
                "abs"   -> abs(arg)
                "ceil"  -> ceil(arg)
                "floor" -> floor(arg)
                "round" -> arg.roundToLong().toDouble()
                "exp"   -> exp(arg)
                else    -> throw IllegalArgumentException("未知函数：$id")
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  ② UnitConvertTool
// ─────────────────────────────────────────────────────────────

/**
 * 单位/汇率换算工具（Phase 18）。
 *
 * 标签格式：<tool:unit_convert value="100" from="km" to="mile"/>
 *
 * 支持类别：
 *   长度：m/km/cm/mm/inch/foot/yard/mile/nautical_mile
 *   重量：kg/g/mg/lb/oz/ton
 *   温度：celsius/fahrenheit/kelvin
 *   面积：m2/km2/cm2/mm2/ha/acre/ft2/inch2
 *   体积：L/mL/m3/gallon/fl_oz/cup/tbsp/tsp
 *   速度：m/s/km/h/mph/knot
 *   数据：bit/byte/KB/MB/GB/TB
 *   时间：s/min/hour/day/week/month/year
 *
 * 汇率：固定汇率（CNY为基准，2024年参考汇率），无需网络。
 */
class UnitConvertTool : AgentTool {

    override val name      = "unit_convert"
    override val description = "长度/重量/温度/面积/体积/速度/数据/时间等单位换算，固定汇率不联网"
    override val paramKeys = listOf("value", "from", "to")

    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val valueStr = params["value"]?.trim()
        val from     = params["from"]?.trim()?.lowercase()
        val to       = params["to"]?.trim()?.lowercase()

        if (valueStr.isNullOrEmpty() || from.isNullOrEmpty() || to.isNullOrEmpty()) {
            return@withContext ToolResult(name, false, "", "需要 value、from、to 三个参数")
        }
        val value = valueStr.toDoubleOrNull()
            ?: return@withContext ToolResult(name, false, "「$valueStr」不是有效的数字。")

        try {
            val result = convert(value, from, to)
            val formatted = if (result == result.toLong().toDouble()) result.toLong().toString()
                            else "%.4f".format(result).trimEnd('0').trimEnd('.')
            ToolResult(
                toolName = name,
                success  = true,
                content  = "$value $from = $formatted $to",
                userHint = "正在换算…",
            )
        } catch (e: IllegalArgumentException) {
            ToolResult(name, false, e.message ?: "不支持的单位换算。")
        }
    }

    private fun convert(value: Double, from: String, to: String): Double {
        if (from == to) return value

        // 温度单独处理（非线性）
        val tempUnits = setOf("celsius", "fahrenheit", "kelvin", "c", "f", "k")
        if (from in tempUnits || to in tempUnits) return convertTemp(value, from, to)

        // 其他单位：找到同类别的基准倍率
        val fromRate = unitToBase(from) ?: throw IllegalArgumentException("不认识单位「$from」，请用英文单位名称。")
        val toRate   = unitToBase(to)   ?: throw IllegalArgumentException("不认识单位「$to」，请用英文单位名称。")
        return value * fromRate / toRate
    }

    /** 转换为该类别基准单位的倍率。不同类别的基准单位不可互转（自动检测）。 */
    private fun unitToBase(unit: String): Double? = when (unit) {
        // 长度（基准：m）
        "m","meter","meters"            -> 1.0
        "km","kilometer","kilometers"   -> 1000.0
        "cm","centimeter","centimeters" -> 0.01
        "mm","millimeter","millimeters" -> 0.001
        "inch","inches","in"            -> 0.0254
        "foot","feet","ft"              -> 0.3048
        "yard","yards","yd"             -> 0.9144
        "mile","miles","mi"             -> 1609.344
        "nautical_mile","nmi"           -> 1852.0
        // 重量（基准：kg）
        "kg","kilogram","kilograms"     -> 1.0
        "g","gram","grams"              -> 0.001
        "mg","milligram","milligrams"   -> 0.000001
        "lb","lbs","pound","pounds"     -> 0.453592
        "oz","ounce","ounces"           -> 0.028350
        "ton","metric_ton","tonnes"     -> 1000.0
        // 面积（基准：m²）
        "m2","sqm","square_meter"       -> 1.0
        "km2","sqkm","square_km"        -> 1_000_000.0
        "cm2","sqcm","square_cm"        -> 0.0001
        "mm2","sqmm","square_mm"        -> 0.000001
        "ha","hectare","hectares"       -> 10_000.0
        "acre","acres"                  -> 4046.856
        "ft2","sqft","square_foot"      -> 0.092903
        "inch2","sqin","square_inch"    -> 0.000645
        // 体积（基准：L）
        "l","liter","liters","litre"    -> 1.0
        "ml","milliliter","milliliters" -> 0.001
        "m3","cubic_meter"              -> 1000.0
        "gallon","gallons","gal"        -> 3.78541
        "fl_oz","fluid_ounce"           -> 0.029574
        "cup","cups"                    -> 0.236588
        "tbsp","tablespoon"             -> 0.014787
        "tsp","teaspoon"                -> 0.004929
        // 速度（基准：m/s）
        "m/s","mps","meters_per_second" -> 1.0
        "km/h","kph","kmh"              -> 1.0 / 3.6
        "mph","miles_per_hour"          -> 0.44704
        "knot","knots","kt"             -> 0.514444
        // 数据（基准：bit）
        "bit","bits"                    -> 1.0
        "byte","bytes","b"              -> 8.0
        "kb","kilobyte","kilobytes"     -> 8.0 * 1024
        "mb","megabyte","megabytes"     -> 8.0 * 1024 * 1024
        "gb","gigabyte","gigabytes"     -> 8.0 * 1024 * 1024 * 1024
        "tb","terabyte","terabytes"     -> 8.0 * 1024 * 1024 * 1024 * 1024
        // 时间（基准：秒）
        "s","sec","second","seconds"    -> 1.0
        "min","minute","minutes"        -> 60.0
        "hour","hours","h","hr"         -> 3600.0
        "day","days","d"                -> 86400.0
        "week","weeks","wk"             -> 604800.0
        "month","months"                -> 2_629_800.0  // 平均月（365.25/12天）
        "year","years","yr"             -> 31_557_600.0 // 儒略年
        // 汇率（基准：CNY）
        "cny","rmb","yuan"              -> 1.0
        "usd","dollar","dollars"        -> 7.24
        "eur","euro","euros"            -> 7.88
        "gbp","pound_sterling"          -> 9.18
        "jpy","yen"                     -> 0.048
        "hkd","hk_dollar"              -> 0.927
        "cad","canadian_dollar"         -> 5.32
        "aud","australian_dollar"       -> 4.71
        "krw","won"                     -> 0.0053
        "sgd","singapore_dollar"        -> 5.41
        else                            -> null
    }

    private fun convertTemp(value: Double, from: String, to: String): Double {
        val normalFrom = when (from) { "c" -> "celsius"; "f" -> "fahrenheit"; "k" -> "kelvin"; else -> from }
        val normalTo   = when (to)   { "c" -> "celsius"; "f" -> "fahrenheit"; "k" -> "kelvin"; else -> to }
        // 先转 Celsius
        val celsius = when (normalFrom) {
            "celsius"    -> value
            "fahrenheit" -> (value - 32) * 5.0 / 9.0
            "kelvin"     -> value - 273.15
            else -> throw IllegalArgumentException("不认识温度单位「$from」")
        }
        return when (normalTo) {
            "celsius"    -> celsius
            "fahrenheit" -> celsius * 9.0 / 5.0 + 32
            "kelvin"     -> celsius + 273.15
            else -> throw IllegalArgumentException("不认识温度单位「$to」")
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  ③ CountdownTool
// ─────────────────────────────────────────────────────────────

/**
 * 日期差/倒计时计算工具（Phase 18）。
 *
 * 标签格式：<tool:countdown to="2025-12-31"/>
 * 可选参数：from="2024-01-01"（默认今天）
 *
 * 返回：相差天数 + 人类可读描述（"还有 X 天"/"已过去 X 天"）。
 * 同时返回：相差周数、月数（近似值）。
 */
class CountdownTool : AgentTool {

    override val name      = "countdown"
    override val description = "计算两个日期之间相差的天数/周数/月数，用于倒计时或已过去多久"
    override val paramKeys = listOf("to", "from")

    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val toStr   = params["to"]?.trim()
        val fromStr = params["from"]?.trim()

        if (toStr.isNullOrEmpty()) {
            return@withContext ToolResult(name, false, "", "需要 to 参数（格式：yyyy-MM-dd）")
        }

        try {
            val fmt  = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            val toDate   = fmt.parse(toStr)   ?: return@withContext ToolResult(name, false, "日期格式错误，请用 yyyy-MM-dd 格式。")
            val fromDate = if (fromStr != null) fmt.parse(fromStr) else java.util.Date()
                           ?: java.util.Date()

            val diffMs    = toDate.time - fromDate.time
            val diffDays  = diffMs / (1000L * 60 * 60 * 24)
            val diffWeeks = diffDays / 7
            val diffMonths = diffDays / 30

            val description = when {
                diffDays == 0L  -> "就是今天！"
                diffDays > 0L   -> "还有 ${diffDays} 天（约 ${diffWeeks} 周 / 约 ${diffMonths} 个月）"
                else            -> "已过去 ${-diffDays} 天（约 ${-diffWeeks} 周 / 约 ${-diffMonths} 个月）"
            }

            ToolResult(
                toolName = name,
                success  = true,
                content  = "从 ${fromStr ?: "今天"} 到 $toStr：$description",
                userHint = "正在计算日期差…",
            )
        } catch (e: Exception) {
            ToolResult(name, false, "日期计算出错：${e.message?.take(60)}")
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  模块注册入口
// ─────────────────────────────────────────────────────────────

/**
 * 注册所有数据计算工具（3个）。
 * 在 ZaijianApp.onCreate() 中调用。
 */
fun AgentToolRegistry.registerDataTools() {
    registerAll(
        CalculatorTool(),
        UnitConvertTool(),
        CountdownTool(),
    )
}
