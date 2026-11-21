package com.toadfiles.app.ui.theme

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Toadmark ink border's contracts:
 *
 *  1. **Determinism** — the same seed always produces the identical jitter, so
 *     the border never shimmers between frames and screenshots stay stable.
 *  2. **Bounds** — every wobbled anchor stays inside the wobble band around the
 *     clean rectangle, so the stroke can never leave the surface.
 *  3. **Anchor geometry** — the clean rectangle walks the perimeter clockwise
 *     from the top-left corner: top edge left→right, right edge top→bottom,
 *     bottom edge right→left, left edge bottom→top, corners shared by both
 *     neighbouring edges.
 *  4. **Path smoke** — building the stroke path succeeds for every shape from
 *     degenerate slivers to wide cards (Robolectric provides the platform Path).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ToadInkTest {

    private val anchors = 12 // 4 edges × (corner + 2 in-between points)

    @Test
    fun `identical seeds draw identical borders`() {
        val first = inkJitter(seed = 42, anchors = anchors, radiusPx = 3f)
        val second = inkJitter(seed = 42, anchors = anchors, radiusPx = 3f)
        assertTrue("same seed must reproduce the same jitter", first.contentEquals(second))
    }

    @Test
    fun `different seeds draw different borders`() {
        val first = inkJitter(seed = 42, anchors = anchors, radiusPx = 3f)
        val second = inkJitter(seed = 43, anchors = anchors, radiusPx = 3f)
        assertTrue("neighbouring seeds should scribble differently", !first.contentEquals(second))
    }

    @Test
    fun `jitter stays inside the wobble band`() {
        val radius = 2.5f
        val jitter = inkJitter(seed = 7, anchors = anchors, radiusPx = radius)
        assertEquals(anchors * 2, jitter.size)
        for (value in jitter) {
            assertTrue("jitter $value escaped ±$radius", value >= -radius && value <= radius)
        }
    }

    @Test
    fun `zero wobble is a perfectly clean rectangle`() {
        val jitter = inkJitter(seed = 1, anchors = anchors, radiusPx = 0f)
        assertTrue(jitter.all { it == 0f })
    }

    @Test
    fun `anchors walk the perimeter clockwise from the top-left corner`() {
        // width=100, height=60, inset=5: the clean rect spans (5,5)-(95,55).
        val points = inkAnchorPoints(width = 100f, height = 60f, insetPx = 5f)
        assertEquals(anchors, points.size)
        // Top edge: y pinned at 5, x rising 5 → 95 through the midpoints.
        assertEquals(Offset(5f, 5f), points[0])
        assertEquals(Offset(50f, 5f), points[1])
        assertEquals(Offset(95f, 5f), points[2])
        // Right edge: x pinned at 95, y rising 5 → 55.
        assertEquals(Offset(95f, 5f), points[3])
        assertEquals(Offset(95f, 55f), points[5])
        // Bottom edge: y pinned at 55, x falling 95 → 5.
        assertEquals(Offset(95f, 55f), points[6])
        assertEquals(Offset(5f, 55f), points[8])
        // Left edge: x pinned at 5, y falling 55 → 5, closing the loop.
        assertEquals(Offset(5f, 55f), points[9])
        assertEquals(Offset(5f, 5f), points[11])
    }

    @Test
    fun `wobbled anchors stay inside the surface`() {
        val w = 200f
        val h = 120f
        val inset = 6f
        val radius = 4f
        val clean = inkAnchorPoints(w, h, inset)
        val jitter = inkJitter(seed = 99, anchors = anchors, radiusPx = radius)
        for (i in clean.indices) {
            val x = clean[i].x + jitter[i * 2]
            val y = clean[i].y + jitter[i * 2 + 1]
            assertTrue("anchor $i left the surface: $x,$y", x >= 0f && x <= w && y >= 0f && y <= h)
        }
    }

    @Test
    fun `the stroke path builds for every shape from slivers to wide cards`() {
        val shapes = listOf(
            10f to 10f,
            1f to 400f,
            400f to 1f,
            1081.5f to 220f, // the Home hero card at 420dpi
            1080f to 1920f,
        )
        for ((w, h) in shapes) {
            val jitter = inkJitter(seed = 5, anchors = anchors, radiusPx = 2f)
            val path = inkBorderPath(w, h, 3f, jitter)
            assertTrue("path for ${w}x$h must have content", !path.isEmpty)
        }
    }

    @Test
    fun `a short jitter array degrades to a clean border instead of crashing`() {
        val path = inkBorderPath(100f, 50f, 3f, FloatArray(2))
        assertTrue(!path.isEmpty)
    }
}
