package com.toadfiles.app.ui.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Random

/**
 * The Toadmark ink border — a hand-drawn, slightly wobbly rectangle stroke that
 * gives Toadmark surfaces their sketchbook signature (the toad family's
 * handwritten look, translated to Compose as a pure draw-phase effect).
 *
 * The wobble is **deterministic**: every offset comes from a seeded
 * [java.util.Random] (its LCG algorithm is specified by the JavaDocs, so the
 * same seed draws the identical border on every device and every run — no
 * shimmer across recompositions or redraws, and screenshots stay stable).
 *
 * Cost profile: one [Path] built inside [drawWithCache] (rebuilt only when the
 * size or parameters change) and one stroke per frame — cheaper than a shadow
 * or a gradient, and it never animates by itself. [LocalToadmarkInk] callers
 * skip it entirely under Performance mode, keeping the "strip decoration when
 * the user asks for speed" contract.
 */

/** How many sample points anchor one edge of the hand-drawn rectangle:
 *  the two corners plus this many in-between points. */
private const val EDGE_SEGMENTS = 2

/** Anchors around the rectangle: 4 edges × (corners + midpoints). */
private const val ANCHORS = (1 + EDGE_SEGMENTS) * 4

/**
 * The per-anchor jitter for one border, as flat (dx, dy) pairs.
 *
 * @param seed      changes the drawing; identical seeds always produce the
 *                  identical sequence (stable across process restarts).
 * @param radiusPx  maximum absolute jitter on each axis, in pixels.
 */
internal fun inkJitter(seed: Int, anchors: Int, radiusPx: Float): FloatArray {
    val random = Random(seed.toLong())
    val out = FloatArray(anchors * 2)
    for (i in out.indices) {
        // nextDouble() is [0,1); mapping to [-radius, +radius) keeps every
        // anchor inside a radius-wide band around its clean position.
        out[i] = ((random.nextDouble() * 2.0) - 1.0).toFloat() * radiusPx
    }
    return out
}

/**
 * The clean (unjittered) anchor rectangle for a `width × height` surface inset
 * by [insetPx]: clockwise from the top-left corner, `1 + [EDGE_SEGMENTS]`
 * anchors per edge (corners plus in-between points).
 */
internal fun inkAnchorPoints(width: Float, height: Float, insetPx: Float): List<Offset> {
    val left = insetPx
    val top = insetPx
    val right = width - insetPx
    val bottom = height - insetPx
    val perEdge = 1 + EDGE_SEGMENTS
    val out = ArrayList<Offset>(ANCHORS)
    for (e in 0 until perEdge) {
        val t = e.toFloat() / EDGE_SEGMENTS
        out += Offset(left + (right - left) * t, top)
    }
    for (e in 0 until perEdge) {
        val t = e.toFloat() / EDGE_SEGMENTS
        out += Offset(right, top + (bottom - top) * t)
    }
    for (e in 0 until perEdge) {
        val t = e.toFloat() / EDGE_SEGMENTS
        out += Offset(right - (right - left) * t, bottom)
    }
    for (e in 0 until perEdge) {
        val t = e.toFloat() / EDGE_SEGMENTS
        out += Offset(left, bottom - (bottom - top) * t)
    }
    return out
}

/**
 * Builds the hand-drawn border path for a `width × height` rectangle inset by
 * [insetPx]. Anchors sit on the rectangle's perimeter, each nudged by its
 * (dx, dy) jitter; the path routes through the **midpoints between anchors**
 * with quadratic curves using the anchors as control points, so the line bends
 * like one pen stroke instead of zigzagging through the jitter.
 */
internal fun inkBorderPath(
    width: Float,
    height: Float,
    insetPx: Float,
    jitter: FloatArray,
): Path {
    val anchors = inkAnchorPoints(width, height, insetPx)

    // Each wobbled anchor, then the pen-stroke midpoints between neighbours.
    fun jitterOffset(i: Int): Offset =
        if (i * 2 + 1 < jitter.size) Offset(jitter[i * 2], jitter[i * 2 + 1]) else Offset.Zero

    val wobbled = Array(ANCHORS) { i ->
        val base = anchors[i]
        val j = jitterOffset(i)
        Offset(base.x + j.x, base.y + j.y)
    }
    val mids = Array(ANCHORS) { i ->
        val a = wobbled[i]
        val b = wobbled[(i + 1) % ANCHORS]
        Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f)
    }

    // One continuous stroke: start at a midpoint, curve through every anchor
    // (as the control point) into the next midpoint, and close.
    val path = Path()
    path.moveTo(mids[0].x, mids[0].y)
    for (i in 0 until ANCHORS) {
        val control = wobbled[i]
        val next = mids[(i + 1) % ANCHORS]
        path.quadraticBezierTo(control.x, control.y, next.x, next.y)
    }
    path.close()
    return path
}

/**
 * Draws the Toadmark ink border inside this element: a hand-drawn, seeded
 * wobbly rectangle stroke. [seedSalt] distinguishes same-sized surfaces so
 * neighbouring cards don't share an identical scribble.
 */
fun Modifier.toadInkBorder(
    color: Color,
    strokeWidth: Dp = 2.dp,
    wobble: Dp = 2.dp,
    seedSalt: Int = 0,
): Modifier = drawWithCache {
    val strokePx = strokeWidth.toPx()
    val wobblePx = wobble.toPx()
    val inset = strokePx / 2f + wobblePx
    val usableW = size.width - inset * 2f
    val usableH = size.height - inset * 2f
    // Degenerate (too small to draw honestly): skip drawing entirely.
    if (usableW <= 0f || usableH <= 0f) {
        onDrawWithContent { drawContent() }
    } else {
        // Seed from the size as well: resizing re-scribbles consistently.
        val seed = seedSalt * 1_000_003 + size.width.toInt() * 31 + size.height.toInt()
        val jitter = inkJitter(seed, ANCHORS, wobblePx)
        val path = inkBorderPath(size.width, size.height, inset, jitter)
        onDrawWithContent {
            drawContent()
            drawPath(path, color, style = Stroke(width = strokePx))
        }
    }
}
