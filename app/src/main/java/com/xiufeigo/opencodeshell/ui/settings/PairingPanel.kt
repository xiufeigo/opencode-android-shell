package com.xiufeigo.opencodeshell.ui.settings

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.Image
import android.media.ImageReader
import android.net.Uri
import android.widget.Toast
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.xiufeigo.opencodeshell.R
import com.xiufeigo.opencodeshell.ServerConfigStore
import com.xiufeigo.opencodeshell.ui.servers.QrScan
import com.xiufeigo.opencodeshell.ui.theme.OcTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * `settings/pairing/pairing.tsx` (v2 head), reduced to what this shell can do
 * without a camera library: the connection details of the active server as a
 * copyable `/connect#…` link plus the `DialogPairing` QR block (`renderSVG(uqr)`
 * → [PairingModel.qrModules] → bitmap here), and the manual-entry counterpart
 * (`decodePairingScan` over [PairingModel]), so a code from `opencode pair` can
 * be consumed without a camera.
 *
 * Scanning has three entries, all ending at the same `decodePairingScan`:
 * - **A-12** the in-app preview (`servers/connect/scanner.tsx`): a platform
 *   `camera2` preview + `zxing:core` decode, [CameraScanOverlay] — no camera
 *   library, and the entry requests `android.permission.CAMERA` first;
 * - the device's scanner app through [com.xiufeigo.opencodeshell.ui.servers.QrScan]
 *   (none installed → `oc_pair_scan_unavailable`), kept as the fallback for a
 *   device with no camera or a refused permission;
 * - a picked photo, decoded in-app with `zxing:core`
 *   ([QrScan.decodeQrFromPixels]).
 *
 * Pairing details come from the active `ServerConfigStore` entry (the same
 * `username`/`password` the connect form collects from `opencode pair`).
 * **Empty state, checked against upstream:** `settings/pairing/pairing.tsx`
 * has no "unavailable" sentence — `SettingsPairing` returns `null` without
 * `platform.pair`, the "Show details" button is `disabled` without a
 * non-local `localHost()`, and `DialogPairing` keeps its body inside
 * `<Show when={props.info}>`; the only failure copy is `pair.error`.
 * `oc_pair_unavailable` is a documented Android deviation: the settings nav
 * always reaches this panel, so an honest sentence beats upstream's silent
 * empty body.
 */
@Composable
fun PairingPanel() {
    val c = OcTheme.colors
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    // Read-only handle on the shared server prefs — same fallback as
    // `ui/terminal/TerminalPanel.kt`: this panel is not handed the host store.
    val serverStore = remember(context) { ServerConfigStore(context).also { it.restore() } }
    var codeInput by remember { mutableStateOf("") }
    var copied by remember { mutableStateOf(false) }
    var scanUnavailable by remember { mutableStateOf(false) }
    var photoFailed by remember { mutableStateOf(false) }
    var pickedImage by remember { mutableStateOf<Uri?>(null) }
    // A-12: the in-app preview (`servers/connect/scanner.tsx`). `cameraNotice`
    // carries the one failure line the entry row shows after a refusal or a
    // camera that could not be opened; it is cleared on every new attempt.
    var cameraOpen by remember { mutableStateOf(false) }
    var cameraNotice by remember { mutableStateOf<Int?>(null) }

    val server = serverStore.server
        ?.takeIf { it.isComplete }
        ?.takeIf { !it.password.isNullOrEmpty() }
    val pairingUrl = server?.let { PairingModel.pairingUrl(it.password.orEmpty(), it.url) }
    LaunchedEffect(copied) {
        if (!copied) return@LaunchedEffect
        kotlinx.coroutines.delay(2_000)
        copied = false
    }

    val scanLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val payload = QrScan.resultFrom(result.data) ?: return@rememberLauncherForActivityResult
        // Scan and paste share the one decode path below.
        codeInput = payload
        copied = false
        scanUnavailable = false
    }
    val photoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        pickedImage = QrScan.imageUriFrom(result.data)
    }
    // A-12: `camera` is a dangerous permission, so the preview opens only after
    // the grant. A device whose manifest does not declare it answers `false`
    // immediately — the row then shows the refusal line instead of a black
    // screen.
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            cameraNotice = null
            cameraOpen = true
        } else {
            cameraNotice = R.string.oc_pair_scan_camera_denied
        }
    }
    LaunchedEffect(pickedImage) {
        val uri = pickedImage ?: return@LaunchedEffect
        val payload = withContext(Dispatchers.IO) { QrScan.decodeImage(context, uri) }
        pickedImage = null
        if (payload == null) {
            photoFailed = true
        } else {
            // Photo and camera scans share the one decode path below.
            codeInput = payload
            copied = false
            photoFailed = false
        }
    }

    // A-12: the live preview **replaces** the page while it is open. A settings
    // panel cannot host a full-bleed camera surface as a child of its scrolling
    // column, and the shell's dialog stack belongs to `ui/overlay`, so the
    // pairing page itself is the surface (the close action returns to it).
    if (cameraOpen) {
        CameraScanOverlay(
            onDecoded = { payload ->
                cameraOpen = false
                codeInput = payload
                copied = false
                photoFailed = false
            },
            onFailed = { resId ->
                cameraOpen = false
                cameraNotice = resId
            },
            onClose = { cameraOpen = false },
        )
        return
    }

    // Single scroll for the whole page (header included): the shared
    // fixed-header + inner-scroll split leaves the QR in a ~0dp scroll region
    // on short windows (≈400dp dialog — the header's top:40/bottom:32 insets
    // eat the height while the layer still fits the viewport, so the host's
    // over-viewport fallback never engages). Here the header scrolls away with
    // the content and the QR is always reachable; on normal windows the content
    // fits and nothing scrolls, matching the old layout.
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SettingsPanelHeader(
            titleResId = SettingsTab.PAIRING.headerTitleResId(),
            below = SettingsTab.PAIRING.pageDescriptionResId()
                ?.let { { OcBodyText(stringResource(it), muted = true) } },
        )
        SettingsPanelBody(scroll = false) {
            SettingsSectionBlock(R.string.oc_pair_connection) {
                OcBodyText(stringResource(R.string.oc_pair_local_description), muted = true)
                Spacer(Modifier.height(12.dp))
                if (pairingUrl == null) {
                    OcBodyText(stringResource(R.string.oc_pair_unavailable), muted = true)
                } else {
                    Text(
                        text = pairingUrl,
                        color = c.textBase,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OcButton(
                            label = stringResource(if (copied) R.string.oc_md_copied else R.string.oc_pair_copy),
                            primary = false,
                            onClick = {
                                if (copyPairingDetails(clipboard, pairingUrl)) {
                                    copied = true
                                    // v40 tD_34: the button label flipped to
                                    // "Copied" for 2 s and that was the only
                                    // feedback, so a tap looked like a no-op
                                    // (the tester screenshotted after the label
                                    // reverted). The toast is the confirmation.
                                    Toast.makeText(
                                        context,
                                        R.string.oc_copy_details_copied,
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            },
                        )
                    }
                    if (copied) {
                        Spacer(Modifier.height(6.dp))
                        OcBodyText(stringResource(R.string.oc_copy_details_copied), muted = true)
                    }
                    Spacer(Modifier.height(8.dp))
                    PairingQrImage(content = pairingUrl)
                }
                Spacer(Modifier.height(12.dp))
                // A-12: the in-app preview (`servers/connect/scanner.tsx`) is
                // the first entry now; the system scanner stays below it as the
                // fallback for a device with no camera or a refused permission.
                ScanCameraRow(
                    onScan = {
                        cameraNotice = null
                        if (hasCameraPermission(context)) {
                            cameraOpen = true
                        } else {
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    },
                    noticeResId = cameraNotice,
                )
                ScanEntryRow(
                    onScan = {
                        try {
                            scanLauncher.launch(QrScan.scanIntent())
                        } catch (_: ActivityNotFoundException) {
                            scanUnavailable = true
                        }
                    },
                    unavailable = scanUnavailable,
                )
                PhotoPickRow(
                    onPick = {
                        photoFailed = false
                        photoLauncher.launch(QrScan.pickImageIntent())
                    },
                    failed = photoFailed,
                )
            }

            SettingsSectionBlock(R.string.oc_pair_manual_title) {
                OcBodyText(stringResource(R.string.oc_pair_manual_description), muted = true)
                Spacer(Modifier.height(12.dp))
                OcTextField(
                    value = codeInput,
                    onValueChange = {
                        codeInput = it
                        copied = false
                    },
                    placeholder = stringResource(R.string.oc_pair_manual_placeholder),
                )
                val decoded = codeInput.trim().takeIf { it.isNotEmpty() }?.let(PairingModel::decodePairingScan)
                Spacer(Modifier.height(8.dp))
                when {
                    codeInput.isBlank() -> Unit
                    decoded == null -> Text(
                        text = stringResource(R.string.oc_pair_invalid),
                        color = c.textAccent,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                    )

                    else -> {
                        Text(
                            text = stringResource(R.string.oc_pair_manual_result, decoded.urls.first()),
                            color = c.textBase,
                            fontSize = 13.sp,
                            lineHeight = 20.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(4.dp))
                        OcBodyText(stringResource(R.string.oc_pair_manual_password), muted = true)
                    }
                }
            }
        }
    }
}

/**
 * `DialogPairing`'s QR block: `renderSVG(url, { border: 4, blackColor:
 * "currentColor", whiteColor: "transparent" })` in a `bg-base` square with
 * 24 px padding and `pair.qr` as the image label.
 *
 * The square is width-capped: an uncapped `fillMaxWidth + aspectRatio(1f)`
 * made the code taller than the visible viewport (URL + Copy filled the
 * screen, the QR sat a full scroll below), so it read as missing entirely.
 */
@Composable
private fun PairingQrImage(content: String) {
    val dark = OcTheme.colors.textBase.toArgb()
    val modules = remember(content) { PairingModel.qrModules(content) }
    val bitmap = remember(modules, dark) { qrBitmap(modules, dark) }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .widthIn(max = PAIRING_QR_MAX_WIDTH)
                .fillMaxWidth()
                .aspectRatio(1f)
                .background(OcTheme.colors.bgBase, RoundedCornerShape(6.dp))
                .padding(24.dp),
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.oc_pair_qr),
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.None,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** QR edge cap so the code renders inside the visible viewport. */
private val PAIRING_QR_MAX_WIDTH = 240.dp

/** One pixel per module — `Image` scales it with `FilterQuality.None`. */
private fun qrBitmap(modules: Array<BooleanArray>, dark: Int): Bitmap {
    val bitmap = Bitmap.createBitmap(modules[0].size, modules.size, Bitmap.Config.ARGB_8888)
    for (y in modules.indices) {
        for (x in modules[y].indices) {
            bitmap.setPixel(x, y, if (modules[y][x]) dark else android.graphics.Color.TRANSPARENT)
        }
    }
    return bitmap
}

/** The `server.connect.scan` entry: hands the scan to the system scanner ([QrScan]). */
@Composable
private fun ScanEntryRow(onScan: () -> Unit, unavailable: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onScan)
            .padding(vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(R.string.oc_pair_scan),
                color = OcTheme.colors.textBase,
                fontSize = 13.sp,
                fontWeight = FontWeight(530),
            )
            // `server.connect.scan.description`, or the scanner-app analog of
            // `server.connect.camera.unavailable` after a failed launch.
            OcBodyText(
                stringResource(
                    if (unavailable) R.string.oc_pair_scan_unavailable
                    else R.string.oc_pair_scan_description,
                ),
                muted = true,
            )
        }
    }
}

/**
 * The in-app decode entry (Android analog of `scanner.tsx` without a camera):
 * pick an image and run it through [QrScan.decodeQrFromPixels].
 */
@Composable
private fun PhotoPickRow(onPick: () -> Unit, failed: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onPick)
            .padding(vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(R.string.oc_pair_scan_photo),
                color = OcTheme.colors.textBase,
                fontSize = 13.sp,
                fontWeight = FontWeight(530),
            )
            OcBodyText(
                stringResource(
                    if (failed) R.string.oc_pair_scan_photo_failed
                    else R.string.oc_pair_scan_photo_description,
                ),
                muted = true,
            )
        }
    }
}

/**
 * A-12: the in-app camera entry (`servers/connect/scanner.tsx`'s scan button).
 * The description line is replaced by the last failure — a refused permission, a
 * device with no camera, or a preview that could not be opened — so the row
 * never reads "ready" while it cannot scan.
 */
@Composable
private fun ScanCameraRow(onScan: () -> Unit, noticeResId: Int?) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onScan)
            .padding(vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(R.string.oc_pair_scan_camera),
                color = OcTheme.colors.textBase,
                fontSize = 13.sp,
                fontWeight = FontWeight(530),
            )
            OcBodyText(
                stringResource(noticeResId ?: R.string.oc_pair_scan_camera_description),
                muted = true,
            )
        }
    }
}

/** `checkSelfPermission` without the androidx-activity dependency's own helper. */
private fun hasCameraPermission(context: Context): Boolean =
    context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

/**
 * A-12: the in-app scanner — upstream `servers/connect/scanner.tsx` (a
 * `qr-scanner` preview with `preferredCamera: "environment"`, inversion mode
 * `both`), rebuilt on the platform `camera2` API and the `zxing:core` decoder
 * this shell already ships, so the shell still takes no camera dependency.
 *
 * Shape: a full-bleed preview inside the panel (an overlay rather than a
 * `ui.window.Dialog` — the dialog stack belongs to the shell, and this surface
 * only ever covers the pairing page), a 1:1 reticle line, the hint copy, and a
 * close action. A decode hands the raw payload to [onDecoded]; the caller runs
 * it through the same `PairingModel.decodePairingScan` every other entry uses.
 *
 * Every failure path reports through [onFailed] and closes: a missing camera, a
 * camera that refuses to open (a `SecurityException` when the permission is not
 * declared in the manifest included), and a configure failure.
 */
@Composable
private fun CameraScanOverlay(
    onDecoded: (String) -> Unit,
    onFailed: (Int) -> Unit,
    onClose: () -> Unit,
) {
    val c = OcTheme.colors
    var controller by remember { mutableStateOf<QrCameraController?>(null) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).also { view ->
                    controller = QrCameraController(
                        context = ctx,
                        preview = view,
                        onDecoded = onDecoded,
                        onFailure = { onFailed(R.string.oc_pair_scan_camera_failed) },
                    ).also { it.start() }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        // `scanner.tsx`'s reticle: one hairline through the middle of the frame.
        Box(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.8f)
                .height(0.5.dp)
                .background(c.accent),
        )
        Text(
            text = stringResource(R.string.oc_pair_scan_camera_description),
            color = Color.White,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 24.dp, start = 24.dp, end = 24.dp),
        )
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp),
        ) {
            OcButton(
                label = stringResource(R.string.oc_common_close),
                primary = false,
                onClick = onClose,
            )
        }
    }
    // The camera device, the capture session and the analysis thread are native
    // resources: leaving the overlay (decode, close, panel teardown) releases
    // all three.
    DisposableEffect(Unit) {
        onDispose { controller?.release() }
    }
}

/**
 * The `camera2` half of [CameraScanOverlay]: one `TEMPLATE_PREVIEW` repeating
 * request whose outputs are the preview `TextureView` and a small
 * `YUV_420_888` [ImageReader]; every analysis frame is handed to
 * [QrScan.decodeQrFromPixels] on the camera's own thread.
 *
 * Throttling: a frame whose decode is still running is dropped
 * ([decoding] gate), so a slow zxing pass costs frames instead of queueing
 * them. The first successful decode wins and closes the loop — the caller
 * navigates away with the payload.
 */
private class QrCameraController(
    private val context: Context,
    private val preview: TextureView,
    private val onDecoded: (String) -> Unit,
    private val onFailure: () -> Unit,
) : TextureView.SurfaceTextureListener {

    private val thread = HandlerThread("oc-qr-camera").apply { start() }
    private val handler = Handler(thread.looper)
    private val decoding = AtomicBoolean(false)
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var finished = false

    fun start() {
        preview.surfaceTextureListener = this
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        open(width, height)
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        release()
        return true
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

    private fun open(previewWidth: Int, previewHeight: Int) {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        if (manager == null) return onFailure()
        val cameraId = runCatching {
            manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
            } ?: manager.cameraIdList.firstOrNull()
        }.getOrNull() ?: return onFailure()
        val size = analysisSize(manager, cameraId)
        val imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
        imageReader.setOnImageAvailableListener({ source -> source.acquireLatestImage()?.let(::onFrame) }, handler)
        reader = imageReader
        // The preview buffer must match the view, otherwise the Surface is
        // rejected by `createCaptureSession`.
        preview.surfaceTexture?.setDefaultBufferSize(previewWidth, previewHeight)
        val surfaces = listOf(Surface(preview.surfaceTexture), imageReader.surface)
        try {
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(surfaces[0])
                        addTarget(surfaces[1])
                    }
                    camera.createCaptureSession(
                        surfaces,
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(configured: CameraCaptureSession) {
                                session = configured
                                runCatching { configured.setRepeatingRequest(request.build(), null, handler) }
                                    .onFailure { onFailure() }
                            }

                            override fun onConfigureFailed(failed: CameraCaptureSession) = onFailure()
                        },
                        handler,
                    )
                }

                override fun onDisconnected(camera: CameraDevice) {
                    runCatching { camera.close() }
                    device = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    runCatching { camera.close() }
                    device = null
                    onFailure()
                }
            }, handler)
        } catch (_: SecurityException) {
            // The manifest does not declare `android.permission.CAMERA`, or the
            // grant was revoked between the check and the open.
            onFailure()
        } catch (_: Exception) {
            onFailure()
        }
    }

    private fun onFrame(image: Image) {
        if (finished || !decoding.compareAndSet(false, true)) {
            image.close()
            return
        }
        try {
            val plane = image.planes.firstOrNull() ?: return
            val pixels = yPlaneToArgb(
                buffer = plane.buffer,
                width = image.width,
                height = image.height,
                rowStride = plane.rowStride,
                pixelStride = plane.pixelStride,
            )
            // Inverted pass included, like upstream `scanner.setInversionMode("both")`.
            val payload = QrScan.decodeQrFromPixels(pixels, image.width, image.height)
            if (payload != null) {
                finished = true
                onDecoded(payload)
            }
        } catch (_: Exception) {
            // A malformed frame is not a failure of the scan — the next one is
            // already on its way.
        } finally {
            image.close()
            decoding.set(false)
        }
    }

    /** Idempotent: called from the surface listener, the close button and the dispose. */
    fun release() {
        runCatching { session?.close() }
        session = null
        runCatching { device?.close() }
        device = null
        runCatching { reader?.close() }
        reader = null
        thread.quitSafely()
    }

    /** Smallest supported analysis size at or under [ANALYSIS_MAX_EDGE], else the smallest there is. */
    private fun analysisSize(manager: CameraManager, cameraId: String): Size {
        val sizes = runCatching {
            manager.getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.YUV_420_888)
                ?.toList()
                .orEmpty()
        }.getOrDefault(emptyList())
        return sizes.filter { maxOf(it.width, it.height) <= ANALYSIS_MAX_EDGE }
            .maxByOrNull { it.width * it.height }
            ?: sizes.minByOrNull { it.width * it.height }
            ?: Size(ANALYSIS_MAX_EDGE, ANALYSIS_MAX_EDGE)
    }
}

/**
 * The luma plane of a `YUV_420_888` frame as ARGB ints, which is what zxing's
 * [com.google.zxing.RGBLuminanceSource] takes: `(r+g+b)/3` of an equal-channel
 * pixel *is* the luma, so the plane is copied straight across without a
 * YUV→RGB conversion.
 *
 * [rowStride] and [pixelStride] come from the plane itself — a padded row or a
 * subsampled plane would otherwise shear the frame. Pure (no `Image`), so the
 * stride walk is pinned by a JVM test.
 */
internal fun yPlaneToArgb(
    buffer: ByteBuffer,
    width: Int,
    height: Int,
    rowStride: Int,
    pixelStride: Int,
): IntArray {
    val pixels = IntArray(width * height)
    if (width <= 0 || height <= 0 || rowStride <= 0 || pixelStride <= 0) return pixels
    val row = ByteArray(rowStride)
    var index = 0
    for (y in 0 until height) {
        val start = y * rowStride
        if (start >= buffer.limit()) break
        buffer.position(start)
        val count = minOf(rowStride, buffer.remaining())
        buffer.get(row, 0, count)
        var x = 0
        var offset = 0
        while (x < width && offset < count) {
            val luma = row[offset].toInt() and 0xFF
            pixels[index++] = (0xFF shl 24) or (luma shl 16) or (luma shl 8) or luma
            x++
            offset += pixelStride
        }
    }
    return pixels
}

/** Longest analysis edge; a QR code needs far less than a full-sensor frame. */
private const val ANALYSIS_MAX_EDGE = 720

/**
 * Writes the pairing details to the clipboard and reports whether there was
 * anything to write: the caller only shows the confirmation when this returns
 * true, so an empty details block cannot claim a copy that never happened.
 */
internal fun copyPairingDetails(clipboard: ClipboardManager, url: String): Boolean {
    if (url.isEmpty()) return false
    clipboard.setText(AnnotatedString(url))
    return true
}
