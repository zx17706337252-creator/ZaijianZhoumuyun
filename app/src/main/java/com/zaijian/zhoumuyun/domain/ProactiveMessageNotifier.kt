package com.zaijian.zhoumuyun.domain

import android.app.NotificationManager
import com.zaijian.zhoumuyun.util.ZLog
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import com.zaijian.zhoumuyun.MainActivity
import com.zaijian.zhoumuyun.data.db.entity.MessageEntity
import com.zaijian.zhoumuyun.data.model.DefaultCharacters
import com.zaijian.zhoumuyun.data.repository.DaughterCharacterRepository
import com.zaijian.zhoumuyun.data.repository.MessageRepository
import java.util.UUID

private const val TAG = "ProactiveMsgNotifier"

/**
 * ProactiveMessageNotifier — 角色"主动发消息"的持久化 + 系统通知小工具类。
 *
 * ═══════════════════════════════════════════════════════════════
 * 职责（与 PresenceEngine 解耦，单独成类，便于 ChatViewModel/Worker 共用）：
 *
 *   1. 把 ProactiveMessage 真正写进 messages 表（role = characterId 字符串，
 *      与正常对话消息同一张表、同一套约定），保证用户之后打开聊天页能在
 *      历史记录里看到这条消息，而不是错过了就消失。
 *
 *   2. 查角色显示名：预设角色（1-9）查 DefaultCharacters，
 *      女儿角色（1000+）查 DaughterCharacterRepository.getCharacterConfig()，
 *      不直碰 DaughterCharacterEntity 的内部字段。
 *
 *   3. 发系统通知：复用 ZaijianApp.setupNotificationChannels() 里已经注册好的
 *      "character_message" 渠道，不重复建渠道。
 *
 *   4. 通知点击跳转：复用 MainActivity 已支持的 zaijian:// 深链接（UI M5 修复，原为自定义 action + extra）+
 *      "chat/{characterId}" 路由（与 FCM 推送跳转同一条路径，不新增机制）。
 *
 * 调用方：
 *   - PresenceEngine.refreshPresence() 的 persistAndNotify 回调
 *   - ProactiveMessageWorker（后台定时任务）
 * ═══════════════════════════════════════════════════════════════
 */
class ProactiveMessageNotifier(
    private val context: Context,
    private val messageDao: MessageRepository,
    private val daughterCharacterRepo: DaughterCharacterRepository? = null,
) {

    companion object {
        const val CHANNEL_ID = "character_message"  // 与 ZaijianApp.setupNotificationChannels() 保持一致，不重复创建
    }

    /**
     * 写入消息表 + （视情况）弹系统通知。
     *
     * @param suppressNotification 当前正在该角色聊天页时传 true，只持久化不弹通知
     *   （避免用户正盯着屏幕时还被系统通知打扰一次）。默认 false。
     */
    suspend fun persistAndNotify(
        message: ProactiveMessage,
        suppressNotification: Boolean = false,
    ) {
        val characterId = message.characterId

        // W1-008 修复：Worker 后台生成主动消息与用户前台聊天写入同一张 messages
        // 表，双方都用 System.currentTimeMillis() 作为 createdAt，但 Worker 执行
        // 时机不确定——如果用户恰好在此刻发了新消息，Worker 的插入时间戳可能晚于
        // 用户消息，导致"角色主动发来的消息"在时间顺序上排在了用户消息之后，
        // 语义错乱（主动消息本应是角色在用户说话之前发起的）。
        //
        // 取 max(该角色最新一条消息的 createdAt + 1, now) 作为本条消息的
        // createdAt，确保主动消息的时间戳不会早于已存在的最新消息（不会插到
        // 历史记录中间打乱顺序），同时仍然反映"这是在当前已知最新消息之后追加
        // 的一条"，不强求与用户消息的插入时机做原子级互斥——排序展示层面的
        // 近似修复足以解决"看起来乱序"的问题，不引入额外的跨表事务复杂度。
        val lastMessageAt = try {
            messageDao.getLastMessageAt(characterId)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            ZLog.w(TAG, "Read lastMessageAt failed for char $characterId, fallback to now", e)
            null
        }
        val now = System.currentTimeMillis()
        val createdAt = if (lastMessageAt != null) maxOf(lastMessageAt + 1, now) else now

        // 遗留问题修复（v1.38 批次2 前置发现）：此前这里直接用 message.text 落库，
        // 完全不经过 stripThinkingTag/stripPsychText/stripMoodTag 任何一层剥离——
        // 且不只是落库这一处，ChatViewModel 订阅 proactiveMessageFlow 展示的 Snackbar
        // 同样直接用原始 msg.text，是用户唯一能实时看到的界面，泄漏面比落库更直接。
        // 现已改为在 PresenceEngine.emitProactiveMessage()（广播+持久化的唯一共用出口）
        // 统一剥离，落库和 Snackbar 两个消费者都拿到同一份净文本，这里不需要
        // （也不应该）再剥离第二次——message.text/message.psychText/message.thinkingText
        // 到这里时已经是剥离后的结果，直接使用即可。
        //
        // 补齐内心独白卡（本次修复）：thinkingText 此前只在剥离时用于清洁正文，
        // 剥离出的内容本身被丢弃，导致主动消息落库后 MessageEntity.thinkingText
        // 恒为 null，聊天记录里主动消息不会出现"内心独白"折叠卡——与私聊消息
        // （ChatMessageOrchestrator 会存 thinkingText）待遇不一致。现在
        // emitProactiveMessage() 已经把 parsedThinking 写回 message.thinkingText，
        // 这里一并存入即可，不需要重新剥离。

        // 1. 持久化：与普通聊天消息同一张表，role 用 characterId 字符串
        try {
            messageDao.insert(
                MessageEntity(
                    id           = UUID.randomUUID().toString(),
                    characterId  = characterId,
                    role         = characterId.toString(),
                    content      = message.text,
                    createdAt    = createdAt,
                    psychText    = message.psychText,
                    thinkingText = message.thinkingText,
                )
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            ZLog.w(TAG, "Persist proactive message failed for char $characterId", e)
            return  // 写入失败就不发通知了，避免通知点进去却看不到对应消息
        }

        if (suppressNotification) return

        // 用户关闭了"消息通知"开关时，只落库不弹通知（聊天页之后照样能看到）
        val prefs = context.getSharedPreferences("user_profile", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("notify_messages", true)) return

        val characterName = resolveCharacterName(characterId)
        sendNotification(characterId, characterName, message.text)
    }

    /** 预设角色查 DefaultCharacters，女儿角色查 Repository，两边都查不到就用兜底名字 */
    private suspend fun resolveCharacterName(characterId: Int): String {
        DefaultCharacters.firstOrNull { it.id == characterId }?.let { return it.name }
        try {
            daughterCharacterRepo?.getCharacterConfig(characterId)?.let { return it.name }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            ZLog.w(TAG, "Resolve daughter name failed for char $characterId", e)
        }
        return "她"
    }

    private fun sendNotification(characterId: Int, characterName: String, text: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return

        // 点击通知 → 直接跳转到该角色的聊天页（复用 FCM 同款 "chat/{id}" 路由）
        // UI M5 修复：原自定义 action + extra 改为标准 ACTION_VIEW + zaijian:// 深链接
        val openIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("zaijian://${MainActivity.HOST_CHAT}/$characterId")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            characterId,  // requestCode 用 characterId，同一角色的新通知会覆盖旧的 PendingIntent
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(characterName)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        // notificationId 用 characterId，同一角色短时间内多条主动消息只保留最后一条通知，不刷屏
        nm.notify(characterId, notif)
    }
}
