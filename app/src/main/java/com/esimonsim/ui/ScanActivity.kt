package com.esimonsim.ui

import android.Manifest
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color.TRANSPARENT
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import android.provider.Settings
import android.view.Surface
import android.view.TextureView
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.esimonsim.R

/** What [ScanActivity] hands back: the QR code's text, or a message to show instead; neither when people close it. */
class ScanOutcome(val text: String?, val error: String?)

/** Opens [ScanActivity]. */
object ScanContract : ActivityResultContract<Unit, ScanOutcome>() {
    override fun createIntent(context: Context, input: Unit) = Intent(context, ScanActivity::class.java)

    override fun parseResult(resultCode: Int, intent: Intent?) = ScanOutcome(
        intent?.getStringExtra(ScanActivity.EXTRA_TEXT)?.takeIf { resultCode == Activity.RESULT_OK },
        intent?.getStringExtra(ScanActivity.EXTRA_ERROR))
}

/** A scan with the app's own scanner: [onResult] gets the code's text, [onError] a message to show. Closing the
 *  scanner calls neither. */
@Composable
fun rememberScanActivity(onResult: (String) -> Unit, onError: (String) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ScanContract) { r -> r.text?.let(onResult) ?: r.error?.let(onError) }
    return remember(launcher) { { launcher.launch(Unit) } }
}

/** The app's own QR scanner: the camera picture across the whole screen, a square to aim with, and the first QR
 *  code read handed back ([ScanContract]). It asks for the camera itself; the camera work is in [ScanCamera].
 *  It handles turning and resizing itself (configChanges in the manifest), so those don't reopen the camera. */
class ScanActivity : ComponentActivity() {
    private lateinit var camera: ScanCamera
    private val main = Handler(Looper.getMainLooper())
    private val cameras by lazy { getSystemService(CameraManager::class.java) }
    private val displays by lazy { getSystemService(DisplayManager::class.java) }
    private var texture: TextureView? = null
    private var stream: ScanCamera.Stream? = null
    private var resumed = false
    /** start() sent, with no stop() or problem since. */
    private var running = false
    private var asked = false

    private var granted by mutableStateOf(false)
    /** CAMERA refused for good: Android won't ask again, only Settings can allow it. */
    private var blocked by mutableStateOf(false)
    private var problem by mutableStateOf<ScanCamera.Problem?>(null)
    private var hasTorch by mutableStateOf(false)
    private var torchOn by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Light status and navigation bar icons, over the camera picture.
        enableEdgeToEdge(SystemBarStyle.dark(TRANSPARENT), SystemBarStyle.dark(TRANSPARENT))
        super.onCreate(savedInstanceState)
        screens += this
        camera = ScanCamera(this, ::onStream, ::onProblem, ::onCode)
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) return fail(NO_CAMERA)
        granted = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        asked = savedInstanceState?.getBoolean(ASKED) == true
        blocked = asked && !granted // the answer, if one is still due, sets it right
        setContent { AppTheme { Screen() } }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(ASKED, asked)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        // Back from Settings, or from the app that had the camera: try again.
        granted = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (granted) blocked = false
        problem = null
        cameras?.registerAvailabilityCallback(available, main)
        displays?.registerDisplayListener(rotation, main)
        place()
        update()
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        cameras?.unregisterAvailabilityCallback(available)
        displays?.unregisterDisplayListener(rotation)
        update()
    }

    override fun onDestroy() {
        super.onDestroy()
        screens -= this
        camera.release()
    }

    /** Keeps the camera open exactly while this screen is in front, may use it, and has a surface to show it on. */
    private fun update() {
        val surface = texture?.surfaceTexture?.takeIf { resumed && granted && problem == null }
        if (surface != null && !running) {
            running = true
            camera.start(surface)
        } else if (surface == null && running) {
            running = false
            camera.stop()
        }
    }

    private fun onPermission(ok: Boolean) {
        granted = ok
        when {
            ok -> { blocked = false; update() }
            // Refused this time; Android asks again on the next scan.
            shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) -> fail(NO_PERMISSION)
            // Refused for good: offer Settings here.
            else -> blocked = true
        }
    }

    private fun onStream(s: ScanCamera.Stream) {
        stream = s
        hasTorch = s.torch
        place()
    }

    private fun onProblem(p: ScanCamera.Problem) {
        running = false // the camera thread has closed it
        when {
            p == ScanCamera.Problem.NO_CAMERA -> fail(NO_CAMERA)
            // From Android 12 this also means camera access is off for all apps, and Android offers to turn it
            // back on: stay, and onResume opens the camera again once that is answered.
            p == ScanCamera.Problem.DISABLED && (Build.VERSION.SDK_INT < 31 || managed()) -> fail(DISABLED)
            else -> problem = p
        }
    }

    /** The phone's admin (work or school) turned the camera off, or locked the camera access switch: nothing for
     *  people to do here. */
    private fun managed() = getSystemService(DevicePolicyManager::class.java)?.getCameraDisabled(null) == true ||
        Build.VERSION.SDK_INT >= 31 &&
        getSystemService(UserManager::class.java)?.hasUserRestriction(UserManager.DISALLOW_CAMERA_TOGGLE) == true

    private fun onCode(text: String) {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_TEXT, text))
        finish()
    }

    private fun fail(message: String) {
        setResult(RESULT_CANCELED, Intent().putExtra(EXTRA_ERROR, message))
        finish()
    }

    private fun retry() {
        problem = null
        update()
    }

    private fun setTorch(on: Boolean) {
        torchOn = on
        camera.torch(on)
    }

    private fun openSettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
    }

    /** Fits the preview to the view and to the screen's rotation; again after every resize and turn. */
    private fun place() {
        val view = texture ?: return
        val s = stream ?: return
        if (view.width == 0 || view.height == 0) return
        val degrees = when (view.display?.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val values = previewMatrix(view.width.toFloat(), view.height.toFloat(), s.width, s.height, s.sensorDegrees, degrees)
        view.setTransform(Matrix().apply { setValues(values) })
    }

    private fun preview(context: Context) = TextureView(context).also {
        it.surfaceTextureListener = surfaces
        it.keepScreenOn = true
        texture = it
    }

    private val surfaces = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            place()
            update()
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = place()

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            running = false
            camera.stop { surface.release() } // only once the camera has let go of it
            return false
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
    }

    /** A turn by 180° changes no size, so only the display says so. */
    private val rotation = object : DisplayManager.DisplayListener {
        override fun onDisplayChanged(displayId: Int) = place()
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
    }

    /** The app that took the camera has let go of it. */
    private val available = object : CameraManager.AvailabilityCallback() {
        override fun onCameraAvailable(cameraId: String) {
            if (problem == ScanCamera.Problem.IN_USE) retry()
        }
    }

    @Composable
    private fun Screen() {
        val ask = rememberLauncherForActivityResult(RequestPermission(), ::onPermission)
        LaunchedEffect(Unit) {
            if (!granted && !asked) {
                asked = true
                ask.launch(Manifest.permission.CAMERA)
            }
        }
        val message = when {
            blocked -> NO_PERMISSION
            problem == ScanCamera.Problem.IN_USE -> IN_USE
            problem == ScanCamera.Problem.DISABLED -> CAMERA_OFF
            problem != null -> FAILED
            else -> null
        }
        val scanning = granted && message == null
        CompositionLocalProvider(LocalContentColor provides Color.White) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                if (granted) {
                    AndroidView(::preview, Modifier.fillMaxSize())
                    Viewfinder(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { camera.focus() } })
                }
                if (message != null)
                    Notice(message, if (blocked) "Open Settings" else "Try again", if (blocked) ::openSettings else ::retry)
                TopBar(hint = scanning, torch = torchOn.takeIf { scanning && hasTorch }, onTorch = ::setTorch, onClose = ::finish)
            }
        }
    }

    internal companion object {
        const val EXTRA_TEXT = "text"
        const val EXTRA_ERROR = "error"
        private const val ASKED = "asked"

        /** Scanner screens not yet destroyed. Main thread. */
        private val screens = ArrayList<ScanActivity>()

        /** Closes any open scanner screen without an answer: the code came another way. Main thread. */
        fun closeAll() = screens.toList().forEach { it.finish() }

        private const val NO_PERMISSION =
            "Camera access is off. Allow it in Settings, or use a screenshot or enter the code instead."
        private const val NO_CAMERA = "This phone has no camera the app can use. Use a screenshot or enter the code instead."
        private const val DISABLED =
            "This phone doesn't let apps use the camera right now. Use a screenshot or enter the code instead."
        private const val IN_USE = "Another app is using the camera. Close it, then try again."
        private const val CAMERA_OFF =
            "Camera access is off for all apps. Turn it back on in quick settings or privacy settings, then try again."
        private const val FAILED = "The camera didn't start. Try again, or go back and use a screenshot instead."
    }
}

/** Close, the hint, and the torch ([torch] null: none to offer). */
@Composable
private fun TopBar(hint: Boolean, torch: Boolean?, onTorch: (Boolean) -> Unit, onClose: () -> Unit) {
    val sides = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
    // On top of the viewfinder's dimming: over a white page or a laptop screen that alone leaves the hint on mid-grey.
    Row(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.5f)).windowInsetsPadding(sides).padding(4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onClose) { Icon(painterResource(R.drawable.ic_close), contentDescription = "Close") }
        Text(if (hint) "Point the camera at the QR code" else "", Modifier.weight(1f).padding(horizontal = 8.dp),
            style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        if (torch != null) IconToggleButton(torch, onTorch, colors = IconButtonDefaults.iconToggleButtonColors(
            checkedContainerColor = Color.White, checkedContentColor = Color.Black)) {
            Icon(painterResource(if (torch) R.drawable.ic_flashlight_on else R.drawable.ic_flashlight_off),
                contentDescription = "Torch")
        } else Spacer(Modifier.size(48.dp)) // keeps the hint centred
    }
}

/** A problem in place of the picture, with the way out of it. */
@Composable
private fun Notice(text: String, action: String, onAction: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)).safeDrawingPadding().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, Modifier.widthIn(max = 400.dp).semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Button(onClick = onAction, colors = ButtonDefaults.buttonColors(containerColor = Color.White,
            contentColor = Color.Black)) { Text(action) }
    }
}

/** Dims the picture around a centred square to aim the code at. Most of the frame's middle is decoded, so aiming
 *  needn't be exact. */
@Composable
private fun Viewfinder(modifier: Modifier) = Canvas(modifier) {
    val side = minOf(size.minDimension * 0.7f, 280.dp.toPx())
    val box = Rect(center.x - side / 2, center.y - side / 2, center.x + side / 2, center.y + side / 2)
    val corner = CornerRadius(24.dp.toPx())
    drawPath(Path().apply {
        fillType = PathFillType.EvenOdd
        addRect(Rect(Offset.Zero, size))
        addRoundRect(RoundRect(box, corner))
    }, Color.Black.copy(alpha = 0.5f))
    drawRoundRect(Color.White, box.topLeft, box.size, corner, style = Stroke(3.dp.toPx()))
}
