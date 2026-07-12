package com.zaijian.zhoumuyun.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.zaijian.zhoumuyun.data.datastore.EmailAccount
import com.zaijian.zhoumuyun.data.datastore.EmailAccountStore
import com.zaijian.zhoumuyun.data.datastore.EmailProvider
import com.zaijian.zhoumuyun.data.datastore.EmailTestResult
import com.zaijian.zhoumuyun.data.datastore.GithubConfig
import com.zaijian.zhoumuyun.data.datastore.GithubConfigDataStore
import com.zaijian.zhoumuyun.data.datastore.GithubTestResult
import com.zaijian.zhoumuyun.ui.design.WorldCard
import com.zaijian.zhoumuyun.ui.theme.Palette
import com.zaijian.zhoumuyun.ui.theme.Radius
import com.zaijian.zhoumuyun.ui.theme.Spacing
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ─────────────────────────────────────────────────────────────
//  IntegrationsSection — GitHub / 邮箱 集成配置
//
//  背景：CreateGithubRepoTool / GitCommitPushTool / BuildApkTool 等 CI/CD
//  工具、EmailSendTool / EmailFetchTool 等邮件工具，此前只有"读配置"的
//  后端代码（GithubConfigDataStore / EmailAccountStore），全项目没有任何
//  UI 入口写入 owner/repo/token 或邮箱地址/授权码——用户无从配置，工具
//  自然"完全用不了"。本文件补齐这两块缺失的设置界面，交互模式与已有
//  AiConfigSection 保持一致（同一套 WorldCard + OutlinedTextField + 保存/
//  测试连接 双按钮 + StatusHint 视觉语言），降低用户学习成本。
// ─────────────────────────────────────────────────────────────

private sealed class ConnTestState {
    object Idle    : ConnTestState()
    object Testing : ConnTestState()
    object Ok      : ConnTestState()
    data class Fail(val reason: String) : ConnTestState()
    object Saved   : ConnTestState()
}

@Composable
internal fun IntegrationsSection() {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    Column(modifier = Modifier.padding(horizontal = Spacing.screenHorizontal)) {
        Text(
            text     = "集成",
            style    = type.label.copy(fontWeight = FontWeight.Medium),
            color    = colors.textSecondary,
            modifier = Modifier.padding(start = Spacing.xs, bottom = Spacing.xs),
        )

        GithubConfigCard()
        Spacer(Modifier.height(Spacing.sm))
        EmailConfigCard()
    }
}

// ─────────────────────────────────────────────────────────────
//  GitHub 配置卡片
// ─────────────────────────────────────────────────────────────

@Composable
private fun GithubConfigCard() {
    val colors  = ZaijianTheme.colors
    val type    = ZaijianTheme.typography
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()
    val store   = remember { GithubConfigDataStore(context) }

    var owner by remember { mutableStateOf("") }
    var repo  by remember { mutableStateOf("") }
    var token by remember {
        mutableStateOf(TextFieldValue(text = "", selection = TextRange(0)))
    }
    var tokenVisible by remember { mutableStateOf(false) }
    var testState by remember { mutableStateOf<ConnTestState>(ConnTestState.Idle) }
    var loaded by remember { mutableStateOf(false) }

    // 首次进入时从加密存储加载已有配置（getConfig 内部已 withContext(IO)）
    LaunchedEffect(Unit) {
        val cfg = store.getConfig()
        owner = cfg.owner
        repo  = cfg.repo
        token = TextFieldValue(text = cfg.token, selection = TextRange(0))
        loaded = true
    }

    IntegrationCard(
        icon        = Icons.Outlined.Code,
        title       = "GitHub",
        subtitle    = "用于 CI/CD：建仓库、提交推送、触发编译、下载 APK",
        expandedByDefault = false,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            LabeledField(
                label = "Owner（用户名或组织名）",
                value = owner,
                onValueChange = { owner = it },
                placeholder = "your-github-username",
            )

            LabeledField(
                label = "Repo（仓库名）",
                value = repo,
                onValueChange = { repo = it },
                placeholder = "your-repo-name",
            )

            HorizontalDivider(thickness = 0.5.dp, color = colors.border)

            Text(text = "Personal Access Token", style = type.label, color = colors.textSecondary)
            OutlinedTextField(
                value = token,
                onValueChange = { new ->
                    val isPaste = new.text.length - token.text.length > 1
                    token = if (isPaste) new.copy(selection = TextRange(0)) else new
                },
                modifier   = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = {
                    Text(text = "ghp_…", style = type.body, color = colors.textDisabled)
                },
                visualTransformation = if (tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    Text(
                        text  = if (tokenVisible) "隐藏" else "显示",
                        style = type.caption,
                        color = colors.accent,
                        modifier = Modifier
                            .clickable { tokenVisible = !tokenVisible }
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
            Text(
                text  = "在 GitHub → Settings → Developer settings → Personal access tokens 生成，" +
                    "需勾选 repo 权限（若要建仓库还需 admin:org 或对应权限）",
                style = type.caption,
                color = colors.textDisabled,
            )

            HorizontalDivider(thickness = 0.5.dp, color = colors.border)

            SaveTestRow(
                testState = testState,
                onSave = {
                    scope.launch {
                        store.saveConfig(GithubConfig(owner = owner, repo = repo, token = token.text))
                        testState = ConnTestState.Saved
                    }
                },
                onTest = {
                    scope.launch {
                        store.saveConfig(GithubConfig(owner = owner, repo = repo, token = token.text))
                        testState = ConnTestState.Testing
                        val cfg = store.getConfig()
                        testState = when (val result = store.testConnection(cfg)) {
                            is GithubTestResult.Success -> ConnTestState.Ok
                            is GithubTestResult.Failure -> ConnTestState.Fail(result.reason)
                        }
                    }
                },
            )

            StatusHintForTest(testState)
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  邮箱配置卡片
// ─────────────────────────────────────────────────────────────

@Composable
private fun EmailConfigCard() {
    val colors  = ZaijianTheme.colors
    val type    = ZaijianTheme.typography
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()
    val store   = remember { EmailAccountStore(context) }

    var address by remember { mutableStateOf("") }
    var authCode by remember {
        mutableStateOf(TextFieldValue(text = "", selection = TextRange(0)))
    }
    var codeVisible by remember { mutableStateOf(false) }
    var testState by remember { mutableStateOf<ConnTestState>(ConnTestState.Idle) }

    // getAccount() 是同步 EncryptedSharedPreferences 读取，切到 IO 线程避免阻塞主线程
    LaunchedEffect(Unit) {
        val account = withContext(Dispatchers.IO) { store.getAccount() }
        address  = account.address
        authCode = TextFieldValue(text = account.authCode, selection = TextRange(0))
    }

    IntegrationCard(
        icon        = Icons.Outlined.Email,
        title       = "邮箱（QQ邮箱）",
        subtitle    = "用于角色真实收发邮件（email_send / email_fetch）",
        expandedByDefault = false,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            LabeledField(
                label = "邮箱地址",
                value = address,
                onValueChange = { address = it },
                placeholder = "yourname@qq.com",
                keyboardType = KeyboardType.Email,
            )

            HorizontalDivider(thickness = 0.5.dp, color = colors.border)

            Text(text = "授权码（非 QQ 登录密码）", style = type.label, color = colors.textSecondary)
            OutlinedTextField(
                value = authCode,
                onValueChange = { new ->
                    val isPaste = new.text.length - authCode.text.length > 1
                    authCode = if (isPaste) new.copy(selection = TextRange(0)) else new
                },
                modifier   = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = {
                    Text(text = "16 位授权码", style = type.body, color = colors.textDisabled)
                },
                visualTransformation = if (codeVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    Text(
                        text  = if (codeVisible) "隐藏" else "显示",
                        style = type.caption,
                        color = colors.accent,
                        modifier = Modifier
                            .clickable { codeVisible = !codeVisible }
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
            Text(
                text  = "QQ邮箱网页版 → 设置 → 账户 → POP3/IMAP/SMTP服务 → 开启服务后生成授权码。" +
                    "若此前用过本项目的历史交付版本，建议在此重新生成一个新授权码，使旧授权码失效。",
                style = type.caption,
                color = colors.textDisabled,
            )

            HorizontalDivider(thickness = 0.5.dp, color = colors.border)

            SaveTestRow(
                testState = testState,
                onSave = {
                    scope.launch(Dispatchers.IO) {
                        store.saveAccount(
                            EmailAccount(provider = EmailProvider.QQ, address = address, authCode = authCode.text)
                        )
                        testState = ConnTestState.Saved
                    }
                },
                onTest = {
                    scope.launch {
                        val account = EmailAccount(provider = EmailProvider.QQ, address = address, authCode = authCode.text)
                        withContext(Dispatchers.IO) { store.saveAccount(account) }
                        testState = ConnTestState.Testing
                        testState = when (val result = store.testConnection(account)) {
                            is EmailTestResult.Success -> ConnTestState.Ok
                            is EmailTestResult.Failure -> ConnTestState.Fail(result.reason)
                        }
                    }
                },
            )

            StatusHintForTest(testState)
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  共享子组件
// ─────────────────────────────────────────────────────────────

@Composable
private fun IntegrationCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    expandedByDefault: Boolean,
    content: @Composable () -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography
    var expanded by remember { mutableStateOf(expandedByDefault) }

    WorldCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(Spacing.md)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(Radius.xs))
                            .background(colors.bgElevated),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = colors.accent,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Spacer(Modifier.width(Spacing.sm))
                    Column {
                        Text(text = title, style = type.body.copy(fontWeight = FontWeight.Medium), color = colors.textPrimary)
                        Text(text = subtitle, style = type.caption, color = colors.textSecondary)
                    }
                }
                Icon(
                    imageVector = if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = colors.textSecondary,
                    modifier = Modifier.size(20.dp),
                )
            }

            if (expanded) {
                Spacer(Modifier.height(Spacing.sm))
                content()
            }
        }
    }
}

@Composable
private fun LabeledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    Text(text = label, style = type.label, color = colors.textSecondary)
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier   = Modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text(text = placeholder, style = type.body, color = colors.textDisabled) },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        textStyle = type.body.copy(color = colors.textPrimary),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor   = colors.accent,
            unfocusedBorderColor = colors.border,
            cursorColor          = colors.accent,
        ),
        shape = RoundedCornerShape(Radius.sm),
    )
}

@Composable
private fun SaveTestRow(
    testState: ConnTestState,
    onSave: () -> Unit,
    onTest: () -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(Radius.sm))
                .background(colors.bgElevated)
                .clickable(enabled = testState !is ConnTestState.Testing) { onSave() }
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "保存", style = type.body, color = colors.textPrimary)
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(Radius.sm))
                .background(colors.accent)
                .clickable(enabled = testState !is ConnTestState.Testing) { onTest() }
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (testState is ConnTestState.Testing) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
            } else {
                Text(text = "测试连接", style = type.body, color = Color.White)
            }
        }
    }
}

@Composable
private fun StatusHintForTest(state: ConnTestState) {
    val colors = ZaijianTheme.colors
    val type   = ZaijianTheme.typography

    when (state) {
        is ConnTestState.Ok ->
            Text(
                text = "✓ 连接成功", style = type.caption, color = Palette.SemanticSuccess,
                modifier = Modifier.padding(top = 2.dp),
            )
        is ConnTestState.Fail ->
            Text(
                text = "✗ ${state.reason}", style = type.caption, color = Palette.SemanticError,
                modifier = Modifier.padding(top = 2.dp),
            )
        is ConnTestState.Saved ->
            Text(
                text = "已保存", style = type.caption, color = colors.textSecondary,
                modifier = Modifier.padding(top = 2.dp),
            )
        else -> Spacer(Modifier.height(0.dp))
    }
}
