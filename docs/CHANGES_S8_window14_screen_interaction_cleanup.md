# S8 窗口14 修复变更记录 — 全Screen交互元素可达性扫描后续清理

**修复日期：** 2026-07-17
**依据：** `zaijian_s8_window14_report.md`（315条结论，315条通过；复核顺带发现4个问题）
**基准代码：** `zaijian_s8_window13_full.zip`

---

## 本窗口审计概览

窗口14对 `ui/screen/` 目录下全部52个 `.kt` 文件做了交互元素可达性扫描，
共识别315个可交互元素，全部确认真实生效——未发现"视觉存在但无绑定回调"、
"跳转到位但目标页占位"、"绑定了假数据"这类功能性问题。本窗口无需修改任何
业务逻辑代码。

复核过程中顺带发现4个非功能性问题（3个冗余 import + 1个防御风格不一致），
本次全部处理。

---

## 修复1-3（W14-F1/F2/F3）：三处冗余 `clickable` import

**文件：**
- `ui/screen/ProfileStatsRow.kt`（原第5行）
- `ui/screen/RoundtableBubble.kt`（原第18行）
- `ui/screen/RoundtableMemberStrip.kt`（原第18行）

**问题：** 三个文件均 `import androidx.compose.foundation.clickable`，但全文
无任何 `clickable` 调用（均为纯展示组件）。

**核实：** 逐文件 grep 确认 `clickable` 关键字仅出现在 import 行，无其他
匹配，报告结论属实。

**修复：** 删除三处未使用的 import。不影响任何运行时行为。

---

## 修复4（W14-F4）：底部导航栏 `navigate()` 调用缺少防御风格统一

**文件：** `ui/screen/AppNavigation.kt`

**问题：** 底部导航栏切换 Tab 时直接调用 `navController.navigate(item.
targetRoute) { popUpTo(...); launchSingleTop = true; restoreState = true }`，
无 try-catch。与同文件的 `navigateSingle()` 扩展函数（有 try-catch，路由
无效时捕获 `IllegalArgumentException` 并静默降级）风格不一致。

**核实：** `navigateSingle()` 之所以未被底部导航复用，是因为它的签名只
支持 `launchSingleTop` 一个选项，表达不了 Tab 切换需要的 `popUpTo(
startDestination){ saveState = true }` + `restoreState` 组合——这是原有
注释里已经写明的有意设计，不是遗漏调用 `navigateSingle`。`item.targetRoute`
本身是 `bottomNavItems`（私有硬编码列表）中的编译期常量，均已在 NavHost
注册，实际触发 `IllegalArgumentException` 的概率为零，报告也确认了这一点
——这是一个风格一致性问题，不是当前会复现的功能缺陷。

**修复：** 新增 `NavController.navigateBottomTab(route: String)` 扩展函数，
作为 `navigateSingle` 的姊妹函数：保留 Tab 切换所需的完整 `popUpTo`/
`restoreState` 语义，同时包上与 `navigateSingle` 一致的 try-catch + 静默
日志降级。底部导航栏调用点改为 `navController.navigateBottomTab(item.
targetRoute)`。

**为何不改动其余3处原生 `navigate()` 调用：** 逐一核实后发现，AppNavigation.
kt 里另外3处直接调用 `navController.navigate()` 的地方（深链路由消费、
Splash→Briefing、Briefing→World）均不在报告 W14-F4 的点名范围内，且各自
已有独立的处理：深链路由消费本身已包 try-catch（`popUpTo(startDestinationId)
{ saveState = false }`，与底部Tab切换的 `saveState = true` 语义不同）；
Splash/Briefing 两处是启动时一次性场景转换，路由同样是编译期常量，且各自
用的是 `popUpTo(...){ inclusive = true }`（与 Tab 切换的"保留返回栈状态"
语义相反，是"离开就不回退"的一次性转场）。这些不属于本次报告指出的问题，
维持原样，避免在报告未要求的范围内引入不必要的改动。

**效果：** 底部导航栏与页面内导航现在共享同一套"try-catch + 静默日志降级"
防御风格，未来若 `bottomNavItems` 的 `targetRoute` 来源发生变化（例如改为
运行时动态拼接），这层防护已经就位，不需要事后补。

---

## 验证

- 三个冗余 import 删除后，逐文件 grep 确认 `clickable` 关键字完全消失
  （原先仅出现在被删除的 import 行）。
- `AppNavigation.kt` 括号平衡校验：初次用逐字符扫描脚本出现 1 处误报
  （`matchesRouteTemplate` 函数里 `template.substringBefore('{')` 的字符
  字面量 `'{'` 被脚本误当作结构花括号），改用剥离字符串/字符字面量后的
  版本重新校验，确认平衡（Gradle 不可用于此环境）。
- 手工核对 `navigateSingle`/`navigateBottomTab` 两个函数定义及底部导航
  栏调用点替换后的完整代码块，确认语法结构完整、语义与原实现一致（仅
  多了 try-catch 包裹）。
- `.kt` 文件总数核对：330，未新增/删除文件。

---

## 未处理项

无。本窗口4项（W14-F1至F4）均已修复，其余315条结论均为"真实生效"，
无需改动。
