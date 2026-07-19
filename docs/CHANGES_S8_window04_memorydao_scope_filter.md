# S8 窗口04 修复：MemoryDao 记忆统计/查询 scope 过滤补齐

> 对应 `zaijian_s8_window04_roundtable_audit.md` 新发现1、新发现2（均为
> 逻辑bug(P3)/代码质量问题，严重度极低，v2 复核确认"未修复"）。
> 窗口04报告本身12条结论均为"已确认解决"或"既有设计决策"，无需处理；
> 仅这两条新发现需要动代码。

## 新发现1：`MemoryDao.count()` 未按 scope 过滤

### 问题

```kotlin
@Query("SELECT COUNT(*) FROM memories WHERE characterId = :characterId")
abstract suspend fun count(characterId: Int): Int
```

`characterId` 在群记忆（`scope = 'GROUP'`）里记录的是发言人 ID，不是"仅属于
该角色"的专属记忆。`ProfileViewModel.loadStats()` 用
`allIds.sumOf { memoryRepo.count(it) }` 跨全部角色（含女儿Agent）累加展示
"记忆条数"统计，不加过滤会把群记忆也计入个人记忆总数，且随圆桌参与角色数
增多进一步虚高——影响范围仅限 Profile 统计数字展示，不影响任何功能逻辑。

### 修复

```kotlin
@Query("SELECT COUNT(*) FROM memories WHERE characterId = :characterId AND scope = 'PERSONAL'")
abstract suspend fun count(characterId: Int): Int
```

与本文件其余 PERSONAL 侧查询（`observeAll`/`getCoreMemories`/`observeImportant`
等）保持一致的过滤口径。

### 影响范围核实

全项目 `count()` 调用点仅两处：

- `ProfileViewModel.kt:86`（唯一生产代码调用点，本次修复的目标场景）
- `DataLayerConcurrencyTest.kt:116, 256`（两处单元测试，构造的
  `MemoryEntity` 均未显式传 `scope`，走 `MemoryScope.PERSONAL.name` 默认值，
  加过滤后断言仍然成立，不受影响）

## 新发现2：`observeAboutUser()` / `observeAboutWorld()` 无 scope 过滤（死代码）

### 问题

两个方法全项目零调用点（`Repository` 层有对应的纯透传包装，但同样零调用），
`domain` 过滤（`'PERSONAL'`/`'WORLD'`）与 `scope` 过滤是两个独立维度，
原查询缺 `scope = 'PERSONAL'`，与本文件其余同类查询口径不一致。

### 修复

按报告建议的"保留但补齐过滤"方案（而非删除死代码——是否清理死代码留给
专门的代码清理批次，不在本次范围）：

```kotlin
@Query("""
    SELECT * FROM memories
    WHERE characterId = :characterId AND domain = 'PERSONAL' AND scope = 'PERSONAL'
    ORDER BY updatedAt DESC
""")
abstract fun observeAboutUser(characterId: Int): Flow<List<MemoryEntity>>

@Query("""
    SELECT * FROM memories
    WHERE characterId = :characterId AND domain = 'WORLD' AND scope = 'PERSONAL'
    ORDER BY updatedAt DESC
""")
abstract fun observeAboutWorld(characterId: Int): Flow<List<MemoryEntity>>
```

`MemoryRepository.observeAboutUser/observeAboutWorld` 是纯透传，无需改动。

## 未处理项（按报告结论，无需修复）

- **结论9**（`finishDiscussion()` 在 `finally` 中重复执行）：报告 v1/v2 均
  复核确认"无副作用，finally 承担了 `isScheduling` 清理职责"，是既有设计
  的冗余保护而非 bug，未改动。
- 其余12条结论（1-8, 10-12）：窗口04报告本身已标注"已确认解决"或"既有
  设计决策（无问题）"，不涉及代码变更。

## 改动文件

- `app/src/main/java/com/zaijian/zhoumuyun/data/db/dao/MemoryDao.kt`
  - `count()`：SQL 增加 `AND scope = 'PERSONAL'`
  - `observeAboutUser()` / `observeAboutWorld()`：SQL 增加
    `AND scope = 'PERSONAL'`
