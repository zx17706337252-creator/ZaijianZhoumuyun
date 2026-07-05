package com.zaijian.zhoumuyun.ui.component

import android.os.Build
import androidx.compose.animation.core.InfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import com.zaijian.zhoumuyun.data.model.StatusType
import com.zaijian.zhoumuyun.data.model.dotColor
import com.zaijian.zhoumuyun.ui.theme.BreathGlowAlphaMax
import com.zaijian.zhoumuyun.ui.theme.BreathGlowAlphaMin
import com.zaijian.zhoumuyun.ui.theme.BreathScaleMax
import com.zaijian.zhoumuyun.ui.theme.BreathScaleMin
import com.zaijian.zhoumuyun.ui.theme.breathAlphaSpec
import com.zaijian.zhoumuyun.ui.theme.breathScaleSpec

// ─────────────────────────────────────────────────────────────
//  BreathingAvatar
//  设计规范 §7 + §8 — 呼吸动画 + 头像层级
//
//  P-14 修复：
//  · enableBreath=false 时完全不创建 InfiniteTransition（零 Choreographer 开销）。
//    方案：公开函数通过 if 分发给两个私有 Composable，满足 Hooks 无条件调用规则。
//  · 光晕 Box 仅在 glowAlpha > 0.01f 时入 Compose 树（避免 blur() 空跑耗 GPU）。
//  · 新增 sharedTransition 参数：调用方（WindowCard）可传入已有的 InfiniteTransition，
//    BreathingAvatar 使用共享 transition 而不再新建，减少 9 卡并发的动画器数量。
// ─────────────────────────────────────────────────────────────

/**
 * 呼吸头像。
 *
 * @param imageUrl         头像图片 URL 或占位符
 * @param breathColor      呼吸光颜色（通常 = character.accentColor）
 * @param statusType       控制是否显示状态环和状态点
 * @param statusRingColor  状态环颜色（默认从 statusType 派生）
 * @param size             头像直径（正方形场景，如聊天页/角色详情页的圆形头像）。
 *                         当 [width]/[height] 非 null 时此参数被忽略。
 * @param width            头像宽度（矩形/非正方形容器场景，如公馆拱形、书架椭圆）。
 *                         必须与 [height] 同时提供，二者非 null 时才生效。
 * @param height           头像高度，配合 [width] 使用，见上。
 * @param ringWidth        状态环线宽
 * @param glowRadius       光晕模糊半径
 * @param enableBreath     是否启用呼吸动画（OFFLINE 角色传 false）
 * @param sharedTransition 调用方已创建的 InfiniteTransition（可选）。
 *                         非 null 时 BreathingAvatar 使用它而不自行创建，
 *                         供 WindowCard 等父 Composable 做跨组件动画合并。
 *                         enableBreath=false 时此参数无效。
 *
 * 布局修复（v49_p18）：原先非圆形调用点（WindowCard 拱形 / BookCard 椭圆）
 * 把 `size = maxOf(avatarW, avatarH)` 传进来，强制内部 Box 变成正方形，
 * 再被外层 `.size(avatarW, avatarH).clip(shape)` 的矩形硬约束居中裁切——
 * 正方形被压扁成矩形的过程和 `AsyncImage.fillMaxSize()+Crop` 的缩放时机
 * 没有对齐，实际观察到的现象就是头像图片只占很小一块、且贴着一圈残留的
 * 圆形描边。现在新增 [width]/[height] 双参数重载：非 null 时内部按精确
 * 矩形测量（不再强制正方形），[size] 仅保留给圆形场景。
 */
@Composable
fun BreathingAvatar(
    imageUrl: String,
    breathColor: Color,
    statusType: StatusType,
    modifier: Modifier = Modifier,
    statusRingColor: Color = breathColor.copy(alpha = 0.60f),
    size: Dp = 52.dp,
    width: Dp? = null,
    height: Dp? = null,
    ringWidth: Dp = 2.5.dp,
    glowRadius: Dp = 8.dp,
    enableBreath: Boolean = true,
    sharedTransition: InfiniteTransition? = null,
    // 布局修复：原先内部图片写死 CircleShape 裁剪，导致 WindowCard(拱形)/
    // BookCard(椭圆) 外层的自定义裁剪形状形同虚设——圆形完整嵌在拱形/椭圆
    // 内部，视觉上只看得到圆。改为可由调用方指定目标形状，默认仍是
    // CircleShape，兼容其余 8 处不需要自定义形状的调用点。
    shape: Shape = CircleShape,
    // v46 头像重新设计新增：原图裁剪参数（对应 CharacterIdentityEntity 里
    // avatarCropCircle* / avatarCropTall* 两套字段之一，由调用方按场景
    // 传对应的一套）。语义与旧 AvatarCropDialog 一致：
    //   cropOffsetX/Y：图片中心相对容器中心的偏移，单位为容器短边一半的比例
    //   cropScale：缩放倍数，1f = 图片刚好覆盖容器（ContentScale.Crop 的
    //     默认效果），越大图片放得越大（能看到的画面范围越小）
    // 默认 0f/0f/1f 与不传时行为完全一致（居中、Crop 覆盖，不额外处理），
    // 因此其余不需要自定义裁剪的调用点不受影响。
    cropOffsetX: Float = 0f,
    cropOffsetY: Float = 0f,
    cropScale: Float = 1f,
) {
    val boxWidth  = width ?: size
    val boxHeight = height ?: size
    // 非圆形（拱形/椭圆）场景下，270° 弧形状态环叠在矩形/椭圆容器上会
    // 呈现出一圈突兀的"多余圆环"（详见 StatusRingCanvas 顶部说明）。
    // 只在 shape === CircleShape（真正的圆形头像场景，如聊天页/详情页）
    // 时绘制状态环；非圆形场景只保留右下角状态点，不画环。
    val showRing = shape === CircleShape

    // P-14 修复：通过 if 分发给两个私有 Composable，
    // 确保 enableBreath=false 时完全不触碰任何 InfiniteTransition。
    if (enableBreath) {
        BreathingAvatarAnimated(
            imageUrl          = imageUrl,
            breathColor       = breathColor,
            statusType        = statusType,
            modifier          = modifier,
            statusRingColor   = statusRingColor,
            boxWidth          = boxWidth,
            boxHeight         = boxHeight,
            ringWidth         = ringWidth,
            glowRadius        = glowRadius,
            sharedTransition  = sharedTransition,
            shape             = shape,
            showRing          = showRing,
            cropOffsetX       = cropOffsetX,
            cropOffsetY       = cropOffsetY,
            cropScale         = cropScale,
        )
    } else {
        BreathingAvatarStatic(
            imageUrl        = imageUrl,
            breathColor     = breathColor,
            statusType      = statusType,
            modifier        = modifier,
            statusRingColor = statusRingColor,
            boxWidth        = boxWidth,
            boxHeight       = boxHeight,
            ringWidth       = ringWidth,
            shape           = shape,
            showRing        = showRing,
            cropOffsetX     = cropOffsetX,
            cropOffsetY     = cropOffsetY,
            cropScale       = cropScale,
        )
    }
}

/**
 * 有呼吸动画版本：使用 sharedTransition（若非 null）或自建 transition。
 * 光晕 Box 条件入树。仅在 enableBreath=true 时被调用。
 */
@Composable
private fun BreathingAvatarAnimated(
    imageUrl: String,
    breathColor: Color,
    statusType: StatusType,
    modifier: Modifier,
    statusRingColor: Color,
    boxWidth: Dp,
    boxHeight: Dp,
    ringWidth: Dp,
    glowRadius: Dp,
    sharedTransition: InfiniteTransition?,
    shape: Shape,
    showRing: Boolean,
    cropOffsetX: Float,
    cropOffsetY: Float,
    cropScale: Float,
) {
    // 优先使用调用方传入的共享 transition，避免重复创建动画器
    val transition = sharedTransition ?: rememberInfiniteTransition(label = "avatar_breath")

    val scale by transition.animateFloat(
        initialValue  = BreathScaleMin,
        targetValue   = BreathScaleMax,
        animationSpec = breathScaleSpec,
        label         = "breath_scale",
    )

    val glowAlpha by transition.animateFloat(
        initialValue  = BreathGlowAlphaMin,
        targetValue   = BreathGlowAlphaMax,
        animationSpec = breathAlphaSpec,
        label         = "glow_alpha",
    )

    AvatarContent(
        imageUrl        = imageUrl,
        breathColor     = breathColor,
        statusType      = statusType,
        modifier        = modifier,
        statusRingColor = statusRingColor,
        boxWidth        = boxWidth,
        boxHeight       = boxHeight,
        ringWidth       = ringWidth,
        glowRadius      = glowRadius,
        scale           = scale,
        glowAlpha       = glowAlpha,
        shape           = shape,
        showRing        = showRing,
        cropOffsetX     = cropOffsetX,
        cropOffsetY     = cropOffsetY,
        cropScale       = cropScale,
    )
}

/**
 * 静态（无动画）版本：不创建任何 InfiniteTransition，零 Choreographer 开销。
 * 仅在 enableBreath=false 时被调用（OFFLINE 角色）。
 */
@Composable
private fun BreathingAvatarStatic(
    imageUrl: String,
    breathColor: Color,
    statusType: StatusType,
    modifier: Modifier,
    statusRingColor: Color,
    boxWidth: Dp,
    boxHeight: Dp,
    ringWidth: Dp,
    shape: Shape,
    showRing: Boolean,
    cropOffsetX: Float,
    cropOffsetY: Float,
    cropScale: Float,
) {
    AvatarContent(
        imageUrl        = imageUrl,
        breathColor     = breathColor,
        statusType      = statusType,
        modifier        = modifier,
        statusRingColor = statusRingColor,
        boxWidth        = boxWidth,
        boxHeight       = boxHeight,
        ringWidth       = ringWidth,
        glowRadius      = 0.dp,
        scale           = 1f,
        glowAlpha       = 0f,   // 恒零 → 光晕 Box 不入树
        shape           = shape,
        showRing        = showRing,
        cropOffsetX     = cropOffsetX,
        cropOffsetY     = cropOffsetY,
        cropScale       = cropScale,
    )
}

/**
 * 实际渲染逻辑：接收已计算的 scale / glowAlpha，不读取任何动画状态。
 * 光晕 Box 仅在 glowAlpha > 0.01f 时入 Compose 树（P-14 修复）。
 */
@Composable
private fun AvatarContent(
    imageUrl: String,
    breathColor: Color,
    statusType: StatusType,
    modifier: Modifier,
    statusRingColor: Color,
    boxWidth: Dp,
    boxHeight: Dp,
    ringWidth: Dp,
    glowRadius: Dp,
    scale: Float,
    glowAlpha: Float,
    shape: Shape,
    showRing: Boolean,
    cropOffsetX: Float,
    cropOffsetY: Float,
    cropScale: Float,
) {
    Box(
        modifier = modifier
            .size(boxWidth, boxHeight)
            .scale(scale),
        contentAlignment = Alignment.Center,
    ) {
        // [2] 情绪光晕 — 仅在 glowAlpha > 0.01f 时入树，避免 blur() 空跑耗 GPU
        if (glowAlpha > 0.01f) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .scale(1.35f)
                        .blur(glowRadius)
                        .background(breathColor.copy(alpha = glowAlpha), CircleShape)
                )
            } else {
                // Fallback: larger, softer circle without hardware blur
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .scale(1.50f)
                        .background(breathColor.copy(alpha = glowAlpha * 0.5f), CircleShape)
                )
            }
        }

        // [1] 头像图片
        // v46 头像重新设计：imageUrl 现在指向「原图」而非固定裁剪成品图，
        // 不同容器（圆形详情页 / 拱形公馆 / 椭圆书架）各自传入自己那套
        // cropOffsetX/Y + cropScale，让同一张原图能在不同形状容器里各自
        // 取景，而不再是「裁一张成品图，哪里都硬塞」。
        //
        // v47 曾经踩过的坑：graphicsLayer 只做 translation、不做 scale 时，
        // 「已经贴合容器边界」的图层被整体平移，挪开的那一侧会露出容器
        // 背景色——因为图层本身大小没变，只是位置变了，边界移出了
        // clip(shape) 的裁剪窗口之外，裁剪窗口里空出来的部分自然没有
        // 图片内容。
        //
        // v50 修复：这次 translation 之前先做 scaleX/scaleY = cropScale
        // 放大（cropScale 取值范围本身就 >= 1，含义是「放大倍数」），
        // 图层边界经过放大后只会比容器边界更大、不会更小，之后再叠加
        // 平移，图层依然能整个盖住裁剪窗口，不会露出背景色——v47 的坑
        // 是「只平移不放大」，这次是「先放大到足够大再平移」，两者看似
        // 都用 graphicsLayer，实际约束条件不同，不会重蹈覆辙。
        //
        // 另外这次没有再重复 v49_p18 那次「手动接管 layout 测量阶段」的
        // 弯路：AsyncImage 本身仍然走 .fillMaxSize() + ContentScale.Crop
        // 的标准路径，Coil 能按标准约束协商正确加载和铺满图片，
        // graphicsLayer 只是在图片已经铺满之后，在绘制阶段做「用户自定义
        // 的二次缩放和平移」，两者职责不冲突。
        //
        // cropScale：1f = 图片刚好覆盖容器（Crop 默认取景）；越大，能看到
        // 的画面范围越小（放大居中）。
        // cropOffsetX/Y：即 AvatarCropDialog 保存出来的 normalizedOffsetX/Y，
        // 定义是「图片中心相对裁剪框中心的归一化平移量」，范围约 -1..1。
        // v50 改用 graphicsLayer 后，换算成实际像素平移时乘 size.width/2
        // 或 size.height/2（size 是 graphicsLayer 绘制时的图层尺寸，此时
        // 已经等于 fillMaxSize() 之后的容器尺寸），语义与旧实现一致。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(shape)
                .background(breathColor.copy(alpha = 0.15f)),
        ) {
            // v50 修复：彻底放弃「手动接管 layout 测量阶段、用
            // Constraints.fixed() 指定放大后像素尺寸」这条路径。这条路径
            // 反复调试多版仍然复现「图片内容比外层容器小很多」的问题——
            // 根因是 Coil 的 AsyncImagePainter 内部用
            // rememberConstraintsSizeResolver 之类的机制，按「measure 阶段
            // 收到的约束」去决定实际请求/解码的图片分辨率和绘制尺寸；
            // 一旦这里改用 Constraints.fixed(scaledWidth, scaledHeight) 抢
            // 在标准约束协商之前把测量结果锁死，Coil 内部对
            // ContentScale.Crop 的缩放计算就不再是「铺满 fillMaxSize 之后
            // 的容器」，而是按这个被我们提前放大的固定尺寸做二次解读，两边
            // 对不上，表现为图片整体只占外层容器一小块。
            //
            // 新写法：让 AsyncImage 走 Coil 官方推荐的标准路径——
            // .fillMaxSize() + contentScale = ContentScale.Crop，不触碰
            // measure 阶段，图片一定会先正确铺满整个容器（跟离线纯色块一样
            // 大）。用户拖拽产生的 cropOffsetX/Y + cropScale，改用
            // graphicsLayer 在绘制阶段叠加「已经铺满容器的图层」之上做
            // 缩放和平移——因为图层此时的物理边界已经等于容器边界，
            // graphicsLayer 的 scale/translation 只是把这个「本来就够大」
            // 的图层进一步放大再平移，外层 clip(shape) 兜底裁掉溢出部分，
            // 不会出现「移开后露出背景色」的问题（那是旧实现在图层本身
            // 偏小时平移才会露底，这里图层起始尺寸就是满的，同样的平移量
            // 不会露底）。
            AsyncImage(
                model              = imageUrl,
                contentDescription = null,
                modifier           = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX       = cropScale
                        scaleY       = cropScale
                        translationX = cropOffsetX * (size.width / 2f)
                        translationY = cropOffsetY * (size.height / 2f)
                    },
                contentScale       = ContentScale.Crop,
                error              = rememberVectorPainter(Icons.Outlined.Person),
            )
        }

        // [3+4] 状态环 + 状态点
        // 布局修复：StatusRingCanvas 用 drawArc 画一个跟随容器宽高的
        // 270° 椭圆弧——在真正的圆形容器（聊天页/详情页头像）里这是
        // 预期的"状态指示环"，但在拱形（公馆）/椭圆（书架）容器里，
        // 这圈弧线会贴着容器四边走出一圈跟角色照片本身无关、颜色突兀
        // 的"多余圆环"，正是用户反馈"头像上盖着一个小圆环"的元凶。
        // 只在 showRing（即 shape === CircleShape）时绘制完整状态环；
        // 非圆形场景只保留 StatusRingCanvas 内部右下角的状态点。
        if (statusType != StatusType.OFFLINE) {
            if (showRing) {
                StatusRingCanvas(
                    statusType  = statusType,
                    ringColor   = statusRingColor,
                    ringWidth   = ringWidth,
                    modifier    = Modifier.fillMaxSize(),
                )
            } else {
                StatusDotOnly(
                    statusType = statusType,
                    modifier   = Modifier.fillMaxSize(),
                )
            }
        }

        // OFFLINE：灰度遮罩 70% 不透明度 (设计规范 §10 窗口发光规则)
        if (statusType == StatusType.OFFLINE) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .background(Color(0x80808080))  // 50% grey overlay
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  StatusDotOnly  — 仅绘制右下角状态点，不画 270° 弧形环
//  用于拱形（公馆 WindowCard）/ 椭圆（书架 BookCard）等非圆形头像
//  容器：这些容器的宽高比不是 1:1，StatusRingCanvas 的椭圆弧会贴着
//  容器四边呈现出一圈与角色照片无关的"多余圆环"，故非圆形场景改用
//  这个只画状态点、不画环的轻量版本。
// ─────────────────────────────────────────────────────────────

@Composable
fun StatusDotOnly(
    statusType: StatusType,
    modifier: Modifier = Modifier,
) {
    val dotColor = statusType.dotColor()
    Canvas(modifier = modifier) {
        val dotRadius = 5.dp.toPx()
        val dotCenter = Offset(
            x = size.width  - dotRadius,
            y = size.height - dotRadius,
        )
        drawCircle(color = Color.White, radius = dotRadius + 1.5f, center = dotCenter)
        drawCircle(color = dotColor,    radius = dotRadius,         center = dotCenter)
    }
}

// ─────────────────────────────────────────────────────────────
//  StatusRingCanvas  — 270° 状态环 + 右下角状态点
//  设计规范 §8
// ─────────────────────────────────────────────────────────────

@Composable
fun StatusRingCanvas(
    statusType: StatusType,
    ringColor: Color,
    ringWidth: Dp,
    modifier: Modifier = Modifier,
) {
    val dotColor = statusType.dotColor()

    Canvas(modifier = modifier) {
        val stroke = ringWidth.toPx()
        val inset  = stroke / 2f

        // 270° 弧，缺口在右下角（放状态点）
        drawArc(
            color      = ringColor,
            startAngle = -225f,
            sweepAngle = 270f,
            useCenter  = false,
            style      = Stroke(width = stroke, cap = StrokeCap.Round),
            topLeft    = Offset(inset, inset),
            size       = androidx.compose.ui.geometry.Size(
                size.width - stroke,
                size.height - stroke,
            ),
        )

        // 状态点：右下角缺口处，直径约 10dp
        val dotRadius = 5.dp.toPx()
        val dotCenter = Offset(
            x = size.width  - dotRadius,
            y = size.height - dotRadius,
        )
        // 白色底衬（防止状态点与光晕混淆）
        drawCircle(color = Color.White, radius = dotRadius + 1.5f, center = dotCenter)
        drawCircle(color = dotColor,    radius = dotRadius,         center = dotCenter)
    }
}
