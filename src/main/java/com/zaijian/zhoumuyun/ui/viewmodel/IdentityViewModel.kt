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
import com.zaijian.zhoumuyun.data.db.AppDatabase
import com.zaijian.zhoumuyun.data.db.entity.CharacterIdentityEntity
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
    val avatarCropTallOffsetX: Float   = 0f,
    val avatarCropTallOffsetY: Float   = 0f,
    val avatarCropTallScale: Float     = 1f,
    val avatarError: String?        = null,
)

class IdentityViewModel(application: Application) : AndroidViewModel(application) {

    private val identityDao = AppDatabase.getInstance(application).characterIdentityDao()

    private val _uiState = MutableStateFlow(IdentityUiState(isLoading = true))
    val uiState: StateFlow<IdentityUiState> = _uiState.asStateFlow()

    private var currentCharacterId: Int = -1
    private var observeJob: Job? = null

    // ── 初始化：读取当前角色的 Identity（响应式 Flow）──────────
    //  改用 observeById Flow 替代一次性 getById，使 DB 中 avatarUrl
    //  或其他字段被外部修改时，UI 能自动刷新。

    fun init(characterId: Int) {
        if (currentCharacterId == characterId) return
        currentCharacterId = characterId
        observeJob?.cancel()
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
                            avatarCropTallOffsetX   = entity?.avatarCropTallOffsetX ?: 0f,
                            avatarCropTallOffsetY   = entity?.avatarCropTallOffsetY ?: 0f,
                            avatarCropTallScale     = entity?.avatarCropTallScale ?: 1f,
                        )
                    } else {
                        // 用户有未保存编辑，仅同步 avatarUrl（头像上传等外部变更）
                        // 保留用户正在编辑的文本字段，避免丢失输入
                        current.copy(
                            avatarUrl = entity?.avatarUrl ?: "",
                            avatarCropCircleOffsetX = entity?.avatarCropCircleOffsetX ?: 0f,
                            avatarCropCircleOffsetY = entity?.avatarCropCircleOffsetY ?: 0f,
                            avatarCropCircleScale   = entity?.avatarCropCircleScale ?: 1f,
                            avatarCropTallOffsetX   = entity?.avatarCropTallOffsetX ?: 0f,
                            avatarCropTallOffsetY   = entity?.avatarCropTallOffsetY ?: 0f,
                            avatarCropTallScale     = entity?.avatarCropTallScale ?: 1f,
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
            when (field) {
                "soul"   -> identityDao.undoSoulNote(currentCharacterId)
                "memory" -> identityDao.undoNarrativeMemory(currentCharacterId)
                "user"   -> identityDao.undoUserImpression(currentCharacterId)
            }
            // undo 写了 DB，标记 isSaved=true 让 Flow collect 走完整同步分支，
            // 刷新被撤销的文本字段（而非只更新 avatarUrl）
            _uiState.update { it.copy(isSaved = true) }
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
            it.copy(boundaries = it.boundaries.toMutableList().also { list -> list.removeAt(index) }, isSaved = false)
        }
    }

    fun updateBoundary(index: Int, text: String) {
        _uiState.update {
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
            it.copy(coreBeliefs = it.coreBeliefs.toMutableList().also { list -> list.removeAt(index) }, isSaved = false)
        }
    }

    fun updateCoreBelief(index: Int, text: String) {
        _uiState.update {
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
                    // 以下非编辑字段传入空默认值，getAndUpsert 会用 DB 中已有值覆盖
                    avatarUrl             = "",
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
        }
    }

    // ── 头像上传（v46 重新设计，从 AvatarCropDialog 调用）──────
    //  旧版本这里会把用户在圆形裁剪框里选的区域直接裁成 512×512 正方形
    //  存盘，avatarUrl 指向的就是成品图。这导致公馆拱形（细高比例）
    //  容器拿到的是一张已经按圆形范围裁死的方图，超出方图之外没有真实
    //  画面——不是渲染层能修的 bug，是存储格式从一开始没考虑非方形
    //  场景。详见 2026-07-03 对话 与 CharacterIdentityEntity 头部注释。
    //
    //  新逻辑：
    //    1. 只做原图保存（限长边 ≤1024px + EXIF 旋转校正），不再按任何
    //       固定形状裁剪、不再缩到 512×512——原图多大比例就存多大比例，
    //       留给各展示场景自己按需要的容器形状裁剪。
    //    2. 本次上传弹窗产生的圆形 offset/scale 存入「圆形」裁剪参数
    //       （对应详情页），矩形 offset/scale 存入「竖长矩形」裁剪参数
    //       （拱形/椭圆共用）。两套参数必须在同一个协程、同一次数据库
    //       写入里一起落库，并且在同一次 _uiState.update 里一起更新
    //       内存状态——绝不能分成两个独立的 viewModelScope.launch。
    //       [v25 修复] 此前矩形参数由调用方另起一个协程
    //       （onAvatarCropTallUpdated）单独保存，与本函数的耗时 IO
    //       协程之间没有先后顺序保证：矩形协程几乎瞬间写完，随后本函数
    //       跑到最后用硬编码的 0f/0f/1f 覆盖内存里的矩形字段，把刚存好
    //       的正确值冲掉——这就是"上传头像后拱形头像仍显示占位大小"的
    //       根因（竞态覆盖，不是渲染裁剪逻辑的问题）。现在改为单一入口，
    //       两套参数作为该函数的必填参数一起传入、一起保存。
    //  normalizedOffsetX/Y：图片中心偏移量，单位为裁剪圆/矩形半径比例（-1..1）
    //  scale：用户在裁剪弹窗中的缩放倍数
    // ─────────────────────────────────────────────────────────
    fun onAvatarCropped(
        uri: Uri,
        context: Context,
        normalizedOffsetX: Float,
        normalizedOffsetY: Float,
        scale: Float,
        tallOffsetX: Float = 0f,
        tallOffsetY: Float = 0f,
        tallScale: Float = 1f,
    ) {
        if (currentCharacterId < 0) return
        viewModelScope.launch {
            try {
                val filePath = withContext(Dispatchers.IO) {
                    val destDir  = File(context.filesDir, "avatars").also { it.mkdirs() }
                    val destFile = File(destDir, "avatar_${currentCharacterId}.jpg")
                    val tmpFile  = File(destDir, "avatar_${currentCharacterId}.tmp")

                    // ── 1. 解码原图（限制长边 ≤ 1024px，不再额外裁成正方形）──
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
                    tmpFile.renameTo(destFile)

                    "file://${destFile.absolutePath}"
                }

                withContext(Dispatchers.IO) {
                    // 新原图落地：把本次弹窗里调好的圆形 + 矩形两套参数
                    // 一起写回去（避免旧图的偏移量套到新图上）。
                    // [v25 修复] 圆形与矩形必须在这同一个协程内依次写完，
                    // 不能让矩形参数的落库交给另一个独立协程去做，否则
                    // 两个协程的完成顺序不确定，会出现后完成的协程用
                    // 默认值覆盖先完成的协程刚写好的正确值。
                    val updated = identityDao.updateAvatarSource(currentCharacterId, filePath)
                    if (updated == 0) {
                        identityDao.upsert(
                            CharacterIdentityEntity(
                                characterId = currentCharacterId,
                                avatarUrl   = filePath,
                            )
                        )
                    }
                    identityDao.updateAvatarCropCircle(
                        currentCharacterId, normalizedOffsetX, normalizedOffsetY, scale,
                    )
                    identityDao.updateAvatarCropTall(
                        currentCharacterId, tallOffsetX, tallOffsetY, tallScale,
                    )
                }

                // 圆形 + 矩形参数在同一次 update 里一起写入内存状态，
                // 避免出现"只更新了圆形、矩形字段还是旧值"的中间态。
                _uiState.update {
                    it.copy(
                        avatarUrl = filePath,
                        avatarCropCircleOffsetX = normalizedOffsetX,
                        avatarCropCircleOffsetY = normalizedOffsetY,
                        avatarCropCircleScale   = scale,
                        avatarCropTallOffsetX   = tallOffsetX,
                        avatarCropTallOffsetY   = tallOffsetY,
                        avatarCropTallScale     = tallScale,
                        avatarError = null,
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(avatarError = "头像保存失败：${e.message}") }
            }
        }
    }

    // ── 竖长矩形裁剪参数单独调整（v46 新增）───────────────────
    //  公馆拱形 + 书架椭圆共用同一套裁剪参数，取景框比例跟详情页圆形
    //  不同，不能直接复用上传时圆形弹窗里调的 offset/scale，需要用户
    //  另外在竖长矩形预览框里单独调一次。不重新解码/写原图，只更新
    //  这一套参数字段，原图（avatarUrl）保持不变。
    // ─────────────────────────────────────────────────────────
    fun onAvatarCropTallUpdated(
        normalizedOffsetX: Float,
        normalizedOffsetY: Float,
        scale: Float,
    ) {
        if (currentCharacterId < 0) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                identityDao.updateAvatarCropTall(
                    currentCharacterId, normalizedOffsetX, normalizedOffsetY, scale,
                )
            }
            _uiState.update {
                it.copy(
                    avatarCropTallOffsetX = normalizedOffsetX,
                    avatarCropTallOffsetY = normalizedOffsetY,
                    avatarCropTallScale   = scale,
                )
            }
        }
    }

    // ── 头像上传（无裁剪，兼容旧路径保留） ──────────────────
    //  1. 用系统 ExifInterface 读旋转角度并校正（Android 7+ 支持流方式）
    //  2. 采样压缩到最大 512px，避免 OOM
    //  3. 原子写入 filesDir/avatars/avatar_{id}.jpg（固定命名，旧文件自动覆盖）
    //  4. DAO 更新：先 updateAvatarUrl；若该角色行不存在（返回 0），再 upsert 新行
    // ─────────────────────────────────────────────────────────
    fun onAvatarPicked(uri: Uri, context: Context) {
        if (currentCharacterId < 0) return
        viewModelScope.launch {
            try {
                val filePath = withContext(Dispatchers.IO) {
                    val destDir = File(context.filesDir, "avatars").also { it.mkdirs() }
                    val destFile = File(destDir, "avatar_${currentCharacterId}.jpg")
                    val tmpFile  = File(destDir, "avatar_${currentCharacterId}.tmp")

                    // 读入 Bitmap（采样压缩到 512px 以内）
                    val bitmap = context.contentResolver.openInputStream(uri)?.use { input ->
                        val opts = BitmapFactory.Options().apply {
                            inJustDecodeBounds = true
                        }
                        BitmapFactory.decodeStream(input, null, opts)
                        val scale = maxOf(
                            opts.outWidth  / 512,
                            opts.outHeight / 512,
                            1
                        )
                        null // 关闭 inJustDecodeBounds 流
                    }.let {
                        context.contentResolver.openInputStream(uri)?.use { input2 ->
                            val opts2 = BitmapFactory.Options()
                            val rawOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            context.contentResolver.openInputStream(uri)?.use { s ->
                                BitmapFactory.decodeStream(s, null, rawOpts)
                            }
                            opts2.inSampleSize = maxOf(
                                rawOpts.outWidth  / 512,
                                rawOpts.outHeight / 512,
                                1
                            )
                            BitmapFactory.decodeStream(input2, null, opts2)
                        }
                    } ?: throw Exception("图片读取失败")

                    // EXIF 旋转校正（Android SDK ExifInterface，不需要额外依赖）
                    val corrected = context.contentResolver.openInputStream(uri)?.use { exifStream ->
                        val exif = ExifInterface(exifStream)
                        val rotation = when (exif.getAttributeInt(
                            ExifInterface.TAG_ORIENTATION,
                            ExifInterface.ORIENTATION_NORMAL
                        )) {
                            ExifInterface.ORIENTATION_ROTATE_90  -> 90f
                            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                            else                                 -> 0f
                        }
                        if (rotation != 0f) {
                            val matrix = Matrix().apply { postRotate(rotation) }
                            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                        } else bitmap
                    } ?: bitmap

                    // 原子写入：先写 tmp，再 rename
                    FileOutputStream(tmpFile).use { out ->
                        corrected.compress(Bitmap.CompressFormat.JPEG, 88, out)
                    }
                    tmpFile.renameTo(destFile)

                    "file://${destFile.absolutePath}"
                }

                // 持久化到 DB
                // v46 注：目前没有任何 UI 入口调用这条路径（已确认 onAvatarPicked
                // 全项目无调用点），但既然保留作为兼容方法，一并改用
                // updateAvatarSource（同时重置裁剪参数），避免以后有人重新
                //接入这条路径时，新图沿用了上一张图的裁剪偏移/缩放，导致
                // 取景范围对不上新图内容而错位——这类问题只有真的换了图
                // 才会暴露，容易被忽略。
                withContext(Dispatchers.IO) {
                    val updated = identityDao.updateAvatarSource(currentCharacterId, filePath)
                    if (updated == 0) {
                        // 该角色还没有 identity 行，插入最小新行
                        identityDao.upsert(
                            CharacterIdentityEntity(
                                characterId = currentCharacterId,
                                avatarUrl   = filePath,
                            )
                        )
                    }
                }

                _uiState.update {
                    it.copy(
                        avatarUrl = filePath,
                        avatarCropCircleOffsetX = 0f,
                        avatarCropCircleOffsetY = 0f,
                        avatarCropCircleScale   = 1f,
                        avatarCropTallOffsetX   = 0f,
                        avatarCropTallOffsetY   = 0f,
                        avatarCropTallScale     = 1f,
                        avatarError = null,
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(avatarError = "头像保存失败：${e.message}") }
            }
        }
    }

    fun clearAvatarError() = _uiState.update { it.copy(avatarError = null) }

    private fun parseJsonArray(json: String): List<String> = try {
        val arr = JSONArray(json)
        (0 until arr.length()).map { arr.getString(it) }
    } catch (_: Exception) {
        emptyList()
    }

    private fun toJsonArray(list: List<String>): String {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        return arr.toString()
    }
}
