package com.toadfiles.app.ui.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import com.toadfiles.app.domain.MotionStyle

/**
 * The motion vocabulary for one [MotionStyle]. Screens and shared components read
 * this from [LocalToadMotion] instead of hard-coding tweens, so the user's choice
 * restyles the whole app at once.
 *
 * All float specs are for 0..1 progress animations (alpha, scale); the slide specs
 * animate IntOffset lambdas and therefore use Int-based specs.
 */
@Immutable
data class ToadMotion(
    val style: MotionStyle,

    /** Standard content animation (chips, rows, small state changes). */
    val standard: FiniteAnimationSpec<Float>,
    /** Emphasized entrances (FAB, sheets, splash mascot). */
    val emphasized: FiniteAnimationSpec<Float>,
    /** Container transforms — larger surfaces settling into place. */
    val spatial: FiniteAnimationSpec<Float>,

    /** Nav-host transitions for forward navigation. */
    val screenEnter: EnterTransition,
    val screenExit: ExitTransition,
    /** Nav-host transitions when popping back. */
    val popEnter: EnterTransition,
    val popExit: ExitTransition,

    /** The transfer bar / armed bars sliding up from the bottom edge. */
    val barEnter: EnterTransition,
    val barExit: ExitTransition,

    /** How long the splash mascot stays on screen before handing over, ms. */
    val splashHoldMs: Int,
) {
    companion object {
        fun forStyle(style: MotionStyle): ToadMotion = when (style) {
            MotionStyle.SPRINGY -> ToadMotion(
                style = style,
                standard = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium,
                ),
                emphasized = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
                spatial = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
                screenEnter = fadeIn(spring(stiffness = Spring.StiffnessMediumLow)) +
                    slideInHorizontally(
                        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
                    ) { it / 16 },
                screenExit = fadeOut(tween(150)),
                popEnter = fadeIn(spring(stiffness = Spring.StiffnessMediumLow)),
                popExit = fadeOut(tween(150)) +
                    slideOutHorizontally(tween(200)) { it / 16 },
                barEnter = slideInVertically(
                    spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
                ) { it } + fadeIn(tween(90)),
                barExit = slideOutVertically(tween(180)) { it } + fadeOut(tween(140)),
                splashHoldMs = 950,
            )

            MotionStyle.CALM -> ToadMotion(
                style = style,
                standard = tween(180, easing = FastOutSlowInEasing),
                emphasized = tween(220, easing = LinearOutSlowInEasing),
                spatial = tween(250, easing = FastOutSlowInEasing),
                screenEnter = fadeIn(tween(200)) + slideInVertically(tween(200)) { it / 24 },
                screenExit = fadeOut(tween(150)),
                popEnter = fadeIn(tween(200)),
                popExit = fadeOut(tween(150)) + slideOutVertically(tween(150)) { it / 24 },
                barEnter = slideInVertically(tween(220)) { it } + fadeIn(tween(120)),
                barExit = slideOutVertically(tween(180)) { it } + fadeOut(tween(140)),
                splashHoldMs = 700,
            )

            MotionStyle.CINEMATIC -> ToadMotion(
                style = style,
                standard = tween(280, easing = FastOutSlowInEasing),
                emphasized = tween(420, easing = BezierEmphasized),
                spatial = tween(450, easing = BezierEmphasized),
                screenEnter = fadeIn(tween(320)) +
                    scaleIn(tween(320, easing = BezierEmphasized), initialScale = 0.92f),
                screenExit = fadeOut(tween(220)) +
                    scaleOut(tween(220), targetScale = 0.96f),
                popEnter = fadeIn(tween(320)) +
                    scaleIn(tween(320, easing = BezierEmphasized), initialScale = 0.96f),
                popExit = fadeOut(tween(260)) +
                    scaleOut(tween(260, easing = BezierEmphasized), targetScale = 0.92f),
                barEnter = slideInVertically(tween(380, easing = BezierEmphasized)) { it } +
                    fadeIn(tween(180)),
                barExit = slideOutVertically(tween(260)) { it } + fadeOut(tween(200)),
                splashHoldMs = 1250,
            )
        }
    }
}

/** M3 "emphasized" curve — the expressive easing behind the cinematic style. */
private val BezierEmphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/** Composition-local carrying the user's motion choice to every composable. */
val LocalToadMotion = staticCompositionLocalOf { ToadMotion.forStyle(MotionStyle.DEFAULT) }
