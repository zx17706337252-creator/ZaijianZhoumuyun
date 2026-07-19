# S8 窗口09 修复变更记录 — 受孕弹窗链路断裂 + DI收敛 + 注释更正

**修复日期：** 2026-07-17
**依据：** `zaijian_s8_window09_audit_report.md`（15条结论，15条通过）
**修复项：** 结论1、结论6（P0，同一问题两处描述）、新发现1（P1）、新发现3（P2）

---

## 修复1（P0）：受孕弹窗"同意/拒绝"→受孕流程链路断裂

**问题：** `ChatViewModel.onFertileWindowDialogResult()` 此前只清空弹窗文案，
从未调用 `PregnancyTriggerManager.proceedAfterDialogConsent()`。受孕流程
判定（`evaluateCycleAndProceed`/`applyRejectedEffect`）从未执行，
`fertileWindowConsentAsked` 标记从未被置为 true，弹窗保护机制完全失效。

**文件：** `app/src/main/java/com/zaijian/zhoumuyun/ui/viewmodel/ChatViewModel.kt`

**修复内容：**
- `onFertileWindowDialogResult(accepted: Boolean)` 改为：
  1. 先读取 `fertileWindowCharacterId`（弹窗展示时捕获的角色ID快照，
     而非实时的 `currentCharacterId`，与既有"问题14"设计保持一致）
  2. 关闭弹窗（保持原有顺序，避免二次弹窗）
  3. `viewModelScope.launch(Dispatchers.IO)` 中读取动态 `pressureScale`
     （复用 `PregnancyPromptDelegate.buildPregnancyPrompts()` 的既有取值方式，
     不再硬编码 1.0f）
  4. 调用 `pregnancyTriggerManager.proceedAfterDialogConsent(characterId, accepted, pressureScale)`
  5. `when` 穷尽处理 `PregnancyTriggerResult` 各分支，日志记录关键分支
     （`Triggered`/`FertileButFailed`/`WrongPhase`/`Rejected`），其余分支
     （该入口理论上不会产出）安全忽略并注明原因
  6. 异常捕获后仅记录日志，不影响 UI（`proceedAfterDialogConsent` 内部
     `finally` 块已保证 `markFertileWindowConsentAsked` 落库，本处无需
     重复兜底）
- 新增 import：`com.zaijian.zhoumuyun.data.model.PregnancyTriggerResult`

**验证：** 括号/字符串平衡校验通过（Gradle 不可用于此环境）。

---

## 修复2（P1，新发现1）：ChatViewModel 未使用 AppContainer 共享 Judge 实例（DI 不一致）

**问题：** `ChatViewModel` 构造 `pregnancyTriggerManager` 时各自 `new` 了一份
`FertileWindowConsentJudge`/`UserConsentIntentJudge`，与 `AppContainer` 已有的
共享单例（`fertileWindowConsentJudge`/`userConsentIntentJudge`）是两个独立
对象，违背 DI 收敛原则。`AppContainer` 已提供 `createPregnancyTriggerManagerFull()`
工厂方法，构造参数与 ChatViewModel 原写法逐字段一致，此前"仅新增此方法供
未来单独批次使用"（`CHANGES_S8_window01_pregnancy_di.md`），未接入 ChatViewModel。

**文件：** `app/src/main/java/com/zaijian/zhoumuyun/ui/viewmodel/ChatViewModel.kt`

**修复内容：**
- `pregnancyTriggerManager` 字段改为调用
  `container.createPregnancyTriggerManagerFull(cycleRepository, stateRepository)`，
  不再手动 `new PregnancyTriggerManager(...)` 并各自构造 Judge 实例。
- 移除不再使用的 import：`FertileWindowConsentJudge`、`UserConsentIntentJudge`。

---

## 修复3（P2，新发现3）：`proceedAfterDialogConsent` 注释与实际代码不一致

**问题：** `PregnancyTriggerManager.proceedAfterDialogConsent()` 的文档注释声称
"ChatViewModel 里原有的 markFertileWindowConsentAsked 调用予以保留"，但全项目
grep 确认该调用在 ChatViewModel 中从未存在，注释描述了一个不存在的"原有调用"。

**文件：** `app/src/main/java/com/zaijian/zhoumuyun/data/manager/PregnancyTriggerManager.kt`

**修复内容：** 更正该段注释，明确说明落库保证完全由本方法 `finally` 块提供，
调用方无需也不应重复调用 `markFertileWindowConsentAsked`，并注明该失实描述
已随本次修复移除。

---

## 未处理项（按报告结论7/N2，均为已确认的既有设计/合理重叠，无需修复）

- 结论7：圆桌场景不开启 aiJudge 是明确设计决策，不是缺陷。
- 新发现2：在线清除 vs 后台清除功能重叠但合理，当前设计无缺陷。

## 遗留待办（未在本窗口范围内，供后续窗口跟进）

- `MIGRATION_40_41` ftsRowId 验证：仍需在最终整合手册中作为独立条目处理，
  不应再被顺延。
