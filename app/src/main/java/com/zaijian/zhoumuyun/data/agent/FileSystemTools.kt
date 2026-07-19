package com.zaijian.zhoumuyun.data.agent

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * FileSystemTools.kt
 *
 * ═══════════════════════════════════════════════════════════════
 * 文件系统操作工具（8个）
 * ═══════════════════════════════════════════════════════════════
 *
 * 工具列表：
 *   ① FolderCreateTool — 创建文件夹（folder_create）
 *   ② FolderDeleteTool — 删除文件夹（folder_delete）
 *   ③ FileRenameTool   — 重命名文件/文件夹（file_rename）
 *   ④ FileEditTool     — 编辑已有文件内容（file_edit）
 *   ⑤ FileDeleteTool   — 删除单个文件（file_delete）
 *   ⑥ ZipExtractTool   — 解压 ZIP 到目录（zip_extract）
 *   ⑦ ZipCreateTool    — 创建 ZIP 压缩包（zip_create）
 *   ⑧ FileOrganizeTool — 整理文件/文件夹排序（file_organize）
 *
 * 安全边界：与 BuiltinTools.kt 的 FileReadTool.resolveFile() 完全一致——
 * 仅允许操作 context.filesDir / context.cacheDir / context.getExternalFilesDir(null)
 * 三个目录范围内的路径，拒绝 "../" 路径穿越，不允许触碰范围外的任意文件系统位置。
 *
 * 注册方式（在 ZaijianApp.onCreate 中）：
 * ```kotlin
 * AgentToolRegistry.registerAll(
 *     FolderCreateTool(context),
 *     FolderDeleteTool(context),
 *     FileRenameTool(context),
 *     FileEditTool(context),
 * )
 * ```
 * ═══════════════════════════════════════════════════════════════
 */

// ─────────────────────────────────────────────────────────────
//  共享：路径安全解析（与 FileReadTool.resolveFile 同一套规则）
// ─────────────────────────────────────────────────────────────

/**
 * 校验路径是否包含穿越字符。两种写法都拦：Unix 的 "../" 和 Windows 的 "..\\"。
 *
 * 可见性说明（第3窗口审查报告问题3）：原为 private（文件内可见），
 * 现改为 internal（模块内可见），供 DataVisTools.kt 的 CsvAnalyzeTool 复用，
 * 避免路径穿越校验逻辑在多个 Tool 文件中重复实现、标准不一致。
 */
internal fun hasPathTraversal(path: String): Boolean {
    if (path.contains("../") || path.contains("..\\")) return true
    // 额外拦截：单独的 ".." 段（如路径整体就是 ".." 或以 ".." 收尾、无尾随斜杠时，
    // 上面两个 contains 检查不到，按分隔符拆段后逐段比对补齐）
    val segments = path.split("/", "\\")
    return segments.any { it == ".." }
}

/**
 * 将相对/绝对路径解析为允许范围内的 File。
 *
 * 规则（与 FileReadTool 一致）：
 * - 绝对路径：必须以 filesDir/cacheDir/externalFilesDir 三者之一为前缀，否则拒绝。
 * - 相对路径：依次尝试 filesDir、externalFilesDir 下的同名路径。
 *   与 FileReadTool 不同的一点——这里不要求 file.exists()，因为创建类操作
 *   (folder_create / file_rename 的目标路径) 本身允许指向"还不存在的路径"，
 *   exists() 校验交给各工具自己按语义判断（创建前不存在才对，删除前必须存在）。
 *
 * @return 解析后的 File；路径不合法（穿越、超出允许范围）时返回 null。
 */
internal fun resolveFileSystemPath(context: Context, path: String): File? {
    if (hasPathTraversal(path)) return null

    if (path.startsWith("/")) {
        val file = File(path)
        val allowed = listOf(
            context.filesDir.absolutePath,
            context.cacheDir.absolutePath,
            context.getExternalFilesDir(null)?.absolutePath ?: "",
        )
        if (allowed.none { path.startsWith(it) }) return null
        return file
    }

    // 相对路径：默认落在 filesDir 下（与 NoteSaveTool/ReminderTool 的私有目录习惯一致）。
    return File(context.filesDir, path)
}

// ─────────────────────────────────────────────────────────────
//  ① FolderCreateTool
// ─────────────────────────────────────────────────────────────

/**
 * 创建文件夹工具。
 *
 * 标签格式：<tool:folder_create path="笔记/旅行计划"/>
 *
 * 行为：
 * - 支持多级路径一次性创建（如 "a/b/c"，内部用 mkdirs()，与 NoteSaveTool 的
 *   notes/ 目录创建方式一致）。
 * - 路径已存在且是文件夹：视为成功（幂等），不报错，提示"文件夹已存在"。
 * - 路径已存在但是文件（非文件夹）：返回失败，避免覆盖用户已有文件。
 */
class FolderCreateTool(private val context: Context) : AgentTool {

    override val name = "folder_create"
    override val description = "创建文件夹（支持多级路径一次性创建），路径已存在则视为成功"
    override val paramKeys = listOf("path")

    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val path = params["path"]?.trim()
        if (path.isNullOrEmpty()) {
            return@withContext ToolResult(name, false, "", "缺少 path 参数")
        }
        if (hasPathTraversal(path)) {
            return@withContext ToolResult(name, false, "无法创建该路径。", "路径包含非法字符")
        }

        val folder = resolveFileSystemPath(context, path)
            ?: return@withContext ToolResult(name, false, "无法创建该路径。", "路径超出允许范围")

        try {
            if (folder.exists()) {
                if (folder.isDirectory) {
                    return@withContext ToolResult(
                        toolName = name,
                        success  = true,
                        content  = "[文件夹已存在]\n路径：$path",
                    )
                }
                return@withContext ToolResult(
                    name, false, "「$path」已存在，但是一个文件，不是文件夹，未执行创建。",
                )
            }

            val created = folder.mkdirs()
            if (!created) {
                return@withContext ToolResult(name, false, "创建文件夹「$path」失败。")
            }

            ToolResult(
                toolName = name,
                success  = true,
                content  = "[文件夹已创建]\n路径：$path",
                userHint = "正在创建文件夹…",
            )
        } catch (e: Exception) {
            ToolResult(name, false, "创建文件夹时遇到问题：${e.message?.take(80)}", e.message)
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  ② FolderDeleteTool
// ─────────────────────────────────────────────────────────────

/**
 * 删除文件夹工具。
 *
 * 标签格式：<tool:folder_delete path="笔记/旧计划" recursive="true"/>
 *
 * 行为：
 * - recursive 缺省为 "false"：文件夹非空时拒绝删除，避免误删大量数据。
 * - recursive="true"：递归删除文件夹及其全部内容，**不可恢复**，
 *   调用方（角色 Prompt 层）应在标签生成前已通过对话确认用户意图，
 *   工具本身不做二次确认（与 clearMessages() 的"前端先弹确认框，工具层
 *   直接执行"模式一致——确认动作发生在 UI/对话层，不发生在工具内部）。
 * - 路径不存在：返回失败但 success 标记需调用方判断是否当作"已经不存在=目标达成"，
 *   这里统一按"找不到就是失败"处理，避免静默吞掉用户预期之外的情况。
 */
class FolderDeleteTool(private val context: Context) : AgentTool {

    override val name = "folder_delete"
    override val description = "删除文件夹，默认非空拒绝删除，recursive=true才递归删除且不可恢复"
    override val paramKeys = listOf("path", "recursive")

    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val path = params["path"]?.trim()
        if (path.isNullOrEmpty()) {
            return@withContext ToolResult(name, false, "", "缺少 path 参数")
        }
        if (hasPathTraversal(path)) {
            return@withContext ToolResult(name, false, "无法删除该路径。", "路径包含非法字符")
        }
        val recursive = params["recursive"]?.trim()?.lowercase() == "true"

        val folder = resolveFileSystemPath(context, path)
            ?: return@withContext ToolResult(name, false, "无法删除该路径。", "路径超出允许范围")

        try {
            if (!folder.exists()) {
                return@withContext ToolResult(name, false, "找不到文件夹「$path」。")
            }
            if (!folder.isDirectory) {
                return@withContext ToolResult(name, false, "「$path」是一个文件，不是文件夹，请使用其他方式删除。")
            }

            val children = folder.listFiles()
            if (!children.isNullOrEmpty() && !recursive) {
                return@withContext ToolResult(
                    toolName = name,
                    success  = false,
                    content  = "「$path」不是空文件夹（包含 ${children.size} 项），未执行删除。" +
                        "如需连同内容一起删除，请明确说明。",
                )
            }

            val ok = if (recursive) folder.deleteRecursively() else folder.delete()
            if (!ok) {
                return@withContext ToolResult(name, false, "删除文件夹「$path」失败。")
            }

            ToolResult(
                toolName = name,
                success  = true,
                content  = "[文件夹已删除]\n路径：$path" +
                    if (recursive && !children.isNullOrEmpty()) "（含其中 ${children.size} 项内容）" else "",
                userHint = "正在删除文件夹…",
            )
        } catch (e: Exception) {
            ToolResult(name, false, "删除文件夹时遇到问题：${e.message?.take(80)}", e.message)
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  ③ FileRenameTool
// ─────────────────────────────────────────────────────────────

/**
 * 重命名/移动文件或文件夹工具。
 *
 * 标签格式：<tool:file_rename from="笔记/旧名.txt" to="笔记/新名.txt"/>
 *
 * 行为：
 * - 对文件和文件夹通用（File.renameTo 本身不区分类型）。
 * - "to" 路径所在的父目录若不存在会自动创建（与用户直觉一致：
 *   重命名/移动到一个新分类文件夹，不应该因为目录不存在而失败）。
 * - "to" 路径已存在：拒绝覆盖，返回失败提示，不静默覆盖用户数据。
 */
class FileRenameTool(private val context: Context) : AgentTool {

    override val name = "file_rename"
    override val description = "重命名或移动文件/文件夹，目标路径已存在时拒绝覆盖"
    override val paramKeys = listOf("from", "to")

    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val from = params["from"]?.trim()
        val to   = params["to"]?.trim()
        if (from.isNullOrEmpty() || to.isNullOrEmpty()) {
            return@withContext ToolResult(name, false, "", "缺少 from 或 to 参数")
        }
        if (hasPathTraversal(from) || hasPathTraversal(to)) {
            return@withContext ToolResult(name, false, "无法重命名该路径。", "路径包含非法字符")
        }

        val source = resolveFileSystemPath(context, from)
            ?: return@withContext ToolResult(name, false, "无法重命名该路径。", "源路径超出允许范围")
        val target = resolveFileSystemPath(context, to)
            ?: return@withContext ToolResult(name, false, "无法重命名该路径。", "目标路径超出允许范围")

        try {
            if (!source.exists()) {
                return@withContext ToolResult(name, false, "找不到「$from」。")
            }
            if (target.exists()) {
                return@withContext ToolResult(
                    name, false, "「$to」已存在，未执行重命名，避免覆盖已有内容。",
                )
            }

            target.parentFile?.let { parent ->
                if (!parent.exists()) parent.mkdirs()
            }

            val ok = source.renameTo(target)
            if (!ok) {
                return@withContext ToolResult(name, false, "重命名「$from」为「$to」失败。")
            }

            ToolResult(
                toolName = name,
                success  = true,
                content  = "[重命名完成]\n原路径：$from\n新路径：$to",
                userHint = "正在重命名…",
            )
        } catch (e: Exception) {
            ToolResult(name, false, "重命名时遇到问题：${e.message?.take(80)}", e.message)
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  ④ FileEditTool
// ─────────────────────────────────────────────────────────────

/**
 * 编辑已有文件内容工具。
 *
 * 标签格式：
 * - 整体覆盖：<tool:file_edit path="笔记/计划.txt" mode="overwrite" content="新内容"/>
 * - 追加内容：<tool:file_edit path="笔记/计划.txt" mode="append" content="追加的内容"/>
 * - 查找替换：<tool:file_edit path="笔记/计划.txt" mode="replace" find="旧文本" content="新文本"/>
 *
 * 行为：
 * - 与现有 NoteSaveTool（只能新建）、FileReadTool（只能读）形成互补——
 *   这是工具层第一个支持"打开已存在文件并修改其内容"的工具。
 * - mode 缺省为 "overwrite"。
 * - replace 模式下 find 文本未在文件中找到：返回失败，不做模糊匹配，
 *   避免在用户没注意到的情况下改错位置。
 * - 仅支持纯文本文件（与 FileReadTool 的 ZIP_TEXT_EXTENSIONS 思路一致），
 *   不处理二进制文件，避免把图片/zip 等文件写坏。
 */
class FileEditTool(private val context: Context) : AgentTool {

    override val name = "file_edit"
    override val description = "编辑已存在文件的内容（整体覆盖/追加/查找替换三种模式）"
    override val paramKeys = listOf("path", "mode", "content", "find")

    private companion object {
        const val MAX_CONTENT_CHARS = 50_000
    }

    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val path = params["path"]?.trim()
        if (path.isNullOrEmpty()) {
            return@withContext ToolResult(name, false, "", "缺少 path 参数")
        }
        if (hasPathTraversal(path)) {
            return@withContext ToolResult(name, false, "无法编辑该路径。", "路径包含非法字符")
        }

        val content = params["content"] ?: ""
        if (content.length > MAX_CONTENT_CHARS) {
            return@withContext ToolResult(
                name, false, "内容过长（超过 $MAX_CONTENT_CHARS 字符），未执行编辑。",
            )
        }

        val mode = params["mode"]?.trim()?.lowercase() ?: "overwrite"
        if (mode !in setOf("overwrite", "append", "replace")) {
            return@withContext ToolResult(name, false, "不支持的 mode「$mode」，请使用 overwrite / append / replace。")
        }

        val file = resolveFileSystemPath(context, path)
            ?: return@withContext ToolResult(name, false, "无法编辑该路径。", "路径超出允许范围")

        try {
            when (mode) {
                "overwrite" -> {
                    file.parentFile?.let { if (!it.exists()) it.mkdirs() }
                    file.writeText(content, Charsets.UTF_8)
                    ToolResult(
                        toolName = name,
                        success  = true,
                        content  = "[文件已覆盖写入]\n路径：$path\n新内容长度：${content.length} 字符",
                        userHint = "正在编辑文件…",
                    )
                }
                "append" -> {
                    if (!file.exists()) {
                        return@withContext ToolResult(name, false, "找不到文件「$path」，无法追加内容。")
                    }
                    if (!file.isFile) {
                        return@withContext ToolResult(name, false, "「$path」是一个目录，无法编辑。")
                    }
                    file.appendText(content, Charsets.UTF_8)
                    ToolResult(
                        toolName = name,
                        success  = true,
                        content  = "[内容已追加]\n路径：$path\n追加长度：${content.length} 字符",
                        userHint = "正在编辑文件…",
                    )
                }
                else -> { // "replace"
                    val find = params["find"]
                    if (find.isNullOrEmpty()) {
                        return@withContext ToolResult(name, false, "", "replace 模式缺少 find 参数")
                    }
                    if (!file.exists()) {
                        return@withContext ToolResult(name, false, "找不到文件「$path」。")
                    }
                    if (!file.isFile) {
                        return@withContext ToolResult(name, false, "「$path」是一个目录，无法编辑。")
                    }
                    val original = file.readText(Charsets.UTF_8)
                    if (!original.contains(find)) {
                        return@withContext ToolResult(
                            name, false, "在「$path」中未找到要替换的内容，未做任何修改。",
                        )
                    }
                    val updated = original.replaceFirst(find, content)
                    file.writeText(updated, Charsets.UTF_8)
                    ToolResult(
                        toolName = name,
                        success  = true,
                        content  = "[内容已替换]\n路径：$path\n已替换匹配到的第一处内容",
                        userHint = "正在编辑文件…",
                    )
                }
            }
        } catch (e: Exception) {
            ToolResult(name, false, "编辑文件时遇到问题：${e.message?.take(80)}", e.message)
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  ⑤ FileDeleteTool
// ─────────────────────────────────────────────────────────────

/**
 * 删除单个文件工具。
 *
 * 标签格式：<tool:file_delete path="笔记/旧文件.txt"/>
 *
 * 行为：
 * - 只删除文件，不删除目录（目录请用 folder_delete）。
 * - 路径不存在：返回失败。
 */
class FileDeleteTool(private val context: Context) : AgentTool {

    override val name = "file_delete"
    override val description = "删除单个文件（不含目录，删目录用folder_delete）"
    override val paramKeys = listOf("path")

    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val path = params["path"]?.trim()
        if (path.isNullOrEmpty()) return@withContext ToolResult(name, false, "", "缺少 path 参数")
        if (hasPathTraversal(path)) return@withContext ToolResult(name, false, "无法删除该路径。", "路径包含非法字符")

        val file = resolveFileSystemPath(context, path)
            ?: return@withContext ToolResult(name, false, "无法删除该路径。", "路径超出允许范围")

        try {
            if (!file.exists()) return@withContext ToolResult(name, false, "找不到文件「$path」。")
            if (!file.isFile) return@withContext ToolResult(name, false, "「$path」是一个目录，请使用 folder_delete。")

            val ok = file.delete()
            if (!ok) return@withContext ToolResult(name, false, "删除文件「$path」失败。")

            ToolResult(name, true, "[文件已删除]\n路径：$path", "正在删除文件…")
        } catch (e: Exception) {
            ToolResult(name, false, "删除文件时遇到问题：${e.message?.take(80)}", e.message)
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  ⑥ ZipExtractTool
// ─────────────────────────────────────────────────────────────

/**
 * 解压 ZIP 文件到指定目录工具。
 *
 * 标签格式：<tool:zip_extract zip="存档.zip" to="输出目录"/>
 *
 * 行为：
 * - zip 路径指向要解压的 .zip 文件
 * - to 指定目标目录（不存在则自动创建）
 * - 仅提取文件，跳过目录条目
 */
class ZipExtractTool(private val context: Context) : AgentTool {

    override val name = "zip_extract"
    override val description = "把ZIP压缩包解压到指定目录"
    override val paramKeys = listOf("zip", "to")

    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val zipPath = params["zip"]?.trim()
        val toPath  = params["to"]?.trim()
        if (zipPath.isNullOrEmpty() || toPath.isNullOrEmpty()) {
            return@withContext ToolResult(name, false, "", "缺少 zip 或 to 参数")
        }
        if (hasPathTraversal(zipPath) || hasPathTraversal(toPath)) {
            return@withContext ToolResult(name, false, "无法解压。", "路径包含非法字符")
        }

        val zipFile = resolveFileSystemPath(context, zipPath)
            ?: return@withContext ToolResult(name, false, "无法解压。", "ZIP 路径超出允许范围")
        val targetDir = resolveFileSystemPath(context, toPath)
            ?: return@withContext ToolResult(name, false, "无法解压。", "目标路径超出允许范围")

        try {
            if (!zipFile.exists()) return@withContext ToolResult(name, false, "找不到 ZIP 文件「$zipPath」。")
            if (!zipFile.name.lowercase().endsWith(".zip")) {
                return@withContext ToolResult(name, false, "「$zipPath」不是 .zip 文件。")
            }

            targetDir.mkdirs()
            var count = 0
            ZipInputStream(FileInputStream(zipFile)).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val outFile = File(targetDir, entry.name)
                        // P2 修复：Zip Slip 路径穿越校验，防止 entry.name 含 "../" 等把文件写到目标目录之外
                        if (!outFile.canonicalPath.startsWith(targetDir.canonicalPath + File.separator)) {
                            throw SecurityException("Zip Slip 检测：非法路径 ${entry.name}")
                        }
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { fos -> zis.copyTo(fos) }
                        count++
                    }
                    entry = zis.nextEntry
                }
            }

            ToolResult(name, true, "[ZIP 已解压]\n源文件：$zipPath\n目标目录：$toPath\n提取文件数：$count")
        } catch (e: Exception) {
            ToolResult(name, false, "解压 ZIP 时遇到问题：${e.message?.take(80)}", e.message)
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  ⑦ ZipCreateTool
// ─────────────────────────────────────────────────────────────

/**
 * 创建 ZIP 压缩包工具。
 *
 * 标签格式：<tool:zip_create source="文件或目录" zip="输出.zip"/>
 *
 * 行为：
 * - source 可以是文件或目录；目录会递归添加其下所有文件。
 * - zip 指定输出的 .zip 文件路径。
 */
class ZipCreateTool(private val context: Context) : AgentTool {

    override val name = "zip_create"
    override val description = "把文件或目录打包成ZIP压缩包"
    override val paramKeys = listOf("source", "zip")

    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val sourcePath = params["source"]?.trim()
        val zipPath    = params["zip"]?.trim()
        if (sourcePath.isNullOrEmpty() || zipPath.isNullOrEmpty()) {
            return@withContext ToolResult(name, false, "", "缺少 source 或 zip 参数")
        }
        if (hasPathTraversal(sourcePath) || hasPathTraversal(zipPath)) {
            return@withContext ToolResult(name, false, "无法创建压缩包。", "路径包含非法字符")
        }

        val source = resolveFileSystemPath(context, sourcePath)
            ?: return@withContext ToolResult(name, false, "无法创建压缩包。", "源路径超出允许范围")
        val zipFile = resolveFileSystemPath(context, zipPath)
            ?: return@withContext ToolResult(name, false, "无法创建压缩包。", "目标路径超出允许范围")

        try {
            if (!source.exists()) return@withContext ToolResult(name, false, "找不到源路径「$sourcePath」。")

            zipFile.parentFile?.mkdirs()
            var count = 0
            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                if (source.isDirectory) {
                    source.walkTopDown().forEach { f ->
                        if (f.isFile) {
                            val relativePath = f.relativeTo(source).path
                            zos.putNextEntry(ZipEntry(relativePath))
                            FileInputStream(f).use { fis -> fis.copyTo(zos) }
                            zos.closeEntry()
                            count++
                        }
                    }
                } else {
                    zos.putNextEntry(ZipEntry(source.name))
                    FileInputStream(source).use { fis -> fis.copyTo(zos) }
                    zos.closeEntry()
                    count++
                }
            }

            ToolResult(name, true, "[ZIP 已创建]\n输出：$zipPath\n包含文件数：$count\n来源：$sourcePath")
        } catch (e: Exception) {
            ToolResult(name, false, "创建 ZIP 时遇到问题：${e.message?.take(80)}", e.message)
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  ⑧ FileOrganizeTool
// ─────────────────────────────────────────────────────────────

/**
 * 整理文件/文件夹排序工具。
 *
 * 标签格式：<tool:file_organize path="目录" order="name|date|size" direction="asc|desc"/>
 *
 * 行为：
 * - 将指定目录下的文件和文件夹按 order 排序方式重命名添加序号前缀。
 * - order 支持 name（名称）、date（修改日期）、size（文件大小）。
 * - direction 支持 asc（升序）和 desc（降序）。
 * - 已有序号前缀（如 "01_"）的文件会先去除旧前缀再添加新序号。
 */
class FileOrganizeTool(private val context: Context) : AgentTool {

    override val name = "file_organize"
    override val description = "把目录下的文件按名称/日期/大小排序并添加序号前缀整理"
    override val paramKeys = listOf("path", "order", "direction")

    private val orderPrefixRegex = Regex("^\\d+[_\\-.]\\s*")

    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val dirPath   = params["path"]?.trim()
        val orderBy   = params["order"]?.trim()?.lowercase() ?: "name"
        val direction = params["direction"]?.trim()?.lowercase() ?: "asc"

        if (dirPath.isNullOrEmpty()) return@withContext ToolResult(name, false, "", "缺少 path 参数")
        if (hasPathTraversal(dirPath)) return@withContext ToolResult(name, false, "无法整理。", "路径包含非法字符")
        if (orderBy !in setOf("name", "date", "size")) {
            return@withContext ToolResult(name, false, "不支持的排序方式「$orderBy」，请使用 name/date/size。")
        }
        if (direction !in setOf("asc", "desc")) {
            return@withContext ToolResult(name, false, "不支持的排序方向「$direction」，请使用 asc/desc。")
        }

        val dir = resolveFileSystemPath(context, dirPath)
            ?: return@withContext ToolResult(name, false, "无法整理。", "路径超出允许范围")

        try {
            if (!dir.exists() || !dir.isDirectory) {
                return@withContext ToolResult(name, false, "找不到目录「$dirPath」或不是目录。")
            }

            val items = dir.listFiles()?.toList() ?: return@withContext ToolResult(name, false, "读取目录失败。")

            val sorted = when (orderBy) {
                "name" -> items.sortedBy { it.name.lowercase() }.let {
                    if (direction == "desc") it.reversed() else it
                }
                "date" -> items.sortedBy { it.lastModified() }.let {
                    if (direction == "desc") it.reversed() else it
                }
                "size" -> items.sortedBy { if (it.isFile) it.length() else 0L }.let {
                    if (direction == "desc") it.reversed() else it
                }
                else -> items
            }

            val digitLen = sorted.size.toString().length.coerceAtLeast(2)
            var renamed = 0
            for ((index, item) in sorted.withIndex()) {
                val cleanName = item.name.replaceFirst(orderPrefixRegex, "")
                val newName = "${(index + 1).toString().padStart(digitLen, '0')}_$cleanName"
                if (newName != item.name) {
                    // 批次4-2-9 修复：renameTo 返回值未检查，renamed 计数器无论成败都递增。
                    val ok = item.renameTo(File(item.parentFile, newName))
                    if (ok) renamed++
                }
            }

            ToolResult(name, true, "[文件已整理]\n目录：$dirPath\n排序：$orderBy ${direction}\n重命名：$renamed 项")
        } catch (e: Exception) {
            ToolResult(name, false, "整理文件时遇到问题：${e.message?.take(80)}", e.message)
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  模块注册入口
// ─────────────────────────────────────────────────────────────

/**
 * 注册全部文件系统操作工具（8个）。
 * 在 ZaijianApp.onCreate() 中调用。
 */
fun AgentToolRegistry.registerFileSystemTools(context: Context) {
    registerAll(
        FolderCreateTool(context),
        FolderDeleteTool(context),
        FileRenameTool(context),
        FileEditTool(context),
        FileDeleteTool(context),
        ZipExtractTool(context),
        ZipCreateTool(context),
        FileOrganizeTool(context),
    )
}
