package com.zaijian.zhoumuyun.data.agent

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.zaijian.zhoumuyun.data.db.AppDatabase
import com.zaijian.zhoumuyun.data.db.entity.FileIndexEntity
import com.zaijian.zhoumuyun.util.PdfExtractor
import com.zaijian.zhoumuyun.util.ZLog
import java.io.File

/**
 * 后台文件索引 Worker（方案 §4.3）。
 *
 * 照抄项目现有 Worker 惯例（CleanupWorker 等）：
 * - CoroutineWorker 子类，构造函数接 (appContext, WorkerParameters)
 * - 内部用 AppDatabase.getInstance(applicationContext) 直接拿单例，不走 DI
 * - doWork() 用 try/catch 包一层返回 Result.success()/Result.retry()
 *
 * 接收一个文件路径（vault 相对路径），提取可索引文本后 upsert 到 file_index 表。
 * 索引失败不阻塞调用方，返回 success 避免无限重试（索引不是关键路径）。
 */
class FileIndexWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            val filePath = inputData.getString(KEY_FILE_PATH)
                ?: return Result.failure()

            // vault 相对路径 → 绝对路径
            val absolutePath = if (File(filePath).isAbsolute) filePath
                else File(applicationContext.filesDir, filePath).absolutePath
            val file = File(absolutePath)
            if (!file.exists()) return Result.success() // 文件已删除，正常退出

            val ext = file.extension.lowercase()
            val extractedText: String? = when (ext) {
                "pdf" -> extractPdfText(file)
                "docx" -> extractDocxText(file)
                "txt", "md" -> tryReadText(file)
                else -> null // 图片/音视频无可提取文本
            }

            val entity = FileIndexEntity(
                filePath = filePath, // 存 vault 相对路径做主键
                fileName = file.name,
                fileType = ext,
                extractedText = extractedText,
                sizeBytes = file.length(),
                createdAt = file.lastModified(),
                indexedAt = System.currentTimeMillis(),
            )
            AppDatabase.getInstance(applicationContext).fileIndexDao().upsert(entity)
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            // C7 #36（已复核，有意设计，非需修复问题——审查报告v2 结论：
            // "可接受的设计选择"）：索引失败时返回 Result.success() 而非
            // retry/failure，是刻意选择。理由：文件搜索索引不是关键路径——
            // 一个文件没建上索引，用户仍能正常打开/使用该文件，只是搜索时
            // 少收录这一条，不会导致数据丢失或功能崩溃，不值得为它引入
            // WorkManager 重试机制（重试对偶发的解析失败大概率无意义，
            // 反而可能造成资源浪费）。
            // 此 catch 仅打日志留痕，供后续排查"为什么某文件搜不到"，
            // 不改变 Result.success() 的既有行为。
            ZLog.w("FileIndexWorker", "文件索引失败，跳过（不影响文件本身使用）: ${inputData.getString(KEY_FILE_PATH)}", e)
            Result.success()
        }
    }

    private suspend fun extractPdfText(file: File): String? = try {
        java.io.FileInputStream(file).use { stream ->
            PdfExtractor.extractText(applicationContext, stream)
        }
    } catch (e: Throwable) {
        null
    }

    /** 手写 docx 解析（ZipFile 解 word/document.xml），兼容伪 docx（HTML套壳）。 */
    private fun extractDocxText(file: File): String? = try {
        java.util.zip.ZipFile(file).use { zip ->
            val entry = zip.getEntry("word/document.xml") ?: return@use null
            val xml = zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
            val text = Regex("<w:t[^>]*>([^<]*)</w:t>").findAll(xml)
                .joinToString("") { it.groupValues[1] }
            text.ifBlank { null }
        }
    } catch (e: Throwable) {
        null // 非 zip 容器（伪 docx / HTML 套壳），无法提取文本
    }

    private fun tryReadText(file: File): String? = try {
        val text = file.readText()
        text.ifBlank { null }
    } catch (e: Throwable) {
        null
    }

    companion object {
        const val KEY_FILE_PATH = "file_path"
    }
}
