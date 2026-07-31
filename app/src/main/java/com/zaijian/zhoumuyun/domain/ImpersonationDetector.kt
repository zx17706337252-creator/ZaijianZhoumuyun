package com.zaijian.zhoumuyun.domain

/**
 * 假扮身份识别（方案_角色间关系头衔系统_实施方案 五节，接入点2）
 *
 * 替代旧 IdentityGuard 的正则+语义启发式判定：只做精确字符串匹配，
 * 不做模糊匹配、不做语气推断——消息中出现"我不是主人，我是XX"，XX 精确等于
 * impersonation_presets 名单中的某个 name 才算命中。
 *
 * 清理记录（方案六/七节）：旧 IdentityGuard 的自称异常/称呼异常判定
 * （detectSelfClaimAnomaly / detectAddressAnomaly 等）已删除，
 * ChatMessageOrchestrator 现在直接依据本检测器的判定结果推导 speakerContext，
 * 不再有两套机制并存。domain/IdentityGuard.kt 现在只保留 SpeakerContext
 * 协程局部状态基础设施（enum/currentSpeakerContext/withSpeakerContext），
 * 判定逻辑本体已整体迁移到本文件。
 */
object ImpersonationDetector {

    /**
     * "我不是主人，我是XX" 精确匹配正则。
     * 只认这一种固定句式（"我不是主人" + 逗号/顿号类分隔 + "我是XX"），
     * 不识别"我不是主人哦，其实是XX"之类的变体——避免语气推断，命中条件必须显性。
     * XX 取到下一个标点/空白/句末为止，最长 12 字符（预设名单的名字通常很短，
     * 留一点余量给"表妹""隔壁老王"这类稍长的称呼）。
     */
    private val IMPERSONATION_REGEX = Regex(
        """我不是主人[，,、]\s*我是\s*([^，。！？!?,.\s]{1,12})"""
    )

    /**
     * 检测消息中是否出现假扮声明句式，命中则返回声明的名字（未经名单校验的原始候选词）。
     * 未命中句式本身（不含"我不是主人，我是XX"这种结构）直接返回 null，
     * 不查名单——句式本身没出现，不构成识别的前提。
     */
    fun extractClaimedName(message: String): String? {
        val match = IMPERSONATION_REGEX.find(message) ?: return null
        val candidate = match.groupValues.getOrNull(1)?.trim()
        return candidate?.takeIf { it.isNotEmpty() }
    }

    /**
     * 检测消息中是否包含"我是主人"这类声明——用于清除假扮状态位。
     * 同样精确匹配，不做语气推断。
     */
    fun claimsToBeOwner(message: String): Boolean {
        return OWNER_CLAIM_REGEX.containsMatchIn(message)
    }

    private val OWNER_CLAIM_REGEX = Regex("""我是主人""")
}
