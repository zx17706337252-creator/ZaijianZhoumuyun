package com.zaijian.zhoumuyun.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.viewinterop.AndroidView
import android.widget.TextView
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin

/**
 * Phase 21 — Markdown 渲染组件
 *
 * 使用 Markwon 将 Markdown 文本渲染为富文本，支持：
 *   - **粗体** / *斜体* / ~~删除线~~
 *   - | 表格 |
 *   - - 列表 / - [x] 任务列表
 *   - # 标题（H1-H6）
 *   - `行内代码` 和代码块
 *   - emoji（原生 Unicode，无需插件）
 *
 * 用途：仅用于角色气泡（role != "user"）。用户气泡继续用原生 Text。
 *
 * @param markdown   Markdown 格式字符串
 * @param textColor  文字颜色（与气泡主题一致）
 * @param style      字体样式（fontSize 同步自 AppTypography.body）
 * @param modifier   布局修饰符
 */
@Composable
fun MarkdownText(
    markdown: String,
    textColor: Color,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // Markwon 实例按 applicationContext 缓存，避免持有 Activity Context 导致内存泄漏。
    // key 为 context（引用稳定），整个 App 生命周期内只构建一次。
    val markwon = remember(context) {
        Markwon.builder(context.applicationContext)
            .usePlugin(TablePlugin.create(context.applicationContext))
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TaskListPlugin.create(context.applicationContext))
            .build()
    }

    // UI M9 修复：Markwon.setMarkdown() 会重新解析并生成 Spannable，代价较高。
    // 原来 update{} 每次重组（包括仅颜色/字号变化时）都调用 setMarkdown，
    // 导致流式输出期间每个 token 都触发全量 Markdown 解析。
    // 用 remember 记录上一次已渲染的内容，仅在 markdown 字符串实际变化时才重新解析。
    val lastRendered = remember { androidx.compose.runtime.mutableStateOf("") }

    AndroidView(
        factory = { ctx ->
            TextView(ctx).apply {
                setTextColor(textColor.toArgb())
                textSize = style.fontSize.value   // sp → float，TextView.textSize 接受 sp
                // 让 TextView 宽度自适应父容器（Compose 会约束外层 Box 宽度）
                maxLines = Int.MAX_VALUE
                setLineSpacing(0f, 1.15f)         // 行距 1.15x，与 Compose Text 接近
            }
        },
        update = { view ->
            // 颜色/字号随主题切换时直接设置，不触发 Markdown 重解析
            view.setTextColor(textColor.toArgb())
            view.textSize = style.fontSize.value
            // 只有内容真正变化时才重新解析 Markdown
            if (markdown != lastRendered.value) {
                markwon.setMarkdown(view, markdown)
                lastRendered.value = markdown
            }
        },
        modifier = modifier,
    )
}
