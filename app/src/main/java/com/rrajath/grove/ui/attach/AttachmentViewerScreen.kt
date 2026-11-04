package com.rrajath.grove.ui.attach

import android.app.Activity
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import coil3.request.ImageRequest
import com.rrajath.grove.GroveApplication
import com.rrajath.grove.R
import com.rrajath.grove.attach.AttachmentIntents
import com.rrajath.grove.ui.components.GroveToast
import com.rrajath.grove.ui.theme.GroveColors
import com.rrajath.grove.ui.theme.LocalGroveColors
import com.rrajath.grove.ui.theme.PlexMono
import com.rrajath.grove.ui.theme.PlexSans
import com.rrajath.grove.ui.theme.grove
import com.rrajath.grove.ui.vault.OutlineToast
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.saket.telephoto.zoomable.DoubleClickToZoomListener
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.ZoomableState
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState
import kotlin.math.roundToInt

/** Double tap jumps between Fit and this zoom (240%, as in 1h). */
const val VIEWER_ZOOM_STEP = 2.4f

/** Pinch limit relative to Fit: enough to reach native pixels on a 48 MP photo. */
const val VIEWER_MAX_ZOOM = 8f

private const val TOAST_MILLIS = 1900L

/** Minimum space kept above and below the hint when it sits in the band under the image. */
private val HINT_GAP = 12.dp

/** Height of the top chrome's row (close target plus filename), below the status bar. */
private val TOP_BAR_HEIGHT = 52.dp

private fun ZoomableState.userZoom(): Float? =
    contentTransformation.takeIf { it.isSpecified }?.scaleMetadata?.userZoom

private fun ZoomableState.isZoomed(): Boolean = (userZoom() ?: 1f) > 1.005f

/** Double tap toggles Fit and [VIEWER_ZOOM_STEP] around the tapped point. */
private object ToggleFitAndZoom : DoubleClickToZoomListener {
    override suspend fun onDoubleClick(state: ZoomableState, centroid: Offset) {
        if (state.isZoomed()) state.resetZoom() else state.zoomTo(VIEWER_ZOOM_STEP, centroid)
    }
}

/**
 * The 1h full-screen image viewer (tech design 8). Always drawn with the
 * user's dark theme ([colors]), whatever the app theme, so photos sit on a
 * dark ground; the status and navigation bar icons are switched to light for
 * as long as it's on screen.
 */
@Composable
fun AttachmentViewerScreen(
    path: String,
    title: String,
    colors: GroveColors,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as? GroveApplication
    val store by remember(app) { app?.fileStore ?: MutableStateFlow(null) }.collectAsState()
    var uri by remember(path) { mutableStateOf<Uri?>(null) }
    var placeholderKey by remember(path) { mutableStateOf<String?>(null) }
    var missing by remember(path) { mutableStateOf(false) }
    LaunchedEffect(store, path) {
        val s = store ?: return@LaunchedEffect
        val io = app?.dispatchers?.io ?: return@LaunchedEffect
        // contentUri() goes through the raw-file lookup and is null when the file
        // is gone. Not exists(): on SAF that only sees the .org note index, so
        // every attachment would read as missing. The stat runs alongside it and
        // only feeds the placeholder key, so it never delays the image.
        val found = withContext(io) {
            val entry = async { runCatching { s.statRaw(path) }.getOrNull() }
            val u = runCatching { s.contentUri(path) }.getOrNull()
            u to entry.await()
        }
        placeholderKey = found.second?.let(::attachmentMemoryCacheKey)
        uri = found.first
        missing = found.first == null
    }
    ViewerSystemBars(appIsDark = MaterialTheme.grove.isDark)

    val request = remember(uri, placeholderKey) {
        uri?.let {
            ImageRequest.Builder(context)
                .data(it)
                // The inline image's decoded bitmap, if it's still in memory, is
                // the first frame while the full image loads.
                .placeholderMemoryCacheKey(placeholderKey)
                .build()
        }
    }

    suspend fun handOff(share: Boolean): String? {
        val s = store ?: return imageNotFoundMessage(title)
        val result = if (share) AttachmentIntents.share(context, s, path) else AttachmentIntents.open(context, s, path)
        return when (result) {
            AttachmentIntents.Result.Launched -> null
            AttachmentIntents.Result.Missing -> imageNotFoundMessage(title)
            AttachmentIntents.Result.NoHandler -> AttachmentIntents.NO_HANDLER_MESSAGE
        }
    }

    CompositionLocalProvider(LocalGroveColors provides colors) {
        AttachmentViewer(
            model = request,
            title = title,
            missing = missing,
            onShare = { handOff(share = true) },
            onOpen = { handOff(share = false) },
            onClose = onClose,
        )
    }
}

/**
 * Light bar icons while the viewer shows; restores the app theme's on exit.
 * Keyed on the configuration as GroveTheme is, since rotation resets them.
 */
@Composable
private fun ViewerSystemBars(appIsDark: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    val configuration = LocalConfiguration.current
    LaunchedEffect(configuration) {
        val window = (view.context as? Activity)?.window ?: return@LaunchedEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }
    DisposableEffect(view) {
        onDispose {
            val window = (view.context as? Activity)?.window ?: return@onDispose
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !appIsDark
                isAppearanceLightNavigationBars = !appIsDark
            }
        }
    }
}

/**
 * The viewer's content, without file lookup or system-bar handling. [model] is
 * the Coil request (null while resolving); [onShare]/[onOpen] return a toast
 * message, or null when another app took over.
 */
@Composable
fun AttachmentViewer(
    model: ImageRequest?,
    title: String,
    missing: Boolean,
    onShare: suspend () -> String?,
    onOpen: suspend () -> String?,
    onClose: () -> Unit,
) {
    val c = MaterialTheme.grove
    val scope = rememberCoroutineScope()
    var chrome by rememberSaveable { mutableStateOf(false) }
    var tapped by rememberSaveable { mutableStateOf(false) }
    var failed by remember(model) { mutableStateOf(false) }
    var toast by remember { mutableStateOf<OutlineToast?>(null) }
    val toggleChrome = {
        chrome = !chrome
        tapped = true
    }
    fun showToast(message: String) {
        val t = OutlineToast(message, System.nanoTime())
        toast = t
        scope.launch {
            delay(TOAST_MILLIS)
            if (toast == t) toast = null
        }
    }
    val zoomState = rememberZoomableState(zoomSpec = ZoomSpec(maxZoomFactor = VIEWER_MAX_ZOOM))
    val imageState = rememberZoomableImageState(zoomState)
    var viewerHeight by remember { mutableIntStateOf(0) }
    val showImage = model != null && !failed && !missing

    Box(
        Modifier
            .fillMaxSize()
            .background(c.bg)
            .onSizeChanged { viewerHeight = it.height }
            .testTag("attachment_viewer"),
    ) {
        if (showImage) {
            val request = remember(model) {
                model!!.newBuilder().listener(onError = { _, _ -> failed = true }).build()
            }
            ZoomableAsyncImage(
                model = request,
                contentDescription = title,
                modifier = Modifier.fillMaxSize().testTag("attachment_viewer_image"),
                state = imageState,
                onClick = { toggleChrome() },
                onDoubleClick = ToggleFitAndZoom,
            )
        } else {
            // Nothing to zoom: the whole screen still toggles the chrome.
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures(onTap = { toggleChrome() }) },
                contentAlignment = Alignment.Center,
            ) {
                if (missing || failed) {
                    Text(
                        if (missing) imageNotFoundMessage(title) else "Can't display image",
                        fontFamily = PlexSans,
                        fontSize = 14.sp,
                        color = c.ink2,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp).testTag("attachment_viewer_error"),
                    )
                }
            }
        }

        if (!tapped) {
            TapHint(
                imageBottom = if (showImage) {
                    zoomState.transformedContentBounds.takeIf { it.height > 0f }?.bottom
                } else {
                    null
                },
                waitForImage = showImage,
                viewerHeight = viewerHeight,
            )
        }

        AnimatedVisibility(
            visible = chrome,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(180)),
            modifier = Modifier.align(Alignment.TopStart),
        ) {
            // Solid at 88% behind the status bar and the row, so the filename never
            // straddles the image's top edge.
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(c.bg.copy(alpha = 0.88f))
                    .statusBarsPadding(),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(TOP_BAR_HEIGHT)
                        .padding(start = 4.dp, end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .clickable(onClick = onClose)
                            .semantics {
                                contentDescription = "Close"
                                role = Role.Button
                            }
                            .testTag("attachment_viewer_close"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("✕", fontFamily = PlexSans, fontSize = 20.sp, color = c.ink)
                    }
                    Text(
                        title,
                        fontFamily = PlexMono,
                        fontSize = 13.sp,
                        color = c.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = chrome,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 30.dp),
        ) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(30.dp))
                    // Solid at 88% on every API level: Compose has no backdrop blur.
                    .background(c.surface2.copy(alpha = 0.88f))
                    .padding(5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                BarAction(R.drawable.ic_attach_share, 17, "Share") {
                    scope.launch { onShare()?.let(::showToast) }
                }
                BarAction(R.drawable.ic_attach_open_with, 16, "Open in…") {
                    scope.launch { onOpen()?.let(::showToast) }
                }
            }
        }

        GroveToast(
            toast = toast,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 100.dp),
        )
    }
}

/**
 * `Tap for controls`. Sits centered in the band between the image's bottom
 * edge ([imageBottom], px) and the nav bar when that band leaves [HINT_GAP]
 * on both sides; otherwise (a tall image, or zoomed in) it floats as a pill
 * 40dp above the nav bar so it never touches the image edge. With
 * [waitForImage] it stays invisible until the image's bounds are known.
 */
@Composable
private fun BoxScope.TapHint(imageBottom: Float?, waitForImage: Boolean, viewerHeight: Int) {
    val c = MaterialTheme.grove
    val density = LocalDensity.current
    val navBottom = WindowInsets.navigationBars.getBottom(density)
    var hintHeight by remember { mutableIntStateOf(0) }
    val gap = with(density) { HINT_GAP.toPx() }
    val room = imageBottom?.let { viewerHeight - navBottom - it }
    val inBand = room != null && hintHeight > 0 && room >= hintHeight + 2 * gap
    val text = @Composable { modifier: Modifier ->
        Text(
            "Tap for controls",
            fontFamily = PlexSans,
            fontSize = 12.sp,
            color = c.ink3,
            modifier = modifier,
        )
    }
    if (inBand) {
        val top = (imageBottom!! + (room!! - hintHeight) / 2).roundToInt()
        text(
            Modifier
                .align(Alignment.TopCenter)
                .offset { IntOffset(0, top) }
                .onSizeChanged { hintHeight = it.height },
        )
    } else {
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 40.dp)
                .alpha(if (waitForImage && imageBottom == null) 0f else 1f)
                .clip(RoundedCornerShape(16.dp))
                .background(c.surface2.copy(alpha = 0.88f))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            text(Modifier.onSizeChanged { hintHeight = it.height })
        }
    }
}

@Composable
private fun BarAction(icon: Int, iconDp: Int, label: String, onClick: () -> Unit) {
    val c = MaterialTheme.grove
    Row(
        Modifier
            .height(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = c.ink, modifier = Modifier.size(iconDp.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, fontFamily = PlexSans, fontSize = 14.sp, color = c.ink)
    }
}
