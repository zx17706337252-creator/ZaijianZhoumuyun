package com.zaijian.zhoumuyun.domain

/**
 * v1.36 问题3 修复：用户性别枚举。
 *
 * 与 [CharacterIdentityEntity][com.zaijian.zhoumuyun.data.db.entity.CharacterIdentityEntity]
 * 的 `userGender: String` 列一一对应——Entity 层不直接用 `@TypeConverters` 映射枚举
 * （项目里 MoodType/StatusType 等既有枚举都是"DB 存字符串 + 单独写转换函数"的风格，
 * 这里保持一致），转换逻辑集中在本文件的 [parseUserGenderType] / [UserGenderType.dbValue]。
 *
 * UNSPECIFIED 是用户显式选择"不指定"（不希望提示词里出现性别指代规则），
 * 与"角色从未配置过"是两回事——后者由 Entity 的默认值 `"MALE"` 兜底，
 * 不会落到 UNSPECIFIED。
 */
enum class UserGenderType {
    MALE,
    FEMALE,
    UNSPECIFIED,
}

/** UserGenderType → 中文展示/提示词用词（"男性"/"女性"）。UNSPECIFIED 无展示词，返回 null。 */
val UserGenderType.displayLabel: String?
    get() = when (this) {
        UserGenderType.MALE -> "男性"
        UserGenderType.FEMALE -> "女性"
        UserGenderType.UNSPECIFIED -> null
    }

/** UserGenderType → Room 列存储值，与 [parseUserGenderType] 互为逆运算。 */
val UserGenderType.dbValue: String
    get() = name

/**
 * Room 列字符串 → UserGenderType。未识别的值（理论上不会出现，防御性兜底）
 * 按 MALE 处理，与 Entity 默认值语义保持一致。
 */
fun parseUserGenderType(raw: String?): UserGenderType = when (raw) {
    "FEMALE" -> UserGenderType.FEMALE
    "UNSPECIFIED" -> UserGenderType.UNSPECIFIED
    else -> UserGenderType.MALE
}
