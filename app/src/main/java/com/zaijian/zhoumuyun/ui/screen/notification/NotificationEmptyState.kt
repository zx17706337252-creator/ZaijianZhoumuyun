package com.zaijian.zhoumuyun.ui.screen.notification

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.zaijian.zhoumuyun.ui.design.WorldCard
import com.zaijian.zhoumuyun.ui.theme.Radius
import com.zaijian.zhoumuyun.ui.theme.Spacing
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme

// ─────────────────────────────────────────────────────────────
//  NotificationEmptyState — 通知中心空态占位
//
//  背景：NotificationAttentionSection 在 items 为空时仍渲染"需要关注"
//  标题卡片（forEach 不循环，卡片本身照样画出来），NotificationGoodNewsSection
//  则相反——直接 return，连标题都不显示。两种呈现方式互相矛盾，且都没有
//  真正告诉用户"这是正常的、公馆目前一切安好"，容易被误读成没做完。
//
//  统一处理：两个 section 在空数据时都保留标题（视觉结构不塌陷），
//  标题下方换成这个占位行，而不是干瘪的空白或者干脆消失。
//  文案与图标按 section 区分语气，调用方传入。
// ─────────────────────────────────────────────────────────────

@Composable
fun NotificationEmptyState(
    text: String,
    modifier: Modifier = Modifier,
) {
    val colors = ZaijianTheme.colors
    val type = ZaijianTheme.typography

    WorldCard(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = Radius.sm,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(Spacing.cardPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = text,
                style = type.caption,
                color = colors.textDisabled,
                textAlign = TextAlign.Center,
            )
        }
    }
}
