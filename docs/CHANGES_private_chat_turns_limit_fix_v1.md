# 角色间私聊·轮数上限与历史截断线对齐修复 v1

## 背景

审查角色间私聊体系（`PrivateChatEngine` 及其上下游）时发现：
`generateReply()` 喂给 LLM 的历史消息固定 `limit = 10` 条，但
`private_chat_pairs.maxTurnsPerSession`（每轮最大对话数）在 UI 的
"参数设置"里是自由数字输入框，没有任何上限校验，用户可以填任意大的值。

一旦实际轮数超过 10，角色会看不到 10 轮以前的对话内容，聊着聊着
像"失忆"；同时该输入框也没有下限校验，填 `0` 会导致"只有开场白、
对方从未回应"就被记成正常完成的空会话。

## 改动内容

### 1. `data/privatechat/PrivateChatEngine.kt`
`generateReply()` 里历史消息截断数：`limit = 10` → `limit = 20`。

### 2. `data/db/dao/PrivateChatMessageDao.kt`
更新 `getRecentBySessionDesc()` 上方的说明注释，去掉过时的
"建议硬上限 12"表述，改为准确描述当前 `limit = 20` 的对齐关系，
并注明 UI 侧上限校验的落地位置（见改动 3）。

### 3. `ui/viewmodel/PrivateChatViewModel.kt`
新增边界常量，并在 `updateParams()` 里做钳制，作为最终防线
（无论调用来自 UI 还是未来任何新入口，落库前都会被夹到合法区间）：

```kotlin
const val MAX_TURNS_UPPER_BOUND = 20  // 对齐 PrivateChatEngine 的历史截断线
const val MIN_TURNS_LOWER_BOUND = 2   // 至少"开场 + 一次回应"，堵住空会话

fun updateParams(pairId: String, maxTurns: Int, maxSessions: Int, cooldown: Int) {
    val clampedTurns = maxTurns.coerceIn(MIN_TURNS_LOWER_BOUND, MAX_TURNS_UPPER_BOUND)
    val clampedSessions = maxSessions.coerceAtLeast(1)
    val clampedCooldown = cooldown.coerceAtLeast(0)
    ...
}
```

`maxSessions`（每日最大会话数）、`cooldown`（冷却分钟数）此前也没有
下限校验，顺带补上 `coerceAtLeast` 防御，避免出现 0 或负数导致的
异常行为（如 `maxSessions=0` 会让该配对当天永远无法发起私聊）。

### 4. `ui/screen/PrivateChatScreen.kt`
- "每轮最大对话数"输入框标签改为"每轮最大对话数（2-20）"，明确告知范围。
- 保存参数按钮点击时，对 `maxTurns` 先按
  `PrivateChatViewModel.MIN_TURNS_LOWER_BOUND` /
  `MAX_TURNS_UPPER_BOUND` 预钳制，并把钳制后的值同步回输入框
  （`maxTurns = mt.toString()`），避免用户填了超范围的数字、UI 却
  没有任何反馈，全靠事后 toast 才知道被静默改写。

两层校验共用同一组常量（定义在 `PrivateChatViewModel`），以后调整
这个上限只需要改一处，不会再出现"引擎里是 10、注释说 12、UI 无限制"
三处数字互相对不上的情况。

## 未在本次改动范围内的问题

审查中还发现的以下问题**未包含在本次改动**，如需处理请另行确认：

- 角色"自主下线"（`[[DECISION:DISCONNECT]]`）中断的会话被记为
  `status = "completed"`，与"正常收尾"混在一起，无法在会话列表/
  导出记录里区分出"被拒绝中断"的会话。
- `PrivateChatExporter.appendRelationshipSection` 在导出文件里展示
  关系值快照，与 `PrivateChatEngine` 类头注释反复强调的"私聊与
  关系值体系双向隔离"存在措辞层面的张力（技术上不冲突，因为是只读
  展示，不影响生成逻辑，但容易引起误解）。
- `PrivateChatSendTool`（主聊天工具触发）与
  `PrivateChatViewModel.triggerSession`（UI 手动触发）对"配对未开启"
  的处理不对称：前者自动开启，后者报错要求用户先手动开启。
- `PrivateChatEngine.runSession()` 里 `otherOf()` 函数对
  `initiatorCharacterId` 不属于该 pair 的情况没有防御性校验（当前
  两个入口都天然满足约束，未实际触发，但没有兜底）。
- `resolveCharacterIdByName()` 在女儿角色重名时会静默匹配到遍历顺序
  中第一个同名角色，无歧义提示。
- `PrivateChatSessionRepository.markCompleted`（非原子版本）在代码库
  中已无调用点，是死代码，建议清理或加注释防止被误用绕开事务保护。
