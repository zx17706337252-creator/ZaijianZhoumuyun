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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Window C 缺口 3 · SkillRegistry.buildSkillCatalogBlock 测试。
 *
 * 补上缺口 1 遗留的测试债：验证目录生成逻辑正确，
 * 结合 RoundtableBotReplyGenerator/RoundtableIdleManager 已确认调用
 * buildSkillCatalogBlock，两者合起来构成完整验证闭环。
 *
 * 范式对齐 [SkillToolsTest]：内存 Room 数据库 + 真实 Repository。
 */
class SkillRegistryTest {

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

    @Test
    fun `buildSkillCatalogBlock - returns non-empty text containing skill id and name`() = runTest {
        val charId = 1
        val skillId = "test-skill-001"
        val skillName = "整理旅行行程"
        repo.create(
            SkillEntity(
                id = skillId,
                characterId = charId,
                name = skillName,
                shortDescriptor = "一套完整的旅行规划方法",
                fullContent = "步骤1...步骤2...",
                category = "旅行",
                status = SkillStatus.ACTIVE.name,
                sourceType = SkillSourceType.AGENT_AUTONOMOUS.name,
                version = 1,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
            ),
            actor = ACTOR_AGENT,
        )

        val catalog = SkillRegistry.buildSkillCatalogBlock(charId, repo)

        assertTrue("目录文本应非空", catalog.isNotEmpty())
        assertTrue("目录应包含技能名称", catalog.contains(skillName))
        assertTrue("目录应包含技能 id", catalog.contains(skillId))
        assertTrue("目录应包含展开提示", catalog.contains("skill_expand"))
    }

    @Test
    fun `buildSkillCatalogBlock - returns empty when no active skills`() = runTest {
        val charId = 1
        // 不插入任何技能
        val catalog = SkillRegistry.buildSkillCatalogBlock(charId, repo)
        assertTrue("无技能时目录应返回空串", catalog.isEmpty())
    }

    @Test
    fun `buildSkillCatalogBlock - excludes deprecated skills`() = runTest {
        val charId = 1
        repo.create(
            SkillEntity(
                id = "active-skill",
                characterId = charId,
                name = "活跃技能",
                shortDescriptor = "描述",
                fullContent = "内容",
                status = SkillStatus.ACTIVE.name,
                sourceType = SkillSourceType.AGENT_AUTONOMOUS.name,
                version = 1,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
            ),
            actor = ACTOR_AGENT,
        )
        repo.create(
            SkillEntity(
                id = "deprecated-skill",
                characterId = charId,
                name = "废弃技能",
                shortDescriptor = "描述",
                fullContent = "内容",
                status = SkillStatus.DEPRECATED.name,
                sourceType = SkillSourceType.AGENT_AUTONOMOUS.name,
                version = 1,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
            ),
            actor = ACTOR_AGENT,
        )

        val catalog = SkillRegistry.buildSkillCatalogBlock(charId, repo)

        assertTrue("目录应包含 ACTIVE 技能", catalog.contains("活跃技能"))
        assertFalse("目录不应包含 DEPRECATED 技能", catalog.contains("废弃技能"))
    }

    @Test
    fun `buildSkillCatalogBlock - returns empty for negative characterId`() = runTest {
        val catalog = SkillRegistry.buildSkillCatalogBlock(-1, repo)
        assertTrue("characterId < 0 应返回空串", catalog.isEmpty())
    }

    @Test
    fun `buildSkillCatalogBlock - only includes skills for specified character`() = runTest {
        val char1 = 1
        val char2 = 2
        repo.create(
            SkillEntity(
                id = "char1-skill",
                characterId = char1,
                name = "角色1的技能",
                shortDescriptor = "描述",
                fullContent = "内容",
                status = SkillStatus.ACTIVE.name,
                sourceType = SkillSourceType.AGENT_AUTONOMOUS.name,
                version = 1,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
            ),
            actor = ACTOR_AGENT,
        )
        repo.create(
            SkillEntity(
                id = "char2-skill",
                characterId = char2,
                name = "角色2的技能",
                shortDescriptor = "描述",
                fullContent = "内容",
                status = SkillStatus.ACTIVE.name,
                sourceType = SkillSourceType.AGENT_AUTONOMOUS.name,
                version = 1,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
            ),
            actor = ACTOR_AGENT,
        )

        val catalog = SkillRegistry.buildSkillCatalogBlock(char1, repo)

        assertTrue("目录应只包含角色1的技能", catalog.contains("角色1的技能"))
        assertFalse("目录不应包含角色2的技能", catalog.contains("角色2的技能"))
    }
}
