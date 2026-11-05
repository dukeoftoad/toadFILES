package com.toadfiles.app.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The toadFILES brand kit, hand-drawn as vectors so everything scales crisp and
 * tints with the active theme.
 *
 *  - [ToadMarkBase]          the mascot silhouette (body, eye bumps, belly) in one tint
 *  - [ToadFolder]            the custom folder with a lit front face
 *  - [ToadFile*]             the duotone file-type family: a faint page backing with a
 *                            solid type glyph, so one tint yields two tones
 *  - [ToadFace]              the mascot assembled — mark + pupils + smile, blinkable
 *  - [EmptyFolderArt] etc.   illustrated empty states built from the same pieces
 *
 * Everything here is theme-agnostic: callers tint with [MaterialTheme.colorScheme]
 * colors, so the brand recolors itself with the chosen app theme.
 */

// ---------------------------------------------------------------------------
// PathBuilder helpers — circles and ellipses from two arcs each.
// ---------------------------------------------------------------------------

private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) =
    ellipse(cx, cy, r, r)

private fun PathBuilder.ellipse(cx: Float, cy: Float, rx: Float, ry: Float) {
    moveTo(cx - rx, cy)
    arcToRelative(rx, ry, 0f, isMoreThanHalf = true, isPositiveArc = false, 2f * rx, 0f)
    arcToRelative(rx, ry, 0f, isMoreThanHalf = true, isPositiveArc = false, -2f * rx, 0f)
    close()
}

/** Rounded axis-aligned rectangle via four quarter-ellipses (clockwise). */
private fun PathBuilder.roundRect(
    left: Float, top: Float, right: Float, bottom: Float, r: Float,
) {
    moveTo(left + r, top)
    lineTo(right - r, top)
    arcToRelative(r, r, 0f, isMoreThanHalf = false, isPositiveArc = true, r, r)
    lineTo(right, bottom - r)
    arcToRelative(r, r, 0f, isMoreThanHalf = false, isPositiveArc = true, -r, r)
    lineTo(left + r, bottom)
    arcToRelative(r, r, 0f, isMoreThanHalf = false, isPositiveArc = true, -r, -r)
    lineTo(left, top + r)
    arcToRelative(r, r, 0f, isMoreThanHalf = false, isPositiveArc = true, r, -r)
    close()
}

// ---------------------------------------------------------------------------
// The mascot
// ---------------------------------------------------------------------------

/**
 * The toad silhouette. Eye-bump circles overlap the body mound so the union reads
 * as one animal; the belly is the same tint at lower alpha for the duotone feel.
 * Pupils and smile are drawn by [ToadFace] so they can blink and take a contrast
 * color.
 */
val ToadMarkBase: ImageVector = ImageVector.Builder(
    name = "ToadMarkBase",
    defaultWidth = 24.dp, defaultHeight = 24.dp,
    viewportWidth = 24f, viewportHeight = 24f,
).apply {
    // Eye bumps (behind the body edge, slightly proud of it).
    path(fill = SolidColor(Color.Black), fillAlpha = 1f) {
        circle(cx = 8.2f, cy = 9.4f, r = 3.5f)
    }
    path(fill = SolidColor(Color.Black), fillAlpha = 1f) {
        circle(cx = 15.8f, cy = 9.4f, r = 3.5f)
    }
    // Body mound with a flat base.
    path(fill = SolidColor(Color.Black), fillAlpha = 1f) {
        moveTo(3.2f, 21.6f)
        curveTo(2.2f, 14.2f, 6.6f, 10.2f, 12f, 10.2f)
        curveTo(17.4f, 10.2f, 21.8f, 14.2f, 20.8f, 21.6f)
        close()
    }
    // Belly — second tone of the duotone.
    path(fill = SolidColor(Color.Black), fillAlpha = 0.32f) {
        ellipse(cx = 12f, cy = 19.4f, rx = 5.2f, ry = 3.4f)
    }
}.build()

/** Pupil geometry, in the mark's 24-unit viewport, shared by [ToadFace]. */
@Immutable
private data class PupilSpec(val cx: Float, val cy: Float, val r: Float)

private val Pupils = listOf(PupilSpec(8.2f, 8.9f, 1.25f), PupilSpec(15.8f, 8.9f, 1.25f))

/**
 * The full mascot: [ToadMarkBase] plus pupils and a smile.
 *
 * @param blink 0 = eyes open, 1 = eyes shut (pupils squash to a line). Splash and
 *   empty states animate this; static callers leave it at 0.
 */
@Composable
fun ToadFace(
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
    blink: Float = 0f,
    body: Color = MaterialTheme.colorScheme.primary,
    pupil: Color = MaterialTheme.colorScheme.onPrimary,
    smile: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
    showSmile: Boolean = true,
) {
    val clampedBlink = blink.coerceIn(0f, 1f)
    Box(modifier.size(size)) {
        Icon(
            imageVector = ToadMarkBase,
            contentDescription = null,
            tint = body,
            modifier = Modifier.fillMaxSize(),
        )
        // Pupils, positioned in the 24-unit viewport's coordinate system.
        Pupils.forEach { p ->
            val pr = size * (p.r / 24f)
            val squashed = pr * (1f - 0.88f * clampedBlink)
            Box(
                modifier = Modifier
                    .offset(
                        x = size * (p.cx / 24f) - pr,
                        y = size * (p.cy / 24f) - squashed,
                    )
                    .size(width = pr * 2, height = squashed * 2),
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    drawOval(color = pupil, size = this.size)
                }
            }
        }
        if (showSmile) {
            Canvas(Modifier.fillMaxSize()) {
                val w = this.size.width
                val h = this.size.height
                drawArc(
                    color = smile,
                    startAngle = 25f,
                    sweepAngle = 130f,
                    useCenter = false,
                    topLeft = Offset(w * 0.34f, h * 0.58f),
                    size = androidx.compose.ui.geometry.Size(w * 0.32f, h * 0.22f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = w * 0.045f,
                        cap = StrokeCap.Round,
                    ),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Folder + file-type family
// ---------------------------------------------------------------------------

/** The toadFILES folder: a faint back panel with a lit front face. */
val ToadFolder: ImageVector = ImageVector.Builder(
    name = "ToadFolder",
    defaultWidth = 24.dp, defaultHeight = 24.dp,
    viewportWidth = 24f, viewportHeight = 24f,
).apply {
    // Back panel + tab, second tone.
    path(fill = SolidColor(Color.Black), fillAlpha = 0.3f) {
        moveTo(3.2f, 9.6f)
        lineTo(3.2f, 6.1f)
        arcToRelative(1.6f, 1.6f, 0f, isMoreThanHalf = false, isPositiveArc = true, 1.6f, -1.6f)
        lineTo(8.8f, 4.5f)
        quadTo(9.7f, 4.5f, 10.3f, 5.2f)
        lineTo(11.6f, 6.6f)
        lineTo(19.2f, 6.6f)
        arcToRelative(1.6f, 1.6f, 0f, isMoreThanHalf = false, isPositiveArc = true, 1.6f, 1.6f)
        lineTo(20.8f, 9.6f)
        close()
    }
    // Front face, full tone.
    path(fill = SolidColor(Color.Black), fillAlpha = 1f) {
        moveTo(3.2f, 10.4f)
        lineTo(20.8f, 10.4f)
        lineTo(20.8f, 17.2f)
        arcToRelative(1.8f, 1.8f, 0f, isMoreThanHalf = false, isPositiveArc = true, -1.8f, 1.8f)
        lineTo(5f, 19f)
        arcToRelative(1.8f, 1.8f, 0f, isMoreThanHalf = false, isPositiveArc = true, -1.8f, -1.8f)
        close()
    }
}.build()

/** The shared page backing every file-type icon sits on (drawn faint). */
private val FilePagePath: PathBuilder.() -> Unit = {
    moveTo(6.1f, 2.9f)
    lineTo(13.8f, 2.9f)
    lineTo(19.1f, 8.2f)
    lineTo(19.1f, 18.9f)
    arcToRelative(2.2f, 2.2f, 0f, isMoreThanHalf = false, isPositiveArc = true, -2.2f, 2.2f)
    lineTo(6.1f, 21.1f)
    arcToRelative(2.2f, 2.2f, 0f, isMoreThanHalf = false, isPositiveArc = true, -2.2f, -2.2f)
    lineTo(3.9f, 5.1f)
    arcToRelative(2.2f, 2.2f, 0f, isMoreThanHalf = false, isPositiveArc = true, 2.2f, -2.2f)
    close()
}

private val FileFoldPath: PathBuilder.() -> Unit = {
    moveTo(13.8f, 2.9f)
    lineTo(19.1f, 8.2f)
    lineTo(14.6f, 8.2f)
    arcToRelative(0.8f, 0.8f, 0f, isMoreThanHalf = false, isPositiveArc = true, -0.8f, -0.8f)
    close()
}

private fun fileIcon(name: String, glyph: PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black), fillAlpha = 0.3f) { this.FilePagePath() }
        path(fill = SolidColor(Color.Black), fillAlpha = 0.5f) { this.FileFoldPath() }
        path(fill = SolidColor(Color.Black), fillAlpha = 1f) { this.glyph() }
    }.build()

val ToadFileImage: ImageVector = fileIcon("ToadFileImage") {
    // Mountain range
    moveTo(6.6f, 18.2f)
    lineTo(10.2f, 12.6f)
    lineTo(12.9f, 16.2f)
    lineTo(14.8f, 13.7f)
    lineTo(17.6f, 18.2f)
    close()
    // Sun
    circle(cx = 15.1f, cy = 11.6f, r = 1.25f)
}

val ToadFileVideo: ImageVector = fileIcon("ToadFileVideo") {
    moveTo(9.6f, 11.9f)
    lineTo(16.6f, 15.4f)
    lineTo(9.6f, 18.9f)
    close()
}

val ToadFileAudio: ImageVector = fileIcon("ToadFileAudio") {
    // Note head
    circle(cx = 9.6f, cy = 16.9f, r = 2.0f)
    // Stem
    roundRect(left = 10.9f, top = 9.6f, right = 12.1f, bottom = 17.1f, r = 0.6f)
    // Flag
    moveTo(12.1f, 9.6f)
    curveTo(14.9f, 10.2f, 15.9f, 11.9f, 15.5f, 13.7f)
    curveTo(14.6f, 12.5f, 13.4f, 12.1f, 12.1f, 12.1f)
    close()
}

val ToadFileDoc: ImageVector = fileIcon("ToadFileDoc") {
    roundRect(left = 6.8f, top = 11.6f, right = 17.2f, bottom = 13.0f, r = 0.7f)
    roundRect(left = 6.8f, top = 14.4f, right = 17.2f, bottom = 15.8f, r = 0.7f)
    roundRect(left = 6.8f, top = 17.2f, right = 12.4f, bottom = 18.6f, r = 0.7f)
}

val ToadFileArchive: ImageVector = fileIcon("ToadFileArchive") {
    // Lid (a seam of faint page shows through below it)
    roundRect(left = 7.0f, top = 10.9f, right = 17.0f, bottom = 12.9f, r = 1.0f)
    // Canister body
    roundRect(left = 7.0f, top = 13.6f, right = 17.0f, bottom = 18.9f, r = 1.1f)
    // Handle
    roundRect(left = 10.6f, top = 14.7f, right = 13.4f, bottom = 16.0f, r = 0.65f)
}

val ToadFileApk: ImageVector = fileIcon("ToadFileApk") {
    // Droid head dome
    moveTo(7.3f, 18.9f)
    lineTo(7.3f, 15.9f)
    curveTo(7.3f, 12.6f, 9.4f, 10.9f, 12f, 10.9f)
    curveTo(14.6f, 10.9f, 16.7f, 12.6f, 16.7f, 15.9f)
    lineTo(16.7f, 18.9f)
    close()
    // Antennae
    moveTo(9.6f, 12.4f)
    lineTo(8.5f, 10.5f)
    lineTo(9.5f, 10.0f)
    lineTo(10.6f, 11.9f)
    close()
    moveTo(14.4f, 12.4f)
    lineTo(15.5f, 10.5f)
    lineTo(16.5f, 11.0f)
    lineTo(15.4f, 12.9f)
    close()
}

val ToadFileGeneric: ImageVector = fileIcon("ToadFileGeneric") {
    // A quiet dot-grid: the "unknown type" tells itself apart from docs.
    circle(cx = 8.6f, cy = 12.6f, r = 1.05f)
    circle(cx = 12.0f, cy = 12.6f, r = 1.05f)
    circle(cx = 15.4f, cy = 12.6f, r = 1.05f)
    circle(cx = 8.6f, cy = 16.4f, r = 1.05f)
    circle(cx = 12.0f, cy = 16.4f, r = 1.05f)
    circle(cx = 15.4f, cy = 16.4f, r = 1.05f)
}

/**
 * The file-type icon family used across Browse/Recent/Categories. Plain function
 * (no composition state) so non-composable helpers like `iconFor` can call it.
 *
 * Audit A18: the extension sets were rebuilt (6-7 allocations) on EVERY call — once
 * per visible row per recomposition; they are top-level constants now.
 */
private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "svg")
private val VIDEO_EXTS = setOf("mp4", "mkv", "avi", "mov", "webm", "3gp", "m4v")
private val AUDIO_EXTS = setOf("mp3", "flac", "wav", "ogg", "m4a", "aac", "opus", "amr")
private val ARCHIVE_EXTS = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz")
private val DOC_EXTS = setOf(
    "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "csv", "htm", "html",
)

fun toadFileIconFor(extension: String): ImageVector = when (extension.lowercase()) {
    in IMAGE_EXTS -> ToadFileImage
    in VIDEO_EXTS -> ToadFileVideo
    in AUDIO_EXTS -> ToadFileAudio
    in ARCHIVE_EXTS -> ToadFileArchive
    "apk" -> ToadFileApk
    in DOC_EXTS -> ToadFileDoc
    else -> ToadFileGeneric
}

// ---------------------------------------------------------------------------
// Illustrated empty states
// ---------------------------------------------------------------------------

/** Scene wrapper: everything centers in a soft tonal circle. */
@Composable
private fun Scene(modifier: Modifier, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    val halo = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(color = halo)
        }
        content()
    }
}

/** Empty folder: the toad peeks over a big folder flap. */
@Composable
fun EmptyFolderArt(modifier: Modifier = Modifier, size: Dp = 148.dp) {
    Scene(modifier.size(size)) {
        val tint = MaterialTheme.colorScheme.primary
        Icon(
            imageVector = ToadFolder,
            contentDescription = null,
            tint = tint.copy(alpha = 0.9f),
            modifier = Modifier.size(size * 0.62f).align(Alignment.BottomCenter)
                .offset(y = size * 0.06f),
        )
        ToadFace(
            size = size * 0.5f,
            body = tint,
            modifier = Modifier.align(Alignment.TopCenter).offset(y = size * 0.07f),
        )
    }
}

/** Empty trash: the toad next to an open bin. */
@Composable
fun EmptyTrashArt(modifier: Modifier = Modifier, size: Dp = 148.dp) {
    Scene(modifier.size(size)) {
        val tint = MaterialTheme.colorScheme.primary
        val binColor = tint.copy(alpha = 0.75f)
        // Bin body + lid, drawn as simple shapes.
        Canvas(Modifier.size(size * 0.44f).align(Alignment.BottomCenter).offset(y = -size * 0.14f)) {
            val w = this.size.width
            val h = this.size.height
            // Lid
            drawRoundRect(
                color = binColor,
                topLeft = Offset(w * 0.06f, h * 0.10f),
                size = androidx.compose.ui.geometry.Size(w * 0.88f, h * 0.10f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.05f),
            )
            drawCircle(color = binColor, radius = w * 0.07f, center = Offset(w * 0.5f, h * 0.06f))
            // Body (tapered)
            val bodyTop = h * 0.22f
            val bodyPath = androidx.compose.ui.graphics.Path().apply {
                moveTo(w * 0.16f, bodyTop)
                lineTo(w * 0.84f, bodyTop)
                lineTo(w * 0.76f, h)
                lineTo(w * 0.24f, h)
                close()
            }
            drawPath(bodyPath, binColor.copy(alpha = 0.55f))
            // Ribs
            for (i in 1..2) {
                val x = w * (0.25f + 0.25f * i)
                drawLine(
                    color = binColor,
                    start = Offset(x, bodyTop + h * 0.06f),
                    end = Offset(x * (1f - 0.03f * i), h * 0.97f),
                    strokeWidth = w * 0.035f,
                    cap = StrokeCap.Round,
                )
            }
        }
        ToadFace(
            size = size * 0.46f,
            body = tint,
            modifier = Modifier.align(Alignment.TopStart).offset(x = size * 0.12f, y = size * 0.16f),
        )
    }
}

/** No search results: the toad inspects a magnifying glass. */
@Composable
fun NoResultsArt(modifier: Modifier = Modifier, size: Dp = 148.dp) {
    Scene(modifier.size(size)) {
        val tint = MaterialTheme.colorScheme.primary
        val glass = MaterialTheme.colorScheme.onSurfaceVariant
        Canvas(Modifier.size(size * 0.5f).align(Alignment.BottomCenter).offset(x = size * 0.10f, y = -size * 0.10f)) {
            val r = this.size.width * 0.30f
            val c = Offset(this.size.width * 0.42f, this.size.height * 0.42f)
            drawCircle(color = glass.copy(alpha = 0.35f), radius = r, center = c)
            drawCircle(color = glass, radius = r, center = c, style = androidx.compose.ui.graphics.drawscope.Stroke(width = r * 0.22f))
            drawLine(
                color = glass,
                start = Offset(c.x + r * 0.72f, c.y + r * 0.72f),
                end = Offset(c.x + r * 1.45f, c.y + r * 1.45f),
                strokeWidth = r * 0.3f,
                cap = StrokeCap.Round,
            )
        }
        ToadFace(
            size = size * 0.48f,
            body = tint,
            modifier = Modifier.align(Alignment.TopStart).offset(x = size * 0.16f, y = size * 0.14f),
        )
    }
}
