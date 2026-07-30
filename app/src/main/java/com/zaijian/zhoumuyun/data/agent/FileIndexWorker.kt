package com.zaijian.zhoumuyun.data.agent

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.zaijian.zhoumuyun.data.db.AppDatabase
import com.zaijian.zhoumuyun.data.db.entity.FileIndexEntity
import com.zaijian.zhoumuyun.util.PdfExtractor
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
            // 索引失败不阻塞调用方，返回 success 避免无限重试
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
