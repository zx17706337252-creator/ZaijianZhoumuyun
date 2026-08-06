package com.zaijian.zhoumuyun.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 私聊实时同步修复: Migration v79 → v80——private_chat_sessions 新增 notifiedCharacterIds 列
 *
 * 背景：私聊动态播报（ChatMessageOrchestrator）此前用"近2小时内"的时间窗口判断要不要
 * 向角色播报"你最近和XX私聊过"，超过2小时窗口就不再提醒，只能靠角色自己检索跨 session
 * 记忆（需要用户先在对话里提到对方名字才会命中 FTS）兜底。这导致被动一方（尤其是发起方
 * 之外、没有主动调用 private_chat_send 的那一方）如果2小时内没被用户找去聊天，就有可能
 * 完全错过被告知这次私聊发生过的机会。
 *
 * 新增 private_chat_sessions.notifiedCharacterIds 列（TEXT，默认空字符串 ''），存放
 * 逗号分隔的 characterId 列表，记录"这次会话已经在跟哪些角色的主对话里播报过"。播报逻辑
 * 从"按时间窗口查"改为"按未告知查"——只要某个参与角色的 id 不在这个列表里，不管这次
 * 私聊是多久之前发生的，下次该角色跟用户对话时都会被播报一次，播报后把自己的 id 追加
 * 进这个列表，不会重复播报同一次会话。
 *
 * 存量数据默认回填为空字符串：迁移前已发生的私聊会话视为"尚未告知任何角色"，迁移后
 * 上线的第一次对话会把这些历史会话也补播一遍——对用户来说是可接受的行为（好过永久错过），
 * 且只会发生一次（补播后即写入 notifiedCharacterIds，不会重复触发）。
 *
 * 纯新增列（ADD COLUMN ... NOT NULL DEFAULT ''），不涉及表重建。列本身不可空，
 * 缺省即空字符串，与下方 SQL 的 NOT NULL DEFAULT '' 保持一致（此前这里误写成
 * "可空默认列"，实际约束一直是 NOT NULL——功能不受影响，仅更正注释描述）。
 */
internal val MIGRATION_79_80 = object : Migration(79, 80) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `private_chat_sessions` ADD COLUMN `notifiedCharacterIds` TEXT NOT NULL DEFAULT ''"
        )
    }
}
