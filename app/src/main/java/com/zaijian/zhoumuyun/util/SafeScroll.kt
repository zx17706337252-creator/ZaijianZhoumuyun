package com.zaijian.zhoumuyun.util

import androidx.compose.foundation.lazy.LazyListState
import kotlinx.coroutines.CancellationException

/**
 * P1 崩溃修复：安全滚动到指定下标。
 *
 * 根因（agent_log.txt 实测 + CrashHandler 未捕获异常）：
 * 多处调用点直接用 Compose 重组作用域里读到的列表长度（比如
 * `messages.size`）当下标去调 `listState.animateScrollToItem(index)`。
 * 典型触发场景是"点发送按钮后立刻滚动"——`sendMessage()` 是异步的，
 * 新消息此时还没真正进入列表，此刻读到的 size 其实是"发送前"的旧长度，
 * 直接拿来当下标就越界了一位（合法下标是 0..size-1）。这个越界下标一旦
 * 抢在下次重组稳定前进入 LazyList 的 measure/subcompose 阶段，就会抛出
 * 未捕获异常，导致整个 App 闪退——调用链与日志里的
 * `animateScrollToItem → scrollBy → onScroll → forceRemeasure → subcompose`
 * 完全吻合。
 *
 * 这里做统一兜底，后续新增的"滚动到底部/滚动到指定项"都应改走这个函数，
 * 不要再直接调 `LazyListState.animateScrollToItem()`：
 *   1. 用 [LazyListState.layoutInfo] 里的 `totalItemsCount`——这是 Compose
 *      内部真实感知到的、当前已measure的项数，比调用方手里可能过期的
 *      state 变量更可靠——对目标下标做钳制，避免越界。
 *   2. 即便钳制之后仍然遇到某些边界竞态（比如动画进行中列表又变化），
 *      也用 try-catch 兜底：吞掉除 [CancellationException] 外的异常，
 *      把"整个 App 崩溃"降级成"这一次自动滚动没有生效"，并记一条日志
 *      方便后续排查，而不是静默吞掉、也不是让它继续往上抛。
 *
 * @param index 期望滚动到的下标（允许传入可能过期/越界的值，内部会钳制）。
 * @param tag   日志 tag，便于在导出日志里区分是哪个调用点触发的兜底。
 */
suspend fun LazyListState.safeAnimateScrollToItem(index: Int, tag: String = "SafeScroll") {
    val itemCount = layoutInfo.totalItemsCount
    if (itemCount <= 0 || index < 0) return
    val target = index.coerceIn(0, itemCount - 1)
    try {
        animateScrollToItem(target)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        ZLog.w(tag, "安全滚动失败已忽略（请求下标=$index，钳制后=$target，itemCount=$itemCount）", e)
    }
}
