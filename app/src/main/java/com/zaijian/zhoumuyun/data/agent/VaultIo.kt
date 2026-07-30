package com.zaijian.zhoumuyun.data.agent

import android.content.Context
import com.zaijian.zhoumuyun.util.ZLog
import java.io.File
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.withContext

/**
 * VaultIo.kt — 文件保险库统一落盘入口
 *
 * ═══════════════════════════════════════════════════════════════
 * 职责
 * ═══════════════════════════════════════════════════════════════
 *  1. 定义 vault 三段目录结构（personal / shared.roundtable / shared.project）
 *  2. 提供 [VaultCallContextHolder]：当前调用方身份（characterId + scope + roundtableId）
 *     的进程级持有器。私聊在 ChatToolRegistrar.registerCharacterTools 里 set，
 *     圆桌在 RoundtableBotReplyGenerator/IdleManager 每个角色发言前 set。
 *  3. 统一落盘函数 [writeVaultText] / [writeVaultStream]：替代原先 FileExportTool
 *     内联落盘、DataVisTools.saveViaStream、ArchiveExportTool 三条并行写入路径。
 *  4. 一次性迁移 [migrateExportsToVault]：把老 exports/ 目录迁到 vault/shared/project/。
 *
 * ═══════════════════════════════════════════════════════════════
 * 关于身份注入方式（v147 验收返工：协程局部化）
 * ═══════════════════════════════════════════════════════════════
 *  初版用 [VaultCallContextHolder]（进程级 AtomicReference）注入身份，
 *  验收时发现并发漏洞：[ToolCallInterceptor.streamWithTools] 是 channelFlow
 *  包裹的长耗时挂起操作（LLM 流式返回，耗时数秒到数十秒），而
 *  [RoundtableIdleManager] 独立持有 CoroutineScope，能在用户私聊进行中
 *  于后台并发触发——两个 streamWithTools 同时跑时，后触发的 setRoundtable
 *  会覆盖 holder，导致先触发的私聊工具落盘时拿到错误的圆桌身份，文件落进
 *  错误目录且不报错（因为覆盖后的 holder 本身是"合法"状态，只是属于别人）。
 *
 *  修复：身份改为"随调用链路走的协程局部状态"——
 *  [VaultCallContextElement] 作为 [CoroutineContext.Element]，由调用方在
 *  streamWithTools 外层用 [withVaultContext] 注入。工具 execute() 内通过
 *  [currentVaultContext] 读取，优先取协程上下文中的 Element（并发安全，
 *  每条调用链路独立），找不到时才回退到 [VaultCallContextHolder]（仅用于
 *  无协程上下文的兜底路径，如 WorkflowEngine 后台执行）。
 *
 *  [VaultCallContextHolder] 保留但降级为"默认值/兜底"角色：ChatToolRegistrar
 *  仍 setPersonal 设一个默认身份，但只要 streamWithTools 被 withVaultContext
 *  包裹（三条调用链路都已包裹），协程上下文就会覆盖 holder 的值——并发安全。
 * ═══════════════════════════════════════════════════════════════
 */

// ─────────────────────────────────────────────────────────────
//  VaultScope — 保险库作用域
// ─────────────────────────────────────────────────────────────

/**
 * 保险库作用域。与 MemoryScope（PERSONAL/GROUP）概念相邻但独立——
 * MemoryScope 描述"记忆归属"，VaultScope 描述"文件归属的目录段"。
 */
enum class VaultScope {
    /** 角色私库：vault/personal/{characterId}/ */
    PERSONAL,

    /** 圆桌共享：vault/shared/roundtable/{roundtableId}/ */
    ROUNDTABLE,

    /** 项目共享：vault/shared/project/，所有角色可见可写 */
    PROJECT,
}

// ─────────────────────────────────────────────────────────────
//  VaultCallContext — 当前调用方身份
// ─────────────────────────────────────────────────────────────

/**
 * 一次工具执行时的保险库身份快照。
 *
 * @param characterId  当前发言/操作角色 ID（圆桌里是当前轮到的发言者）
 * @param scope        当前作用域（私聊=PERSONAL，圆桌=ROUNDTABLE）
 * @param roundtableId 仅 ROUNDTABLE 作用域需要；生成规则为
 *   `characterIds.sorted().joinToString("_")`（纯数字+下划线，路径安全，
 *   已核实 RoundtableViewModel.kt 第398行）。仍做防御性 sanitize。
 */
data class VaultCallContext(
    val characterId: Int,
    val scope: VaultScope,
    val roundtableId: String? = null,
) {
    companion object {
        /** 未初始化占位：characterId=-1 与项目里其他 characterIdProvider 占位一致。 */
        val UNINITIALIZED = VaultCallContext(characterId = -1, scope = VaultScope.PERSONAL, roundtableId = null)
    }
}

/**
 * 进程级身份持有器。AtomicReference 保证多线程读写可见性。
 *
 * 写入时机：
 *  - ChatToolRegistrar.registerCharacterTools(currentCharacterId)：PERSONAL + currentCharacterId
 *  - RoundtableBotReplyGenerator.generateBotReply：ROUNDTABLE + bot.id + rtId（发言前）
 *  - RoundtableIdleManager 自发发言路径：同上
 *
 * 读取时机：工具 execute() 内、[writeVaultText]/[writeVaultStream] 内。
 */
object VaultCallContextHolder {
    private val ref = AtomicReference(VaultCallContext.UNINITIALIZED)

    fun get(): VaultCallContext = ref.get()

    fun set(ctx: VaultCallContext) {
        ref.set(ctx)
    }

    /** 便捷构造：私聊场景。 */
    fun setPersonal(characterId: Int) {
        set(VaultCallContext(characterId = characterId, scope = VaultScope.PERSONAL, roundtableId = null))
    }

    /** 便捷构造：圆桌场景。 */
    fun setRoundtable(characterId: Int, roundtableId: String?) {
        set(VaultCallContext(characterId = characterId, scope = VaultScope.ROUNDTABLE, roundtableId = roundtableId))
    }
}

// ─────────────────────────────────────────────────────────────
//  协程局部身份（v147 验收返工：修复并发覆盖竞态）
// ─────────────────────────────────────────────────────────────

/**
 * [CoroutineContext.Element]：把 [VaultCallContext] 绑定到当前协程。
 *
 * 用法：`withVaultContext(ctx) { streamWithTools(...).collect { ... } }`
 *
 * 与 [VaultCallContextHolder] 的区别：
 * - Holder 是进程级单一 AtomicReference，两个并发 streamWithTools 会互相覆盖。
 * - Element 是协程局部的，每条调用链路各自持有一份，互不干扰。
 *
 * [currentVaultContext] 优先读取协程上下文中的 Element，找不到才回退 holder。
 */
class VaultCallContextElement(
    val context: VaultCallContext,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<VaultCallContextElement>
}

/**
 * 读取当前协程的保险库身份。
 *
 * 优先从 [coroutineContext] 取 [VaultCallContextElement]（协程局部，并发安全），
 * 找不到时回退到 [VaultCallContextHolder.get]（进程级，仅用于无协程上下文的兜底）。
 *
 * 所有工具 execute() 内的落盘/权限校验都应通过本函数取身份，而非直接读 holder。
 */
suspend fun currentVaultContext(): VaultCallContext {
    coroutineContext[VaultCallContextElement]?.let { return it.context }
    return VaultCallContextHolder.get()
}

/**
 * 在 [block] 执行期间把 [ctx] 绑定到当前协程的 [CoroutineContext]。
 *
 * 替代 `VaultCallContextHolder.set(ctx)` 的进程级共享可变状态方案。
 * block 内（含其子协程）通过 [currentVaultContext] 读到的都是 [ctx]，
 * 不会被其他并发调用链路覆盖。
 */
suspend fun <T> withVaultContext(ctx: VaultCallContext, block: suspend () -> T): T =
    withContext(VaultCallContextElement(ctx)) { block() }

// ─────────────────────────────────────────────────────────────
//  目录结构
// ─────────────────────────────────────────────────────────────

private const val VAULT_DIR = "vault"
private const val PERSONAL_DIR = "personal"
private const val SHARED_DIR = "shared"
private const val ROUNDTABLE_DIR = "roundtable"
private const val PROJECT_DIR = "project"
private const val EXPORTS_DIR = "exports"          // 旧目录
private const val MIGRATED_MARKER = ".migrated"    // 迁移完成标记

/** vault 根目录：filesDir/vault/ */
fun vaultRoot(context: Context): File =
    File(context.filesDir, VAULT_DIR).also { it.mkdirs() }

/** 角色私库根：filesDir/vault/personal/{characterId}/ */
fun personalVaultDir(context: Context, characterId: Int): File =
    File(File(vaultRoot(context), PERSONAL_DIR), characterId.toString()).also { it.mkdirs() }

/** 圆桌共享根：filesDir/vault/shared/roundtable/{roundtableId}/ */
fun roundtableVaultDir(context: Context, roundtableId: String): File {
    val safe = sanitizeForPath(roundtableId)
    return File(File(File(vaultRoot(context), SHARED_DIR), ROUNDTABLE_DIR), safe).also { it.mkdirs() }
}

/** 项目共享根：filesDir/vault/shared/project/ */
fun projectVaultDir(context: Context): File =
    File(File(vaultRoot(context), SHARED_DIR), PROJECT_DIR).also { it.mkdirs() }

/**
 * 防御性路径清洗：剥离路径分隔符与穿越字符。
 * characterId 是 Int 天然安全；roundtableId 实测为数字+下划线，仍兜底清洗。
 */
internal fun sanitizeForPath(name: String): String =
    name.replace(Regex("[/\\\\:*?\"<>|]"), "_").take(120).ifEmpty { "unknown" }

private val UNSAFE_CHARS = Regex("[/\\\\:*?\"<>|]")

/**
 * 根据当前协程的保险库身份计算落盘目标目录。
 *
 * 通过 [currentVaultContext] 读取身份：优先取协程上下文中的
 * [VaultCallContextElement]（并发安全），回退到 [VaultCallContextHolder]。
 *
 * - PERSONAL：角色私库（characterId<0 时兜底到项目共享，避免文件丢失）
 * - ROUNDTABLE：圆桌共享（roundtableId 为空时兜底到项目共享）
 * - PROJECT：项目共享
 */
suspend fun resolveVaultTargetDir(context: Context): File {
    val ctx = currentVaultContext()
    return when (ctx.scope) {
        VaultScope.PERSONAL -> {
            if (ctx.characterId < 0) {
                // 角色未初始化（如 WorkflowEngine 后台执行无 ChatViewModel 存活）：
                // 兜底放进项目共享区，避免文件丢失。与老 exports/ 行为等价（无角色归属）。
                projectVaultDir(context)
            } else {
                personalVaultDir(context, ctx.characterId)
            }
        }
        VaultScope.ROUNDTABLE -> {
            if (ctx.roundtableId.isNullOrBlank()) projectVaultDir(context)
            else roundtableVaultDir(context, ctx.roundtableId)
        }
        VaultScope.PROJECT -> projectVaultDir(context)
    }
}

// ─────────────────────────────────────────────────────────────
//  统一落盘
// ─────────────────────────────────────────────────────────────

/**
 * 文件名安全化 + 截断（与原 FileExportTool/saveViaStream 行为一致）。
 */
internal fun safeFileName(rawName: String): String =
    UNSAFE_CHARS.replace(rawName, "_").take(60)

/**
 * 核心文本落盘：[finalSafeName] 必须已是调用方清洗过的安全人读文件名（不含时间戳前缀）。
 * 本函数只负责加时间戳前缀、写盘、产 metaJson，**不做二次 sanitize/截断**——
 * 避免对 FileExportTool 这种"自己已算好带后缀文件名"的调用方把扩展名截掉。
 *
 * metaJson 的 fileName 字段 = [finalSafeName]（人读名，不含时间戳）。
 */
internal suspend fun writeVaultFile(
    context: Context,
    finalSafeName: String,
    content: String,
    mimeType: String,
): String {
    val dir = resolveVaultTargetDir(context).also { it.mkdirs() }
    val timestamp = System.currentTimeMillis()
    // P1-24 修复：与 writeVaultStream 一致，加 8 位 UUID 后缀防并发覆盖。
    // 原先仅用 "${timestamp}_${finalSafeName}"，同一毫秒内两个协程以相同文件名
    // 写入同一目录时会互相覆盖、静默丢失数据。加 UUID 后磁盘文件名唯一，
    // metaJson.fileName 仍为 finalSafeName（不含时间戳/UUID），不影响下游读取。
    val uniqueSuffix = UUID.randomUUID().toString().take(8)
    val file = File(dir, "${timestamp}_${uniqueSuffix}_${finalSafeName}")
    file.writeText(content, Charsets.UTF_8)
    return buildMetaJson(file, finalSafeName, mimeType, currentVaultContext())
}

/**
 * 统一文本落盘（对外便捷入口）。
 *
 * 替代 FileExportTool 内联的 `File(exportDir, "${timestamp}_${fileName}").writeText(content)`。
 * 对未清洗的 rawFileName 做 sanitize+截断后委托 [writeVaultFile]。
 *
 * @param rawFileName 用户/上层工具给的文件名（可能含后缀）
 * @param content     文本内容
 * @param mimeType    mimeType（保留上层工具的判断逻辑，不在本函数重判）
 * @return metaJson 字符串。字段结构与原实现完全一致（fileName/mimeType/sizeBytes/
 *   absolutePath），新增 characterId/scope 两个附加字段（新增不改名，下游向后兼容）。
 *   fileName 为人读名（不含时间戳前缀），absolutePath 为完整磁盘路径。
 */
suspend fun writeVaultText(
    context: Context,
    rawFileName: String,
    content: String,
    mimeType: String,
): String = writeVaultFile(context, safeFileName(rawFileName), content, mimeType)

/**
 * 统一二进制流落盘。
 *
 * 替代 DataVisTools.saveViaStream（excel_gen/pptx_gen）与 ArchiveExportTool 的内联落盘。
 * 保留原 saveViaStream 的"时间戳+短随机后缀"命名风格（防并发覆盖）。
 *
 * @param write 在打开的输出流上执行实际写入（如 wb.write(stream)）
 * @return metaJson 字符串（结构同 [writeVaultText]）
 */
suspend fun writeVaultStream(
    context: Context,
    rawFileName: String,
    mimeType: String,
    write: (OutputStream) -> Unit,
): String {
    val dir = resolveVaultTargetDir(context).also { it.mkdirs() }
    val safeName = safeFileName(rawFileName)
    val timestamp = System.currentTimeMillis()
    val uniqueSuffix = UUID.randomUUID().toString().take(8)
    val file = File(dir, "${timestamp}_${uniqueSuffix}_${safeName}")
    file.outputStream().use { write(it) }
    return buildMetaJson(file, safeName, mimeType, currentVaultContext())
}

private fun buildMetaJson(file: File, safeName: String, mimeType: String, ctx: VaultCallContext): String {
    return org.json.JSONObject().apply {
        put("fileName", safeName)
        put("mimeType", mimeType)
        put("sizeBytes", file.length())
        put("absolutePath", file.absolutePath)
        // 新增字段（不改名/不删字段，下游 FileExportCard 等只读老字段，向后兼容）
        put("characterId", ctx.characterId)
        put("scope", ctx.scope.name)
    }.toString()
}

// ─────────────────────────────────────────────────────────────
//  一次性迁移：exports/ → vault/shared/project/
// ─────────────────────────────────────────────────────────────

/**
 * 把旧 filesDir/exports/ 下的文件迁移到 vault/shared/project/。
 *
 * 触发时机：ZaijianApp.onCreate 同步阶段（早于任何工具写入）。
 *
 * 防重复执行：用 vault/.migrated 标记文件。已迁移则跳过。
 *
 * 老数据没有角色归属信息，只能兜底放进项目共享区（设计方案 3.1 约定）。
 * 迁移后保留 exports/ 目录本身（不删，避免破坏仍引用该路径的旧代码/缓存），
 * 但不再往里写新文件——新写入全部走 vault/。
 *
 * 实际迁移逻辑委托给 [migrateExportsToVaultCore]（纯文件操作，不依赖 Android
 * [Context] / [ZLog]），便于在 JVM 单测里用临时目录覆盖全部分支。
 *
 * @return 迁移的文件数（0 表示无需迁移或已迁移）
 */
fun migrateExportsToVault(context: Context): Int {
    val result = migrateExportsToVaultCore(context.filesDir)
    result.failures.forEach { (name, err) ->
        if (err != null) {
            ZLog.w("VaultIo", "迁移文件失败，跳过：$name", err)
        } else {
            ZLog.w("VaultIo", "迁移文件失败，跳过：$name")
        }
    }
    if (result.moved > 0) {
        ZLog.i("VaultIo", "exports→vault 迁移完成，迁移 ${result.moved} 个文件到 shared/project/")
    }
    return result.moved
}

/**
 * 迁移结果（供 [migrateExportsToVault] 做日志输出，核心函数本身不直接调 [ZLog]）。
 *
 * @param moved    成功迁移的文件数
 * @param failures 迁移失败的文件名 + 异常（不阻断整体迁移，逐文件兜底）
 */
internal data class MigrationResult(val moved: Int, val failures: List<Pair<String, Throwable?>>)

/**
 * 纯迁移核心：把 [filesDir]/exports/ 下的文件搬到 [filesDir]/vault/shared/project/。
 *
 * 不依赖 Android [Context] 与 [ZLog]，可在任意 JVM 上用临时目录驱动测试。
 * 语义与 [migrateExportsToVault] 完全一致，详见其文档。
 *
 * @param filesDir 等价于 context.filesDir——vault 与 exports 都挂在它下面
 */
internal fun migrateExportsToVaultCore(filesDir: File): MigrationResult {
    val vault = File(filesDir, VAULT_DIR).also { it.mkdirs() }
    val marker = File(vault, MIGRATED_MARKER)
    if (marker.exists()) return MigrationResult(0, emptyList())

    val exports = File(filesDir, EXPORTS_DIR)
    if (!exports.exists()) {
        // 从未产生过老数据，直接标记已迁移，避免每次启动都进来扫。
        runCatching { marker.writeText("done") }
        return MigrationResult(0, emptyList())
    }

    val files = exports.listFiles()?.filter { it.isFile } ?: emptyList()
    if (files.isEmpty()) {
        runCatching { marker.writeText("done") }
        return MigrationResult(0, emptyList())
    }

    val target = File(File(vault, SHARED_DIR), PROJECT_DIR).also { it.mkdirs() }
    var moved = 0
    val failures = mutableListOf<Pair<String, Throwable?>>()
    for (src in files) {
        // 同名兜底：目标已存在同名时加时间戳前缀保留（老文件名已含 timestamp 前缀，
        // 极少冲突，但防御性处理避免覆盖丢失）。
        val dest = if (File(target, src.name).exists()) {
            File(target, "${System.currentTimeMillis()}_${src.name}")
        } else {
            File(target, src.name)
        }
        // 用 copy + delete 而非 renameTo：跨目录 renameTo 在某些设备/文件系统上失败率较高，
        // copy 保证内容完整，删除失败不影响数据正确性（仅留孤儿文件）。
        val ok = runCatching {
            src.copyTo(dest, overwrite = false)
            src.delete()
        }
        if (ok.isSuccess) moved++ else {
            failures.add(src.name to ok.exceptionOrNull())
        }
    }

    runCatching { marker.writeText("done") }
    return MigrationResult(moved, failures)
}

// ─────────────────────────────────────────────────────────────
//  历史文件补建索引（方案 §4.2）
// ─────────────────────────────────────────────────────────────

/**
 * 把 vault 内尚未建过索引的文件路径入队 [FileIndexWorker]。
 *
 * 共享核心：[FileVaultViewModel.reindexAll]（手动按钮，单角色可见范围）与
 * App 冷启动全量补建（[reindexAllVaultFilesOnColdStart]，全部角色/圆桌）
 * 都调用这个函数，避免"差集扫描 + 批量入队"逻辑重复一份。
 *
 * 只做"补建还没被索引过的文件"，不做复杂对账（不处理已索引但文件已改动/
 * 已删除的情况——那是 FileObserver 实时同步的职责，见
 * [FileVaultViewModel.handleIndexSync]）。
 *
 * @param roots 要扫描的目录集合（调用方决定可见范围：单角色 or 全部角色）
 * @return 实际入队补建索引的文件数
 */
internal suspend fun reindexUnindexedFilesUnder(context: Context, roots: List<File>): Int {
    val allFiles = mutableListOf<File>()
    fun collectFiles(dir: File) {
        dir.listFiles()?.forEach { f ->
            if (f.isDirectory) collectFiles(f) else allFiles.add(f)
        }
    }
    roots.forEach { collectFiles(it) }

    val alreadyIndexed = com.zaijian.zhoumuyun.data.db.AppDatabase.getInstance(context)
        .fileIndexDao().getAllPaths().toHashSet()
    val toIndex = allFiles.mapNotNull { f -> vaultRelativePathOf(context, f) }
        .filter { it !in alreadyIndexed }

    toIndex.forEach { relativePath -> enqueueFileIndexWork(context, relativePath) }
    return toIndex.size
}

/** 把绝对路径转换为 vault 相对路径（如 "vault/personal/1/notes.pdf"）；不在 vault 内返回 null。 */
internal fun vaultRelativePathOf(context: Context, file: File): String? {
    val rootPath = vaultRoot(context).absolutePath
    val filePath = file.absolutePath
    if (!filePath.startsWith(rootPath)) return null
    return "vault" + filePath.removePrefix(rootPath).let { if (it.startsWith("/")) it else "/$it" }
}

/** 入队一次性 [FileIndexWorker]，对同一 filePath 用 REPLACE 策略避免短时间内重复排队。 */
internal fun enqueueFileIndexWork(context: Context, relativePath: String) {
    val inputData = androidx.work.Data.Builder()
        .putString(FileIndexWorker.KEY_FILE_PATH, relativePath)
        .build()
    val request = androidx.work.OneTimeWorkRequestBuilder<FileIndexWorker>()
        .setInputData(inputData)
        .build()
    androidx.work.WorkManager.getInstance(context).enqueueUniqueWork(
        "file_index_${relativePath.hashCode()}",
        androidx.work.ExistingWorkPolicy.REPLACE,
        request,
    )
}

/**
 * App 冷启动全量补建索引（方案 §4.2）。
 *
 * 与 [FileVaultViewModel.reindexAll]（用户手动点击、仅扫当前角色可见范围）
 * 不同：这里在 App 启动时后台自动跑一次，覆盖**全部**角色私库 + **全部**
 * 圆桌共享 + 项目共享——冷启动时没有"当前角色"上下文，也不需要按可见范围
 * 过滤（这不是用户操作，只是把磁盘上已存在但数据库里还没有记录的文件
 * 补上索引，不涉及越权访问的问题）。
 *
 * 手动触发 vs 自动触发的区别只在"扫描范围"，核心的差集+入队逻辑完全
 * 复用 [reindexUnindexedFilesUnder]。
 *
 * 不依赖硬编码的角色 ID 范围（如 1..9）——直接遍历 vault/personal/ 和
 * vault/shared/roundtable/ 下实际存在的子目录，天然覆盖女儿角色等
 * ID 超出常规范围的情况。
 *
 * @return 实际入队补建索引的文件数
 */
suspend fun reindexAllVaultFilesOnColdStart(context: Context): Int {
    val roots = mutableListOf<File>()

    val personalRoot = File(vaultRoot(context), PERSONAL_DIR)
    personalRoot.listFiles { f -> f.isDirectory }?.forEach { roots.add(it) }

    val roundtableRoot = File(File(vaultRoot(context), SHARED_DIR), ROUNDTABLE_DIR)
    roundtableRoot.listFiles { f -> f.isDirectory }?.forEach { roots.add(it) }

    val project = projectVaultDir(context)
    if (project.exists()) roots.add(project)

    return reindexUnindexedFilesUnder(context, roots)
}
