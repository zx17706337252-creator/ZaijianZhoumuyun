package com.zaijian.zhoumuyun.data.agent

import com.zaijian.zhoumuyun.data.provider.LLMConfig
import com.zaijian.zhoumuyun.data.provider.LLMMessage
import com.zaijian.zhoumuyun.data.provider.LLMProvider
import com.zaijian.zhoumuyun.data.provider.ProviderManager
import com.zaijian.zhoumuyun.data.provider.chatSyncWithRetry

/**
 * Phase 13 · Tool Call Engine（Prompt-based Dispatch）
 *
 * ═══════════════════════════════════════════════════════════════
 * 文件 1/4 — AgentTool.kt
 * 工具接口 + 注册表
 * ═══════════════════════════════════════════════════════════════
 *
 * 设计原则：
 *   ① Provider 无关 —— 标签解析在客户端完成，LLM 只输出纯文本
 *   ② 流式兼容 —— 工具标签可在流式输出中被检测到，不需要等待完整回复
 *   ③ 对用户透明 —— 角色以第一人称汇报工具结果，用户不感知 Agent 存在
 *   ④ 本地优先 —— calculator / datetime 纯本地执行，无网络依赖
 *
 * 工具标签格式（LLM 在回复中输出）：
 *   <tool:tool_name key1="val1" key2="val2"/>
 *
 * 示例：
 *   <tool:web_search query="今天上海天气"/>
 *   <tool:calculator expr="(100 + 200) * 1.13"/>
 *   <tool:datetime format="full"/>
 *   <tool:translate text="Hello World" target="zh"/>
 *   <tool:file_read path="notes.txt"/>
 */

// ─────────────────────────────────────────────────────────────
//  工具执行结果
// ─────────────────────────────────────────────────────────────

/**
 * 工具执行结果。
 *
 * @param toolName  执行的工具名称，如 "web_search"
 * @param success   是否执行成功
 * @param content   成功时的结果文本（注入 context 给 LLM）
 * @param error     失败时的简短错误描述（不暴露给用户）
 * @param userHint  可选：展示给用户的简短提示（如"正在搜索…"），null = 不展示
 */
data class ToolResult(
    val toolName: String,
    val success: Boolean,
    val content: String,
    val error: String? = null,
    val userHint: String? = null,
)

// ─────────────────────────────────────────────────────────────
//  工具接口
// ─────────────────────────────────────────────────────────────

/**
 * 所有 Agent 工具的统一接口。
 *
 * 每个工具：
 *   - 声明自己的 [name]（必须与标签中的工具名一致）
 *   - 声明支持的 [paramKeys]（供 ToolParser 验证）
 *   - 实现 [execute]（挂起函数，可安全调用网络/IO）
 *
 * 工具实现禁止：
 *   - 抛出未处理异常（必须 try-catch 后返回 ToolResult(success=false, ...)）
 *   - 直接修改 UI 状态（通过返回值通知调用方）
 *   - 阻塞主线程（必须在 suspend 函数内使用 withContext(IO)）
 */
interface AgentTool {
    /** 工具唯一名称，与标签中的名称对应，如 "web_search" */
    val name: String

    /** 工具支持的参数 key 列表，用于校验解析结果 */
    val paramKeys: List<String>

    /**
     * 执行工具。
     *
     * @param params  从标签解析出的参数 Map，key 均为小写
     * @return        工具执行结果，永不抛出
     */
    suspend fun execute(params: Map<String, String>): ToolResult

    companion object {
        /**
         * 内部共享辅助：LLM 调用。
         *
         * 原本在 DataVisTools.kt / AgentMetaTools.kt（`p3CallLlm`）/ CreativeDocTools.kt
         * 中各自重复定义，逻辑完全一致，现提取为唯一实现（2.17）。
         */
        suspend fun callLlm(
            providerFn:   () -> LLMProvider?,
            systemPrompt: String,
            userPrompt:   String,
            maxTokens:    Int   = 800,
            temperature:  Float = 0.5f,
        ): String {
            val provider = providerFn()
                ?: throw IllegalStateException("当前未配置 API，请在设置中填写 API Key。")
            return provider.chatSyncWithRetry(
                messages     = listOf(LLMMessage("user", userPrompt)),
                systemPrompt = systemPrompt,
                config       = LLMConfig(
                    model       = "",
                    maxTokens   = maxTokens,
                    temperature = temperature,
                    stream      = false,
                ),
            )
        }

        /**
         * 内部共享辅助：构造指向当前激活 Provider 的 providerFn。
         *
         * 原本在 DataVisTools.kt / AgentMetaTools.kt / CreativeDocTools.kt 的
         * register*Tools() 中各自重复出现，CreativeTools.kt 的两个工具内部也各自
         * 写了一份等价的内联调用，现提取为唯一实现（2.18）。
         */
        fun defaultProviderFn(): () -> LLMProvider? =
            { ProviderManager.instance.activeProvider }
    }
}

// ─────────────────────────────────────────────────────────────
//  工具注册表
// ─────────────────────────────────────────────────────────────

/**
 * Agent 工具注册表（单例）。
 *
 * 所有内置工具在 [ZaijianApp.onCreate] 中通过 [register] 注册。
 * ToolCallInterceptor 通过 [get] 按名称查找工具实例。
 *
 * 线程安全：内部使用 [java.util.concurrent.ConcurrentHashMap]，支持多线程并发注册与查找。
 */
object AgentToolRegistry {

    private val tools = java.util.concurrent.ConcurrentHashMap<String, AgentTool>()

    /**
     * 注册工具。重复注册同名工具会覆盖旧实现（热更新友好）。
     */
    fun register(tool: AgentTool) {
        tools[tool.name] = tool
    }

    /**
     * 批量注册。
     */
    fun registerAll(vararg toolList: AgentTool) {
        toolList.forEach { register(it) }
    }

    /**
     * 按名称查找工具。未注册返回 null。
     */
    fun get(name: String): AgentTool? = tools[name]

    /**
     * 返回所有已注册工具的名称列表（用于 Prompt 注入）。
     */
    fun allNames(): List<String> = tools.keys.toList()

    /**
     * 生成工具能力描述块，注入到 System Prompt 的 Task Layer。
     *
     * 格式：
     *   [可用工具]
     *   - web_search: query="搜索关键词"
     *   - calculator: expr="数学表达式"
     *   ...
     *
     * 当注册表为空时返回空字符串（不注入）。
     */
    fun buildToolDescriptionBlock(): String {
        if (tools.isEmpty()) return ""
        return buildString {
            appendLine("[可用工具]")
            appendLine("当需要获取外部信息或执行计算时，在回复中嵌入以下格式的工具标签：")
            appendLine("<tool:工具名 参数名=\"参数值\"/>")
            appendLine()
            appendLine("可用工具：")
            tools.values.forEach { tool ->
                val paramDesc = tool.paramKeys.joinToString(" ") { key -> "$key=\"...\"" }
                appendLine("- ${tool.name}: $paramDesc")
            }
            appendLine()
            append("工具执行后，结果会自动回注到对话中，你无需解释工具的存在。")
        }.trimEnd()
    }
}
