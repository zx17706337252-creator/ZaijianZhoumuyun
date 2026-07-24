package com.zaijian.zhoumuyun.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zaijian.zhoumuyun.ui.theme.Spacing
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme

// ═══════════════════════════════════════════════════════════════
//  LoadingIndicator — 统一加载态组件（窗口3报告 4.2 节）
//
//  替代项目中36处裸 CircularProgressIndicator 调用。
//  底层复用现有 CircularProgressIndicator，统一封装尺寸、颜色、遮罩层。
//
//  替换节奏：21页面重做窗口改到哪个页面，就顺手把该页面内的裸调用替换掉，
//  不单独立项跑一遍全项目替换（与 AppIcons 收拢采用同一节奏）。
// ═══════════════════════════════════════════════════════════════

/**
 * 加载指示器样式
 */
@Suppress("unused")
enum class LoadingStyle {
    /** 列表内联小尺寸 */
    INLINE,

    /** 整页覆盖（含半透明遮罩层） */
    FULL_SCREEN,
}

/**
 * 统一加载态组件
 *
 * @param style   INLINE（列表内联小尺寸）/ FULL_SCREEN（整页覆盖）
 * @param modifier 布局修饰符
 */
// 死代码-07（阶段2·批次1）：当前全项目零调用方，属"待接入组件"——
// 等待后续页面改版时逐步替换裸 CircularProgressIndicator 调用（见上方
// 类注释"替换节奏"），非遗留废弃代码，故不删除，加 @Suppress 消除告警。
@Suppress("unused")
@Composable
fun LoadingIndicator(
    style: LoadingStyle = LoadingStyle.INLINE,
    modifier: Modifier = Modifier,
) {
    val colors = ZaijianTheme.colors

    when (style) {
        LoadingStyle.INLINE -> {
            Box(
                modifier = modifier.padding(vertical = Spacing.md),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    color = colors.accent,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(24.dp),
                )
            }
        }

        LoadingStyle.FULL_SCREEN -> {
            Box(
                modifier = modifier
                    .fillMaxSize()
                    .background(colors.bgBase.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    color = colors.accent,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(36.dp),
                )
            }
        }
    }
}
