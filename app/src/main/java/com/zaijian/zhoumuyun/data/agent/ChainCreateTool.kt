package com.zaijian.zhoumuyun.data.agent

import com.zaijian.zhoumuyun.data.db.entity.ChainDefinitionEntity
import com.zaijian.zhoumuyun.data.db.entity.ChainTriggerType
import com.zaijian.zhoumuyun.data.repository.ChainRunRepository
import java.util.UUID

/**
 * 灵活自动化编排 · 链条创建工具（§7/§8）
 *
 * ═══════════════════════════════════════════════════════════════
 * 注册为普通 AgentTool，LLM 在回复里输出
 *   <tool:chain_create name="情绪波动后跟进" trigger_event="state_updated"
 *    nodes="[{...}]"/>
 * （trigger_event 对应 CharacterStateRepository.updateState() 实际发布的事件名，
 * payload 含 primaryEmotion/intensity 两个字段，可在 Check 节点条件表达式中引用）
 * 走 ToolParser → ToolCallInterceptor 流程（与 workflow_start 同款）。
 *
 * execute() 只做"创建阶段一次性挡住"的五条校验（§7），全部通过后写一条
 * [ChainDefinitionEntity] 落库即返回——不在此处启动运行，运行由 ChainTriggerMatcher
 * 在事件匹配时创建 ChainRunEntity 触发（§6）。
 *
 * 对照 [WorkflowStartTool]：characterId 用 lambda 取值（切角色后始终是当前角色），
 * 静态占位注册时传 { -1 }，由 ChatViewModel 动态覆盖。charId < 0 拒绝（①）的语义
 * 与 WorkflowStartTool 一致——防的是"App 启动阶段静态占位、ChatViewModel 尚未
 * 动态覆盖"时被误调用。
 *
 * 现状说明：§11.12 数据模型（ChainDefinitionEntity/ChainRunEntity）与匹配逻辑
 * （ChainTriggerMatcher）均已支持 characterId=-1（项目级链条，不挂靠单一角色，
 * 全局事件命中），但目前【没有任何创建入口】能产出这样一条记录——本工具是当前
 * 唯一的链条创建入口，且校验①直接拒绝 charId<0。如需支持项目级链条创建，需要
 * 另外设计并新增创建入口，并需先确认权限边界（例如是否只允许系统内置调用传
 * -1，避免普通对话中 LLM 误建出影响所有角色的全局规则），而非简单放行本工具
 * 的 charId<0 分支。
 *
 * 五条校验（§7，创建阶段一次性挡住，非运行时才发现）：
 *   ① charId < 0 拒绝
 *   ② nodesJson 反序列化失败拒绝（ChainNodeCodec.parseAndValidate 内含）
 *   ③ 节点结构校验失败拒绝（可达性/引用悬空/&&与||混用，validate() 内含）
 *   ④ checkToolName 白名单校验（运行时校验，validate() 不做这条）
 *   ⑤ &&/|| 混用已在 validate() 内做（规则#5），此处不重复
 * ═══════════════════════════════════════════════════════════════
 */
class ChainCreateTool(
    private val chainRunRepository: ChainRunRepository,
    private val characterId: () -> Int,
) : AgentTool {

    override val name = "chain_create"
    override val description = "创建灵活自动化规则（事件触发+Wait/Check/Action/End节点链），用于定时轮询或条件驱动的后台自动化"
    override val paramKeys = listOf("name", "trigger_event", "nodes")

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val charId = characterId()
        if (charId < 0) return fail("角色未初始化")  // ①

        val name = params["name"]?.trim().orEmpty()
        val triggerEvent = params["trigger_event"]?.trim()
        val nodesJson = params["nodes"]?.trim()
        if (nodesJson.isNullOrBlank()) return fail("缺少 nodes 参数")

        // ②③ 一并做：deserialize 失败 + validate() 内部含可达性/引用悬空校验
        val parseResult = ChainNodeCodec.parseAndValidate(nodesJson)
        if (parseResult is ChainParseResult.Failure) return fail(parseResult.error)

        val nodes = (parseResult as ChainParseResult.Success).nodes

        // ④ checkToolName 白名单校验。ChainNodeCodec.validate() 不做这条——它依赖
        // 调用上下文（SAFE_TOOL_NAMES 白名单），属于 ChainCreateTool 运行时校验范畴。
        // 防御纵深：不信任"创建时校验过就永远安全"（白名单后续可能收窄）。
        for (node in nodes) {
            if (node is ChainNode.Check && node.checkToolName != null) {
                if (node.checkToolName !in WorkflowEngine.SAFE_TOOL_NAMES) {
                    return fail("checkToolName '${node.checkToolName}' 不在白名单内")
                }
            }
        }
        // ⑤ &&/|| 混用已在 ChainNodeCodec.validate() 内部做了（规则#5），不重复

        // 全部通过，写库
        val defId = UUID.randomUUID().toString()
        chainRunRepository.insertDefinition(
            ChainDefinitionEntity(
                id = defId,
                characterId = charId,
                name = name,
                triggerType = ChainTriggerType.EVENT,
                triggerEventName = triggerEvent,
                // ChainCreateTool 本次只支持 EVENT 类型触发（校验①-④全围绕 trigger_event
                // 展开），SCHEDULE 类型（复用 cron）不在本次范围内，固定传 null。
                // triggerCron: String? 无默认值，不显式传会编译不过。
                triggerCron = null,
                nodesJson = nodesJson,
                enabled = true,
                createdAt = System.currentTimeMillis(),
            ),
        )
        return ToolResult(toolName = "chain_create", success = true, content = "已创建自动化规则：$name")
    }

    private fun fail(msg: String): ToolResult =
        ToolResult(toolName = name, success = false, content = "", error = msg)
}
