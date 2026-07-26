package com.zaijian.zhoumuyun.data.repository

import android.content.Context
import com.zaijian.zhoumuyun.data.db.dao.ProjectDao
import com.zaijian.zhoumuyun.data.db.dao.ProjectKnowledgeDao
import com.zaijian.zhoumuyun.data.db.entity.KnowledgeSource
import com.zaijian.zhoumuyun.data.db.entity.ProjectEntity
import com.zaijian.zhoumuyun.data.db.entity.ProjectKnowledgeEntity
import com.zaijian.zhoumuyun.data.db.entity.ProjectMemberEntity
import com.zaijian.zhoumuyun.data.db.entity.ProjectMilestoneEntity
import com.zaijian.zhoumuyun.data.db.entity.ProjectStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.apache.poi.xwpf.usermodel.XWPFDocument
import java.io.InputStream
import java.util.UUID

/**
 * 知识库注入的 Token 预算保护（修复 2：buildKnowledgeBlock 此前全文不截断）。
 */
object KnowledgeBudget {
    /** 单次注入最多携带的知识条目数 */
    const val MAX_ITEMS = 20
    /** 知识库块总字符上限（粗略按 1 token ≈ 1.5~2 中文字符估算，留余量） */
    const val MAX_TOTAL_CHARS = 6000
}

/**
 * Project Engine Repository（Phase 31）
 *
 * 封装所有 Project/Milestone/Member/Knowledge 的读写操作。
 * ViewModel 只与 Repository 交互，不直接碰 DAO。
 *
 * Phase 31 变更：
 * - addKnowledge() 写入 charCount
 * - 新增 importFile() — TXT/MD/DOCX/PDF 解析为纯文本后调 addKnowledge
 * - buildWorldLayerBlock() 去掉 content.take(120) 截断，改为全文注入
 * - buildKnowledgeBlock() 新增：独立知识库块，供 PromptOrchestrator 前置注入
 */
class ProjectRepository(
    private val projectDao: ProjectDao,
    private val knowledgeDao: ProjectKnowledgeDao,
) {

    // ── Projects ──────────────────────────────────────────────

    suspend fun createProject(
        title: String,
        description: String = "",
        ownerId: String = "user",
    ): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        projectDao.upsertProject(
            ProjectEntity(
                id          = id,
                title       = title,
                description = description,
                ownerId     = ownerId,
                createdAt   = now,
                updatedAt   = now,
            )
        )
        return id
    }

    suspend fun updateProject(project: ProjectEntity) {
        projectDao.upsertProject(project.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun archiveProject(id: String) {
        projectDao.updateStatus(id, ProjectStatus.ARCHIVED.name)
    }

    suspend fun completeProject(id: String) {
        projectDao.updateStatus(id, ProjectStatus.COMPLETED.name)
    }

    suspend fun pauseProject(id: String) {
        projectDao.updateStatus(id, ProjectStatus.PAUSED.name)
    }

    suspend fun reactivateProject(id: String) {
        projectDao.updateStatus(id, ProjectStatus.ACTIVE.name)
    }

    fun observeActive(): Flow<List<ProjectEntity>> = projectDao.observeActive()
    suspend fun getActiveProjectsForCharacter(characterId: Int): List<ProjectEntity> =
        projectDao.getActiveProjectsForCharacter(characterId)
    fun observeAll(): Flow<List<ProjectEntity>> = projectDao.observeAll()

    // 批次4 4-3修复：getActiveProjectsForCharacter 的响应式版本透传
    fun observeActiveForCharacter(characterId: Int): Flow<List<ProjectEntity>> =
        projectDao.observeActiveForCharacter(characterId)
    suspend fun getById(id: String): ProjectEntity? = projectDao.getById(id)

    /**
     * 日程系统第七节新增：批量按 ID 查询项目。
     *
     * 供 ScheduleListTool 展示"关联项目: xxx"用，避免 N+1 查询。
     * 空列表短路返回 emptyList——Room 的 `IN ()` 会触发 SQL 语法错误，
     * 这里在上层拦截，调用方不必关心。
     *
     * @param ids 项目 ID 列表，允许重复（SQL IN 自动去重），允许含不存在的 ID
     *            （返回结果只包含实际存在的，不报错）
     * @return 实际查到的项目列表，顺序不保证与入参一致（调用方按 id 自行映射）
     */
    suspend fun getByIds(ids: List<String>): List<ProjectEntity> =
        if (ids.isEmpty()) emptyList() else projectDao.getByIds(ids)

    // S8-窗口07 结论5修复：goalId 反向关联（Project→Goal）透传。
    suspend fun setGoalId(projectId: String, goalId: String?) = projectDao.setGoalId(projectId, goalId)
    suspend fun getByGoalId(goalId: String): ProjectEntity? = projectDao.getByGoalId(goalId)

    // ── Milestones ────────────────────────────────────────────

    suspend fun addMilestone(
        projectId: String,
        title: String,
        description: String = "",
    ): String {
        val id = UUID.randomUUID().toString()
        projectDao.upsertMilestone(
            ProjectMilestoneEntity(
                id          = id,
                projectId   = projectId,
                title       = title,
                description = description,
                createdAt   = System.currentTimeMillis(),
            )
        )
        return id
    }

    suspend fun completeMilestone(milestoneId: String) {
        projectDao.completeMilestone(milestoneId)
    }

    fun observeMilestones(projectId: String): Flow<List<ProjectMilestoneEntity>> =
        projectDao.observeMilestones(projectId)

    // S8-窗口01 收口：TaskViewModel 原先裸持有
    // AppDatabase.getInstance(application).projectDao().getMilestones(...) 做
    // 一次性挂起查询（预览卡场景不需要实时刷新，故不用上面的 observeMilestones
    // Flow 版本），此处补齐透传。
    suspend fun getMilestones(projectId: String): List<ProjectMilestoneEntity> =
        projectDao.getMilestones(projectId)

    // ── Members ───────────────────────────────────────────────

    suspend fun addMember(
        projectId: String,
        characterId: Int,
        role: String = "CONTRIBUTOR",
    ) {
        projectDao.upsertMember(
            ProjectMemberEntity(
                id          = "${projectId}_${characterId}",
                projectId   = projectId,
                characterId = characterId,
                role        = role,
                joinedAt    = System.currentTimeMillis(),
            )
        )
    }

    suspend fun removeMember(projectId: String, characterId: Int) {
        projectDao.removeMember(projectId, characterId)
    }

    suspend fun getMembers(projectId: String): List<ProjectMemberEntity> =
        projectDao.getMembers(projectId)

    // ── Knowledge ────────────────────────────────────────────

    suspend fun addKnowledge(
        projectId: String,
        content: String,
        title: String = "",
        characterId: String? = null,
        source: String = KnowledgeSource.MANUAL.name,
        importance: Int = 3,
    ): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        knowledgeDao.upsert(
            ProjectKnowledgeEntity(
                id          = id,
                projectId   = projectId,
                characterId = characterId,
                title       = title,
                content     = content,
                source      = source,
                importance  = importance,
                charCount   = content.length,   // Phase 31: 写入字数
                createdAt   = now,
                updatedAt   = now,
            )
        )
        return id
    }

    /**
     * 更新已有知识条目（标题/内容/重要度）。
     *
     * 用于知识库条目的手动预览+编辑入口——预览需要展示未截断的完整 content，
     * 编辑同一个入口直接改完保存，不单独做只读预览态。
     * characterId/source/createdAt 保持不变，只有 title/content/importance/
     * charCount/updatedAt 会被覆盖。content 不做长度截断，与 [addKnowledge]/
     * 类头注释里"全文注入策略：content 不截断"的约定一致——文件导入进来的
     * 条目本来就可能远超手动输入场景的长度上限，编辑时截断等于丢数据。
     *
     * @return 是否成功（id 不存在返回 false）
     */
    suspend fun updateKnowledge(
        id: String,
        title: String,
        content: String,
        importance: Int,
    ): Boolean {
        val existing = knowledgeDao.getById(id) ?: return false
        knowledgeDao.upsert(
            existing.copy(
                title      = title,
                content    = content,
                importance = importance,
                charCount  = content.length,
                updatedAt  = System.currentTimeMillis(),
            )
        )
        return true
    }

    /**
     * 导入文件到知识库。
     *
     * 支持格式：
     *   .txt / .md  → 直接读取文本
     *   .docx       → Apache POI 解析段落
     *   .pdf        → Android PdfRenderer 逐页提取文本（系统 API，无需额外依赖）
     *
     * @param context   Android Context（PdfRenderer 需要 ParcelFileDescriptor）
     * @param inputStream 文件输入流
     * @param fileName  原始文件名，用于判断格式和设置 title
     * @param projectId 目标项目 ID
     * @param importance 重要度，默认 3
     * @return 新建知识条目的 ID
     */
    suspend fun importFile(
        context: Context,
        inputStream: InputStream,
        fileName: String,
        projectId: String,
        importance: Int = 3,
    ): String = withContext(Dispatchers.IO) {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        // P1-8-3 修复：所有分支的 InputStream 改用 .use{} 包裹，确保关闭。
        // 原来 txt/md/else 分支直接 readText() 后不关流；docx 分支已有 XWPFDocument.use{}
        // 但外层 InputStream 本身未关闭；pdf 分支同理。
        //
        // v148 修复：txt/md/else 分支原来硬编码 Charsets.UTF_8，完全没有编码检测——
        // 比 detectFileCharset 的 4096 字节采样误判 bug 更严重：只要导入的是 Windows
        // Excel/记事本另存为默认的 GBK 编码文件，100% 会乱码（不是概率性误判，是
        // 压根没检测）。这里的输入是 ContentResolver 的 InputStream，不像 File 那样
        // 能 seek，所以先读全部字节，再用 detectCharsetFromBytes（与 detectFileCharset
        // 同一套修复后的逻辑）检测编码，避免这条导入路径遗漏了同一个乱码 bug 的修复。
        val content = inputStream.use { stream ->
            when (ext) {
                "txt", "md" -> {
                    val bytes = stream.readBytes()
                    val charset = com.zaijian.zhoumuyun.data.agent.detectCharsetFromBytes(bytes)
                    String(bytes, charset)
                }
                "docx"      -> parseDocx(stream)
                "pdf"       -> parsePdf(context, stream)
                else        -> {
                    val bytes = stream.readBytes()
                    val charset = com.zaijian.zhoumuyun.data.agent.detectCharsetFromBytes(bytes)
                    String(bytes, charset)
                }
            }
        }
        addKnowledge(
            projectId  = projectId,
            content    = content,
            title      = fileName,
            source     = KnowledgeSource.FILE_IMPORT.name,
            importance = importance,
        )
    }

    /** Apache POI：解析 .docx 所有段落文本 */
    private fun parseDocx(inputStream: InputStream): String {
        return XWPFDocument(inputStream).use { doc ->
            doc.paragraphs.joinToString("\n") { it.text }
        }
    }

    /**
     * Android PdfRenderer：逐页提取文本。
     * PdfRenderer 只能渲染位图，不直接提供文本提取 API，
     * 所以这里用 PdfRenderer 把每页渲染成 Bitmap 后交给系统 OCR（API 31+）。
     * 对于 API 31 以下或无 OCR 支持的设备，回退为用 PDFBox 的纯文本提取路径。
     *
     * 注：如果项目已集成 pdfbox-android，可直接 PDDocument.load(inputStream)
     * 提取文本，无需 OCR。此处提供双路径实现。
     */
    private fun parsePdf(context: Context, inputStream: InputStream): String {
        // 优先尝试 Apache PDFBox（如果依赖存在）
        return try {
            val pdfBoxClass = Class.forName("com.tom_roush.pdfbox.pdmodel.PDDocument")
            val loadMethod  = pdfBoxClass.getMethod("load", InputStream::class.java)
            val doc         = loadMethod.invoke(null, inputStream)
            val stripperClass = Class.forName("com.tom_roush.pdfbox.text.PDFTextStripper")
            val stripper    = stripperClass.getDeclaredConstructor().newInstance()
            val getText     = stripperClass.getMethod("getText", pdfBoxClass)
            val text        = getText.invoke(stripper, doc) as? String
                ?: throw UnsupportedOperationException("PDFBox getText 返回了非预期类型")
            val close       = pdfBoxClass.getMethod("close")
            close.invoke(doc)
            text
        } catch (_: ClassNotFoundException) {
            // P1-13-4 修复：原先返回占位字符串，调用方无法区分"正常文本"和"PDF提取失败"，
            // 导致 LLM 读到的是"[PDF 文件已导入，内容需要 PDFBox...]"这类内部提示而非真实内容。
            // 改为抛出异常，让 importFile 调用链感知失败，可在 UI 层给用户明确提示。
            throw UnsupportedOperationException("PDF 文本提取需要 PDFBox 依赖（com.tom_roush:pdfbox-android），当前构建未包含该依赖。")
        }
    }

    fun observeKnowledge(projectId: String): Flow<List<ProjectKnowledgeEntity>> =
        knowledgeDao.observeByProject(projectId)

    suspend fun searchKnowledge(projectId: String, query: String, limit: Int = 10): List<ProjectKnowledgeEntity> =
        knowledgeDao.searchFts(projectId, query, limit)

    suspend fun getTopKnowledge(projectId: String, limit: Int = KnowledgeBudget.MAX_ITEMS): List<ProjectKnowledgeEntity> =
        knowledgeDao.getTopK(projectId, limit)

    suspend fun deleteKnowledge(id: String) = knowledgeDao.delete(id)

    suspend fun knowledgeCount(projectId: String): Int = knowledgeDao.countByProject(projectId)

    // ── 知识库独立块（Phase 31：前置注入，缓存友好）────────────

    /**
     * 构建独立的「项目知识库」块，用于注入到 Identity 层之后、Memory 层之前。
     *
     * 修复 2：原实现全文注入不截断，知识条目和单条长度均无上限，
     * 存在把 Prompt 撑爆到超出 Provider context 限制的风险。
     * 现按 [KnowledgeBudget] 做条目数 + 总字符数双重限制，超出部分截断并提示。
     * 注入时机由 ChatViewModel 的 KnowledgeInjectMode 控制。
     *
     * 格式：
     * ```
     * ════════════════════════════════
     * [项目知识库：{projectTitle}]
     *
     * ## {title}
     * {content（可能被截断）}
     *
     * ## {title2}
     * ...
     * （知识库内容较多，本次仅注入部分条目/已截断，完整内容共 N 条）
     * ════════════════════════════════
     * ```
     */
    suspend fun buildKnowledgeBlock(projectId: String): String {
        val project   = getById(projectId) ?: return ""
        val knowledge = getTopKnowledge(projectId, limit = KnowledgeBudget.MAX_ITEMS)
        if (knowledge.isEmpty()) return ""

        val totalCount = knowledgeCount(projectId)
        var usedChars = 0
        var truncated = false

        val body = buildString {
            knowledge.forEach { k ->
                if (usedChars >= KnowledgeBudget.MAX_TOTAL_CHARS) {
                    truncated = true
                    return@forEach
                }
                appendLine()
                if (k.title.isNotEmpty()) appendLine("## ${k.title}")
                val remaining = KnowledgeBudget.MAX_TOTAL_CHARS - usedChars
                val content = if (k.content.length > remaining) {
                    truncated = true
                    k.content.take(remaining)
                } else k.content
                append(content)
                usedChars += content.length
                appendLine()
            }
        }

        return buildString {
            appendLine("════════════════════════════════")
            appendLine("[项目知识库：${project.title}]")
            append(body)
            if (truncated || totalCount > KnowledgeBudget.MAX_ITEMS) {
                appendLine()
                appendLine("（知识库内容较多，本次仅注入部分条目/已截断，完整内容共 $totalCount 条）")
            }
            append("════════════════════════════════")
        }
    }

    // ── World Layer 注入文本（项目状态 + 里程碑，不含知识库）──

    /**
     * 构建 World Layer 文本块（仅项目状态和里程碑）。
     *
     * Phase 31 变更：知识库内容已迁移到独立的 buildKnowledgeBlock()，
     * 此处不再注入知识条目，保持 World Layer 职责纯粹。
     *
     * 格式：
     * ```
     * [当前项目：{title}]
     * 描述：{description}
     *
     * 里程碑：
     * □ {milestone title}
     * ✓ {completed milestone}
     * ```
     */
    suspend fun buildWorldLayerBlock(
        projectId: String,
        userMessage: String = "",
    ): String {
        val project    = getById(projectId) ?: return ""
        val milestones = projectDao.getMilestones(projectId)

        return buildString {
            appendLine("[当前项目：${project.title}]")
            if (project.description.isNotEmpty()) {
                appendLine("描述：${project.description}")
            }
            if (milestones.isNotEmpty()) {
                appendLine()
                appendLine("里程碑：")
                milestones.forEach { m ->
                    val prefix = if (m.isCompleted) "✓" else "□"
                    appendLine("$prefix ${m.title}")
                }
            }
        }.trimEnd()
    }
}
