package com.rrajath.grove.ui.attach

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.exifinterface.media.ExifInterface
import coil3.compose.AsyncImage
import coil3.decode.BitmapFactoryDecoder
import coil3.request.ImageRequest
import com.rrajath.grove.FeatureFlags
import com.rrajath.grove.GroveApplication
import com.rrajath.grove.R
import com.rrajath.grove.attach.AttachmentFiles
import com.rrajath.grove.attach.AttachmentLink
import com.rrajath.grove.attach.AttachmentLookup
import com.rrajath.grove.attach.AttachmentResolver
import com.rrajath.grove.attach.AttachmentStatus
import com.rrajath.grove.attach.AttachmentStatusCache
import com.rrajath.grove.attach.ImageBounds
import com.rrajath.grove.attach.LocalAttachment
import com.rrajath.grove.attach.attachmentExtension
import com.rrajath.grove.attach.linkDescription
import com.rrajath.grove.org.InlineTokenizer
import com.rrajath.grove.org.InlineType
import com.rrajath.grove.org.OrgDocument
import com.rrajath.grove.settings.AttachImageWidth
import com.rrajath.grove.ui.editor.categoryColors
import com.rrajath.grove.ui.theme.PlexMono
import com.rrajath.grove.ui.theme.grove
import com.rrajath.grove.vault.FileEntry
import com.rrajath.grove.vault.FileStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * What read mode needs to draw attachment links as 1d images and chips: the
 * org file and parsed document the links sit in, the vault store, the status
 * cache, and the image-width setting. Null (no provider) means attachments are
 * off and links render as plain links, exactly as before.
 */
@Stable
class AttachmentRenderContext(
    val orgFile: String,
    val doc: OrgDocument?,
    val store: FileStore,
    val cache: AttachmentStatusCache,
    val widthMode: AttachImageWidth,
    val fixedWidthPx: Int,
    /** Lines of the note on screen; a repeated chip link resolves against these first. */
    val preferLines: IntRange?,
    /** Opens a link target the way a link tap does (the Open with chooser). */
    val openTarget: (String) -> Unit,
    /** Opens a present image in the 1h viewer: (vault path, filename). Null falls back to [openTarget]. */
    val openImage: ((path: String, name: String) -> Unit)? = null,
    val toast: (String) -> Unit,
    private val io: CoroutineDispatcher,
) {
    private val resolver = AttachmentResolver(AttachmentLookup(AttachmentFiles(store)), cache)

    suspend fun status(link: LocalAttachment, rawTarget: String, docLine: Int?): AttachmentStatus =
        withContext(io) { resolver.status(link, rawTarget, orgFile, doc, docLine, preferLines) }

    /** [status] again from the store, ignoring the cached answer. */
    suspend fun recheck(link: LocalAttachment, rawTarget: String, docLine: Int?): AttachmentStatus =
        withContext(io) { resolver.recheck(link, rawTarget, orgFile, doc, docLine, preferLines) }

    /**
     * Displayed pixel size from the file header (cached per path + mtime), or
     * null when unreadable. EXIF rotation is applied: a camera JPEG copied as-is
     * stores sensor-landscape pixels plus a "rotate 90" tag, and Coil draws it
     * rotated, so the raw header size would give a portrait photo a landscape box.
     */
    suspend fun bounds(entry: FileEntry): ImageBounds? {
        cache.bounds(entry)?.let { return it }
        return withContext(io) {
            runCatching {
                val raw = store.openInput(entry.name).use { input ->
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeStream(input, null, opts)
                    if (opts.outWidth > 0 && opts.outHeight > 0) ImageBounds(opts.outWidth, opts.outHeight) else null
                } ?: return@runCatching null
                if (exifSwapsAxes(entry.name)) ImageBounds(raw.height, raw.width) else raw
            }.getOrNull()
        }?.also { cache.putBounds(entry, it) }
    }

    /** Remembers a corrected size once the decoded image disagrees with the header's. */
    fun correctBounds(entry: FileEntry, value: ImageBounds) = cache.putBounds(entry, value)

    private suspend fun exifSwapsAxes(path: String): Boolean {
        if (attachmentExtension(path) !in EXIF_EXTENSIONS) return false
        val orientation = runCatching {
            store.openInput(path).use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        return orientation in SWAPPING_ORIENTATIONS
    }

    suspend fun contentUri(path: String): Uri? = withContext(io) { runCatching { store.contentUri(path) }.getOrNull() }
}

private val EXIF_EXTENSIONS = setOf("jpg", "jpeg", "heic", "heif", "webp")
private val SWAPPING_ORIENTATIONS = setOf(
    ExifInterface.ORIENTATION_ROTATE_90,
    ExifInterface.ORIENTATION_ROTATE_270,
    ExifInterface.ORIENTATION_TRANSPOSE,
    ExifInterface.ORIENTATION_TRANSVERSE,
)

val LocalAttachmentRender = staticCompositionLocalOf<AttachmentRenderContext?> { null }

/**
 * Provides [LocalAttachmentRender] for a read screen's content when the
 * attachments flag is on and a vault is open; otherwise just runs [content].
 * The status cache is invalidated app-wide (foreground, sync, attachment
 * moves and deletes), not per screen resume, so returning from the image
 * viewer doesn't look every attachment up again.
 */
@Composable
fun ProvideAttachmentRendering(
    orgFile: String,
    doc: OrgDocument?,
    preferLines: IntRange?,
    openTarget: (String) -> Unit,
    toast: (String) -> Unit,
    openImage: ((path: String, name: String) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val app = LocalContext.current.applicationContext as? GroveApplication
    if (!FeatureFlags.ATTACHMENTS || app == null) return content()
    val store by app.fileStore.collectAsState()
    val settings by app.settingsRepository.settings.collectAsState(initial = null)
    val s = store
    val prefs = settings
    if (s == null || prefs == null) return content()
    val cache = app.attachmentStatusCache
    val ctx = remember(orgFile, doc, s, prefs.attachImageWidth, prefs.attachImageWidthPx, preferLines, openTarget, openImage, toast) {
        AttachmentRenderContext(
            orgFile = orgFile,
            doc = doc,
            store = s,
            cache = cache,
            widthMode = prefs.attachImageWidth,
            fixedWidthPx = prefs.attachImageWidthPx,
            preferLines = preferLines,
            openTarget = openTarget,
            openImage = openImage,
            toast = toast,
            io = app.dispatchers.io,
        )
    }
    CompositionLocalProvider(LocalAttachmentRender provides ctx, content = content)
}

/** 1q toasts for missing attachments. */
fun imageNotFoundMessage(name: String) = "Image not found: $name"
fun fileNotFoundMessage(name: String) = "File not found: $name"

private val ALPHA_EXTENSIONS = setOf("png", "gif", "webp")

/** Over-image GIF badge: fixed, theme-independent (tech design 12). */
private val OverImageBadgeInk = Color.White
private val OverImageBadgeFill = Color.Black.copy(alpha = 0.55f)

/**
 * Display size of an inline image in px (tech design 7.2): the column, narrowed
 * by `#+ATTR_ORG` [widthAttr] and the Fixed setting (both read as dp), never
 * wider than the image itself (one image pixel per dp), then shrunk to keep the
 * height under [capH]. Unknown [bounds] draw a 4:3 placeholder.
 */
fun imageDisplaySize(
    maxW: Float,
    capH: Float,
    density: Float,
    widthAttr: Int?,
    fixedWidth: Int?,
    bounds: ImageBounds?,
): Size {
    var w = maxW
    widthAttr?.let { w = minOf(w, it * density) }
    fixedWidth?.let { w = minOf(w, it * density) }
    bounds?.let { w = minOf(w, it.width * density) }
    val ratio = bounds?.ratio ?: (4f / 3f)
    var h = w / ratio
    if (capH > 0 && h > capH) {
        h = capH
        w = h * ratio
    }
    return Size(w, h)
}

/**
 * The 1d inline image (tech design 7.2, 7.4): fits the column (or the
 * `#+ATTR_ORG` / Fixed width), keeps its aspect ratio under a 60%-of-window
 * height cap, start-aligned, with a mono filename caption. Nothing is looked up
 * or decoded until the block is near the viewport.
 */
@Composable
fun AttachmentImage(
    link: LocalAttachment,
    rawTarget: String,
    docLine: Int?,
    widthAttr: Int?,
    render: AttachmentRenderContext,
    onDoubleTap: (() -> Unit)? = null,
) {
    val c = MaterialTheme.grove
    val density = LocalDensity.current
    val windowHeight = LocalWindowInfo.current.containerSize.height
    val generation by render.cache.generation.collectAsState()
    var nearViewport by remember { mutableStateOf(false) }
    var status by remember(rawTarget, docLine) { mutableStateOf<AttachmentStatus?>(null) }
    var bounds by remember(rawTarget, docLine) { mutableStateOf<ImageBounds?>(null) }
    var uri by remember(rawTarget, docLine) { mutableStateOf<Uri?>(null) }
    var failed by remember(rawTarget, docLine) { mutableStateOf(false) }
    var loaded by remember(rawTarget, docLine) { mutableStateOf(false) }
    // A cached Present answer can be stale (deleted elsewhere); the first load
    // error asks the store again before showing "can't display".
    var loadError by remember(rawTarget, docLine) { mutableStateOf(false) }
    var rechecked by remember(rawTarget, docLine) { mutableStateOf(false) }

    LaunchedEffect(nearViewport, rawTarget, docLine, generation, render) {
        if (!nearViewport) return@LaunchedEffect
        val st = render.status(link, rawTarget, docLine)
        if (st is AttachmentStatus.Present) {
            bounds = render.bounds(st.entry)
            uri = render.contentUri(st.path)
            if (st != status) {
                failed = false
                loadError = false
                rechecked = false
            }
        }
        status = st
    }

    LaunchedEffect(loadError) {
        if (!loadError) return@LaunchedEffect
        if (rechecked) {
            failed = true
            return@LaunchedEffect
        }
        rechecked = true
        val st = render.recheck(link, rawTarget, docLine)
        val before = status
        if (st is AttachmentStatus.Present && st != before) {
            bounds = render.bounds(st.entry)
            uri = render.contentUri(st.path)
            loadError = false
        } else if (st is AttachmentStatus.Present) {
            failed = true
        }
        status = st
    }

    val name = link.fileName
    val gate = Modifier.onGloballyPositioned { coords ->
        if (!nearViewport) {
            val b = coords.boundsInWindow()
            // One window of look-ahead either way, so images start before they scroll in.
            nearViewport = b.bottom >= -windowHeight && b.top <= windowHeight * 2f
        }
    }
    val tap = Modifier.pointerTaps(
        onTap = {
            val st = status
            val viewer = render.openImage
            when {
                st == AttachmentStatus.Missing -> render.toast(imageNotFoundMessage(name))
                st is AttachmentStatus.Present && viewer != null -> viewer(st.path, name)
                else -> render.openTarget(rawTarget)
            }
        },
        onDoubleTap = onDoubleTap,
    )

    when {
        status == AttachmentStatus.Missing -> MissingImage(name, gate.then(tap))
        failed -> CantDisplayImage(name, gate.then(tap))
        else -> BoxWithConstraints(gate.fillMaxWidth().padding(bottom = 16.dp)) {
            val fixed = if (render.widthMode == AttachImageWidth.FIXED) render.fixedWidthPx else null
            val size = imageDisplaySize(
                maxW = constraints.maxWidth.toFloat(),
                capH = windowHeight * 0.6f,
                density = density.density,
                widthAttr = widthAttr,
                fixedWidth = fixed,
                bounds = bounds,
            )
            val wDp = with(density) { size.width.toDp() }
            val hDp = with(density) { size.height.toDp() }
            val ext = link.extension
            val gif = ext == "gif"
            Column {
                Box(
                    Modifier
                        .size(wDp, hDp)
                        .clip(RoundedCornerShape(10.dp))
                        .then(
                            when {
                                !loaded -> Modifier.stripes(c.surface2, c.surface3)
                                ext in ALPHA_EXTENSIONS -> Modifier.checkerboard(c.surface, c.surface3)
                                else -> Modifier
                            }
                        )
                        .semantics { contentDescription = name }
                        .then(tap)
                        .testTag("attachment_image"),
                ) {
                    val u = uri
                    val st = status
                    if (u != null && st is AttachmentStatus.Present) {
                        val context = LocalContext.current
                        val request = remember(u, st.entry) {
                            ImageRequest.Builder(context)
                                .data(u)
                                // Path + mtime + size, so an edited file isn't served stale.
                                .memoryCacheKey("${st.path}:${st.entry.lastModified}:${st.entry.size}")
                                // First frame only; the viewer animates.
                                .apply { if (gif) decoderFactory(BitmapFactoryDecoder.Factory()) }
                                .build()
                        }
                        AsyncImage(
                            model = request,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                            onSuccess = { result ->
                                // Safety net: if the decoded image's ratio differs from the
                                // header's (an orientation we didn't read), resize the box to
                                // it, so the image fills the rounded clip instead of being
                                // letterboxed inside it with square corners.
                                val drawn = result.painter.intrinsicSize
                                val b = bounds
                                if (drawn.width > 0f && drawn.height > 0f) {
                                    val ratio = drawn.width / drawn.height
                                    if (b == null || kotlin.math.abs(ratio - b.ratio) > 0.01f * b.ratio) {
                                        val base = b?.let { maxOf(it.width, it.height) } ?: maxOf(drawn.width, drawn.height).toInt()
                                        val fixed = if (ratio >= 1f) {
                                            ImageBounds(base, (base / ratio).roundToInt().coerceAtLeast(1))
                                        } else {
                                            ImageBounds((base * ratio).roundToInt().coerceAtLeast(1), base)
                                        }
                                        bounds = fixed
                                        render.correctBounds(st.entry, fixed)
                                    }
                                }
                                loaded = true
                            },
                            onError = { loadError = true },
                        )
                    }
                    if (gif) {
                        Text(
                            "GIF",
                            fontFamily = PlexMono,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = OverImageBadgeInk,
                            modifier = Modifier
                                .padding(start = 8.dp, top = 8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(OverImageBadgeFill)
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    if (gif) "$name · plays in viewer" else name,
                    fontFamily = PlexMono,
                    fontSize = 11.5.sp,
                    color = c.ink3,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun MissingImage(name: String, modifier: Modifier) {
    val c = MaterialTheme.grove
    Column(
        modifier
            .padding(bottom = 12.dp)
            .fillMaxWidth()
            .height(112.dp)
            .drawBehind {
                val stroke = 1.5.dp.toPx()
                drawRoundRect(
                    color = c.line2,
                    topLeft = Offset(stroke / 2, stroke / 2),
                    size = Size(size.width - stroke, size.height - stroke),
                    cornerRadius = CornerRadius(10.dp.toPx()),
                    style = Stroke(
                        width = stroke,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
                    ),
                )
            }
            .testTag("attachment_image_missing"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        Icon(
            painterResource(R.drawable.ic_attach_image_missing),
            contentDescription = null,
            tint = c.ink3,
            modifier = Modifier.size(20.dp),
        )
        Text(imageNotFoundMessage(name), fontFamily = PlexMono, fontSize = 12.sp, color = c.ink3, maxLines = 1)
    }
}

@Composable
private fun CantDisplayImage(name: String, modifier: Modifier) {
    val c = MaterialTheme.grove
    Box(
        modifier
            .padding(bottom = 16.dp)
            .fillMaxWidth()
            .height(84.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(c.surface2)
            .testTag("attachment_image_error"),
        contentAlignment = Alignment.Center,
    ) {
        Text("Can't display image: $name", fontFamily = PlexMono, fontSize = 12.sp, color = c.ink2, maxLines = 1)
    }
}

private fun Modifier.pointerTaps(onTap: () -> Unit, onDoubleTap: (() -> Unit)?): Modifier =
    pointerInput(onTap, onDoubleTap) {
        detectTapGestures(
            onTap = { onTap() },
            onDoubleTap = onDoubleTap?.let { dt -> { _: Offset -> dt() } },
        )
    }

/** The 135° loading stripes: alternating 10dp bands. */
private fun Modifier.stripes(a: Color, b: Color): Modifier = drawWithCache {
    val period = 20.dp.toPx() / kotlin.math.sqrt(2f)
    val brush = Brush.linearGradient(
        0f to a, 0.5f to a, 0.5f to b, 1f to b,
        start = Offset.Zero,
        end = Offset(period, period),
        tileMode = TileMode.Repeated,
    )
    onDrawBehind { drawRect(brush) }
}

/** Checkerboard behind images that can carry alpha: [base] with 7dp [square]s on a 14dp period. */
private fun Modifier.checkerboard(base: Color, square: Color): Modifier = drawWithCache {
    val cell = 7.dp.toPx()
    val path = Path()
    var y = 0f
    var row = 0
    while (y < size.height) {
        var x = if (row % 2 == 0) 0f else cell
        while (x < size.width) {
            path.addRect(Rect(x, y, x + cell, y + cell))
            x += cell * 2
        }
        y += cell
        row++
    }
    onDrawBehind {
        drawRect(base)
        drawPath(path, square)
    }
}

// ---------- Chips (tech design 7.3) ----------

/** A local-file link in a text run that draws as a 1d chip. */
data class ChipLink(
    /** Source range of the link token in the run's text. */
    val range: IntRange,
    val rawTarget: String,
    val link: LocalAttachment,
) {
    val id: String get() = "attach-chip-${range.first}"
}

/** Every local-file link in [text]; image links outside paragraphs (list items) draw as chips too. */
fun chipLinksIn(text: String): List<ChipLink> {
    if (!text.contains("[[") && !text.contains("file:")) return emptyList()
    return InlineTokenizer.tokenize(text).mapNotNull { token ->
        if (token.type != InlineType.LINK) return@mapNotNull null
        val target = token.target ?: return@mapNotNull null
        val link = AttachmentLink.classify(target, linkDescription(text, token.range, token.text))
            ?: return@mapNotNull null
        ChipLink(token.range, target, link)
    }
}

/** Middle-truncates [label] past 28 chars so the extension stays visible. */
fun chipLabel(label: String): String =
    if (label.length <= 28) label else label.take(16) + "…" + label.takeLast(10)

/** Chip state for one text run: which chips are missing, and the `inlineContent` map. */
class ChipRun(
    val missing: Map<String, Boolean>,
    val inlineContent: Map<String, InlineTextContent>,
)

/**
 * Resolves [chips]' file status asynchronously (never on the render path) and
 * builds their inline content. Each chip's placeholder is sized from measured
 * text, so it flows with the line and wraps as one unit.
 */
@Composable
fun rememberChipRun(chips: List<ChipLink>, render: AttachmentRenderContext): ChipRun {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val generation by render.cache.generation.collectAsState()
    val missing = remember(chips, render) { mutableStateMapOf<String, Boolean>() }
    LaunchedEffect(chips, render, generation) {
        for (chip in chips) {
            missing[chip.id] = render.status(chip.link, chip.rawTarget, null) == AttachmentStatus.Missing
        }
    }
    // Read in composition so a status flip recomposes; as a remember key it
    // limits measuring and rebuilding the inline content to when a chip flips.
    val missingFlags = chips.map { missing[it.id] == true }
    return remember(chips, missing, missingFlags, density, measurer) {
        val content = chips.zip(missingFlags).associate { (chip, isMissing) ->
            val label = chipLabel(chip.link.label)
            // Whole pixels, rounded the way AttachmentChip's padding, size and
            // spacer modifiers round them (see chipPlaceholder).
            val placeholder = with(density) {
                val labelSize = measurer.measure(label, ChipLabelStyle).size
                val lead = if (isMissing) {
                    5.dp.roundToPx() + 12.dp.roundToPx()
                } else {
                    3.dp.roundToPx() + measurer.measure(chip.link.badge, ChipBadgeStyle).size.width + 4.dp.roundToPx() * 2
                }
                val width = lead + 5.dp.roundToPx() + labelSize.width + 7.dp.roundToPx()
                val height = labelSize.height + 1.dp.roundToPx() * 2
                chipPlaceholder(width, height)
            }
            chip.id to InlineTextContent(placeholder) { AttachmentChip(chip.link, label, isMissing) }
        }
        ChipRun(missing, content)
    }
}

/**
 * The inline placeholder for a chip that lays out [widthPx] by [heightPx]. The
 * placeholder is the chip's only width constraint: BasicText measures inline
 * content with maxWidth = floor(placeholder width), so the size must be the
 * chip's real pixel size (each dp rounded as its modifier rounds it, not a sum
 * of fractional dp), or the label gets a pixel or two too few and its last
 * character wraps onto a line maxLines hides. The extra half pixel survives
 * the px to sp to px round trip, which can otherwise floor a pixel away.
 */
internal fun Density.chipPlaceholder(widthPx: Int, heightPx: Int): Placeholder =
    Placeholder((widthPx + 0.5f).toSp(), heightPx.toSp(), PlaceholderVerticalAlign.TextCenter)

private val ChipLabelStyle = TextStyle(fontFamily = PlexMono, fontSize = 12.5.sp, lineHeight = 1.5.em)
private val ChipBadgeStyle = TextStyle(fontFamily = PlexMono, fontSize = 9.sp, fontWeight = FontWeight.Bold)

/** The 1d file chip: mono, soft fill, category badge (or a broken-link icon when missing). */
@Composable
fun AttachmentChip(link: LocalAttachment, label: String, missing: Boolean) {
    val c = MaterialTheme.grove
    val (fg, bg) = categoryColors(c, link.category)
    Row(
        Modifier
            .offset(y = (-1).dp)
            .clip(RoundedCornerShape(6.dp))
            .background(c.surface2)
            .padding(start = if (missing) 5.dp else 3.dp, end = 7.dp, top = 1.dp, bottom = 1.dp)
            .then(if (missing) Modifier.alpha(0.75f) else Modifier)
            .testTag(if (missing) "attachment_chip_missing" else "attachment_chip"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (missing) {
            Icon(
                painterResource(R.drawable.ic_attach_chip_missing),
                contentDescription = null,
                tint = c.ink3,
                modifier = Modifier.size(12.dp),
            )
        } else {
            Text(
                link.badge,
                style = ChipBadgeStyle,
                color = fg,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(bg)
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
        Spacer(Modifier.width(5.dp))
        Text(
            label,
            style = ChipLabelStyle,
            color = if (missing) c.ink3 else c.ink,
            textDecoration = if (missing) TextDecoration.LineThrough else null,
            maxLines = 1,
            softWrap = false,
        )
    }
}
