# S8 窗口11 修复变更记录 — RuleDistillTool Provider 捕获方式统一

**修复日期：** 2026-07-17
**依据：** `window11-tool-audit.md`（81条结论，79条通过，2条复核后修正，2条新发现）
**基准代码：** `zaijian_s8_window10_full.zip`

---

## 本窗口审计概览

窗口11审计 ZaijianApp.kt + ChatToolRegistrar.kt 注册的全部81个Agent工具。
初版报告存在大量幻觉（工具名/数量与源码不符），复核版逐文件重新核对，
修正为源码实际数据。复核后仅剩一条实质性代码问题：**P1-8-7**。

其余结论（正常工具79个、原"双重注册bug"P1-8-9确认为两阶段注册设计意图、
原"characterId注入"疑问P1-8-8确认已通过`__character_id`机制妥善解决、
新发现P1-8-10 file_read命名分散仅为潜在混淆点无实际冲突）均为已确认解决
或正向确认，代码原样保留，未做修改。

---

## 修复（P1-8-7）：RuleDistillTool 的 LLMProvider 捕获方式与其他工具不一致

**问题：** `RuleDistillTool` 构造时直接捕获 `provider: LLMProvider` 实例，
而 `CodeGenTool`、`TableGenTool`、`SelfReflectTool`、`RuleReviewTool`、
`RuleConflictCheckTool` 等其余 LLM 工具均使用
`providerFn: () -> LLMProvider?` 闭包延迟获取。

**核实后发现的实际影响比报告描述更严重：**
两处注册点（`ZaijianApp.onCreate()` Phase1 占位、`ChatToolRegistrar.
registerCharacterTools()` Phase2 覆盖）原实现都写成
`xxxProvider?.let { p -> AgentToolRegistry.register(RuleDistillTool(provider = p, ...)) }`——
如果注册那一刻 `activeProvider` 恰好为 `null`（例如首次启动未配置
API Key），`rule_distill` 会直接跳过注册、完全不可用，直到下次角色
切换触发重新注册才可能补上。即使注册成功，用户此后仅切换 Provider/Key
不切换角色时，该工具仍会一直使用注册时刻捕获的旧 Provider 实例。

**文件与修改：**

1. `app/src/main/java/com/zaijian/zhoumuyun/data/agent/AgentCoreTools.kt`
   - `RuleDistillTool` 构造参数 `provider: LLMProvider` 改为
     `providerFn: () -> LLMProvider?`（不带默认值，与
     `SelfReflectTool`/`RuleConflictCheckTool` 等同样"providerFn + 多个
     必填参数"的构造惯例保持一致；唯一带默认值的 `CodeGenTool`/
     `CodeReviewTool` 是单参数构造，不适用于此处）。
   - `execute()` 内 LLM 调用前改为 `val provider = providerFn() ?:
     throw IllegalStateException("当前未配置 API，请在设置中填写 API Key。")`，
     与 `AgentTool.callLlm()` 的空值处理方式一致。该异常会被本方法既有的
     `try/catch` 捕获，走"LLM 精简失败时降级使用原始输入"的既有分支，
     行为对用户透明。

2. `app/src/main/java/com/zaijian/zhoumuyun/ZaijianApp.kt`
   - Phase1 注册改为无条件 `AgentToolRegistry.register(RuleDistillTool(
     providerFn = AgentTool.defaultProviderFn(), ...))`，不再依赖
     `ProviderManager.instance.activeProvider?.let { ... }` 判空后才注册。
   - 新增 import：`com.zaijian.zhoumuyun.data.agent.AgentTool`。

3. `app/src/main/java/com/zaijian/zhoumuyun/ui/viewmodel/ChatToolRegistrar.kt`
   - Phase2 覆盖注册改为无条件 `AgentToolRegistry.register(RuleDistillTool(
     providerFn = providerFn, ...))`，复用本方法已有的局部 `providerFn`
     闭包变量，不再依赖 `providerFn()?.let { p -> ... }` 判空。

**效果：**
- `rule_distill` 现在与其余 LLM 工具一样，无论注册时刻是否已配置 API Key
  都会被注册，不再出现"首次启动未配置 Key 导致工具整体缺席"的空窗期。
- 用户切换 Provider/Key 后，下次调用 `rule_distill` 会立即拿到最新
  Provider，不再需要等待角色切换触发的重新注册。
- 未配置 Key 时的失败路径从"工具不存在"变为"工具存在但给出明确提示"，
  对 LLM 侧和用户侧都更可诊断。

**验证：** 括号平衡校验通过（Gradle 不可用于此环境）；三处调用点逐一
核对，确认无遗留的 `provider:` 直接持有写法。

---

## 未处理项

无。本窗口除 P1-8-7 外无其他需要修改代码的结论。
