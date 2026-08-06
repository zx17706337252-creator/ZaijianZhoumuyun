package com.zaijian.zhoumuyun.data.agent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.zaijian.zhoumuyun.util.ImageEditor
import org.json.JSONObject
import java.io.File

/**
 * 图片编辑工具（image_edit）。
 *
 * 对已有图片文件做裁剪 / 缩放 / 旋转 / 镜像 / 加水印 / 转格式 / 清除 EXIF，
 * 底层几何与编解码全部委托 [ImageEditor]（纯函数，基于 Android 自带 Bitmap/Canvas/Matrix）。
 *
 * ## 安全边界
 * 与其它文件类工具一致：先 [hasPathTraversal] 拦路径穿越，再走 [resolveVaultPath]
 * 叠加保险库三段权限校验（角色不能越权处理别的角色私库里的图片）。图片水印的
 * `watermark_path` 同样走这套校验，避免把任意文件当水印读进来。
 *
 * ## 输出策略
 * 修复（专项审查报告 #3）：此前处理结果写入 `context.cacheDir/image_edit/`（临时目录），
 * 且 ToolResult.content 里没有 metaJson，导致下游 ExportedFileMeta.extractExportedFileJson
 * 提取不到、不渲染文件卡片，用户拿不到任何下载/打开入口；即便手动分享，cacheDir 也不在
 * res/xml/file_paths.xml 声明的 FileProvider 范围内（该文件明确注释说明已删除
 * `<cache-path>`，因为核查后全项目没有代码依赖它共享 cacheDir），会直接抛
 * IllegalArgumentException。现改为与其它文件类工具（FileExportTool 等）一致，
 * 统一走 [writeVaultStream] 落盘到 vault，并把 metaJson 拼进 content 末尾，
 * 走 ExportedFileMeta 同一条识别链路，渲染出文件卡片。**不覆盖原文件**——
 * 原文件可能是用户唯一副本，工具层只产新副本，由调用方/用户决定是否替换。
 *
 * ## 参数
 * 见 [paramKeys]；`operation` 取值：crop / resize / rotate / flip / watermark /
 * convert / strip_exif。数值参数用 toIntOrNull()/toFloatOrNull() 解析，失败即返回错误。
 */
class ImageEditTool(
    private val context: Context,
    private val characterIdProvider: () -> Int = { VaultCallContextHolder.get().characterId },
) : AgentTool {

    override val name = "image_edit"
    override val description = "裁剪/缩放/旋转/加水印/转格式/清除EXIF，处理已有图片文件"
    override val usageNotes = "operation 可选 crop/resize/rotate/flip/watermark/convert/strip_exif；" +
        "crop 需 x,y,width,height；resize 需 max_width,max_height；rotate 需 degrees；" +
        "flip 需 flip_horizontal(true/false)；watermark 需 text+text_size 或 watermark_path，可选 x,y；" +
        "convert 需 format(jpg/png/webp)+quality(0-100)；strip_exif 无额外参数。结果写入文件保险库，不覆盖原文件"
    override val paramKeys = listOf(
        "file_path", "operation",
        "x", "y", "width", "height",
        "max_width", "max_height",
        "degrees",
        "flip_horizontal",
        "text", "text_size",
        "watermark_path",
        "format", "quality",
    )

    private companion object {
        val SUPPORTED_OPERATIONS = setOf(
            "crop", "resize", "rotate", "flip", "watermark", "convert", "strip_exif",
        )
        const val DEFAULT_QUALITY = 92
        // 半透明白色水印文字：在多数背景上都可见
        val DEFAULT_WATERMARK_COLOR = Color.argb(204, 255, 255, 255)
    }

    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val filePath = params["file_path"]?.trim()
        val operation = params["operation"]?.trim()?.lowercase()

        if (filePath.isNullOrEmpty()) {
            return@withContext ToolResult(name, false, "", "缺少 file_path 参数")
        }
        if (operation.isNullOrEmpty()) {
            return@withContext ToolResult(name, false, "", "缺少 operation 参数")
        }
        if (operation !in SUPPORTED_OPERATIONS) {
            return@withContext ToolResult(
                name, false,
                "不支持的 operation「$operation」，请使用 ${SUPPORTED_OPERATIONS.joinToString("/")}。",
            )
        }

        // 1) 路径穿越校验
        if (hasPathTraversal(filePath)) {
            return@withContext ToolResult(name, false, "无法访问该路径。", "路径包含非法字符")
        }

        // 2) resolveVaultPath 权限校验（角色不能越权处理别的角色私库的图片）
        val file = when (val r = resolveVaultPath(context, filePath, characterIdProvider)) {
            is VaultPathResolution.Allowed -> r.file
            is VaultPathResolution.Denied -> return@withContext ToolResult(name, false, "无法访问该路径。", r.reason)
        }

        try {
            // 4) 检查文件存在
            if (!file.exists() || !file.isFile) {
                return@withContext ToolResult(name, false, "找不到图片文件「$filePath」。")
            }

            val baseName = file.nameWithoutExtension

            // 5/6) 根据 operation 执行对应 ImageEditor 方法，结果统一写入 vault
            // （修复 #3：此前写临时目录 cacheDir，用户拿不到文件；见类注释「输出策略」）。
            // vaultFileName / mimeType 由各分支算好，最终统一走 writeVaultStream 落盘。
            // 预存在修复：Kotlin 不允许解构声明带类型注解（ImageEditTool 曾因此编译失败），
            // 去掉注解，让 when 各分支返回的 Triple 自行推断类型。
            val (vaultFileName, mimeType, writer) = when (operation) {
                "crop" -> {
                    val x = params["x"]?.toIntOrNull()
                    val y = params["y"]?.toIntOrNull()
                    val w = params["width"]?.toIntOrNull()
                    val h = params["height"]?.toIntOrNull()
                    if (x == null || y == null || w == null || h == null) {
                        return@withContext ToolResult(
                            name, false, "crop 需要 x, y, width, height 参数（均为整数）。",
                        )
                    }
                    // 闪退排查（OOM）：crop 需要按原分辨率坐标取景，不能像 resize 那样
                    // 降采样解码（会让 x/y/width/height 与实际像素错位），所以用只读
                    // 边界的护栏挡住过大的图，而不是硬解码撑爆堆。
                    ImageEditor.checkDecodeSizeSafe(file.absolutePath)?.let { reason ->
                        return@withContext ToolResult(name, false, reason)
                    }
                    val src = BitmapFactory.decodeFile(file.absolutePath)
                        ?: return@withContext ToolResult(name, false, "无法解码图片「$filePath」。")
                    val result = ImageEditor.crop(src, x, y, w, h)
                    bitmapWriter(result, compressFormatFor(file.extension), DEFAULT_QUALITY,
                        baseName, "crop")
                }

                "resize" -> {
                    val maxW = params["max_width"]?.toIntOrNull()
                    val maxH = params["max_height"]?.toIntOrNull()
                    if (maxW == null || maxH == null) {
                        return@withContext ToolResult(
                            name, false, "resize 需要 max_width, max_height 参数（均为整数）。",
                        )
                    }
                    // 两阶段解码：先按目标尺寸算 inSampleSize 降采样，再精细缩放，省内存
                    val src = ImageEditor.decodeSampledBitmap(file.absolutePath, maxW, maxH)
                    val result = ImageEditor.resize(src, maxW, maxH)
                    bitmapWriter(result, compressFormatFor(file.extension), DEFAULT_QUALITY,
                        baseName, "resize")
                }

                "rotate" -> {
                    val degrees = params["degrees"]?.toFloatOrNull()
                    if (degrees == null) {
                        return@withContext ToolResult(name, false, "rotate 需要 degrees 参数（数值）。")
                    }
                    // 闪退排查（OOM）：见 crop 分支同名护栏说明
                    ImageEditor.checkDecodeSizeSafe(file.absolutePath)?.let { reason ->
                        return@withContext ToolResult(name, false, reason)
                    }
                    val src = BitmapFactory.decodeFile(file.absolutePath)
                        ?: return@withContext ToolResult(name, false, "无法解码图片「$filePath」。")
                    val result = ImageEditor.rotate(src, degrees)
                    bitmapWriter(result, compressFormatFor(file.extension), DEFAULT_QUALITY,
                        baseName, "rotate")
                }

                "flip" -> {
                    val flipHorizontal = params["flip_horizontal"]?.trim()?.lowercase()
                    if (flipHorizontal == null) {
                        return@withContext ToolResult(name, false, "flip 需要 flip_horizontal 参数（true/false）。")
                    }
                    val horizontal = when (flipHorizontal) {
                        "true" -> true
                        "false" -> false
                        else -> return@withContext ToolResult(
                            name, false, "flip_horizontal 只接受 true 或 false。",
                        )
                    }
                    // 闪退排查（OOM）：见 crop 分支同名护栏说明
                    ImageEditor.checkDecodeSizeSafe(file.absolutePath)?.let { reason ->
                        return@withContext ToolResult(name, false, reason)
                    }
                    val src = BitmapFactory.decodeFile(file.absolutePath)
                        ?: return@withContext ToolResult(name, false, "无法解码图片「$filePath」。")
                    val result = ImageEditor.flip(src, horizontal)
                    bitmapWriter(result, compressFormatFor(file.extension), DEFAULT_QUALITY,
                        baseName, "flip")
                }

                "watermark" -> {
                    val text = params["text"]
                    val textSize = params["text_size"]?.toFloatOrNull()
                    val watermarkPath = params["watermark_path"]?.trim()
                    val x = params["x"]?.toIntOrNull() ?: 0
                    val y = params["y"]?.toIntOrNull() ?: 0

                    // 闪退排查（OOM）：见 crop 分支同名护栏说明。水印是 src+result（文字水印）
                    // 或 src+watermark+result（图片水印）多张位图同时驻留内存，比其它
                    // 操作更容易叠加撑爆堆，所以护栏在这里同样必须挡在解码之前。
                    ImageEditor.checkDecodeSizeSafe(file.absolutePath)?.let { reason ->
                        return@withContext ToolResult(name, false, reason)
                    }
                    val src = BitmapFactory.decodeFile(file.absolutePath)
                        ?: return@withContext ToolResult(name, false, "无法解码图片「$filePath」。")

                    val result = if (!text.isNullOrEmpty() && textSize != null) {
                        // 文字水印
                        ImageEditor.addTextWatermark(src, text, x, y, textSize, DEFAULT_WATERMARK_COLOR)
                    } else if (!watermarkPath.isNullOrEmpty()) {
                        // 图片水印：watermark_path 同样做路径穿越 + 保险库权限校验
                        if (hasPathTraversal(watermarkPath)) {
                            return@withContext ToolResult(
                                name, false, "无法访问水印图片路径。", "路径包含非法字符",
                            )
                        }
                        val wmFile = when (val r = resolveVaultPath(context, watermarkPath, characterIdProvider)) {
                            is VaultPathResolution.Allowed -> r.file
                            is VaultPathResolution.Denied -> return@withContext ToolResult(
                                name, false, "无法访问水印图片路径。", r.reason,
                            )
                        }
                        if (!wmFile.exists() || !wmFile.isFile) {
                            return@withContext ToolResult(name, false, "找不到水印图片「$watermarkPath」。")
                        }
                        // 闪退排查（OOM）：水印图同样护栏，不然一张超大水印图也能撑爆堆
                        ImageEditor.checkDecodeSizeSafe(wmFile.absolutePath)?.let { reason ->
                            return@withContext ToolResult(name, false, reason)
                        }
                        val watermark = BitmapFactory.decodeFile(wmFile.absolutePath)
                            ?: return@withContext ToolResult(
                                name, false, "无法解码水印图片「$watermarkPath」。",
                            )
                        ImageEditor.addImageWatermark(src, watermark, x, y)
                    } else {
                        return@withContext ToolResult(
                            name, false,
                            "watermark 需要 text+text_size（文字水印）或 watermark_path（图片水印）。",
                        )
                    }
                    bitmapWriter(result, compressFormatFor(file.extension), DEFAULT_QUALITY,
                        baseName, "watermark")
                }

                "convert" -> {
                    val formatStr = params["format"]?.trim()?.lowercase()
                    val quality = params["quality"]?.toIntOrNull()
                    if (formatStr.isNullOrEmpty() || quality == null) {
                        return@withContext ToolResult(
                            name, false, "convert 需要 format（jpg/png/webp）和 quality（0-100）参数。",
                        )
                    }
                    if (quality !in 0..100) {
                        return@withContext ToolResult(name, false, "quality 必须在 0-100 之间。")
                    }
                    val format = when (formatStr) {
                        "jpg", "jpeg" -> Bitmap.CompressFormat.JPEG
                        "png" -> Bitmap.CompressFormat.PNG
                        "webp" -> Bitmap.CompressFormat.WEBP
                        else -> return@withContext ToolResult(
                            name, false, "不支持的 format「$formatStr」，请使用 jpg/png/webp。",
                        )
                    }
                    // 闪退排查（OOM）：见 crop 分支同名护栏说明
                    ImageEditor.checkDecodeSizeSafe(file.absolutePath)?.let { reason ->
                        return@withContext ToolResult(name, false, reason)
                    }
                    val src = BitmapFactory.decodeFile(file.absolutePath)
                        ?: return@withContext ToolResult(name, false, "无法解码图片「$filePath」。")
                    bitmapWriter(src, format, quality, baseName, "convert")
                }

                "strip_exif" -> {
                    // 先写到 cacheDir 临时文件做 EXIF 清除（ImageEditor.stripExif 需要一个
                    // 磁盘文件路径原地操作），再把清除后的字节流写入 vault；cacheDir 里的
                    // 临时件用完即删，从不作为最终产物暴露给用户/FileProvider。
                    val ext = file.extension.ifBlank { "jpg" }
                    val tmpFile = File(context.cacheDir, "image_edit_tmp_${System.currentTimeMillis()}.$ext")
                    try {
                        file.copyTo(tmpFile, overwrite = true)
                        val stripped = ImageEditor.stripExif(tmpFile.absolutePath)
                        // ExifInterface 仅支持 JPEG 等格式；非 JPEG 本就无 EXIF，拷贝副本即可视为成功。
                        // 但若是 JPEG 却清除失败（文件损坏 / IO 异常），视为真正失败。
                        if (!stripped && (ext.equals("jpg", true) || ext.equals("jpeg", true))) {
                            return@withContext ToolResult(
                                name, false, "清除 EXIF 失败（图片格式可能不支持或文件损坏）。",
                            )
                        }
                        val bytes = tmpFile.readBytes()
                        val vaultName = "${baseName}_strip_exif.$ext"
                        Triple(vaultName, mimeTypeFor(ext)) { out: java.io.OutputStream -> out.write(bytes) }
                    } finally {
                        tmpFile.delete()
                    }
                }

                else -> return@withContext ToolResult(name, false, "不支持的 operation「$operation」。")
            }

            val metaJson = writeVaultStream(context, vaultFileName, mimeType, writer)
            val sizeBytes = try { JSONObject(metaJson).optLong("sizeBytes", 0L) } catch (_: Throwable) { 0L }

            ToolResult(
                toolName = name,
                success = true,
                content = "[图片已处理]\n操作：$operation（${formatSize(sizeBytes)}）\n$metaJson",
                userHint = "正在处理图片…",
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            toolFailure(name, "图片编辑时遇到问题。", "image_edit_failed", e)
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  私有辅助
    // ─────────────────────────────────────────────────────────────

    /** 按文件扩展名推断压缩格式，缺省 JPEG。 */
    private fun compressFormatFor(ext: String): Bitmap.CompressFormat = when (ext.lowercase()) {
        "png" -> Bitmap.CompressFormat.PNG
        "webp" -> Bitmap.CompressFormat.WEBP
        else -> Bitmap.CompressFormat.JPEG
    }

    /** 按压缩格式反推输出扩展名。 */
    private fun extensionFor(format: Bitmap.CompressFormat): String = when (format) {
        Bitmap.CompressFormat.PNG -> "png"
        Bitmap.CompressFormat.WEBP -> "webp"
        else -> "jpg"
    }

    /** 按扩展名推断 MIME 类型，供 writeVaultStream 使用。 */
    private fun mimeTypeFor(ext: String): String = when (ext.lowercase()) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        else -> "image/jpeg"
    }

    /** 人类可读的文件大小格式化（与 BuiltinTools.formatSize 保持一致格式，不跨文件复用 private 函数）。 */
    private fun formatSize(bytes: Long): String = when {
        bytes < 1024        -> "${bytes} B"
        bytes < 1024 * 1024 -> "${"%.1f".format(bytes / 1024.0)} KB"
        else                -> "${"%.1f".format(bytes / 1024.0 / 1024.0)} MB"
    }

    /**
     * 构造 vault 文件名 + mimeType + 写入函数三元组（修复 #3：结果落盘目标从
     * cacheDir 改为 vault，见类注释「输出策略」）。返回值直接喂给
     * [writeVaultStream]，由它统一处理去重命名/校验/metaJson 生成。
     */
    private fun bitmapWriter(
        bitmap: Bitmap,
        format: Bitmap.CompressFormat,
        quality: Int,
        baseName: String,
        operation: String,
    ): Triple<String, String, (java.io.OutputStream) -> Unit> {
        val ext = extensionFor(format)
        val vaultFileName = "${baseName}_${operation}.$ext"
        val mimeType = mimeTypeFor(ext)
        val writer: (java.io.OutputStream) -> Unit = { out ->
            bitmap.compress(format, quality.coerceIn(0, 100), out)
        }
        return Triple(vaultFileName, mimeType, writer)
    }
}
