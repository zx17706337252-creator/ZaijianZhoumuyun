package com.zaijian.zhoumuyun.ui.viewmodel

import com.zaijian.zhoumuyun.data.repository.ProjectRepository
import com.zaijian.zhoumuyun.util.ZLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.collections.immutable.persistentListOf

/**
 * 项目切换委托，从 ChatViewModel 中提取。
 * 管理聊天页面的活跃项目上下文。
 */
class ChatProjectDelegate(
    private val _uiState: MutableStateFlow<ChatUiState>,
    private val projectRepo: ProjectRepository,
    private val viewModelScope: CoroutineScope,
) {
    /** 设置或清除当前活跃项目（projectId=null 表示清除）。 */
    fun setActiveProject(projectId: String?) {
        viewModelScope.launch {
            try {
                val project = if (projectId != null) projectRepo.getById(projectId) else null
                _uiState.update {
                    it.copy(
                        activeProjectId = projectId,
                        activeProjects = if (project != null) persistentListOf(project) else persistentListOf(),
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                ZLog.e("ChatProjectDelegate", "设置活跃项目失败 projectId=$projectId", e)
                _uiState.update { it.copy(error = "设置项目失败，请重试") }
            }
        }
    }
}
