# S8 窗口02 结论6 专项修复：reassembleCompetitionEngine() 支持 Provider 切换后重新装配

> 对应 `window02_di_dependency_audit.md` 结论6（P2，逻辑bug，已知问题）。
> 前一批次（`CHANGES_S8_window02_specialty_repo_and_trycatch.md`）已明确将
> 本条排除在范围外，本次作为独立专项处理，如报告原文建议。

## 问题回顾

`AppContainer.reassembleCompetitionEngine()` 内部有幂等判断：

```kotlin
if (competitionEngine != null) return@withLock
```

装配一次后永久跳过。但 `ZaijianApp.kt` 在 `onCreate()` 里注册了
`ProviderManager.instance.addOnProviderConfigChangedListener { ... }`，
用户在 ProfileScreen 切换/更新 API Key 时会触发该监听器调用
`reassembleCompetitionEngine()`——由于 App 冷启动阶段通常已经装配过一次
（`competitionEngine != null`），这次调用会被幂等判断直接短路跳过，
**实际什么都没做**。

`CompetitionEngine`/`CompetitionRoundManager` 内部持有的 `provider` 是
构造时传入的 `private val`，不会自己感知外部 Key 变化。结果是：用户切换
Key 后，界面上看起来"配置已保存"，但竞赛功能（裁判评审 `judgeRound`、
角色产出 `selfEvaluateEntry` 等）实际仍在用旧 Key 发起 LLM 请求——这条
影响是真实的，不是理论风险。

## 修复方案

给 `reassembleCompetitionEngine()` 新增 `force: Boolean = false` 参数：

- **`force = false`**（App 冷启动首次尝试）：保持原幂等语义，已装配则跳过。
- **`force = true`**（`onProviderConfigChanged` 回调触发）：说明 Provider
  配置确实发生了变化，无条件重新构造 `SpecialtyEvolutionEngine` →
  `CompetitionEngine` → `CompetitionRoundManager` 三件套，用新
  `activeProvider` 替换旧实例。

`ZaijianApp.kt` 两处调用点同步更新：

```kotlin
// 首次尝试：force=false
scope.launch { appContainer.reassembleCompetitionEngine(force = false) }

// 配置变更回调：force=true
ProviderManager.instance.addOnProviderConfigChangedListener {
    scope.launch { appContainer.reassembleCompetitionEngine(force = true) }
}
```

## 为什么不用"比较新旧 Provider 是否相等"的方案

`ProviderManager.activeProvider` 是计算属性，每次读取都 `new` 一个全新的
`OpenAICompatProvider` 实例，且该类未覆写 `equals()`/`hashCode()`（默认
引用相等），`baseUrl`/`apiKey`/`defaultModel` 均为 `private`，
`AppContainer` 这一层拿不到可比较的字段。引入这种比较需要改
`OpenAICompatProvider` 的可见性或加 `data class` 语义，属于超出本次
bug 修复范围的架构改动。

而 `onCreate()` 首次尝试与 `onProviderConfigChanged` 回调两个调用点本身
就已经精确对应"第一次装配"与"配置确实变了"两种场景，让调用方显式传参
更直接、风险更低、也不引入额外的 Provider 比较逻辑。

## 为什么重新装配是安全的

`CompetitionRoundManager` 的状态机完全由数据库 `competition_rounds.status`
驱动（见该类头部注释），不持有跨调用的内存态。唯一调用方
`CompetitionViewModel` 通过
`AppContainer.instance.competitionRoundManager` 计算属性（`get()`）实时
读取（见该 ViewModel 私有属性 getter），不缓存旧引用——每次调用都拿到
当时最新的实例，重新装配后不会出现"半个操作在旧引擎、半个在新引擎"的
撕裂状态。

`SpecialtyEvolutionViewModel` 只引用 `specialtyProfileRepo`（不涉及
`competitionEngine`/`competitionRoundManager`），不受本次改动影响。

## 装配失败时的行为

沿用窗口02"复核新发现1"已补齐的 try-catch：装配失败时
`competitionEngine`/`competitionRoundManager` 保持**原值不变**（无论
`force` 与否都不清空旧实例）——`force=true` 场景下宁可继续用旧 Provider
兜底可用，也不要让功能突然变得完全不可用。下次 Provider 配置变更回调
触发时会自然重试。

## 改动文件

- `app/src/main/java/com/zaijian/zhoumuyun/data/AppContainer.kt`
  - `reassembleCompetitionEngine()` 增加 `force: Boolean = false` 参数，
    幂等判断改为 `if (competitionEngine != null && !force) return@withLock`
  - 方法文档重写，记录修复动机与方案取舍
- `app/src/main/java/com/zaijian/zhoumuyun/ZaijianApp.kt`
  - 首次调用显式传 `force = false`
  - `onProviderConfigChanged` 回调内调用改为 `force = true`
  - 更新相关注释，移除"行为与搬家前完全一致"的过时表述
