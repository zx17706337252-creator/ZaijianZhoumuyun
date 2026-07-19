# S8 窗口05 修复：declineCorrection 静默失败 + finalizeRound 评分兜底不一致

> 对应 `zaijian_s8_window05_audit_report.md` 新发现1、新发现2（均为
> 逻辑bug(P3)）。窗口05报告主体15条结论全部"复核通过"，无需处理；
> 仅这两条"复核过程中顺带发现的问题"需要动代码。

## 新发现1：`declineCorrection` 失败时静默吞异常

### 问题

```kotlin
fun declineCorrection(profileId: String, entryText: String) {
    viewModelScope.launch {
        try {
            judgeProfileRepo.declineCorrection(profileId, entryText)
            _snackbarMessage.value = "已忽略这条观察"
        } catch (_: Exception) { /* 静默失败，候选池会在下次评审时再次写入 */ }
    }
}
```

注释里说的"候选池会在下次评审时再次写入"并不成立：`feedJudgeCorrectionCandidates`
的语义匹配只在候选条目已被移除时才会重新写入，写入失败意味着该条目根本
没被移除，下次评审也不会"再次写入"——它会一直卡在候选池里。用户点"先不用"
每次都静默失败，UI 上毫无提示，形成一个用户自己发现不了的死循环。

同一个 ViewModel 里 `confirmCorrection`/`updateStandardNotes` 失败时都会走
`_snackbarMessage.value = "...失败：${e.message}"`，只有 `declineCorrection`
是例外。

### 修复

```kotlin
fun declineCorrection(profileId: String, entryText: String) {
    viewModelScope.launch {
        try {
            judgeProfileRepo.declineCorrection(profileId, entryText)
            _snackbarMessage.value = "已忽略这条观察"
        } catch (e: Exception) {
            _snackbarMessage.value = "操作失败：${e.message}"
        }
    }
}
```

与文件内既有的 snackbar 失败反馈模式对齐，不引入新的日志框架依赖
（该文件本身未使用 ZLog）。

## 新发现2：`finalizeRound` 中 judgeScore/selfScore 为 null 时兜底50分，与 userScore==null 处理不一致

### 问题

```kotlin
val jScore = (entry.judgeScore ?: 50).toFloat()
val sScore = (entry.selfScore ?: 50).toFloat()
```

`CompetitionEntryEntity.judgeScore` 字段注释明确写"null 表示评审尚未完成"，
和 `userScore == null`（用户主动跳过打分，代码里已 `continue` 跳过综合分
计算）本质是同一类"评审未真正完成"的语义。但原代码对 `judgeScore`/
`selfScore` 却用 `?: 50` 兜底参与加权计算——自相矛盾。

`judgeScore` 为 null 的真实触发路径（非理论风险）：`runJudging()` 中
`judgeResult.success` 只要求 `validVerdictCount > 0`（即至少一条命中），
不要求"每条参赛作品都命中裁判 verdict"；此外每条 entry 的写回还有独立
try-catch（`updateJudgeResult`/`updateSelfResult` 任一失败都只记日志、
不阻断循环）。这意味着完全可能出现"裁判评审整体成功、但个别条目
judgeScore/selfScore 仍是 null"的情况，兜底 50 分会让这些实际未被评审的
条目获得不应有的中等权重，且由于 `applyCompetitionRewards` 用
`sortedByComposite.first()` 取"赢家"，还可能造成误判。

### 修复

在综合分计算处，`judgeScore`/`selfScore` 任一为 null 时也 `continue`
跳过（不再用 `?: 50` 兜底），与 `userScore == null` 采用完全一致的处理：

```kotlin
if (entry.userScore == null) { ...; continue }

if (entry.judgeScore == null || entry.selfScore == null) {
    ZLog.w(TAG, "...评审未完整完成，跳过综合分计算（compositeScore 保持默认值）")
    continue
}

val uScore = entry.userScore.toFloat()
val jScore = entry.judgeScore.toFloat()
val sScore = entry.selfScore.toFloat()
```

同步修正 `scoredFinal` 的筛选条件（原先只过滤 `userScore != null`）：

```kotlin
val scoredFinal = freshEntries4
    .filter { it.userScore != null && it.judgeScore != null && it.selfScore != null }
    .sortedByDescending { it.compositeScore }
```

**为什么必须同步改 `scoredFinal`**：仅在综合分计算处 `continue` 还不够——
若某条目 `userScore` 有值但 `judgeScore`/`selfScore` 为 null，
`compositeScore` 会保持默认值 `0f`，但原 `scoredFinal` 筛选条件只看
`userScore != null`，这类条目仍会混进 `scoredFinal`：
- 参与最终排名记忆编号，把"评审未完成"的条目当成"综合得分0.0"的真实
  垫底表现写进角色永久记忆（与方案4-1补丁想要避免的问题同源）
- 传给 `applyCompetitionRewards(round, scoredFinal)`，`sortedByComposite.first()`
  取赢家时，若该条目恰好是 `scoredFinal` 中唯一一条（或综合分意外偏高的
  一条），可能被误判为赢家触发不该有的晋升反哺

补上 `judgeScore != null && selfScore != null` 后，`scoredFinal`/
`sortedFinal`/`applyCompetitionRewards` 全链路口径一致：只有真正完整走完
用户打分 + 裁判评审 + 自评三个环节的条目才参与最终排名与奖惩反哺。

### 未受影响的部分

- `computeAndSaveJudgeAccuracy()`（Spearman 裁判准确度）已经是独立的
  `it.judgeScore != null` 过滤，不依赖 `compositeScore`，不受本次改动影响。
- 未发现相关单元测试覆盖 `finalizeRound`/`judgeScore`/`selfScore` 语义，
  无需同步调整测试断言。

## 未处理项（按报告结论，无需修复）

窗口05报告主体15条结论（操作1-8 + 复核9-15）全部"复核通过"，均为
"已确认解决"或既有设计，未涉及代码变更。

## 改动文件

- `app/src/main/java/com/zaijian/zhoumuyun/ui/viewmodel/JudgeProfileViewModel.kt`
  - `declineCorrection()`：catch 块改为 snackbar 失败提示，不再静默吞异常
- `app/src/main/java/com/zaijian/zhoumuyun/data/agent/CompetitionRoundManager.kt`
  - `finalizeRound()`：judgeScore/selfScore 为 null 时改为 continue 跳过，
    不再 `?: 50` 兜底
  - `scoredFinal` 筛选条件同步补上 `judgeScore != null && selfScore != null`
