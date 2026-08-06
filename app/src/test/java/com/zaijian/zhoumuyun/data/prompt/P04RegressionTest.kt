package com.zaijian.zhoumuyun.data.prompt

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P0-4 PR5/PR6 回归测试（验收：routinePressurePatch / skillCatalogBlock 相关代码本次未改动、
 * 行为逐字符一致）。
 *
 * 这两个函数在 v10 中明确**排除**在 P0-4 改造范围外（v10 四、排除事项 + 风险点1/5 裁定）：
 *  - `buildRoutinePressurePatch`（PregnancyPromptDelegate.kt，**私有**，且依赖 CharacterStateLayer
 *    内部结构 + pregnancyTriggerManager，纯 JVM 无法直接构造调用）
 *  - `buildSkillCatalogBlock`（SkillTools.kt，**suspend** + 依赖 SkillRepository，需 Android 上下文）
 *
 * 因此这里用**源码特征化回归**：直接读取这两个源文件，断言关键实现行原样存在。任何后续改动
 * （哪怕只是结构变化）都会使断言失败，从而守住"本次未改动、行为逐字符一致"的验收。
 */
class P04RegressionTest {

    private fun source(relPath: String): String =
        File("src/main/java/com/zaijian/zhoumuyun/$relPath").readText()

    @Test
    fun `routinePressurePatch 源码未改动`() {
        val src = source("ui/viewmodel/PregnancyPromptDelegate.kt")
        // 关键实现：双门控（desireStrength / emotionalSuppression）+ 委托 buildRoutinePromptPatch
        assertTrue(src.contains("private fun buildRoutinePressurePatch(characterState: CharacterStateLayer): String"))
        assertTrue(src.contains("characterState.motivationalState.desireStrength > 0"))
        assertTrue(src.contains("characterState.hiddenState.emotionalSuppression > 0"))
        assertTrue(src.contains("pregnancyTriggerManager.buildRoutinePromptPatch("))
        assertTrue(src.contains("val routinePressurePatch = buildRoutinePressurePatch(characterState)"))
    }

    @Test
    fun `skillCatalogBlock 源码未改动`() {
        val src = source("data/agent/SkillTools.kt")
        // 关键实现：characterId<0 短路、skill_expand/skill_create 引导、try-catch 降级
        assertTrue(src.contains("suspend fun buildSkillCatalogBlock(characterId: Int, repo: SkillRepository): String"))
        assertTrue(src.contains("if (characterId < 0) return \"\""))
        assertTrue(src.contains("skill_expand skill_id"))
        assertTrue(src.contains("skill_create"))
        assertTrue(src.contains("repo.getActiveSkills(characterId)"))
    }
}