package com.zaijian.zhoumuyun.data.agent

import android.content.Context
import com.zaijian.zhoumuyun.util.MediaInfoExtractor
import com.zaijian.zhoumuyun.util.MediaInfoExtractor.MediaInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * MediaInfoTool — media_info 工具。
 *
 * 读取音视频的时长 / 分辨率 / 编码 / 封面帧等元数据，返回 JSON 格式。
 *
 * ═══════════════════════════════════════════════════════════════
 * 参数
 * ═══════════════════════════════════════════════════════════════
 * - file_path（必填）：音视频文件路径
 *
 * ═══════════════════════════════════════════════════════════════
 * 安全
 * ═══════════════════════════════════════════════════════════════
 * 与 file_read 同一套规则：先 [hasPathTraversal] 拦穿越字符，再
 * [resolveVaultPath] 叠加保险库三段权限校验。
 *
 * ═══════════════════════════════════════════════════════════════
 * 异常处理
 * ═══════════════════════════════════════════════════════════════
 * 遵循金标准模式：先 rethrow CancellationException，再 catch Throwable 兜底，
 * 统一走 [toolFailure] 返回稳定错误码 "media_info_failed"。
 */
class MediaInfoTool(
    private val context: Context,
    private val characterIdProvider: () -> Int = { VaultCallContextHolder.get().characterId },
) : AgentTool {

    override val name = "media_info"
    override val description = "读取音视频的时长/分辨率/编码/封面帧等元数据"
    override val paramKeys = listOf("file_path")

    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val filePath = params["file_path"]?.trim()
        if (filePath.isNullOrEmpty()) {
            return@withContext ToolResult(name, false, "", error = "missing file_path")
        }

        if (hasPathTraversal(filePath)) {
            return@withContext ToolResult(name, false, "无法访问该路径。", error = "路径包含非法字符")
        }

        // v147：保险库权限校验。
        val file = when (val r = resolveVaultPath(context, filePath, characterIdProvider)) {
            is VaultPathResolution.Allowed -> r.file
            is VaultPathResolution.Denied -> return@withContext ToolResult(name, false, "无法访问该路径。", r.reason)
        }

        try {
            if (!file.exists() || !file.isFile) {
                return@withContext ToolResult(name, false, "找不到文件「$filePath」。")
            }

            val info: MediaInfo = MediaInfoExtractor.extractInfo(file.absolutePath)
            val json = mediaInfoToJson(info)
            ToolResult(
                toolName = name,
                success = true,
                content = "[媒体元数据]\n$json",
                userHint = "正在读取媒体信息…",
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            toolFailure(name, "媒体信息读取时遇到问题。", "media_info_failed", e)
        }
    }

    /**
     * 把 [MediaInfo] 序列化为可读 JSON（2 空格缩进），null 字段输出为 JSON null。
     */
    private fun mediaInfoToJson(info: MediaInfo): String =
        JSONObject().apply {
            put("durationMs", info.durationMs ?: JSONObject.NULL)
            put("width", info.width ?: JSONObject.NULL)
            put("height", info.height ?: JSONObject.NULL)
            put("bitrate", info.bitrate ?: JSONObject.NULL)
            put("mimeType", info.mimeType ?: JSONObject.NULL)
            put("videoCodec", info.videoCodec ?: JSONObject.NULL)
            put("audioCodec", info.audioCodec ?: JSONObject.NULL)
            put("title", info.title ?: JSONObject.NULL)
            put("artist", info.artist ?: JSONObject.NULL)
            put("album", info.album ?: JSONObject.NULL)
            put("date", info.date ?: JSONObject.NULL)
            put("location", info.location ?: JSONObject.NULL)
        }.toString(2)
}
