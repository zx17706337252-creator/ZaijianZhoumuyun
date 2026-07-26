package com.zaijian.zhoumuyun.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.viewinterop.AndroidView
import android.text.Layout
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
                // 修复（超长单行文本溢出/闪退）：Android TextView 默认 BREAK_STRATEGY_SIMPLE
                // 对长段落只做简单换行；HIGH_QUALITY 策略会做整段落级别的换行优化，
                // 效果更平滑。但真正保证"无空格可断的超长 token（堆栈路径/JSON/URL，
                // 数百到上千字符无空格）不会溢出屏幕右侧"的关键手段，是下方的
                // wrapLongLines() 预处理——在超长无空格行中每隔固定字符数插入一个
                // 零宽空格（\u200B）作为断行点，TextView 的换行算法能在该位置断行，
                // 从而彻底避免整行溢出或撑爆 Canvas。
                // （注：曾误写 `wordWrapMode = Layout.WORD_WRAP_MODE_BREAK_ONCE`——
                // Android SDK 里 TextView 没有 wordWrapMode 属性，Layout 类也没有
                // WORD_WRAP_MODE_BREAK_ONCE 常量，这是不存在的 API，编译期就会报
                // Unresolved reference。已删除，不影响实际效果，因为真正生效的是
                // wrapLongLines() 的零宽空格方案。）
                breakStrategy = Layout.BREAK_STRATEGY_HIGH_QUALITY
                // 修复：TextView 默认会消费触摸事件，导致外层 WorldBubble 的
                // combinedClickable（长按复制）收不到长按事件。设为 false 让事件
                // 透传到外层，用户就能长按角色气泡复制内容了。
                //
                // ── 耦合前提（复核意见四）──
                // 这里禁用 clickable 依赖于当前 Markwon/MarkdownText 配置**未启用任何
                // 链接点击相关插件**（未使用 Linkify、未设置 MovementMethod、未注册
                // LinkPlugin）。如果未来给 Markdown 渲染增加可点击链接功能（如 Linkify、
                // autolink、自定义链接点击回调），必须重新评估这个改动——届时需要让
                // 链接点击和长按复制共存（例如用 GestureDetector 区分单击和长按），
                // 而不是简单地恢复 isClickable=true（那会让长按复制再次失效）。
                // 在改动链接功能前先搜索本注释的"耦合前提"关键词确认影响范围。
                isClickable = false
                isLongClickable = false
            }
        },
        update = { view ->
            // 颜色/字号随主题切换时直接设置，不触发 Markdown 重解析
            view.setTextColor(textColor.toArgb())
            view.textSize = style.fontSize.value
            // 只有内容真正变化时才重新解析 Markdown
            if (markdown != lastRendered.value) {
                // Fix-预览闪退：Markwon（含 TablePlugin/TaskListPlugin 扩展）对畸形输入
                // ——最常见的是表头/分隔行/数据行列数对不齐的 GFM 表格——已知会抛
                // ArrayIndexOutOfBoundsException 之类的运行时异常。这里传入的文本
                // 可能是用户自己写的完整文档（md 文件预览）或 AI 生成的原始回复，
                // 内容不可控，任何一处畸形语法都会在这行同步崩掉整个 App，且不分
                // 文档格式——凡是走 MarkdownText 渲染的地方（聊天气泡、圆桌、文件
                // 预览）都有同样风险。加 try-catch 兜底：解析失败就退化成纯文本
                // 展示，不再让第三方库的解析异常传播到 Compose/Activity 层。
                //
                // 修复（超长单行文本溢出/闪退）：对 markdown 文本做预处理，
                // 在超长无空格行中按固定长度插入零宽空格（\u200B），给 TextView
                // 提供额外的断行机会。breakStrategy=HIGH_QUALITY 能在零宽空格处
                // 断行，视觉上不产生多余字符，但能防止超长行溢出屏幕或打崩 Canvas。
                val safeMarkdown = wrapLongLines(markdown)
                try {
                    markwon.setMarkdown(view, safeMarkdown)
                } catch (e: Throwable) {
                    com.zaijian.zhoumuyun.util.ZLog.e("MarkdownText", "Markwon 解析失败，降级为纯文本", e)
                    view.text = safeMarkdown
                }
                lastRendered.value = markdown
            }
        },
        modifier = modifier,
    )
}

/**
 * 超长无空格行预处理：在每个超过 [MAX_LINE_LENGTH] 字符且不含空格的行中，
 * 每隔 [MAX_LINE_LENGTH] 个字符插入一个零宽空格（\u200B）。
 *
 * 零宽空格在视觉上不可见，但 TextView 的 HIGH_QUALITY 断行策略会在该位置断行，
 * 从而防止超长单行文本（如完整堆栈路径、拼接 JSON、长 URL）溢出屏幕右侧
 * 或在极端情况下直接撑崩 TextView 的 Canvas 渲染。
 *
 * 只处理"无空格可断"的行——正常包含空格的行由 TextView 自行断行即可，
 * 不需要额外插入零宽空格（避免影响正常文本的断行质量）。
 */
private const val MAX_LINE_LENGTH = 120

private fun wrapLongLines(text: String): String {
    if (text.length <= MAX_LINE_LENGTH) return text

    val zwsp = '\u200B'
    return text.lines().joinToString("\n") { line ->
        if (line.length <= MAX_LINE_LENGTH || line.any { it.isWhitespace() }) {
            line
        } else {
            // 超长无空格行：每 MAX_LINE_LENGTH 个字符插入零宽空格
            buildString(line.length + line.length / MAX_LINE_LENGTH) {
                var count = 0
                for (ch in line) {
                    append(ch)
                    count++
                    if (count >= MAX_LINE_LENGTH) {
                        append(zwsp)
                        count = 0
                    }
                }
            }
        }
    }
}
