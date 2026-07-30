package com.zaijian.zhoumuyun.data.agent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.zaijian.zhoumuyun.util.ImageEditor
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
 * 处理结果一律写入临时目录 `context.cacheDir/image_edit/`，**不覆盖原文件**——
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
        "convert 需 format(jpg/png/webp)+quality(0-100)；strip_exif 无额外参数。结果写入临时目录不覆盖原文件"
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
        const val OUTPUT_SUBDIR = "image_edit"
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

            // 5/6) 根据 operation 执行对应 ImageEditor 方法，结果写入临时目录
            val outputDir = File(context.cacheDir, OUTPUT_SUBDIR).apply { mkdirs() }
            val baseName = file.nameWithoutExtension
            val timestamp = System.currentTimeMillis()

            val outputPath: String = when (operation) {
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
                    val src = BitmapFactory.decodeFile(file.absolutePath)
                        ?: return@withContext ToolResult(name, false, "无法解码图片「$filePath」。")
                    val result = ImageEditor.crop(src, x, y, w, h)
                    saveBitmap(result, compressFormatFor(file.extension), DEFAULT_QUALITY,
                        outputDir, baseName, "crop", timestamp)
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
                    saveBitmap(result, compressFormatFor(file.extension), DEFAULT_QUALITY,
                        outputDir, baseName, "resize", timestamp)
                }

                "rotate" -> {
                    val degrees = params["degrees"]?.toFloatOrNull()
                    if (degrees == null) {
                        return@withContext ToolResult(name, false, "rotate 需要 degrees 参数（数值）。")
                    }
                    val src = BitmapFactory.decodeFile(file.absolutePath)
                        ?: return@withContext ToolResult(name, false, "无法解码图片「$filePath」。")
                    val result = ImageEditor.rotate(src, degrees)
                    saveBitmap(result, compressFormatFor(file.extension), DEFAULT_QUALITY,
                        outputDir, baseName, "rotate", timestamp)
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
                    val src = BitmapFactory.decodeFile(file.absolutePath)
                        ?: return@withContext ToolResult(name, false, "无法解码图片「$filePath」。")
                    val result = ImageEditor.flip(src, horizontal)
                    saveBitmap(result, compressFormatFor(file.extension), DEFAULT_QUALITY,
                        outputDir, baseName, "flip", timestamp)
                }

                "watermark" -> {
                    val text = params["text"]
                    val textSize = params["text_size"]?.toFloatOrNull()
                    val watermarkPath = params["watermark_path"]?.trim()
                    val x = params["x"]?.toIntOrNull() ?: 0
                    val y = params["y"]?.toIntOrNull() ?: 0

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
                    saveBitmap(result, compressFormatFor(file.extension), DEFAULT_QUALITY,
                        outputDir, baseName, "watermark", timestamp)
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
                    val src = BitmapFactory.decodeFile(file.absolutePath)
                        ?: return@withContext ToolResult(name, false, "无法解码图片「$filePath」。")
                    val outFile = File(
                        outputDir,
                        "${baseName}_convert_${timestamp}.${extensionFor(format)}",
                    )
                    ImageEditor.convert(src, format, quality, outFile)
                }

                "strip_exif" -> {
                    // 拷贝到临时目录再清除 EXIF，不覆盖原文件
                    val ext = file.extension.ifBlank { "jpg" }
                    val outFile = File(outputDir, "${baseName}_strip_exif_${timestamp}.$ext")
                    file.copyTo(outFile, overwrite = true)
                    val stripped = ImageEditor.stripExif(outFile.absolutePath)
                    // ExifInterface 仅支持 JPEG 等格式；非 JPEG 本就无 EXIF，拷贝副本即可视为成功。
                    // 但若是 JPEG 却清除失败（文件损坏 / IO 异常），视为真正失败。
                    if (!stripped && (ext.equals("jpg", true) || ext.equals("jpeg", true))) {
                        outFile.delete()
                        return@withContext ToolResult(
                            name, false, "清除 EXIF 失败（图片格式可能不支持或文件损坏）。",
                        )
                    }
                    outFile.absolutePath
                }

                else -> return@withContext ToolResult(name, false, "不支持的 operation「$operation」。")
            }

            ToolResult(
                toolName = name,
                success = true,
                content = "[图片已处理]\n操作：$operation\n输出路径：$outputPath",
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

    /** 把位图压缩写入临时目录，返回输出文件绝对路径。 */
    private fun saveBitmap(
        bitmap: Bitmap,
        format: Bitmap.CompressFormat,
        quality: Int,
        outputDir: File,
        baseName: String,
        operation: String,
        timestamp: Long,
    ): String {
        val ext = extensionFor(format)
        val outFile = File(outputDir, "${baseName}_${operation}_${timestamp}.$ext")
        outFile.parentFile?.mkdirs()
        java.io.FileOutputStream(outFile).use { fos ->
            bitmap.compress(format, quality.coerceIn(0, 100), fos)
        }
        return outFile.absolutePath
    }
}
