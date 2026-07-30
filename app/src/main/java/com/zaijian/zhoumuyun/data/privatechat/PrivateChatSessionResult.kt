package com.zaijian.zhoumuyun.data.privatechat

/**
 * 私聊会话执行结果（方案_角色间私聊_v2-5 4 节）
 *
 * - [Completed]：会话正常收尾（检测到 wrap_up 或到达 maxTurnsPerSession）
 * - [Skipped]：因配对不存在/未开启/达上限/冷却中/全局开关关闭等原因跳过
 */
sealed class PrivateChatSessionResult {
    data class Completed(val sessionId: String, val turnCount: Int) : PrivateChatSessionResult()
    data class Skipped(val reason: String) : PrivateChatSessionResult()
}
