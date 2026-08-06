package com.zaijian.zhoumuyun.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream

/**
 * 图片编辑纯函数工具类。
 *
 * 全部基于 Android 自带 [Bitmap] / [Canvas] / [Matrix] / [Paint] / [BitmapFactory] 实现，
 * 不引入任何三方图片库。所有方法均为静态方法（[object] 单例），无内部可变状态，
 * 对同一输入产出确定输出，可安全在任意线程调用（耗时操作建议放 IO 线程）。
 *
 * ## 内存安全
 * 大图直接 [BitmapFactory.decodeFile] 容易 OOM。本类提供 [decodeSampledBitmap]：
 * 先用 `inJustDecodeBounds=true` 读边界、按 [calculateInSampleSize]（Android 官方推荐
 * 写法）算采样率，再真正解码，把解码后的像素总量压到与目标分辨率同量级。
 * 调用方对"需要全分辨率的操作"（如 crop）应自行权衡是否直接解码原图。
 *
 * ## EXIF
 * [stripExif] 使用 `androidx.exifinterface.media.ExifInterface`（注意不是已废弃的
 * `android.media.ExifInterface`），仅对 JPEG 等支持 EXIF 的格式有效。
 */
object ImageEditor {

    // ─────────────────────────────────────────────────────────────
    //  内存安全：两阶段解码
    // ─────────────────────────────────────────────────────────────

    /**
     * 计算 [BitmapFactory.Options.inSampleSize]（Android 官方推荐算法）。
     *
     * 思路：每次把采样率翻倍，直到解码后的尺寸刚好不再大于目标尺寸的一半——
     * 这样最终解码出的图比目标略大，再交给上层做精细缩放，既省内存又不至于太糊。
     *
     * @param options  已用 `inJustDecodeBounds=true` 读过边界的 Options（读 outWidth/outHeight）。
     * @param reqWidth 期望宽度（px）。
     * @param reqHeight 期望高度（px）。
     * @return 采样率，最小为 1。
     */
    fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            // 每次翻倍，直到再翻一倍就会让解码尺寸小于目标尺寸
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    /**
     * 两阶段解码：先读边界算 [BitmapFactory.Options.inSampleSize]，再真正解码。
     *
     * 用于"只需要近似目标分辨率"的场景（如 resize 的预降采样）。需要全分辨率的
     * 场景（如 crop 的精确取景）不应使用本方法，否则会丢像素。
     *
     * @param filePath  图片文件绝对路径。
     * @param reqWidth  期望宽度（px）。
     * @param reqHeight 期望高度（px）。
     * @return 解码后的 [Bitmap]。
     * @throws IllegalStateException 文件无法读取或解码失败时抛出（由调用方兜底捕获）。
     */
    @Throws(IllegalStateException::class)
    fun decodeSampledBitmap(filePath: String, reqWidth: Int, reqHeight: Int): Bitmap {
        // 第一阶段：只读边界，不分配像素内存
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(filePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IllegalStateException("无法读取图片边界：$filePath")
        }

        // 第二阶段：按采样率真正解码
        bounds.inSampleSize = calculateInSampleSize(bounds, reqWidth, reqHeight)
        bounds.inJustDecodeBounds = false
        return BitmapFactory.decodeFile(filePath, bounds)
            ?: throw IllegalStateException("无法解码图片：$filePath")
    }

    /**
     * 闪退排查（OOM）：解码前的内存护栏，只读边界（inJustDecodeBounds=true，不分配
     * 像素内存）算出解码为 ARGB_8888 后的预估内存占用，超过 [maxBytes] 就判定"过大"。
     *
     * 用于 crop / rotate / flip / watermark / convert 这类必须拿到全分辨率原图、
     * 不能像 [decodeSampledBitmap] 那样降采样的操作（降采样会让 crop 的 x/y/width/height
     * 坐标、watermark 的 x/y 坐标相对图片实际尺寸错位，属于正确性问题而非单纯性能问题）。
     * 这些路径此前直接 [BitmapFactory.decodeFile] 裸解码，遇到大图会把整个 App 撑到
     * OOM 闪退——不只是这一次工具调用失败，而是连带当前会话、其它页面一起崩掉。
     * 加了这道护栏后，大图会在真正分配像素内存之前就被拦下，只让这一次 image_edit
     * 调用失败（工具返回 [ToolResult.success]=false，AI 可以据此提示用户先用
     * resize 操作缩小图片），不再牵连整个进程。
     *
     * @param filePath 图片文件绝对路径。
     * @param maxBytes 单张 ARGB_8888 解码后允许占用的内存上限（字节），默认 100MB——
     *                 常见 12~20MP 手机照片（每张约 48~80MB）能正常通过，只挡住
     *                 明显偏大（约 25MP 以上）会带来 OOM 风险的图。
     * @return 尺寸安全返回 null；过大或读不出边界（留给正常解码路径报"无法解码"）时，
     *         返回一句可直接作为 [ToolResult] 失败原因使用的中文提示。
     */
    fun checkDecodeSizeSafe(filePath: String, maxBytes: Long = DEFAULT_MAX_DECODE_BYTES): String? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(filePath, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null // 边界都读不出来，交给后续正常解码路径去报"无法解码"
        val estimatedBytes = w.toLong() * h.toLong() * 4L // ARGB_8888 = 4 字节/像素
        if (estimatedBytes <= maxBytes) return null
        val megapixels = (w.toLong() * h.toLong()) / 1_000_000.0
        return "图片尺寸过大（约%.1fMP，%d×%d），直接处理容易导致内存溢出。请先用 resize 操作缩小图片后再试。"
            .format(megapixels, w, h)
    }

    /** [checkDecodeSizeSafe] 默认内存上限：100MB，见该函数文档说明取值理由。 */
    const val DEFAULT_MAX_DECODE_BYTES = 100L * 1024 * 1024

    // ─────────────────────────────────────────────────────────────
    //  几何变换：裁剪 / 缩放 / 旋转 / 镜像
    // ─────────────────────────────────────────────────────────────

    /**
     * 裁剪。对 (x, y, w, h) 做边界夹取，避免越界抛异常。
     *
     * @param src 源图。
     * @param x   裁剪区左上角 x。
     * @param y   裁剪区左上角 y。
     * @param w   裁剪区宽。
     * @param h   裁剪区高。
     * @return 裁剪后的新 [Bitmap]。
     */
    fun crop(src: Bitmap, x: Int, y: Int, w: Int, h: Int): Bitmap {
        val srcW = src.width
        val srcH = src.height
        val sx = x.coerceIn(0, srcW - 1)
        val sy = y.coerceIn(0, srcH - 1)
        // 夹取到 [1, 可用余量]，保证至少 1×1、不越界
        val sw = w.coerceIn(1, srcW - sx)
        val sh = h.coerceIn(1, srcH - sy)
        return Bitmap.createBitmap(src, sx, sy, sw, sh)
    }

    /**
     * 等比缩放：保持宽高比缩放到能放进 (maxW, maxH) 的最大尺寸，且不放大。
     *
     * 两阶段解码（算 inSampleSize）应在调用方完成，本方法直接接收已解码的 [Bitmap]
     * 做精细缩放。若原图已小于目标尺寸则原样返回（不放大，避免插值模糊）。
     *
     * @param src  源图。
     * @param maxW 目标最大宽（px）。
     * @param maxH 目标最大高（px）。
     * @return 缩放后的 [Bitmap]；无需缩放时返回原 [src]。
     */
    fun resize(src: Bitmap, maxW: Int, maxH: Int): Bitmap {
        if (maxW <= 0 || maxH <= 0) return src
        val srcW = src.width
        val srcH = src.height
        // 已在限制范围内，不放大
        if (srcW <= maxW && srcH <= maxH) return src

        // 取较小缩放比，保证两条边都不超出 maxW/maxH（等比缩放）
        val scale = minOf(
            maxW.toFloat() / srcW,
            maxH.toFloat() / srcH,
        ).coerceAtMost(1f) // 不放大
        val matrix = Matrix().apply { postScale(scale, scale) }
        return Bitmap.createBitmap(src, 0, 0, srcW, srcH, matrix, true)
    }

    /**
     * 旋转。
     *
     * @param src     源图。
     * @param degrees 旋转角度（顺时针为正，可为负）。
     * @return 旋转后的新 [Bitmap]。
     */
    fun rotate(src: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }

    /**
     * 镜像翻转。
     *
     * @param src         源图。
     * @param horizontal  true=水平翻转（左右镜像），false=垂直翻转（上下镜像）。
     * @return 翻转后的新 [Bitmap]。
     */
    fun flip(src: Bitmap, horizontal: Boolean): Bitmap {
        val matrix = Matrix().apply {
            if (horizontal) preScale(-1f, 1f) else preScale(1f, -1f)
        }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }

    // ─────────────────────────────────────────────────────────────
    //  水印
    // ─────────────────────────────────────────────────────────────

    /**
     * 添加文字水印。
     *
     * 在源图副本上绘制文字，不修改原图。结果为可变 ARGB_8888 位图。
     *
     * @param src      源图。
     * @param text     水印文字。
     * @param x        文字绘制基点 x。
     * @param y        文字绘制基点 y（drawText 的 y 是文字基线）。
     * @param textSize 文字大小（px）。
     * @param color    文字颜色（ARGB int）。
     * @return 带水印的新 [Bitmap]。
     */
    fun addTextWatermark(src: Bitmap, text: String, x: Int, y: Int, textSize: Float, color: Int): Bitmap {
        val result = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawBitmap(src, 0f, 0f, null)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.textSize = textSize
            this.color = color
        }
        canvas.drawText(text, x.toFloat(), y.toFloat(), paint)
        return result
    }

    /**
     * 添加图片水印。
     *
     * 在源图副本上绘制水印图，不修改原图。
     *
     * @param src       源图。
     * @param watermark 水印图（按原尺寸绘制，如需缩放请调用方先 resize）。
     * @param x         水印左上角 x。
     * @param y         水印左上角 y。
     * @return 带水印的新 [Bitmap]。
     */
    fun addImageWatermark(src: Bitmap, watermark: Bitmap, x: Int, y: Int): Bitmap {
        val result = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawBitmap(src, 0f, 0f, null)
        canvas.drawBitmap(watermark, x.toFloat(), y.toFloat(), null)
        return result
    }

    // ─────────────────────────────────────────────────────────────
    //  格式转换 / 保存
    // ─────────────────────────────────────────────────────────────

    /**
     * 格式转换并保存到指定文件。
     *
     * @param src     源图。
     * @param format  目标压缩格式（JPEG / PNG / WEBP）。
     * @param quality 压缩质量 0-100（PNG 忽略此值；会被夹取到 [0,100]）。
     * @param output  输出文件（父目录不存在会自动创建）。
     * @return 输出文件绝对路径。
     */
    fun convert(src: Bitmap, format: Bitmap.CompressFormat, quality: Int, output: File): String {
        output.parentFile?.mkdirs()
        FileOutputStream(output).use { fos ->
            src.compress(format, quality.coerceIn(0, 100), fos)
        }
        return output.absolutePath
    }

    /**
     * 清除 EXIF 元数据（GPS、拍摄时间、设备型号、方向等隐私敏感标签）。
     *
     * 使用 `androidx.exifinterface.media.ExifInterface`，仅对支持 EXIF 的格式
     * （JPEG 等）有效。把已知隐私标签逐个置 null 后 [ExifInterface.saveAttributes]
     * 回写。非 JPEG / 文件损坏 / IO 失败时返回 false（调用方据此判断是否回退）。
     *
     * @param filePath 图片文件绝对路径。
     * @return 成功清除返回 true；格式不支持或写回失败返回 false。
     */
    fun stripExif(filePath: String): Boolean {
        return try {
            val exif = ExifInterface(filePath)
            for (tag in EXIF_TAGS_TO_STRIP) {
                exif.setAttribute(tag, null)
            }
            exif.saveAttributes()
            true
        } catch (e: Exception) {
            // ExifInterface 仅支持 JPEG 等格式；非支持格式会抛异常，这里统一返回 false
            false
        }
    }

    /**
     * 需要清除的 EXIF 标签集合：位置、时间、设备、镜头、用户备注等隐私敏感信息。
     * 方向标签（ORIENTATION）也一并清除——几何方向已由调用方在解码后用 Matrix 校正，
     * 留着只会让别的看图软件二次旋转。
     */
    private val EXIF_TAGS_TO_STRIP = arrayOf(
        ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP,
        ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_PROCESSING_METHOD,
        ExifInterface.TAG_GPS_AREA_INFORMATION,
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME,
        ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
        ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_SOFTWARE,
        ExifInterface.TAG_ARTIST,
        ExifInterface.TAG_USER_COMMENT,
        ExifInterface.TAG_IMAGE_DESCRIPTION,
        ExifInterface.TAG_CAMERA_OWNER_NAME,
        ExifInterface.TAG_BODY_SERIAL_NUMBER,
        ExifInterface.TAG_LENS_MAKE,
        ExifInterface.TAG_LENS_MODEL,
        ExifInterface.TAG_LENS_SERIAL_NUMBER,
        ExifInterface.TAG_ORIENTATION,
    )
}
