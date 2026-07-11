package com.zaijian.zhoumuyun.data.agent

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.zaijian.zhoumuyun.data.datastore.GithubConfigDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

class BuildApkDownloadTool(
    private val context: Context,
    private val githubConfigStore: GithubConfigDataStore,
) : AgentTool {

    override val name = "build_apk_download"
    override val paramKeys = listOf("run_id")

    private companion object {
        const val API_BASE = "https://api.github.com"
        const val CHANNEL_ID = "apk_download"
        const val NOTIFICATION_ID = 2001
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
            } catch (e: Exception) {
                ToolResult(
                    toolName = name,
                    success  = false,
                    content  = "",
                    error    = "下载失败：${e.message?.take(120)}",
                    userHint = "下载失败",
                )
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

        val (artifactName, archiveUrl) = artifactInfo

        val zipFile = downloadZip(config, archiveUrl) ?: return null

        val apkFile = extractApkFromZip(zipFile) ?: run {
            zipFile.delete()
            null
        }

        zipFile.delete()
        return apkFile
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
            conn.inputStream.use { input ->
                FileOutputStream(tempZip).use { output ->
                    input.copyTo(output)
                }
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

        try {
            var entry = zipInputStream.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.endsWith(".apk")) {
                    val outFile = File(context.filesDir, "builds/${entry.name}")
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
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                CHANNEL_ID,
                "APK 下载",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "编译完成的 APK 下载通知" }
            nm.createNotificationChannel(channel)
        }

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
    }
}
