package com.zaijian.zhoumuyun.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontLoadingStrategy
import androidx.compose.ui.text.font.FontWeight
import com.zaijian.zhoumuyun.R

// ─────────────────────────────────────────────────────────────
//  Font.kt — 字体三层分工（精修方案 v1.3 第4节）的字体资源定义
//
//  对应设计文档表格：
//    显示/标题 → 思源宋体 Noto Serif SC → titleBold / cardTitle / navTitle
//    正文      → 思源黑体 Noto Sans SC → body / caption / label / presence /
//                button / bodyBold（见 AppTypography.kt；ttf 文件待补，见下方卡点）
//    数据      → 等宽字体（JetBrains Mono）→ labelMono 的纯数字/拉丁场景
//
//  【当前状态 —— 2026-08-03 补字重（已补完）】
//  SerifSC / MonoFamily：字体文件已就位，指向真实 R.font 引用（2026-07-01 接入）。
//  SansSC（正文黑体 Regular/Medium）：★已补完★
//  noto_sans_sc_regular.ttf / noto_sans_sc_medium.ttf 已从 Google Fonts 下载
//  并放入 res/font/，SansSC 声明已启用真实 R.font 引用（2026-08-03 接入）。
//
//  约束（精修方案 v1.3 第147-149行明确写过，必须遵守，不要在以后维护时违反）：
//  等宽字体不含 CJK 字形，只能用于纯数字/英文/符号场景（"23:41"、"NO.0049"），
//  绝不能套在中文状态文案上。presence（状态文案，≤10字中文）必须保持黑体，
//  不接入 MonoFamily——但 SansSC 是黑体本身，presence 接入 SansSC 不违反这条约束，
//  违反的是"接入等宽 MonoFamily"，二者不要混淆。
// ─────────────────────────────────────────────────────────────

/**
 * 思源宋体（Noto Serif SC）。
 * 字体文件已就位：res/font/noto_serif_sc_bold.ttf（2026-07-01 接入）。
 * cardTitle 设计字重是 Medium 500，但只内嵌 Bold 一个字重文件（控制 APK 体积），
 * Medium 由系统对同一份 Bold 字形文件做字重模拟（见下方 FontWeight.Medium 条目），
 * 实际效果是从 Bold 往细处模拟，不是字体设计师手绘的真正 Medium 字形——
 * 如果以后觉得不够精细，再补 res/font/noto_serif_sc_medium.ttf 并加第三个 Font() 条目。
 */
val SerifSC: FontFamily = FontFamily(
    // W12问题4修复：显式指定 FontLoadingStrategy.OptionalLocal。默认策略 Blocking
    // 在字体资源缺失（构建配置错误/ProGuard 误删/APK 分包异常）时会抛
    // IllegalStateException，导致 titleBold/cardTitle/navTitle 全部崩溃。
    // Optional 策略下加载失败会静默回退到系统默认字体，不崩溃。
    Font(R.font.noto_serif_sc_bold, weight = FontWeight.Bold, loadingStrategy = FontLoadingStrategy.OptionalLocal),
    Font(R.font.noto_serif_sc_bold, weight = FontWeight.Medium, loadingStrategy = FontLoadingStrategy.OptionalLocal),
)

/**
 * 等宽字体（JetBrains Mono），仅限纯数字/拉丁字符场景使用，绝不套在中文状态文案上
 * （presence 等中文文案走 SansSC 黑体，见 AppTypography.kt，不要"顺手"接上等宽）。
 * 字体文件已就位：res/font/mono_regular.ttf（2026-07-01 接入）。
 */
val MonoFamily: FontFamily = FontFamily(
    // W12问题4修复：同 SerifSC，加 Optional 降级策略。
    Font(R.font.mono_regular, weight = FontWeight.Normal, loadingStrategy = FontLoadingStrategy.OptionalLocal),
)

/**
 * 思源黑体（Noto Sans SC），正文/按钮/状态文案专用字重。
 * 内嵌 Regular + Medium 两档，不含 Bold——Bold 场景用 SerifSC 衬线体做视觉区分。
 * 字体文件已就位：res/font/noto_sans_sc_regular.ttf / noto_sans_sc_medium.ttf
 *（2026-08-03 从 Google Fonts 下载，SIL OFL 1.1 许可证）。
 */
val SansSC: FontFamily = FontFamily(
    Font(R.font.noto_sans_sc_regular, weight = FontWeight.Normal, loadingStrategy = FontLoadingStrategy.OptionalLocal),
    Font(R.font.noto_sans_sc_medium, weight = FontWeight.Medium, loadingStrategy = FontLoadingStrategy.OptionalLocal),
)
