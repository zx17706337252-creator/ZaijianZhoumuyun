package com.zaijian.zhoumuyun.data.agent

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * VaultIo / FileSystemTools 权限与迁移层单元测试（v147 文件保险库改造）。
 *
 * 被测对象全部是纯函数（不依赖 Android [android.content.Context] / [com.zaijian.zhoumuyun.util.ZLog]），
 * 用 JDK 临时目录构造 vault 结构即可覆盖全部分支，无需 Robolectric / Instrumented 环境。
 *
 * 覆盖矩阵：
 *  - [decideVaultPermission]：三段目录 × 读/写/删 × 自己/他人/圆桌参与者/非参与者
 *  - [sanitizeForPath]：非法字符剥离 / 超长截断 / 空兜底
 *  - [safeFileName]：非法字符剥离 / 60 字符截断
 *  - [migrateExportsToVaultCore]：无 exports / 空 exports / marker 存在 / 正常迁移 / 同名冲突 / 重复执行幂等
 */
class VaultIoTest {

    /** 临时 filesDir，模拟 context.filesDir。每个测试独立创建，@After 清理。 */
    private lateinit var filesDir: File

    /** 临时 vault 根，等价于 vaultRoot(context)。 */
    private lateinit var vault: File

    @Before
    fun setup() {
        filesDir = Files.createTempDirectory("vaultTest").toFile()
        vault = File(filesDir, "vault").apply { mkdirs() }
        // 重置进程级 holder，避免上一个测试残留的身份影响当前测试
        VaultCallContextHolder.setPersonal(-1)
    }

    @After
    fun tearDown() {
        filesDir.deleteRecursively()
    }

    // ═══════════════════════════════════════════════════════════
    //  decideVaultPermission — 非保险库路径
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `non-vault path is allowed`() {
        // notes/ 等非保险库路径不受角色权限约束，直接放行
        val notes = File(filesDir, "notes").apply { mkdirs() }
        val file = File(notes, "diary.txt")
        val r = decideVaultPermission(file, vault, characterId = 7, scope = VaultScope.PERSONAL, roundtableId = null, isDelete = false)
        assertTrue("非保险库路径应放行", r is VaultPathResolution.Allowed)
    }

    @Test
    fun `non-vault path delete is allowed`() {
        // 非保险库路径的删除也不受结构性根目录保护约束
        val notes = File(filesDir, "notes").apply { mkdirs() }
        val file = File(notes, "old.txt")
        val r = decideVaultPermission(file, vault, characterId = 7, scope = VaultScope.PERSONAL, roundtableId = null, isDelete = true)
        assertTrue("非保险库路径删除应放行", r is VaultPathResolution.Allowed)
    }

    // ═══════════════════════════════════════════════════════════
    //  decideVaultPermission — vault 根
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `vault root read is allowed but delete is denied`() {
        val rRead = decideVaultPermission(vault, vault, 7, VaultScope.PERSONAL, null, isDelete = false)
        assertTrue("vault 根读应放行", rRead is VaultPathResolution.Allowed)

        val rDel = decideVaultPermission(vault, vault, 7, VaultScope.PERSONAL, null, isDelete = true)
        assertTrue("vault 根删除应拒绝", rDel is VaultPathResolution.Denied)
    }

    // ═══════════════════════════════════════════════════════════
    //  decideVaultPermission — personal/{characterId}
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `personal own vault read write is allowed`() {
        val own = File(File(vault, "personal"), "7").apply { mkdirs() }
        val file = File(own, "report.md")
        val r = decideVaultPermission(file, vault, characterId = 7, scope = VaultScope.PERSONAL, roundtableId = null, isDelete = false)
        assertTrue("自己私库的文件应放行", r is VaultPathResolution.Allowed)
    }

    @Test
    fun `personal other character vault is denied`() {
        // 角色 7 试图访问角色 12 的私库
        val other = File(File(vault, "personal"), "12").apply { mkdirs() }
        val file = File(other, "secret.txt")
        val r = decideVaultPermission(file, vault, characterId = 7, scope = VaultScope.PERSONAL, roundtableId = null, isDelete = false)
        assertTrue("访问他人私库应拒绝", r is VaultPathResolution.Denied)
        if (r is VaultPathResolution.Denied) {
            assertTrue("拒绝原因应提及角色 12", r.reason.contains("12"))
        }
    }

    @Test
    fun `personal own root delete is denied`() {
        // 不能删除自己的私库根目录（结构性保护）
        val own = File(File(vault, "personal"), "7").apply { mkdirs() }
        val r = decideVaultPermission(own, vault, characterId = 7, scope = VaultScope.PERSONAL, roundtableId = null, isDelete = true)
        assertTrue("删除私库根应拒绝", r is VaultPathResolution.Denied)
    }

    @Test
    fun `personal own subfolder delete is allowed`() {
        // 删除自己私库里的子文件夹应放行
        val own = File(File(vault, "personal"), "7").apply { mkdirs() }
        val sub = File(own, "old_project").apply { mkdirs() }
        val r = decideVaultPermission(sub, vault, characterId = 7, scope = VaultScope.PERSONAL, roundtableId = null, isDelete = true)
        assertTrue("删除自己私库的子文件夹应放行", r is VaultPathResolution.Allowed)
    }

    @Test
    fun `personal segment without id delete is denied`() {
        // personal 本身（无 id 段）删除应拒绝
        val personal = File(vault, "personal").apply { mkdirs() }
        val r = decideVaultPermission(personal, vault, characterId = 7, scope = VaultScope.PERSONAL, roundtableId = null, isDelete = true)
        assertTrue("删除 personal 结构目录应拒绝", r is VaultPathResolution.Denied)
    }

    @Test
    fun `personal with non-numeric id is denied`() {
        // personal/abc（非数字 ID）应拒绝
        val bad = File(File(vault, "personal"), "abc").apply { mkdirs() }
        val r = decideVaultPermission(bad, vault, characterId = 7, scope = VaultScope.PERSONAL, roundtableId = null, isDelete = false)
        assertTrue("非数字 personal ID 应拒绝", r is VaultPathResolution.Denied)
    }

    // ═══════════════════════════════════════════════════════════
    //  decideVaultPermission — shared/roundtable/{rtId}
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `roundtable participant access is allowed`() {
        // 角色 7 是圆桌 "3_7_15" 的参与者，访问该圆桌共享区应放行
        val rt = File(File(File(vault, "shared"), "roundtable"), "3_7_15").apply { mkdirs() }
        val file = File(rt, "minutes.md")
        val r = decideVaultPermission(file, vault, characterId = 7, scope = VaultScope.ROUNDTABLE, roundtableId = "3_7_15", isDelete = false)
        assertTrue("圆桌参与者访问应放行", r is VaultPathResolution.Allowed)
    }

    @Test
    fun `roundtable non-participant access is denied`() {
        // 角色 7 试图访问圆桌 "3_12_15"（不包含 7）
        val rt = File(File(File(vault, "shared"), "roundtable"), "3_12_15").apply { mkdirs() }
        val file = File(rt, "minutes.md")
        val r = decideVaultPermission(file, vault, characterId = 7, scope = VaultScope.ROUNDTABLE, roundtableId = "3_7_15", isDelete = false)
        assertTrue("非该圆桌参与者应拒绝", r is VaultPathResolution.Denied)
    }

    @Test
    fun `roundtable access in personal scope is denied`() {
        // 私聊场景（scope=PERSONAL）下访问圆桌共享区应拒绝
        val rt = File(File(File(vault, "shared"), "roundtable"), "3_7_15").apply { mkdirs() }
        val file = File(rt, "minutes.md")
        val r = decideVaultPermission(file, vault, characterId = 7, scope = VaultScope.PERSONAL, roundtableId = null, isDelete = false)
        assertTrue("私聊场景访问圆桌共享区应拒绝", r is VaultPathResolution.Denied)
    }

    @Test
    fun `roundtable root delete is denied`() {
        // 不能删除圆桌共享根目录
        val rt = File(File(File(vault, "shared"), "roundtable"), "3_7_15").apply { mkdirs() }
        val r = decideVaultPermission(rt, vault, characterId = 7, scope = VaultScope.ROUNDTABLE, roundtableId = "3_7_15", isDelete = true)
        assertTrue("删除圆桌共享根应拒绝", r is VaultPathResolution.Denied)
    }

    @Test
    fun `roundtable subfolder delete by participant is allowed`() {
        // 圆桌参与者删除圆桌共享区里的子文件夹应放行
        val rt = File(File(File(vault, "shared"), "roundtable"), "3_7_15").apply { mkdirs() }
        val sub = File(rt, "drafts").apply { mkdirs() }
        val r = decideVaultPermission(sub, vault, characterId = 7, scope = VaultScope.ROUNDTABLE, roundtableId = "3_7_15", isDelete = true)
        assertTrue("圆桌参与者删除子文件夹应放行", r is VaultPathResolution.Allowed)
    }

    @Test
    fun `roundtable segment without id delete is denied`() {
        val rtRoot = File(File(vault, "shared"), "roundtable").apply { mkdirs() }
        val r = decideVaultPermission(rtRoot, vault, characterId = 7, scope = VaultScope.ROUNDTABLE, roundtableId = "3_7_15", isDelete = true)
        assertTrue("删除 roundtable 共享根目录应拒绝", r is VaultPathResolution.Denied)
    }

    // ═══════════════════════════════════════════════════════════
    //  decideVaultPermission — shared/project
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `project shared read write is allowed for all`() {
        val project = File(File(vault, "shared"), "project").apply { mkdirs() }
        val file = File(project, "team_doc.md")
        // 任何角色都能读写项目共享区
        val r = decideVaultPermission(file, vault, characterId = 7, scope = VaultScope.PERSONAL, roundtableId = null, isDelete = false)
        assertTrue("项目共享区读应放行", r is VaultPathResolution.Allowed)
    }

    @Test
    fun `project shared root delete is denied`() {
        val project = File(File(vault, "shared"), "project").apply { mkdirs() }
        val r = decideVaultPermission(project, vault, characterId = 7, scope = VaultScope.PERSONAL, roundtableId = null, isDelete = true)
        assertTrue("删除项目共享根应拒绝", r is VaultPathResolution.Denied)
    }

    @Test
    fun `project shared subfolder delete is allowed`() {
        val project = File(File(vault, "shared"), "project").apply { mkdirs() }
        val sub = File(project, "archive").apply { mkdirs() }
        val r = decideVaultPermission(sub, vault, characterId = 7, scope = VaultScope.PERSONAL, roundtableId = null, isDelete = true)
        assertTrue("删除项目共享子文件夹应放行", r is VaultPathResolution.Allowed)
    }

    @Test
    fun `shared segment without sub delete is denied`() {
        val shared = File(vault, "shared").apply { mkdirs() }
        val r = decideVaultPermission(shared, vault, characterId = 7, scope = VaultScope.PERSONAL, roundtableId = null, isDelete = true)
        assertTrue("删除 shared 结构目录应拒绝", r is VaultPathResolution.Denied)
    }

    @Test
    fun `unknown shared subdirectory is denied`() {
        val unknown = File(File(vault, "shared"), "mystery").apply { mkdirs() }
        val r = decideVaultPermission(unknown, vault, characterId = 7, scope = VaultScope.PERSONAL, roundtableId = null, isDelete = false)
        assertTrue("未知 shared 子目录应拒绝", r is VaultPathResolution.Denied)
    }

    @Test
    fun `unknown vault top-level segment is denied`() {
        val unknown = File(vault, "mystery").apply { mkdirs() }
        val r = decideVaultPermission(unknown, vault, characterId = 7, scope = VaultScope.PERSONAL, roundtableId = null, isDelete = false)
        assertTrue("未知 vault 顶层段应拒绝", r is VaultPathResolution.Denied)
    }

    // ═══════════════════════════════════════════════════════════
    //  sanitizeForPath
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `sanitizeForPath strips unsafe chars`() {
        assertEquals("a_b_c", sanitizeForPath("a/b\\c"))
        assertEquals("a_b", sanitizeForPath("a:b"))
        assertEquals("a_b", sanitizeForPath("a*b"))
        assertEquals("a_b", sanitizeForPath("a?b"))
        assertEquals("a_b", sanitizeForPath("a\"b"))
        assertEquals("a_b", sanitizeForPath("a<b"))
        assertEquals("a_b", sanitizeForPath("a>b"))
        assertEquals("a_b", sanitizeForPath("a|b"))
    }

    @Test
    fun `sanitizeForPath truncates to 120`() {
        val long = "x".repeat(200)
        val result = sanitizeForPath(long)
        assertEquals(120, result.length)
    }

    @Test
    fun `sanitizeForPath empty falls back to unknown`() {
        assertEquals("unknown", sanitizeForPath(""))
        assertEquals("unknown", sanitizeForPath("///\\\\"))
    }

    @Test
    fun `sanitizeForPath preserves roundtable id format`() {
        // roundtableId = sortedIds.joinToString("_")，纯数字+下划线，应原样保留
        assertEquals("3_7_15", sanitizeForPath("3_7_15"))
    }

    // ═══════════════════════════════════════════════════════════
    //  safeFileName
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `safeFileName strips unsafe chars`() {
        assertEquals("a_b", safeFileName("a/b"))
        assertEquals("a_b_c", safeFileName("a:b*c"))
    }

    @Test
    fun `safeFileName preserves extension dot`() {
        // UNSAFE_CHARS 不含 '.'，文件扩展名应保留
        assertEquals("report.md", safeFileName("report.md"))
        assertEquals("data.json", safeFileName("data.json"))
    }

    @Test
    fun `safeFileName truncates to 60`() {
        val long = "x".repeat(100) + ".txt"
        val result = safeFileName(long)
        assertEquals(60, result.length)
    }

    // ═══════════════════════════════════════════════════════════
    //  migrateExportsToVaultCore
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `migration no exports dir returns zero and writes marker`() {
        val result = migrateExportsToVaultCore(filesDir)
        assertEquals("无 exports 目录应返回 0", 0, result.moved)
        assertTrue("应写入迁移标记", File(vault, ".migrated").exists())
    }

    @Test
    fun `migration empty exports dir returns zero and writes marker`() {
        File(filesDir, "exports").mkdirs()
        val result = migrateExportsToVaultCore(filesDir)
        assertEquals("空 exports 目录应返回 0", 0, result.moved)
        assertTrue("应写入迁移标记", File(vault, ".migrated").exists())
    }

    @Test
    fun `migration marker exists skips and returns zero`() {
        // 预置标记
        File(vault, ".migrated").writeText("done")
        // 即使有 exports 文件也不应迁移
        val exports = File(filesDir, "exports").apply { mkdirs() }
        File(exports, "old.txt").writeText("data")

        val result = migrateExportsToVaultCore(filesDir)
        assertEquals("标记存在应跳过", 0, result.moved)
        // exports 里的文件应原封不动
        assertTrue("文件不应被迁移", File(exports, "old.txt").exists())
        assertFalse("目标目录不应有文件", File(File(File(vault, "shared"), "project"), "old.txt").exists())
    }

    @Test
    fun `migration moves files to project shared`() {
        val exports = File(filesDir, "exports").apply { mkdirs() }
        File(exports, "1700000000000_report.md").writeText("报告内容")
        File(exports, "1700000000001_data.json").writeText("{\"k\":1}")

        val result = migrateExportsToVaultCore(filesDir)
        assertEquals("应迁移 2 个文件", 2, result.moved)
        assertTrue("应写入迁移标记", File(vault, ".migrated").exists())

        val project = File(File(vault, "shared"), "project")
        assertTrue("文件应出现在项目共享区", File(project, "1700000000000_report.md").exists())
        assertTrue("文件应出现在项目共享区", File(project, "1700000000001_data.json").exists())
        // 源文件应被删除
        assertFalse("源文件应删除", File(exports, "1700000000000_report.md").exists())
    }

    @Test
    fun `migration handles same name conflict with timestamp prefix`() {
        val exports = File(filesDir, "exports").apply { mkdirs() }
        val project = File(File(vault, "shared"), "project").apply { mkdirs() }
        // 目标已存在同名文件
        File(project, "1700000000000_report.md").writeText("已有内容")
        // 源也有同名文件
        File(exports, "1700000000000_report.md").writeText("新内容")

        val result = migrateExportsToVaultCore(filesDir)
        assertEquals("冲突时应加时间戳前缀迁移", 1, result.moved)
        // 原目标文件内容不应被覆盖
        assertEquals("已有内容", File(project, "1700000000000_report.md").readText())
        // 新文件应以时间戳前缀出现
        val migrated = project.listFiles { f -> f.isFile && f.name.endsWith("1700000000000_report.md") && f.name != "1700000000000_report.md" }
        assertTrue("应有带时间戳前缀的迁移文件", migrated != null && migrated.size == 1)
        assertEquals("新内容", migrated!![0].readText())
    }

    @Test
    fun `migration is idempotent on second call`() {
        val exports = File(filesDir, "exports").apply { mkdirs() }
        File(exports, "old.txt").writeText("data")

        val first = migrateExportsToVaultCore(filesDir)
        assertEquals(1, first.moved)

        // 第二次调用：标记已存在，应跳过
        val second = migrateExportsToVaultCore(filesDir)
        assertEquals("第二次调用应返回 0", 0, second.moved)
    }

    @Test
    fun `migration skips subdirectories in exports`() {
        // exports 下的子目录不被迁移（只迁移文件）
        val exports = File(filesDir, "exports").apply { mkdirs() }
        File(exports, "file.txt").writeText("data")
        File(File(exports, "subfolder"), "ignored.txt").apply { parentFile?.mkdirs() }.writeText("ignored")

        val result = migrateExportsToVaultCore(filesDir)
        assertEquals("只迁移文件，不迁移子目录内容", 1, result.moved)
        val project = File(File(vault, "shared"), "project")
        assertTrue(File(project, "file.txt").exists())
        assertFalse(File(project, "ignored.txt").exists())
    }

    // ═══════════════════════════════════════════════════════════
    //  并发身份隔离（v147 验收返工：协程局部化修复）
    //
    //  验收反馈：VaultCallContextHolder 是进程级 AtomicReference，
    //  两个并发 streamWithTools 会互相覆盖身份。修复方案：用
    //  VaultCallContextElement (CoroutineContext.Element) 把身份
    //  绑定到协程，currentVaultContext() 优先读协程上下文。
    // ═══════════════════════════════════════════════════════════

    /**
     * 核心并发验证：模拟"私聊角色7 + 圆桌角色12"两条 streamWithTools
     * 并发跑的场景。两条协程各自用 withVaultContext 绑定身份，中间
     * 有 delay（模拟 LLM 流式返回耗时），验证读取到的身份互不干扰。
     *
     * 这正是验收反馈中描述的竞态场景：
     * "用户正和角色A私聊，工具调用进行到一半（holder=PERSONAL(A)），
     *  此时后台圆桌空闲触发角色B发言、调用setRoundtable(B, rt1)覆盖了holder"
     */
    @Test
    fun `concurrent coroutines have isolated vault contexts`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)

        // 协程A：私聊角色7，模拟 streamWithTools 的长耗时挂起
        val deferredA = scope.async {
            withVaultContext(VaultCallContext(characterId = 7, scope = VaultScope.PERSONAL)) {
                // 模拟 LLM 流式返回期间挂起（让协程B有机会在此期间覆盖 holder）
                delay(100)
                // 此时协程B可能已经 setRoundtable(12, ...) 覆盖了 holder，
                // 但协程A通过 withVaultContext 绑定的身份应该不受影响
                currentVaultContext()
            }
        }

        // 协程B：圆桌角色12，在协程A挂起期间触发（模拟 RoundtableIdleManager 后台并发）
        val deferredB = scope.async {
            delay(50) // 确保协程A已经进入 withVaultContext 并在 delay 中
            withVaultContext(VaultCallContext(characterId = 12, scope = VaultScope.ROUNDTABLE, roundtableId = "3_12_15")) {
                delay(50)
                currentVaultContext()
            }
        }

        val (ctxA, ctxB) = awaitAll(deferredA, deferredB)

        assertEquals("协程A（私聊角色7）身份不应被协程B覆盖", 7, ctxA?.characterId)
        assertEquals("协程A作用域应为 PERSONAL", VaultScope.PERSONAL, ctxA?.scope)
        assertEquals("协程B（圆桌角色12）身份应独立", 12, ctxB?.characterId)
        assertEquals("协程B作用域应为 ROUNDTABLE", VaultScope.ROUNDTABLE, ctxB?.scope)
        assertEquals("协程B的 roundtableId 应独立", "3_12_15", ctxB?.roundtableId)
    }

    /**
     * 验证协程上下文优先于 holder：即使在 withVaultContext 期间
     * 外部代码覆盖了 holder，协程内读取到的仍是自己的身份。
     *
     * 这直接模拟验收反馈描述的场景：
     * holder 被并发覆盖后，协程内 currentVaultContext() 不应返回被覆盖的值。
     */
    @Test
    fun `holder overwrite does not affect coroutine with vault element`() = runBlocking {
        // 初始：holder = PERSONAL(7)
        VaultCallContextHolder.setPersonal(7)

        withVaultContext(VaultCallContext(characterId = 7, scope = VaultScope.PERSONAL)) {
            // 模拟并发：圆桌后台触发，覆盖了 holder
            VaultCallContextHolder.setRoundtable(12, "3_12_15")

            // 协程内读取身份——应仍是 PERSONAL(7)，而非被覆盖的 ROUNDTABLE(12)
            val ctx = currentVaultContext()
            assertEquals("协程内身份不应被 holder 覆盖影响", 7, ctx.characterId)
            assertEquals("协程内作用域应为 PERSONAL", VaultScope.PERSONAL, ctx.scope)
            assertNotEquals("协程内不应读到圆桌身份", "3_12_15", ctx.roundtableId)
        }
    }

    /**
     * 对比测试：没有 withVaultContext 时，holder 确实是共享可变状态——
     * 后写入的值会覆盖先写入的值。这验证了旧方案（纯 holder）的缺陷确实存在，
     * 也说明了为什么需要协程局部化。
     */
    @Test
    fun `without coroutine element holder is shared mutable state`() = runBlocking {
        // 不使用 withVaultContext，直接读 holder
        VaultCallContextHolder.setPersonal(7)
        assertEquals(7, currentVaultContext().characterId)

        // 模拟并发覆盖
        VaultCallContextHolder.setRoundtable(12, "3_12_15")
        val ctx = currentVaultContext()
        assertEquals("无协程上下文时，holder 被覆盖后读到的是新值", 12, ctx.characterId)
        assertEquals("作用域也被覆盖为 ROUNDTABLE", VaultScope.ROUNDTABLE, ctx.scope)
    }

    /**
     * 验证 withVaultContext 的嵌套：内层 withVaultContext 覆盖外层，
     * 退出内层后恢复外层身份。确保协程上下文的层级语义正确。
     */
    @Test
    fun `nested withVaultContext overrides and restores`() = runBlocking {
        withVaultContext(VaultCallContext(characterId = 7, scope = VaultScope.PERSONAL)) {
            assertEquals(7, currentVaultContext().characterId)

            withVaultContext(VaultCallContext(characterId = 12, scope = VaultScope.ROUNDTABLE, roundtableId = "3_12_15")) {
                val inner = currentVaultContext()
                assertEquals("内层应覆盖外层身份", 12, inner.characterId)
                assertEquals(VaultScope.ROUNDTABLE, inner.scope)
            }

            // 退出内层后，外层身份恢复
            val outer = currentVaultContext()
            assertEquals("退出内层后应恢复外层身份", 7, outer.characterId)
            assertEquals(VaultScope.PERSONAL, outer.scope)
        }
    }

    /**
     * 验证协程上下文在子协程中继承：withVaultContext 内 launch 的
     * 子协程能读到父协程绑定的身份。streamWithTools 的 channelFlow
     * 内部会创建子协程，需要确保身份能传递到子协程。
     */
    @Test
    fun `child coroutine inherits vault context from parent`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)

        val result = scope.async {
            withVaultContext(VaultCallContext(characterId = 15, scope = VaultScope.ROUNDTABLE, roundtableId = "7_12_15")) {
                // 模拟 channelFlow 内部的子协程
                val child = async(Dispatchers.Default) {
                    currentVaultContext()
                }
                child.await()
            }
        }.await()

        assertEquals("子协程应继承父协程的身份", 15, result?.characterId)
        assertEquals(VaultScope.ROUNDTABLE, result?.scope)
        assertEquals("7_12_15", result?.roundtableId)
    }
}
