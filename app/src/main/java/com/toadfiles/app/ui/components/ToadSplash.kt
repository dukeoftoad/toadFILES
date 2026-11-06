package com.toadfiles.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.toadfiles.app.domain.MotionStyle
import com.toadfiles.app.ui.theme.LocalToadMotion
import com.toadfiles.app.ui.theme.ToadFace
import kotlinx.coroutines.delay

/**
 * Launch splash: the toad hops in with the theme's emphasized spring, blinks
 * twice, then the veil fades and hands over to the app. Honors the user's motion
 * style via [LocalToadMotion] (hold duration + spring physics).
 *
 * Rendered as an overlay on top of the app content by [MainActivity]; dismissed
 * by calling [onFinished] once the fade completes. [MainActivity] holds the
 * flag in a `rememberSaveable`, so a configuration change mid-splash finishes
 * the same animation instead of replaying it.
 *
 * Audit A14 (v2.1.1): the timeline state used to be plain `remember`, which
 * contradicted that contract — on a configuration change mid-splash the hop
 * reset to 0.55 scale, the veil jumped back to fully opaque and the whole
 * sequence replayed from the top. The hop/veil flags and the veil/blink
 * progress now survive via [rememberSaveable] (the Animatables through a
 * value-preserving Saver), so a rotation continues the exact frame it left.
 */
private val FloatAnimatableSaver: Saver<Animatable<Float, AnimationVector1D>, Float> =
    Saver(save = { it.value }, restore = { Animatable(it) })

@Composable
fun ToadSplash(onFinished: () -> Unit) {
    val motion = LocalToadMotion.current
    // MotionStyle.NONE (and Performance mode, which forces it): no animations at all
    // means no splash — emit zero nodes and finish immediately instead of playing a
    // 1.4 s mascot timeline the user explicitly turned off.
    if (motion.style == MotionStyle.NONE) {
        LaunchedEffect(Unit) { onFinished() }
        return
    }
    var hopStarted by rememberSaveable { mutableStateOf(false) }
    var veilVisible by rememberSaveable { mutableStateOf(true) }
    val veil = rememberSaveable(saver = FloatAnimatableSaver) { Animatable(1f) }
    val blink = rememberSaveable(saver = FloatAnimatableSaver) { Animatable(0f) }

    LaunchedEffect(motion.style) {
        hopStarted = true
        delay(380)
        // Two blinks, each: close fast, open slower.
        repeat(2) {
            blink.animateTo(1f, tween(80))
            blink.animateTo(0f, tween(120))
            delay(160)
        }
        delay((motion.splashHoldMs - 900).coerceAtLeast(0).toLong())
        veilVisible = false
    }

    // Veil fade-out, then removal.
    LaunchedEffect(veilVisible) {
        if (!veilVisible) {
            veil.animateTo(0f, tween(320))
            onFinished()
        }
    }

    if (veilVisible || veil.value > 0f) {
        Box(
            Modifier
                .fillMaxSize()
                .alpha(veil.value)
                .testTag("toad_splash"),
            contentAlignment = Alignment.Center,
        ) {
            val hop = updateTransition(targetState = hopStarted, label = "toad_hop")
            val hopScale by hop.animateFloat(
                transitionSpec = { motion.emphasized },
                label = "toad_hop_scale",
            ) { started -> if (started) 1f else 0.55f }
            val hopAlpha by hop.animateFloat(
                transitionSpec = { tween(160) },
                label = "toad_hop_alpha",
            ) { started -> if (started) 1f else 0f }
            // A tiny rise-and-settle: the "hop" reads through the scale bounce.

            ToadFace(
                size = 112.dp,
                blink = blink.value,
                modifier = Modifier
                    .scale(hopScale)
                    .alpha(hopAlpha),
            )
        }
    }
}
