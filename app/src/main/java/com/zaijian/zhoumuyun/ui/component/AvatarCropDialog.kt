package com.zaijian.zhoumuyun.ui.component

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme

// ─────────────────────────────────────────────────────────────
//  AvatarCropDialog — 头像拖拽缩放裁剪弹窗
//
//  v46 头像重新设计（2026-07-03）：旧版本只有一种圆形裁剪框，裁出的
//  512×512 正方形成品图被塞进公馆拱形（细高比例约 1:2.17）容器后，
//  超出正方形之外的区域没有真实画面，只剩容器背景色——形状不匹配
//  是存储设计问题，不是能靠调渲染参数修好的 bug。
//
//  新方案：
//    · 不再在这里裁出成品图，只产出归一化的 offset/scale 参数，
//      交给上层（IdentityViewModel）决定存到 avatarCropCircle* 还是
//      avatarCropTall*（原图本身完整保留，见 IdentityViewModel.
//      onAvatarCropped 头部注释）。
//    · 新增 CropShape 参数：CIRCLE（详情页圆形）和 TALL_RECT（公馆
//      拱形 + 书架椭圆共用，两处展示比例一致，不再分别裁剪）。
//      两种形状是两次独立的裁剪流程，各自产出一套参数——用户需要
//      分别为圆形和竖长矩形各调一次，才能让两边都精确取景。
//    · TALL_RECT 的宽高比取三层实测均值 1:2.171（详见 2026-07-03
//      对话中 SECOND/FIRST/BASEMENT 三层 archHeight/cardW 的实测
//      推算），跟公馆/书架最终展示的取景范围高度吻合。
//
//  交互说明（两种形状通用）：
//    - 单指拖拽：平移图片（offsetX/offsetY）
//    - 双指捏合：缩放图片（scale，范围 1.0 ~ 5.0）
//    - 裁剪框固定居中，图片在框内自由移动
//    - 「确认」时把当前 offset + scale 传出，由调用方决定写入哪一套
//      字段
//
//  参数约定（传给调用方 onConfirm 回调）：
//    normalizedOffsetX / normalizedOffsetY：图片中心相对裁剪框中心的
//      平移量，圆形场景归一化到圆半径，矩形场景分别归一化到框宽/框高
//      的一半（与 BreathingAvatar.cropOffsetX/Y 的换算方式对应）。
//    scale：当前缩放倍数
//
//  设计约束：
//    - 圆形：裁剪框直径 = min(屏宽, 屏高) × 0.72
//    - 竖长矩形：裁剪框宽度 = 屏宽 × 0.60，高度按 1:2.171 比例换算
//    - 图片初始以 Crop 模式铺满裁剪框（scale=1 时图片刚好覆盖裁剪框）
//    - 边界限制：图片边缘不能露出裁剪框（scale 越大，可移动范围越大）
// ─────────────────────────────────────────────────────────────

/** 裁剪框形状：圆形（详情页）、竖长矩形（公馆拱形 + 书架椭圆共用）或全屏（聊天背景图） */
enum class CropShape {
    CIRCLE,
    TALL_RECT,
    FULL_SCREEN,
}

/** 公馆/书架共用的竖长矩形取景比例，宽:高 = 1:TALL_RECT_HEIGHT_RATIO */
private const val TALL_RECT_HEIGHT_RATIO = 2.171f

data class CropParams(
    val uri: Uri,
    /** 圆形：相对裁剪圆半径的比例；矩形：相对裁剪框宽/高各自一半的比例。范围约 -1..1 */
    val normalizedOffsetX: Float,
    val normalizedOffsetY: Float,
    /** 缩放倍数（1.0 = 图片刚好铺满裁剪框） */
    val scale: Float,
    /** 本次裁剪对应的形状，调用方据此决定写入 avatarCropCircle* 还是 avatarCropTall* */
    val shape: CropShape,
)

@Composable
fun AvatarCropDialog(
    uri: Uri,
    shape: CropShape = CropShape.CIRCLE,
    onConfirm: (CropParams) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors  = ZaijianTheme.colors
    val accent  = colors.accent
    val density = LocalDensity.current

    // ── 手势状态 ─────────────────────────────────────────────
    // [bug 修复] 此前这四个状态用不带 key 的 `remember`，导致同一个
    // AvatarCropDialog 调用点（例如 CharacterDetailScreen 里
    // `cropStep?.let { AvatarCropDialog(...) }`）在 CIRCLE 步骤确认后
    // 切换到 TALL_RECT 步骤时，Compose 只是在原地重组、并不会重新
    // remember——isConfirming 在圆形步骤点确认时被设成 true 后就再也
    // 没有机会复位，导致矩形步骤的「确认」按钮每次点击都被
    // `if (isConfirming) return@Button` 挡掉、表现为按钮点不动；
    // 同时 scale/offsetX/offsetY 也会把圆形步骤的手势状态带进矩形
    // 步骤。用 `uri to shape` 作为 key，每次裁剪形状切换（或换了张图）
    // 都会重新初始化这组状态，isConfirming 保证每次进入弹窗都是干净
    // 的 false，且两步裁剪的手势状态互不污染。
    val stateKey = uri to shape
    var scale   by remember(stateKey) { mutableFloatStateOf(1f) }
    var offsetX by remember(stateKey) { mutableFloatStateOf(0f) }
    var offsetY by remember(stateKey) { mutableFloatStateOf(0f) }
    var isConfirming by remember(stateKey) { mutableStateOf(false) }

    // 裁剪框尺寸（由 onSizeChanged 实测容器后算出）。圆形只用半径；
    // 矩形宽高分别算，因为两个方向的可移动边界不一样。
    // FULL_SCREEN 复用 cropHalfW/cropHalfH（宽高各自独立，而不是像
    // TALL_RECT 那样按固定比例换算），语义上等价于"矩形裁剪框铺满
    // 整个可视容器"。
    var cropRadius by remember { mutableFloatStateOf(0f) }  // CIRCLE 用
    var cropHalfW  by remember { mutableFloatStateOf(0f) }  // TALL_RECT / FULL_SCREEN 用
    var cropHalfH  by remember { mutableFloatStateOf(0f) }  // TALL_RECT / FULL_SCREEN 用

    // ── [v26 修复] 原图完整宽高比 ──────────────────────────────
    //  此前 AsyncImage 直接把 size(width, height) 设成裁剪框本身的
    //  尺寸、并用 ContentScale.Crop——这一步在用户能拖动之前，就已经
    //  把图片按裁剪框比例"预裁剪"掉一刀（例如横图配圆形框，左右直接
    //  被切掉，用户后续怎么拖都拖不回被切掉的部分）。手势能移动的只是
    //  这个已经被裁过的结果图，而不是原图全貌，表现为"强制截取图片
    //  中间、无法自由选取范围"。
    //  修复：改用 rememberAsyncImagePainter 拿到原图真实 intrinsicSize，
    //  图片层按"完整保留原图、覆盖裁剪框较大边"的基准尺寸铺设（等价于
    //  scale=1 时的 Crop 效果，但不丢像素），用户的 pan/zoom 手势在此
    //  基准上叠加，才能真正拖到原图的任意区域。
    val painter = rememberAsyncImagePainter(model = uri)
    // Coil 2.7.0：state 属性声明为 `var state: State by mutableStateOf(State.Empty)`，
    // 属性委托的 getter 直接返回裸 AsyncImagePainter.State 值（不是 State<T> 包装）。
    // 所以 painterState 的类型就是 AsyncImagePainter.State 本身，没有 .value 属性。
    // 直接 `painterState is AsyncImagePainter.State.Success` 即可 smart cast。
    val painterState = painter.state
    // 方案 5-1：图片加载完成前禁用交互，防止 imageAspect 突变导致
    // 裁剪参数在用户已调整后发生坐标系漂移。
    val isImageReady = painterState is AsyncImagePainter.State.Success
    val intrinsicSize = (painterState as? AsyncImagePainter.State.Success)
        ?.painter?.intrinsicSize
    // 原图宽/高比；拿不到真实尺寸前（图片还没加载完）先按 1:1 兜底，
    // 图片加载完成后会重组并换成真实比例，不影响最终裁剪参数正确性。
    val imageAspect = if (intrinsicSize != null &&
        intrinsicSize.width > 0f && intrinsicSize.height > 0f
    ) {
        intrinsicSize.width / intrinsicSize.height
    } else {
        1f
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // P3-31 修复：添加系统栏避让，此前 Dialog 全屏铺满但不避开
                // 状态栏和导航栏，导致裁剪框顶部/底部被系统栏遮挡。
                .statusBarsPadding()
                .navigationBarsPadding()
                .background(Color.Black.copy(alpha = 0.92f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier            = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {

                // ── 提示文字 ──────────────────────────────────
                Text(
                    text     = when (shape) {
                        CropShape.CIRCLE      -> "拖拽移动，双指缩放 · 圆形头像（详情页）"
                        CropShape.TALL_RECT   -> "拖拽移动，双指缩放 · 公馆/书架取景"
                        CropShape.FULL_SCREEN -> "拖拽移动，双指缩放 · 聊天背景取景"
                    },
                    color    = Color.White.copy(alpha = 0.60f),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(bottom = 16.dp),
                )

                // ── [v26 修复] 图片基准尺寸：完整保留原图，按"较大边覆盖
                //  裁剪框"铺设，而不是把容器强制设成裁剪框尺寸再用
                //  ContentScale.Crop 预裁一刀。计算方式等价于 Crop 的
                //  覆盖效果，但图片内容本身不丢任何像素——多出来的部分
                //  仍完整存在于绘制范围内，只是超出裁剪框的部分会被
                //  遮罩盖住；用户拖拽/缩放时，之前被遮住的区域能重新
                //  移回裁剪框内，才是真正"自由选取范围"，而不是像此前
                //  那样图片一进来就已经按裁剪框比例被切掉一部分、怎么
                //  拖都拖不回来（表现为"强制截取图片中间"）。
                val frameHalfW = when (shape) {
                    CropShape.CIRCLE                        -> cropRadius
                    CropShape.TALL_RECT, CropShape.FULL_SCREEN -> cropHalfW
                }
                val frameHalfH = when (shape) {
                    CropShape.CIRCLE                        -> cropRadius
                    CropShape.TALL_RECT, CropShape.FULL_SCREEN -> cropHalfH
                }
                // 裁剪框宽高比 vs 图片宽高比，取较大边覆盖（跟 Crop 语义一致）
                val frameAspect = if (frameHalfH > 0f) frameHalfW / frameHalfH else 1f
                val baseWidthPx: Float
                val baseHeightPx: Float
                if (imageAspect > frameAspect) {
                    // 图片比裁剪框更"宽"：以高度对齐裁剪框，宽度按比例溢出
                    baseHeightPx = frameHalfH * 2f
                    baseWidthPx  = baseHeightPx * imageAspect
                } else {
                    // 图片比裁剪框更"高瘦"：以宽度对齐裁剪框，高度按比例溢出
                    baseWidthPx  = frameHalfW * 2f
                    baseHeightPx = baseWidthPx / imageAspect
                }

                // 手势闭包里要用到的边界相关值全部通过 rememberUpdatedState
                // 包一层，保证每次 pan/zoom 回调读到的都是最新值——
                // pointerInput 的 key 只有 shape，不会因为这些值变化
                // （图片异步加载完成、容器 onSizeChanged 触发）而重新
                // 绑定手势，若不用 rememberUpdatedState 会一直用到首次
                // 组合时的旧边界，导致同样的"图没法拖满"问题。
                val latestBaseWidth  by rememberUpdatedState(baseWidthPx)
                val latestBaseHeight by rememberUpdatedState(baseHeightPx)
                val latestFrameHalfW by rememberUpdatedState(frameHalfW)
                val latestFrameHalfH by rememberUpdatedState(frameHalfH)
                // [bug 修复] 此前最小缩放写死 1f，而 baseWidthPx/baseHeightPx
                // 已经是"较大边覆盖裁剪框、较小边按图片比例溢出"的基准尺寸
                // （scale=1 时就是标准 Crop 覆盖效果）。对宽高比跟裁剪框差异
                // 很大的图片（比如横图配 FULL_SCREEN 细高框），scale=1 时
                // 溢出边已经远超框宽/框高，用户即使把 scale 缩到下限 1，
                // 看到的仍是被过度放大的局部——没法整体缩小看到图片全貌再
                // 自由选取范围，表现为"不能缩放，只能放大"。
                // 修复：最小缩放改成动态值——缩到图片两条边都刚好能完整塞进
                // 裁剪框（即溢出边收缩回框内）为止，min(...) 保证宽高两个
                // 方向都不会超出框；用 coerceAtMost(1f) 兜底，保证宽高比
                // 接近裁剪框的图片（min 算出来 >1）时，上限仍然是标准的
                // "scale=1 覆盖"效果，不会意外把图片缩得比覆盖状态还小。
                val latestMinScale by rememberUpdatedState(
                    if (baseWidthPx > 0f && baseHeightPx > 0f) {
                        minOf(
                            frameHalfW * 2f / baseWidthPx,
                            frameHalfH * 2f / baseHeightPx,
                        ).coerceAtMost(1f)
                    } else {
                        1f
                    }
                )

                // FULL_SCREEN 预览框按真实屏幕宽高比换算高度（而不是固定
                // 360dp），让用户在弹窗里看到的取景范围跟最终聊天页背景
                // 的实际宽高比一致——否则会出现"弹窗里看着居中，存到聊天
                // 页却发现上下被裁掉一截"的错位。
                val screenConfig = androidx.compose.ui.platform.LocalConfiguration.current
                val previewHeight = if (shape == CropShape.FULL_SCREEN) {
                    val screenAspect = screenConfig.screenWidthDp.toFloat() /
                        screenConfig.screenHeightDp.toFloat()
                    // 预览框宽度 = 对话框可用宽度，高度按屏幕宽高比反推，
                    // 并夹在一个合理范围内避免极端屏幕比例撑爆弹窗布局。
                    val heightDpValue = (screenConfig.screenWidthDp.toFloat() / screenAspect)
                        .coerceIn(280f, 520f)
                    heightDpValue.dp
                } else {
                    360.dp
                }

                // 方案 5-1：图片未就绪时禁用手势，避免用户调整后 imageAspect 突变
                // 导致裁剪参数在坐标系漂移后偏离预期位置。
                val gestureModifier = if (isImageReady) {
                    Modifier.pointerInput(shape) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val newScale = (scale * zoom).coerceIn(latestMinScale, 5f)
                            val maxOffsetX =
                                (latestBaseWidth  / 2f * newScale - latestFrameHalfW)
                                    .coerceAtLeast(0f)
                            val maxOffsetY =
                                (latestBaseHeight / 2f * newScale - latestFrameHalfH)
                                    .coerceAtLeast(0f)
                            offsetX = (offsetX + pan.x).coerceIn(-maxOffsetX, maxOffsetX)
                            offsetY = (offsetY + pan.y).coerceIn(-maxOffsetY, maxOffsetY)
                            scale = newScale
                        }
                    }
                } else {
                    Modifier
                }

                // ── 裁剪区域 ──────────────────────────────────
                // 手势挂在这个外层容器（覆盖整个可视区域）而不是
                // 图片本身，这样触摸裁剪框内、图片之外的空白区域也能
                // 正常拖拽/缩放，不会因为图片实际渲染尺寸的边界而失效。
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(previewHeight)
                        .onSizeChanged { size: IntSize ->
                            when (shape) {
                                CropShape.CIRCLE -> {
                                    // 裁剪圆半径 = 容器宽度的 36%（圆直径 = 72% 容器宽）
                                    cropRadius = size.width * 0.36f
                                }
                                CropShape.TALL_RECT -> {
                                    // 裁剪矩形宽度 = 容器宽度的 60%，高度按实测比例换算，
                                    // 并夹在容器高度内（避免极端窄屏把矩形撑出裁剪区域）。
                                    val halfW = size.width * 0.30f
                                    val halfH = (halfW * TALL_RECT_HEIGHT_RATIO)
                                        .coerceAtMost(size.height / 2f)
                                    cropHalfW = halfW
                                    cropHalfH = halfH
                                }
                                CropShape.FULL_SCREEN -> {
                                    // 聊天背景是全屏铺满，裁剪框直接等于整个可视容器
                                    // （不像 TALL_RECT 那样只占容器一部分），这样用户
                                    // 预览时看到的取景范围跟最终聊天页背景完全一致。
                                    cropHalfW = size.width  / 2f
                                    cropHalfH = size.height / 2f
                                }
                            }
                        }
                        .then(gestureModifier),
                    contentAlignment = Alignment.Center,
                ) {
                    // 图片层：graphicsLayer 做用户手势变换，不接触摸事件
                    // （手势已挂在外层容器，这里只负责按变换结果绘制）
                    Image(
                        painter            = painter,
                        contentDescription = null,
                        contentScale       = ContentScale.Fit,
                        modifier           = Modifier
                            .requiredSize(
                                width  = with(density) { baseWidthPx.toDp() },
                                height = with(density) { baseHeightPx.toDp() },
                            )
                            .graphicsLayer {
                                scaleX        = scale
                                scaleY        = scale
                                translationX  = offsetX
                                translationY  = offsetY
                            },
                    )

                    // 裁剪框遮罩层：框外区域半透明遮住，框内显示图片
                    // 用 drawWithContent 在图片上叠加：先画内容，再画框外的遮罩和边框
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .drawWithContent {
                                drawContent()

                                val cx = size.width  / 2f
                                val cy = size.height / 2f

                                when (shape) {
                                    CropShape.CIRCLE -> {
                                        val radius = size.width * 0.36f

                                        // 四片矩形遮住圆外区域
                                        drawRect(
                                            color   = Color.Black.copy(alpha = 0.55f),
                                            topLeft = Offset(0f, 0f),
                                            size    = androidx.compose.ui.geometry.Size(size.width, cy - radius),
                                        )
                                        drawRect(
                                            color   = Color.Black.copy(alpha = 0.55f),
                                            topLeft = Offset(0f, cy + radius),
                                            size    = androidx.compose.ui.geometry.Size(size.width, cy - radius),
                                        )
                                        drawRect(
                                            color   = Color.Black.copy(alpha = 0.55f),
                                            topLeft = Offset(0f, cy - radius),
                                            size    = androidx.compose.ui.geometry.Size(cx - radius, radius * 2),
                                        )
                                        drawRect(
                                            color   = Color.Black.copy(alpha = 0.55f),
                                            topLeft = Offset(cx + radius, cy - radius),
                                            size    = androidx.compose.ui.geometry.Size(cx - radius, radius * 2),
                                        )

                                        drawCircle(
                                            color  = Color.White.copy(alpha = 0.85f),
                                            radius = radius,
                                            center = Offset(cx, cy),
                                            style  = Stroke(width = 2.dp.toPx()),
                                        )
                                    }
                                    CropShape.FULL_SCREEN -> {
                                        // 裁剪框本身就等于整个可视容器，没有框外区域需要
                                        // 遮罩，也不需要画拱形/圆形边框——用户看到的整个
                                        // 预览区域即最终取景范围，保持画面干净即可。
                                    }
                                    CropShape.TALL_RECT -> {
                                        val halfW = cropHalfW
                                        val halfH = cropHalfH

                                        // 四片矩形遮住裁剪框外区域（上/下/左/右）
                                        drawRect(
                                            color   = Color.Black.copy(alpha = 0.55f),
                                            topLeft = Offset(0f, 0f),
                                            size    = androidx.compose.ui.geometry.Size(size.width, cy - halfH),
                                        )
                                        drawRect(
                                            color   = Color.Black.copy(alpha = 0.55f),
                                            topLeft = Offset(0f, cy + halfH),
                                            size    = androidx.compose.ui.geometry.Size(size.width, (size.height - (cy + halfH)).coerceAtLeast(0f)),
                                        )
                                        drawRect(
                                            color   = Color.Black.copy(alpha = 0.55f),
                                            topLeft = Offset(0f, cy - halfH),
                                            size    = androidx.compose.ui.geometry.Size(cx - halfW, halfH * 2),
                                        )
                                        drawRect(
                                            color   = Color.Black.copy(alpha = 0.55f),
                                            topLeft = Offset(cx + halfW, cy - halfH),
                                            size    = androidx.compose.ui.geometry.Size(cx - halfW, halfH * 2),
                                        )

                                        // 裁剪框边框——矩形+半圆拱形轮廓，跟公馆最终展示形状一致，
                                        // 而不是普通直角矩形，让用户预览时更接近真实效果。
                                        val archRadius = (halfW).coerceAtMost(halfH * 0.85f)
                                        val straightTopY = cy - halfH + archRadius
                                        val outline = androidx.compose.ui.graphics.Path().apply {
                                            moveTo(cx - halfW, cy + halfH)
                                            lineTo(cx - halfW, straightTopY)
                                            arcTo(
                                                rect = androidx.compose.ui.geometry.Rect(
                                                    left   = cx - halfW,
                                                    top    = straightTopY - archRadius,
                                                    right  = cx + halfW,
                                                    bottom = straightTopY + archRadius,
                                                ),
                                                startAngleDegrees = 180f,
                                                sweepAngleDegrees = 180f,
                                                forceMoveTo = false,
                                            )
                                            lineTo(cx + halfW, cy + halfH)
                                        }
                                        drawPath(
                                            path  = outline,
                                            color = Color.White.copy(alpha = 0.85f),
                                            style = Stroke(width = 2.dp.toPx()),
                                        )
                                    }
                                }
                            }
                    )
                }
                // 方案 5-1：图片加载中时显示加载指示器，告知用户裁剪区域尚未就绪
                if (!isImageReady) {
                    CircularProgressIndicator(
                        modifier         = Modifier.size(32.dp),
                        color            = Color.White.copy(alpha = 0.7f),
                        strokeWidth      = 2.dp,
                    )
                }

                Spacer(Modifier.height(24.dp))

                // ── 操作按钮 ──────────────────────────────────
                Row(
                    modifier            = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp),
                    verticalAlignment   = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick  = onDismiss,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            text  = "取消",
                            color = Color.White.copy(alpha = 0.70f),
                        )
                    }

                    Spacer(Modifier.width(16.dp))

                    Button(
                        onClick  = {
                            if (isConfirming) return@Button
                            isConfirming = true
                            when (shape) {
                                CropShape.CIRCLE -> {
                                    val r = cropRadius.coerceAtLeast(1f)
                                    onConfirm(
                                        CropParams(
                                            uri               = uri,
                                            normalizedOffsetX = offsetX / r,
                                            normalizedOffsetY = offsetY / r,
                                            scale             = scale,
                                            shape             = shape,
                                        )
                                    )
                                }
                                CropShape.TALL_RECT, CropShape.FULL_SCREEN -> {
                                    val hw = cropHalfW.coerceAtLeast(1f)
                                    val hh = cropHalfH.coerceAtLeast(1f)
                                    onConfirm(
                                        CropParams(
                                            uri               = uri,
                                            normalizedOffsetX = offsetX / hw,
                                            normalizedOffsetY = offsetY / hh,
                                            scale             = scale,
                                            shape             = shape,
                                        )
                                    )
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape    = RoundedCornerShape(12.dp),
                        colors   = ButtonDefaults.buttonColors(containerColor = accent),
                    ) {
                        Text("确认", color = Color.White)
                    }
                }

                Spacer(Modifier.height(32.dp))
            }
        }
    }
}
