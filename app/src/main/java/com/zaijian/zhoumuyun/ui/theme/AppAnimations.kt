package com.zaijian.zhoumuyun.ui.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

// ─────────────────────────────────────────────────────────────
//  Spring curves
// ─────────────────────────────────────────────────────────────

/**
 * Default app-wide spring.
 * Use for: Tab switches, card expansions, state transitions.
 */
val appSpring = spring<Float>(
    dampingRatio = 0.85f,
    stiffness    = Spring.StiffnessLow,
)

/**
 * Bounce spring.
 * Use for: book-open animation, mansion door, any "arrival" motion.
 */
val bounceSpring = spring<Float>(
    dampingRatio = 0.70f,
    stiffness    = Spring.StiffnessMedium,
)

/**
 * Snap spring — used for press-feedback (scale down on tap).
 */
val snapSpring = spring<Float>(
    dampingRatio = 0.85f,
    stiffness    = Spring.StiffnessMedium,
)

// ─────────────────────────────────────────────────────────────
//  Tween presets
// ─────────────────────────────────────────────────────────────

/** 80ms — button press-down */
val instantTween  = tween<Float>(AnimDuration.instant)

/** 150ms — tab or status change */
val fastTween     = tween<Float>(AnimDuration.fast)

/** 220ms — BottomSheet enter */
val sheetTween    = tween<Float>(AnimDuration.bottomSheet)

/** 250ms — page transition */
val pageTween     = tween<Float>(AnimDuration.pageSwitch)

/** 300ms + bounceSpring feel — book open (use as tween on alpha, spring on scale) */
val bookOpenTween = tween<Float>(AnimDuration.bookOpen, easing = FastOutSlowInEasing)

// ─────────────────────────────────────────────────────────────
//  Breathing animation spec (avatar glow)
//  scale: 1.00 → 1.04 → 1.00, full cycle = 4000ms
// ─────────────────────────────────────────────────────────────

val breathScaleSpec = infiniteRepeatable<Float>(
    animation  = tween(AnimDuration.breath, easing = FastOutSlowInEasing),
    repeatMode = RepeatMode.Reverse,
)

val breathAlphaSpec = infiniteRepeatable<Float>(
    animation  = tween(AnimDuration.breath, easing = FastOutSlowInEasing),
    repeatMode = RepeatMode.Reverse,
)

// Breath range constants
const val BreathScaleMin = 1.00f
const val BreathScaleMax = 1.04f
const val BreathGlowAlphaMin = 0.25f
const val BreathGlowAlphaMax = 0.45f
