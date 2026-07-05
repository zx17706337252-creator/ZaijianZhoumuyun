package com.zaijian.zhoumuyun.ui.viewmodel

import android.app.Application
import android.os.Bundle
import androidx.lifecycle.AbstractSavedStateViewModelFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.savedstate.SavedStateRegistryOwner

/**
 * #35 修复（SavedStateHandle）Step 1：ChatViewModel 专属工厂。
 *
 * 背景：ChatViewModel 原本是 AndroidViewModel(application)，构造参数只有一个，
 * Compose 的 viewModel() 无参调用能靠默认工厂自动反射创建。现在构造函数加了
 * SavedStateHandle，默认工厂不知道这第二个参数该传什么，必须用这个自定义工厂——
 * AbstractSavedStateViewModelFactory 会自动接好 SavedStateRegistry，
 * 帮我们生成可用的 SavedStateHandle 实例。
 *
 * 用法（ChatScreen.kt）：
 * ```
 * val context = LocalContext.current
 * val owner = LocalSavedStateRegistryOwner.current
 * val chatViewModel: ChatViewModel = viewModel(
 *     factory = ChatViewModelFactory(context.applicationContext as Application, owner),
 * )
 * ```
 *
 * 注意：owner 必须是当前 Composable 所在的 SavedStateRegistryOwner（一般是宿主
 * Activity，通过 LocalSavedStateRegistryOwner.current 取），不能传 Application——
 * Application 没有 SavedStateRegistry，传错会在构造时直接崩溃。
 */
class ChatViewModelFactory(
    private val application: Application,
    owner: SavedStateRegistryOwner,
    defaultArgs: Bundle? = null,
) : AbstractSavedStateViewModelFactory(owner, defaultArgs) {

    override fun <T : ViewModel> create(
        key: String,
        modelClass: Class<T>,
        handle: SavedStateHandle,
    ): T {
        require(modelClass.isAssignableFrom(ChatViewModel::class.java)) {
            "ChatViewModelFactory 只能创建 ChatViewModel，收到：${modelClass.name}"
        }
        @Suppress("UNCHECKED_CAST")
        return ChatViewModel(application) as T
    }
}
