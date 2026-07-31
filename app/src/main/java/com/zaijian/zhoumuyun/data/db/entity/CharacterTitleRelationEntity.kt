package com.zaijian.zhoumuyun.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 角色间关系头衔（方案_角色间关系头衔系统_实施方案 二节）
 *
 * 方向性存储：fromCharacterId 对 to 一方的头衔认定，不是对称关系
 * （A 认 B 做"姐姐"不代表 B 也认 A 做"姐姐"，需要各存一行）。
 *
 * to 一方分两种情况：
 * - 真实角色（初代或女儿/孙女）：toCharacterId 非空，toPresetName 为 null
 * - 假扮识别预设名单里的虚构身份（无对应 characterId，如"表妹"）：
 *   toPresetName 非空，toCharacterId 为 null
 * 业务层保证两者有且仅有一个非空，不用 DB CHECK 约束（Room 对此支持有限）。
 *
 * 不用复合主键：其中一列可能为 null，Room 用 null 列做主键不安全，
 * 改用自增 id 主键，toCharacterId / toPresetName 分别建索引供查询。
 */
@Entity(
    tableName = "character_title_relations",
    indices = [
        Index(value = ["fromCharacterId", "toCharacterId"], unique = true),
        Index(value = ["fromCharacterId", "toPresetName"], unique = true),
        Index(value = ["toCharacterId"]),
        Index(value = ["toPresetName"]),
    ],
)
data class CharacterTitleRelationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fromCharacterId: Int,
    val toCharacterId: Int? = null,
    val toPresetName: String? = null,
    val title: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
)
