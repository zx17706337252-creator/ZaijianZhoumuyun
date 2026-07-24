package com.zaijian.zhoumuyun.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.zaijian.zhoumuyun.data.AppContainer
import com.zaijian.zhoumuyun.data.model.CharacterConfig
import com.zaijian.zhoumuyun.data.model.DaughterDataException
import com.zaijian.zhoumuyun.util.ZLog

// ═══════════════════════════════════════════════════════════════
//  DaughterCharacterViewModel（E0 分层收口 · 女儿角色数据）
//
//  职责：
//  - 把 CharacterDetailScreen 原先对 AppContainer.instance.daughterCharacterRepo
//    的直接持有，收敛到 ViewModel 层。UI 侧只调用本 ViewModel 的只读方法，
//    不再直接触碰 Repository（E0 coupling_scan 违规点 #4、#6 的修复落地）。
//
//  异常策略：
//  - DaughterDataException（女儿数据损坏）在 VM 内兜底为 null 并记录日志，
//    UI 侧无需再各自 try-catch，与 CharacterDetailScreen 原有"角色不存在兜底页"
//    语义保持一致。
//
//  范式对齐 AgentActivityViewModel.kt：AndroidViewModel + AppContainer.instance.xxxRepo。
// ═══════════════════════════════════════════════════════════════

class DaughterCharacterViewModel(application: Application) : AndroidViewModel(application) {

    private val daughterCharacterRepo = AppContainer.instance.daughterCharacterRepo

    /**
     * 按 daughterCharacterId 查女儿角色配置。
     * 数据损坏（DaughterDataException）时记录日志并返回 null，由 UI 走"角色不存在"兜底页。
     */
    suspend fun getCharacterConfig(daughterCharacterId: Int): CharacterConfig? = try {
        daughterCharacterRepo.getCharacterConfig(daughterCharacterId)
    } catch (e: DaughterDataException) {
        ZLog.e("DaughterCharacterViewModel", "characterId=$daughterCharacterId 女儿数据损坏，无法加载", e)
        null
    }

    /**
     * 按母亲 ID 查女儿角色，返回女儿自己的 characterId（无则 null）。
     * 供孕育模块"生育记录点击 → 跳转子代详情"使用。
     */
    suspend fun getDaughterIdByMother(motherCharacterId: Int): Int? =
        daughterCharacterRepo.getByMother(motherCharacterId)?.daughterCharacterId
}
