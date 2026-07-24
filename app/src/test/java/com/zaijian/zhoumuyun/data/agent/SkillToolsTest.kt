package com.zaijian.zhoumuyun.data.agent

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.zaijian.zhoumuyun.data.db.AppDatabase
import com.zaijian.zhoumuyun.data.db.entity.SkillEntity
import com.zaijian.zhoumuyun.data.db.entity.SkillSourceType
import com.zaijian.zhoumuyun.data.db.entity.SkillStatus
import com.zaijian.zhoumuyun.data.repository.SkillRepository
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Window C 缺口 3 · 技能工具逻辑测试。
 *
 * 范式对齐 [DataLayerConcurrencyTest]：内存 Room 数据库 + 真实 DAO/Repository，
 * 不 mock/fake。测的是"工具类 + 真实持久化"完整链路。
 *
 * 覆盖点：
 * - [SkillCreateTool.findSimilarSkill] 去重规则（纯函数）
 * - [SkillCreateTool] §5 节流（单角色单日 ≤5 条 + 跨天不计入）
 * - [SkillEditTool] / [SkillDeprecateTool] / [SkillExpandTool] / [SkillFeedbackTool] 越权校验
 */
class SkillToolsTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: SkillRepository

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
        repo = SkillRepository(db.skillDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ─────────────────────────────────────────────────────────
    // 1. findSimilarSkill 去重规则（纯函数，不需要数据库）
    // ─────────────────────────────────────────────────────────

    @Test
    fun `findSimilarSkill - name exact match case insensitive returns match`() {
        val existing = listOf(makeSkill(name = "整理旅行行程"))
        val result = SkillCreateTool.findSimilarSkill("整理旅行行程", "新描述", existing)
        assertNotNull("名称完全相同（忽略大小写）应命中", result)
        assertEquals("整理旅行行程", result?.name)
    }

    @Test
    fun `findSimilarSkill - name contains existing returns match`() {
        val existing = listOf(makeSkill(name = "整理旅行行程"))
        // 长度 >=2，新名称包含已有名称
        val result = SkillCreateTool.findSimilarSkill("整理旅行行程的方法", "其他描述", existing)
        assertNotNull("一者名称包含另一者（双方≥2字）应命中", result)
    }

    @Test
    fun `findSimilarSkill - short_desc exact match returns match`() {
        val existing = listOf(makeSkill(name = "不同名称", shortDesc = "相同描述"))
        val result = SkillCreateTool.findSimilarSkill("完全不同的名字", "相同描述", existing)
        assertNotNull("short_desc 完全相同（即使 name 不同）应命中", result)
    }

    @Test
    fun `findSimilarSkill - no match returns null`() {
        val existing = listOf(makeSkill(name = "整理旅行行程", shortDesc = "旅行规划"))
        val result = SkillCreateTool.findSimilarSkill("做饭", "烹饪技巧", existing)
        assertNull("都不满足去重规则应返回 null", result)
    }

    // ─────────────────────────────────────────────────────────
    // 2. §5 节流：单角色单日 Agent 自主新建 ≤5 条
    // ─────────────────────────────────────────────────────────

    @Test
    fun `throttle - allows up to 5 agent-created skills per day then rejects 6th`() = runTest {
        val charId = 1
        val now = System.currentTimeMillis()
        val tool = SkillCreateTool(repo) { charId }

        // 预先插入同一角色当天的 4 条 AGENT_AUTONOMOUS 技能
        repeat(4) { i ->
            repo.create(
                makeSkill(
                    id = "skill-$i",
                    characterId = charId,
                    name = "技能$i",
                    sourceType = SkillSourceType.AGENT_AUTONOMOUS,
                    createdAt = now,
                ),
                actor = ACTOR_AGENT,
            )
        }

        // 第 5 次应成功（已有 4 条，上限 5）
        val fifthResult = tool.execute(mapOf(
            "name" to "第五条技能",
            "short_desc" to "测试节流",
            "full_content" to "内容",
        ))
        assertTrue("第 5 条技能应创建成功", fifthResult.success)

        // 第 6 次应被拒绝
        val sixthResult = tool.execute(mapOf(
            "name" to "第六条技能",
            "short_desc" to "测试节流上限",
            "full_content" to "内容",
        ))
        assertFalse("第 6 条技能应被节流拒绝", sixthResult.success)
    }

    @Test
    fun `throttle - yesterday's skills do not count towards today's limit`() = runTest {
        val charId = 1
        val now = System.currentTimeMillis()
        val yesterday = now - 24 * 60 * 60 * 1000
        val tool = SkillCreateTool(repo) { charId }

        // 插入一条昨天的 AGENT_AUTONOMOUS 技能
        repo.create(
            makeSkill(
                id = "yesterday-skill",
                characterId = charId,
                name = "昨天的技能",
                sourceType = SkillSourceType.AGENT_AUTONOMOUS,
                createdAt = yesterday,
            ),
            actor = ACTOR_AGENT,
        )

        // 今天第 1 条应成功（昨天的计数不影响今天）
        val result = tool.execute(mapOf(
            "name" to "今天的技能",
            "short_desc" to "测试跨天",
            "full_content" to "内容",
        ))
        assertTrue("跨天不计入，今天的第 1 条应成功", result.success)
    }

    // ─────────────────────────────────────────────────────────
    // 3. 越权校验：非本人角色操作应失败
    // ─────────────────────────────────────────────────────────

    @Test
    fun `authorization - edit rejects skill belonging to another character`() = runTest {
        val ownerCharId = 1
        val attackerCharId = 2
        val skillId = "skill-owner"
        repo.create(
            makeSkill(id = skillId, characterId = ownerCharId, name = "角色1的技能"),
            actor = ACTOR_AGENT,
        )

        val editTool = SkillEditTool(repo) { attackerCharId }
        val result = editTool.execute(mapOf(
            "skill_id" to skillId,
            "full_content" to "篡改内容",
            "reason" to "测试越权",
        ))
        assertFalse("越权编辑应失败", result.success)
    }

    @Test
    fun `authorization - edit succeeds for own skill`() = runTest {
        val charId = 1
        val skillId = "skill-own"
        repo.create(
            makeSkill(id = skillId, characterId = charId, name = "自己的技能"),
            actor = ACTOR_AGENT,
        )

        val editTool = SkillEditTool(repo) { charId }
        val result = editTool.execute(mapOf(
            "skill_id" to skillId,
            "full_content" to "修订内容",
            "reason" to "正常修订",
        ))
        assertTrue("本人角色编辑自己的技能应成功", result.success)
    }

    @Test
    fun `authorization - deprecate rejects skill belonging to another character`() = runTest {
        val ownerCharId = 1
        val attackerCharId = 2
        val skillId = "skill-deprecate"
        repo.create(
            makeSkill(id = skillId, characterId = ownerCharId, name = "角色1的技能"),
            actor = ACTOR_AGENT,
        )

        val deprecateTool = SkillDeprecateTool(repo) { attackerCharId }
        val result = deprecateTool.execute(mapOf(
            "skill_id" to skillId,
            "reason" to "越权废弃",
        ))
        assertFalse("越权废弃应失败", result.success)
    }

    @Test
    fun `authorization - expand rejects skill belonging to another character`() = runTest {
        val ownerCharId = 1
        val attackerCharId = 2
        val skillId = "skill-expand"
        repo.create(
            makeSkill(id = skillId, characterId = ownerCharId, name = "角色1的技能"),
            actor = ACTOR_AGENT,
        )

        val expandTool = SkillExpandTool(repo) { attackerCharId }
        val result = expandTool.execute(mapOf("skill_id" to skillId))
        assertFalse("越权展开应失败", result.success)
    }

    @Test
    fun `authorization - feedback rejects skill belonging to another character`() = runTest {
        val ownerCharId = 1
        val attackerCharId = 2
        val skillId = "skill-feedback"
        repo.create(
            makeSkill(id = skillId, characterId = ownerCharId, name = "角色1的技能"),
            actor = ACTOR_AGENT,
        )

        val feedbackTool = SkillFeedbackTool(repo) { attackerCharId }
        val result = feedbackTool.execute(mapOf(
            "skill_id" to skillId,
            "outcome" to "success",
        ))
        assertFalse("越权反馈应失败", result.success)
    }

    // ─────────────────────────────────────────────────────────
    // 辅助方法
    // ─────────────────────────────────────────────────────────

    private fun makeSkill(
        id: String = "test-skill",
        characterId: Int = 1,
        name: String = "测试技能",
        shortDesc: String = "测试描述",
        fullContent: String = "完整内容",
        category: String? = null,
        status: SkillStatus = SkillStatus.ACTIVE,
        sourceType: SkillSourceType = SkillSourceType.AGENT_AUTONOMOUS,
        version: Int = 1,
        usageCount: Int = 0,
        successCount: Int = 0,
        failureCount: Int = 0,
        lastUsedAt: Long? = null,
        relatedSkillIds: String? = null,
        createdAt: Long = System.currentTimeMillis(),
        updatedAt: Long = System.currentTimeMillis(),
    ) = SkillEntity(
        id = id,
        characterId = characterId,
        name = name,
        shortDescriptor = shortDesc,
        fullContent = fullContent,
        category = category,
        status = status.name,
        sourceType = sourceType.name,
        version = version,
        usageCount = usageCount,
        successCount = successCount,
        failureCount = failureCount,
        lastUsedAt = lastUsedAt,
        relatedSkillIds = relatedSkillIds,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
