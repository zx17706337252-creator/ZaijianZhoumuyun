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
 * 为所有构造签名为 `(Application, SavedStateHandle)` 的 ViewModel 提供统一工厂。
 * 当前使用本工厂的 ViewModel：
 *   - SpecialtyEvolutionViewModel
 *   - JudgeProfileViewModel
 *   - CompetitionViewModel
 *
 * 注意：PregnancyViewModel 虽然也是 `(Application, SavedStateHandle)` 签名，
 * 但它通过默认 viewModel() 构造（由 SavedStateViewModelFactory 自动处理），
 * 不经过本工厂。
 *
 * 用法（各 Screen.kt，以 JudgeProfileViewModel 为例）：
 * ```
 * val context = LocalContext.current
 * val owner = LocalSavedStateRegistryOwner.current
 * val viewModel: JudgeProfileViewModel = viewModel(
 *     factory = SimpleSavedStateViewModelFactory(
 *         application = context.applicationContext as Application,
 *         owner       = owner,
 *         create      = ::JudgeProfileViewModel,
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
