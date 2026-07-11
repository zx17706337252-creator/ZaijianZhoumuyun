package com.zaijian.zhoumuyun.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zaijian.zhoumuyun.data.model.CharacterConfig
import com.zaijian.zhoumuyun.data.model.DefaultCharacters
import com.zaijian.zhoumuyun.data.model.StatusType
import com.zaijian.zhoumuyun.ui.theme.Spacing
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme

// ─────────────────────────────────────────────────────────────
//  CharacterPickerSheet — 通用九角色选择浮层
//
//  2.3/2.4/3.4 修复：「成长 Tab / 任务中心目标按钮」和「学习目标深链
//  无参数时的默认值」此前硬编码 characterId = 1，本质是这两个入口
//  站在「非角色语境」页面（任务中心是跨角色的汇总页），没有一个
//  可用的"当前角色"可以代入。与其继续假装角色 1 是合理默认值，不如
//  让用户自己选——这个浮层就是给这类"目标页需要一个角色但上下文没有
//  提供"的入口复用的选择器。
//
//  九个主角色是固定常量（DefaultCharacters），不涉及家族链查询，
//  比 FamilyPickerSheet 简单，直接铺开展示，不做分页/搜索。
// ─────────────────────────────────────────────────────────────

/**
 * 通用角色选择浮层。
 *
 * @param onDismiss         关闭浮层（点外部/选完后调用）
 * @param onSelectCharacter 选中某角色后回调，调用方据此跳转目标页
 * @param title             浮层标题，默认"选择角色"
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharacterPickerSheet(
    onDismiss: () -> Unit,
    onSelectCharacter: (characterId: Int) -> Unit,
    title: String = "选择角色",
    characters: List<CharacterConfig> = DefaultCharacters,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState,
        containerColor   = colors.bgCard,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = Spacing.xl),
        ) {
            Text(
                text     = title,
                style    = type.cardTitle,
                color    = colors.textPrimary,
                modifier = Modifier.padding(
                    horizontal = Spacing.screenHorizontal,
                    vertical   = Spacing.md,
                ),
            )
            characters.forEach { character ->
                CharacterPickerRow(
                    character = character,
                    onClick = {
                        onSelectCharacter(character.id)
                        onDismiss()
                    },
                )
            }
        }
    }
}

@Composable
private fun CharacterPickerRow(
    character: CharacterConfig,
    onClick: () -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.screenHorizontal, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(width = 1.dp, color = character.accentColor.copy(alpha = 0.5f), shape = RoundedCornerShape(12.dp))
            .background(colors.bgCard)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BreathingAvatar(
            imageUrl    = character.avatarUrl,
            breathColor = character.accentColor,
            statusType  = StatusType.ACTIVE,
            size        = 48.dp,
            enableBreath = false,
        )
        Spacer(modifier = Modifier.width(Spacing.md))
        Text(
            text     = character.nickname ?: character.name,
            style    = type.body,
            color    = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
