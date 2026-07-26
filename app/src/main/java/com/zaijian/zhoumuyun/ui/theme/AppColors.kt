package com.zaijian.zhoumuyun.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

object Palette {
    val Ink900   = Color(0xFF2C2118)
    val Ink600   = Color(0xFF7A6A56)
    val Ink300   = Color(0xFFBEAE98)
    val White    = Color(0xFFFFFFFF)
    val Black    = Color(0xFF000000)

    // 窗口16审计【问题B3/补充发现1】：RoundtableScreen.kt 与 ChatScreen.kt 的
    // 背景图半透明遮罩数值、逻辑完全相同但各自硬编码，收拢为共享 token，
    // 纯换值不改变视觉效果。
    val ScreenScrimDark  = Black.copy(alpha = 0.45f)
    val ScreenScrimLight = White.copy(alpha = 0.30f)

    // 圆桌用户气泡暗色模式背景（W12问题5修复：原硬编码在 RoundtableBubble.kt 的
    // UserBubble 中，与亮色模式下复用的 Ink900 配对，收拢为独立 token）
    val UserBubbleDark = Color(0xFF3A2E20)

    val Cream      = Color(0xFFF5F0E8)
    val Parchment  = Color(0xFFFBF7F0)
    val Border     = Color(0xFFE0D4C0)
    val AccentSoft = Color(0xFFF0E8D8)

    // 批次7 7-3修复：parchmentSurface 装饰色 token（原硬编码在 ZaijianModifiers.kt）
    val ParchmentGlow     = Color(0xFFFEFCF8)  // 亮色模式中心高光（与 LightColors.bgElevated 同值）
    val ParchmentCornerDark = Color(0xFF0A0806)  // 暗色模式角落暗影
    val ParchmentCornerLight = Color(0xFFD8C9B0) // 亮色模式角落暗影

    val Night         = Color(0xFF12100A)
    val NightCard     = Color(0xFF1E1A12)
    val NightElevated = Color(0xFF2A2418)
    val NightBorder   = Color(0xFF3A3020)
    val NightText     = Color(0xFFEDE8DE)
    val NightTextSub  = Color(0xFF8A7E68)

    val Gold     = Color(0xFFC4A46A)
    val GoldSoft = Color(0xFFF0E8D0)

    // 文件类型图标重设计（icon_redesign_renders 新配色方案）：此前 PDF/文档/
    // 压缩包全部落在 Ink600 同一色上，观感单一。现按类型分派独立暖色系色相，
    // 降过饱和度，彼此有区分度又不出戏（非 Material 默认红蓝绿黄撞色）。
    // 链接类型沿用既有 Gold，不新增色号。
    val FileTypePdf   = Color(0xFFB5654A)  // PDF · 赭红
    val FileTypeDoc   = Color(0xFF6B7A99)  // 文档/MD · 靛蓝灰
    val FileTypeTable = Color(0xFF5C8A82)  // 表格 · 青竹
    val FileTypeZip   = Color(0xFF8C7A5C)  // 压缩包 · 橄榄棕
    val FileTypeImage = Color(0xFFC98B4A)  // 图片 · 琥珀

    val Online  = Color(0xFF6BCB8B)
    val Idle    = Color(0xFFF6C858)
    val Focused = Color(0xFF8FA8C9)
    val Offline = Color(0xFFBCC3CE)
    // W12问题3修复：BreathingAvatar 离线遮罩原先硬编码 Color(0x80808080)（50% 灰），
    // 未接主题系统。此处复用 Offline 灰蓝色配 50% alpha，保持视觉基本不变，
    // 同时收拢进 Palette，未来主题调整可统一改这里。
    val OfflineOverlay = Offline.copy(alpha = 0.5f)

    val TaskActive = Color(0xFF5B9CF6)
    val TaskPaused = Color(0xFFF6C858)
    val TaskDone   = Color(0xFF6BCB8B)
    val TaskFailed = Color(0xFFF47067)

    // ── 角色详情页"楼层"渐变背景（W12问题5修复：原硬编码在
    //    CharacterDetailHeader.kt 的 floorGradientColors() 局部函数中，
    //    收拢为具名 token，light/dark 各三档，纯换值不改视觉）──
    val FloorSecondDarkStart  = Color(0xFF3A3424)   // 二楼暗色模式渐变起点（暖白，三档最亮）
    val FloorSecondDarkEnd    = Color(0xFF2A2418)
    val FloorSecondLightStart = Color(0xFFFFFBF3)
    val FloorSecondLightEnd   = Color(0xFFFBF7F0)
    val FloorFirstDarkStart   = Color(0xFF2E2818)   // 一楼暗色模式渐变起点（暖黄，三档中等）
    val FloorFirstDarkEnd     = Color(0xFF221E14)
    val FloorFirstLightStart  = Color(0xFFFAF2E2)
    val FloorFirstLightEnd    = Color(0xFFF5F0E8)
    val FloorBasementDarkStart  = Color(0xFF1A1C28)   // 地下室暗色模式渐变起点（冷蓝紫，三档最低）
    val FloorBasementDarkEnd    = Color(0xFF14141C)
    val FloorBasementLightStart = Color(0xFFE8E6F2)
    val FloorBasementLightEnd   = Color(0xFFE0DCEC)

    // ── M3 bridge 补丁（修复 AppTheme.kt 引用未定义色值）────────
    val Slate  = Color(0xFF8A9496)   // 中性蓝灰，M3 primary
    val Snow   = Color(0xFFF5F0E8)   // 同 Cream，M3 background（浅色）
    val Paper  = Color(0xFFFBF7F0)   // 同 Parchment，M3 surface（浅色）

    // ── UI M6/M7：语义色——统一替换散落在各 Screen 的硬编码颜色 ──
    // 成功/正向（绿）：连接成功、任务完成、在线状态
    val SemanticSuccess = Color(0xFF4CAF50)
    // 错误/负向（红）：连接失败、删除确认、错误状态
    val SemanticError   = Color(0xFFF44336)
    // 警告/橙：暂停状态、待处理
    val SemanticWarning = Color(0xFFFF9800)
    // 危险操作高亮（偏暖红，用于\"删除\"等破坏性操作文字）
    val SemanticDanger  = Color(0xFFE57373)
    // 中性灰：归档、离线、未知状态
    val SemanticNeutral = Color(0xFF9E9E9E)
    // 信息蓝：进行中项目、工作蓝
    val SemanticInfo    = Color(0xFF2196F3)
    // 情感类目（粉）：CharacterDetailScreen 记忆维度 Tab/Chip 的"情感"指示色，
    // 与工作维度的 SemanticInfo/Palette.Focused 是同一组对照，纯语义色，不绑定任何角色。
    // 历史注：曾与旧版 Palette.MingMei（角色专属色占位值，已废弃删除）同值，
    // 现两者已无关联——明媚 accentColor 已在 CharacterConfig.kt 改为 #C23A54（精修方案 v1.3）。
    val SemanticEmotion = Color(0xFFC89AA3)

    // ── 经期卡片拍板新增：排卵期/安全期颜色统一为 token，不再写死在 BookCard 单处 ──
    // 提醒/留意（黄）：排卵期等需要关注但非紧急的状态，未来任何「黄色提醒」场景复用
    val SemanticReminder = Color(0xFFFFD54F)
    // 安全/平稳（绿）：安全期等无需特别注意的平稳状态，与 SemanticSuccess（任务完成/连接成功）
    // 语义不同，不复用——未来任何「绿色=平稳无需关注」场景复用这个
    val SemanticSafe     = Color(0xFF81C784)

    // ── 卡片文字色拍板新增：WindowCard 状态文字 / CardSharedComponents 角色名文字 ──
    // 两者都只在亮色模式下使用（暗色模式走 Color.White 系），原先各写各的深棕色，
    // 数值不同（不是同一种棕，没有合并成一个token），分别建token只是不再裸写在
    // 各自文件里，方便统一查找/调整。
    val WindowCardStatusTextLight = Color(0xFF5C4033)
    val CardNameTextLight         = Color(0xFF3A2A1A)

    // ── UI M10/M11：新增语义色，替换散落的硬编码色值 ──────────
    // Timeline 事件卡：关系变化（暖橙）、记忆创建（薰衣草紫）
    val TimelineRelationship = Color(0xFFE8A87C)
    val TimelineMemory       = Color(0xFFB8A9C9)
    // Splash 启动页亮色模式背景
    val SplashLightBg        = Color(0xFFEEE8DC)
    // World/公馆场景氛围叠层（夜晚加深 / 白天提亮）
    val MansionNightOverlay  = Color(0x44000000)
    val MansionDayOverlay    = Color(0x0AFFFFFF)

    // ── 精修方案 v1.3 第3.2节：仪式性强调色 ──────────────────
    // 关系阶段「重要/核心」、记忆置顶等稀缺时刻专用，平时不出现，用满则对比强烈。
    /** 深色模式蜡封点 */
    val Velvet     = Color(0xFF7A2331)
    /** 浅色模式蜡封点（同色相，提亮以免在米色纸面上过重，不要在浅色模式下误用 Velvet） */
    val VelvetSoft = Color(0xFFA8475A)

    // ── P3-55 修复：成长指标绿色，统一主题常量，替代散落各处的 Color(0xFF7BAE7F) ──
    val GrowthGreen = Color(0xFF7BAE7F)

    // ── P2-41 修复：MoodType 蜡烛色 token（原硬编码在 WorldOSComponents.candleColor）──
    val MoodExcited    = Color(0xFFFF8A65)    // 暖橙，兴奋
    val MoodCurious    = Color(0xFFBA68C8)    // 紫，好奇
    val MoodReflective = Color(0xFF9FA8DA)    // 冷紫蓝，沉思
    val MoodTired      = Color(0xFF90A4AE)    // 灰，疲惫

    // ── P2-44 修复：暗色模式 borderSubtle token（原硬编码在 DarkColors）──
    val BorderSubtleDark = Color(0x15FFFFFF)
}

@Immutable
data class AppColors(
    val bgBase: Color,
    val bgCard: Color,
    val bgElevated: Color,
    val border: Color,
    val borderSubtle: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textDisabled: Color,
    val accent: Color,
    val accentSoft: Color,
    val statusActive: Color,
    val statusIdle: Color,
    val statusFocused: Color,
    val statusOffline: Color,
    val taskActive: Color,
    val taskPaused: Color,
    val taskDone: Color,
    val taskFailed: Color,
    val isDark: Boolean,
) {
    // ── M3 兼容属性映射 ────────────────────────────
    val background:   Color get() = bgBase
    val onBackground: Color get() = textPrimary
    val primary:      Color get() = accent
    val card:         Color get() = bgCard
    val surface:      Color get() = bgCard
    val outline:      Color get() = border
    val secondary:    Color get() = textSecondary
}

val LightColors = AppColors(
    bgBase        = Palette.Cream,
    bgCard        = Palette.Parchment,
    bgElevated    = Palette.ParchmentGlow,
    border        = Palette.Border,
    borderSubtle  = Palette.Border.copy(alpha = 0.6f),
    textPrimary   = Palette.Ink900,
    textSecondary = Palette.Ink600,
    textDisabled  = Palette.Ink300,
    accent        = Palette.Gold,
    accentSoft    = Palette.AccentSoft,
    statusActive  = Palette.Online,
    statusIdle    = Palette.Idle,
    statusFocused = Palette.Focused,
    statusOffline = Palette.Offline,
    taskActive    = Palette.TaskActive,
    taskPaused    = Palette.TaskPaused,
    taskDone      = Palette.TaskDone,
    taskFailed    = Palette.TaskFailed,
    isDark        = false,
)

val DarkColors = AppColors(
    bgBase        = Palette.Night,
    bgCard        = Palette.NightCard,
    bgElevated    = Palette.NightElevated,
    border        = Palette.NightBorder,
    borderSubtle  = Palette.BorderSubtleDark,
    textPrimary   = Palette.NightText,
    textSecondary = Palette.NightTextSub,
    textDisabled  = Palette.Ink600,
    accent        = Palette.Gold,
    accentSoft    = Palette.Gold.copy(alpha = 0.15f),
    statusActive  = Palette.Online,
    statusIdle    = Palette.Idle,
    statusFocused = Palette.Focused,
    statusOffline = Palette.Offline,
    taskActive    = Palette.TaskActive,
    taskPaused    = Palette.TaskPaused,
    taskDone      = Palette.TaskDone,
    taskFailed    = Palette.TaskFailed,
    isDark        = true,
)

val LocalAppColors = staticCompositionLocalOf { LightColors }
