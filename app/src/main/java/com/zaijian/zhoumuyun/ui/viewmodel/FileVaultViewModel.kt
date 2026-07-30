package com.zaijian.zhoumuyun.ui.viewmodel

import android.app.Application
import android.os.FileObserver
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.zaijian.zhoumuyun.data.agent.FileIndexWorker
import com.zaijian.zhoumuyun.data.agent.personalVaultDir
import com.zaijian.zhoumuyun.data.agent.projectVaultDir
import com.zaijian.zhoumuyun.data.agent.reindexUnindexedFilesUnder
import com.zaijian.zhoumuyun.data.agent.resolveVaultPath
import com.zaijian.zhoumuyun.data.agent.VaultCallContext
import com.zaijian.zhoumuyun.data.agent.VaultPathResolution
import com.zaijian.zhoumuyun.data.agent.VaultScope
import com.zaijian.zhoumuyun.data.agent.vaultRoot
import com.zaijian.zhoumuyun.data.agent.withVaultContext
import com.zaijian.zhoumuyun.data.db.AppDatabase
import com.zaijian.zhoumuyun.util.TimeFormatUtils
import com.zaijian.zhoumuyun.util.ZLog
import java.io.File

// ─────────────────────────────────────────────────────────────
//  FileVaultViewModel — v147 文件保险库改造（树形重写）
//
//  职责：
//    - 扫描 vault 三段目录树，按当前角色可见范围组织成 [VaultNode] 树：
//        · vault/personal/{characterId}/        →「角色私库」
//        · vault/shared/roundtable/{rtId}/      →「圆桌共享」（仅该角色参与的圆桌）
//        · vault/shared/project/                →「项目共享」
//    - 支持导出到 Downloads、删除。预览/编辑已迁移至统一的
//      FilePreviewEditorScreen（经 onNavigateToPreview 跳转），本 ViewModel
//      不再持有预览/编辑状态（死代码-10 修复，阶段2·批次1）。
//    - 文件夹树可展开/折叠。
//
//  可见范围（与 resolveVaultPath 权限层一致）：
//    - 自己的私库 + 自己参与的圆桌共享 + 项目共享。
//    - 不展示别的角色私库、不参与的圆桌共享。
// ─────────────────────────────────────────────────────────────

// ─────────────────────────────────────────────────────────────
//  数据模型
// ─────────────────────────────────────────────────────────────

/**
 * 保险库树节点。
 */
sealed class VaultNode {
    abstract val name: String
    abstract val absolutePath: String

    /** 文件夹节点（含角色私库/圆桌共享/项目共享三类根，及子文件夹）。 */
    data class Folder(
        override val name: String,
        override val absolutePath: String,
        /** 人读的作用域标签，如「角色私库」「圆桌共享」「项目共享」或子文件夹名。 */
        val scopeLabel: String,
        val children: List<VaultNode>,
        /** 该文件夹下（递归）的文件总数，用于角标展示。 */
        val fileCount: Int,
    ) : VaultNode()

    /** 文件叶子节点。 */
    data class FileLeaf(
        override val name: String,          // 人读名（去掉时间戳前缀）
        override val absolutePath: String,
        val rawName: String,                // 磁盘上的真实文件名（含时间戳前缀）
        val sizeLabel: String,
        val dateLabel: String,
        val extension: String,
        val sizeBytes: Long,
    ) : VaultNode()
}

data class FileVaultUiState(
    val roots: List<VaultNode> = emptyList(),
    val isLoading: Boolean = true,
    /** 展开的文件夹 absolutePath 集合。根文件夹默认展开。 */
    val expandedPaths: Set<String> = emptySet(),
    val deleteTarget: VaultNode? = null,
    val snackbarMessage: String? = null,
    /** 手动补建索引扫描中：期间禁用触发按钮，避免连点触发重复扫描。 */
    val isReindexing: Boolean = false,
)

// ─────────────────────────────────────────────────────────────
//  ViewModel
// ─────────────────────────────────────────────────────────────

class FileVaultViewModel(
    app: Application,
    private val characterId: Int,
) : AndroidViewModel(app) {

    private val _uiState = MutableStateFlow(FileVaultUiState())
    val uiState: StateFlow<FileVaultUiState> = _uiState.asStateFlow()

    // P2-24 修复：目录监听机制
    // FileObserver 只能监听单个目录（非递归），因此对 vault 下当前角色
    // 可见的每个关键目录分别建立 observer。文件创建/删除/移动时触发
    // debounce 重扫，替代原来空操作的 refreshSignal LaunchedEffect。
    private var fileObservers: List<FileObserver> = emptyList()
    private var reloadJob: Job? = null

    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val roots = withContext(Dispatchers.IO) { scanVaultTree() }
            // 根文件夹默认展开，方便用户直接看到文件。
            val defaultExpanded = roots.filterIsInstance<VaultNode.Folder>().map { it.absolutePath }.toSet()
            _uiState.update {
                it.copy(roots = roots, isLoading = false, expandedPaths = defaultExpanded)
            }
            // P2-24：启动目录监听（每次 load 重建 observer，确保新增的圆桌目录也被覆盖）
            startWatching()
        }
    }

    fun toggleFolder(path: String) {
        _uiState.update { state ->
            val next = if (path in state.expandedPaths) state.expandedPaths - path else state.expandedPaths + path
            state.copy(expandedPaths = next)
        }
    }

    // ── 目录监听（P2-24 修复）─────────────────────────────────

    /**
     * 对当前角色可见的 vault 关键目录建立 [FileObserver]。
     *
     * FileObserver 非递归，因此需要逐目录创建。监听的事件掩码涵盖
     * 文件创建、删除、移入、移出、写入关闭——覆盖所有会让树形列表
     * 过期的文件系统变更。
     */
    private fun startWatching() {
        stopWatching()
        val context = getApplication<Application>()
        val dirsToWatch = collectWatchedDirs(context)

        // FileObserver.MODIFY 会在写入过程中频繁触发，CLOSE_WRITE 更精准——
        // 文件写完关闭后才通知。但部分设备/文件系统不保证发 CLOSE_WRITE，
        // 保留 MODIFY 兜底。CREATE/DELETE/MOVED_FROM/MOVED_TO 覆盖增删移动。
        val mask = FileObserver.CREATE or FileObserver.DELETE or
            FileObserver.MOVED_FROM or FileObserver.MOVED_TO or
            FileObserver.CLOSE_WRITE or FileObserver.MODIFY

        fileObservers = dirsToWatch.map { dir ->
            object : FileObserver(dir, mask) {
                override fun onEvent(event: Int, path: String?) {
                    // onEvent 在 FileObserver 的后台线程回调，不能直接操作
                    // Compose 状态。通过 viewModelScope.launch 转回主线程。
                    scheduleReload()
                    // file_search 索引同步（方案 §4.3）：FileObserver 是所有 vault
                    // 写入路径（Agent 工具/用户手动导入/圆桌协作等）汇合后唯一必经的
                    // 文件系统层面感知点，挂在这里比逐个改写入调用点更不容易漏改。
                    handleIndexSync(event, dir, path)
                }
            }
        }
        fileObservers.forEach { it.startWatching() }
    }

    /**
     * file_search 索引同步（方案 §4.3 / §4.2 [v5]）。
     *
     * - CREATE/CLOSE_WRITE（文件新建或写入完成）→ 入队 [FileIndexWorker] 建/更新索引
     * - DELETE/MOVED_FROM（文件被删除或移走）→ 同步删除对应索引记录
     * - MODIFY 不在这里处理：会在写入过程中密集触发，交给 CLOSE_WRITE 兜底，
     *   避免同一次写入触发多次索引任务
     *
     * [path] 是相对 [dir] 的文件名（FileObserver 语义），拼接后转换成
     * vault 相对路径（如 "vault/personal/1/notes.pdf"）——与
     * [FileIndexEntity.filePath]/[FileIndexWorker.KEY_FILE_PATH] 的约定一致。
     */
    private fun handleIndexSync(event: Int, dir: File, path: String?) {
        if (path.isNullOrEmpty()) return
        // FileObserver 对目录本身的结构性事件（如 vault 根下新增子目录）也会回调，
        // 此处只关心文件，交由下一次 scanVaultTree 走目录分支处理，不进索引表。
        val target = File(dir, path)
        if (target.isDirectory) return

        val context = getApplication<Application>()
        val relativePath = vaultRelativePath(context, target) ?: return

        when (event and FileObserver.ALL_EVENTS) {
            FileObserver.CREATE, FileObserver.CLOSE_WRITE -> {
                enqueueFileIndex(context, relativePath)
            }
            FileObserver.DELETE, FileObserver.MOVED_FROM -> {
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        AppDatabase.getInstance(context).fileIndexDao().deleteByPath(relativePath)
                    } catch (e: Throwable) {
                        ZLog.e("FileVault", "索引删除失败：$relativePath", e)
                    }
                }
            }
        }
    }

    /** 把绝对路径转换为 vault 相对路径（如 "vault/personal/1/notes.pdf"）；不在 vault 内返回 null。 */
    private fun vaultRelativePath(context: Application, file: File): String? {
        val rootPath = vaultRoot(context).absolutePath
        val filePath = file.absolutePath
        if (!filePath.startsWith(rootPath)) return null
        return "vault" + filePath.removePrefix(rootPath).let { if (it.startsWith("/")) it else "/$it" }
    }

    /** 入队一次性 [FileIndexWorker]，对同一 filePath 用 REPLACE 策略避免短时间内重复排队。 */
    private fun enqueueFileIndex(context: Application, relativePath: String) {
        val inputData = Data.Builder()
            .putString(FileIndexWorker.KEY_FILE_PATH, relativePath)
            .build()
        val request = OneTimeWorkRequestBuilder<FileIndexWorker>()
            .setInputData(inputData)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "file_index_${relativePath.hashCode()}",
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    /**
     * 手动补建索引（方案 4.2 节）。
     *
     * 只在用户主动点击时触发，不做成自动后台任务；只补建"还没被索引过"的
     * 历史文件，不做复杂对账。
     *
     * 注意：这里不能直接复用 [collectWatchedDirs]——它为了在 vault 根新增
     * 子目录时也能被 FileObserver 感知到，把 `vaultRoot(context)` 本身也
     * 放进了监听列表；但 vaultRoot 下的 vault/personal/ 平级放着*所有*角色的
     * 私库（vault/personal/{characterId}/），vault/shared/roundtable/ 下也
     * 平级放着*所有*圆桌（不分是否当前角色参与）。如果直接递归 collectWatchedDirs
     * 返回的目录列表，会把其他角色的私库文件、当前角色未参与的圆桌文件也
     * 一并扫入索引——越权访问了不可见范围。
     *
     * 因此改为与 [scanVaultTree] 同款的三段可见范围手动收集：角色私库 +
     * 仅参与的圆桌共享 + 项目共享，跳过 vault 根本身。
     *
     * 差集扫描 + 批量入队的核心逻辑复用 [reindexUnindexedFilesUnder]——
     * 与 App 冷启动全量补建（[reindexAllVaultFilesOnColdStart]）共用同一个
     * 实现，两者只在"传入哪些根目录"上不同（单角色可见范围 vs 全部角色）。
     */
    fun reindexAll() {
        if (_uiState.value.isReindexing) return
        viewModelScope.launch {
            _uiState.update { it.copy(isReindexing = true) }
            val queuedCount = withContext(Dispatchers.IO) {
                val context = getApplication<Application>()
                val visibleRoots = mutableListOf<File>()

                val personal = personalVaultDir(context, characterId)
                if (personal.exists()) visibleRoots.add(personal)

                val roundtableRoot = File(File(vaultRoot(context), "shared"), "roundtable")
                if (roundtableRoot.exists()) {
                    roundtableRoot.listFiles { f -> f.isDirectory }?.forEach { rtDir ->
                        val participants = rtDir.name.split("_")
                        if (characterId.toString() in participants) visibleRoots.add(rtDir)
                    }
                }

                val project = projectVaultDir(context)
                if (project.exists()) visibleRoots.add(project)

                reindexUnindexedFilesUnder(context, visibleRoots)
            }
            _uiState.update {
                it.copy(
                    isReindexing = false,
                    snackbarMessage = if (queuedCount > 0) "已补建 $queuedCount 个文件的索引" else "没有需要补建索引的文件",
                )
            }
        }
    }

    /** 收集需要监听的目录列表（与 scanVaultTree 的可见范围一致）。 */
    private fun collectWatchedDirs(context: Application): List<File> {
        val dirs = mutableListOf<File>()

        // 1. vault 根目录（捕获结构性变化：新子目录出现）
        dirs.add(vaultRoot(context))

        // 2. 角色私库
        val personal = personalVaultDir(context, characterId)
        if (personal.exists()) dirs.add(personal)

        // 3. 参与的圆桌共享目录
        val roundtableRoot = File(File(vaultRoot(context), "shared"), "roundtable")
        if (roundtableRoot.exists()) {
            dirs.add(roundtableRoot)
            roundtableRoot.listFiles { f -> f.isDirectory }?.forEach { rtDir ->
                val participants = rtDir.name.split("_")
                if (characterId.toString() in participants) {
                    dirs.add(rtDir)
                }
            }
        }

        // 4. 项目共享
        val project = projectVaultDir(context)
        if (project.exists()) dirs.add(project)

        return dirs
    }

    /**
     * 防抖重扫：取消上一次待执行的 reload，500ms 后执行新的。
     * 避免短时间内多次文件变更（如批量写入）触发过多扫描。
     */
    private fun scheduleReload() {
        reloadJob?.cancel()
        reloadJob = viewModelScope.launch {
            delay(500)
            val roots = withContext(Dispatchers.IO) { scanVaultTree() }
            // 只更新 roots，保留用户当前的 expandedPaths（不重置展开状态）
            _uiState.update { state ->
                state.copy(roots = roots, isLoading = false)
            }
        }
    }

    private fun stopWatching() {
        fileObservers.forEach { it.stopWatching() }
        fileObservers = emptyList()
        reloadJob?.cancel()
        reloadJob = null
    }

    override fun onCleared() {
        super.onCleared()
        stopWatching()
    }

    // ── 删除 ─────────────────────────────────────────────────

    fun requestDelete(node: VaultNode) {
        _uiState.update { it.copy(deleteTarget = node) }
    }

    fun cancelDelete() {
        _uiState.update { it.copy(deleteTarget = null) }
    }

    fun confirmDelete() {
        val target = _uiState.value.deleteTarget ?: return
        viewModelScope.launch {
            // P2-47 修复：收口到 resolveVaultPath 权限校验，
            // 不再直接 File(path).delete() 绕过权限链路。
            val ok = withContext(Dispatchers.IO) {
                val resolution = withVaultContext(
                    VaultCallContext(characterId, VaultScope.PERSONAL)
                ) {
                    resolveVaultPath(
                        getApplication(),
                        target.absolutePath,
                        characterIdProvider = { characterId },
                        isDelete = true,
                    )
                }
                when (resolution) {
                    is VaultPathResolution.Denied -> {
                        _uiState.update {
                            it.copy(
                                deleteTarget = null,
                                snackbarMessage = "无权删除：${resolution.reason}",
                            )
                        }
                        return@withContext false
                    }
                    is VaultPathResolution.Allowed -> {
                        val f = resolution.file
                        if (f.isDirectory) f.deleteRecursively() else f.delete()
                    }
                }
            }
            if (ok) {
                _uiState.update {
                    it.copy(
                        deleteTarget = null,
                        snackbarMessage = "已删除「${target.name}」",
                    )
                }
                load()
            }
        }
    }

    // ── 导出到 Downloads ─────────────────────────────────────

    /**
     * 导出到系统 Downloads 目录。
     *
     * 原实现用 `Environment.getExternalStoragePublicDirectory(DIRECTORY_DOWNLOADS)` 直接写入，
     * 该 API 在 Android 10+（API 29+）被废弃且受 scoped storage 限制——targetSdk=35 时
     * 没有 `WRITE_EXTERNAL_STORAGE` 权限（manifest 未声明）也没有 `requestLegacyExternalStorage`，
     * 直接写入会抛异常导致闪退。
     *
     * 修复：
     * - API 29+：用 `MediaStore.Downloads` + `ContentResolver.insert` + `openOutputStream`，
     *   这是 Android 10+ 写入系统 Downloads 的标准做法，无需任何存储权限。
     * - API 26-28：用废弃 API + `WRITE_EXTERNAL_STORAGE` 权限（已在 manifest 补声明）。
     * - catch 改成 Throwable，确保 Error 类型异常也能兜住（原来只 catch Exception）。
     */
    fun exportToDownloads(file: VaultNode.FileLeaf) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val context = getApplication<Application>()
                    // P2-47 修复：收口到 resolveVaultPath 权限校验
                    val resolution = withVaultContext(
                        VaultCallContext(characterId, VaultScope.PERSONAL)
                    ) {
                        resolveVaultPath(
                            context,
                            file.absolutePath,
                            characterIdProvider = { characterId },
                        )
                    }
                    val srcFile = when (resolution) {
                        is VaultPathResolution.Denied -> {
                            _uiState.update {
                                it.copy(snackbarMessage = "无权导出：${resolution.reason}")
                            }
                            return@withContext null
                        }
                        is VaultPathResolution.Allowed -> resolution.file
                    }
                    if (!srcFile.exists()) return@withContext null

                    val displayName = uniqueDisplayName(file.name)
                    val mimeType = guessMimeType(file.extension)

                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        // ── Android 10+：MediaStore.Downloads（无需权限）──
                        val resolver = context.contentResolver
                        val values = android.content.ContentValues().apply {
                            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mimeType)
                            // RELATIVE_PATH 让文件出现在 Downloads/ 下
                            put(
                                android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                                android.os.Environment.DIRECTORY_DOWNLOADS,
                            )
                        }
                        val collection = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
                        val uri = resolver.insert(collection, values)
                            ?: return@withContext null
                        resolver.openOutputStream(uri)?.use { out ->
                            srcFile.inputStream().use { it.copyTo(out) }
                        } ?: return@withContext null
                        displayName
                    } else {
                        // ── Android 9 及以下：废弃 API + WRITE_EXTERNAL_STORAGE 权限 ──
                        @Suppress("DEPRECATION")
                        val downloads = android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS,
                        )
                        downloads.mkdirs()
                        val dest = uniqueDest(downloads, file.name)
                        srcFile.copyTo(dest, overwrite = true)
                        dest.name
                    }
                } catch (e: Throwable) {
                    // 改成 Throwable：原来只 catch Exception，Error 类型（如 OutOfMemoryError）
                    // 会绕过 catch 导致协程未捕获异常 → 闪退。现在全部兜住。
                    com.zaijian.zhoumuyun.util.ZLog.e("FileVault", "导出到 Downloads 失败：${file.name}", e)
                    null
                }
            }
            _uiState.update {
                it.copy(snackbarMessage = if (result != null) "已保存到下载目录：$result" else "导出失败，请检查存储权限")
            }
        }
    }

    /** 根据 MIME 类型表猜测文件类型（MediaStore 需要 mimeType）。 */
    private fun guessMimeType(ext: String): String = when (ext.lowercase()) {
        "txt", "log"      -> "text/plain"
        "md"              -> "text/markdown"
        "csv"             -> "text/csv"
        "html", "htm"     -> "text/html"
        "json"            -> "application/json"
        "xml"             -> "application/xml"
        "yml", "yaml"     -> "application/x-yaml"
        "xlsx"            -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "docx"            -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "pptx"            -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "pdf"             -> "application/pdf"
        "zip"             -> "application/zip"
        else             -> "application/octet-stream"
    }

    /** 生成唯一的显示名（避免覆盖 Downloads 里的同名文件）。 */
    private fun uniqueDisplayName(name: String): String {
        val noExt = name.substringBeforeLast(".")
        val ext = name.substringAfterLast(".", "")
        val suffix = if (ext.isNotEmpty()) ".$ext" else ""
        var i = 1
        var candidate = name
        val context = getApplication<Application>()
        // 查询 MediaStore 是否已有同名文件（Android 10+）
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val collection = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
            while (true) {
                val selection = "${android.provider.MediaStore.MediaColumns.DISPLAY_NAME} = ?"
                val args = arrayOf(candidate)
                context.contentResolver.query(collection, arrayOf(android.provider.MediaStore.MediaColumns._ID), selection, args, null)?.use {
                    if (it.moveToFirst()) {
                        candidate = "${noExt}_($i)$suffix"
                        i++
                    } else return candidate
                } ?: return candidate
            }
        }
        return candidate
    }

    private fun uniqueDest(dir: File, name: String): File {
        val base = File(dir, name)
        if (!base.exists()) return base
        val noExt = name.substringBeforeLast(".")
        val ext = name.substringAfterLast(".", "")
        val suffix = if (ext.isNotEmpty()) ".$ext" else ""
        var i = 1
        while (File(dir, "${noExt}_($i)$suffix").exists()) i++
        return File(dir, "${noExt}_($i)$suffix")
    }

    fun clearSnackbar() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }

    // ── 扫描 vault 树 ───────────────────────────────────────

    /**
     * 按当前 [characterId] 的可见范围扫描 vault 树。
     * 返回根文件夹列表：角色私库 + 参与的圆桌共享 + 项目共享。
     */
    private fun scanVaultTree(): List<VaultNode> {
        val context = getApplication<Application>()
        val roots = mutableListOf<VaultNode>()

        // 1. 角色私库
        val personal = personalVaultDir(context, characterId)
        if (personal.exists()) {
            val node = buildFolder(personal, "角色私库")
            if (node.fileCount > 0 || node.children.isNotEmpty()) roots.add(node)
        }

        // 2. 参与的圆桌共享（roundtableId = sortedIds.joinToString("_")，
        //    凡 id 段含当前 characterId 的圆桌目录都算参与）
        val roundtableRoot = File(File(vaultRoot(context), "shared"), "roundtable")
        if (roundtableRoot.exists()) {
            roundtableRoot.listFiles { f -> f.isDirectory }?.sortedByDescending { it.lastModified() }?.forEach { rtDir ->
                val participants = rtDir.name.split("_")
                if (characterId.toString() in participants) {
                    val node = buildFolder(rtDir, "圆桌共享·${rtDir.name}")
                    if (node.fileCount > 0 || node.children.isNotEmpty()) roots.add(node)
                }
            }
        }

        // 3. 项目共享
        val project = projectVaultDir(context)
        if (project.exists()) {
            val node = buildFolder(project, "项目共享")
            if (node.fileCount > 0 || node.children.isNotEmpty()) roots.add(node)
        }

        return roots
    }

    /**
     * 递归构建文件夹节点。空文件夹（无文件也无子文件夹）会被剪枝，
     * 避免展示无意义的空层级。
     */
    private fun buildFolder(dir: File, label: String): VaultNode.Folder {
        val children = mutableListOf<VaultNode>()
        val subDirs = dir.listFiles { f -> f.isDirectory }?.sortedBy { it.name.lowercase() } ?: emptyList()
        val files = dir.listFiles { f -> f.isFile }?.sortedByDescending { it.lastModified() } ?: emptyList()

        for (sub in subDirs) {
            val node = buildFolder(sub, sub.name)
            // 剪枝：跳过完全空的子文件夹
            if (node.fileCount > 0 || node.children.isNotEmpty()) children.add(node)
        }
        for (f in files) {
            children.add(buildFileLeaf(f))
        }

        val totalFiles = files.size + subDirs.sumOf { countFilesRecursive(it) }
        return VaultNode.Folder(
            name = label,
            absolutePath = dir.absolutePath,
            scopeLabel = label,
            children = children,
            fileCount = totalFiles,
        )
    }

    private fun countFilesRecursive(dir: File): Int {
        val files = dir.listFiles { f -> f.isFile }?.size ?: 0
        val subDirs: List<File> = dir.listFiles { f -> f.isDirectory }?.toList() ?: emptyList()
        return files + subDirs.sumOf { countFilesRecursive(it) }
    }

    private fun buildFileLeaf(f: File): VaultNode.FileLeaf {
        val displayName = stripTimestampPrefix(f.name)
        val ext = displayName.substringAfterLast(".", "").lowercase()
        return VaultNode.FileLeaf(
            name = displayName,
            absolutePath = f.absolutePath,
            rawName = f.name,
            sizeLabel = formatSize(f.length()),
            dateLabel = TimeFormatUtils.formatMonthDaySlashTime(f.lastModified()),
            extension = ext,
            sizeBytes = f.length(),
        )
    }

    /**
     * 去掉文件名前的毫秒时间戳前缀（FileExportTool/saveViaStream 的命名约定）。
     * 兼容 "{ts}_{name}" 与 "{ts}_{uuid8}_{name}" 两种风格。
     */
    private fun stripTimestampPrefix(name: String): String {
        // 形如 1700000000000_xxx 或 1700000000000_ab12cd34_xxx
        val match = Regex("^(\\d{13})_(?:[0-9a-fA-F]{8}_)?(.+)$").matchEntire(name)
        return match?.groupValues?.get(2) ?: name
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024        -> "${bytes} B"
        bytes < 1024 * 1024 -> "${"%.1f".format(bytes / 1024f)} KB"
        else                -> "${"%.1f".format(bytes / 1024f / 1024f)} MB"
    }

    companion object {
        /**
         * 工厂：传入 [characterId] 以确定可见范围。
         * 用法：viewModel(factory = FileVaultViewModel.factory(characterId))
         */
        fun factory(characterId: Int) = viewModelFactory {
            initializer {
                val app = (this[androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY] as Application)
                FileVaultViewModel(app, characterId)
            }
        }
    }
}
