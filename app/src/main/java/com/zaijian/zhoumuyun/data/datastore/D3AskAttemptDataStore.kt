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
     * 取"如果这次把问题问出去，这是第几次"——即已记录次数 + 1。
     * 还没问过的槎位返回 1（第一次）。
     *
     * 只读取，不写入——计数的写入由 [recordAsked] 单独负责，确保只有
     * 「文案确实生成并注入 system prompt」这件事发生时才会让计数增长，
     * 门控提前 return（怀孕状态不符 / 已有挂起问题 / 全部槎位已锁定）
     * 的分支不会误增计数。
     */
    suspend fun nextAttemptNumber(
        characterId: Int,
        questionType: PregnancyQuestionType,
        slotIndex: Int,
    ): Int {
        val prefs = context.d3AskAttemptDataStore.safeData().first()
        val asked = prefs[attemptKey(characterId, questionType, slotIndex)] ?: 0
        return asked + 1
    }

    /** D3-② 门控通过、确实把本轮情境文案注入 system prompt 后调用，计数 +1。 */
    // 审查项 3.14：写入失败时 safeEdit 记录日志并返回，计数这次不会 +1，
    // 属于可接受的降级（下次调用 nextAttemptNumber 仍能正常读到旧值继续走）。
    suspend fun recordAsked(
        characterId: Int,
        questionType: PregnancyQuestionType,
        slotIndex: Int,
    ) {
        context.d3AskAttemptDataStore.safeEdit { prefs ->
            val k = attemptKey(characterId, questionType, slotIndex)
            prefs[k] = (prefs[k] ?: 0) + 1
        }
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
