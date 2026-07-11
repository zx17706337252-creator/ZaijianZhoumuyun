# Phase 3 · AppContainer 设计草稿

本目录下两个文件是 2026-07-11 会话里为决定 `AppContainer` 初始化方式而写的
完整代码对比草稿，**不是可以直接编译进项目的最终代码**——里面还没有处理
`relationshipEngine`/`pregnancyTriggerManager` 那两处已确认的功能性差异
（见审计报告 Phase 3 章节"设计决策"部分）。

- `option_A_onCreate.kt` —— **已选定方案**。跟着 `ZaijianApp.onCreate()` 走，
  同步构造，不需要 `@Volatile`/双重检查锁。
- `option_B_lazy.kt` —— 备选方案（独立 by lazy 单例 + 双重检查锁），已否决，
  留档仅供对比参考，不要照抄。

下个窗口写正式的 `data/AppContainer.kt` 时：
1. 以 `option_A_onCreate.kt` 的结构为基础
2. 按报告里"设计决策 2"处理 `relationshipEngine`（统一带 `milestoneDao`）
   和 `pregnancyTriggerManager`（`aiJudge` 保持两边差异化，具体写法待定）
3. 写完后按报告"设计决策 3"补做 `eventRepo`/`memoryRepo`/`memoryEngine`/
   `pregnancyRepo`/`characterStateRepo` 的逐参数核查，确认没有遗漏的差异点
4. 这两个草稿文件用完可以删除，不属于最终交付物
