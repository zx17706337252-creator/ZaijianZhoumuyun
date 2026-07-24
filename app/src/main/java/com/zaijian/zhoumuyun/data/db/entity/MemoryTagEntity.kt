package com.zaijian.zhoumuyun.data.db.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * L2 记忆索引层（Window A-1）。
 *
 * 为每条记忆建立结构化标签索引，支持按 tag 快速定位——比 FTS4 全文检索
 * 更精准、更快。检索路由优先查 L2（tag 精确匹配），未命中再降级到
 * L1（FTS4 全文匹配）。
 *
 * ## 与 MemoryEntity.keywords 的关系
 *
 * `MemoryEntity.keywords` 是空格分隔的字符串，同步写入 FTS4 虚拟表。
 * 本表把 keywords 拆分为独立行（一行一个 tag），额外加上 domain 和
 * importance 衍生的权重，使检索可以按 tag 精确匹配 + 权重排序，
 * 而非 FTS4 的模糊全文匹配。
 *
 * ## 与 MemoryFtsEntity 的关系
 *
 * L1（FTS4）：全文模糊匹配，适合"用户说了什么"的宽泛检索
 * L2（本表）：结构化标签精确匹配，适合"用户意图是什么"的精准检索
 * 检索路由：L2 优先 → 未命中走 L1
 *
 * ## 数据一致性
 *
 * 写入：[com.zaijian.zhoumuyun.data.repository.MemoryRepository.save()]
 * 和 [update()][com.zaijian.zhoumuyun.data.repository.MemoryRepository.update()]
 * 内部同步维护本表（delete + re-insert，原子事务）。
 *
 * @param id          UUID，主键
 * @param memoryId    关联的记忆 ID（非外键，不级联删除——由 MemoryRepository 同步维护）
 * @param characterId 角色 ID（冗余存储，避免 JOIN 查询）
 * @param tag         标签字符串（从 keywords 拆分，或 domain 值）
 * @param weight      权重（importance 值，用于 L2 命中后的排序）
 * @param createdAt   创建时间戳
 */
@Entity(
    tableName = "memory_tags",
    indices = [
        Index(value = ["characterId"]),
        Index(value = ["characterId", "tag"]),
        Index(value = ["memoryId"]),
    ],
)
data class MemoryTagEntity(
    @androidx.room.PrimaryKey val id: String,
    val memoryId: String,
    val characterId: Int,
    val tag: String,
    val weight: Int,
    val createdAt: Long,
)
