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
import com.zaijian.zhoumuyun.ui.theme.Spacing
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
                .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
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
                    .padding(horizontal = Spacing.screenHorizontal),
                textStyle = type.labelMono.copy(fontFamily = FontFamily.Monospace),
            )
            Button(
                onClick = { onSave(editSource) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Spacing.md),
            ) {
                Text("保存")
            }
        } else {
            // WebView 渲染
            // Fix-预览闪退（html 专属风险排查）：WebView() 构造函数在部分设备上
            // （未安装/被冻结/被卸载了系统 WebView 组件，国产 ROM 上并不罕见）
            // 会直接抛出 MissingWebViewPackageException，这与 html 文件内容本身
            // 无关，是设备环境问题——但同样会让"打开预览"这个动作直接崩溃退出。
            // 用 try-catch 兜底：构造失败就退化成显示源码的纯文本视图。
            AndroidView(
                factory = { ctx ->
                    try {
                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            webViewClient = WebViewClient()
                            settings.javaScriptEnabled = false
                        }
                    } catch (e: Throwable) {
                        com.zaijian.zhoumuyun.util.ZLog.e(
                            "HtmlPreviewEditor",
                            "WebView 初始化失败，设备可能未安装/禁用了 WebView 组件",
                            e,
                        )
                        android.widget.TextView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            text = "当前设备无法渲染网页预览（缺少 WebView 组件），以下是源码：\n\n" + source
                            setPadding(24, 24, 24, 24)
                        }
                    }
                },
                update = { view ->
                    // factory 失败时会返回 TextView，只有真的拿到 WebView 才继续加载，
                    // 避免类型不匹配再次触发异常。
                    if (view is WebView) {
                        try {
                            view.loadDataWithBaseURL(null, source, "text/html", "UTF-8", null)
                        } catch (e: Throwable) {
                            com.zaijian.zhoumuyun.util.ZLog.e("HtmlPreviewEditor", "加载 HTML 内容失败", e)
                        }
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )
        }
    }
}
