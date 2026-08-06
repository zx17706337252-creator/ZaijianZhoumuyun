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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.zaijian.zhoumuyun.ui.design.GhostGoldButton
import com.zaijian.zhoumuyun.ui.design.RolePrimaryButton
import com.zaijian.zhoumuyun.ui.design.SecondaryGoldButton
import com.zaijian.zhoumuyun.ui.design.WashiTape
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
    val recentMemories by previewViewModel.recentMemories.collectAsStateWithLifecycle()
    LaunchedEffect(character.id) {
        previewViewModel.loadForCharacter(character.id)
    }

    ModalBottomSheet(
        onDismissRequest   = onDismiss,
        sheetState         = sheetState,
        containerColor     = colors.bgCard,
        shape              = RoundedCornerShape(topStart = Radius.lg, topEnd = Radius.lg),
        dragHandle = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(
                    modifier = Modifier
                        .padding(top = Spacing.sm)
                        .size(width = 36.dp, height = 4.dp)
                        .clip(CircleShape)
                        .background(colors.border)
                )
                // 内凹拱形饰线：拖拽把下方一条 0.6 宽的金色水平发丝线，
                // 底边倒 50% 圆角模拟内凹拱形。
                Box(
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .fillMaxWidth(0.6f)
                        .height(1.dp)
                        .clip(RoundedCornerShape(bottomStartPercent = 50, bottomEndPercent = 50))
                        .background(Palette.GoldLine),
                )
            }
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

            // 和纸胶带（融合方案 §3.3 书架预览装饰）：粉色胶带贴在最近记忆区域顶部
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                WashiTape(color = Color(0xFFEC93AE).copy(alpha = 0.7f))
            }
            Spacer(Modifier.height(4.dp))

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

            // ── 按钮区（UI 升级 v2.0 融合方案帧06：按钮权重三级）─────────
            // 酒红(RolePrimary) > 12%金(SecondaryGold) > 8%金(GhostGold) 三级权重。
            // 发起对话 = 角色主按钮（角色色渐变+白字），查看档案 = 次按钮（12%金底），
            // 查看家族 = 幽灵按钮（8%金底+金发丝边），视觉层级与操作频率对齐。
            // 批次4-7 修复：防重复点击——快速点击多个按钮时，
            // 多个 invokeOnCompletion 回调并发执行 onDismiss() + 导航，
            // 可能导致多个页面同时入栈，导航栈混乱。
            var isProcessing by remember { mutableStateOf(false) }
            // 纵向堆叠：发起对话(46dp) > 查看完整档案(42dp) > 查看家族(40dp)，
            // 高度递进 46/42/40，每个按钮之间 8dp 间距，视觉层级与操作频率对齐。
            Column(modifier = Modifier.fillMaxWidth()) {
                // 发起对话（角色主按钮·46dp）
                RolePrimaryButton(
                    text      = "发起对话",
                    roleColor = character.accentColor,
                    onClick = {
                        if (!character.isUnlocked) {
                            android.widget.Toast.makeText(
                                context,
                                "此角色尚未解锁，请先完成对应任务",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            if (isProcessing) return@RolePrimaryButton
                            isProcessing = true
                            scope.launch { sheetState.hide() }.invokeOnCompletion {
                                onDismiss()
                                onStartChat(character.id)
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (!character.isUnlocked) Modifier.alpha(0.45f) else Modifier),
                    height = 46.dp,
                )

                Spacer(Modifier.height(8.dp))

                // 查看完整档案（次按钮·12%金底·42dp）
                SecondaryGoldButton(
                    text    = "查看完整档案",
                    onClick = {
                        if (isProcessing) return@SecondaryGoldButton
                        isProcessing = true
                        scope.launch { sheetState.hide() }.invokeOnCompletion {
                            onDismiss()
                            onViewProfile(character.id)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    height = 42.dp,
                )

                Spacer(Modifier.height(8.dp))

                // 查看家族（幽灵按钮·8%金底·40dp）
                GhostGoldButton(
                    text    = "查看家族",
                    onClick = {
                        if (isProcessing) return@GhostGoldButton
                        isProcessing = true
                        scope.launch { sheetState.hide() }.invokeOnCompletion {
                            onDismiss()
                            onViewFamily(character.id)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    height = 40.dp,
                )
            }
        }
    }
}
