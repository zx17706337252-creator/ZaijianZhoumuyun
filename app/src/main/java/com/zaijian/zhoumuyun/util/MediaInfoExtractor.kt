package com.zaijian.zhoumuyun.util

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * MediaInfoExtractor — 音视频元数据与封面帧提取的纯函数工具集。
 *
 * ═══════════════════════════════════════════════════════════════
 * 职责
 * ═══════════════════════════════════════════════════════════════
 * 封装 [MediaMetadataRetriever]（持有 native 资源，必须显式 [release]），
 * 隔离 setDataSource / extractMetadata / embeddedPicture / getFrameAtTime
 * 等调用细节，供 Agent 工具与预览层共用一份实现。
 *
 * 所有 IO 操作用 [withContext] 挂到 [Dispatchers.IO] 上执行。
 */
object MediaInfoExtractor {

    /**
     * 音视频元数据快照。各字段在源文件未提供时为 null。
     *
     * @param durationMs  时长（毫秒）
     * @param width       视频宽度（像素）
     * @param height      视频高度（像素）
     * @param bitrate     比特率（bps）
     * @param mimeType    容器 / 整体 MIME 类型（如 "video/mp4"）
     * @param videoCodec  视频编码 MIME（如 "video/avc"，API 30+ 可提取）
     * @param audioCodec  音频编码 MIME（如 "audio/mp4a-latm"，API 30+ 可提取）
     * @param title       标题
     * @param artist      艺术家
     * @param album       专辑
     * @param date        日期
     * @param location    地理位置
     */
    data class MediaInfo(
        val durationMs: Long? = null,
        val width: Int? = null,
        val height: Int? = null,
        val bitrate: Long? = null,
        val mimeType: String? = null,
        val videoCodec: String? = null,
        val audioCodec: String? = null,
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val date: String? = null,
        val location: String? = null,
    )

    /**
     * 提取音视频元数据。
     *
     * [MediaMetadataRetriever] 是 native 资源持有者，用 try-finally 保证
     * [release] 一定执行，避免 fd 泄漏。
     */
    suspend fun extractInfo(filePath: String): MediaInfo =
        withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(filePath)
                MediaInfo(
                    durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull(),
                    width      = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull(),
                    height     = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull(),
                    bitrate    = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull(),
                    mimeType   = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE),
                    videoCodec = extractCodec(retriever, isVideo = true),
                    audioCodec = extractCodec(retriever, isVideo = false),
                    title      = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                    artist     = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                    album      = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                    date       = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE),
                    location   = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION),
                )
            } finally {
                retriever.release()
            }
        }

    /**
     * 提取视频首帧 / 音频专辑封面，保存为图片并返回路径。
     *
     * - 优先取 [MediaMetadataRetriever.embeddedPicture]（音频专辑封面，部分视频也有）。
     * - 其次用 [MediaMetadataRetriever.getFrameAtTime] 取视频首帧位图。
     * - 均失败返回 null。
     *
     * @param outputDir 图片输出目录（不存在会自动创建）
     */
    suspend fun extractCoverFrame(filePath: String, outputDir: File): String? =
        withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(filePath)

                // 1. 内嵌图片（音频专辑封面 / 部分视频封面）——按原始字节落盘，避免二次编码丢真。
                val embedded = retriever.embeddedPicture
                if (embedded != null && embedded.isNotEmpty()) {
                    return@withContext saveBytesAsImage(embedded, outputDir)
                }

                // 2. 视频首帧位图。
                val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (frame != null) {
                    return@withContext saveBitmapAsPng(frame, outputDir)
                }
                null
            } finally {
                retriever.release()
            }
        }

    // ─────────────────────────────────────────────────────────────
    //  私有辅助
    // ─────────────────────────────────────────────────────────────

    /**
     * 视频 / 音频编码 MIME 提取。
     *
     * 独立的编码 MIME key（[MediaMetadataRetriever.METADATA_KEY_VIDEO_CODEC_MIME_TYPE] /
     * [MediaMetadataRetriever.METADATA_KEY_AUDIO_CODEC_MIME_TYPE]）自 API 30 (R) 起
     * 才有，低版本无此 key——做版本守卫返回 null，不崩溃。
     */
    private fun extractCodec(retriever: MediaMetadataRetriever, isVideo: Boolean): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val key = if (isVideo) {
            MediaMetadataRetriever.METADATA_KEY_VIDEO_CODEC_MIME_TYPE
        } else {
            MediaMetadataRetriever.METADATA_KEY_AUDIO_CODEC_MIME_TYPE
        }
        return retriever.extractMetadata(key)
    }

    /**
     * 把内嵌图片字节落盘。内嵌封面本身可能是 JPEG/PNG，按原始字节保存，
     * 用 .jpg 扩展名（绝大多数专辑封面是 JPEG）。
     */
    private fun saveBytesAsImage(bytes: ByteArray, outputDir: File): String? {
        if (!outputDir.exists()) outputDir.mkdirs()
        val outFile = File(outputDir, "cover_${System.currentTimeMillis()}.jpg")
        return try {
            FileOutputStream(outFile).use { it.write(bytes) }
            outFile.absolutePath
        } catch (e: Throwable) {
            null
        }
    }

    /**
     * 把位图压缩为 PNG 落盘。
     */
    private fun saveBitmapAsPng(bitmap: Bitmap, outputDir: File): String? {
        if (!outputDir.exists()) outputDir.mkdirs()
        val outFile = File(outputDir, "cover_${System.currentTimeMillis()}.png")
        return try {
            FileOutputStream(outFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            outFile.absolutePath
        } catch (e: Throwable) {
            null
        } finally {
            bitmap.recycle()
        }
    }
}
