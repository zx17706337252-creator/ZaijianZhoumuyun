package com.zaijian.zhoumuyun.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v58 → v59：用户身份设定（v1.36 三项修复方案问题3）。
 *
 * 修复"角色统一用「她」称呼用户"——提示词此前从未注入用户性别/关系身份，
 * 模型只能瞎猜。character_identity 表新增 4 列，按角色单独配置：
 *   - userGender：MALE/FEMALE/UNSPECIFIED，NOT NULL DEFAULT 'MALE'
 *     （存量角色此前从未配置，默认按男性处理，避免继续空值瞎猜）。
 *   - userRoleLabelPrivate / userRoleLabelPublic：私下/公开（圆桌）关系称谓，
 *     NOT NULL DEFAULT ''（空值语义为"未设置"，Prompt 组装层据此跳过注入）。
 *   - publicPrivacyReason：公开场合为何不用私下称谓，NOT NULL DEFAULT ''。
 * 纯新增列，不改动任何现有数据；4 个默认值全部与 Entity 类默认值保持一致。
 */
internal val MIGRATION_58_59 = object : Migration(58, 59) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `character_identity` ADD COLUMN `userGender` TEXT NOT NULL DEFAULT 'MALE'")
        db.execSQL("ALTER TABLE `character_identity` ADD COLUMN `userRoleLabelPrivate` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `character_identity` ADD COLUMN `userRoleLabelPublic` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `character_identity` ADD COLUMN `publicPrivacyReason` TEXT NOT NULL DEFAULT ''")
    }
}
