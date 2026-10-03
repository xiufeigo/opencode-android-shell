package com.xiufeigo.opencodeshell.ui.session.review

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiufeigo.opencodeshell.OpenCodeClient
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ui.markdown.CodeHighlight
import com.xiufeigo.opencodeshell.ui.markdown.OcMarkdown
import com.xiufeigo.opencodeshell.ui.theme.OcColors
import com.xiufeigo.opencodeshell.ui.theme.OcTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Base64

/** Preview/source toggle of `ArtifactMode` (`artifact-view.tsx:24`). */
private enum class ArtifactMode { PREVIEW, SOURCE }

/** Media facts the viewer learns from the decode, shown in the toolbar (`artifact-view.tsx:27`). */
private data class ArtifactInfo(val width: Int = 0, val height: Int = 0)

/**
 * Rich artifact viewer, the Compose port of upstream `session/files/artifact-view.tsx`
 * (v2 head `8ce629b`) mounted from `SessionFileView` (`file-tabs.tsx:455-478`).
 *
 * The wire input is the v1 `GET /file/content?path=` shape rebuilt from
 * `GET /api/fs/read/<rel>` ([OpenCodeClient.FileContent]); classification is
 * [FileArtifact.viewerKind]. Toolbar is upstream's `h-10` `ArtifactToolbar`
 * (`:153-192`): a Preview/Source `SegmentedControl` for `previewableKinds`,
 * the `formatBytes` meta, and the 「Open in app」 menu (`open-in-app-button.tsx`).
 *
 * Preview bodies map 1:1 to the kinds this host can render without new
 * dependencies — markdown → [OcMarkdown] (`ArtifactMarkdown`, `:332-349`),
 * image → a downsampled `BitmapFactory` fit (`ArtifactImage`, `:215-266`),
 * audio → a platform `MediaPlayer` over the spooled payload ([ArtifactAudio],
 * the `FileMedia` `<audio controls>` branch), csv/tsv → the `parseDelimited`
 * table (`ArtifactTable`, `:360-394`) and source → [FileCodeView] (Prism4j
 * spans, `file-tabs.tsx:406-446` code view). Video/pdf/font keep the upstream
 * `ArtifactBinary` placeholder (`:443-453`); SVG/HTML/mermaid open on their
 * source the way upstream falls back when the browser cannot decode.
 *
 * @param onOpenInApp toolbar action; see [OpenInApp.actionFor].
 * @param onCopyPath menu row, upstream `session.header.open.copyPath`.
 */
@Composable
fun ArtifactView(
    path: String,
    content: OpenCodeClient.FileContent,
    onOpenInApp: (String) -> Unit,
    onCopyPath: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val kind = FileArtifact.viewerKind(path, content.binary, content.mimeType)
    // `createEffect(on(() => props.content, …))` (`artifact-view.tsx:52-58`):
    // a new payload resets the mode and the measured media facts.
    var mode by remember(content) { mutableStateOf(ArtifactMode.PREVIEW) }
    var info by remember(content) { mutableStateOf(ArtifactInfo()) }
    var undecodable by remember(content) { mutableStateOf(false) }
    // A decode (image) or playback (audio) failure folds the payload onto the
    // binary placeholder. Only those two kinds render from the bytes
    // themselves; the remaining media kinds never try, so the flag stays off.
    val effective = if (undecodable &&
        (kind == FileArtifact.Kind.IMAGE || kind == FileArtifact.Kind.AUDIO)
    ) {
        FileArtifact.Kind.BINARY
    } else {
        kind
    }
    val previewable = FileArtifact.isPreviewable(effective) && effective != FileArtifact.Kind.BINARY
    val sizeLabel = remember(content) {
        FileArtifact.formatBytes(FileArtifact.contentBytes(content))
    }

    Column(modifier = modifier.fillMaxSize()) {
        ArtifactToolbar(
            mode = mode,
            onModeChange = if (previewable) { { mode = it } } else null,
            meta = buildList {
                if (info.width > 0 && info.height > 0) add("${info.width} × ${info.height}")
                add(sizeLabel)
            },
            path = path,
            content = content,
            kind = effective,
            onOpenInApp = onOpenInApp,
            onCopyPath = onCopyPath,
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(OcTheme.colors.borderMuted),
        )
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                previewable && mode == ArtifactMode.SOURCE -> FileCodeView(
                    path = path,
                    text = content.content,
                    modifier = Modifier.fillMaxSize(),
                )

                effective == FileArtifact.Kind.MARKDOWN -> Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    // `ArtifactMarkdown` (`:332-349`): the file body through the
                    // same renderer the timeline uses.
                    OcMarkdown(markdown = content.content)
                }

                effective == FileArtifact.Kind.TABLE -> ArtifactTable(
                    path = path,
                    text = content.content,
                    onInfo = { info = it },
                    modifier = Modifier.fillMaxSize(),
                )

                effective == FileArtifact.Kind.IMAGE -> ArtifactImage(
                    path = path,
                    content = content,
                    onInfo = { info = it },
                    onError = { undecodable = true },
                    modifier = Modifier.fillMaxSize(),
                )

                // A-13: audio gets a real player instead of the binary
                // placeholder (`FileMedia`'s `<audio controls>` branch,
                // `components/file-media.tsx`). `onError` folds a payload the
                // platform decoder refuses into the same placeholder the image
                // branch falls back to.
                effective == FileArtifact.Kind.AUDIO -> ArtifactAudio(
                    path = path,
                    content = content,
                    onError = { undecodable = true },
                    modifier = Modifier.fillMaxSize(),
                )

                effective == FileArtifact.Kind.BINARY ||
                    FileArtifact.isMedia(effective) -> ArtifactBinary(
                    path = path,
                    size = sizeLabel,
                    modifier = Modifier.fillMaxSize(),
                )

                else -> FileCodeView(
                    path = path,
                    text = content.content,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/**
 * `ArtifactToolbar` (`artifact-view.tsx:153-192`): Preview/Source segmented
 * control on the start, `·`-joined `formatBytes` meta and the open-in-app
 * split menu at the end.
 */
@Composable
private fun ArtifactToolbar(
    mode: ArtifactMode,
    onModeChange: ((ArtifactMode) -> Unit)?,
    meta: List<String>,
    path: String,
    content: OpenCodeClient.FileContent,
    kind: FileArtifact.Kind,
    onOpenInApp: (String) -> Unit,
    onCopyPath: (String) -> Unit,
) {
    val c = OcTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onModeChange != null) {
            ModeSegment(
                selected = mode == ArtifactMode.PREVIEW,
                label = stringResource(R.string.oc_file_view_preview),
                onClick = { onModeChange(ArtifactMode.PREVIEW) },
            )
            ModeSegment(
                selected = mode == ArtifactMode.SOURCE,
                label = stringResource(R.string.oc_file_view_source),
                onClick = { onModeChange(ArtifactMode.SOURCE) },
            )
        }
        Spacer(Modifier.weight(1f))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            meta.forEachIndexed { index, item ->
                if (index > 0) {
                    Text(
                        text = "·",
                        color = c.textFaint,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
                Text(
                    text = item,
                    color = c.textMuted,
                    fontSize = 12.sp,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            ArtifactOpenMenu(
                path = path,
                content = content,
                kind = kind,
                onOpenInApp = onOpenInApp,
                onCopyPath = onCopyPath,
            )
        }
    }
}

/** 13/500 pill of the upstream `SegmentedControl` pair (`artifact-view.tsx:163-172`). */
@Composable
private fun ModeSegment(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
) {
    val c = OcTheme.colors
    Box(
        modifier = Modifier
            .padding(end = 4.dp)
            .height(24.dp)
            .clip(OcTheme.shapes.sm)
            .background(if (selected) c.layer02 else Color.Transparent)
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) c.textBase else c.textMuted,
            fontSize = 13.sp,
            fontWeight = FontWeight.W500,
        )
    }
}

/**
 * `OpenInAppButton` (`open-in-app-button.tsx:11-62`) collapsed to Android's
 * two real rows: `session.header.openIn` (the [OpenInApp.Action] hand-off) and
 * `session.header.open.copyPath`.
 */
@Composable
private fun ArtifactOpenMenu(
    path: String,
    content: OpenCodeClient.FileContent,
    kind: FileArtifact.Kind,
    onOpenInApp: (String) -> Unit,
    onCopyPath: (String) -> Unit,
) {
    val c = OcTheme.colors
    var open by remember { mutableStateOf(false) }
    // Hoisted: `semantics {}` is not a composable scope. The label rides the
    // clickable Box, not the `Icon` inside it.
    val openMenuLabel = stringResource(R.string.oc_file_view_open_in_app)
    Box {
        Box(
            modifier = Modifier
                .padding(start = 4.dp)
                .size(28.dp)
                .clip(CircleShape)
                .clickable(role = Role.Button) { open = true }
                .semantics { contentDescription = openMenuLabel },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_oc_dots),
                contentDescription = null,
                tint = if (open) c.iconAccent else c.iconMuted,
                modifier = Modifier.size(14.dp),
            )
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            // v11 dark-surface sweep (see `DropdownMenuDarkSurfaceTest`): the M3
            // default `surfaceContainer` is a light translucent tint this shell
            // never re-themes, so `textBase` rows landed on a near-white panel in
            // the dark theme. Opaque `layer01` + zero tonal elevation + a
            // hairline edge is the pairing the other shell menus use.
            shape = RoundedCornerShape(6.dp),
            containerColor = c.layer01,
            tonalElevation = 0.dp,
            border = BorderStroke(0.5.dp, c.borderMuted),
        ) {
            DropdownMenuItem(
                text = {
                    Text(
                        text = stringResource(R.string.oc_file_view_open_in_app),
                        color = c.textBase,
                        fontSize = 13.sp,
                        lineHeight = 16.sp,
                    )
                },
                onClick = {
                    open = false
                    onOpenInApp(path)
                },
            )
            DropdownMenuItem(
                text = {
                    Text(
                        text = stringResource(R.string.oc_file_view_copy_path),
                        color = c.textBase,
                        fontSize = 13.sp,
                        lineHeight = 16.sp,
                    )
                },
                onClick = {
                    open = false
                    onCopyPath(path)
                },
            )
        }
    }
}

/**
 * `ArtifactImage` (`artifact-view.tsx:215-266`): fit the pane from the base64
 * payload of `GET /file/content` (`encoding="base64"`), sampled to
 * [IMAGE_TARGET_PX] like `AttachmentPreviewRow.decodePreview`. A decode
 * failure reports [onError] and the host falls back to the binary placeholder.
 */
@Composable
private fun ArtifactImage(
    path: String,
    content: OpenCodeClient.FileContent,
    onInfo: (ArtifactInfo) -> Unit,
    onError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    var bitmap by remember(content) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(content) {
        val decoded = withContext(Dispatchers.IO) { decodeImage(content) }
        if (decoded == null) {
            onError()
        } else {
            bitmap = decoded
            onInfo(ArtifactInfo(decoded.width, decoded.height))
        }
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(c.layer01)
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let { image ->
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = ReviewDiff.basename(path),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * `FileMedia`'s audio branch (`components/file-media.tsx`: the payload becomes a
 * `data:audio/…` url and is rendered as `<audio controls preload="metadata">`
 * centred in the pane) — ported to the platform [MediaPlayer], because Compose
 * has no audio element and the shell takes no new dependency.
 *
 * The wire payload is base64 (`FileArtifact.MEDIA` keeps media bytes encoded),
 * so the bytes are spooled to a cache file first: `MediaPlayer.setDataSource`
 * takes a path/descriptor, not a byte array. The file is deleted when the
 * viewer leaves or the payload changes, and the player is released on the same
 * disposals — a viewer that is torn down mid-playback must not keep an audio
 * focus.
 *
 * Transport: play/pause, a position readout over a seekable track (tap or drag)
 * and the duration, which is what upstream's native control bar shows. Any
 * failure — a decoder that refuses the payload, a spool write error — reports
 * [onError] so the host falls back to the binary placeholder exactly like the
 * image branch does.
 */
@Composable
private fun ArtifactAudio(
    path: String,
    content: OpenCodeClient.FileContent,
    onError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    val context = LocalContext.current
    var player by remember(content) { mutableStateOf<MediaPlayer?>(null) }
    var spooled by remember(content) { mutableStateOf<File?>(null) }
    var playing by remember(content) { mutableStateOf(false) }
    var durationMs by remember(content) { mutableStateOf(0) }
    var positionMs by remember(content) { mutableStateOf(0) }
    // Held while the user drags the track: the ticker must not fight the thumb.
    var seeking by remember(content) { mutableStateOf(false) }

    LaunchedEffect(content) {
        val prepared = withContext(Dispatchers.IO) { prepareAudio(context, path, content) }
        if (prepared == null) {
            onError()
            return@LaunchedEffect
        }
        spooled = prepared.file
        player = prepared.player
        durationMs = prepared.durationMs
    }
    // `DisposableEffect` on the *value*, not on `Unit`: the effect has to own the
    // instance it releases, and the key change is exactly the hand-off.
    DisposableEffect(player) {
        val owned = player
        onDispose { owned?.release() }
    }
    DisposableEffect(spooled) {
        val owned = spooled
        onDispose { owned?.delete() }
    }
    // Position ticker: only while playing, and paused while the user drags.
    LaunchedEffect(playing, player) {
        val active = player
        if (!playing || active == null) return@LaunchedEffect
        while (true) {
            delay(AUDIO_TICK_MS)
            if (!seeking) {
                positionMs = runCatching { active.currentPosition }.getOrDefault(positionMs)
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(c.layer01)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = ReviewDiff.basename(path),
            color = c.textBase,
            fontSize = 14.sp,
            fontWeight = FontWeight.W500,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            modifier = Modifier.padding(top = 16.dp).widthIn(max = AUDIO_PLAYER_MAX_WIDTH),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(c.layer02)
                    .clickable(enabled = player != null, role = Role.Button) {
                        val active = player ?: return@clickable
                        if (playing) {
                            runCatching { active.pause() }
                            playing = false
                        } else {
                            runCatching { active.start() }
                            playing = true
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                AudioTransportGlyph(
                    playing = playing,
                    tint = if (player == null) c.iconMuted.copy(alpha = 0.4f) else c.iconBase,
                )
            }
            Column(Modifier.weight(1f)) {
                AudioSeekBar(
                    positionMs = positionMs,
                    durationMs = durationMs,
                    enabled = player != null && durationMs > 0,
                    onSeek = { target ->
                        positionMs = target
                        runCatching { player?.seekTo(target) }
                    },
                    onSeekStart = { seeking = true },
                    onSeekEnd = { seeking = false },
                )
                Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                    Text(
                        text = audioTimeLabel(positionMs),
                        color = c.textFaint,
                        fontFamily = OcTheme.monoFont,
                        fontSize = 11.sp,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = audioTimeLabel(durationMs),
                        color = c.textFaint,
                        fontFamily = OcTheme.monoFont,
                        fontSize = 11.sp,
                    )
                }
            }
        }
    }
}

/**
 * The `▶` / `❙❙` glyph of [ArtifactAudio], drawn instead of shipped as a
 * drawable: `res/drawable` is outside this wave's file scope and the two shapes
 * are a triangle and two bars.
 */
@Composable
private fun AudioTransportGlyph(playing: Boolean, tint: Color) {
    Canvas(modifier = Modifier.size(14.dp)) {
        if (playing) {
            val bar = size.width * 0.28f
            drawRect(tint, topLeft = Offset(0f, 0f), size = Size(bar, size.height))
            drawRect(tint, topLeft = Offset(size.width - bar, 0f), size = Size(bar, size.height))
        } else {
            val path = Path().apply {
                moveTo(size.width * 0.15f, 0f)
                lineTo(size.width, size.height / 2f)
                lineTo(size.width * 0.15f, size.height)
                close()
            }
            drawPath(path, tint)
        }
    }
}

/**
 * The seek track: a 3 dp bar inside a 20 dp touch strip, filled to [positionMs]
 * over [durationMs]. Tap and horizontal drag both seek (`detectTapGestures` +
 * `detectHorizontalDragGestures`), which is the minimum a native control bar
 * offers and what Compose's `Slider` would need the M3 colour set for.
 */
@Composable
private fun AudioSeekBar(
    positionMs: Int,
    durationMs: Int,
    enabled: Boolean,
    onSeek: (Int) -> Unit,
    onSeekStart: () -> Unit,
    onSeekEnd: () -> Unit,
) {
    val c = OcTheme.colors
    var widthPx by remember { mutableStateOf(1f) }
    val fraction = if (durationMs > 0) {
        (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    fun targetAt(x: Float): Int =
        (x.coerceIn(0f, widthPx) / widthPx * durationMs.toFloat()).toInt().coerceIn(0, durationMs)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(AUDIO_SEEK_TOUCH_HEIGHT)
            .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(durationMs, enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { offset -> onSeek(targetAt(offset.x)) }
            }
            .pointerInput(durationMs, enabled) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { onSeekStart() },
                    onDragEnd = { onSeekEnd() },
                    onDragCancel = { onSeekEnd() },
                ) { change, _ ->
                    change.consume()
                    onSeek(targetAt(change.position.x))
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(AUDIO_TRACK_HEIGHT)
                .clip(RoundedCornerShape(999.dp))
                .background(c.layer03),
        )
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .height(AUDIO_TRACK_HEIGHT)
                .clip(RoundedCornerShape(999.dp))
                .background(if (enabled) c.iconBase else c.iconMuted.copy(alpha = 0.4f)),
        )
    }
}

/**
 * One spooled payload: the cache file holding the decoded bytes, the prepared
 * player over it, and the duration `prepare()` measured.
 */
private class PreparedAudio(val player: MediaPlayer, val file: File, val durationMs: Int)

/**
 * base64 (or plain text, defensively) → cache file → prepared [MediaPlayer];
 * `null` when the payload is empty, the write fails or the platform decoder
 * refuses it. Runs on a worker (the caller's `withContext(Dispatchers.IO)`):
 * `prepare()` blocks on the file and a real audio file can take a moment.
 */
private fun prepareAudio(
    context: Context,
    path: String,
    content: OpenCodeClient.FileContent,
): PreparedAudio? {
    val bytes = runCatching {
        if (content.encoding == "base64") {
            Base64.getDecoder().decode(content.content)
        } else {
            content.content.toByteArray(Charsets.UTF_8)
        }
    }.getOrNull() ?: return null
    if (bytes.isEmpty()) return null
    val suffix = FileArtifact.extension(path).ifEmpty { "bin" }
    val file = runCatching {
        File.createTempFile("oc-audio-", ".$suffix", context.cacheDir).also { it.writeBytes(bytes) }
    }.getOrNull() ?: return null
    val player = MediaPlayer()
    return try {
        player.setDataSource(file.absolutePath)
        player.prepare()
        PreparedAudio(player, file, player.duration.coerceAtLeast(0))
    } catch (_: Exception) {
        runCatching { player.release() }
        file.delete()
        null
    }
}

/**
 * `m:ss` of a millisecond offset — the readout under [ArtifactAudio]'s track.
 * Negative and overflowing inputs clamp to `0:00` rather than rendering a sign.
 *
 * Pure, so it is covered by a JVM test.
 */
internal fun audioTimeLabel(ms: Int): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

/** `max-w-xl` on upstream's `<audio class="w-full max-w-xl">`. */
private val AUDIO_PLAYER_MAX_WIDTH = 560.dp

/** The seek bar's touch strip (the visible track is [AUDIO_TRACK_HEIGHT]). */
private val AUDIO_SEEK_TOUCH_HEIGHT = 20.dp

/** The visible seek track. */
private val AUDIO_TRACK_HEIGHT = 3.dp

/** Position poll interval while playing. */
private const val AUDIO_TICK_MS = 200L

/**
 * `ArtifactTable` (`artifact-view.tsx:360-394`): `parseDelimited` grid with a
 * numbered index column and the `file.view.table.truncated` footer.
 */
@Composable
private fun ArtifactTable(
    path: String,
    text: String,
    onInfo: (ArtifactInfo) -> Unit,
    modifier: Modifier = Modifier,
) {
    val parsed = remember(text, path) {
        FileArtifact.parseDelimited(text, FileArtifact.tableDelimiter(path))
    }
    LaunchedEffect(parsed) { onInfo(ArtifactInfo()) }
    val header = parsed.rows.firstOrNull().orEmpty()
    val body = parsed.rows.drop(1)
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
    ) {
        Row(Modifier.fillMaxWidth()) {
            TableHeaderCell("")
            for (column in 0 until parsed.columns) {
                TableHeaderCell(header.getOrElse(column) { "" })
            }
        }
        body.forEachIndexed { index, row ->
            Row(Modifier.fillMaxWidth()) {
                TableCell("${index + 1}", faint = true)
                for (column in 0 until parsed.columns) {
                    TableCell(row.getOrElse(column) { "" }, faint = false)
                }
            }
        }
        if (parsed.total > parsed.rows.size) {
            Text(
                text = stringResource(
                    R.string.oc_file_view_table_truncated,
                    (parsed.rows.size - 1).coerceAtLeast(0),
                    (parsed.total - 1).coerceAtLeast(0),
                ),
                color = OcTheme.colors.textMuted,
                fontSize = 12.sp,
                modifier = Modifier.padding(12.dp),
            )
        }
    }
}

@Composable
private fun TableHeaderCell(text: String) {
    val c = OcTheme.colors
    Text(
        text = text,
        color = c.textBase,
        fontSize = 13.sp,
        fontWeight = FontWeight.W500,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .widthIn(min = 64.dp)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun TableCell(text: String, faint: Boolean) {
    val c = OcTheme.colors
    Text(
        text = text,
        color = if (faint) c.textFaint else c.textBase,
        fontSize = 13.sp,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .widthIn(min = 64.dp)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/**
 * Source / plain-text body: mono 11/16 both-axes scroll, Prism4j colours of
 * [CodeHighlight] (the `file-tabs.tsx:406-446` code view) and the
 * [MAX_PREVIEW_LINES] / [MAX_LINE_CHARS] caps of the old preview so a huge
 * file cannot stall the panel.
 */
@Composable
fun FileCodeView(
    path: String,
    text: String,
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    val language = remember(path) { FileArtifact.extension(path).ifEmpty { null } }
    val lines = remember(text) {
        val all = text.split('\n')
        PreviewLines(
            lines = all.take(MAX_PREVIEW_LINES).map { it.take(MAX_LINE_CHARS) },
            truncated = all.size > MAX_PREVIEW_LINES,
        )
    }
    val body = remember(lines) { lines.lines.joinToString("\n") }
    val annotated = remember(body, language, c) {
        highlightSource(body, language, c)
    }
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
    ) {
        Text(
            text = annotated,
            color = c.textBase,
            fontFamily = OcTheme.monoFont,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        if (lines.truncated) {
            Text(
                text = stringResource(R.string.oc_review_truncated),
                color = c.textFaint,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}

/**
 * `highlightAnnotated` of `MarkdownRenderer.kt:427-448` for a whole file body:
 * [CodeHighlight.tokenize] spans get the v2 syntax ramp, gaps stay body colour.
 */
private fun highlightSource(
    code: String,
    language: String?,
    c: OcColors,
): AnnotatedString {
    if (code.isEmpty()) return AnnotatedString("")
    val spans = CodeHighlight.tokenize(code, language)
    if (spans.isEmpty()) return AnnotatedString(code)
    return buildAnnotatedString {
        var cursor = 0
        for (span in spans) {
            if (span.start > cursor) append(code.substring(cursor, span.start))
            withStyle(SpanStyle(color = syntaxColor(span.kind, c))) {
                append(code.substring(span.start, span.end))
            }
            cursor = span.end
        }
        if (cursor < code.length) append(code.substring(cursor))
    }
}

/** `OcMarkdownPalette.highlight` (`MarkdownRenderer.kt:174-182`) on the raw tokens. */
private fun syntaxColor(kind: CodeHighlight.Kind, c: OcColors): Color =
    when (kind) {
        CodeHighlight.Kind.KEYWORD -> c.syntaxKeyword
        CodeHighlight.Kind.STRING -> c.syntaxString
        CodeHighlight.Kind.CONSTANT -> c.syntaxConstant
        CodeHighlight.Kind.PRIMITIVE -> c.syntaxPrimitive
        CodeHighlight.Kind.PROPERTY -> c.syntaxProperty
        CodeHighlight.Kind.TYPE -> c.syntaxType
        CodeHighlight.Kind.COMMENT -> c.syntaxComment
    }

/**
 * `ArtifactBinary` (`artifact-view.tsx:443-453`): file icon + name +
 * `file.view.binary` with the toolbar's `formatBytes` size.
 */
@Composable
private fun ArtifactBinary(
    path: String,
    size: String,
    modifier: Modifier = Modifier,
) {
    val c = OcTheme.colors
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_oc_file),
            contentDescription = null,
            tint = c.iconMuted,
            modifier = Modifier.size(32.dp),
        )
        Text(
            text = ReviewDiff.basename(path),
            color = c.textBase,
            fontSize = 14.sp,
            fontWeight = FontWeight.W500,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = stringResource(R.string.oc_review_binary),
            color = c.textMuted,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            text = size,
            color = c.textFaint,
            fontFamily = OcTheme.monoFont,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/**
 * Base64 → downsampled bitmap (`AttachmentPreviewRow.decodePreview`'s sampling
 * walk at [IMAGE_TARGET_PX]); null when the payload is not a decodable raster
 * (an SVG's text, a truncated transfer).
 */
private fun decodeImage(content: OpenCodeClient.FileContent): Bitmap? = try {
    val payload = content.content
    val bytes = if (content.encoding == "base64") {
        Base64.getDecoder().decode(payload)
    } else {
        payload.toByteArray(Charsets.UTF_8)
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        null
    } else {
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= IMAGE_TARGET_PX) sample *= 2
        BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }
} catch (_: Exception) {
    null
}

private class PreviewLines(val lines: List<String>, val truncated: Boolean)

/** Preview body caps — see [FileCodeView]. */
private const val MAX_PREVIEW_LINES = 400
private const val MAX_LINE_CHARS = 500

/** Longest edge the image preview decodes at (`AttachmentPreviewRow.PREVIEW_TARGET_PX`). */
private const val IMAGE_TARGET_PX = 1024
