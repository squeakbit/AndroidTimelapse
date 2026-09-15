package de.example.timelapse.camera

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.*
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface

/**
 * Keeps a camera open with a repeating preview request targeting an
 * external [Surface] (typically backed by a TextureView's SurfaceTexture).
 * Unlike [Camera2Capture], this does not close the camera after a single
 * frame - it stays open until [stop] is called. Only one instance should be
 * active at a time since the camera is an exclusive resource and would
 * otherwise conflict with scheduled timelapse captures.
 *
 * Owns a background [HandlerThread] for the lifetime of the instance. Call
 * [release] once this controller itself is no longer needed (e.g. when the
 * screen showing the live preview is disposed) so that thread is actually
 * stopped - [stop] alone only tears down the camera session, not the
 * thread, so a controller left un-released would leak one thread per
 * instance (e.g. one per screen rotation, since that recreates it).
 */
class CameraPreviewController(private val context: Context) {
    private val thread = HandlerThread("CameraPreview").apply { start() }
    private val handler = Handler(thread.looper)
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null

    @SuppressLint("MissingPermission")
    fun start(cameraId: String, surface: Surface, onError: (Throwable) -> Unit = {}) {
        stop()
        try {
            val manager = context.getSystemService(CameraManager::class.java)
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    device = d
                    try {
                        d.createCaptureSession(
                            listOf(surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(s: CameraCaptureSession) {
                                    session = s
                                    try {
                                        val req = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                            addTarget(surface)
                                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                        }.build()
                                        s.setRepeatingRequest(req, null, handler)
                                    } catch (t: Throwable) {
                                        onError(t)
                                    }
                                }
                                override fun onConfigureFailed(s: CameraCaptureSession) {
                                    onError(IllegalStateException("Preview-Session fehlgeschlagen"))
                                }
                            },
                            handler
                        )
                    } catch (t: Throwable) {
                        onError(t)
                    }
                }
                override fun onDisconnected(d: CameraDevice) {
                    d.close()
                    device = null
                }
                override fun onError(d: CameraDevice, e: Int) {
                    d.close()
                    device = null
                    onError(IllegalStateException("Kamerafehler $e"))
                }
            }, handler)
        } catch (t: Throwable) {
            onError(t)
        }
    }

    fun stop() {
        try { session?.stopRepeating() } catch (_: Throwable) {}
        try { session?.close() } catch (_: Throwable) {}
        try { device?.close() } catch (_: Throwable) {}
        session = null
        device = null
    }

    /**
     * Stops the background thread. Call once, when this controller instance
     * itself is being discarded (not on every [stop]) - see class-level doc.
     */
    fun release() {
        stop()
        thread.quitSafely()
    }
}