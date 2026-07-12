package com.zaijian.zhoumuyun.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.zaijian.zhoumuyun.data.db.entity.DaughterIdAllocatorEntity

// 问题32修复：由 interface 改为 abstract class，与 PregnancyAnswerDao（问题
// P1-6-9 修复）同一先例，用于承载带 @Transaction 的具体方法 allocateNext()。
// 原 getRow()/insertIfAbsent()/updateNextId() 三个独立方法保留不动（仍可能被
// 其它只读场景单独调用），新增 allocateNext() 把 DaughterIdAllocator.allocate()
// 里"插入默认行→读当前值→自增写回"三步合并为单个 SQLite 事务——此前这三步是
// 三次独立的挂起函数调用，中间任意一步之后进程崩溃，都会导致"号已经读出来但
// nextId 未及时+1"或反之的不一致状态，是报告问题32所说的"极端情况下产生空洞"
// 的根本原因。用 @Transaction 包裹后，三步要么全部生效要么全部不生效，
// 消除了这个中间状态窗口（不能消除的是"号被读出来分配给调用方后，调用方自己
// 没有真正用掉这个号"这一层——那是调用方职责，DAO 层的事务保护解决不了，
// 也不需要解决，注释里已经说明这种情况"不影响正确性，只留空洞"）。
@Dao
abstract class DaughterIdAllocatorDao {

    @Query("SELECT * FROM daughter_id_allocator WHERE id = 0")
    abstract suspend fun getRow(): DaughterIdAllocatorEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertIfAbsent(row: DaughterIdAllocatorEntity = DaughterIdAllocatorEntity())

    @Query("UPDATE daughter_id_allocator SET nextId = :nextId WHERE id = 0")
    abstract suspend fun updateNextId(nextId: Int)

    /**
     * 问题32修复核心：原子化"确保默认行存在→读当前 nextId→自增写回"三步，
     * @Transaction 保证单个 SQLite 事务内完成。
     * @return 本次分配到的 characterId（自增前的值）。
     */
    @Transaction
    open suspend fun allocateNext(): Int {
        insertIfAbsent()
        val row = getRow() ?: DaughterIdAllocatorEntity()
        val allocated = row.nextId
        updateNextId(allocated + 1)
        return allocated
    }
}
