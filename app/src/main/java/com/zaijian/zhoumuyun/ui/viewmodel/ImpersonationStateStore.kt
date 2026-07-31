package com.zaijian.zhoumuyun.ui.viewmodel

import android.content.Context
import com.zaijian.zhoumuyun.util.ZLog
import org.json.JSONObject

/**
 * 假扮状态轻量持久化存储（B2 审查报告问题 #1 修复）。
 *
 * 背景：`ChatUiState.impersonationByCharacter: Map<Int, String?>` 是主聊天路径
 * SpeakerContext 的唯一判定来源（见 [ChatMessageOrchestrator] :456-476），但只存在
 * `ChatViewModel._uiState` 纯内存 StateFlow 里。ChatViewModel 虽是 Activity 作用域
 * 单例，但进程被系统杀死后随之销毁，重建后 `impersonationByCharacter` 复位为
 * `emptyMap()`——用户正处于假扮会话中时，记忆隔离/关系值跳过/ReplyGuard 三项
 * 保护会短暂失效，直到用户再次说出"我不是主人，我是XX"。
 *
 * 方案取舍（对应报告"方案一/方案二"之外的第三种，详见问题排查记录）：
 * - 方案一（SavedStateHandle）：需把 `ChatViewModel` 从 `AndroidViewModel` 改造成
 *   `ViewModel` + `SavedStateHandle` 构造，且要同步改 `ChatScreen.kt` 的
 *   ViewModelFactory 一侧，影响面大。
 * - 方案二（从 `messages.speakerContext` 恢复）：`SpeakerContext` 枚举本身只有
 *   `OWNER_DIRECT`/`NON_OWNER` 两态，不含"具体假扮的是谁"这个信息——而
 *   `impersonationByCharacter` 存的是具体假扮名字（`String?`），这个名字要
 *   继续喂给 `characterTitleRelationRepo` 查头衔、生成 prompt patch。方案二只能
 *   恢复"是否处于 NON_OWNER"的布尔态，恢复不出具体名字，链路会断裂。
 * - 本方案：按 characterId 分片，用 SharedPreferences 持久化"具体假扮的名字"，
 *   不新增 DB 列 / 不需要 Migration（避免再欠一次 Migration 测试debt），语义上
 *   也更贴合原注释"不落库、不进长期记忆"的设计意图——SharedPreferences 不是
 *   "长期记忆表"。风格参照 [com.zaijian.zhoumuyun.data.privatechat.PrivateChatEngine]
 *   kill switch 的 SharedPreferences 用法（同为"App 内配置态"而非业务数据）。
 *
 * 写入侧：[ChatMessageOrchestrator.sendMessage] 每次更新
 * `impersonationByCharacter` 时（进入假扮 / 解除假扮）同步调用 [save]。
 * 恢复侧：[ChatSessionDelegate.init] 的 `loadCharacterJob` 块中，若
 * `impersonationByCharacter` 尚不含该 characterId 的记录（说明进程刚重建，
 * 而非用户在本次进程内已经查过、確認未假扮），调用 [load] 回填。
 *
 * 只持久化"当前正在假扮"这一有效状态：解除假扮（[save] 传入 `null`）时直接从
 * SharedPreferences 里删除该条目，而不是写入一个"null 占位"——因为恢复时"无记录"
 * 与"确认未假扮"在效果上等价（都是不触发保护的默认态 OWNER_DIRECT），没有必要
 * 额外持久化"未假扮"这一状态本身。
 */
object ImpersonationStateStore {
    private const val TAG = "ImpersonationStateStore"
    private const val PREFS_NAME = "impersonation_state_prefs"
    private const val KEY_MAP = "impersonation_by_character"

    /**
     * 持久化（或清除）指定角色的假扮状态。
     *
     * @param impersonatedName 具体假扮的名字；传 `null` 表示解除假扮，会从存储中
     * 移除该 characterId 对应的条目。
     */
    fun save(context: Context, characterId: Int, impersonatedName: String?) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val json = JSONObject(prefs.getString(KEY_MAP, null) ?: "{}")
            if (impersonatedName != null) {
                json.put(characterId.toString(), impersonatedName)
            } else {
                json.remove(characterId.toString())
            }
            prefs.edit().putString(KEY_MAP, json.toString()).apply()
        } catch (e: Throwable) {
            // 持久化失败不应影响主流程（内存里的 impersonationByCharacter 仍然正确，
            // 只是本次进程死亡后无法恢复）——记录日志，不向上抛出。
            ZLog.e(TAG, "save 失败，characterId=$characterId，本次假扮状态本地持久化跳过", e)
        }
    }

    /** 读取指定角色当前持久化的假扮名字，无记录时返回 null（视为未假扮）。 */
    fun load(context: Context, characterId: Int): String? {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val json = JSONObject(prefs.getString(KEY_MAP, null) ?: "{}")
            json.optString(characterId.toString(), null)
        } catch (e: Throwable) {
            ZLog.e(TAG, "load 失败，characterId=$characterId，按未假扮处理", e)
            null
        }
    }
}
