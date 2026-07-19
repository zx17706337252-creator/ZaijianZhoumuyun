package com.zaijian.zhoumuyun.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.zaijian.zhoumuyun.ui.theme.Radius
import com.zaijian.zhoumuyun.ui.theme.Spacing
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme

// ─────────────────────────────────────────────────────────────
//  SaveTestRow — "保存 + 测试连接" 双按钮行
//
//  窗口16审计【问题E2】修复：ProfileAiConfigSection（AI 提供商配置）与
//  ProfileIntegrationsSection（GitHub / 邮箱集成配置）此前各自内联/私有
//  实现了几乎一字不差的"保存 + 测试连接"按钮对，抽取为共享组件。
//
//  两个调用方各自有不同的测试状态 sealed class（TestState / ConnTestState），
//  这里不强行统一状态类型，只依赖一个 isTesting: Boolean 表达"测试进行中"
//  这一唯一影响 UI 的维度（按钮禁用 + 图标换成 loading spinner），
//  具体状态机与保存/测试的业务逻辑仍由各自调用方通过 onSave/onTest 处理。
// ─────────────────────────────────────────────────────────────
@Composable
internal fun SaveTestRow(
    isTesting: Boolean,
    onSave: () -> Unit,
    onTest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        // 保存按钮
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(Radius.sm))
                .background(colors.bgElevated)
                .clickable(enabled = !isTesting) { onSave() }
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "保存", style = type.body, color = colors.textPrimary)
        }

        // 测试连接按钮
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(Radius.sm))
                .background(colors.accent)
                .clickable(enabled = !isTesting) { onTest() }
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (isTesting) {
                CircularProgressIndicator(
                    modifier    = Modifier.size(18.dp),
                    color       = Color.White,
                    strokeWidth = 2.dp,
                )
            } else {
                Text(text = "测试连接", style = type.body, color = Color.White)
            }
        }
    }
}
