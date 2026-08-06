package com.zaijian.zhoumuyun.ui.screen.characterdetail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.zaijian.zhoumuyun.ui.design.AppIcons
import com.zaijian.zhoumuyun.ui.design.WorldCard
import com.zaijian.zhoumuyun.ui.theme.Palette
import com.zaijian.zhoumuyun.ui.theme.Radius
import com.zaijian.zhoumuyun.ui.theme.Spacing
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme

// ─────────────────────────────────────────────────────────────
//  S-8：ListEditSection — 从 CharacterDetailRelationship.kt 提取
//  为 CharacterDetailIdentity.kt 和 CharacterDetailRelationship.kt
//  共享的通用可编辑列表组件。
// ─────────────────────────────────────────────────────────────

@Composable
internal fun ListEditSection(
    title: String,
    hint: String,
    items: List<String>,
    accentColor: Color,
    onAdd: (String) -> Unit,
    onRemove: (Int) -> Unit,
    onUpdate: (Int, String) -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography
    var newText by remember { mutableStateOf("") }

    WorldCard(modifier = Modifier.fillMaxWidth(), cornerRadius = Radius.sm) {
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(Spacing.cardPadding),
        ) {
            Text(text = title, style = type.label, color = colors.textSecondary)

            // 已有条目
            items.forEachIndexed { index, item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Radius.sm))
                        .background(if (colors.isDark) colors.bgElevated else colors.bgCard)
                        .border(0.5.dp, colors.border, RoundedCornerShape(Radius.sm))
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(accentColor.copy(alpha = 0.6f))
                    )
                    Spacer(Modifier.width(8.dp))
                    BasicTextField(
                        value         = item,
                        onValueChange = { onUpdate(index, it) },
                        textStyle     = type.body.copy(color = colors.textPrimary),
                        cursorBrush   = SolidColor(accentColor),
                        modifier      = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick  = { onRemove(index) },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector        = AppIcons.Add,
                            contentDescription = "删除",
                            tint               = colors.textDisabled,
                            modifier           = Modifier
                                .size(16.dp)
                                .graphicsLayer { rotationZ = 45f },
                        )
                    }
                }
            }

            // 新增输入行
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.sm))
                    .background(if (colors.isDark) colors.bgElevated else colors.bgCard)
                    .border(0.5.dp, accentColor.copy(alpha = 0.35f), RoundedCornerShape(Radius.sm))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicTextField(
                    value         = newText,
                    onValueChange = { newText = it },
                    textStyle     = type.body.copy(color = colors.textPrimary),
                    cursorBrush   = SolidColor(accentColor),
                    decorationBox = { inner ->
                        if (newText.isEmpty()) {
                            Text(text = hint, style = type.body, color = colors.textDisabled)
                        }
                        inner()
                    },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (newText.isNotBlank()) accentColor else colors.border)
                        .clickable {
                            if (newText.isNotBlank()) {
                                onAdd(newText)
                                newText = ""
                            }
                        }
                        .padding(4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector        = AppIcons.Add,
                        contentDescription = "添加",
                        tint               = Palette.White,
                        modifier           = Modifier.size(14.dp),
                    )
                }
            }
        }
    }
}