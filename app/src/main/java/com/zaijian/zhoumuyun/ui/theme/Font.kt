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
//    正文      → 思源黑体 Noto Sans SC（即系统默认，不内嵌，见 AppTypography.kt）
//    数据      → 等宽字体（JetBrains Mono）→ label 的纯数字/拉丁场景
//
//  【当前状态】字体文件已放入 res/font/，SerifSC / MonoFamily 已指向真实 R.font 引用
//  （2026-07-01 接入，来源 Google Fonts，均为 SIL OFL 1.1 开源协议，可内嵌分发）：
//            res/font/noto_serif_sc_bold.ttf   ← 思源宋体 Bold
//            res/font/mono_regular.ttf         ← JetBrains Mono Regular
//
//  约束（精修方案 v1.3 第147-149行明确写过，必须遵守，不要在以后维护时违反）：
//  等宽字体不含 CJK 字形，只能用于纯数字/英文/符号场景（"23:41"、"NO.0049"），
//  绝不能套在中文状态文案上。presence（状态文案，≤10字中文）必须保持黑体，
//  不接入 MonoFamily——AppTypography.kt 里 presence 因此维持系统默认，不要"顺手"给它也接上等宽。
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
 * （presence 等中文文案维持系统默认黑体，见 AppTypography.kt，不要"顺手"接上等宽）。
 * 字体文件已就位：res/font/mono_regular.ttf（2026-07-01 接入）。
 */
val MonoFamily: FontFamily = FontFamily(
    // W12问题4修复：同 SerifSC，加 Optional 降级策略。
    Font(R.font.mono_regular, weight = FontWeight.Normal, loadingStrategy = FontLoadingStrategy.OptionalLocal),
)
