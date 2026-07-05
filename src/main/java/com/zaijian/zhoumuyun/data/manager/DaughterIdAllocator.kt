package com.zaijian.zhoumuyun.data.manager

import com.zaijian.zhoumuyun.data.db.dao.DaughterIdAllocatorDao
import com.zaijian.zhoumuyun.data.db.entity.DaughterIdAllocatorEntity
import kotlinx.coroutines.sync.withLock

// ─────────────────────────────────────────────────────────────
//  DaughterIdAllocator — 女儿角色编号发号器
//
//  职责：每次有新女儿需要登记角色身份时，分配一个全新、不冲突的
//  characterId（从 1000 起跳，见 DaughterIdAllocatorEntity 注释）。
//
//  使用方式：DaughterCharacterGenerator 成功生成并校验女儿 JSON 后，
//  调用 allocate() 拿到一个新 id，再用这个 id 写入 CharacterIdentityEntity。
//
//  注意：allocate() 每次调用都会消耗一个号（nextId 自增），
//  调用方必须保证拿到号之后真正用掉它（写入 CharacterIdentityEntity），
//  不要"取号但不用"，否则会留下空洞（不影响正确性，但浪费编号空间，
//  反正起始值留了足够余量，空洞本身不是问题）。
// ─────────────────────────────────────────────────────────────

class DaughterIdAllocator(
    private val dao: DaughterIdAllocatorDao,
) {
    /**
     * 分配一个新的女儿 characterId。
     *
     * 首次调用时表里还没有行，会先插入一条默认记录（nextId = 1000），
     * 之后每次调用返回当前 nextId 并把表里的值 +1。
     *
     * 注意：这不是一个跨进程的强一致分配器（没有用 DB 事务级别的行锁），
     * 但 Room 在单个 Android 进程内对同一张表的写操作是序列化的，
     * 加上 D4 生成器全程在 viewModelScope 单协程链路里跑（见 ChatViewModel
     * .maybeTriggerDaughterGeneration 的 in-flight Set 保护），不会有
     * 真正的并发调用场景，目前的实现足够安全。
     */
    private val mutex = kotlinx.coroutines.sync.Mutex()

    suspend fun allocate(): Int = mutex.withLock {
        dao.insertIfAbsent()
        val row = dao.getRow() ?: DaughterIdAllocatorEntity()
        val allocated = row.nextId
        dao.updateNextId(allocated + 1)
        allocated
    }
}
