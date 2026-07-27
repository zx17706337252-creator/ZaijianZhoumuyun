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
 * @param selectable Fix-BubbleTextSelect：默认 false，走"长按整条复制"这套自定义
 *                   交互（isClickable/isLongClickable 关闭，事件透传给外层
 *                   combinedClickable，见下方 Fix-LongClickReset）。
 *                   由 BubbleActionMenu 里"选择文字"选项驱动为 true 时，切到
 *                   Android 原生的 setTextIsSelectable(true) 模式——此时交由
 *                   系统接管长按，弹出拖选手柄和系统自带的复制/全选气泡菜单，
 *                   两种模式互斥，不能同时生效。
 */
@Composable
fun MarkdownText(
    markdown: String,
    textColor: Color,
    style: TextStyle,
    modifier: Modifier = Modifier,
    selectable: Boolean = false,
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
                // ── 耦合前提（复核意见四，已过期，见下方 Fix-LongClickReset）──
                // 这里禁用 clickable 原本被认为依赖"未启用任何链接点击相关插件"。
                // 实际上即使不启用任何链接插件，Markwon 4.6.2 的 CorePlugin 默认
                // 行为就会在 setMarkdown() 内部对 TextView 调用
                // setMovementMethod(LinkMovementMethod)（用于让文本里天然的
                // ClickableSpan/URLSpan 可点击，即便本项目没有主动注册任何链接
                // 插件，CorePlugin 本身也会挂默认的 movementMethod）。而 Android
                // TextView.setMovementMethod() 源码里会根据传入的 MovementMethod
                // 是否"可点击"自动重新计算并覆盖 isClickable/isLongClickable——
                // 这会把此处 factory 里设的 false 静默翻回 true。
                // factory 只在 View 首次创建时跑一次，此后 LazyColumn 复用/更新
                // View 都走 update{}，所以只要 setMarkdown 被调用过一次（几乎
                // 每条真实消息都会调用），这个开关就被 Markwon 自己重新打开了，
                // 长按复制和文本可选状态都随之失效——与此前"耦合前提"注释所设想
                // 的风险模型不同：问题不需要真的启用链接插件才触发，Markwon 核心
                // 本身在当前版本下就会做这件事。
                // 见下方 update{} 块内 setMarkdown() 调用之后的强制复位。
                isClickable = false
                isLongClickable = false
                movementMethod = null
            }
        },
        update = { view ->
            // 颜色/字号随主题切换时直接设置，不触发 Markdown 重解析
            view.setTextColor(textColor.toArgb())
            view.textSize = style.fontSize.value

            // Fix-BubbleTextSelect：selectable 状态本身不依赖 markdown 内容是否
            // 变化，每次重组都要同步——因为这个值由外部菜单操作驱动，随时可能
            // 在 markdown 不变的情况下从 false 切到 true（点了"选择文字"）或
            // 从 true 切回 false（选完文字后退出选择模式）。
            //
            // setTextIsSelectable(true)：Android 原生选字模式，内部会自己接管
            // 长按并挂一个 ArrowKeyMovementMethod，此时不需要（也不应该）再管
            // isClickable——选择模式和"外层 combinedClickable 长按复制"是两套
            // 互斥的手势系统，同时打开只会互相打架。
            //
            // 切回 false 时，必须重新执行一遍 Fix-LongClickReset 的收紧逻辑——
            // setTextIsSelectable(false) 不保证把 movementMethod/isClickable
            // 干净地复位到我们要的状态，稳妥起见每次都显式设一遍。
            if (view.isTextSelectable != selectable) {
                view.setTextIsSelectable(selectable)
                if (!selectable) {
                    view.movementMethod = null
                    view.isClickable = false
                    view.isLongClickable = false
                }
            }

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

                // Fix-LongClickReset：setMarkdown() 内部会把 movementMethod 设回
                // LinkMovementMethod，连带把 isClickable/isLongClickable 重新
                // 打开（TextView 框架行为，见上方 factory 块注释）。每次解析后
                // 必须重新强制关闭，否则外层 WorldBubble.combinedClickable 的
                // onLongClick（长按复制）收不到事件——这正是此前"长按复制/角色
                // 气泡文字可拖选"失效的根因：factory 里设的 false 只在 View
                // 首次创建时生效一瞬间，第一次真正的 setMarkdown() 调用就会
                // 把它悄悄翻回 true。
                //
                // Fix-BubbleTextSelect 连带修改：这个复位只在非选字模式下执行——
                // selectable=true 时用户正在框选文字，这里如果无条件复位会把
                // setTextIsSelectable(true) 挂的选择态 movementMethod 冲掉，
                // 选字模式下理论上很少会撞上 markdown 内容变化（消息已经渲染
                // 完了才会去长按选字），但防御性地把判断加上，避免流式消息还
                // 没结束、用户已经点了"选择文字"这种边缘情况下选择手柄突然消失。
                if (!selectable) {
                    view.movementMethod = null
                    view.isClickable = false
                    view.isLongClickable = false
                }
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
