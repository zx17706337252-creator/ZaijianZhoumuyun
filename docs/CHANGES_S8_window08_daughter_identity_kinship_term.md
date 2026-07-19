# S8 窗口08 修复：DaughterIdentity.gender 回填 + 命名对齐

> 对应 `zaijian_s8_window08_audit_report.md`。窗口08报告9条主结论 +
> 12条复核全部"复核通过"，代码本身无问题。复核过程中新发现的3条
> （N01/N02/N03）里，N03（母亲角色初始状态词库未接入）本质是待办事项
> ——报告里明确写着"TODO 已标注但尚未实现"，需要为9个角色分别设计
> 有区分度的初始数值，属于新功能范畴，本轮不做。N01、N02 是两个
> 小缺口（一个是死字段、一个是命名不一致），本轮处理。

## 新问题01：DaughterIdentity.gender 始终为 null

### 问题

`DaughterIdentity.gender` 字段（P1-47 修复新增）一直没有数据源：
D4 生成器 LLM 输出的 `identityJson` 从不包含这个字段，
`toDaughterCharacterData()` 转换实体到强类型层时也没有从
`DaughterCharacterEntity.kinshipTerm`（数据库列，D4 生成器写入的
"女儿"/"孙女"）回填。结果是一个"写不进去、读出来永远是 null"的
死字段。

注：FamilyScreen 实际展示用的是另一条完全独立的路径
（`DaughterCharacterRepository.getFamilyChain()` → `FamilyMember.gender`，
直接来自 `kinshipTerm`），不受这个字段影响，所以这个 bug 之前没有
从功能上表现出来——纯粹是 `DaughterIdentity` 这一层的字段形同虚设。

### 修复

`toDaughterCharacterData()` 里从 `entity.kinshipTerm` 显式回填：

```kotlin
val identity = DaughterIdentity.fromJson(identityJson).let {
    if (it.kinshipTerm == null && kinshipTerm != null) it.copy(kinshipTerm = kinshipTerm) else it
}
```

只在 `identityJson` 解析出的值为 null 时才回填，保留了"如果未来
identityJson 真的开始携带这个字段，以 identityJson 里的值为准"的
优先级（虽然目前不会发生，因为 `toJson()` 序列化路径也没有变化，
见下）。

## 新问题02：gender 与 kinshipTerm 命名不一致

### 问题

`DaughterCharacterEntity` 一侧的字段已经在 P3-11 修复里从
`gender` 重命名为 `kinshipTerm`（数据库列名保留 `gender` 避免
Migration），但 `DaughterIdentity` 一侧的字段名没有同步，两个文件
对同一个语义概念（代际称呼词）用了不同的名字。

### 修复

`DaughterIdentity.gender` → `DaughterIdentity.kinshipTerm`，与
`DaughterCharacterEntity` 对齐。JSON 序列化/反序列化用的 key 不动，
仍然是 `"gender"`——这是持久化格式，改 key 名要考虑历史数据兼容，
而这次命名问题本身只是 Kotlin 侧代码可读性问题，不需要牵扯到存储
格式：

```kotlin
// 字段声明
val kinshipTerm: String? = null,

// 序列化（JSON key 不变）
if (kinshipTerm != null) put("gender", kinshipTerm)

// 反序列化（JSON key 不变）
kinshipTerm = json.optStringOrNull("gender"),
```

### 影响范围核查

全项目搜索 `DaughterIdentity` 的使用点（`AgentRelationEngine.kt`
三处引用），确认没有代码读取过 `DaughterIdentity.gender`——这个字段
之前确实没有消费者，重命名不影响任何现有调用点。

## 未处理项

- **N03 / 结论09（母亲角色初始状态词库未接入）**：`CharacterStateLayer.kt`
  的 TODO 明确标注"为每个角色提供独立默认值"尚未实现，9位角色（蒂法/
  露娜/伊芙/宥熙/索菲娅/顾澜/明媚/莫婉凝/江凡）首次对话时共享同一份
  `DefaultInitialCharacterStateLayer`。这是待办事项而非本轮修复的
  逻辑bug，需要参考每个角色的 persona 设计单独定初始数值，工作量
  和性质都更接近新功能开发，留待后续单独排期。
- 结论01-08：报告本身已确认"已确认解决"，其中结论06（Trust衰减
  排除女儿）、结论08（种类维度中性占位符）都是明确的既有设计决策，
  不是代码缺陷，本轮未动。

## 改动文件

- `app/src/main/java/com/zaijian/zhoumuyun/data/model/DaughterIdentity.kt`
  - `DaughterIdentity.gender` 重命名为 `kinshipTerm`
  - `toJson()`/`fromJson()` 同步改用新字段名（JSON key 仍是 `"gender"`）
  - `toDaughterCharacterData()` 补上从 `entity.kinshipTerm` 的回填逻辑
