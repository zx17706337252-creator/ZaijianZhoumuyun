package com.zaijian.zhoumuyun.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zaijian.zhoumuyun.data.model.CharacterConfig
import com.zaijian.zhoumuyun.data.model.PresenceState
import com.zaijian.zhoumuyun.data.model.StatusType
import com.zaijian.zhoumuyun.data.model.dotColor
import com.zaijian.zhoumuyun.data.model.labelChinese
import com.zaijian.zhoumuyun.ui.theme.AvatarSize
import com.zaijian.zhoumuyun.ui.theme.Radius
import com.zaijian.zhoumuyun.ui.theme.RingWidth
import com.zaijian.zhoumuyun.ui.theme.Spacing
import com.zaijian.zhoumuyun.ui.theme.Palette
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme
import com.zaijian.zhoumuyun.ui.viewmodel.CharacterPreviewViewModel
import kotlinx.coroutines.launch

// ─────────────────────────────────────────────────────────────
//  CharacterPreviewSheet  — 长按窗口弹出的角色预览底部弹窗
//  设计规范 §12 连接逻辑
// ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharacterPreviewSheet(
    character: CharacterConfig,
    presence: PresenceState,
    onDismiss: () -> Unit,
    onStartChat: (Int) -> Unit,
    onViewProfile: (Int) -> Unit,
    onViewFamily: (Int) -> Unit = {},
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography
    val scope  = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Phase 16：加载最近 2 条 PERSONAL 记忆
    // Phase 4（4.2）修复：原先在此处直接 LaunchedEffect { AppDatabase.getInstance(...) }
    // 是架构违规（Composable 直连数据库），改为注入 CharacterPreviewViewModel，
    // 数据库访问收敛到 ViewModel 层，Composable 只负责 collectAsState() 订阅展示。
    val context = LocalContext.current
    val previewViewModel: CharacterPreviewViewModel = viewModel()
    val recentMemories by previewViewModel.recentMemories.collectAsState()
    LaunchedEffect(character.id) {
        previewViewModel.loadForCharacter(character.id)
    }

    ModalBottomSheet(
        onDismissRequest   = onDismiss,
        sheetState         = sheetState,
        containerColor     = colors.bgCard,
        shape              = RoundedCornerShape(topStart = Radius.lg, topEnd = Radius.lg),
        dragHandle         = {
            Box(
                modifier = Modifier
                    .padding(top = Spacing.sm)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(colors.border)
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.screenHorizontal)
                .navigationBarsPadding()
                .padding(bottom = Spacing.lg),
        ) {
            Spacer(Modifier.height(Spacing.lg))

            // ── 头像 + 基本信息 ────────────────────────────────
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                BreathingAvatar(
                    imageUrl     = character.avatarUrl,
                    breathColor  = character.accentColor,
                    statusType   = presence.statusType,
                    size         = AvatarSize.shelf,
                    ringWidth    = RingWidth.shelf,
                    enableBreath = presence.statusType == StatusType.ACTIVE,
                )

                Column {
                    Text(
                        text  = character.name,
                        style = type.cardTitle,
                        color = colors.textPrimary,
                    )
                    Row(
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(presence.statusType.dotColor())
                        )
                        Text(
                            text  = if (presence.statusType != StatusType.OFFLINE)
                                "${presence.statusType.labelChinese()} · ${presence.statusText}"
                            else
                                "暂未解锁",
                            style = type.caption,
                            color = if (colors.isDark) Palette.Gold.copy(alpha = 0.75f) else Palette.Gold.copy(alpha = 0.85f),
                        )
                    }
                }
            }

            Spacer(Modifier.height(Spacing.lg))

            // ── 最近记忆（Phase 16：接入真实数据）──────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.sm))
                    .background(
                        if (colors.isDark)
                            character.accentColor.copy(alpha = 0.08f)
                        else
                            character.accentColor.copy(alpha = 0.06f)
                    )
                    .padding(Spacing.md),
            ) {
                Column {
                    Text(
                        text  = "最近记忆",
                        style = type.label,
                        color = colors.textSecondary,
                    )
                    Spacer(Modifier.height(4.dp))
                    if (recentMemories.isEmpty()) {
                        Text(
                            text  = "还没有记忆，去聊聊吧",
                            style = type.caption.copy(fontStyle = FontStyle.Italic),
                            color = colors.textDisabled,
                        )
                    } else {
                        recentMemories.forEach { mem ->
                            Text(
                                text     = "· $mem",
                                style    = type.caption,
                                color    = colors.textPrimary,
                                modifier = Modifier.padding(vertical = 2.dp),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(Spacing.lg))

            // ── 按钮区 ─────────────────────────────────────────
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                // 发起对话按钮（主要）
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .then(
                            if (!character.isUnlocked) Modifier.clickable {
                                android.widget.Toast.makeText(
                                    context,
                                    "此角色尚未解锁，请先完成对应任务",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            } else Modifier
                        )
                ) {
                Button(
                    onClick = {
                        scope.launch { sheetState.hide() }.invokeOnCompletion {
                            onDismiss()
                            if (character.isUnlocked) onStartChat(character.id)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled  = character.isUnlocked,
                    colors   = ButtonDefaults.buttonColors(
                        containerColor = character.accentColor,
                        contentColor   = Color.White,
                    ),
                    shape    = RoundedCornerShape(Radius.sm),
                ) {
                    Text("发起对话", style = type.button)
                }
                }

                // 查看档案按钮（次要）
                Button(
                    onClick = {
                        scope.launch { sheetState.hide() }.invokeOnCompletion {
                            onDismiss()
                            onViewProfile(character.id)
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors   = ButtonDefaults.buttonColors(
                        containerColor = character.accentColor.copy(alpha = 0.12f),
                        contentColor   = character.accentColor,
                    ),
                    shape    = RoundedCornerShape(Radius.sm),
                ) {
                    Text("查看完整档案", style = type.button)
                }
            }

            // ── A-1 Fix：家族页入口 ───────────────────────────
            Spacer(Modifier.height(Spacing.xs))
            Button(
                onClick = {
                    scope.launch { sheetState.hide() }.invokeOnCompletion {
                        onDismiss()
                        onViewFamily(character.id)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors   = ButtonDefaults.buttonColors(
                    containerColor = character.accentColor.copy(alpha = 0.08f),
                    contentColor   = character.accentColor,
                ),
                shape    = RoundedCornerShape(Radius.sm),
            ) {
                Text("查看家族", style = type.button)
            }
        }
    }
}
