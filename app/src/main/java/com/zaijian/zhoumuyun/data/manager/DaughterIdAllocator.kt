package com.zaijian.zhoumuyun.data.manager

import com.zaijian.zhoumuyun.data.db.dao.DaughterIdAllocatorDao
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
     * 问题32修复：原实现是"插入默认行→读当前值→自增写回"三次独立的挂起函数
     * 调用，中间任意一步之后进程崩溃会留下不一致状态（见 DaughterIdAllocatorDao
     * .allocateNext() 的详细说明）。现改为调用 DAO 层新增的 @Transaction
     * allocateNext()，三步合并为单个 SQLite 事务，消除这个中间状态窗口。
     *
     * 进程内的 Mutex 仍然保留：@Transaction 保护的是"跨进程崩溃恢复后的数据
     * 一致性"，不是"同一进程内两个协程同时调用 allocate() 时谁先谁后"这个
     * 调度顺序问题——Room 事务本身允许并发调用排队执行，但 Mutex 能让调用方
     * 在语义上更明确地感知到"这是一个需要互斥的操作"，且与本类文件头注释
     * 里已经说明的"进程内不会有真正并发场景"的判断不矛盾，保留不改变现有
     * 行为，只是把 DB 层的原子性補齐。
     */
    private val mutex = kotlinx.coroutines.sync.Mutex()

    suspend fun allocate(): Int = mutex.withLock {
        dao.allocateNext()
    }
}
