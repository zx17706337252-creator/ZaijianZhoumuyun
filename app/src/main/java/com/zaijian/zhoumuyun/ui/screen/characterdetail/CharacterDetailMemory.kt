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
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.ui.draw.alpha
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
import com.zaijian.zhoumuyun.ui.component.EmptyStateView
import com.zaijian.zhoumuyun.domain.ContentBlockParser
import com.zaijian.zhoumuyun.ui.component.ContentBlockRenderer
import com.zaijian.zhoumuyun.ui.design.WorldCard
import com.zaijian.zhoumuyun.ui.design.VaultCard
import com.zaijian.zhoumuyun.ui.design.WaxSealBadge
import com.zaijian.zhoumuyun.ui.theme.AppBrushes
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import com.zaijian.zhoumuyun.ui.design.AppIcons
import com.zaijian.zhoumuyun.ui.design.GoldPrimaryButton
import com.zaijian.zhoumuyun.ui.design.GhostGoldButton

private data class MemoryEntry(
    val id: String,
    val content: String,
    val dateLabel: String,
    val isImportant: Boolean = false,
    val isCore: Boolean = false,
    /** true = 关于用户（PERSONAL domain），false = 关于角色/世界 */
    val aboutSelf: Boolean = true,
    /** Phase 17：衰减状态标签，null = 无需显示 */
    val decayLabel: String? = null,
    /** Phase 30 方案三：维度标签 */
    val domainLabel: String = "",
    /** Phase 30 方案三：维度色条颜色 (ARGB Long) */
    val domainColorArgb: Long = 0xFF9E9E9EL,
    /** C8#44 UI 闭环：假扮身份识别期间产生的叙事记忆，需在列表中标出以区分 */
    val isNarrativeOnly: Boolean = false,
)

private fun MemoryUiItem.toEntry() = MemoryEntry(
    id              = id,
    content         = content,
    dateLabel       = dateLabel,
    isImportant     = isImportant,
    isCore          = isCore,
    aboutSelf       = aboutSelf,
    decayLabel      = decayLabel,
    domainLabel     = domainLabel,
    domainColorArgb = domainColorArgb,
    isNarrativeOnly = isNarrativeOnly,
)

@Composable
internal fun MemoryTabContent(
    memoryViewModel:      MemoryViewModel,
    accentColor:          Color,
    memoryDimTab:         Int,
    memorySecondaryChip:  Int,
    onDimTabChange:       (Int) -> Unit,
    onSecondaryChipChange:(Int) -> Unit,
    onShowAddDialog:      () -> Unit,
    onEditMemory:         (String, String) -> Unit,
    characterName:        String,
) {
    // ★ collectAsState 在此处执行，仅当 mainTab == 0 时该 Composable 存在
    val memoryState by memoryViewModel.uiState.collectAsStateWithLifecycle()

    // P2-7-2 修复：消费 memoryState.snackbar（删除/编辑/保存等操作反馈）。此前该字段
    // 只在 ViewModel 里写入、UI 从不消费，用户操作成功/失败无任何提示。本 Composable
    // 无 Scaffold/SnackbarHost，用轻量 Toast 展示后清空，避免改动外层布局结构。
    val context = LocalContext.current
    LaunchedEffect(memoryState.snackbar) {
        val msg = memoryState.snackbar ?: return@LaunchedEffect
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
        memoryViewModel.clearSnackbar()
    }

    Column {
        // ── v1.1：四段式"记忆档案"视图（自上而下）──────────────
        // 1.关系叙事 / 2.她对你的印象 / 3.重大事件锚点 / 4.其他记忆（原有 filter+list）
        // 前三段此前完全不可见（藏是人设 Tab 配置表单里），这里改为查看入口。
        // 人设 Tab 的编辑功能不删除，两处共享同一份数据源。

        // 导出按钮（顶部，独立一行）
        MemoryExportButton(
            accentColor   = accentColor,
            exportResult  = memoryState.exportResult,
            onExport      = { memoryViewModel.exportArchive(characterName) },
            onClearResult = { memoryViewModel.clearExportResult() },
        )
        Spacer(Modifier.height(Spacing.sm))

        // 1. 关系叙事（narrativeMemory）—— MarkdownText 渲染，阶段日志结构
        NarrativeSection(
            label       = "关系叙事",
            content     = memoryState.narrativeMemory,
            accentColor = accentColor,
            onSave      = { memoryViewModel.updateNarrativeMemory(it) },
            modifier    = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.screenHorizontal),
        )
        Spacer(Modifier.height(Spacing.md))

        // 2. 她对你的印象（userImpression）—— 纯文本
        // P2-4 修复（Window A 验收待办）：补充文档 §3.1 要求纯文本展示，
        // 不走 MarkdownText——印象文本若含 * _ # 等字符会被误解析。
        NarrativeSection(
            label        = "她对你的印象",
            content      = memoryState.userImpression,
            accentColor  = accentColor,
            useMarkdown  = false,
            onSave       = { memoryViewModel.updateUserImpression(it) },
            modifier     = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.screenHorizontal),
        )
        Spacer(Modifier.height(Spacing.md))

        // 3. 重大事件锚点（isCore=true 的 memories）—— 单独分区
        CoreAnchorsSection(
            coreMemories = memoryState.coreMemories,
            accentColor  = accentColor,
            modifier     = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.screenHorizontal),
        )
        Spacer(Modifier.height(Spacing.md))

        // 4. 其他记忆（现有 filter + list，折叠在后面作为明细）
        MemoryOtherMemoriesHeader()
        Spacer(Modifier.height(Spacing.xs))

        // Phase 30 方案三：两层过滤结构
        // 第一层（主维度 Tab）：全部 | 工作 | 情感
        MemoryDimTabRow(
            selectedIndex = memoryDimTab,
            accentColor   = accentColor,
            onSelect      = onDimTabChange,
        )
        // 第二层（次维度 FilterChip）：重要
        MemorySecondaryChips(
            dimIndex    = memoryDimTab,
            chipIndex   = memorySecondaryChip,
            accentColor = accentColor,
            onSelect    = onSecondaryChipChange,
        )
        Spacer(Modifier.height(Spacing.sm))

        if (memoryState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Spacing.xxl),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    color       = accentColor,
                    strokeWidth = 2.dp,
                    modifier    = Modifier.size(24.dp),
                )
            }
        } else {
            val entries = memoryState.items.map { it.toEntry() }

            if (entries.isEmpty()) {
                EmptyStateView(
                    icon     = AppIcons.Psychology,
                    title    = "还没有记忆，去聊聊吧",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Spacing.xxl),
                )
            } else {
                entries.forEach { entry ->
                    MemoryRow(
                        entry        = entry,
                        accentColor  = accentColor,
                        onToggleStar = { memoryViewModel.toggleImportant(entry.id) },
                        onDelete     = { memoryViewModel.delete(entry.id) },
                        onEdit       = { onEditMemory(entry.id, entry.content) },
                        modifier     = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.screenHorizontal),
                    )
                    Spacer(Modifier.height(Spacing.xs))
                }
            }

            Spacer(Modifier.height(Spacing.sm))
            AddButton(
                label       = "新增记忆",
                accentColor = accentColor,
                onClick     = onShowAddDialog,
                modifier    = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.screenHorizontal),
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  v1.1：四段式"记忆档案"视图的子组件
// ─────────────────────────────────────────────────────────────

/**
 * 导出记忆存档按钮 + 结果提示。
 * UI 直接触发 exportArchive，不经过 LLM（补充文档 §4.1）。
 */
@Composable
private fun MemoryExportButton(
    accentColor: Color,
    exportResult: String?,
    onExport: () -> Unit,
    onClearResult: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val type   = ZaijianTheme.typography
    val colors = ZaijianTheme.colors

    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.md))
                .background(accentColor.copy(alpha = 0.12f))
                .clickable { onExport() }
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text  = "导出记忆存档",
                style = type.button,
                color = accentColor,
            )
        }
        exportResult?.let { result ->
            Spacer(Modifier.height(Spacing.xs))
            Row(
                modifier          = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                Text(
                    text     = result,
                    style    = type.caption,
                    color    = colors.textSecondary,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .sizeIn(minWidth = 48.dp, minHeight = 36.dp)
                        .clickable { onClearResult() }
                        .wrapContentSize(Alignment.Center),
                ) {
                    Text("关闭", style = type.caption, color = accentColor)
                }
            }
        }
    }
}

/**
 * 叙事字段展示+内联编辑（关系叙事 / 她对你的印象）。
 * 预览态默认用 MarkdownText 渲染（阶段日志的时间标签天然显示成结构化文档），
 * 编辑态用 BasicTextField，保存复用 identityRepo.upsert*。
 *
 * P2-4 修复（Window A 验收待办）：新增 [useMarkdown] 参数。
 * "关系叙事"保持 MarkdownText（阶段日志含时间标签结构化文本）；
 * "她对你的印象"按补充文档 §3.1 要求纯文本展示，传 false 走 Text 渲染。
 */
@Composable
private fun NarrativeSection(
    label: String,
    content: String,
    accentColor: Color,
    onSave: (String) -> Unit,
    modifier: Modifier = Modifier,
    useMarkdown: Boolean = true,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography
    var isEditing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(content) }

    // content 来自 Room Flow 观察的 character_identity（narrativeMemory/userImpression）。
    // 此前用 remember(content) 做 key：agent 工具 SoulMemoryUserTools 在后台聊天/主动消息
    // 流程中写入该字段会推送新 content，导致 draft 被静默重置，用户正在编辑但未保存的
    // 内容被覆盖（B7 审查报告 序号2）。
    // 改为只在非编辑态时把 draft 同步为最新 content：进入编辑态后，后台再怎么写都不会
    // 打断用户输入；只有点击"保存"才会把 draft 提交出去。切换角色（content 随之切换）时
    // 因为此时必然处于非编辑态，draft 会正常同步为新角色的内容。
    LaunchedEffect(content, isEditing) {
        if (!isEditing) {
            draft = content
        }
    }

    WorldCard(modifier = modifier, cornerRadius = Radius.sm) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                Text(
                    text       = label,
                    style      = type.body,
                    color      = accentColor,
                    fontWeight = FontWeight.Bold,
                )
                Box(
                    modifier = Modifier
                        .sizeIn(minWidth = 48.dp, minHeight = 36.dp)
                        .clickable {
                            if (isEditing) onSave(draft)
                            isEditing = !isEditing
                        }
                        .wrapContentSize(Alignment.Center),
                ) {
                    Text(
                        text  = if (isEditing) "保存" else "编辑",
                        style = type.caption,
                        color = accentColor,
                    )
                }
            }
            Spacer(Modifier.height(Spacing.xs))
            if (isEditing) {
                BasicTextField(
                    value         = draft,
                    onValueChange = { draft = it },
                    textStyle     = type.body.copy(color = colors.textPrimary),
                    modifier      = Modifier
                        .fillMaxWidth()
                        .sizeIn(minHeight = 100.dp),
                )
            } else {
                if (content.isBlank()) {
                    Text(
                        text  = "（尚未建立）",
                        style = type.body,
                        color = colors.textDisabled,
                    )
                } else if (useMarkdown) {
                    // E2 统一内容渲染接入：关系叙事走 ContentBlockParser → ContentBlockRenderer，
                    // 支持标题/列表/引用等块级结构化渲染，行内 Markdown 仍由内部 MarkdownText 处理。
                    val blocks = remember(content) { ContentBlockParser.parse(content) }
                    ContentBlockRenderer(
                        blocks    = blocks,
                        textColor = colors.textPrimary,
                        style     = type.body,
                    )
                } else {
                    // P2-4：纯文本展示（"她对你的印象"），不走 Markdown 解析
                    Text(
                        text  = content,
                        style = type.body,
                        color = colors.textPrimary,
                    )
                }
            }
        }
    }
}

/**
 * 重大事件锚点分区（isCore=true 的 memories），按 createdAt 倒序。
 * 视觉上和普通记忆列表区分：单独 WorldCard + 左侧 accent 色条。
 */
@Composable
private fun CoreAnchorsSection(
    coreMemories: List<MemoryUiItem>,
    accentColor: Color,
    modifier: Modifier = Modifier,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    WorldCard(modifier = modifier, cornerRadius = Radius.sm) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            Text(
                text       = "重大事件锚点（${coreMemories.size} 条）",
                style      = type.body,
                color      = accentColor,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(Spacing.xs))
            if (coreMemories.isEmpty()) {
                Text(
                    text  = "（暂无锚点）",
                    style = type.caption,
                    color = colors.textDisabled,
                )
            } else {
                coreMemories.forEach { memory ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = Spacing.xs),
                        verticalAlignment = Alignment.Top,
                    ) {
                        // 左侧锚点标识色条（和普通 MemoryRow 的维度色条区分：用 accent 色）
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(20.dp)
                                .background(
                                    color  = accentColor,
                                    shape  = RoundedCornerShape(2.dp),
                                ),
                        )
                        Spacer(Modifier.width(Spacing.sm))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text  = memory.content,
                                style = type.body,
                                color = colors.textPrimary,
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text  = memory.dateLabel,
                                    style = type.label,
                                    color = colors.textDisabled,
                                )
                                // C8#44 UI 闭环：isCore 和 isNarrativeOnly 是写入侧独立
                                // 判定的两个字段（isCore = importance==5），假扮场景里
                                // 产生的记忆一样可能被判为"重大事件"，这里同样需要标出，
                                // 否则比普通列表更容易被误读成真实发生的关系里程碑。
                                if (memory.isNarrativeOnly) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(Palette.SemanticNeutral.copy(alpha = 0.12f))
                                            .padding(horizontal = 5.dp, vertical = 1.dp),
                                    ) {
                                        Text("叙事记忆", style = type.label, color = colors.textSecondary)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** "其他记忆"分区标题，视觉上和前三段区分。 */
@Composable
private fun MemoryOtherMemoriesHeader() {
    val type   = ZaijianTheme.typography
    val colors = ZaijianTheme.colors
    Text(
        text       = "其他记忆",
        style      = type.body,
        color      = colors.textSecondary,
        fontWeight = FontWeight.Bold,
        modifier   = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.screenHorizontal),
    )
}

@Composable
internal fun AddMemoryDialog(
    accentColor: Color,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors  = ZaijianTheme.colors
    val type    = ZaijianTheme.typography
    var text by remember { mutableStateOf("") }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = colors.bgCard,
        title = {
            Text(
                text  = "新增记忆",
                style = type.cardTitle,
                color = colors.textPrimary,
            )
        },
        text = {
            Column {
                Text(
                    text  = "手动记录一件重要的事，它会作为长期记忆保留。",
                    style = type.caption,
                    color = colors.textSecondary,
                )
                Spacer(Modifier.height(Spacing.sm))
                androidx.compose.material3.OutlinedTextField(
                    value         = text,
                    onValueChange = { text = it },
                    placeholder   = {
                        Text(
                            text  = "例：喜欢喝桂花乌龙，不吃辣",
                            style = type.body,
                            color = colors.textDisabled,
                        )
                    },
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = accentColor,
                        unfocusedBorderColor = colors.border,
                        focusedTextColor     = colors.textPrimary,
                        unfocusedTextColor   = colors.textPrimary,
                        cursorColor          = accentColor,
                    ),
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            val canSave = text.isNotBlank()
            GoldPrimaryButton(
                text     = "保存",
                onClick  = { if (canSave) onConfirm(text) },
                modifier = Modifier.alpha(if (canSave) 1f else 0.4f),
            )
        },
        dismissButton = {
            GhostGoldButton(
                text    = "取消",
                onClick = onDismiss,
            )
        },
    )
}

@Composable
internal fun EditMemoryDialog(
    initialContent: String,
    accentColor: Color,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography
    var text by remember { mutableStateOf(initialContent) }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = colors.bgCard,
        title = {
            Text(
                text  = "编辑记忆",
                style = type.cardTitle,
                color = colors.textPrimary,
            )
        },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value         = text,
                onValueChange = { text = it },
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedBorderColor   = accentColor,
                    unfocusedBorderColor = colors.border,
                    focusedTextColor     = colors.textPrimary,
                    unfocusedTextColor   = colors.textPrimary,
                    cursorColor          = accentColor,
                ),
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            val canSave = text.isNotBlank()
            GoldPrimaryButton(
                text     = "保存",
                onClick  = { if (canSave) onConfirm(text) },
                modifier = Modifier.alpha(if (canSave) 1f else 0.4f),
            )
        },
        dismissButton = {
            GhostGoldButton(
                text    = "取消",
                onClick = onDismiss,
            )
        },
    )
}

@Composable
private fun MemoryDimTabRow(
    selectedIndex: Int,
    accentColor: Color,
    onSelect: (Int) -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography
    // 主维度：全部 / 工作 / 情感
    val tabs = listOf("全部", "工作", "情感")

    ScrollableTabRow(
        selectedTabIndex  = selectedIndex,
        containerColor    = Color.Transparent,
        contentColor      = accentColor,
        edgePadding       = Spacing.screenHorizontal,
        indicator         = { tabPositions ->
            if (selectedIndex < tabPositions.size) {
                TabRowDefaults.SecondaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(tabPositions[selectedIndex]),
                    color    = when (selectedIndex) {
                        1    -> Palette.Focused   // 工作蓝
                        2    -> Palette.SemanticEmotion   // 情感粉
                        else -> accentColor
                    },
                    height   = 2.dp,
                )
            }
        },
        divider = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(colors.border)
            )
        },
    ) {
        tabs.forEachIndexed { index, label ->
            val tabAccent = when (index) {
                1    -> Palette.Focused
                2    -> Palette.SemanticEmotion
                else -> accentColor
            }
            Tab(
                selected = selectedIndex == index,
                onClick  = { onSelect(index) },
                text     = {
                    Text(
                        text  = label,
                        style = type.caption.copy(
                            fontWeight = if (selectedIndex == index) FontWeight.Medium else FontWeight.Normal,
                        ),
                        color = if (selectedIndex == index) tabAccent else colors.textSecondary,
                    )
                },
                selectedContentColor   = tabAccent,
                unselectedContentColor = colors.textSecondary,
            )
        }
    }
}

@Composable
private fun MemorySecondaryChips(
    dimIndex:    Int,
    chipIndex:   Int,
    accentColor: Color,
    onSelect:    (Int) -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    // 根据主维度决定次维度 Chip 组。
    // 待处理报告 9-6 关联修复：MemoryFilter.ABOUT_WORLD（domain != PERSONAL，
    // 涵盖 WORK/WORLD/RULE/INFERENCE）此前定义了但从未接入 UI。只在"全部"
    // 维度（dimIndex==0）下暴露"关于世界"chip——"工作"/"情感"两个维度已经是
    // domain 的精确子集，再叠加"关于世界"会与其语义冲突或恒为空。
    // 不加"关于我"：ABOUT_ME 的查询条件（domain==PERSONAL）与"情感"维度
    // （EMOTION，domain==PERSONAL）完全重复，会做出一个内容和现有"情感"tab
    // 一模一样的多余按钮。
    val chips = if (dimIndex == 0) listOf("重要", "关于世界") else listOf("重要")

    // P3-46 修复：当前只有"重要"一个 Chip，FlowRow（自动换行布局）
    // 在此场景下是多余开销，改为普通 Row +水平间距即可。
    Row(
        modifier            = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.screenHorizontal, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        chips.forEachIndexed { index, label ->
            val chipAccent = when (dimIndex) {
                1    -> Palette.Focused
                2    -> Palette.SemanticEmotion
                else -> accentColor
            }
            val selected = chipIndex == index + 1
            FilterChip(
                selected = selected,
                onClick  = { onSelect(if (selected) 0 else index + 1) },
                label    = {
                    Text(
                        text  = label,
                        style = type.label,
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor     = chipAccent.copy(alpha = 0.15f),
                    selectedLabelColor         = chipAccent,
                    containerColor             = Color.Transparent,
                    labelColor                 = colors.textSecondary,
                    selectedLeadingIconColor   = chipAccent,
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled         = true,
                    selected        = selected,
                    borderColor     = colors.border,
                    selectedBorderColor = chipAccent.copy(alpha = 0.5f),
                    borderWidth     = 0.5.dp,
                    selectedBorderWidth = 1.dp,
                ),
            )
        }
    }
}

@Composable
private fun MemoryRow(
    entry: MemoryEntry,
    accentColor: Color,
    onToggleStar: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    // Phase 30 方案三：左侧 2dp 维度色条
    val stripColor = if (entry.domainLabel.isNotEmpty())
        Color(entry.domainColorArgb) else Color.Transparent

    // WorldCard 接入（精修方案 v1.3）：独立列表项，L0-L2 常态层交给
    // WorldCard。不传 ownerAccent——整页本就是单一角色的记忆列表，"归属
    // 谁"已经是页面级的已知信息；这里真正需要逐条区分的是"记忆维度"
    // （Phase 30 既有的左侧 stripColor 色条），保留它不动，避免和 L3
    // 身份脊在左侧出现两条并排竖线、互相抢视觉。
    // UI 升级 v2.0（帧09 置顶记忆）：置顶记忆（isImportant=true）用馆藏
    // 收藏卡 VaultCard 替换 WorldCard——拱形卡头放日期标题（角色色渐变
    // 底由 ownerAccent 触发），卡身放记忆内容 + 标签 + 操作按钮，火漆
    // 角标「珍」由 VaultCard 的 waxChar 参数统一渲染。非置顶记忆保持
    // WorldCard 不变。cardBody 提取共享卡身内容，两分支复用，避免重复。
    val cardBody: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.Top) {
            // 左侧维度色条
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(
                        color = stripColor,
                        shape = RoundedCornerShape(topStart = Radius.sm, bottomStart = Radius.sm),
                    ),
            )
            // 右侧内容区
            // 排版修复：编辑/删除/星标三个 48dp 触控热区此前和正文 Text 挤在
            // 同一个 Row 里，正文 Column 只能拿到"总宽度 - 3×48dp - 间距"剩下
            // 的窄条（实测常常不到 100dp），长记忆被压成一列很窄、行数很多的
            // 文字，看起来像是"整段都挤在左边"、图标又悬在段落中间。现在把
            // 三个操作图标挪到下方，和日期/标签同一行（那一行本身矮，容得下），
            // 正文 Text 单独占满整行宽度。
            Column(modifier = Modifier.weight(1f).padding(Spacing.md)) {
                // 内容（占满整行宽度）
                Text(
                    text     = entry.content,
                    style    = type.body,
                    color    = colors.textPrimary,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(Spacing.xs))
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment     = Alignment.CenterVertically,
                ) {
                    // 日期 + 标签
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                        verticalAlignment     = Alignment.CenterVertically,
                    ) {
                        Text(
                            text  = entry.dateLabel,
                            style = type.label,
                            color = colors.textDisabled,
                        )
                        // Phase 17：衰减状态标签
                        entry.decayLabel?.let { label ->
                            val (bgAlpha, textColor) = when (label) {
                                "7天到期"  -> 0.15f to Palette.SemanticDanger
                                "即将到期"  -> 0.12f to Palette.SemanticWarning
                                "即将清理" -> 0.12f to Palette.SemanticWarning
                                else       -> 0.10f to colors.textSecondary
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(textColor.copy(alpha = bgAlpha))
                                    .padding(horizontal = 5.dp, vertical = 1.dp),
                            ) {
                                Text(label, style = type.label, color = textColor)
                            }
                        }
                        // C8#44 UI 闭环：叙事记忆标签——假扮身份识别期间产生的记忆，
                        // 用中性灰区别于 decayLabel 的警示色，避免被误读成"即将过期"
                        // 一类的状态提示；这条只是"来源说明"，不是需要用户处理的事。
                        if (entry.isNarrativeOnly) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Palette.SemanticNeutral.copy(alpha = 0.12f))
                                    .padding(horizontal = 5.dp, vertical = 1.dp),
                            ) {
                                Text("叙事记忆", style = type.label, color = colors.textSecondary)
                            }
                        }
                    }

                    // UI M12 修复：图标触摸热区扩大到 48dp×48dp（Android 最小触控建议），
                    // 视觉尺寸（20dp）保持不变，外层 Box 吸收额外点击区域。放在日期/
                    // 标签这一行的末尾，不再和正文共享一行，避免挤占正文宽度。
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 编辑
                        Box(
                            modifier = Modifier
                                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                .clickable { onEdit() }
                                .wrapContentSize(Alignment.Center),
                        ) {
                            Icon(
                                imageVector        = AppIcons.Edit,
                                contentDescription = "编辑记忆",
                                tint               = colors.textDisabled,
                                modifier           = Modifier.size(20.dp),
                            )
                        }
                        // 删除
                        Box(
                            modifier = Modifier
                                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                .clickable { onDelete() }
                                .wrapContentSize(Alignment.Center),
                        ) {
                            Icon(
                                imageVector        = AppIcons.Delete,
                                contentDescription = "删除记忆",
                                tint               = colors.textDisabled,
                                modifier           = Modifier.size(20.dp),
                            )
                        }
                        // 星标
                        Box(
                            modifier = Modifier
                                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                .clickable { onToggleStar() }
                                .wrapContentSize(Alignment.Center),
                        ) {
                            Icon(
                                imageVector        = if (entry.isImportant) AppIcons.Star else AppIcons.StarBorder,
                                contentDescription = if (entry.isImportant) "取消重要" else "标记重要",
                                tint               = if (entry.isImportant) accentColor else colors.textDisabled,
                                modifier           = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            } // end content Column
        } // end outer Row (strip+content)
    }

    Box(modifier = modifier) {
        if (entry.isImportant) {
            // UI 升级 v2.0（帧09 大拱壁龛）：置顶记忆外层包一层拱形壁龛容器——
            // 大圆角拱顶（28dp）+ 金色渐变描边 + 微抬底色，营造「馆藏展龛」仪式感。
            // VaultCard 本身已有拱形卡头，壁龛是更外层的展示框架。
            val nicheShape = RoundedCornerShape(
                topStart = 28.dp, topEnd = 28.dp,
                bottomStart = Radius.sm, bottomEnd = Radius.sm,
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(nicheShape)
                    .border(1.dp, AppBrushes.goldGradient(), nicheShape)
                    .background(colors.bgElevated)
                    .padding(1.dp),
            ) {
                VaultCard(
                    headerContent = {
                        Text(
                            text  = entry.dateLabel,
                            style = type.label,
                            color = Color.White,
                        )
                    },
                    bodyContent   = cardBody,
                    ownerAccent   = accentColor,
                    waxChar       = "珍",
                )
                // UI 升级 v2.0（帧09 书签穗）：壁龛右上角悬挂金色书签穗——
                // 3dp 宽金渐变窄条 + 末端三角切口，模拟书签丝带垂坠。
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 16.dp)
                        .width(3.dp)
                        .height(20.dp)
                        .clip(RoundedCornerShape(topStart = 1.5.dp, topEnd = 1.5.dp))
                        .background(AppBrushes.goldGradient()),
                )
            }
        } else {
            WorldCard(cornerRadius = Radius.sm) {
                cardBody()
            }
        }
    }
}

