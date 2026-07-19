package com.zaijian.zhoumuyun.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.zaijian.zhoumuyun.data.model.BirthRecord

// ─────────────────────────────────────────────────────────────
//  BirthRecordEntity — P4.0（V5 执行方案）
//  历次生育记录，一条 = 一次产子。自增主键，按 characterId 建索引
//  方便角色档案页按时间倒序查询。
// ─────────────────────────────────────────────────────────────

@Entity(
    tableName = "birth_records",
    indices = [Index("characterId")],
)
data class BirthRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val characterId: Int,
    val bornAt: Long,
    /** true=女孩，false=男孩 */
    val isDaughter: Boolean,
)

fun BirthRecordEntity.toDomain(): BirthRecord = BirthRecord(
    characterId = characterId,
    bornAt      = bornAt,
    isDaughter  = isDaughter,
)
