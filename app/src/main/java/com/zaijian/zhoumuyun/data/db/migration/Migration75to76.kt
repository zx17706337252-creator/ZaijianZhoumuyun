package com.zaijian.zhoumuyun.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONArray

/**
 * Migration 75 → 76：角色忠诚锁定机制（方案 v1.5）。
 *
 * 四张表新增列，纯 ADD COLUMN，不改动既有 schema 结构：
 *
 * 1. character_identity：新增 ownerAliasesJson / characterCallsOwnerJson
 *    （机制一 IdentityGuard 判定依据）。默认值用已有数据回填——
 *    ownerAliasesJson 填 [""]（空字符串占位，调用方按空数组处理），
 *    characterCallsOwnerJson 从 userRoleLabelPrivate 回填（若有）。
 *
 * 2. messages：新增 speakerContext（机制四状态隔离标记），默认 OWNER_DIRECT。
 *
 * 3. private_chat_pairs：新增 characterDisconnectState（6.4 角色自主下线），默认 ACTIVE。
 *
 * 4. memories：新增 isNarrativeOnly（4.2 记忆隔离标记），默认 0（false）。
 *
 * 所有新增列均有默认值，存量数据无需手动迁移，向后完全兼容。
 */
internal val MIGRATION_75_76 = object : Migration(75, 76) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 1. character_identity：owner 身份特征（机制一判定依据）
        db.execSQL("ALTER TABLE character_identity ADD COLUMN ownerAliasesJson TEXT NOT NULL DEFAULT '[]'")
        db.execSQL("ALTER TABLE character_identity ADD COLUMN characterCallsOwnerJson TEXT NOT NULL DEFAULT '[]'")

        // 回填：characterCallsOwnerJson 从 userRoleLabelPrivate 回填（若该列存在且有值）
        //
        // Fix-验收后-SQL转义bug：原实现用 SQL 字符串拼接手搓 JSON 转义
        // （REPLACE(REPLACE(..., '"', '\\"'), '\', '\\')），但 SQLite 字符串字面量
        // 不支持反斜杠转义（只有 '' 表示转义单引号这一种机制），'\\"' 和 '\' 在
        // SQLite 里是字面反斜杠字符，不是"转义后的引号"。当 userRoleLabelPrivate
        // 本身含双引号（用户自定义称呼完全可能出现，如 "老板"）时，拼出的
        // characterCallsOwnerJson 是损坏 JSON——不会崩溃（调用方 parseJsonArrayOrNull
        // 解析失败会 ?: emptyList() 兜底），但会导致该用户的称呼异常检测整条规则
        // 静默失效（detectAddressAnomaly 开头 characterCallsOwner.isEmpty() 直接
        // return false）。
        //
        // 改为逐行游标遍历 + org.json.JSONArray 做正规 JSON 序列化，再用
        // 参数化 UPDATE（?  占位符，不做任何手工字符串拼接/转义），彻底消除
        // 这一类转义错误的可能性。
        val cursor = db.query("SELECT characterId, userRoleLabelPrivate FROM character_identity")
        cursor.use {
            val idIdx = it.getColumnIndex("characterId")
            val labelIdx = it.getColumnIndex("userRoleLabelPrivate")
            while (it.moveToNext()) {
                val characterId = it.getInt(idIdx)
                val label = if (it.isNull(labelIdx)) null else it.getString(labelIdx)
                val json = if (!label.isNullOrEmpty()) {
                    JSONArray().put(label).toString()
                } else {
                    "[]"
                }
                db.execSQL(
                    "UPDATE character_identity SET characterCallsOwnerJson = ? WHERE characterId = ?",
                    arrayOf(json, characterId),
                )
            }
        }

        // 2. messages：speakerContext 标记（机制四状态隔离）
        db.execSQL("ALTER TABLE messages ADD COLUMN speakerContext TEXT NOT NULL DEFAULT 'OWNER_DIRECT'")

        // 3. private_chat_pairs：角色自主下线状态（6.4 节）
        db.execSQL("ALTER TABLE private_chat_pairs ADD COLUMN characterDisconnectState TEXT NOT NULL DEFAULT 'ACTIVE'")

        // 4. memories：叙事隔离标记（4.2 节）
        db.execSQL("ALTER TABLE memories ADD COLUMN isNarrativeOnly INTEGER NOT NULL DEFAULT 0")
    }
}
