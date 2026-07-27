package com.zaijian.zhoumuyun.ui.viewmodel

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zaijian.zhoumuyun.data.AppContainer
import com.zaijian.zhoumuyun.data.db.AppDatabase
import com.zaijian.zhoumuyun.data.db.entity.CharacterIdentityEntity
import com.zaijian.zhoumuyun.util.ZLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream

// ─────────────────────────────────────────────────────────────
//  IdentityViewModel（Phase 15 升级）
//
//  Phase 15 新增：
//  ① boundaries（禁忌列表）的读写和编辑操作
//  ② coreBeliefs（核心信念列表）的读写和编辑操作
//
//  DB 层已有 boundariesJson / corebeliefsJson（JSON 数组字符串），
//  这里负责在 List<String> ↔ JSON 之间转换。
// ─────────────────────────────────────────────────────────────

data class IdentityUiState(
    val persona: String             = "",
    val speechStyle: String         = "",
    val attitudeToUser: String      = "",
    val boundaries: List<String>    = emptyList(),   // ★ Phase 15
    val coreBeliefs: List<String>   = emptyList(),   // ★ Phase 15
    val customSystemPrompt: String  = "",
    val isLoading: Boolean          = false,
    val isSaved: Boolean            = false,
    // ── Phase 1（zaijian）三层内核字段 ──────────────────────
    val coreWound: String           = "",
    val coreDesire: String          = "",
    val maskTrigger: String         = "",
    val privatePersona: String      = "",
    val privateStyle: String        = "",
    val privateExamples: String     = "",
    val situationRules: String      = "",
    val deviationSignals: String    = "",
    // ── 附加（NyxChat V18 A.1/A.2）──────────────────────────
    val likes: String               = "",
    val dislikes: String            = "",
    val relationships: String       = "",
    // ── Soul/Memory/User 三模块 ──────────────────────────────
    val soulNote: String            = "",
    val narrativeMemory: String     = "",
    val userImpression: String      = "",
    val lastEditedNoteField: String? = null,
    val lastEditedNoteAt: Long      = 0,
    // ── 头像 ─────────────────────────────────────────────────
    val avatarUrl: String           = "",
    // v46 头像重新设计：avatarUrl 语义变为「原图路径」，新增两套裁剪参数
    val avatarCropCircleOffsetX: Float = 0f,
    val avatarCropCircleOffsetY: Float = 0f,
    val avatarCropCircleScale: Float   = 1f,
    // v56→v57 公馆/书架头像独立化：公馆与书架从共用一张原图+一套裁剪参数
    // 拆分为完全独立的两套。avatarCropTall* 字段名不变，语义收窄为「仅公馆」。
    val avatarUrlTall: String         = "",
    val avatarCropTallOffsetX: Float   = 0f,
    val avatarCropTallOffsetY: Float   = 0f,
    val avatarCropTallScale: Float     = 1f,
    val avatarUrlShelf: String         = "",
    val avatarCropShelfOffsetX: Float  = 0f,
    val avatarCropShelfOffsetY: Float  = 0f,
    val avatarCropShelfScale: Float    = 1f,
    val avatarError: String?        = null,
    // ── v1.36 问题3：用户身份设定（性别 + 关系称谓）───────────
    val userGender: String            = "MALE",
    val userRoleLabelPrivate: String  = "",
    val userRoleLabelPublic: String   = "",
    val publicPrivacyReason: String   = "",
)

class IdentityViewModel(application: Application) : AndroidViewModel(application) {

    // 阶段2 S-1 批次2收口：identityDao 原先独立 new（构造参数与容器完全一致），改引用
    // AppContainer 共享实例。
    private val identityDao = AppContainer.instance.identityRepo

    // [AUDIT-WONTFIX S8-窗口01] promotedSkillTagDao 保留裸访问，不纳入 AppContainer。
    // 理由：全项目仅此一处调用点，无重复构造问题，抽象成 Repository/容器字段
    // 收益为零、纯增加间接层。AppContainer 的收敛价值在于消除"多处独立 new
    // 同参数实例"，单一调用点不适用该逻辑。后续复审请勿再判定为"裸装配"待修项。
    private val promotedSkillTagDao = AppDatabase.getInstance(application).promotedSkillTagDao()

    private val _uiState = MutableStateFlow(IdentityUiState(isLoading = true))
    val uiState: StateFlow<IdentityUiState> = _uiState.asStateFlow()

    private var currentCharacterId: Int = -1
    private var observeJob: Job? = null
    private var skillTagsJob: Job? = null

    // ── 擅长领域标签墙（能力Tab）──────────────────────────────
    //  此前 CharacterDetailAbility.getSkillTags() 是硬编码占位符，
    //  与专长进化系统的晋升流程完全没有接通。现在从 promoted_skill_tags
    //  表读取真实数据：只有真正走完晋升流程、被用户确认过的特质才会
    //  出现在这里，未晋升过的角色是空列表（UI 需要处理空状态）。
    private val _skillTags = MutableStateFlow<List<String>>(emptyList())
    val skillTags: StateFlow<List<String>> = _skillTags.asStateFlow()

    // ── 初始化：读取当前角色的 Identity（响应式 Flow）──────────
    //  改用 observeById Flow 替代一次性 getById，使 DB 中 avatarUrl
    //  或其他字段被外部修改时，UI 能自动刷新。

    fun init(characterId: Int) {
        if (currentCharacterId == characterId) return
        currentCharacterId = characterId
        observeJob?.cancel()
        skillTagsJob?.cancel()

        // 问题18修复：立即重置为初始 loading 态，避免上一个角色的未保存编辑内容
        // （current.isSaved == false 时）在新角色的 DB 数据到达前短暂残留、
        // 混入新角色界面，形成跨角色状态泄漏。与 ProjectViewModel.openProject()
        // 对 _detailState 的处理策略对齐。
        _uiState.value = IdentityUiState(isLoading = true)

        skillTagsJob = viewModelScope.launch {
            promotedSkillTagDao.observeAllForCharacter(characterId).collect { entities ->
                _skillTags.value = entities.map { it.tag }
            }
        }
        observeJob = viewModelScope.launch {
            identityDao.observeById(characterId).collect { entity ->
                _uiState.update { current ->
                    // 首次加载或已保存（无未保存编辑）时，完整同步 DB
                    if (current.isLoading || current.isSaved) {
                        IdentityUiState(
                            persona            = entity?.persona ?: "",
                            speechStyle        = entity?.speechStyle ?: "",
                            attitudeToUser     = entity?.attitudeToUser ?: "",
                            boundaries         = parseJsonArray(entity?.boundariesJson ?: "[]"),
                            coreBeliefs        = parseJsonArray(entity?.corebeliefsJson ?: "[]"),
                            customSystemPrompt = entity?.customSystemPrompt ?: "",
                            isLoading          = false,
                            isSaved            = true,
                            coreWound          = entity?.coreWound ?: "",
                            coreDesire         = entity?.coreDesire ?: "",
                            maskTrigger        = entity?.maskTrigger ?: "",
                            privatePersona     = entity?.privatePersona ?: "",
                            privateStyle       = entity?.privateStyle ?: "",
                            privateExamples    = entity?.privateExamples ?: "",
                            situationRules     = entity?.situationRules ?: "",
                            deviationSignals   = entity?.deviationSignals ?: "",
                            likes              = entity?.likes ?: "",
                            dislikes           = entity?.dislikes ?: "",
                            relationships      = entity?.relationships ?: "",
                            soulNote           = entity?.soulNote ?: "",
                            narrativeMemory    = entity?.narrativeMemory ?: "",
                            userImpression     = entity?.userImpression ?: "",
                            lastEditedNoteField = entity?.lastEditedNoteField,
                            lastEditedNoteAt   = entity?.lastEditedNoteAt ?: 0,
                            avatarUrl          = entity?.avatarUrl ?: "",
                            avatarCropCircleOffsetX = entity?.avatarCropCircleOffsetX ?: 0f,
                            avatarCropCircleOffsetY = entity?.avatarCropCircleOffsetY ?: 0f,
                            avatarCropCircleScale   = entity?.avatarCropCircleScale ?: 1f,
                            avatarUrlTall           = entity?.avatarUrlTall ?: "",
                            avatarCropTallOffsetX   = entity?.avatarCropTallOffsetX ?: 0f,
                            avatarCropTallOffsetY   = entity?.avatarCropTallOffsetY ?: 0f,
                            avatarCropTallScale     = entity?.avatarCropTallScale ?: 1f,
                            avatarUrlShelf          = entity?.avatarUrlShelf ?: "",
                            avatarCropShelfOffsetX  = entity?.avatarCropShelfOffsetX ?: 0f,
                            avatarCropShelfOffsetY  = entity?.avatarCropShelfOffsetY ?: 0f,
                            avatarCropShelfScale    = entity?.avatarCropShelfScale ?: 1f,
                            // v1.36 问题3
                            userGender              = entity?.userGender ?: "MALE",
                            userRoleLabelPrivate    = entity?.userRoleLabelPrivate ?: "",
                            userRoleLabelPublic     = entity?.userRoleLabelPublic ?: "",
                            publicPrivacyReason     = entity?.publicPrivacyReason ?: "",
                        )
                    } else {
                        // 用户有未保存编辑，仅同步头像相关字段（三处头像各自的
                        // 上传/裁剪都是外部变更），保留用户正在编辑的文本字段，
                        // 避免丢失输入
                        current.copy(
                            avatarUrl = entity?.avatarUrl ?: "",
                            avatarCropCircleOffsetX = entity?.avatarCropCircleOffsetX ?: 0f,
                            avatarCropCircleOffsetY = entity?.avatarCropCircleOffsetY ?: 0f,
                            avatarCropCircleScale   = entity?.avatarCropCircleScale ?: 1f,
                            avatarUrlTall           = entity?.avatarUrlTall ?: "",
                            avatarCropTallOffsetX   = entity?.avatarCropTallOffsetX ?: 0f,
                            avatarCropTallOffsetY   = entity?.avatarCropTallOffsetY ?: 0f,
                            avatarCropTallScale     = entity?.avatarCropTallScale ?: 1f,
                            avatarUrlShelf          = entity?.avatarUrlShelf ?: "",
                            avatarCropShelfOffsetX  = entity?.avatarCropShelfOffsetX ?: 0f,
                            avatarCropShelfOffsetY  = entity?.avatarCropShelfOffsetY ?: 0f,
                            avatarCropShelfScale    = entity?.avatarCropShelfScale ?: 1f,
                            isLoading = false,
                        )
                    }
                }
            }
        }
    }

    // ── 简单字段更新 ─────────────────────────────────────────

    fun onPersonaChange(v: String)            = _uiState.update { it.copy(persona = v, isSaved = false) }
    fun onSpeechStyleChange(v: String)        = _uiState.update { it.copy(speechStyle = v, isSaved = false) }
    fun onAttitudeToUserChange(v: String)     = _uiState.update { it.copy(attitudeToUser = v, isSaved = false) }
    fun onCustomSystemPromptChange(v: String) = _uiState.update { it.copy(customSystemPrompt = v, isSaved = false) }

    // ── v1.36 问题3：用户身份设定（性别 + 关系称谓）─────────────
    fun onUserGenderChange(v: String)            = _uiState.update { it.copy(userGender = v, isSaved = false) }
    fun onUserRoleLabelPrivateChange(v: String)  = _uiState.update { it.copy(userRoleLabelPrivate = v, isSaved = false) }
    fun onUserRoleLabelPublicChange(v: String)   = _uiState.update { it.copy(userRoleLabelPublic = v, isSaved = false) }
    fun onPublicPrivacyReasonChange(v: String)   = _uiState.update { it.copy(publicPrivacyReason = v, isSaved = false) }

    // ── Phase 1（zaijian）内核字段更新 ──────────────────────
    fun onCoreWoundChange(v: String)        = _uiState.update { it.copy(coreWound = v, isSaved = false) }
    fun onCoreDesireChange(v: String)       = _uiState.update { it.copy(coreDesire = v, isSaved = false) }
    fun onMaskTriggerChange(v: String)      = _uiState.update { it.copy(maskTrigger = v, isSaved = false) }
    fun onPrivatePersonaChange(v: String)   = _uiState.update { it.copy(privatePersona = v, isSaved = false) }
    fun onPrivateStyleChange(v: String)     = _uiState.update { it.copy(privateStyle = v, isSaved = false) }
    fun onPrivateExamplesChange(v: String)  = _uiState.update { it.copy(privateExamples = v, isSaved = false) }
    fun onSituationRulesChange(v: String)   = _uiState.update { it.copy(situationRules = v, isSaved = false) }
    fun onDeviationSignalsChange(v: String) = _uiState.update { it.copy(deviationSignals = v, isSaved = false) }

    // ── 附加（NyxChat V18 A.1/A.2）──────────────────────────
    fun onLikesChange(v: String)         = _uiState.update { it.copy(likes = v, isSaved = false) }
    fun onDislikesChange(v: String)      = _uiState.update { it.copy(dislikes = v, isSaved = false) }
    fun onRelationshipsChange(v: String) = _uiState.update { it.copy(relationships = v, isSaved = false) }

    // ── Soul/Memory/User 三模块 ──────────────────────────────
    fun onSoulNoteChange(v: String)        = _uiState.update { it.copy(soulNote = v, isSaved = false) }
    fun onNarrativeMemoryChange(v: String) = _uiState.update { it.copy(narrativeMemory = v, isSaved = false) }
    fun onUserImpressionChange(v: String)  = _uiState.update { it.copy(userImpression = v, isSaved = false) }

    fun undoLastNoteEdit() {
        val field = _uiState.value.lastEditedNoteField ?: return
        viewModelScope.launch {
            // 第8窗口问题7修复：原先无 try-catch，DB 写入失败时异常会传播到
            // viewModelScope，可能导致崩溃。补上异常处理，失败时写入 avatarError。
            try {
                when (field) {
                    "soul"   -> identityDao.undoSoulNote(currentCharacterId)
                    "memory" -> identityDao.undoNarrativeMemory(currentCharacterId)
                    "user"   -> identityDao.undoUserImpression(currentCharacterId)
                }
                // undo 写了 DB，标记 isSaved=true 让 Flow collect 走完整同步分支，
                // 刷新被撤销的文本字段（而非只更新 avatarUrl）
                _uiState.update { it.copy(isSaved = true) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                ZLog.e("IdentityViewModel", "撤销笔记编辑失败（field=$field, characterId=$currentCharacterId）", e)
                _uiState.update { it.copy(avatarError = "撤销失败：${e.message ?: "未知错误"}") }
            }
        }
    }

    // ── Boundaries 列表操作 ★ Phase 15 ──────────────────────

    fun addBoundary(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        _uiState.update { it.copy(boundaries = it.boundaries + trimmed, isSaved = false) }
    }

    fun removeBoundary(index: Int) {
        _uiState.update {
            if (index < 0 || index >= it.boundaries.size) return@update it
            it.copy(boundaries = it.boundaries.toMutableList().also { list -> list.removeAt(index) }, isSaved = false)
        }
    }

    fun updateBoundary(index: Int, text: String) {
        _uiState.update {
            if (index < 0 || index >= it.boundaries.size) return@update it
            val list = it.boundaries.toMutableList()
            list[index] = text
            it.copy(boundaries = list, isSaved = false)
        }
    }

    // ── CoreBeliefs 列表操作 ★ Phase 15 ─────────────────────

    fun addCoreBelief(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        _uiState.update { it.copy(coreBeliefs = it.coreBeliefs + trimmed, isSaved = false) }
    }

    fun removeCoreBelief(index: Int) {
        _uiState.update {
            if (index < 0 || index >= it.coreBeliefs.size) return@update it
            it.copy(coreBeliefs = it.coreBeliefs.toMutableList().also { list -> list.removeAt(index) }, isSaved = false)
        }
    }

    fun updateCoreBelief(index: Int, text: String) {
        _uiState.update {
            if (index < 0 || index >= it.coreBeliefs.size) return@update it
            val list = it.coreBeliefs.toMutableList()
            list[index] = text
            it.copy(coreBeliefs = list, isSaved = false)
        }
    }

    // ── 保存到 DB ────────────────────────────────────────────

    fun save() {
        if (currentCharacterId < 0) return
        viewModelScope.launch {
            val s = _uiState.value
            // 第8窗口问题6修复：原先 getAndUpsert() 无 try-catch，DB 写入失败时
            // 异常会传播到 viewModelScope（可能崩溃），且 isSaved 永远不会置 true，
            // UI 卡在"正在保存"状态。补上异常处理，失败时复用已有的 avatarError
            // 展示通路，告知用户保存未成功。
            try {
                // 通过 @Transaction getAndUpsert() 在同一事务内读取现有记录并写入，
                // 消除 getById + upsert 两步之间的 TOCTOU 竞态（头像上传与保存并发时不再丢失 avatarUrl）。
                // 非编辑字段（avatarUrl/name 等）由 getAndUpsert 内部从 DB 读取后保留。
                identityDao.getAndUpsert(
                    CharacterIdentityEntity(
                        characterId        = currentCharacterId,
                        persona            = s.persona.trim(),
                        speechStyle        = s.speechStyle.trim(),
                        attitudeToUser     = s.attitudeToUser.trim(),
                        boundariesJson     = toJsonArray(s.boundaries.map { it.trim() }.filter { it.isNotEmpty() }),
                        corebeliefsJson    = toJsonArray(s.coreBeliefs.map { it.trim() }.filter { it.isNotEmpty() }),
                        customSystemPrompt = s.customSystemPrompt.trim().ifEmpty { null },
                        updatedAt          = System.currentTimeMillis(),
                        coreWound          = s.coreWound.trim(),
                        coreDesire         = s.coreDesire.trim(),
                        maskTrigger        = s.maskTrigger.trim(),
                        privatePersona     = s.privatePersona.trim(),
                        privateStyle       = s.privateStyle.trim(),
                        privateExamples    = s.privateExamples.trim(),
                        situationRules     = s.situationRules.trim(),
                        deviationSignals   = s.deviationSignals.trim(),
                        likes              = s.likes.trim(),
                        dislikes           = s.dislikes.trim(),
                        relationships      = s.relationships.trim(),
                        soulNote           = s.soulNote.trim(),
                        narrativeMemory    = s.narrativeMemory.trim(),
                        userImpression     = s.userImpression.trim(),
                        // v1.36 问题3：用户身份设定，与 persona 等字段一样是本表单主动
                        // 编辑的字段，直接按 UI 状态写入即可——不需要像 avatarUrl/name
                        // 那样加入 getAndUpsert() 的保护性合并列表（那是给"这个表单
                        // 不编辑、只能由别处写入"的字段用的）。
                        userGender             = s.userGender,
                        userRoleLabelPrivate   = s.userRoleLabelPrivate.trim(),
                        userRoleLabelPublic    = s.userRoleLabelPublic.trim(),
                        publicPrivacyReason    = s.publicPrivacyReason.trim(),
                        // 以下非编辑字段传入空默认值，getAndUpsert 会用 DB 中已有值覆盖
                        avatarUrl             = "",
                        avatarUrlTall         = "",
                        avatarUrlShelf        = "",
                        name                  = "",
                        relationAssumption    = "",
                        conflictStrategy      = "",
                        lastEditedNoteField   = null,
                        lastEditedNoteAt      = 0,
                        soulNoteBackup        = "",
                        narrativeMemoryBackup = "",
                        userImpressionBackup  = "",
                    )
                )
                _uiState.update { it.copy(isSaved = true) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                ZLog.e("IdentityViewModel", "身份信息保存失败（characterId=$currentCharacterId）", e)
                _uiState.update { it.copy(avatarError = "保存失败：${e.message ?: "未知错误"}") }
            }
        }
    }

    // ── 头像上传（v56→v57 公馆/书架头像独立化）───────────────
    //  v46 版本这里曾是"一次选图，产出圆形+矩形两组参数，一起写入数据库"
    //  的耦合逻辑（因为过去公馆和书架共用同一张图、同一套裁剪参数）。
    //  现在公馆和书架也拆开了，三处头像（圆形/公馆/书架）完全独立、
    //  各自单独选图、单独裁剪，互不影响，因此改造为三个结构完全对称、
    //  互相独立的方法。
    //
    //  文件命名：三张图分别存成 avatar_circle_{id}.jpg / avatar_tall_{id}.jpg /
    //  avatar_shelf_{id}.jpg，用不同文件名区分——如果仍用同一个文件名
    //  avatar_{id}.jpg，后传的图会把前传的覆盖掉，即使数据库字段已经是
    //  独立的，文件本身也会串。
    //
    //  三个方法内部"解码图片、EXIF 旋转校正、压缩、原子写入"逻辑完全一致，
    //  抽成私有的 saveAvatarFile()，只是 baseFileName 不同。
    //
    //  normalizedOffsetX/Y：图片中心偏移量，单位为裁剪圆/矩形半径比例（-1..1）
    //  scale：用户在裁剪弹窗中的缩放倍数
    // ─────────────────────────────────────────────────────────

    /** 圆形头像（详情页）：选图 → 裁剪 → 保存，只影响圆形，不动公馆/书架 */
    fun onAvatarCropCirclePicked(
        uri: Uri,
        context: Context,
        normalizedOffsetX: Float,
        normalizedOffsetY: Float,
        scale: Float,
    ) {
        if (currentCharacterId < 0) return
        viewModelScope.launch {
            try {
                val filePath = withContext(Dispatchers.IO) {
                    saveAvatarFile(uri, context, "avatar_circle_${currentCharacterId}")
                }
                withContext(Dispatchers.IO) {
                    // 批次3 3-2修复：用事务化 upsertAvatarSourceCircle 替代裸 upsert，
                    // 避免 REPLACE 整行替换清空 persona/soulNote 等字段。
                    identityDao.upsertAvatarSourceCircle(currentCharacterId, filePath)
                    identityDao.updateAvatarCropCircle(
                        currentCharacterId, normalizedOffsetX, normalizedOffsetY, scale,
                    )
                }
                _uiState.update {
                    it.copy(
                        avatarUrl = filePath,
                        avatarCropCircleOffsetX = normalizedOffsetX,
                        avatarCropCircleOffsetY = normalizedOffsetY,
                        avatarCropCircleScale   = scale,
                        avatarError = null,
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                ZLog.e("IdentityViewModel", "圆形头像保存失败（characterId=$currentCharacterId）", e)
                _uiState.update { it.copy(avatarError = "头像保存失败：${e.message ?: "未知错误"}") }
            }
        }
    }

    /** 公馆头像：选图 → 裁剪 → 保存，只影响公馆，不动圆形/书架 */
    fun onAvatarCropTallPicked(
        uri: Uri,
        context: Context,
        normalizedOffsetX: Float,
        normalizedOffsetY: Float,
        scale: Float,
    ) {
        if (currentCharacterId < 0) return
        viewModelScope.launch {
            try {
                val filePath = withContext(Dispatchers.IO) {
                    saveAvatarFile(uri, context, "avatar_tall_${currentCharacterId}")
                }
                withContext(Dispatchers.IO) {
                    // 批次3 3-2修复：用事务化 upsertAvatarSourceTall 替代裸 upsert
                    identityDao.upsertAvatarSourceTall(currentCharacterId, filePath)
                    identityDao.updateAvatarCropTall(
                        currentCharacterId, normalizedOffsetX, normalizedOffsetY, scale,
                    )
                }
                _uiState.update {
                    it.copy(
                        avatarUrlTall = filePath,
                        avatarCropTallOffsetX = normalizedOffsetX,
                        avatarCropTallOffsetY = normalizedOffsetY,
                        avatarCropTallScale   = scale,
                        avatarError = null,
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                ZLog.e("IdentityViewModel", "公馆头像保存失败（characterId=$currentCharacterId）", e)
                _uiState.update { it.copy(avatarError = "头像保存失败：${e.message ?: "未知错误"}") }
            }
        }
    }

    /** 书架头像：选图 → 裁剪 → 保存，只影响书架，不动圆形/公馆 */
    fun onAvatarCropShelfPicked(
        uri: Uri,
        context: Context,
        normalizedOffsetX: Float,
        normalizedOffsetY: Float,
        scale: Float,
    ) {
        if (currentCharacterId < 0) return
        viewModelScope.launch {
            try {
                val filePath = withContext(Dispatchers.IO) {
                    saveAvatarFile(uri, context, "avatar_shelf_${currentCharacterId}")
                }
                withContext(Dispatchers.IO) {
                    // 批次3 3-2修复：用事务化 upsertAvatarSourceShelf 替代裸 upsert
                    identityDao.upsertAvatarSourceShelf(currentCharacterId, filePath)
                    identityDao.updateAvatarCropShelf(
                        currentCharacterId, normalizedOffsetX, normalizedOffsetY, scale,
                    )
                }
                _uiState.update {
                    it.copy(
                        avatarUrlShelf = filePath,
                        avatarCropShelfOffsetX = normalizedOffsetX,
                        avatarCropShelfOffsetY = normalizedOffsetY,
                        avatarCropShelfScale   = scale,
                        avatarError = null,
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                ZLog.e("IdentityViewModel", "书架头像保存失败（characterId=$currentCharacterId）", e)
                _uiState.update { it.copy(avatarError = "头像保存失败：${e.message ?: "未知错误"}") }
            }
        }
    }

    /**
     * 解码原图（限长边 ≤1024px）+ EXIF 旋转校正 + 压缩 + 原子写入，
     * 三个 onAvatarCropXxxPicked 方法共用同一段逻辑，仅 baseFileName 不同。
     * 必须在 IO 线程调用。返回写入后的 file:// 路径。
     */
    private fun saveAvatarFile(uri: Uri, context: Context, baseFileName: String): String {
        val destDir  = File(context.filesDir, "avatars").also { it.mkdirs() }
        val destFile = File(destDir, "$baseFileName.jpg")
        val tmpFile  = File(destDir, "$baseFileName.tmp")

        // ── 1. 解码原图（限制长边 ≤ 1024px，不裁成正方形）──
        val rawOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { s ->
            BitmapFactory.decodeStream(s, null, rawOpts)
        }
        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = maxOf(
                rawOpts.outWidth  / 1024,
                rawOpts.outHeight / 1024,
                1,
            )
        }
        val raw = context.contentResolver.openInputStream(uri)?.use { s ->
            BitmapFactory.decodeStream(s, null, decodeOpts)
        } ?: throw Exception("图片读取失败")

        // ── 2. EXIF 旋转校正 ───────────────────────────────────
        val bitmap = context.contentResolver.openInputStream(uri)?.use { exifStream ->
            val rotation = when (
                ExifInterface(exifStream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            ) {
                ExifInterface.ORIENTATION_ROTATE_90  -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else                                 -> 0f
            }
            if (rotation != 0f) {
                val matrix = Matrix().apply { postRotate(rotation) }
                Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
            } else raw
        } ?: raw

        // ── 3. 原样写入原图（不裁剪、不缩到固定尺寸）────────────
        FileOutputStream(tmpFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
        }
        val renamed = tmpFile.renameTo(destFile)
        if (!renamed) {
            // 降级方案：拷贝 + 删除
            try {
                tmpFile.copyTo(destFile, overwrite = true)
                tmpFile.delete()
            } catch (e: Throwable) {
                tmpFile.delete()  // 清理临时文件
                throw Exception("头像保存失败：无法写入目标文件")
            }
        }

        return "file://${destFile.absolutePath}"
    }

    fun clearAvatarError() = _uiState.update { it.copy(avatarError = null) }

    // v56→v57 公馆/书架头像独立化：onAvatarCropTallUpdated（"仅重调取景，
    // 不重新选图"的隐藏功能）已删除。该功能没有任何界面提示，用户确认
    // 视为从未真正上线，不保留、不迁移。三处头像现在都走
    // onAvatarCropXxxPicked 统一入口（选图→裁剪→保存一步到位）。
    //
    // onAvatarPicked（已确认全项目无调用点的旧兼容方法）也已删除。

    private fun parseJsonArray(json: String): List<String> = try {
        val arr = JSONArray(json)
        (0 until arr.length()).map { arr.getString(it) }
    } catch (_: Throwable) {
        emptyList()
    }

    private fun toJsonArray(list: List<String>): String {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        return arr.toString()
    }
}
