package com.zaijian.zhoumuyun.ui.viewmodel

import com.zaijian.zhoumuyun.data.db.AppDatabase
import com.zaijian.zhoumuyun.data.provider.ProviderManager
import com.zaijian.zhoumuyun.data.repository.MemoryRepository
import com.zaijian.zhoumuyun.domain.DistillationEngine
import com.zaijian.zhoumuyun.domain.EvaluationEngine
import com.zaijian.zhoumuyun.util.ZLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 评分引擎 + 规则提炼引擎委托，从 ChatViewModel 中提取。
 *
 * Provider 变更时重建实例（方案B），保持 lastSessionAt/lastDistillAt 冷却去重缓存
 * 不被清空——与 ZaijianApp.kt 中 CompetitionEngine 的重建模式一致。
 */
class ChatEvaluationDelegate(
    private val _uiState: MutableStateFlow<ChatUiState>,
    private val db: AppDatabase,
    private val memoryRepo: MemoryRepository,
    private val viewModelScope: CoroutineScope,
    private val getCurrentCharacterId: () -> Int,
) {
    @Volatile private var evaluationEngine: EvaluationEngine? = null
    @Volatile private var distillationEngine: DistillationEngine? = null

    /** 供 ChatMessageOrchestrator 消费的引擎引用 */
    fun getEvaluationEngine(): EvaluationEngine? = evaluationEngine

    /** Provider 监听器 lambda，由 ChatViewModel 注册/反注册到 ProviderManager */
    val providerConfigListener: () -> Unit = { rebuildEngines() }

    /** 依据当前 activeProvider 重建引擎；provider 未配置时置空。 */
    fun rebuildEngines() {
        val p = ProviderManager.instance.activeProvider
        evaluationEngine = p?.let {
            EvaluationEngine(
                evaluationSessionDao = db.evaluationSessionDao(),
                learningGoalDao      = db.learningGoalDao(),
                provider             = it,
            )
        }
        distillationEngine = p?.let {
            DistillationEngine(
                db                   = db,
                evaluationSessionDao = db.evaluationSessionDao(),
                learningGoalDao      = db.learningGoalDao(),
                memoryDao            = db.memoryDao(),
                provider             = it,
                memoryRepo           = memoryRepo,
            )
        }
    }

    /** 提交用户评分，完成 Session（SCORED），若满足条件触发规则提炼。 */
    fun submitEvaluationScore(score: Int) {
        val sessionId = _uiState.value.pendingEvaluationSessionId ?: return
        _uiState.update { it.copy(
            pendingEvaluationSessionId = null,
            pendingEvaluationReport    = null,
            pendingAgentScore          = null,
        ) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val engine = evaluationEngine ?: run {
                    ZLog.w("ChatEvaluationDelegate", "submitEvaluationScore: provider 未初始化，跳过打分")
                    return@launch
                }
                val compositeScore = engine.submitUserScore(
                    sessionId = sessionId,
                    userScore = score,
                )
                if (compositeScore == null) {
                    ZLog.w("ChatEvaluationDelegate", "submitEvaluationScore: Session $sessionId 不存在或状态不符")
                    return@launch
                }

                val session = db.evaluationSessionDao().getById(sessionId) ?: return@launch
                val goalId = session.goalId ?: return@launch

                val distillResult = try {
                    distillationEngine?.maybeDistill(
                        characterId = getCurrentCharacterId(),
                        goalId      = goalId,
                    )
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    ZLog.w("ChatEvaluationDelegate", "maybeDistill 异常（不影响打分结果）", e)
                    null
                }

                if (distillResult?.triggered == true) {
                    _uiState.update { it.copy(
                        pendingDistillResult = DistillResult(
                            triggered        = true,
                            newlyLockedCount = distillResult.newlyLockedCount,
                            goalTitle        = distillResult.goalTitle,
                            progressDelta    = distillResult.progressDelta,
                        )
                    ) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                ZLog.w("ChatEvaluationDelegate", "submitEvaluationScore 异常", e)
            }
        }
    }

    /** 用户跳过评分，清除 UI 弹窗状态。 */
    fun skipEvaluation() {
        _uiState.update { it.copy(
            pendingEvaluationSessionId = null,
            pendingEvaluationReport = null,
            pendingAgentScore = null,
        ) }
    }
}
