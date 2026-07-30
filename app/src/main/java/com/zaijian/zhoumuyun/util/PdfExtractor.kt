package com.zaijian.zhoumuyun.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * PdfExtractor — PDF 文本 / 元数据 / 缩略图提取的纯函数工具集。
 *
 * ═══════════════════════════════════════════════════════════════
 * 职责
 * ═══════════════════════════════════════════════════════════════
 * 隔离两套底层 PDF 实现，让上层 Agent 工具只面对稳定的纯函数 API：
 *  - 文本与元数据：PdfBox-Android（com.tom_roush.pdfbox），首次调用前
 *    幂等初始化 [PDFBoxResourceLoader] 加载 native 库。
 *  - 首页缩略图：Android 系统 [PdfRenderer]（非 PdfBox），渲染首页为
 *    Bitmap 并落盘为 PNG。
 *
 * 所有 IO 操作均用 [withContext] 挂到 [Dispatchers.IO] 上执行，调用方
 * 传入的 [InputStream] 会被完整读取后关闭。
 *
 * 设计原则：本对象无状态、无副作用依赖（除落盘的缩略图文件），可被
 * 任意协程并发调用。
 */
object PdfExtractor {

    /** 缩略图最长边的目标像素，避免大页面渲染出过大位图导致 OOM。 */
    private const val THUMBNAIL_MAX_DIMENSION = 1024

    /**
     * PDF 元数据快照。
     *
     * @param title        文档标题（documentInformation.title），无则为 null
     * @param author       作者（documentInformation.author），无则为 null
     * @param creationDate 创建时间（documentInformation.creationDate），
     *   格式化为 "yyyy-MM-dd HH:mm:ss" 字符串；无则 null
     * @param pageCount    页数（document.numberOfPages）
     */
    data class PdfMetadata(
        val title: String?,
        val author: String?,
        val creationDate: String?,
        val pageCount: Int,
    )

    /**
     * 提取 PDF 全文文本。
     *
     * 在调用 PdfBox 前幂等初始化 [PDFBoxResourceLoader]（首次加载 native 库，
     * 重复调用无副作用）。调用方负责关闭传入的 [inputStream]。
     */
    suspend fun extractText(context: Context, inputStream: InputStream): String =
        withContext(Dispatchers.IO) {
            PDFBoxResourceLoader.init(context.applicationContext)
            PDDocument.load(inputStream).use { doc ->
                PDFTextStripper().getText(doc)
            }
        }

    /**
     * 提取 PDF 元数据（标题 / 作者 / 创建时间 / 页数）。
     */
    suspend fun extractMetadata(context: Context, inputStream: InputStream): PdfMetadata =
        withContext(Dispatchers.IO) {
            PDFBoxResourceLoader.init(context.applicationContext)
            PDDocument.load(inputStream).use { doc ->
                val info = doc.documentInformation
                PdfMetadata(
                    title        = info.title?.takeIf { it.isNotBlank() },
                    author       = info.author?.takeIf { it.isNotBlank() },
                    creationDate = info.creationDate?.let { formatCalendar(it) },
                    pageCount    = doc.numberOfPages,
                )
            }
        }

    /**
     * 用系统 [PdfRenderer] 渲染首页缩略图，保存为 PNG 文件。
     *
     * [PdfRenderer] 只接受 [ParcelFileDescriptor]（文件描述符），无法直接吃
     * [InputStream]；这里先把流落盘到 cacheDir 的临时文件再交给 PdfRenderer，
     * 渲染完成后删除临时文件。
     *
     * @param outputDir 缩略图输出目录（不存在会自动创建）
     * @return 生成的 PNG 文件绝对路径；渲染失败返回 null
     */
    suspend fun renderFirstPageThumbnail(
        context: Context,
        inputStream: InputStream,
        outputDir: File,
    ): String? = withContext(Dispatchers.IO) {
        val tempPdf = File(context.cacheDir, "pdf_render_${UUID.randomUUID()}.pdf")
        try {
            inputStream.use { input ->
                tempPdf.outputStream().use { output -> input.copyTo(output) }
            }
            renderFirstPageThumbnailFromFile(tempPdf, outputDir)
        } finally {
            tempPdf.delete()
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  私有辅助
    // ─────────────────────────────────────────────────────────────

    /**
     * 渲染给定 PDF 文件首页为 PNG 缩略图。
     *
     * 资源关闭顺序：先关 [PdfRenderer]（内部 [PdfRenderer.Page]），再关
     * [ParcelFileDescriptor]——PdfRenderer 持有 pfd，必须先于 pfd 释放。
     * 用嵌套 [use] 天然保证此顺序（内层先关，外层后关）。
     */
    private fun renderFirstPageThumbnailFromFile(pdfFile: File, outputDir: File): String? {
        if (!pdfFile.exists() || pdfFile.length() == 0L) return null
        if (!outputDir.exists()) outputDir.mkdirs()

        return try {
            ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    if (renderer.pageCount <= 0) return@use null
                    renderer.openPage(0).use { page ->
                        renderPageToPng(page, outputDir)
                    }
                }
            }
        } catch (e: Throwable) {
            // 损坏 / 加密 / 非标准 PDF 会让 PdfRenderer 抛异常，统一退化为 null。
            null
        }
    }

    /**
     * 把单页渲染为 PNG 并落盘。返回文件绝对路径。
     */
    private fun renderPageToPng(page: PdfRenderer.Page, outputDir: File): String? {
        val srcWidth = page.width
        val srcHeight = page.height
        if (srcWidth <= 0 || srcHeight <= 0) return null

        val scale = THUMBNAIL_MAX_DIMENSION.toFloat() / maxOf(srcWidth, srcHeight).toFloat()
        val bmpWidth = (srcWidth * scale).toInt().coerceAtLeast(1)
        val bmpHeight = (srcHeight * scale).toInt().coerceAtLeast(1)

        // PdfRenderer 要求 Bitmap 必须是 ARGB_8888 配置。
        val bitmap = Bitmap.createBitmap(bmpWidth, bmpHeight, Bitmap.Config.ARGB_8888)
        return try {
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            val outFile = File(outputDir, "pdf_thumb_${UUID.randomUUID()}.png")
            outFile.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            outFile.absolutePath
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * 把 [Calendar] 格式化为稳定的日期字符串。
     */
    private fun formatCalendar(calendar: Calendar): String? = try {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            .format(Date(calendar.timeInMillis))
    } catch (e: Throwable) {
        null
    }
}
