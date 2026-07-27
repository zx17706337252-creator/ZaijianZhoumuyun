package com.zaijian.zhoumuyun.data.agent

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.zaijian.zhoumuyun.data.datastore.GithubConfigDataStore
import com.zaijian.zhoumuyun.util.ZLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

class BuildApkDownloadTool(
    private val context: Context,
    private val githubConfigStore: GithubConfigDataStore,
) : AgentTool {

    override val name = "build_apk_download"
    override val description = "下载已完成的编译产物APK并推送安装通知，需要run_id"
    override val paramKeys = listOf("run_id")

    // 修复：companion object 原为 private 且缺少 CHANNEL_NAME，
    // 导致 ZaijianApp.kt 统一注册通知渠道时无法访问 CHANNEL_ID/CHANNEL_NAME。
    // 参照 CiCdPipelineWorker 的写法改为公开 companion object。
    companion object {
        const val API_BASE = "https://api.github.com"
        const val CHANNEL_ID = "apk_download"
        const val CHANNEL_NAME = "APK 下载"
        const val NOTIFICATION_ID = 2001

        // P2 修复：下载体积上限。原 downloadZip 直接 copyTo 落盘，无任何大小校验，
        // 异常/被篡改的 artifact（或重定向到超大文件）可能把磁盘写满或导致 OOM。
        // 200MB 对正常 APK 产物足够宽裕，超过即中止下载。
        const val MAX_DOWNLOAD_BYTES = 200L * 1024 * 1024  // 200MB
    }

    override suspend fun execute(params: Map<String, String>): ToolResult =
        withContext(Dispatchers.IO) {
            val config = githubConfigStore.getConfig()
            if (!config.isConfigured) {
                return@withContext ToolResult(
                    toolName = name, success = false, content = "",
                    error = "GitHub 配置未完成，请先在设置中填写 owner / repo / token。",
                    userHint = "GitHub 未配置",
                )
            }

            val runId = params["run_id"]?.trim()
            if (runId.isNullOrBlank()) {
                return@withContext ToolResult(name, false, "", "缺少 run_id 参数")
            }

            try {
                val apkFile = downloadArtifact(config, runId)
                if (apkFile != null) {
                    sendDownloadNotification(apkFile)
                    ToolResult(
                        toolName = name,
                        success  = true,
                        content  = "APK 已下载到：${apkFile.absolutePath}",
                        userHint = "APK 下载完成",
                    )
                } else {
                    ToolResult(
                        toolName = name,
                        success  = false,
                        content  = "",
                        error    = "未找到可下载的 APK artifact，或编译尚未完成。",
                        userHint = "下载失败",
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                toolFailure(name, "下载 APK 失败，请稍后重试。", "build_apk_download_failed", e)
            }
        }

    private fun downloadArtifact(
        config: com.zaijian.zhoumuyun.data.datastore.GithubConfig,
        runId: String,
    ): File? {
        val artifactsUrl = "$API_BASE/repos/${config.owner}/${config.repo}/actions/runs/$runId/artifacts"

        val conn = (URL(artifactsUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout    = 10_000
            setRequestProperty("Accept",              "application/vnd.github+json")
            setRequestProperty("Authorization",       "Bearer ${config.token}")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }

        val artifactInfo: Pair<String, String>?
        try {
            if (conn.responseCode != 200) return null
            val json = conn.inputStream.bufferedReader().use { it.readText() }
            val artifacts = JSONObject(json).optJSONArray("artifacts") ?: return null
            if (artifacts.length() == 0) return null

            val first = artifacts.getJSONObject(0)
            val name = first.optString("name", "build")
            val downloadUrl = first.optString("archive_download_url", "")
            if (downloadUrl.isBlank()) return null
            artifactInfo = Pair(name, downloadUrl)
        } finally {
            conn.disconnect()
        }

        val (artifactName, archiveUrl) = artifactInfo ?: return null

        val zipFile = downloadZip(config, archiveUrl) ?: return null

        // P2 修复（Batch5审查问题：临时 zip 文件泄漏）：原实现仅在"正常路径"和
        // "extract 返回 null"两条分支里各自调 zipFile.delete()，一旦 extractApkFromZip
        // 抛异常（Zip Slip 校验失败、zip 损坏、IO 异常等），两条 delete 都走不到，
        // 临时文件会长期堆积在 cacheDir，最终可能导致磁盘占用异常增长。改为
        // try-finally 兜底，无论 extractApkFromZip 返回 null 还是抛异常都删除 zipFile。
        return try {
            extractApkFromZip(zipFile)
        } finally {
            zipFile.delete()
        }
    }

    private fun downloadZip(
        config: com.zaijian.zhoumuyun.data.datastore.GithubConfig,
        archiveUrl: String,
    ): File? {
        val conn = (URL(archiveUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 30_000
            readTimeout    = 60_000
            setRequestProperty("Accept",              "application/vnd.github+json")
            setRequestProperty("Authorization",       "Bearer ${config.token}")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            instanceFollowRedirects = true
        }

        return try {
            if (conn.responseCode !in 200..399) return null
            val tempZip = File(context.cacheDir, "apk_download_${System.currentTimeMillis()}.zip")
            // P2 修复：下载无大小限制。原实现用 input.copyTo(output) 一路写到底，
            // 异常/被篡改的 artifact（或重定向到超大文件）可能把磁盘写满或导致 OOM。
            // 改为手动循环读取并累计字节数，超过 MAX_DOWNLOAD_BYTES(200MB) 即中止并
            // 抛 IOException，由 execute() 的 catch 统一转成失败 ToolResult。
            var totalRead = 0L
            var exceededLimit = false
            try {
                conn.inputStream.use { input ->
                    FileOutputStream(tempZip).use { output ->
                        val buffer = ByteArray(8 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n == -1) break
                            totalRead += n
                            if (totalRead > MAX_DOWNLOAD_BYTES) {
                                exceededLimit = true
                                break
                            }
                            output.write(buffer, 0, n)
                        }
                    }
                }
            } finally {
                // 超限或异常时清理半成品文件，避免 cacheDir 残留
                if (exceededLimit || (tempZip.exists() && tempZip.length() == 0L)) {
                    tempZip.delete()
                }
            }
            if (exceededLimit) {
                throw IOException("下载体积超过上限 ${MAX_DOWNLOAD_BYTES / (1024 * 1024)}MB，已中止")
            }
            if (tempZip.length() == 0L) { tempZip.delete(); return null }
            tempZip
        } finally {
            conn.disconnect()
        }
    }

    private fun extractApkFromZip(zipFile: File): File? {
        val zipInputStream = ZipInputStream(zipFile.inputStream())
        var apkFile: File? = null
        // P2 修复（Batch5审查报告问题13）：原实现直接用 entry.name 拼路径落盘，
        // 只用 entry.name.endsWith(".apk") 过滤文件类型，未校验 entry.name 是否
        // 含 "../" 路径穿越——恶意/被篡改的 GitHub Actions 产物 ZIP 可构造
        // entry.name="../../../data/data/xxx/shared_prefs/xxx.apk" 之类的条目
        // 写到 builds 目录之外。与 FileSystemTools.zip_extract 的 Zip Slip 防护
        // 用同一套 canonicalPath.startsWith() 校验对齐。
        val buildsDir = File(context.filesDir, "builds")

        try {
            var entry = zipInputStream.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.endsWith(".apk")) {
                    val outFile = File(buildsDir, entry.name)
                    if (!outFile.canonicalPath.startsWith(buildsDir.canonicalPath + File.separator)) {
                        throw SecurityException("Zip Slip 检测：非法路径 ${entry.name}")
                    }
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { output ->
                        zipInputStream.copyTo(output)
                    }
                    outFile.setReadable(true, false)
                    apkFile = outFile
                    break
                }
                zipInputStream.closeEntry()
                entry = zipInputStream.nextEntry
            }
        } finally {
            zipInputStream.close()
        }

        return apkFile
    }

    private fun sendDownloadNotification(apkFile: File) {
        // P2 修复：通知发送失败不影响下载结果。走到这里说明 APK 已成功下载落盘，
        // 通知只是引导用户点击安装的辅助提示，不应因通知渠道未注册/PendingIntent
        // 异常/NotificationManager 异常等把一次成功的下载拖成失败 ToolResult。
        // 包一层 try-catch，失败仅记日志，execute() 仍按 success=true 返回。
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // S3问题7修复：渠道创建已收敛至 ZaijianApp.setupNotificationChannels()
            // 此处不再自行创建，直接使用已注册的渠道

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile,
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }

            val pendingIntent = PendingIntent.getActivity(
                context, 0, installIntent, flags,
            )

            val notif = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_upload)
                .setContentTitle("✅ 编译完成")
                .setContentText("APK 已下载，点击安装")
                .setStyle(NotificationCompat.BigTextStyle().bigText("APK 已保存到：${apkFile.name}"))
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

            nm.notify(NOTIFICATION_ID, notif)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            ZLog.w("BuildApkDownloadTool", "下载完成通知发送失败: ${e.message}")
        }
    }
}
