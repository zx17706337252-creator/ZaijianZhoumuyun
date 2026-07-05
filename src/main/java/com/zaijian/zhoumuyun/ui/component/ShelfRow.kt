package com.zaijian.zhoumuyun.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.zaijian.zhoumuyun.data.model.CharacterConfig
import com.zaijian.zhoumuyun.data.model.DefaultCharacters
import com.zaijian.zhoumuyun.data.model.DefaultPresenceStates
import com.zaijian.zhoumuyun.data.model.PresenceState
import com.zaijian.zhoumuyun.data.model.StatusType
import com.zaijian.zhoumuyun.ui.theme.AppTheme
import com.zaijian.zhoumuyun.ui.theme.Spacing
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme

// ─────────────────────────────────────────────────────────────
//  ShelfRow  — 书架的一行（3 本书）
//  设计规范 §11
//
//  总是渲染 3 个槽（shelfCol 1/2/3），空缺时插入 Spacer 占位。
//  单行宽度 = 屏幕宽 - screenHorizontal × 2
//  每本书宽 = (总宽 - gap × 2) ÷ 3
// ─────────────────────────────────────────────────────────────

@Composable
fun ShelfRow(
    /** 当前行的所有角色（已按 shelfRow 过滤，shelfCol = 1/2/3） */
    characters: List<CharacterConfig>,
    presenceMap: Map<Int, PresenceState>,
    onBookClick: (characterId: Int) -> Unit,
    onBookLongClick: (characterId: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier              = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment     = Alignment.Bottom,
    ) {
        // 三列固定槽位：col 1 / 2 / 3
        (1..3).forEach { col ->
            val character = characters.find { it.shelfCol == col }

            if (character != null) {
                val presence = presenceMap[character.id] ?: PresenceState(
                    characterId = character.id,
                    statusText  = "—",
                    statusType  = StatusType.OFFLINE,
                    lastUpdated = 0L,
                )
                BookCard(
                    character   = character,
                    presence    = presence,
                    onClick     = { onBookClick(character.id) },
                    onLongClick = { onBookLongClick(character.id) },
                    modifier    = Modifier.weight(1f),
                )
            } else {
                // 空槽（理论上不存在，安全保护）
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  Preview
// ─────────────────────────────────────────────────────────────

@Preview(name = "ShelfRow · Row 1 · Dark", showBackground = true,
    backgroundColor = 0xFF1A160F.toLong(), widthDp = 390, heightDp = 180)
@Composable
private fun PreviewShelfRowDark() {
    ZaijianTheme(appTheme = AppTheme.DARK) {
        ShelfRow(
            characters  = DefaultCharacters.filter { it.shelfRow == 1 },
            presenceMap = DefaultPresenceStates.associateBy { it.characterId },
            onBookClick     = {},
            onBookLongClick = {},
        )
    }
}

@Preview(name = "ShelfRow · Row 1 · Light", showBackground = true, widthDp = 390, heightDp = 180)
@Composable
private fun PreviewShelfRowLight() {
    ZaijianTheme(appTheme = AppTheme.LIGHT) {
        ShelfRow(
            characters  = DefaultCharacters.filter { it.shelfRow == 1 },
            presenceMap = DefaultPresenceStates.associateBy { it.characterId },
            onBookClick     = {},
            onBookLongClick = {},
        )
    }
}
