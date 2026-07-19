package com.zaijian.zhoumuyun.ui.viewmodel

import com.zaijian.zhoumuyun.data.datastore.ChatBackgroundConfig
import com.zaijian.zhoumuyun.data.datastore.ChatBackgroundDataStore
import com.zaijian.zhoumuyun.util.ZLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 聊天背景图管理委托类，从 ChatViewModel 中提取。
 * 封装背景 URI 设置、裁剪确认、清除以及背景配置的订阅与更新逻辑。
 */
class ChatBackgroundManager(
    private val _uiState: MutableStateFlow<ChatUiState>,
    private val chatBgStore: ChatBackgroundDataStore,
    private val viewModelScope: CoroutineScope,
    private val getCurrentCharacterId: () -> Int,
) {
    /**
     * 触发 UI 弹出 AvatarCropDialog(shape = FULL_SCREEN)，不直接写入
     * 持久化存储——真正的取景参数要等用户在裁剪弹窗里拖拽/缩放并确认后
     * 才通过 confirmChatBackgroundCrop 一并写入。
     */
    fun requestChatBackgroundCrop(uri: String) {
        _uiState.update { it.copy(pendingBackgroundCropUri = uri) }
    }

    /** 用户在裁剪弹窗中点击「取消」，放弃本次换背景 */
    fun cancelChatBackgroundCrop() {
        _uiState.update { it.copy(pendingBackgroundCropUri = null) }
    }

    /**
     * 用户在 AvatarCropDialog 中确认裁剪：写入 URI + 归一化偏移/缩放，
     * 三者作为一个整体存储，保证聊天页读到的取景参数始终跟对应的图片
     * 是同一次操作产出的（不会出现"图还是老的、偏移却是新的"错位）。
     */
    fun confirmChatBackgroundCrop(uri: String, offsetX: Float, offsetY: Float, scale: Float) {
        val charId = getCurrentCharacterId()
        if (charId < 0) return
        _uiState.update { it.copy(pendingBackgroundCropUri = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                chatBgStore.setBackgroundConfig(
                    charId,
                    ChatBackgroundConfig(
                        uri     = uri,
                        offsetX = offsetX,
                        offsetY = offsetY,
                        scale   = scale,
                    )
                )
            } catch (e: Exception) {
                ZLog.e("ChatBackgroundManager", "确认背景裁剪失败", e)
            }
        }
    }

    /** 设置当前角色的聊天背景图（URI 字符串，来自系统图片选择器）。
     *  保留供旧调用点兼容；新代码请走 requestChatBackgroundCrop → 裁剪弹窗
     *  → confirmChatBackgroundCrop 的完整流程，才能让用户拖动缩放取景。 */
    fun setChatBackground(uri: String) {
        val charId = getCurrentCharacterId()
        if (charId < 0) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                chatBgStore.setBackgroundUri(charId, uri)
            } catch (e: Exception) {
                ZLog.e("ChatBackgroundManager", "设置聊天背景失败", e)
            }
        }
    }

    /** 清除当前角色的聊天背景图，恢复默认渐变背景 */
    fun clearChatBackground() {
        val charId = getCurrentCharacterId()
        if (charId < 0) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                chatBgStore.clearBackground(charId)
            } catch (e: Exception) {
                ZLog.e("ChatBackgroundManager", "清除聊天背景失败", e)
            }
        }
    }

    /**
     * 开始订阅当前角色的聊天背景配置（URI + 取景偏移/缩放），
     * 用户换图或调整取景后实时更新 _uiState。
     * 返回的 [Job] 供调用方（ChatViewModel）纳入 observeJobs 管理，
     * 切换角色时 cancel 旧 Job 并重新调用本方法。
     */
    fun startObserving(): Job = viewModelScope.launch {
        // 聊天背景图：订阅当前角色的背景配置（URI + 取景偏移/缩放），
        // 用户换图或调整取景后实时更新
        chatBgStore.configFlow(getCurrentCharacterId())
            .flowOn(Dispatchers.IO)
            .collect { config ->
                _uiState.update {
                    it.copy(
                        backgroundImageUri = config?.uri,
                        backgroundOffsetX   = config?.offsetX ?: 0f,
                        backgroundOffsetY   = config?.offsetY ?: 0f,
                        backgroundScale     = config?.scale ?: 1f,
                    )
                }
            }
    }
}