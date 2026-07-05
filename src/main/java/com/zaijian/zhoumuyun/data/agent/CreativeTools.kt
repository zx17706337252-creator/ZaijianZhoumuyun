package com.zaijian.zhoumuyun.data.agent

import com.zaijian.zhoumuyun.data.provider.chatSyncWithRetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fix-17 拆分 · CreativeTools.kt
 *
 * ═══════════════════════════════════════════════════════════════
 * 创作/代码能力工具（2个）
 * ═══════════════════════════════════════════════════════════════
 *
 * 工具列表：
 *   ① CodeGenTool    — 代码生成（code_gen）
 *   ② CodeReviewTool — 代码审查（code_review）
 *
 * 注册方式（在 ZaijianApp.onCreate 中）：
 * ```kotlin
 * AgentToolRegistry.registerAll(
 *     CodeGenTool(),
 *     CodeReviewTool(),
 * )
 * ```
 *
 * 原位置：BuiltinTools.kt ⑭⑮（Phase 18）
 * ═══════════════════════════════════════════════════════════════
 */

// ─────────────────────────────────────────────────────────────
//  ① CodeGenTool
// ─────────────────────────────────────────────────────────────

/**
 * 代码生成工具（Phase 18）。
 *
 * 标签格式：<tool:code_gen lang="kotlin" desc="实现一个冒泡排序函数"/>
 * 可选参数：context="已有的相关代码片段"
 *
 * 实现：调用 LLM（通过 ProviderManager.activeProvider.chatSync），
 * 使用代码生成专用 System Prompt（简洁、仅输出代码块）。
 *
 * 结果回注给主 LLM，由角色以第一人称汇报结果。
 */
class CodeGenTool : AgentTool {

    override val name      = "code_gen"
    override val paramKeys = listOf("lang", "desc", "context")

    private companion object {
        const val MAX_TOKENS = 800
    }

    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val lang = params["lang"]?.trim() ?: "kotlin"
        val desc = params["desc"]?.trim()
        val ctx  = params["context"]?.trim()

        if (desc.isNullOrEmpty()) {
            return@withContext ToolResult(name, false, "", "需要 desc 参数描述代码功能")
        }

        val provider = com.zaijian.zhoumuyun.data.provider.ProviderManager.instance.activeProvider
            ?: return@withContext ToolResult(name, false, "当前未配置 API，无法生成代码。")

        val prompt = buildString {
            append("用 $lang 实现以下功能，只输出代码块（```$lang ... ```），不要任何解释：\n")
            append(desc)
            if (!ctx.isNullOrEmpty()) {
                append("\n\n现有代码参考：\n$ctx")
            }
        }

        try {
            val systemPrompt = "你是一个代码生成助手。只输出代码，不输出解释或其他文字。代码用 Markdown 代码块包裹。"
            val result = provider.chatSyncWithRetry(
                messages     = listOf(com.zaijian.zhoumuyun.data.provider.LLMMessage("user", prompt)),
                systemPrompt = systemPrompt,
                config       = com.zaijian.zhoumuyun.data.provider.LLMConfig(
                    model       = "",
                    maxTokens   = MAX_TOKENS,
                    temperature = 0.2f,
                    stream      = false,
                ),
            )

            ToolResult(
                toolName = name,
                success  = true,
                content  = "[生成的 $lang 代码]\n$result",
                userHint = "正在生成代码…",
            )
        } catch (e: Exception) {
            ToolResult(name, false, "代码生成失败：${e.message?.take(80)}", e.message)
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  ② CodeReviewTool
// ─────────────────────────────────────────────────────────────

/**
 * 代码审查工具（Phase 18）。
 *
 * 标签格式：<tool:code_review code="代码内容"/>
 * 可选参数：lang="kotlin"，focus="性能/安全/可读性"（默认综合）
 *
 * 实现：调用 LLM 做代码审查，要求返回结构化审查意见：
 *   - 问题列表（严重程度 + 描述）
 *   - 改进建议
 *   - 总体评分（1-10）
 */
class CodeReviewTool : AgentTool {

    override val name      = "code_review"
    override val paramKeys = listOf("code", "lang", "focus")

    private companion object {
        const val MAX_CODE_CHARS = 4_000
        const val MAX_TOKENS     = 600
    }

    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val code  = params["code"]?.trim()
        val lang  = params["lang"]?.trim() ?: "（未指定语言）"
        val focus = params["focus"]?.trim() ?: "综合"

        if (code.isNullOrEmpty()) {
            return@withContext ToolResult(name, false, "", "需要 code 参数")
        }

        val provider = com.zaijian.zhoumuyun.data.provider.ProviderManager.instance.activeProvider
            ?: return@withContext ToolResult(name, false, "当前未配置 API，无法审查代码。")

        val truncatedCode = if (code.length > MAX_CODE_CHARS)
            code.take(MAX_CODE_CHARS) + "\n（代码过长，已截断）"
        else code

        val prompt = """
请对以下 $lang 代码进行代码审查（关注点：$focus）。

```$lang
$truncatedCode
```

输出格式：
1. 发现的问题（每条注明严重程度：高/中/低）
2. 改进建议（最多3条，简洁）
3. 综合评分：X/10
        """.trimIndent()

        try {
            val systemPrompt = "你是一个代码审查专家。简洁客观地指出代码问题，不需要夸奖，只需要给出实质性建议。"
            val result = provider.chatSyncWithRetry(
                messages     = listOf(com.zaijian.zhoumuyun.data.provider.LLMMessage("user", prompt)),
                systemPrompt = systemPrompt,
                config       = com.zaijian.zhoumuyun.data.provider.LLMConfig(
                    model       = "",
                    maxTokens   = MAX_TOKENS,
                    temperature = 0.3f,
                    stream      = false,
                ),
            )

            ToolResult(
                toolName = name,
                success  = true,
                content  = "[代码审查结果]\n$result",
                userHint = "正在审查代码…",
            )
        } catch (e: Exception) {
            ToolResult(name, false, "代码审查失败：${e.message?.take(80)}", e.message)
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  模块注册入口
// ─────────────────────────────────────────────────────────────

/**
 * 注册所有创作/代码工具（2个）。
 * 在 ZaijianApp.onCreate() 中调用。
 */
fun AgentToolRegistry.registerCreativeTools() {
    registerAll(
        CodeGenTool(),
        CodeReviewTool(),
    )
}
