package com.zaijian.zhoumuyun.ui.screen.characterdetail


import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zaijian.zhoumuyun.data.db.entity.CharacterGoalEntity
import com.zaijian.zhoumuyun.data.db.entity.GoalHorizon
import com.zaijian.zhoumuyun.ui.viewmodel.GoalDraft
import com.zaijian.zhoumuyun.ui.viewmodel.GoalViewModel
import com.zaijian.zhoumuyun.ui.viewmodel.IdentityViewModel
import com.zaijian.zhoumuyun.ui.viewmodel.MemoryFilter
import com.zaijian.zhoumuyun.ui.viewmodel.MemoryUiItem
import com.zaijian.zhoumuyun.ui.viewmodel.MemoryViewModel
import com.zaijian.zhoumuyun.ui.viewmodel.PregnancyViewModel
import com.zaijian.zhoumuyun.data.model.PregnancyState
import com.zaijian.zhoumuyun.data.model.isDaughterMother
import com.zaijian.zhoumuyun.ui.theme.GoldDivider
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.zaijian.zhoumuyun.data.model.CharacterConfig
import com.zaijian.zhoumuyun.data.model.DefaultCharacters
import com.zaijian.zhoumuyun.data.model.DefaultPresenceStates
import com.zaijian.zhoumuyun.data.model.FloorEnum
import com.zaijian.zhoumuyun.data.model.StatusType
import com.zaijian.zhoumuyun.data.model.accentLight
import com.zaijian.zhoumuyun.ui.component.BreathingAvatar
import com.zaijian.zhoumuyun.ui.design.WorldCard
import com.zaijian.zhoumuyun.ui.theme.AppTheme
import com.zaijian.zhoumuyun.ui.theme.AppColors
import com.zaijian.zhoumuyun.ui.theme.AppTypography
import com.zaijian.zhoumuyun.ui.theme.AvatarSize
import com.zaijian.zhoumuyun.ui.theme.Elevation
import com.zaijian.zhoumuyun.ui.theme.GlassOpacity
import com.zaijian.zhoumuyun.ui.theme.Palette
import com.zaijian.zhoumuyun.ui.theme.Radius
import com.zaijian.zhoumuyun.ui.theme.RingWidth
import com.zaijian.zhoumuyun.ui.theme.Spacing
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme
import com.zaijian.zhoumuyun.util.ZLog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import androidx.compose.material3.FilterChip

@Composable
internal fun IdentityPanel(
    state: com.zaijian.zhoumuyun.ui.viewmodel.IdentityUiState,
    accentColor: Color,
    onPersonaChange: (String) -> Unit,
    onSpeechStyleChange: (String) -> Unit,
    onAttitudeToUserChange: (String) -> Unit,
    onCustomSystemPromptChange: (String) -> Unit,
    onAddBoundary: (String) -> Unit,
    onRemoveBoundary: (Int) -> Unit,
    onUpdateBoundary: (Int, String) -> Unit,
    onAddCoreBelief: (String) -> Unit,
    onRemoveCoreBelief: (Int) -> Unit,
    onUpdateCoreBelief: (Int, String) -> Unit,
    onSave: () -> Unit,
    // ── Phase 1（zaijian）内核字段回调 ──────────────────────
    onCoreWoundChange: (String) -> Unit = {},
    onCoreDesireChange: (String) -> Unit = {},
    onMaskTriggerChange: (String) -> Unit = {},
    onPrivatePersonaChange: (String) -> Unit = {},
    onPrivateStyleChange: (String) -> Unit = {},
    onPrivateExamplesChange: (String) -> Unit = {},
    onSituationRulesChange: (String) -> Unit = {},
    onDeviationSignalsChange: (String) -> Unit = {},
    // ── 附加（NyxChat V18 A.1/A.2）──
    onLikesChange: (String) -> Unit = {},
    onDislikesChange: (String) -> Unit = {},
    onRelationshipsChange: (String) -> Unit = {},
    onSoulNoteChange: (String) -> Unit = {},
    onNarrativeMemoryChange: (String) -> Unit = {},
    onUserImpressionChange: (String) -> Unit = {},
    onUndoLastNoteEdit: () -> Unit = {},
    lastEditedNoteField: String? = null,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography
    var advancedExpanded by remember { mutableStateOf(false) }

    if (state.isLoading) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                color       = accentColor,
                strokeWidth = 2.dp,
                modifier    = Modifier.size(24.dp),
            )
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.screenHorizontal),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        // ── 说明文字 ─────────────────────────────────────────
        Text(
            text  = "编辑后的人设在下次对话时立即生效。留空则使用角色默认设定。",
            style = type.caption,
            color = colors.textSecondary,
        )

        // ── 性格核心 ─────────────────────────────────────────
        IdentityField(
            label       = "性格核心",
            placeholder = "描述这个角色是什么样的人…",
            value       = state.persona,
            onValueChange = onPersonaChange,
            accentColor = accentColor,
            minLines    = 3,
        )

        // ── 说话风格 ─────────────────────────────────────────
        IdentityField(
            label       = "说话风格",
            placeholder = "语气、句式特点，例如「简洁克制，偶尔反问」…",
            value       = state.speechStyle,
            onValueChange = onSpeechStyleChange,
            accentColor = accentColor,
            minLines    = 2,
        )

        // ── 对你的态度 ───────────────────────────────────────
        IdentityField(
            label       = "对你的态度",
            placeholder = "例如「温柔但有距离感，不轻易表露情绪」…",
            value       = state.attitudeToUser,
            onValueChange = onAttitudeToUserChange,
            accentColor = accentColor,
            minLines    = 2,
        )

        // ── 禁忌（Boundaries）★ Phase 15 ────────────────────
        ListEditSection(
            title       = "绝对不会做的事",
            hint        = "每条一项，例如「不评价用户的选择」",
            items       = state.boundaries,
            accentColor = accentColor,
            onAdd       = onAddBoundary,
            onRemove    = onRemoveBoundary,
            onUpdate    = onUpdateBoundary,
        )

        // ── 核心信念（CoreBeliefs）★ Phase 15 ──────────────
        ListEditSection(
            title       = "核心信念",
            hint        = "每条一项，例如「陪伴是无声的力量」",
            items       = state.coreBeliefs,
            accentColor = accentColor,
            onAdd       = onAddCoreBelief,
            onRemove    = onRemoveCoreBelief,
            onUpdate    = onUpdateCoreBelief,
        )

        // ── 角色内核（Phase 1 zaijian）────────────────────────
        Spacer(Modifier.height(8.dp))
        Text(
            text     = "角色内核（AI 可见，影响角色深度表现）",
            style    = type.label,
            color    = accentColor,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        IdentityField(
            label         = "核心创伤",
            placeholder   = "曾经付出过全部，被彻底辜负。此后不再轻易动心。",
            value         = state.coreWound,
            onValueChange = onCoreWoundChange,
            accentColor   = accentColor,
        )
        Spacer(Modifier.height(8.dp))
        IdentityField(
            label         = "核心渴望",
            placeholder   = "被一个人完全接住，不需要交换，不需要表演。",
            value         = state.coreDesire,
            onValueChange = onCoreDesireChange,
            accentColor   = accentColor,
        )
        Spacer(Modifier.height(8.dp))
        IdentityField(
            label         = "面具何时碎裂（触发条件）",
            placeholder   = "对方第一次让她感到真正的安全；或她突然意识到自己已经在乎了。",
            value         = state.maskTrigger,
            onValueChange = onMaskTriggerChange,
            accentColor   = accentColor,
        )
        Spacer(Modifier.height(8.dp))
        IdentityField(
            label         = "私下真实面目（面具碎裂后）",
            placeholder   = "情感极度浓烈，像最纯粹的孩子，没有防御，也没有理智。",
            value         = state.privatePersona,
            onValueChange = onPrivatePersonaChange,
            accentColor   = accentColor,
        )
        Spacer(Modifier.height(8.dp))
        IdentityField(
            label         = "私下说话方式",
            placeholder   = "语气突然软下来，开始没有逻辑。可能哑口无言，也可能一下子说很多。",
            value         = state.privateStyle,
            onValueChange = onPrivateStyleChange,
            accentColor   = accentColor,
        )
        Spacer(Modifier.height(8.dp))
        IdentityField(
            label         = "私下对话示例（破防时的 Few-shot）",
            placeholder   = "用户：你哭了吗？\n角色：（没有回答，只是把头埋进他肩膀）",
            value         = state.privateExamples,
            onValueChange = onPrivateExamplesChange,
            accentColor   = accentColor,
            minLines      = 3,
        )
        Spacer(Modifier.height(8.dp))
        IdentityField(
            label         = "情境反应规则",
            placeholder   = "在被问到家人时：停顿三秒，换话题，如果对方继续问才会说一句模糊的话。",
            value         = state.situationRules,
            onValueChange = onSituationRulesChange,
            accentColor   = accentColor,
            minLines      = 3,
        )
        Spacer(Modifier.height(8.dp))
        IdentityField(
            label         = "有心事时的外显信号",
            placeholder   = "比平时沉默多一些；回复速度变慢；说话开始用「随便」、「都行」。",
            value         = state.deviationSignals,
            onValueChange = onDeviationSignalsChange,
            accentColor   = accentColor,
            minLines      = 3,
        )
        Spacer(Modifier.height(8.dp))

        // ── 附加（NyxChat V18 A.1/A.2）：喜恶 + 人际关系行为逻辑 ──
        Text(
            text     = "喜恶与人际（注入行为层，权重等同情境规则）",
            style    = type.label,
            color    = accentColor,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        IdentityField(
            label         = "你喜欢",
            placeholder   = "清晨的咖啡香气、独处时的安静、有人记住她的细节",
            value         = state.likes,
            onValueChange = onLikesChange,
            accentColor   = accentColor,
        )
        Spacer(Modifier.height(8.dp))
        IdentityField(
            label         = "你厌恶",
            placeholder   = "被人打断、无意义的客套、被当成工具",
            value         = state.dislikes,
            onValueChange = onDislikesChange,
            accentColor   = accentColor,
        )
        Spacer(Modifier.height(8.dp))
        IdentityField(
            label         = "人际关系行为逻辑",
            placeholder   = "在露娜面前：压制自己的情绪反应，偶尔用锐利的话刺她，但事后会后悔。\n在宥熙面前：隐性保护，不承认自己在关心她。",
            value         = state.relationships,
            onValueChange = onRelationshipsChange,
            accentColor   = accentColor,
            minLines      = 4,
        )
        Spacer(Modifier.height(8.dp))

        // ── Soul/Memory/User 三模块 ─────────────────────────────
        if (lastEditedNoteField != null) {
            val undoLabel = when (lastEditedNoteField) {
                "soul"   -> "人设备忘录"
                "memory" -> "关系记忆摘要"
                "user"   -> "她对你的印象"
                else     -> "笔记"
            }
            Spacer(Modifier.height(4.dp))
            androidx.compose.material3.TextButton(onClick = onUndoLastNoteEdit) {
                Text("↩ 撤销上次对「$undoLabel」的修改", style = type.caption, color = colors.accent)
            }
        }
        IdentityField(
            label         = "人设备忘录",
            placeholder   = "她希望被记住的样子——自由文本，不套结构",
            value         = state.soulNote,
            onValueChange = onSoulNoteChange,
            accentColor   = accentColor,
            minLines      = 3,
            softLimit     = 600,
        )
        Spacer(Modifier.height(8.dp))
        IdentityField(
            label         = "关系记忆摘要",
            placeholder   = "她经历了什么、关系走到哪了——整段覆盖写",
            value         = state.narrativeMemory,
            onValueChange = onNarrativeMemoryChange,
            accentColor   = accentColor,
            minLines      = 3,
            softLimit     = 800,
        )
        Spacer(Modifier.height(8.dp))
        IdentityField(
            label         = "她对你的印象",
            placeholder   = "角色对用户的整体印象",
            value         = state.userImpression,
            onValueChange = onUserImpressionChange,
            accentColor   = accentColor,
            minLines      = 2,
            softLimit     = 400,
        )
        Spacer(Modifier.height(8.dp))

        // ── 高级：完全替换 System Prompt ────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { advancedExpanded = !advancedExpanded }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text  = "高级：自定义完整 System Prompt",
                style = type.label,
                color = accentColor,
            )
            Text(
                text  = if (advancedExpanded) "收起" else "展开",
                style = type.caption,
                color = colors.textSecondary,
            )
        }
        if (advancedExpanded) {
            Text(
                text  = "非空时将完全替换上方字段，直接作为 AI 的 System Prompt。",
                style = type.caption,
                color = colors.textDisabled,
            )
            IdentityField(
                label       = "",
                placeholder = "你是…（直接写 System Prompt）",
                value       = state.customSystemPrompt,
                onValueChange = onCustomSystemPromptChange,
                accentColor = accentColor,
                minLines    = 5,
            )
        }

        // ── 保存按钮 ─────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.sm))
                .background(accentColor)
                .clickable { onSave() }
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text  = if (state.isSaved) "已保存 ✓" else "保存人设",
                style = type.button,
                color = androidx.compose.ui.graphics.Color.White,
            )
        }

        Spacer(Modifier.height(Spacing.sm))
    }
}

@Composable
internal fun IdentityField(
    label: String,
    placeholder: String,
    value: String,
    onValueChange: (String) -> Unit,
    accentColor: Color,
    minLines: Int = 2,
    softLimit: Int = 0,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (label.isNotEmpty()) {
            Text(text = label, style = type.label, color = colors.textSecondary)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.sm))
                .background(if (colors.isDark) colors.bgElevated else colors.bgCard)
                .border(
                    width = 0.5.dp,
                    color = colors.border,
                    shape = RoundedCornerShape(Radius.sm),
                )
                .padding(horizontal = Spacing.md, vertical = 10.dp),
        ) {
            if (value.isEmpty()) {
                Text(text = placeholder, style = type.body, color = colors.textDisabled)
            }
            BasicTextField(
                value         = value,
                onValueChange = onValueChange,
                textStyle     = type.body.copy(color = colors.textPrimary),
                minLines      = minLines,
                modifier      = Modifier.fillMaxWidth(),
                cursorBrush   = androidx.compose.ui.graphics.SolidColor(accentColor),
            )
        }
        if (softLimit > 0) {
            val over = value.length > softLimit
            Text(
                text    = "${value.length} / ${softLimit} 字",
                style   = type.small,
                color   = if (over) Palette.SemanticDanger else colors.textDisabled,
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}

