package com.zaijian.zhoumuyun.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 假扮身份识别·预设名单（方案_角色间关系头衔系统_实施方案 二节）
 *
 * 独立于 DefaultCharacters 之外的名字名单，用于"我不是主人，我是XX"精确匹配。
 * name 不要求对应真实 characterId——命中后去 character_title_relations 查询时，
 * 若 name 同时是正式角色则按 toCharacterId 查，否则按 toPresetName（字符串）查。
 * 全局共用一份，不按角色分开配置。
 */
@Entity(tableName = "impersonation_presets")
data class ImpersonationPresetEntity(
    @PrimaryKey val name: String,
    val createdAt: Long = System.currentTimeMillis(),
)
