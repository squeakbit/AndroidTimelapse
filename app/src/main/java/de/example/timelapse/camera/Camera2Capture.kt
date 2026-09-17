package de.example.timelapse.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.Display
import android.view.Surface
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Single-use: create a new instance per capture, call [capture] once, then
 * [close]. Each instance owns a dedicated [HandlerThread] for the duration
 * of one capture; without calling [close] afterwards that thread is never
 * stopped, and since a fresh instance is created for every single photo
 * (see [PhotoCaptureHelper.captureAndSave]), a long-running timelapse would
 * otherwise leak one live thread per photo taken for the entire lifetime of
 * the process.
 */
class Camera2Capture(private val context: Context) {
    private val thread = HandlerThread("Camera2Capture").apply { start() }
    private val handler = Handler(thread.looper)

    /**
     * Computes the JPEG_ORIENTATION value (clockwise degrees) needed so the
     * resulting photo displays upright, combining this camera's fixed
     * sensor mounting angle with the device's current display rotation.
     * Without this, JPEGs come out however the sensor happens to be
     * mounted relative to the device - on most phones/tablets that's 90°
     * off from how the device is actually held, which is exactly the
     * "photo is rotated" symptom this fixes. Uses the same lookup table as
     * Android's own Camera2Basic sample. Never throws - falls back to
     * ROTATION_0 if the display can't be queried for any reason (e.g. an
     * unusual context), which just means the sensor's native orientation
     * is used as-is rather than crashing the capture over it.
     */
    private fun computeJpegOrientation(characteristics: CameraCharacteristics): Int {
        val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val displayRotation = try {
            context.getSystemService(DisplayManager::class.java)
                ?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0
        } catch (_: Throwable) {
            Surface.ROTATION_0
        }
        val deviceRotationDegrees = when (displayRotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
        return if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
            (sensorOrientation - deviceRotationDegrees + 360) % 360
        } else {
            (sensorOrientation + deviceRotationDegrees) % 360
        }
    }

    @SuppressLint("MissingPermission")
    fun capture(cameraId: String, width: Int, height: Int, jpegQuality: Int, outFile: File): Boolean {
        val manager = context.getSystemService(CameraManager::class.java)
        val chars = manager.getCameraCharacteristics(cameraId)
        val afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
        val supportsAf = afModes.contains(CameraCharacteristics.CONTROL_AF_MODE_AUTO) ||
                afModes.contains(CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        val jpegOrientation = computeJpegOrientation(chars)

        val latch = CountDownLatch(1)
        var ok = false
        var error: Throwable? = null

        val reader = ImageReader.newInstance(width, height, ImageFormat.JPEG, 2)
        reader.setOnImageAvailableListener({ r ->
            r.acquireLatestImage()?.use { image ->
                try {
                    val buf = image.planes[0].buffer
                    val bytes = ByteArray(buf.remaining())
                    buf.get(bytes)
                    FileOutputStream(outFile).use { it.write(bytes) }
                    ok = true
                } catch (t: Throwable) {
                    error = t
                } finally {
                    latch.countDown()
                }
            }
        }, handler)

        // Small surface used purely to meter/drive autofocus, so we don't
        // waste time JPEG-encoding throwaway preview frames.
        val afReader = ImageReader.newInstance(320, 240, ImageFormat.YUV_420_888, 2)
        afReader.setOnImageAvailableListener({ r -> r.acquireLatestImage()?.close() }, handler)

        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null

        fun fail(t: Throwable) {
            error = t
            latch.countDown()
        }

        fun doStillCapture(d: CameraDevice, s: CameraCaptureSession) {
            try {
                val req = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(reader.surface)
                    set(CaptureRequest.JPEG_QUALITY, jpegQuality.toByte())
                    set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)
                    if (supportsAf) {
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                        set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
                    }
                }.build()
                s.capture(req, object : CameraCaptureSession.CaptureCallback() {}, handler)
            } catch (t: Throwable) {
                fail(t)
            }
        }

        // Runs continuous AF on the metering surface, triggers a lock, and
        // only then fires the still capture — fully asynchronous so it never
        // blocks the handler thread that delivers the camera callbacks.
        fun autoFocusThenCapture(d: CameraDevice, s: CameraCaptureSession) {
            val done = AtomicBoolean(false)

            fun finishAf() {
                if (done.compareAndSet(false, true)) {
                    try { s.stopRepeating() } catch (_: Throwable) {}
                    doStillCapture(d, s)
                }
            }

            try {
                val previewReq = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(afReader.surface)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                }.build()
                s.setRepeatingRequest(previewReq, object : CameraCaptureSession.CaptureCallback() {}, handler)

                // Safety net in case AF never reports a locked/failed state.
                handler.postDelayed({ finishAf() }, 3000)

                // Give continuous AF a brief head start, then request a lock.
                handler.postDelayed({
                    if (done.get()) return@postDelayed
                    try {
                        val triggerReq = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(afReader.surface)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                        }.build()
                        s.capture(triggerReq, object : CameraCaptureSession.CaptureCallback() {
                            override fun onCaptureCompleted(
                                sess: CameraCaptureSession,
                                r: CaptureRequest,
                                result: TotalCaptureResult
                            ) {
                                val state = result.get(CaptureResult.CONTROL_AF_STATE)
                                if (state == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                                    state == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
                                ) {
                                    finishAf()
                                }
                            }
                        }, handler)
                    } catch (t: Throwable) {
                        finishAf()
                    }
                }, 200)
            } catch (t: Throwable) {
                fail(t)
            }
        }

        try {
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    device = d
                    try {
                        d.createCaptureSession(
                            listOf(reader.surface, afReader.surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(s: CameraCaptureSession) {
                                    session = s
                                    if (supportsAf) autoFocusThenCapture(d, s) else doStillCapture(d, s)
                                }
                                override fun onConfigureFailed(s: CameraCaptureSession) {
                                    fail(IllegalStateException("Camera session failed"))
                                }
                            },
                            handler
                        )
                    } catch (t: Throwable) {
                        fail(t)
                    }
                }
                override fun onDisconnected(d: CameraDevice) {
                    d.close()
                    fail(IllegalStateException("Camera disconnected"))
                }
                override fun onError(d: CameraDevice, e: Int) {
                    d.close()
                    fail(IllegalStateException("Camera error $e"))
                }
            }, handler)
            latch.await(30, TimeUnit.SECONDS)
        } finally {
            try { session?.stopRepeating() } catch (_: Throwable) {}
            try { session?.close() } catch (_: Throwable) {}
            try { device?.close() } catch (_: Throwable) {}
            reader.close()
            afReader.close()
        }
        if (!ok && error != null) throw error!!
        return ok
    }

    /**
     * Stops this instance's background thread. Must be called exactly once
     * after [capture] returns (successfully or not) - see the class-level
     * doc. Safe to call even if [capture] threw.
     */
    fun close() {
        thread.quitSafely()
    }
}