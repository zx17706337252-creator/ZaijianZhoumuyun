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
 * @param tablePayloadJson  可选：表格直传方案（W2）产出的 [TablePayload] 序列化 JSON。
 *   仅 `table_export` 工具在 50~500 行 / >500 行场景下填此字段；≤50 行 Markdown 路径
 *   及其他所有工具此字段为 null。W4 管线在三个调用点（ChatMessageOrchestrator /
 *   RoundtableBotReplyGenerator / RoundtableIdleManager）的 `is StreamEvent.ToolDone -> { }`
 *   里读 `event.result.tablePayloadJson`，与 `extractExportedFileJson(event.result)` 同一
 *   位置、同一局部变量收集方式，写入 `MessageEntity.tableDataJson`。
 *
 *   ⚠️ 设计原则（W2 验收修复）：payload 必须走 [ToolResult] 返回值随 [StreamEvent.ToolDone]
 *   事件传给调用点的**局部**变量，**不得**存在工具实例字段上等调用方来读。原因：
 *   工具实例在 AgentToolRegistry 是全局单例，而 `RoundtableIdleManager` 独立持有
 *   CoroutineScope 能在私聊进行中后台并发触发（见 `VaultIo.kt:32-37`），两条
 *   `streamWithTools` 同时跑时，共享字段会被后完成者覆盖/提前清空，一旦 W4 接上
 *   "执行完立刻读字段"的动作，被覆盖的那条消息最终写进 `tableDataJson` 的可能是
 *   **别的角色**的数据（若来源是 CSV 私库文件或角色日程，构成隐私越权）。走返回值
 *   则每次 execute 的产物是函数局部对象，天然不会被别的协程摸到——这与 v147 对
 *   身份问题（`VaultCallContextElement`）给出的解法同构：别用共享可变状态传数据。
 */
data class ToolResult(
    val toolName: String,
    val success: Boolean,
    val content: String,
    val error: String? = null,
    val userHint: String? = null,
    val tablePayloadJson: String? = null,
)

// ─────────────────────────────────────────────────────────────
//  P2 安全错误处理辅助
// ─────────────────────────────────────────────────────────────

/**
 * P2 修复：工具异常时返回稳定错误码，详细异常写 AgentLog，
 * content 用固定友好文案（不拼接 e.message，避免把绝对路径 / SQL / 堆栈泄露给 LLM 或用户）。
 *
 * 原先各工具的 catch 块普遍写成
 * `ToolResult(name, false, "X失败：${e.message?.take(80)}", e.message)`，
 * `error` 字段裸传 `e.message`——文件 I/O 异常会泄露 `/data/user/0/.../vault/...` 绝对路径，
 * DB 异常会泄露 `SQLITE_CONSTRAINT: UNIQUE constraint failed: ...` SQL 片段。
 * 这些内容会被 ToolCallInterceptor 回注 LLM，并可能透传到用户气泡。
 *
 * 用本函数替换后：
 * - `content`：调用方提供的固定友好文案（不含 e.message）
 * - `error`：稳定错误码（如 "file_read_failed"），不含路径/SQL
 * - 完整异常堆栈只写进 AgentLog（filesDir/logs/agent_log.txt），供排查用
 *
 * 用法（金标准异常处理模式）：
 * ```
 * } catch (e: kotlinx.coroutines.CancellationException) {
 *     throw e
 * } catch (e: Throwable) {
 *     toolFailure(name, "读取文件时遇到问题。", "file_read_failed", e)
 * }
 * ```
 *
 * ⚠️ 所有 AgentTool 的 execute() 实现必须遵循此模式：
 * - 先 catch CancellationException 并 rethrow，保证协程取消信号不被吞掉
 * - 再 catch Throwable（而非 Exception），防止 Error 子类（如 OutOfMemoryError）击穿
 */
suspend fun toolFailure(
    toolName: String,
    userMsg: String,
    errorCode: String,
    e: Throwable,
    tag: String = toolName,
): ToolResult {
    // 排查性修复：日志消息里带上 e::class 全限定名（如
    // "java.lang.NoClassDefFoundError"），不再只有 userMsg + errorCode。
    // 此前只有堆栈本身能看出异常类型，需要展开完整 stackTraceString 才能
    // 判断"这次到底是 Exception 还是 Error"；现在一行摘要就能看出来，
    // 尤其是排查"某几层 catch 只认 Exception、Error 被击穿"这类问题时，
    // 不用每次都翻到堆栈第一行。errorCode 仍是给外部/LLM 看的稳定错误码，
    // 这里追加的类名只写本地 AgentLog 文件，不影响对外文案。
    com.zaijian.zhoumuyun.util.AgentLog.error(
        tag,
        "$userMsg (code=$errorCode, exceptionType=${e::class.qualifiedName ?: e.javaClass.name})",
        e,
    )
    return ToolResult(toolName, false, userMsg, errorCode)
}

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

    /**
     * 一句话中文描述：这个工具是干什么的、典型什么时候该用。
     * 用于注入 System Prompt 让 LLM 判断该不该调用这个工具，
     * 不是给开发者看的注释。要求：
     *   - 一句话，不换行，控制在 40 字以内
     *   - 说清楚"做什么"+"什么场景用"，不要只重复工具名
     *   - 与语义相近的工具（如 reminder vs schedule_create）要写出区分点
     * 默认空字符串（向后兼容，避免遗漏 description 的工具导致编译失败）；
     * 每个工具都必须显式 override，不允许依赖默认值。
     */
    val description: String get() = ""

    /**
     * 可选的参数使用说明（长度不限）。
     *
     * 见《Window B 执行方案 v1.1》2.3.2。不注入 [buildToolDescriptionBlock]，不参与
     * LLM"要不要调用这个工具"的判断，只在需要时（例如未来做参数纠错重试的决策
     * Prompt、或 2.1 节降级决策 Prompt 需要给 LLM 看可用工具时）按需读取。
     *
     * 本轮只做接口新增 + 现有超长 description 的内容搬迁，"调用前展示 usageNotes"
     * 这个消费逻辑本身不在本轮实现（YAGNI，留作扩展点）。默认 null，对未覆写的
     * 其余工具零影响，是纯向后兼容的接口新增。
     */
    val usageNotes: String? get() = null

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
     *
     * @param excludeNames 需要从描述块中排除的工具名集合（默认空，即全量返回，
     *   私聊等原有调用点行为不变）。用于场景化过滤——例如圆桌场景需要排除
     *   `agent_message`/`roundtable_trigger`/`task_delegate` 这类跨角色协作工具。
     *   注意：这里的排除只影响"模型看到什么"（prompt 层），不代表执行层一定会
     *   拒绝该工具——执行层的强制拦截见 [ToolCallInterceptor.streamWithTools] 的
     *   `disabledToolNames` 参数，两者需配合使用才构成完整防御。
     */
    fun buildToolDescriptionBlock(excludeNames: Set<String> = emptySet()): String {
        val visibleTools = if (excludeNames.isEmpty()) {
            tools.values
        } else {
            tools.values.filter { it.name !in excludeNames }
        }
        if (visibleTools.isEmpty()) return ""
        return buildString {
            appendLine("[可用工具]")
            appendLine("当需要获取外部信息、执行计算、或执行用户明确要求的具体操作")
            appendLine("（如设置提醒、创建/修改日程、保存笔记、管理任务等）时，")
            appendLine("在回复中嵌入以下格式的工具标签：")
            appendLine("<tool:工具名 参数名=\"参数值\"/>")
            appendLine()
            appendLine("【文件优先规则】判断内容本身是否适合导出为文件，主动选用对应工具生成，")
            appendLine("不需要等用户先明确说「发个文件」「导出」——只要内容形态符合就该主动这样做：")
            appendLine("表格/统计数据 → table_export（真实数据）或 excel_gen（按描述生成）；")
            appendLine("长文档/报告/纪要 → docx_gen；演示文稿 → pptx_gen；PDF → pdf_export；")
            appendLine("网页/排版页面 → html_gen；代码/配置/纯文本内容 → file_export；")
            appendLine("多个文件需要一起发 → zip_export；诊断日志 → diag_export_log。")
            appendLine("禁止用 file_export 生成 docx/xlsx/pptx/pdf/html 格式文件——这些有专用工具，")
            appendLine("file_export 仅支持 md/txt，用它生成 .docx 等扩展名会被静默回退为 txt。")
            appendLine("这是流程规则，不受角色性格、语气、当下情绪影响——角色可以在说话方式上")
            appendLine("保留个性（比如嘴上不情不愿），但该调用的工具必须真实调用并等待结果，")
            appendLine("不能只在对话里说「已经发了」却没有实际执行。")
            appendLine()
            appendLine("可用工具：")
            visibleTools.sortedBy { it.name }.forEach { tool ->
                val paramDesc = tool.paramKeys.joinToString(" ") { key -> "$key=\"...\"" }
                val desc = tool.description.ifBlank { "（无描述）" }
                appendLine("- ${tool.name}（$desc）: $paramDesc")
                // P0-1 修复：注入 usageNotes（参数约束/常见坑），与 buildDegradeDecisionToolBlock
                // （288-290 行）同款逻辑。原先主描述块不拼 usageNotes，LLM 看不到 FileExportTool
                // 的"format 仅支持 md/txt"这类关键约束，也不知道 docx_gen/pdf_export 等工具的
                // 调用方式，导致漏调或误传 format 值（如 format="pdf"）被静默回退成 md——是
                // "除 MD 外其他格式发不出来"的 Prompt 层根因。现成范本就在同一文件里，直接照抄。
                tool.usageNotes?.takeIf { it.isNotBlank() }?.let { notes ->
                    appendLine("  用法备注: ${notes.take(300)}")
                }
            }
            appendLine()
            // P2 修复：关键文档生成工具补标签示例，降低 LLM 猜错参数格式的概率。
            // 仅列出已注册的工具，避免展示不存在的工具示例。
            //
            // 根因修复（html_gen/zip_export 无示例 + pptx_gen theme 值错误）：
            // 原 exampleMap 缺少 html_gen 和 zip_export 示例，LLM 不确定如何调用
            // 就倾向于直接用文字"冒领"结果而不调工具——是"说发送了但看不到"
            // 在 HTML/ZIP 场景反复出现的 prompt 层根因。
            // 同时修正 pptx_gen 示例的 theme="简约"为"blue"——实际只支持
            // blue/dark/minimal 三个英文值，中文值会触发 themeUnsupported 警告。
            appendLine("【常用工具调用示例】")
            val exampleMap = linkedMapOf(
                "docx_gen" to "<tool:docx_gen title=\"项目周报\" description=\"本周完成的三项主要工作及下周计划\"/>",
                "excel_gen" to "<tool:excel_gen title=\"预算表\" description=\"各部门季度预算对比\" sheets_json=\"[{\\\"name\\\":\\\"Q1\\\",\\\"description\\\":\\\"各部门预算\\\"}]\"/>",
                "pptx_gen" to "<tool:pptx_gen title=\"产品介绍\" outline=\"1.背景 2.核心功能 3.路线图\" theme=\"blue\"/>",
                "pdf_export" to "<tool:pdf_export title=\"合同草案\" content=\"# 甲方...\\n## 第一条...\" orientation=\"portrait\"/>",
                "html_gen" to "<tool:html_gen title=\"产品介绍页\" content=\"展示核心功能、技术优势和应用场景\" theme=\"light\"/>",
                "file_export" to "<tool:file_export name=\"config.yml\" content=\"server:\\n  port: 8080\" format=\"txt\"/>",
                "zip_export" to "<tool:zip_export names=\"项目周报.docx,预算表.xlsx\"/>",
            )
            exampleMap.forEach { (toolName, example) ->
                // 显式用 containsKey，避免 Kotlin 在 ConcurrentHashMap 上解析 `in`/`contains`
                // 时可能匹配到遗留的 contains(Object value)（语义等价于 containsValue）
                // 而不是期望的按 key 判断存在。
                if (tools.containsKey(toolName)) {
                    appendLine(example)
                }
            }
            appendLine()
            append("工具执行后，结果会自动回注到对话中，你无需解释工具的存在。")
        }.trimEnd()
    }

    /**
     * 构建「降级决策」专用的工具描述块（Window B-1 fix2）。
     *
     * 与 [buildToolDescriptionBlock] 的差异：
     * - 包含 [AgentTool.usageNotes]（参数使用约束/常见坑），帮助 LLM 在换工具时
     *   做出更合理的决策（原版不包含 usageNotes）。
     * - 去掉教学前缀（降级决策是纯功能判断，不需要"如何调用工具"的教学）。
     * - 额外标注失败工具，让 LLM 知道当前换工具决策的上下文。
     *
     * @param excludeNames    需排除的工具名
     * @param failedToolName  刚刚失败的工具名（标注为"已失败，请换其他工具或换参数重试"）
     */
    fun buildDegradeDecisionToolBlock(
        excludeNames: Set<String> = emptySet(),
        failedToolName: String? = null,
    ): String {
        val visibleTools = tools.values
            .filter { it.name !in excludeNames }
            .sortedBy { it.name }
        if (visibleTools.isEmpty()) return "（无可用工具）"
        return buildString {
            visibleTools.forEach { tool ->
                val paramDesc = tool.paramKeys.joinToString(" ") { key -> "$key=\"...\"" }
                val desc = tool.description.ifBlank { "（无描述）" }
                val failedTag = if (tool.name == failedToolName) " ⚠️已失败" else ""
                appendLine("- ${tool.name}$failedTag（$desc）: $paramDesc")
                // Window B-1 fix2：注入 usageNotes，帮助 LLM 做换工具/换参数决策
                tool.usageNotes?.takeIf { it.isNotBlank() }?.let { notes ->
                    appendLine("  用法备注: ${notes.take(300)}")
                }
            }
        }.trimEnd()
    }
}
