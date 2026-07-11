package com.zaijian.zhoumuyun.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Spa
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
// GitHub 配置已移至专属管理页
import com.zaijian.zhoumuyun.data.model.DefaultCharacters
import com.zaijian.zhoumuyun.data.db.AppDatabase
import com.zaijian.zhoumuyun.data.provider.ProviderManager
import com.zaijian.zhoumuyun.data.provider.ProviderType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.zaijian.zhoumuyun.ui.component.BreathingAvatar
import com.zaijian.zhoumuyun.ui.component.OptionPickerDialog
import com.zaijian.zhoumuyun.ui.design.WorldCard
import com.zaijian.zhoumuyun.ui.theme.AvatarSize
import com.zaijian.zhoumuyun.ui.theme.AppTheme
import com.zaijian.zhoumuyun.ui.theme.GlassOpacity
import com.zaijian.zhoumuyun.ui.theme.Palette
import com.zaijian.zhoumuyun.ui.theme.Radius
import com.zaijian.zhoumuyun.ui.theme.Spacing
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme
import kotlinx.coroutines.launch


// ─────────────────────────────────────────────────────────────
//  AiConfigSection — AI 提供商配置（接 ProviderManager）
// ─────────────────────────────────────────────────────────────

@Composable
internal fun AiConfigSection() {
    val colors  = ZaijianTheme.colors
    val type    = ZaijianTheme.typography
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()
    val pm      = remember { ProviderManager.instance }

    // ── 本地 UI State ──────────────────────────────────────

    // 当前选择的 ProviderType
    var selectedType by remember {
        val savedId = pm.getActiveProviderId()
        val initial = ProviderType.entries.find { it.toProviderId() == savedId }
            ?: ProviderType.DEEPSEEK
        mutableStateOf(initial)
    }

    // Key 输入（不显示明文）
    // 使用 TextFieldValue 以便在粘贴后将光标重置到首位，
    // 让用户能看到 key 的开头（如 sk-… / ms-…），而不是末尾。
    var apiKey by remember {
        mutableStateOf(
            TextFieldValue(
                text      = pm.getKey(selectedType.toProviderId()) ?: "",
                selection = TextRange(0),
            )
        )
    }
    var keyVisible by remember { mutableStateOf(false) }

    // 自定义 URL / Model（仅 CUSTOM 展示）
    var customUrl   by remember { mutableStateOf(pm.getCustomBaseUrl()) }
    var customModel by remember { mutableStateOf(pm.getCustomModel()) }

    // 需要用户填写模型名的平台（火山方舟 Endpoint ID / opencode go 模型名）
    var providerModel by remember { mutableStateOf(pm.getProviderModel(selectedType.toProviderId())) }

    // 下拉菜单
    var dropdownExpanded by remember { mutableStateOf(false) }

    // 测试状态：idle / testing / ok / fail
    var testState by remember { mutableStateOf<TestState>(TestState.Idle) }

    // 当提供商切换时，重新加载已存储的 Key；光标置于首位以显示前缀
    LaunchedEffect(selectedType) {
        val raw = pm.getKey(selectedType.toProviderId()) ?: ""
        apiKey        = TextFieldValue(text = raw, selection = TextRange(0))
        providerModel = pm.getProviderModel(selectedType.toProviderId())
        testState     = TestState.Idle
    }

    Column(modifier = Modifier.padding(horizontal = Spacing.screenHorizontal)) {
        // 分组标题
        Text(
            text     = "AI 配置",
            style    = type.label.copy(fontWeight = FontWeight.Medium),
            color    = colors.textSecondary,
            modifier = Modifier.padding(start = Spacing.xs, bottom = Spacing.xs),
        )

        WorldCard(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                // ── 提供商选择器 ─────────────────────────────────
                Text(
                    text  = "提供商",
                    style = type.label,
                    color = colors.textSecondary,
                )
                Box {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Radius.sm))
                            .background(colors.bgElevated)
                            .clickable { dropdownExpanded = true }
                            .padding(horizontal = Spacing.md, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text  = selectedType.displayName,
                            style = type.body,
                            color = colors.textPrimary,
                        )
                        Icon(
                            imageVector        = Icons.Outlined.KeyboardArrowDown,
                            contentDescription = "展开",
                            tint               = colors.textSecondary,
                            modifier           = Modifier.size(20.dp),
                        )
                    }
                    DropdownMenu(
                        expanded         = dropdownExpanded,
                        onDismissRequest = { dropdownExpanded = false },
                        modifier         = Modifier.background(
                            if (colors.isDark) colors.bgCard else Palette.White
                        ),
                    ) {
                        ProviderType.entries.forEach { pt ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text  = pt.displayName,
                                        style = type.body,
                                        color = if (pt == selectedType) colors.accent
                                                else colors.textPrimary,
                                    )
                                },
                                trailingIcon = if (pt == selectedType) ({
                                    Icon(
                                        imageVector = Icons.Outlined.Check,
                                        contentDescription = null,
                                        tint     = colors.accent,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }) else null,
                                onClick = {
                                    selectedType     = pt
                                    dropdownExpanded = false
                                    // 保存活跃提供商
                                    pm.saveActiveProviderId(pt.toProviderId())
                                },
                            )
                        }
                    }
                }

                HorizontalDivider(thickness = 0.5.dp, color = colors.border)

                // ── API Key 输入框 ────────────────────────────────
                Text(
                    text  = "API Key",
                    style = type.label,
                    color = colors.textSecondary,
                )
                OutlinedTextField(
                    value         = apiKey,
                    onValueChange = { new ->
                        // 粘贴检测：文本长度一次跳增超过 1 字符视为粘贴。
                        // 粘贴后将光标重置到首位，让用户看到 key 前缀而非末尾。
                        val isPaste = new.text.length - apiKey.text.length > 1
                        apiKey = if (isPaste) new.copy(selection = TextRange(0)) else new
                    },
                    modifier      = Modifier.fillMaxWidth(),
                    singleLine    = true,
                    placeholder   = {
                        Text(
                            text  = "sk-…",
                            style = type.body,
                            color = colors.textDisabled,
                        )
                    },
                    visualTransformation = if (keyVisible)
                        VisualTransformation.None
                    else
                        PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        Text(
                            text  = if (keyVisible) "隐藏" else "显示",
                            style = type.caption,
                            color = colors.accent,
                            modifier = Modifier
                                .clickable { keyVisible = !keyVisible }
                                .padding(end = 8.dp),
                        )
                    },
                    textStyle = type.body.copy(color = colors.textPrimary),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = colors.accent,
                        unfocusedBorderColor = colors.border,
                        cursorColor          = colors.accent,
                    ),
                    shape = RoundedCornerShape(Radius.sm),
                )

                // ── 火山方舟 / opencode go：模型名输入框 ──────────
                val needsModelInput = selectedType == ProviderType.VOLCENGINE ||
                                      selectedType == ProviderType.OPENCODEGO
                if (needsModelInput) {
                    HorizontalDivider(thickness = 0.5.dp, color = colors.border)

                    val modelHint = if (selectedType == ProviderType.VOLCENGINE)
                        "接入点 ID（ep-xxxxxxxx-xxxxx）"
                    else
                        "模型名称（如 deepseek-v3）"

                    Text(
                        text  = modelHint,
                        style = type.label,
                        color = colors.textSecondary,
                    )
                    OutlinedTextField(
                        value         = providerModel,
                        onValueChange = { providerModel = it },
                        modifier      = Modifier.fillMaxWidth(),
                        singleLine    = true,
                        placeholder   = {
                            Text(
                                text  = modelHint,
                                style = type.body,
                                color = colors.textDisabled,
                            )
                        },
                        textStyle = type.body.copy(color = colors.textPrimary),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor   = colors.accent,
                            unfocusedBorderColor = colors.border,
                            cursorColor          = colors.accent,
                        ),
                        shape = RoundedCornerShape(Radius.sm),
                    )
                }

                // ── 自定义 Base URL / Model（CUSTOM 专属）────────
                if (selectedType == ProviderType.CUSTOM) {
                    HorizontalDivider(thickness = 0.5.dp, color = colors.border)

                    Text(
                        text  = "Base URL",
                        style = type.label,
                        color = colors.textSecondary,
                    )
                    OutlinedTextField(
                        value         = customUrl,
                        onValueChange = { customUrl = it },
                        modifier      = Modifier.fillMaxWidth(),
                        singleLine    = true,
                        placeholder   = {
                            Text(
                                text  = "https://your-endpoint.com",
                                style = type.body,
                                color = colors.textDisabled,
                            )
                        },
                        textStyle = type.body.copy(color = colors.textPrimary),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor   = colors.accent,
                            unfocusedBorderColor = colors.border,
                            cursorColor          = colors.accent,
                        ),
                        shape = RoundedCornerShape(Radius.sm),
                    )

                    Text(
                        text  = "模型名称",
                        style = type.label,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(top = Spacing.xs),
                    )
                    OutlinedTextField(
                        value         = customModel,
                        onValueChange = { customModel = it },
                        modifier      = Modifier.fillMaxWidth(),
                        singleLine    = true,
                        placeholder   = {
                            Text(
                                text  = "gpt-4o / deepseek-chat / …",
                                style = type.body,
                                color = colors.textDisabled,
                            )
                        },
                        textStyle = type.body.copy(color = colors.textPrimary),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor   = colors.accent,
                            unfocusedBorderColor = colors.border,
                            cursorColor          = colors.accent,
                        ),
                        shape = RoundedCornerShape(Radius.sm),
                    )
                }

                HorizontalDivider(thickness = 0.5.dp, color = colors.border)

                // ── 操作行：保存 + 测试连接 ──────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    // 保存按钮
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(Radius.sm))
                            .background(colors.bgElevated)
                            .clickable {
                                val id = selectedType.toProviderId()
                                pm.saveActiveProviderId(id)
                                pm.saveKey(id, apiKey.text.trim())
                                if (selectedType == ProviderType.CUSTOM) {
                                    pm.saveCustomBaseUrl(customUrl.trim())
                                    pm.saveCustomModel(customModel.trim())
                                }
                                // 火山方舟 / opencode go：保存模型名/接入点 ID
                                if (selectedType == ProviderType.VOLCENGINE ||
                                    selectedType == ProviderType.OPENCODEGO) {
                                    pm.saveProviderModel(id, providerModel.trim())
                                }
                                // Fix-13-18：通过 ProviderManager 的配置变更监听器（Phase 3 改为
                                // addOnProviderConfigChangedListener 多订阅者模式）
                                // 已在 ZaijianApp.onCreate 中注册自动装配，无需再手动调用。
                                testState = TestState.Saved
                            }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text  = "保存",
                            style = type.body,
                            color = colors.textPrimary,
                        )
                    }

                    // 测试连接按钮
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(Radius.sm))
                            .background(colors.accent)
                            .clickable(enabled = testState !is TestState.Testing) {
                                // 先保存再测试（与"保存"按钮保持一致）：
                                // 火山方舟/opencode go 的 defaultModel 必须先落盘，
                                // 否则 pm.activeProvider 读到的是上次保存的旧值（或空），
                                // testConnection() 会因 defaultModel 为空而"正确地"返回 false，
                                // 但对用户来说看起来像是"配置出错了"。
                                val id = selectedType.toProviderId()
                                pm.saveActiveProviderId(id)
                                pm.saveKey(id, apiKey.text.trim())
                                if (selectedType == ProviderType.CUSTOM) {
                                    pm.saveCustomBaseUrl(customUrl.trim())
                                    pm.saveCustomModel(customModel.trim())
                                }
                                if (selectedType == ProviderType.VOLCENGINE ||
                                    selectedType == ProviderType.OPENCODEGO) {
                                    pm.saveProviderModel(id, providerModel.trim())
                                }
                                testState = TestState.Testing
                                scope.launch {
                                    val provider = pm.activeProvider
                                    testState = if (provider != null && provider.testConnection()) {
                                        TestState.Ok
                                    } else {
                                        TestState.Fail
                                    }
                                }
                            }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        when (testState) {
                            is TestState.Testing ->
                                CircularProgressIndicator(
                                    modifier  = Modifier.size(18.dp),
                                    color     = Color.White,
                                    strokeWidth = 2.dp,
                                )
                            else ->
                                Text(
                                    text  = "测试连接",
                                    style = type.body,
                                    color = Color.White,
                                )
                        }
                    }
                }

                // ── 测试结果提示 ──────────────────────────────────
                when (testState) {
                    is TestState.Ok ->
                        StatusHint(text = "✓ 连接成功，可以开始对话了", color = Palette.SemanticSuccess)
                    is TestState.Fail ->
                        StatusHint(text = "✗ 连接失败，请检查 Key 或网络", color = Palette.SemanticError)
                    is TestState.Saved ->
                        StatusHint(text = "已保存", color = colors.textSecondary)
                    else -> Spacer(Modifier.height(0.dp))
                }
            }
        }
    }
}


private sealed class TestState {
    object Idle    : TestState()
    object Testing : TestState()
    object Ok      : TestState()
    object Fail    : TestState()
    object Saved   : TestState()
}


@Composable
private fun StatusHint(text: String, color: Color) {
    val type = ZaijianTheme.typography
    Text(
        text     = text,
        style    = type.caption,
        color    = color,
        modifier = Modifier.padding(top = 2.dp),
    )
}
