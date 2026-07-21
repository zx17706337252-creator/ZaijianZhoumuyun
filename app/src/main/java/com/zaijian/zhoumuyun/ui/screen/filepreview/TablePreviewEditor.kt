package com.zaijian.zhoumuyun.ui.screen.filepreview

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zaijian.zhoumuyun.ui.theme.ZaijianTheme

/**
 * 表格类渲染器（v1.48 应用内预览编辑）。
 *
 * csv 可编辑（单元格 BasicTextField），xlsx 只读。
 * 横向+纵向滚动，表头行固定。
 *
 * @param columns 列头
 * @param rows 数据行
 * @param editable true=csv 可编辑，false=xlsx 只读
 * @param onSave 保存回调（editable=true 时显示保存按钮）
 */
@Composable
internal fun TablePreviewEditor(
    columns: List<String>,
    rows: List<List<String>>,
    editable: Boolean,
    onSave: (List<String>, List<List<String>>) -> Unit,
) {
    val colors = ZaijianTheme.colors
    val type = ZaijianTheme.typography
    val horizontalScroll = rememberScrollState()

    // 可编辑状态：深拷贝一份，避免直接改入参
    var editColumns by remember(columns) { mutableStateOf(columns.toList()) }
    var editRows by remember(rows) {
        mutableStateOf(rows.map { it.toMutableList() })
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 工具栏：行数统计
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "共 ${rows.size} 行 × ${columns.size} 列",
                style = type.label,
                color = colors.textSecondary,
            )
            if (editable) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        editRows = editRows.toMutableList().apply { add(MutableList(columns.size) { "" }) }
                    }) {
                        Text("+ 行")
                    }
                    TextButton(onClick = {
                        if (editRows.isNotEmpty()) editRows = editRows.dropLast(1)
                    }) {
                        Text("- 行")
                    }
                }
            }
            if (!editable) {
                Text(
                    text = "xlsx 只读",
                    style = type.label,
                    color = colors.textDisabled,
                )
            }
        }

        // 表格区：横向 + 纵向滚动
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .horizontalScroll(horizontalScroll),
            ) {
                // 表头行
                Row(modifier = Modifier.fillMaxWidth()) {
                    columns.forEachIndexed { colIdx, colName ->
                        val displayCol = if (editable) editColumns.getOrNull(colIdx) ?: colName else colName
                        Box(
                            modifier = Modifier
                                .width(120.dp)
                                .padding(8.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            if (editable) {
                                OutlinedTextField(
                                    value = displayCol,
                                    onValueChange = { newVal ->
                                        editColumns = editColumns.toMutableList().also { list ->
                                            if (colIdx < list.size) list[colIdx] = newVal
                                        }
                                    },
                                    textStyle = type.labelMono,
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            } else {
                                Text(
                                    text = colName,
                                    style = type.labelMono,
                                    color = colors.accent,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
                HorizontalDivider(color = colors.borderSubtle)

                // 数据行
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    itemsIndexed(
                        items = if (editable) editRows else rows,
                        key = { idx, _ -> idx },
                    ) { rowIdx, row ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            for (colIdx in columns.indices) {
                                val cellValue = row.getOrNull(colIdx) ?: ""
                                Box(
                                    modifier = Modifier
                                        .width(120.dp)
                                        .padding(4.dp),
                                    contentAlignment = Alignment.CenterStart,
                                ) {
                                    if (editable) {
                                        OutlinedTextField(
                                            value = cellValue,
                                            onValueChange = { newVal ->
                                                editRows = editRows.toMutableList().also { rowList ->
                                                    if (rowIdx < rowList.size) {
                                                        rowList[rowIdx] = rowList[rowIdx].toMutableList().also { cells ->
                                                            if (colIdx < cells.size) cells[colIdx] = newVal
                                                        }
                                                    }
                                                }
                                            },
                                            textStyle = type.labelMono,
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    } else {
                                        Text(
                                            text = cellValue,
                                            style = type.labelMono,
                                            color = colors.textPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                        HorizontalDivider(color = colors.borderSubtle.copy(alpha = 0.5f))
                    }
                }
            }
        }

        // 保存按钮（仅可编辑模式）
        if (editable) {
            Button(
                onClick = { onSave(editColumns, editRows.map { it.toList() }) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                Text("保存")
            }
        }
    }
}
