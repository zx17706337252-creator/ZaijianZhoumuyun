package com.zaijian.zhoumuyun.ui.screen.filepreview

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme

/**
 * HTML 渲染器（v1.48 应用内预览编辑）。
 *
 * 预览模式用 WebView 渲染，编辑模式编辑源码。
 *
 * @param source HTML 源码
 * @param editable true=显示编辑按钮
 * @param onSave 保存回调
 */
@Composable
internal fun HtmlPreviewEditor(
    source: String,
    editable: Boolean,
    onSave: (String) -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type = ZaijianTheme.typography
    var isEditing by remember { mutableStateOf(false) }
    var editSource by remember(source) { mutableStateOf(source) }

    Column(modifier = Modifier.fillMaxSize()) {
        // 模式切换 Tab
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = !isEditing,
                onClick = { isEditing = false },
                label = { Text("渲染") },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = colors.accent.copy(alpha = 0.15f),
                    selectedLabelColor = colors.accent,
                ),
            )
            if (editable) {
                FilterChip(
                    selected = isEditing,
                    onClick = { isEditing = true },
                    label = { Text("源码") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = colors.accent.copy(alpha = 0.15f),
                        selectedLabelColor = colors.accent,
                    ),
                )
            }
        }

        if (isEditing && editable) {
            // 源码编辑
            OutlinedTextField(
                value = editSource,
                onValueChange = { editSource = it },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                textStyle = type.labelMono.copy(fontFamily = FontFamily.Monospace),
            )
            Button(
                onClick = { onSave(editSource) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                Text("保存")
            }
        } else {
            // WebView 渲染
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        webViewClient = WebViewClient()
                        settings.javaScriptEnabled = false
                    }
                },
                update = { webView ->
                    webView.loadDataWithBaseURL(null, source, "text/html", "UTF-8", null)
                },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )
        }
    }
}
