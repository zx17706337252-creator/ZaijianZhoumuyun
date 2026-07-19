package com.zaijian.zhoumuyun.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

// ─────────────────────────────────────────────────────────────
//  DaughterIdAllocatorEntity — 女儿角色编号发号器（D4，v25→v26）
//
//  作用：给每个新生成的女儿分配一个不会和现有角色（蒂法/露娜等，
//  id 1-9）、也不会和其他女儿互相冲突的全新 characterId。
//
//  设计成单行表（只有一条记录，固定 id=0），存"下一个可用编号"，
//  每发一个号就 +1。比起"查表里最大值再+1"，这种方式在并发场景下
//  更安全——配合 DaughterIdAllocator.allocate() 内部 Mutex 防止进程内并发冲突。
//
//  起始值定为 1000：留出足够空间给未来可能扩展的预设角色（蒂法们
//  目前是 1-9，即使扩到 1-99 也远够不到 1000），避免编号撞车。
// ─────────────────────────────────────────────────────────────

@Entity(tableName = "daughter_id_allocator")
data class DaughterIdAllocatorEntity(
    /** 固定主键，整张表只有一行，id 恒为 0 */
    @PrimaryKey
    val id: Int = 0,

    /** 下一个可分配的女儿 characterId */
    val nextId: Int = FIRST_DAUGHTER_ID,
) {
    companion object {
        /** 女儿编号起始值。预设角色（蒂法/露娜等）占用 1-9，这里从 1000 起跳，留足安全余量。 */
        const val FIRST_DAUGHTER_ID = 1000
    }
}
