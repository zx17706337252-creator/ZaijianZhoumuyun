package com.zaijian.zhoumuyun.domain.pregnancy

// ─────────────────────────────────────────────────────────────────────────────
//  LlmIntentParse — LLM 意图解析共用工具
//
//  P1-2-3 修复：消除 PregnancyAnswerIntentDetector 与
//  PregnancyAnswerConsistencyChecker 之间重复的 JSON 清洗 + 裸文本回退逻辑。
//
//  两个解析器独立持有 SYSTEM_PROMPT 和枚举类型，只将以下共用逻辑抽到此处：
//    1. cleanLlmJson()  — 去除 ```json ... ``` Markdown 包装
//    2. YesNoMatcher    — 词边界正则预编译（YES/NO 两态，共享实例）
//    3. ConsistencyMatcher — 词边界正则预编译（CONSISTENT/CONTRADICTORY 两态）
//
//  调用方保持不变：各解析器的 parseResult() 使用 cleanLlmJson() 清洗后
//  先尝试 JSONObject 解析，失败时调用各自的 parseFallback()（委托到此处）。
// ─────────────────────────────────────────────────────────────────────────────

import org.json.JSONObject

/**
 * 去除 LLM 回复中可能包裹的 Markdown 代码块标记。
 * 部分模型返回 ```json\n...\n``` 格式，需先清洗才能被 JSONObject 解析。
 */
internal fun cleanLlmJson(raw: String): String =
    raw.replace("```json", "").replace("```", "").trim()

/**
 * 从 JSON 字符串中提取 "result" 字段值。
 *
 * 修复手册 Phase 2.3：原实现内部吞掉所有异常返回 null，导致调用方
 * （PregnancyAnswerConsistencyChecker/IntentDetector 的 parseResult()）
 * 外层 catch 块永远不可达，其中经过 P0-4 修复、专门处理子串误判问题的
 * parseConsistencyFallback/parseIntentFallback 正则回退逻辑因此永久失效
 * （所有解析失败的情况都被 when 表达式的 else 分支粗暴吞掉）。
 * 现改为解析失败时向上抛出异常，交由调用方 catch 块捕获并触发正则回退，
 * 恢复原有的"JSON解析失败 → 词边界正则兜底"两级容错设计意图。
 */
internal fun parseResultField(cleaned: String): String =
    JSONObject(cleaned).getString("result")

// ── 预编译正则（YES/NO 两态，IntentDetector 使用）────────────────────────────

/** YES/NO 否定词词边界正则（优先匹配，防止 "know" 中 "no" 子串误命中） */
internal val INTENT_NEGATIVE_RE = Regex("""(?i)\b(no|not|negative|nope|nah)\b""")

/** YES/NO 肯定词词边界正则 */
internal val INTENT_POSITIVE_RE = Regex("""(?i)\b(yes|yeah|yep|affirmative|correct|true)\b""")

/**
 * IntentDetector 裸文本回退解析（词边界正则替代 contains）。
 *
 * 策略：否定词优先 → 肯定词 → 保守返回 NO。
 * 解决：LLM 返回 "Yes, I know..." 时 "know" 包含 "no" 子串的误匹配问题。
 */
internal fun parseIntentFallback(cleaned: String): IntentResult {
    val text = cleaned.replace(Regex("[^A-Za-z\\s]"), " ")
    return when {
        INTENT_NEGATIVE_RE.containsMatchIn(text) -> IntentResult.NO
        INTENT_POSITIVE_RE.containsMatchIn(text) -> IntentResult.YES
        else                                     -> IntentResult.NO
    }
}

// ── 预编译正则（CONSISTENT/CONTRADICTORY 两态，ConsistencyChecker 使用）───────

/** 一致性否定词词边界正则（优先匹配，防止 "inconsistent" 包含 "consistent" 子串） */
internal val CONSISTENCY_NEGATIVE_RE =
    Regex("""(?i)\b(inconsisten|contradictor|conflict|differ|mismatch)\w*""")

/** 一致性肯定词词边界正则 */
internal val CONSISTENCY_POSITIVE_RE =
    Regex("""(?i)\b(consisten|same|match|align|agreement)\w*""")

/**
 * ConsistencyChecker 裸文本回退解析（词边界正则替代 contains）。
 *
 * 策略：否定词优先 → 肯定词 → 保守返回 CONTRADICTORY。
 * 解决：LLM 返回 "INCONSISTENT" 时被 "CONSISTENT" 子串误命中的 P0-4 问题。
 */
internal fun parseConsistencyFallback(cleaned: String): ConsistencyResult {
    val text = cleaned.replace(Regex("[^A-Za-z\\s]"), " ")
    return when {
        CONSISTENCY_NEGATIVE_RE.containsMatchIn(text) -> ConsistencyResult.CONTRADICTORY
        CONSISTENCY_POSITIVE_RE.containsMatchIn(text) -> ConsistencyResult.CONSISTENT
        else                                          -> ConsistencyResult.CONTRADICTORY
    }
}
