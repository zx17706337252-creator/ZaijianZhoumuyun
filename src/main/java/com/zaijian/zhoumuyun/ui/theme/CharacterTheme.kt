package com.zaijian.zhoumuyun.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

// ─────────────────────────────────────────────────────────────
//  Per-character accent color injection
//  Wrap any subtree that should "inherit" a character's color.
//
//  Usage:
//    CharacterTheme(character) {
//        val accent = LocalCharacterAccent.current
//        ...
//    }
// ─────────────────────────────────────────────────────────────

/** Falls back to the global accent if not inside a CharacterTheme. */
val LocalCharacterAccent = compositionLocalOf { Palette.Slate }

/** Derived: 15% alpha — used for background fills */
val LocalCharacterAccentSoft
    @Composable @ReadOnlyComposable
    get() = LocalCharacterAccent.current.copy(alpha = 0.15f)

/** Derived: 35% alpha — used for the avatar glow ring */
val LocalCharacterAccentGlow
    @Composable @ReadOnlyComposable
    get() = LocalCharacterAccent.current.copy(alpha = 0.35f)

/** Derived: 60% alpha — used for the status arc border */
val LocalCharacterAccentBorder
    @Composable @ReadOnlyComposable
    get() = LocalCharacterAccent.current.copy(alpha = 0.60f)

@Composable
fun CharacterTheme(
    accentColor: Color,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalCharacterAccent provides accentColor,
        content = content,
    )
}
