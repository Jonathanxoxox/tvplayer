package tv.own.owntv.features.shell.components

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Call just before a screen gives way to another one inside the content area (Settings ⇄ More, a Settings
 * page ⇄ one of its sub-pages). The screen leaving takes its focused row with it, and with nobody focused
 * Compose hands focus to the first thing on screen, the menu: its item lit up for a frame before the next
 * screen took the focus, a flicker at the left edge on every way back out of Settings (Jonathan, 30 Oct 2026).
 * The shell parks the focus out of sight instead; the next screen takes it from there as before, and if it
 * does not within a moment, the content area does.
 */
val LocalParkFocus = staticCompositionLocalOf<() -> Unit> { {} }
