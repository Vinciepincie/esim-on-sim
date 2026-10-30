package com.esimonsim.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Size
import android.view.Surface
import com.google.zxing.PlanarYUVLuminanceSource
import java.nio.Buffer
import java.nio.ByteBuffer
import kotlin.math.abs

/** The camera behind [ScanActivity], on plain Camera2: a preview into the screen's SurfaceTexture, plus frames of
 *  about 1280x720 whose centre is decoded with ZXing. Camera calls run on one thread and decoding on another, so a
 *  slow frame never holds up the camera; frames that arrive mid-decode are dropped. Callbacks come on the main
 *  thread, and only for the latest [start]. */
internal class ScanCamera(
    context: Context,
    private val onStream: (Stream) -> Unit,
    private val onProblem: (Problem) -> Unit,
    private val onCode: (String) -> Unit,
) {
    /** The preview's buffer size and the sensor's turn, for the screen's transform; [torch]: the camera has a flash. */
    class Stream(val width: Int, val height: Int, val sensorDegrees: Int, val torch: Boolean)

    enum class Problem { NO_CAMERA, DISABLED, IN_USE, FAILED }

    private val app = context.applicationContext
    private val manager = app.getSystemService(Context.CAMERA_SERVICE) as CameraManager?
    private val main = Handler(Looper.getMainLooper())
    private var released = false // main thread

    /** Bumped on the main thread by every [start] and [stop]; work for an older one is dropped or closed. */
    @Volatile private var generation = 0

    // Camera thread only.
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var request: CaptureRequest.Builder? = null
    private var reader: ImageReader? = null
    private var preview: Surface? = null
    private var afModes = IntArray(0)
    private var torch = false
    private var frameCount = 0

    // The camera thread fills [frame] only while no decode runs, and the decoder reads it only during one.
    private var frame = ByteArray(0)
    @Volatile private var decoding = false
    @Volatile private var found = false

    fun start(texture: SurfaceTexture) {
        val gen = ++generation
        cameraThread.post { if (gen == generation) open(texture, gen) }
    }

    /** Closes the camera; [after] runs on the camera thread once it has let go of the preview surface. */
    fun stop(after: () -> Unit = {}) {
        generation++
        cameraThread.post { close(); after() }
    }

    fun torch(on: Boolean) { cameraThread.post { torch = on; repeat() } }

    /** Focus again now, on the middle of the picture (people tap when the code looks blurred). */
    fun focus() { cameraThread.post(::autoFocus) }

    fun release() { released = true; stop() }

    private fun post(gen: Int, call: () -> Unit) {
        main.post { if (gen == generation && !released) call() }
    }

    private fun problem(gen: Int, problem: Problem) = post(gen) { onProblem(problem) }

    @SuppressLint("MissingPermission") // ScanActivity starts the camera only once CAMERA is granted
    private fun open(texture: SurfaceTexture, gen: Int) {
        close()
        val manager = manager ?: return problem(gen, Problem.NO_CAMERA)
        try {
            val (id, info) = pick(manager) ?: return problem(gen, Problem.NO_CAMERA)
            val map = info.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return problem(gen, Problem.FAILED)
            val size = frameSize(map)
            val shown = previewSize(map, size, app.resources.displayMetrics.run { maxOf(widthPixels, heightPixels) })
            afModes = info.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: IntArray(0)
            val stream = Stream(shown.width, shown.height, info.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
                info.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true)
            post(gen) { onStream(stream) } // before the first frame, so it never shows stretched
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (gen != generation) return camera.close()
                    device = camera
                    configure(camera, info, texture, shown, size, gen)
                }

                // Another app took the camera (it may be in front, in split screen).
                override fun onDisconnected(camera: CameraDevice) = lost(camera, gen, Problem.IN_USE)

                override fun onError(camera: CameraDevice, error: Int) = lost(camera, gen, when (error) {
                    ERROR_CAMERA_IN_USE, ERROR_MAX_CAMERAS_IN_USE -> Problem.IN_USE
                    ERROR_CAMERA_DISABLED -> Problem.DISABLED
                    else -> Problem.FAILED
                })
            }, cameraThread)
        } catch (e: CameraAccessException) {
            problem(gen, problemOf(e))
        } catch (_: RuntimeException) { // SecurityException, IllegalArgumentException: the camera just went away
            problem(gen, Problem.FAILED)
        }
    }

    private fun lost(camera: CameraDevice, gen: Int, why: Problem) {
        camera.close()
        if (gen == generation) { close(); problem(gen, why) }
    }

    private fun configure(camera: CameraDevice, info: CameraCharacteristics, texture: SurfaceTexture, shown: Size,
                          size: Size, gen: Int) {
        try {
            texture.setDefaultBufferSize(shown.width, shown.height)
            val surface = Surface(texture).also { preview = it }
            val frames = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2).also { reader = it }
            frames.setOnImageAvailableListener(::analyse, cameraThread)
            val ready = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (gen != generation) return // stop() is about to close the camera, and with it this session
                    try {
                        request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(surface)
                            addTarget(frames.surface)
                            afMode()?.let { set(CaptureRequest.CONTROL_AF_MODE, it) }
                            centre(info)?.let { area ->
                                if ((info.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0) > 0)
                                    set(CaptureRequest.CONTROL_AF_REGIONS, area)
                                if ((info.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0) > 0)
                                    set(CaptureRequest.CONTROL_AE_REGIONS, area)
                            }
                        }
                    } catch (_: CameraAccessException) { // closing, or broken: the device's own callbacks say which
                        return
                    } catch (_: IllegalStateException) {
                        return
                    }
                    session = s
                    repeat()
                    // A lens with only one-shot autofocus focuses when asked, so ask now and every few seconds.
                    if (CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE !in afModes) autoFocus()
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    if (gen == generation) { close(); problem(gen, Problem.FAILED) }
                }
            }
            @Suppress("DEPRECATION") // its SessionConfiguration replacement needs API 28
            camera.createCaptureSession(listOf(surface, frames.surface), ready, cameraThread)
        } catch (e: CameraAccessException) {
            if (gen == generation) { close(); problem(gen, problemOf(e)) }
        } catch (_: RuntimeException) { // the device closed meanwhile, or the surface is gone
            if (gen == generation) { close(); problem(gen, Problem.FAILED) }
        }
    }

    /** Continuous autofocus where the lens has it, else one-shot (see [autoFocus]); fixed focus keeps the default. */
    private fun afMode() = when {
        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE in afModes -> CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        CaptureRequest.CONTROL_AF_MODE_AUTO in afModes -> CaptureRequest.CONTROL_AF_MODE_AUTO
        else -> null
    }

    /** Puts the request (with the torch as set) on repeat. */
    private fun repeat() {
        val s = session ?: return
        val r = request ?: return
        r.set(CaptureRequest.FLASH_MODE, if (torch) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
        safely { s.setRepeatingRequest(r.build(), null, cameraThread) }
    }

    /** One focus sweep. A trigger in continuous mode would lock the lens for good, so this switches to one-shot
     *  focus and [refocus] switches back a few seconds later. */
    private fun autoFocus() {
        val s = session ?: return
        val r = request ?: return
        if (CaptureRequest.CONTROL_AF_MODE_AUTO !in afModes) return // fixed focus
        cameraThread.removeCallbacks(refocus)
        safely {
            r.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            r.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            repeat()
            r.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            s.capture(r.build(), null, cameraThread)
            r.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
        }
        cameraThread.postDelayed(refocus, REFOCUS_MS)
    }

    /** Back to continuous focus after a tap; on one-shot lenses, the next sweep. */
    private val refocus = Runnable {
        if (CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE !in afModes) return@Runnable autoFocus()
        val s = session ?: return@Runnable
        val r = request ?: return@Runnable
        safely {
            r.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            r.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
            s.capture(r.build(), null, cameraThread)
            r.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            repeat()
        }
    }

    /** Camera thread. Closing the device ends its session and returns its buffers, so the reader and the preview
     *  surface can go right after. */
    private fun close() {
        cameraThread.removeCallbacks(refocus)
        session = null
        request = null
        device?.close()
        device = null
        reader?.close()
        reader = null
        preview?.release()
        preview = null
    }

    /** ImageReader callback, on the camera thread: copies the centre of the newest frame out for the decoder, unless
     *  it is still busy. Every other frame is decoded inverted: ZXing finds light-on-dark codes only that way. */
    private fun analyse(images: ImageReader) {
        val image = try { images.acquireLatestImage() } catch (_: IllegalStateException) { null } ?: return
        val side = minOf(image.width, image.height)
        val copied = try {
            if (decoding || found) false
            else {
                val y = image.planes[0]
                frame = centreSquare(y.buffer, y.rowStride, image.width, image.height, frame)
                true
            }
        } catch (_: RuntimeException) {
            false
        } finally {
            image.close()
        }
        if (!copied) return
        decoding = true
        val data = frame
        val inverted = frameCount++ % 2 == 1
        decoder.post { decode(data, side, inverted) }
    }

    /** Decoder thread. */
    private fun decode(data: ByteArray, side: Int, inverted: Boolean) {
        try {
            val source = PlanarYUVLuminanceSource(data, side, side, 0, 0, side, side, false)
            val text = QrImage.read(if (inverted) source.invert() else source) ?: return
            if (found) return
            found = true
            main.post { if (!released) onCode(text) }
        } catch (_: RuntimeException) {
            // ZXing choking on one odd frame; the next gets its turn.
        } finally {
            decoding = false
        }
    }

    private companion object {
        const val REFOCUS_MS = 3_000L

        /** Shared and never stopped, so a camera that finishes opening after its screen has gone still gets its
         *  callback, and is closed. */
        val cameraThread by lazy { Handler(HandlerThread("QrCamera").apply { start() }.looper) }
        val decoder by lazy { Handler(HandlerThread("QrDecode").apply { start() }.looper) }

        fun problemOf(e: CameraAccessException) = when (e.reason) {
            CameraAccessException.CAMERA_DISABLED -> Problem.DISABLED
            CameraAccessException.CAMERA_IN_USE, CameraAccessException.MAX_CAMERAS_IN_USE,
            CameraAccessException.CAMERA_DISCONNECTED -> Problem.IN_USE
            else -> Problem.FAILED
        }

        /** Drops a call that fails because the camera is closing; its state callbacks report why. */
        inline fun safely(call: () -> Unit) {
            try { call() } catch (_: CameraAccessException) { } catch (_: IllegalStateException) { }
        }

        /** The back camera, else an external one, else whatever there is (a front camera on some tablets). */
        fun pick(manager: CameraManager): Pair<String, CameraCharacteristics>? {
            val cameras = manager.cameraIdList.map { it to manager.getCameraCharacteristics(it) }.filter { (_, info) ->
                // Depth and infrared sensors are listed too, without the usual colour streams.
                info.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                    ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE) != false
            }
            fun facing(lens: Int) = cameras.firstOrNull { it.second.get(CameraCharacteristics.LENS_FACING) == lens }
            return facing(CameraCharacteristics.LENS_FACING_BACK) ?: facing(CameraCharacteristics.LENS_FACING_EXTERNAL)
                ?: cameras.firstOrNull()
        }

        /** About 1280x720: enough for a code an eighth of the picture wide, few enough pixels to decode several
         *  frames a second on an old phone. */
        fun frameSize(map: StreamConfigurationMap): Size =
            map.getOutputSizes(ImageFormat.YUV_420_888).orEmpty().filter { maxOf(it.width, it.height) <= 1920 }
                .minByOrNull { abs(it.width * it.height - 1280 * 720) } ?: Size(640, 480)

        /** The largest preview in the frames' shape, so the screen shows what is decoded. At most 1080p, or the
         *  screen's long side ([screen]) where that is smaller: what every camera must manage next to a second stream. */
        fun previewSize(map: StreamConfigurationMap, frames: Size, screen: Int): Size {
            val long = minOf(1920, maxOf(screen, frames.width))
            val sizes = map.getOutputSizes(SurfaceTexture::class.java).orEmpty()
                .filter { it.width <= long && it.height <= 1080 }
            val shape = frames.width.toFloat() / frames.height
            return sizes.filter { abs(it.width.toFloat() / it.height - shape) < 0.01f }.maxByOrNull { it.width * it.height }
                ?: sizes.maxByOrNull { it.width * it.height } ?: frames
        }

        /** The middle of the picture, around the viewfinder, for focus and exposure: a phone screen showing the
         *  code is much brighter than what is around it. In active-array coordinates, which start at 0,0. */
        fun centre(info: CameraCharacteristics): Array<MeteringRectangle>? {
            val area = info.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return null
            val side = minOf(area.width(), area.height()) / 2
            return arrayOf(MeteringRectangle((area.width() - side) / 2, (area.height() - side) / 2, side, side,
                MeteringRectangle.METERING_WEIGHT_MAX))
        }
    }
}

/** The middle square of a camera frame's Y plane (the picture in grey; one byte a pixel, rows [rowStride] apart),
 *  copied out of the camera's buffer, which goes back to the camera once the image is closed. Reuses [into] when
 *  it is the right size. */
internal fun centreSquare(y: ByteBuffer, rowStride: Int, width: Int, height: Int, into: ByteArray): ByteArray {
    val side = minOf(width, height)
    val out = if (into.size == side * side) into else ByteArray(side * side)
    val left = (width - side) / 2
    val top = (height - side) / 2
    val rows: Buffer = y // ByteBuffer's own position(int) override only exists from API 34
    for (row in 0 until side) {
        rows.position((top + row) * rowStride + left)
        y.get(out, row * side, side)
    }
    return out
}

/** The TextureView transform that shows a [bufferWidth]x[bufferHeight] camera preview upright, filling a
 *  [viewWidth]x[viewHeight] view and cropping what overflows, as android.graphics.Matrix values. The camera already
 *  turns its picture upright for the phone's natural orientation (mirrored for a front camera) and TextureView
 *  stretches that over the view, so: undo the stretch, turn the picture against the screen's rotation
 *  ([displayDegrees], clockwise), then scale it to cover the view. */
internal fun previewMatrix(viewWidth: Float, viewHeight: Float, bufferWidth: Int, bufferHeight: Int,
                           sensorDegrees: Int, displayDegrees: Int): FloatArray {
    val sideways = sensorDegrees % 180 != 0
    val w = (if (sideways) bufferHeight else bufferWidth).toFloat() // the picture, upright in natural orientation
    val h = (if (sideways) bufferWidth else bufferHeight).toFloat()
    val turned = displayDegrees % 180 != 0
    val scale = maxOf(viewWidth / (if (turned) h else w), viewHeight / (if (turned) w else h))
    val a = scale * w / viewWidth
    val b = scale * h / viewHeight
    // cos and sin of -displayDegrees, exactly.
    val (cos, sin) = when ((360 - displayDegrees % 360) % 360) {
        90 -> 0f to 1f
        180 -> -1f to 0f
        270 -> 0f to -1f
        else -> 1f to 0f
    }
    val m00 = cos * a
    val m01 = -sin * b
    val m10 = sin * a
    val m11 = cos * b
    val cx = viewWidth / 2
    val cy = viewHeight / 2
    return floatArrayOf(m00, m01, cx - m00 * cx - m01 * cy, m10, m11, cy - m10 * cx - m11 * cy, 0f, 0f, 1f)
}
