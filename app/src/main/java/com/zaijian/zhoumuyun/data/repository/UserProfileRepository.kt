package com.zaijian.zhoumuyun.data.repository

import android.content.Context
import android.content.SharedPreferences

/**
 * 用户资料 Repository（窗口7前置修复：「称呼」功能性缺陷）。
 *
 * 背景：Phase 16 起，用户昵称一直用 SharedPreferences("user_profile") 明文持久化，
 * 读写散落在 ProfileScreen.kt 内部（`context.getSharedPreferences("user_profile", ...)`
 * + 裸字符串 key "user_name" + 裸默认值 "旅人"）。窗口1方案B撤销独立用户信息模块后，
 * 「称呼」收敛为 ProfileAiConfigSection 顶部的一个功能性设置项——但审查发现，
 * 全项目没有任何调用方把这个值传给 `PromptOrchestrator.buildSystemPrompt(userName = ...)`，
 * 四条真实对话路径（私聊 ChatMessageOrchestrator、圆桌常规回复
 * RoundtableBotReplyGenerator、圆桌自发发言 RoundtableIdleManager、日程工单
 * AgentTaskJobExecutor）全部吃 `userName: String = "你"` 的参数默认值。「称呼」在 UI
 * 上可编辑、可保存，但对 AI 端完全不生效，与其"功能性设置项"的定位矛盾。
 *
 * 本 Repository 是这次修复引入的唯一真相源：key 名 / 默认值只在这一处硬编码，
 * ProfileScreen（写）与四条 buildSystemPrompt 调用路径（读）都通过它访问，
 * 不再各自裸持有 SharedPreferences 实例或复制 "user_name"/"旅人" 字面量。
 *
 * 与 IdentityRepository 等同一持有模式：容器（AppContainer）持有单例，
 * ViewModel/Composable 侧不再各自 new。SharedPreferences 读写是同步内存操作
 * （已加载的 XML 文件走内存 Map），不需要 suspend。
 */
class UserProfileRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("user_profile", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_USER_NAME = "user_name"
        const val DEFAULT_USER_NAME = "旅人"
    }

    /**
     * 当前「称呼」。供 buildSystemPrompt 的 userName 参数使用，
     * 未设置时返回与 ProfileScreen 一致的默认值"旅人"。
     */
    fun getUserName(): String = prefs.getString(KEY_USER_NAME, DEFAULT_USER_NAME) ?: DEFAULT_USER_NAME

    /** 写入新的「称呼」。空白值按 ProfileScreen 既有规则兜底为默认值。 */
    fun setUserName(name: String) {
        prefs.edit().putString(KEY_USER_NAME, name.ifBlank { DEFAULT_USER_NAME }).apply()
    }
}
