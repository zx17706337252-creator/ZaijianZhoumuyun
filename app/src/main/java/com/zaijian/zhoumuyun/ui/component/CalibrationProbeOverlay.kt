package com.zaijian.zhoumuyun.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * v20 新增——坐标探针，从 WorldScreen.kt 和 CharacterScreen.kt 两份几乎
 * 一样但又不完全同步的拷贝中抽出来的共享实现。
 *
 * 抽出来的直接原因：CharacterScreen.kt（书架页）当时还停留在 v18 版本
 * （单击、最多4点、没有拖动/撤销/清空），WorldScreen.kt 已经是 v19（拖动+
 * 撤销+清空），两边各改各的、忘了同步，用户在书架页测试时才发现"拖了
 * 但读数没跟着变"——这正是两份重复代码没有共享一个真相来源的直接后果。
 * 现在两个页面都调用这一份，以后再改探针交互只需要改一处。
 *
 * v20 相对 v19 的两个新增能力（都是用户反馈直接触发的）：
 *   1. 可读性：v19 版本把全部点的编号+读数原地浮在图上，点一多
 *      （截图里有 45 个点）文字互相压在一起，根本看不清哪个数字对应
 *      哪个点。v20 改为「画面上只有小圆点+编号数字」，完整的 x,y 读数
 *      挪到屏幕顶部一个固定的可滚动列表面板里，一行一个点，等宽字体
 *      对齐，不再和背景图/其它点互相重叠。
 *   2. 导出：之前唯一的导出方式是截图再肉眼抄数字，正是可读性问题的
 *      根源。现在列表面板下面加一个"复制全部"按钮，用 Compose 自带的
 *      LocalClipboardManager 把全部点格式化成一行一个 "序号: x, y" 的
 *      纯文本，一次性拷进系统剪贴板，可以直接粘贴回聊天里发给我，不用
 *      再手抄。
 *
 * v21 修复（拖动中的指针被手指本身挡住看不见）：
 *   v20 一开始的做法是把"读数文字"往上挪开一段距离，但十字标记本身
 *   还是画在手指真实按压的坐标上——手指按下去的时候，指腹正好挡在
 *   十字所在的位置，读数挪开了也没用，因为看不见十字指在哪，等于
 *   还是盲拖。
 *   v21 的做法是把"代表当前坐标的可视十字"本身和"手指实际触摸点"
 *   分开：可视十字固定往上偏移 ProbeIndicatorOffsetY 画（约一节手指
 *   的长度，确保十字露在指腹上方），跟着手指左右同步移动，纵向保持
 *   这个固定偏移。读数气泡跟着可视十字走。
 *
 * v23 修复（记录的坐标和看到的十字对不上）：
 *   v21 修完"看不看得见"的问题后，遗留了一个更隐蔽的错配：视觉上
 *   移出去的十字只是"画在哪"，v21 当时特意让 dragPreview 里存的、
 *   最终松手时提交进 probePoints 的坐标仍然是手指的原始触摸坐标，
 *   理由是"不能让视觉偏移污染实际记录的数值"。但这个理由反过来看
 *   是错的：用户校准时天然是"看着十字对准拱门边缘再松手"，肉眼
 *   瞄准、确认准不准的基准自始至终是十字，不是被指腹盖住、根本看
 *   不见的那个原始触摸点。继续提交手指坐标，等于用户校准的对象和
 *   实际记录的对象压根不是同一个点，长期表现为"改了很多次数值都
 *   贴合不上拱门边缘"——这正是 WorldScreen.kt 里 v19→v22 数值反复
 *   横跳、archHeight 越改越不对的根因之一。
 *   现在提交时用跟十字视觉位置完全一致的坐标（同一份 offset/min
 *   clamp 反推归一化 fy），用户在屏幕上看见十字停在哪，记录的就是
 *   哪，不再有肉眼瞄准点和实际写入数据之间的隐藏偏差。
 */

/**
 * 可视十字相对手指真实触摸点的纵向上移量，以及贴屏幕顶部时的最小
 * 钳制值。这两个数值同时被"十字怎么画"和"点击松手时提交什么坐标"
 * 两处使用，必须是同一份定义——过去这里各处独立写死同一个数字
 * （64.dp / 4.dp），一旦只改其中一处，视觉位置和实际记录的坐标就会
 * 重新对不上，回到"十字对准了但记录的不是那个点"的老问题。
 * @param modifier              外部传入的修饰符（通常只需要 fillMaxSize，
 *                              调用方决定这个 Box 在自己的 z-order 里放哪层）；
 *                              目前调用方通过长按空白处的长按手势来关闭探针，
 *                              这里只负责拖动/记点/撤销/清空/导出。
 */
private val ProbeIndicatorOffsetY = 64.dp
private val ProbeIndicatorMinY = 4.dp

@Composable
fun CalibrationProbeOverlay(
    modifier: Modifier = Modifier,
) {
    var probePoints by remember { mutableStateOf(listOf<Pair<Float, Float>>()) }
    var dragPreview by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    val clipboard = LocalClipboardManager.current
    val density = LocalDensity.current
    // 十字视觉上移的偏移量，换算成像素——引用文件顶部的具名常量，
    // 跟下面绘制十字用的同一份常量保持一致，不再各自硬编码数字。
    val indicatorOffsetPx = with(density) { ProbeIndicatorOffsetY.toPx() }
    val indicatorMinPx = with(density) { ProbeIndicatorMinY.toPx() }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        val fx = (offset.x / size.width).coerceIn(0f, 1f)
                        val fy = (offset.y / size.height).coerceIn(0f, 1f)
                        dragPreview = fx to fy
                    },
                    onDrag = { change, _ ->
                        val fx = (change.position.x / size.width).coerceIn(0f, 1f)
                        val fy = (change.position.y / size.height).coerceIn(0f, 1f)
                        dragPreview = fx to fy
                    },
                    onDragEnd = {
                        // [v23 修复] 此前提交的是手指真实触摸坐标 dragPreview，
                        // 跟画面上黄色十字的显示位置相差固定 64dp（十字为了
                        // 不被指腹挡住而故意上移，参见下方十字绘制处的说明）。
                        // 但用户校准时是"看着十字对准拱门边缘再松手"，肉眼
                        // 瞄准的基准是十字，不是被手指盖住看不见的触摸点——
                        // 提交手指坐标等于把用户校准的对象和实际记录的对象
                        // 完全对不上，每一次记录都会系统性地比用户以为的
                        // 位置偏下一整截，这正是历次校准数值反复跑偏、改了
                        // 很多次都对不上拱门边缘的根因。
                        // 现在提交跟十字视觉位置完全一致的坐标：用同一套
                        // 偏移量（-indicatorOffsetPx，下限钳制到 indicatorMinPx）
                        // 反推出对应的归一化 fy，用户看见什么就记录什么。
                        // 全程用像素运算，不依赖 BoxWithConstraints 的
                        // maxWidth/maxHeight（那两个是在 pointerInput 修饰符
                        // 之外才声明的，这里的协程闭包访问不到）。
                        dragPreview?.let { (fx, fy) ->
                            val rawIndicatorPx = fy * size.height - indicatorOffsetPx
                            val indicatorPx = if (rawIndicatorPx < indicatorMinPx) indicatorMinPx else rawIndicatorPx
                            val committedFy = (indicatorPx / size.height).coerceIn(0f, 1f)
                            probePoints = probePoints + (fx to committedFy)
                        }
                        dragPreview = null
                    },
                    onDragCancel = { dragPreview = null },
                )
            },
    ) {
        val psw = maxWidth
        val psh = maxHeight

        // 画面上只留小圆点 + 编号数字（不再带 x,y 读数——那些数字挪到
        // 下面的固定列表面板里），点位再多也不会互相盖住看不清。
        probePoints.forEachIndexed { i, (fx, fy) ->
            Box(
                modifier = Modifier
                    .offset(x = psw * fx - 9.dp, y = psh * fy - 9.dp)
                    .size(18.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(Color(0xFF00E5FF)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text     = "${i + 1}",
                    color    = Color.Black,
                    fontSize = 9.sp,
                )
            }
        }

        // 正在拖动、尚未松手的预览点。v21：十字和手指触摸点分开画，
        // 十字固定上移 ProbeIndicatorOffsetY，避免被指腹整个盖住。
        // v23：松手时提交进 probePoints 的坐标已经改成跟这里的十字
        // 视觉位置一致（见上方 onDragEnd 里的换算），不再是手指的
        // 原始触摸坐标——这里画的十字就是最终会被记录的那个点，
        // 所见即所得。
        dragPreview?.let { (fx, fy) ->
            // 可视十字的纵向位置：手指坐标往上偏移 ProbeIndicatorOffsetY，
            // 同时钳制在 ProbeIndicatorMinY 以上——手指按在屏幕最顶部
            // 附近时，十字贴着屏幕顶沿显示，不会被推到负坐标看不见。
            // 这里的 clamp 逻辑必须和 onDragEnd 里的像素版本保持一致，
            // 否则会重新出现"十字停在一个位置、实际记录另一个位置"的
            // 错配。
            val indicatorY = (psh * fy - ProbeIndicatorOffsetY).let {
                if (it < ProbeIndicatorMinY) ProbeIndicatorMinY else it
            }
            val indicatorX = psw * fx

            // 连接线：从手指真实按压位置一直画到可视十字，让人能看出
            // 十字当前对应的是哪根手指、哪个触点——固定在触点 x 坐标，
            // 十字纵向被钳制、偏移量对不上时，线的长度会跟着变化。
            Box(
                modifier = Modifier
                    .offset(x = indicatorX - 1.dp, y = indicatorY + 12.dp)
                    .size(2.dp, (psh * fy - indicatorY - 12.dp).coerceAtLeast(0.dp))
                    .background(Color(0xFFFFEB3B).copy(alpha = 0.5f)),
            )

            // 可视十字：跟手指左右同步移动，纵向固定露在指腹上方。
            Box(
                modifier = Modifier
                    .offset(x = indicatorX - 12.dp, y = indicatorY - 1.dp)
                    .size(24.dp, 2.dp)
                    .background(Color(0xFFFFEB3B)),
            )
            Box(
                modifier = Modifier
                    .offset(x = indicatorX - 1.dp, y = indicatorY - 12.dp)
                    .size(2.dp, 24.dp)
                    .background(Color(0xFFFFEB3B)),
            )

            // 读数气泡：紧贴在可视十字正上方（不是手指上方），十字既然
            // 已经露出来了，气泡跟着十字走即可，不需要再单独躲避手指。
            // 水平方向按估算宽度居中对齐到十字 x，靠近屏幕左右边缘时
            // 钳制在可见范围内。
            val bubbleW = 90.dp
            val rawX = indicatorX - bubbleW / 2
            val readoutX = when {
                rawX < 4.dp                  -> 4.dp
                rawX + bubbleW > psw - 4.dp  -> psw - bubbleW - 4.dp
                else                          -> rawX
            }
            val readoutY = (indicatorY - 28.dp).let { if (it < 4.dp) 4.dp else it }

            Text(
                text     = "${"%.3f".format(fx)}, ${"%.3f".format(fy)}",
                color    = Color(0xFFFFEB3B),
                fontSize = 14.sp,
                modifier = Modifier
                    .offset(x = readoutX, y = readoutY)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xCC000000))
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }

        // ── 固定读数列表面板（贴顶部，横跨整个宽度，内部可滚动）──────
        // 这是本次可读性修复的核心：45 个点不再各自散落在画面各处互相
        // 重叠，而是集中在这一个面板里，一行一个点，等宽字体对齐，
        // 想看哪个点的精确读数直接在这里找编号，不用再去图上找那个
        // 被其它文字挡住的数字。
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .padding(top = 96.dp, start = 12.dp, end = 12.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xE6111111)),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Text(
                    text     = "已记录 ${probePoints.size} 点",
                    color    = Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text     = "撤销",
                    color    = Color(0xFFFFEB3B),
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(enabled = probePoints.isNotEmpty()) {
                            probePoints = probePoints.dropLast(1)
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
                Text(
                    text     = "清空",
                    color    = Color(0xFFFF6B6B),
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(enabled = probePoints.isNotEmpty()) {
                            probePoints = emptyList()
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
                Text(
                    text     = "复制全部",
                    color    = Color(0xFF69F0AE),
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(enabled = probePoints.isNotEmpty()) {
                            val exportText = probePoints
                                .mapIndexed { i, (fx, fy) ->
                                    "${i + 1}: ${"%.3f".format(fx)}, ${"%.3f".format(fy)}"
                                }
                                .joinToString("\n")
                            clipboard.setText(AnnotatedString(exportText))
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }

            // 列表本身限高，超过一屏就在面板内部滚动，不会把整个面板
            // 撑到盖住全部背景图看不见校准对象。
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .padding(bottom = 8.dp),
            ) {
                items(probePoints.size) { i ->
                    val (fx, fy) = probePoints[i]
                    Text(
                        text       = "#${i + 1}  x=${"%.3f".format(fx)}  y=${"%.3f".format(fy)}",
                        color      = Color(0xFF00E5FF),
                        fontSize   = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier   = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}
