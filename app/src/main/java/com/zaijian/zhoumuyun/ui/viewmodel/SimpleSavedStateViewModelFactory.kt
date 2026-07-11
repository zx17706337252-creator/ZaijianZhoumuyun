package com.zaijian.zhoumuyun.ui.viewmodel

import android.app.Application
import android.os.Bundle
import androidx.lifecycle.AbstractSavedStateViewModelFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.savedstate.SavedStateRegistryOwner

/**
 * #35 修复（SavedStateHandle）Step 2：通用工厂。
 *
 * 注意：本工厂要求的构造签名是 `(Application, SavedStateHandle)`，但实际只有
 * PregnancyViewModel 是这个签名；GoalViewModel / IdentityViewModel /
 * LearningGoalViewModel / MemoryViewModel 这 4 个目前构造签名都只有
 * `(Application)` 单参数，并未使用 SavedStateHandle，也没有通过本工厂构造。
 * 跟 ChatViewModel 同款套路但没有额外参数，没必要照搬 ChatViewModelFactory
 * 那样每个写一个专属工厂类——传个构造函数引用就行。
 *
 * 用法（各 Screen.kt，以 IdentityViewModel 为例）：
 * ```
 * val context = LocalContext.current
 * val owner = LocalSavedStateRegistryOwner.current
 * val identityViewModel: IdentityViewModel = viewModel(
 *     factory = SimpleSavedStateViewModelFactory(
 *         application = context.applicationContext as Application,
 *         owner       = owner,
 *         create      = ::IdentityViewModel,
 *     ),
 * )
 * ```
 *
 * 同 ChatViewModelFactory：owner 必须是 LocalSavedStateRegistryOwner.current
 * （宿主 Activity / NavBackStackEntry），不能传 Application。
 */
class SimpleSavedStateViewModelFactory<T : ViewModel>(
    private val application: Application,
    owner: SavedStateRegistryOwner,
    private val create: (Application, SavedStateHandle) -> T,
    defaultArgs: Bundle? = null,
) : AbstractSavedStateViewModelFactory(owner, defaultArgs) {

    @Suppress("UNCHECKED_CAST")
    override fun <VM : ViewModel> create(
        key: String,
        modelClass: Class<VM>,
        handle: SavedStateHandle,
    ): VM {
        return create(application, handle) as VM
    }
}
