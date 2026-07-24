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
    // ── v1.36 问题3：用户身份设定（性别 + 关系称谓）回调 ────────
    onUserGenderChange: (String) -> Unit = {},
    onUserRoleLabelPrivateChange: (String) -> Unit = {},
    onUserRoleLabelPublicChange: (String) -> Unit = {},
    onPublicPrivacyReasonChange: (String) -> Unit = {},
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

        // ── 关于你（用户身份：性别 + 关系称谓）★ v1.36 问题3 ──────
        // 修复"角色统一用她称呼用户"：此前提示词从未告知模型用户是谁，
        // 这里让 世界书 按角色分别配置，未设置的角色仍会以默认"男性"注入。
        Spacer(Modifier.height(Spacing.xs))
        UserIdentitySection(
            userGender            = state.userGender,
            userRoleLabelPrivate  = state.userRoleLabelPrivate,
            userRoleLabelPublic   = state.userRoleLabelPublic,
            publicPrivacyReason   = state.publicPrivacyReason,
            accentColor           = accentColor,
            onUserGenderChange           = onUserGenderChange,
            onUserRoleLabelPrivateChange = onUserRoleLabelPrivateChange,
            onUserRoleLabelPublicChange  = onUserRoleLabelPublicChange,
            onPublicPrivacyReasonChange  = onPublicPrivacyReasonChange,
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
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text     = "角色内核（AI 可见，影响角色深度表现）",
            style    = type.label,
            color    = accentColor,
            modifier = Modifier.padding(bottom = Spacing.xs),
        )
        IdentityField(
            label         = "核心创伤",
            placeholder   = "曾经付出过全部，被彻底辜负。此后不再轻易动心。",
            value         = state.coreWound,
            onValueChange = onCoreWoundChange,
            accentColor   = accentColor,
        )
        Spacer(Modifier.height(Spacing.sm))
        IdentityField(
            label         = "核心渴望",
            placeholder   = "被一个人完全接住，不需要交换，不需要表演。",
            value         = state.coreDesire,
            onValueChange = onCoreDesireChange,
            accentColor   = accentColor,
        )
        Spacer(Modifier.height(Spacing.sm))
        IdentityField(
            label         = "面具何时碎裂（触发条件）",
            placeholder   = "对方第一次让她感到真正的安全；或她突然意识到自己已经在乎了。",
            value         = state.maskTrigger,
            onValueChange = onMaskTriggerChange,
            accentColor   = accentColor,
        )
        Spacer(Modifier.height(Spacing.sm))
        IdentityField(
            label         = "私下真实面目（面具碎裂后）",
            placeholder   = "情感极度浓烈，像最纯粹的孩子，没有防御，也没有理智。",
            value         = state.privatePersona,
            onValueChange = onPrivatePersonaChange,
            accentColor   = accentColor,
        )
        Spacer(Modifier.height(Spacing.sm))
        IdentityField(
            label         = "私下说话方式",
            placeholder   = "语气突然软下来，开始没有逻辑。可能哑口无言，也可能一下子说很多。",
            value         = state.privateStyle,
            onValueChange = onPrivateStyleChange,
            accentColor   = accentColor,
        )
        Spacer(Modifier.height(Spacing.sm))
        IdentityField(
            label         = "私下对话示例（破防时的 Few-shot）",
            placeholder   = "用户：你哭了吗？\n角色：（没有回答，只是把头埋进他肩膀）",
            value         = state.privateExamples,
            onValueChange = onPrivateExamplesChange,
            accentColor   = accentColor,
            minLines      = 3,
        )
        Spacer(Modifier.height(Spacing.sm))
        IdentityField(
            label         = "情境反应规则",
            placeholder   = "在被问到家人时：停顿三秒，换话题，如果对方继续问才会说一句模糊的话。",
            value         = state.situationRules,
            onValueChange = onSituationRulesChange,
            accentColor   = accentColor,
            minLines      = 3,
        )
        Spacer(Modifier.height(Spacing.sm))
        IdentityField(
            label         = "有心事时的外显信号",
            placeholder   = "比平时沉默多一些；回复速度变慢；说话开始用「随便」、「都行」。",
            value         = state.deviationSignals,
            onValueChange = onDeviationSignalsChange,
            accentColor   = accentColor,
            minLines      = 3,
        )
        Spacer(Modifier.height(Spacing.sm))

        // ── 附加（NyxChat V18 A.1/A.2）：喜恶 + 人际关系行为逻辑 ──
        Text(
            text     = "喜恶与人际（注入行为层，权重等同情境规则）",
            style    = type.label,
            color    = accentColor,
            modifier = Modifier.padding(bottom = Spacing.xs),
        )
        IdentityField(
            label         = "你喜欢",
            placeholder   = "清晨的咖啡香气、独处时的安静、有人记住她的细节",
            value         = state.likes,
            onValueChange = onLikesChange,
            accentColor   = accentColor,
        )
        Spacer(Modifier.height(Spacing.sm))
        IdentityField(
            label         = "你厌恶",
            placeholder   = "被人打断、无意义的客套、被当成工具",
            value         = state.dislikes,
            onValueChange = onDislikesChange,
            accentColor   = accentColor,
        )
        Spacer(Modifier.height(Spacing.sm))
        IdentityField(
            label         = "人际关系行为逻辑",
            placeholder   = "在露娜面前：压制自己的情绪反应，偶尔用锐利的话刺她，但事后会后悔。\n在宥熙面前：隐性保护，不承认自己在关心她。",
            value         = state.relationships,
            onValueChange = onRelationshipsChange,
            accentColor   = accentColor,
            minLines      = 4,
        )
        Spacer(Modifier.height(Spacing.sm))

        // ── Soul/Memory/User 三模块 ─────────────────────────────
        if (lastEditedNoteField != null) {
            val undoLabel = when (lastEditedNoteField) {
                "soul"   -> "人设备忘录"
                "memory" -> "关系记忆摘要"
                "user"   -> "她对你的印象"
                else     -> "笔记"
            }
            Spacer(Modifier.height(Spacing.xs))
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
        Spacer(Modifier.height(Spacing.sm))
        IdentityField(
            label         = "关系记忆摘要",
            placeholder   = "按阶段记：\"7月上旬起，持续讨论了XX话题\"——当前阶段延续就扩写最新一条，出现新话题就追加新的一条，旧阶段自己压缩变短",
            value         = state.narrativeMemory,
            onValueChange = onNarrativeMemoryChange,
            accentColor   = accentColor,
            minLines      = 3,
            softLimit     = 800,
        )
        Spacer(Modifier.height(Spacing.sm))
        IdentityField(
            label         = "她对你的印象",
            placeholder   = "角色对用户的整体印象",
            value         = state.userImpression,
            onValueChange = onUserImpressionChange,
            accentColor   = accentColor,
            minLines      = 2,
            softLimit     = 400,
        )
        Spacer(Modifier.height(Spacing.sm))

        // ── 高级：完全替换 System Prompt ────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { advancedExpanded = !advancedExpanded }
                .padding(vertical = Spacing.xs),
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

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
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

// ─────────────────────────────────────────────────────────────
//  v1.36 问题3：用户身份设定（性别 + 关系称谓）UI
//
//  放置在"对你的态度"字段之后——同属"角色如何看待用户"这一组信息，
//  与该字段紧邻显示更符合用户填写时的心理路径。
// ─────────────────────────────────────────────────────────────

/** 性别预设：(存入DB的值, 展示文案)。与 domain/UserIdentity.kt 的 UserGenderType 一一对应。 */
private val UserGenderOptions = listOf(
    "MALE" to "男性",
    "FEMALE" to "女性",
    "UNSPECIFIED" to "不指定",
)

/**
 * 关系称谓预设列表。这款 App 里角色与用户的关系既有恋人向（老公/男朋友/未婚夫/爱人），
 * 也有家人向（女儿角色可能称用户"爸爸"；也有哥哥/姐姐/弟弟/妹妹式设定），
 * 还有单纯的朋友/伙伴/师生关系——不预设单一关系类型，覆盖面尽量宽，
 * 同时保留自定义输入兜底不在列表里的称谓。
 */
private val UserRoleLabelPresets = listOf(
    "老公", "男朋友", "未婚夫", "爱人",
    "爸爸", "哥哥", "弟弟", "姐姐", "妹妹",
    "朋友", "伙伴", "学生",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UserIdentitySection(
    userGender: String,
    userRoleLabelPrivate: String,
    userRoleLabelPublic: String,
    publicPrivacyReason: String,
    accentColor: Color,
    onUserGenderChange: (String) -> Unit,
    onUserRoleLabelPrivateChange: (String) -> Unit,
    onUserRoleLabelPublicChange: (String) -> Unit,
    onPublicPrivacyReasonChange: (String) -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(
            text  = "关于你（这个角色怎么认识你）",
            style = type.label,
            color = accentColor,
        )
        Text(
            text  = "决定对话中角色如何代指/称呼你。这是背景认知，角色不会因此每句话都刻意点出称呼。",
            style = type.caption,
            color = colors.textSecondary,
        )

        // ── 性别 ─────────────────────────────────────────────
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(text = "你的性别", style = type.label, color = colors.textSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                UserGenderOptions.forEach { (savedValue, label) ->
                    SelectableChip(
                        text        = label,
                        selected    = userGender == savedValue,
                        accentColor = accentColor,
                        onClick     = { onUserGenderChange(savedValue) },
                    )
                }
            }
        }

        // ── 私下称谓 ─────────────────────────────────────────
        RoleLabelEditor(
            label         = "私下称谓",
            placeholder   = "只有你们两个人时，角色心里怎么称呼/看待你",
            value         = userRoleLabelPrivate,
            onValueChange = onUserRoleLabelPrivateChange,
            accentColor   = accentColor,
        )

        // ── 公开（圆桌）称谓 ───────────────────────────────────
        RoleLabelEditor(
            label         = "公开称谓（圆桌场景，有其他角色在场时）",
            placeholder   = "留空则自动沿用私下称谓",
            value         = userRoleLabelPublic,
            onValueChange = onUserRoleLabelPublicChange,
            accentColor   = accentColor,
        )

        // 只有公开称谓被单独设置、且确实与私下称谓不同时，"原因"才有意义——
        // 避免用户在还没填公开称谓时就看到一个不知道为什么要填的空字段。
        if (userRoleLabelPublic.isNotBlank() && userRoleLabelPublic != userRoleLabelPrivate) {
            IdentityField(
                label         = "公开场合为什么不用私下称谓（可选）",
                placeholder   = "例如「其他人还不知道你们的关系，TA不想说破」",
                value         = publicPrivacyReason,
                onValueChange = onPublicPrivacyReasonChange,
                accentColor   = accentColor,
                minLines      = 1,
            )
        }
    }
}

/** 称谓编辑组件：预设 chip 快速选择 + 自由文本输入，两者写同一个 value，互不冲突。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RoleLabelEditor(
    label: String,
    placeholder: String,
    value: String,
    onValueChange: (String) -> Unit,
    accentColor: Color,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(text = label, style = type.label, color = colors.textSecondary)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            verticalArrangement   = Arrangement.spacedBy(Spacing.xs),
        ) {
            UserRoleLabelPresets.forEach { preset ->
                SelectableChip(
                    text        = preset,
                    selected    = value == preset,
                    accentColor = accentColor,
                    onClick     = { onValueChange(preset) },
                )
            }
        }
        IdentityField(
            label         = "",
            placeholder   = placeholder,
            value         = value,
            onValueChange = onValueChange,
            accentColor   = accentColor,
            minLines      = 1,
        )
    }
}

/** 单选 chip：选中态实心填充 accentColor，未选中态描边。视觉沿用 AbilityPanel 标签墙同款语言。 */
@Composable
private fun SelectableChip(
    text: String,
    selected: Boolean,
    accentColor: Color,
    onClick: () -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.xs))
            .background(if (selected) accentColor else Color.Transparent)
            .border(
                width = 0.5.dp,
                color = if (selected) accentColor else colors.border,
                shape = RoundedCornerShape(Radius.xs),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = 6.dp),
    ) {
        Text(
            text  = text,
            style = type.caption,
            color = if (selected) androidx.compose.ui.graphics.Color.White else colors.textSecondary,
        )
    }
}

