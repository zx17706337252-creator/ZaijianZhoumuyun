package com.zaijian.zhoumuyun.ui.screen

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zaijian.zhoumuyun.data.db.entity.CharacterTitleRelationEntity
import com.zaijian.zhoumuyun.data.db.entity.ImpersonationPresetEntity
import com.zaijian.zhoumuyun.data.model.CharacterConfig
import com.zaijian.zhoumuyun.ui.component.DetailTopBar
import com.zaijian.zhoumuyun.ui.design.WorldCard
import com.zaijian.zhoumuyun.ui.theme.AppBrushes
import com.zaijian.zhoumuyun.ui.theme.Spacing
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme
import com.zaijian.zhoumuyun.ui.viewmodel.CharacterTitleRelationViewModel
import kotlinx.coroutines.launch

// ═══════════════════════════════════════════════════════════════
//  CharacterTitleRelationScreen — 角色间关系头衔管理页
//  （方案_角色间关系头衔系统_实施方案 三节）
//
//  结构：
//    1. 角色选择器（横向滚动，选中角色 A）
//    2. A 对其余角色/预设身份的头衔列表（单向，每行自由文本可编辑，失焦保存）
//    3. 预设名单管理分区（假扮识别用，加名字/删名字/列表展示）
// ═══════════════════════════════════════════════════════════════

@Composable
fun CharacterTitleRelationScreen(
    onBack: () -> Unit,
    viewModel: CharacterTitleRelationViewModel = viewModel(),
) {
    val colors = ZaijianTheme.colors
    val type = ZaijianTheme.typography
    val context = LocalContext.current

    val allCharacters by viewModel.allCharactersMerged.collectAsStateWithLifecycle()
    val selectedCharacterId by viewModel.selectedCharacterId.collectAsStateWithLifecycle()
    val relations by viewModel.relationsForSelected.collectAsStateWithLifecycle()
    val presets by viewModel.allPresets.collectAsStateWithLifecycle()
    val toast by viewModel.toast.collectAsStateWithLifecycle()

    LaunchedEffect(toast) {
        toast?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }

    // 目标列表 = 全部真实角色（A 自己除外）+ 全部预设身份名单。
    // 真实角色行优先展示于预设身份行之前，两者内部各自保持稳定顺序
    // （角色按 id 升序，预设按名字字母序，均来自各自 Flow 的排序）。
    val targetCharacters = remember(allCharacters, selectedCharacterId) {
        allCharacters.filter { it.id != selectedCharacterId }
    }

    Scaffold(
        containerColor = colors.bgBase,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            DetailTopBar(
                title = "角色关系头衔",
                onBack = onBack,
                headerBg = colors.surface,
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    horizontal = Spacing.screenHorizontal,
                    vertical = Spacing.md,
                ),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                // ── 角色选择器 ──────────────────────────────────────
                item {
                    Text(
                        text = "当前角色",
                        style = type.caption,
                        color = colors.textSecondary,
                    )
                    Spacer(Modifier.height(Spacing.xs))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        allCharacters.forEach { ch ->
                            CharacterChip(
                                name = ch.name,
                                selected = ch.id == selectedCharacterId,
                                onClick = { viewModel.selectCharacter(ch.id) },
                            )
                        }
                    }
                }

                // ── 头衔列表：真实角色目标 ───────────────────────────
                item {
                    Spacer(Modifier.height(Spacing.sm))
                    Text(
                        text = "对其他角色的头衔",
                        style = type.cardTitle,
                        color = colors.textPrimary,
                    )
                }
                if (targetCharacters.isEmpty()) {
                    item {
                        EmptyHint(text = "暂无其他可配置角色")
                    }
                } else {
                    items(targetCharacters, key = { "char_${it.id}" }) { target ->
                        val existing = relations.firstOrNull { it.toCharacterId == target.id }
                        TitleEditRow(
                            targetName = target.name,
                            initialTitle = existing?.title ?: "",
                            onSave = { newTitle -> viewModel.setTitleForCharacter(target.id, newTitle) },
                        )
                    }
                }

                // ── 头衔列表：预设身份目标 ───────────────────────────
                item {
                    Spacer(Modifier.height(Spacing.sm))
                    Text(
                        text = "对预设身份的头衔",
                        style = type.cardTitle,
                        color = colors.textPrimary,
                    )
                }
                if (presets.isEmpty()) {
                    item {
                        EmptyHint(text = "预设名单为空，去下方添加后即可在此配置头衔")
                    }
                } else {
                    items(presets, key = { "preset_${it.name}" }) { preset ->
                        val existing = relations.firstOrNull { it.toPresetName == preset.name }
                        TitleEditRow(
                            targetName = preset.name,
                            initialTitle = existing?.title ?: "",
                            onSave = { newTitle -> viewModel.setTitleForPresetName(preset.name, newTitle) },
                        )
                    }
                }

                // ── 预设名单管理分区 ─────────────────────────────────
                item {
                    Spacer(Modifier.height(Spacing.lg))
                    PresetManagementSection(
                        presets = presets,
                        onAdd = { name -> viewModel.addPreset(name) },
                        onRemove = { name -> viewModel.removePreset(name) },
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  角色选择器 Chip
// ─────────────────────────────────────────────────────────────

@Composable
private fun CharacterChip(
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type = ZaijianTheme.typography

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            // 选中态：黄铜三段渐变（金色军规 §1，禁纯色平涂）；未选：纸底 + 金发丝边
            .then(
                if (selected) {
                    Modifier.background(AppBrushes.goldGradient())
                } else {
                    Modifier
                        .background(colors.bgCard)
                        .border(0.5.dp, colors.accent.copy(alpha = 0.30f), RoundedCornerShape(999.dp))
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
    ) {
        Text(
            text = name,
            style = type.body,
            color = if (selected) Color.White else colors.textPrimary,
        )
    }
}

// ─────────────────────────────────────────────────────────────
//  单行：目标名称 + 可编辑头衔文本框（失焦保存）
// ─────────────────────────────────────────────────────────────

@Composable
private fun TitleEditRow(
    targetName: String,
    initialTitle: String,
    onSave: (String) -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type = ZaijianTheme.typography

    // 本地可编辑状态，随 initialTitle（切换角色/数据回填）变化时重置；
    // 失焦时若内容与上次保存值不同才触发写入，避免每次失焦都无意义 upsert。
    var text by remember(initialTitle) { mutableStateOf(initialTitle) }
    var lastSaved by remember(initialTitle) { mutableStateOf(initialTitle) }

    WorldCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 12.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = targetName,
                style = type.body,
                color = colors.textPrimary,
                modifier = Modifier.width(84.dp),
            )
            Spacer(Modifier.width(Spacing.sm))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { focusState ->
                        if (!focusState.isFocused && text != lastSaved) {
                            lastSaved = text
                            onSave(text)
                        }
                    },
                placeholder = { Text("未填写，如「姐妹」「同伴」") },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.accent,
                    unfocusedBorderColor = colors.border,
                    cursorColor = colors.accent,
                ),
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  空状态提示
// ─────────────────────────────────────────────────────────────

@Composable
private fun EmptyHint(text: String) {
    val colors = ZaijianTheme.colors
    val type = ZaijianTheme.typography

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.md),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = type.caption, color = colors.textDisabled)
    }
}

// ─────────────────────────────────────────────────────────────
//  预设名单管理分区
// ─────────────────────────────────────────────────────────────

@Composable
private fun PresetManagementSection(
    presets: List<ImpersonationPresetEntity>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type = ZaijianTheme.typography
    val scope = rememberCoroutineScope()

    var newName by remember { mutableStateOf("") }

    SectionCard(title = "假扮识别·预设名单") {
        Text(
            text = "消息中出现\"我不是主人，我是XX\"，XX 命中此名单才会触发假扮识别。" +
                "不要求对应正式角色，可单独维护（如\"表妹\"）。",
            style = type.caption,
            color = colors.textSecondary,
        )
        Spacer(Modifier.height(Spacing.sm))

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("新名字，如「表妹」") },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.accent,
                    unfocusedBorderColor = colors.border,
                    cursorColor = colors.accent,
                ),
            )
            Spacer(Modifier.width(Spacing.sm))
            IconButton(
                onClick = {
                    scope.launch {
                        onAdd(newName)
                        newName = ""
                    }
                },
            ) {
                Icon(
                    imageVector = com.zaijian.zhoumuyun.ui.design.AppIcons.Add,
                    contentDescription = "添加",
                    tint = colors.accent,
                )
            }
        }

        Spacer(Modifier.height(Spacing.sm))

        if (presets.isEmpty()) {
            Text(
                text = "名单为空，添加后可在上方为每个角色分别设置对TA的头衔",
                style = type.caption,
                color = colors.textDisabled,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                presets.forEach { preset ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(colors.bgBase.copy(alpha = 0.4f))
                            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = preset.name,
                            style = type.body,
                            color = colors.textPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { onRemove(preset.name) }) {
                            Icon(
                                imageVector = com.zaijian.zhoumuyun.ui.design.AppIcons.Delete,
                                contentDescription = "删除",
                                tint = colors.textSecondary,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  通用 Section 卡片（本地副本，风格对齐 PrivateChatScreen.SectionCard，
//  该组件为 private，无法跨文件复用，故在此另建同款）
// ─────────────────────────────────────────────────────────────

@Composable
private fun SectionCard(
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type = ZaijianTheme.typography

    WorldCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 16.dp,
    ) {
        Column(modifier = Modifier.padding(Spacing.cardPadding)) {
            if (title != null) {
                Text(title, style = type.cardTitle, color = colors.textPrimary)
                Spacer(Modifier.height(Spacing.sm))
            }
            content()
        }
    }
}
