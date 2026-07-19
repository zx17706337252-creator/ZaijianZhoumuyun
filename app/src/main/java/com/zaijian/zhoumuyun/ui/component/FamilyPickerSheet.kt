package com.zaijian.zhoumuyun.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zaijian.zhoumuyun.data.model.StatusType
import com.zaijian.zhoumuyun.ui.theme.Spacing
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme
import com.zaijian.zhoumuyun.ui.viewmodel.FamilyListUiState
import com.zaijian.zhoumuyun.ui.viewmodel.FamilyListViewModel
import com.zaijian.zhoumuyun.ui.viewmodel.FamilyMember

// ─────────────────────────────────────────────────────────────
//  FamilyPickerSheet — 家族链原地浮层（线二·女儿对话入口，方案二）
//
//  替代早期 FamilyListScreen 独立页面方案。改动理由（讨论结论）：
//    ① 三代封顶 + 严格单链，数据规模最多 3 行，配不上一整个独立页面
//       的转场开销——这是个"扫一眼选一个"的轻量场景，不是"管理/浏览"场景。
//    ② 母亲格子已有"无后代直接进聊天"的行为（方案2），独立页面会让
//       "有后代"和"无后代"两种路径的仪式感差距过大，体验不统一。
//    ③ 不用称谓文字（"原住人/第二代/第三代"）做代际区分——系统视角的
//       标签不贴合产品的沉浸感调性，改用边框颜色区分。
//
//  数据层完全复用 FamilyListViewModel（loadFamily / getFamilyChain），
//  只是渲染容器从独立 Screen 换成 ModalBottomSheet，数据查询逻辑不变。
//
//  代际边框颜色算法：
//    一代（母亲）：该角色自己的 accentColor 原色，边框稍粗（1.5dp），
//                  强化"本体"存在感。
//    二代：lerp(motherAccentColor, Color.White, fraction = 0.35f)
//          —— 原色占 65%，与白混合（Oklab 空间插值，感知线性）。
//    三代：lerp(motherAccentColor, Color.White, fraction = 0.60f)
//          —— 原色占 40%。
//    用"该家族链母亲的 accentColor"做基准色而非全局统一色，是因为
//    九个母亲格子各自的 accentColor 互不相同（已核实），这样每条家族链
//    弹出的浮层会带上专属色调，代际深浅只影响明度，不丢失角色身份感。
//
//  头像占位：暂用名字首字（与 FamilyListScreen 旧版一致写法），
//  这是个独立问题（全角色头像目前都是空字符串），留作后续单独处理，
//  不阻塞本浮层上线。
// ─────────────────────────────────────────────────────────────

private const val GEN2_WHITE_FRACTION = 0.35f
private const val GEN3_WHITE_FRACTION = 0.60f

/**
 * 按代数计算边框颜色。
 *
 * @param motherAccentColor 这条家族链第一代母亲的 accentColor（基准色）
 * @param generation        1 = 母亲本人，2 = 女儿，3 = 孙女
 */
private fun borderColorForGeneration(motherAccentColor: Color, generation: Int): Color =
    when (generation) {
        1    -> motherAccentColor
        2    -> lerp(motherAccentColor, Color.White, GEN2_WHITE_FRACTION)
        else -> lerp(motherAccentColor, Color.White, GEN3_WHITE_FRACTION)
    }

/**
 * 家族链原地浮层。
 *
 * @param firstGenCharacterId 第一代母亲的 characterId（1-9）
 * @param onDismiss           关闭浮层（点外部/选完后调用）
 * @param onSelectCharacter   选中某一项后回调，调用方据此跳转聊天页
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun FamilyPickerSheet(
    firstGenCharacterId: Int,
    onDismiss: () -> Unit,
    onSelectCharacter: (characterId: Int) -> Unit,
    viewModel: FamilyListViewModel = viewModel(),
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(firstGenCharacterId) {
        viewModel.loadFamily(firstGenCharacterId)
    }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

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
            when (val state = uiState) {
                is FamilyListUiState.Loading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = Spacing.xl),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = colors.accent)
                    }
                }
                is FamilyListUiState.Error -> {
                    Text(
                        text     = state.message,
                        style    = type.body,
                        color    = colors.textSecondary,
                        modifier = Modifier.padding(
                            horizontal = Spacing.screenHorizontal,
                            vertical   = Spacing.md,
                        ),
                    )
                }
                is FamilyListUiState.Ready -> {
                    // 基准色：取这条家族链母亲（generation == 1）的 accentColor。
                    // members 由 loadFamily() 保证第一项一定是母亲本人，理论上不会找不到，
                    // 找不到时兜底用浮层主题色，避免整体崩溃。
                    val motherAccentColor = state.members
                        .firstOrNull { it.generation == 1 }
                        ?.config?.accentColor
                        ?: colors.accent

                    // W5-007 修复：快速连击某一行会在 onDismiss() 真正关闭浮层前
                    // 触发多次 onSelectCharacter，可能导致导航栈多次跳转。用一个
                    // 浮层级别的 isProcessing 标记拦截：一旦有任意一行被选中，
                    // 后续所有行的点击都不再生效，直到浮层关闭。
                    var isProcessing by remember { mutableStateOf(false) }
                    val onRowClick: (Int) -> Unit = { characterId ->
                        if (!isProcessing) {
                            isProcessing = true
                            onSelectCharacter(characterId)
                            onDismiss()
                        }
                    }

                    if (state.members.size <= 4) {
                        // 三代封顶最多 4 行内容已经很轻量，不需要 LazyColumn 的滚动开销，
                        // 直接 Column 铺开，避免嵌套滚动容器在 BottomSheet 里的已知问题。
                        Column {
                            state.members.forEach { member ->
                                FamilyMemberRow(
                                    member            = member,
                                    motherAccentColor = motherAccentColor,
                                    onClick           = { onRowClick(member.config.id) },
                                )
                            }
                        }
                    } else {
                        // 防御性兜底：理论上三代封顶不会超过 3 项，超出时退回可滚动列表
                        LazyColumn {
                            items(state.members, key = { it.config.id }) { member ->
                                FamilyMemberRow(
                                    member            = member,
                                    motherAccentColor = motherAccentColor,
                                    onClick           = { onRowClick(member.config.id) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FamilyMemberRow(
    member: FamilyMember,
    motherAccentColor: Color,
    onClick: () -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    val borderColor = borderColorForGeneration(motherAccentColor, member.generation)
    val borderWidth = if (member.generation == 1) 1.5.dp else 1.dp

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.screenHorizontal, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(width = borderWidth, color = borderColor, shape = RoundedCornerShape(12.dp))
            .background(colors.bgCard)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ── 真实头像（56dp BreathingAvatar）──────────────────────
        BreathingAvatar(
            imageUrl    = member.config.avatarUrl,
            breathColor = member.config.accentColor,
            statusType  = StatusType.ACTIVE,  // 家族页不显示在线状态点，用 ACTIVE 取消灰度遮罩
            size        = 56.dp,
            enableBreath = false,             // 静态列表不需要呼吸动画
        )

        Spacer(modifier = Modifier.width(Spacing.md))

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text     = member.config.name,
                style    = type.body,
                color    = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 代数标签（用颜色区分，不用文字，保持沉浸感；仅在非母亲行显示简短提示）
            if (member.generation > 1) {
                Text(
                    text  = if (member.generation == 2) "第二代" else "第三代",
                    style = type.label,
                    color = borderColor.copy(alpha = 0.75f),
                )
            }
        }
    }
}
