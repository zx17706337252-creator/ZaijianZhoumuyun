package com.zaijian.zhoumuyun.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zaijian.zhoumuyun.data.AppContainer
import com.zaijian.zhoumuyun.data.db.entity.CharacterTitleRelationEntity
import com.zaijian.zhoumuyun.data.db.entity.ImpersonationPresetEntity
import com.zaijian.zhoumuyun.data.model.CharacterConfig
import com.zaijian.zhoumuyun.data.model.DefaultCharacters
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 头衔管理页 ViewModel（方案_角色间关系头衔系统_实施方案 三节）
 *
 * 页面结构：选择当前角色 A → 列出 A 对其余所有角色/预设身份的头衔（单向列表，
 * 逐条可编辑）；另有一个独立分区做假扮预设名单 CRUD。
 *
 * "可配置组合"列表 = 初代 9 人（DefaultCharacters）+ 运行时女儿/孙女角色
 * （daughterCharacterRepo.observeAllCharacterConfigs()），两者 id 空间不重叠
 * （女儿 characterId >= 1000，见方案一节），直接拼接展示，不写死人数——
 * 新增女儿后自动出现在角色选择器和目标列表里。
 */
class CharacterTitleRelationViewModel(application: Application) : AndroidViewModel(application) {

    private val container = AppContainer.instance
    private val titleRepo = container.characterTitleRelationRepo
    private val daughterRepo = container.daughterCharacterRepo

    /**
     * 全部可配置角色（初代 + 运行时女儿/孙女），按 id 排序，供选择器 + 目标列表复用。
     *
     * daughterRepo.observeAllCharacterConfigs() 本身已返回"母亲(DefaultCharacters
     * 中 isUnlocked=true 的项，9 位初代默认全部解锁) + 已注册女儿/孙女"完整列表，
     * 这里只需排序即可，**不能再叠加 DefaultCharacters**——否则每位初代角色在列表
     * 中出现两次，下游 LazyColumn 的 items(key = "char_${id}") 会因重复 key 抛
     * IllegalArgumentException 导致闪退（修复"点击角色关系头衔闪退"bug）。
     */
    val allCharactersMerged: StateFlow<List<CharacterConfig>> =
        daughterRepo.observeAllCharacterConfigs()
            .map { allCharacters -> allCharacters.sortedBy { it.id } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, DefaultCharacters.sortedBy { it.id })

    /** 当前选中的角色 A（默认取第一个初代角色）。 */
    private val _selectedCharacterId = MutableStateFlow(DefaultCharacters.firstOrNull()?.id ?: 1)
    val selectedCharacterId: StateFlow<Int> = _selectedCharacterId.asStateFlow()

    /** A 对其余所有真实角色 + 预设身份的全部头衔行（含空行，供列表展示）。 */
    private val _relationsForSelected = MutableStateFlow<List<CharacterTitleRelationEntity>>(emptyList())
    val relationsForSelected: StateFlow<List<CharacterTitleRelationEntity>> = _relationsForSelected.asStateFlow()

    val allPresets: StateFlow<List<ImpersonationPresetEntity>> =
        titleRepo.observeAllPresets()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    private var loadJob: Job? = null

    init {
        loadRelationsForSelected()
    }

    fun selectCharacter(characterId: Int) {
        if (_selectedCharacterId.value == characterId) return
        _selectedCharacterId.value = characterId
        loadRelationsForSelected()
    }

    private fun loadRelationsForSelected() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            titleRepo.observeAllForCharacter(_selectedCharacterId.value).collect { rows ->
                _relationsForSelected.value = rows
            }
        }
    }

    /**
     * 设置 A 对某个真实角色 toId 的头衔（自由文本，允许清空）。
     * 失焦保存调用点，每次调用都是一次完整 upsert。
     */
    fun setTitleForCharacter(toId: Int, title: String) {
        val fromId = _selectedCharacterId.value
        viewModelScope.launch {
            titleRepo.setTitle(fromId, toId, title)
        }
    }

    /** 设置 A 对某个预设身份 toName 的头衔。 */
    fun setTitleForPresetName(toName: String, title: String) {
        val fromId = _selectedCharacterId.value
        viewModelScope.launch {
            titleRepo.setTitleForPresetName(fromId, toName, title)
        }
    }

    // ── 预设名单 CRUD ────────────────────────────────────────────

    fun addPreset(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            _toast.value = "名字不能为空"
            return
        }
        viewModelScope.launch {
            val exists = allPresets.value.any { it.name == trimmed }
            if (exists) {
                _toast.value = "该名字已在名单中"
                return@launch
            }
            titleRepo.addPreset(trimmed)
            _toast.value = "已添加「$trimmed」"
        }
    }

    fun removePreset(name: String) {
        viewModelScope.launch {
            titleRepo.removePreset(name)
            _toast.value = "已删除「$name」"
        }
    }

    fun clearToast() { _toast.value = null }

    /** 解析角色显示名，供列表行展示。查不到时兜底显示 id。 */
    fun resolveCharacterName(characterId: Int): String {
        DefaultCharacters.firstOrNull { it.id == characterId }?.let { return it.name }
        allCharactersMerged.value.firstOrNull { it.id == characterId }?.let { return it.name }
        return "角色$characterId"
    }
}
