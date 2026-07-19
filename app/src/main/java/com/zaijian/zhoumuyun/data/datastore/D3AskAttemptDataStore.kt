package com.zaijian.zhoumuyun.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.zaijian.zhoumuyun.data.db.entity.PregnancyQuestionType
import kotlinx.coroutines.flow.first

// ─────────────────────────────────────────────────────────────
//  D3AskAttemptDataStore — D3 提问触发器·每个槎位「问到第几次」计数
//
//  背景：domain/pregnancy/QuestionSlot 本身只记录 questionType + slotIndex，
//  不记录"这是第几次问这个槎位"——但 D3TriggerContent（情境化文案库）
//  里每个槎位最多准备了 3 套不同情境文案（对应"日常 → 情绪渐沉 → 临产前"
//  的递进），需要知道第几次才能取对文案。这一层信息原项目里没有任何地方
//  在追踪，单独用一个轻量 DataStore 记一份计数，不动 QuestionSlot /
//  PregnancyAnswerRepository 现有结构。
//
//  写法对齐 AppearanceDataStore.kt / PregnancyPressureDataStore 的现有约定：
//  by preferencesDataStore() 必须声明在文件顶层，不能放在类内部
//  （同名 DataStore 被实例化第二次会抛 IllegalStateException）。
// ─────────────────────────────────────────────────────────────

private val Context.d3AskAttemptDataStore: DataStore<Preferences>
        by preferencesDataStore(name = "d3_ask_attempt")

class D3AskAttemptDataStore(private val context: Context) {

    private fun attemptKey(characterId: Int, questionType: PregnancyQuestionType, slotIndex: Int) =
        intPreferencesKey("attempt_${characterId}_${questionType.name}_$slotIndex")

    /**
     * P2-9 修复：原子化"读取→+1→返回"操作。
     * 原先 nextAttemptNumber() 只读、recordAsked() 只写，两步之间
     * 存在 read-modify-write 竞态——两个并发调用可能同时读到旧值 0，
     * 各自计算 +1，各自写入 1，导致计数丢失一次。
     *
     * 改为在 safeEdit 内原子完成"读取→+1→写入→返回新值"，
     * DataStore 的 safeEdit 串行化所有写入，消除竞态。
     *
     * @return 本次递增后的值（即第几次问），供调用方取对应文案
     */
    suspend fun nextAttemptNumberAndRecord(
        characterId: Int,
        questionType: PregnancyQuestionType,
        slotIndex: Int,
    ): Int {
        var newValue = 0
        context.d3AskAttemptDataStore.safeEdit { prefs ->
            val k = attemptKey(characterId, questionType, slotIndex)
            val current = prefs[k] ?: 0
            newValue = current + 1
            prefs[k] = newValue
        }
        return newValue
    }

    /**
     * 槎位锁定（recordAnswer 返回 Locked / ForceLocked）后调用，
     * 清掉这个槎位的计数——槎位锁定后业务上不会再问，清零是更安全的
     * 默认行为（万一未来支持"二胎"之类场景需要重新计数，不会被旧值污染）。
     */
    suspend fun clear(
        characterId: Int,
        questionType: PregnancyQuestionType,
        slotIndex: Int,
    ) {
        context.d3AskAttemptDataStore.safeEdit { prefs ->
            prefs.remove(attemptKey(characterId, questionType, slotIndex))
        }
    }
}
