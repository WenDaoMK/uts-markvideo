package uts.markvideo.android

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Environment
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import io.dcloud.uts.UTSAndroid
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class MarkVideoEmbeddedCameraView(context: Context) : FrameLayout(context) {
    private val previewView: TextureView
    private val watermarkOverlay: WatermarkOverlayView

    private var eventCallback: ((String, String) -> Unit)? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var captureSize: Size = Size(640, 480)
    private var recordingSize: Size = Size(640, 480)
    private var recorder: CameraMp4Recorder? = null
    private var outputFile: File? = null
    private var recordingStartedAt = 0L
    private var openingCamera = false
    private var openingCameraSession = false
    private var cameraSessionReady = false
    private var previewFrameCounter = 0
    private var reusableSnapshotBitmap: Bitmap? = null
    private var watermarkImage: Bitmap? = null
    private var scaledWatermarkImage: Bitmap? = null
    private var watermarkCenterXRatio = 0.5f
    private var watermarkCenterYRatio = 0.78f
    private var watermarkDragArmed = false
    private var watermarkDragging = false
    private var targetFps = 24
    private var targetBitrate = 1_200_000
    private var includeAudio = false
    private var maxDurationMs = 0L
    private var minDurationMs = 0L
    private var cameraFacing = "back"
    private var perfLogging = false
    private var firstFrameLogged = false
    private var recorderStartRequestedAtMs = 0L
    private var lastRecordingFinishedAtMs = 0L
    private val snapshotFramePending = AtomicBoolean(false)
    private val recordFrameStats = RecordingFrameStats()

    @Volatile private var recording = false
    @Volatile private var stoppingRecording = false
    @Volatile private var finishingRecording = false
    @Volatile private var snapshotCaptureRunning = false

    private var watermarkStyle = WatermarkStyle()

    private val autoStopRunnable = Runnable {
        if (recording) {
            stopRecord()
        }
    }

    private val snapshotCaptureRunnable = object : Runnable {
        override fun run() {
            if (!snapshotCaptureRunning) return
            if (!recording || finishingRecording) {
                snapshotCaptureRunning = false
                return
            }
            requestPreviewSnapshotFrame()
        }
    }

    private val watermarkLongPressRunnable = Runnable {
        if (!recording) {
            watermarkDragArmed = true
        }
    }

    init {
        setBackgroundColor(Color.rgb(16, 22, 30))

        previewView = TextureView(context).apply {
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                    previewSurface = Surface(surface)
                    openCameraWhenPreviewReady()
                }

                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                }

                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                    releasePreviewSurface()
                    return true
                }

                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                }
            }
        }
        addView(
            previewView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )

        watermarkOverlay = WatermarkOverlayView(context).apply {
            setOnTouchListener { view, event ->
                handleWatermarkOverlayTouch(view, event)
            }
        }
        addView(
            watermarkOverlay,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.TOP or Gravity.START)
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (hasRequiredPermissions()) {
            startCameraThread()
            openCameraWhenPreviewReady()
        } else {
            requestRequiredPermissions(includeAudioPermission = false, emitDeniedOnTimeout = false)
        }
    }

    override fun onDetachedFromWindow() {
        release()
        super.onDetachedFromWindow()
    }

    fun setEventCallback(callback: ((String, String) -> Unit)?) {
        eventCallback = callback
    }

    fun setFacing(facing: String) {
        val nextFacing = if (facing == "front") "front" else "back"
        if (cameraFacing == nextFacing) return
        cameraFacing = nextFacing
        if (isAttachedToWindow && !recording && !finishingRecording) {
            restartCamera()
        }
    }

    fun setPerfLogging(enabled: Boolean) {
        perfLogging = enabled
    }

    fun startRecord(
        fps: Number,
        bitrate: Number,
        audioEnabled: Boolean,
        maxMs: Number,
        minMs: Number
    ) {
        startRecordInternal(fps, bitrate, audioEnabled, maxMs, minMs, startAttempt = 0)
    }

    private fun startRecordInternal(
        fps: Number,
        bitrate: Number,
        audioEnabled: Boolean,
        maxMs: Number,
        minMs: Number,
        startAttempt: Int
    ) {
        if (recording || finishingRecording) return
        val nowMs = System.currentTimeMillis()
        val waitAfterFinishMs = RECORD_RESTART_COOLDOWN_MS - (nowMs - lastRecordingFinishedAtMs)
        if (waitAfterFinishMs > 0L) {
            postDelayed({
                startRecordInternal(fps, bitrate, audioEnabled, maxMs, minMs, startAttempt)
            }, waitAfterFinishMs)
            return
        }
        cleanupRecorderState(deleteFile = true)
        targetFps = fps.toInt().coerceIn(8, 60)
        targetBitrate = bitrate.toInt().coerceIn(0, 20_000_000)
        includeAudio = audioEnabled
        maxDurationMs = maxMs.toLong().coerceIn(0L, 3_600_000L)
        minDurationMs = minMs.toLong().coerceIn(0L, 60_000L)
        if (!hasRequiredPermissions()) {
            requestRequiredPermissions(includeAudioPermission = audioEnabled, emitDeniedOnTimeout = true)
            emitError(MarkVideoNative.ERR_PERMISSION_DENIED, "Please grant camera or microphone permission, then start recording again.")
            return
        }
        startCameraThread()
        openCameraWhenPreviewReady()
        val handler = cameraHandler
        if (handler == null || !previewView.isAvailable) {
            emitError(MarkVideoNative.ERR_CAMERA_UNAVAILABLE, "Camera preview is not ready.")
            return
        }
        if (!isCameraReadyForRecording()) {
            scheduleStartRecordRetry(fps, bitrate, audioEnabled, maxMs, minMs, startAttempt)
            return
        }
        updateWatermarkRatiosFromOverlay()
        recordingSize = chooseRecordingSizeFromPreview()
        recorderStartRequestedAtMs = System.currentTimeMillis()
        firstFrameLogged = false
        perfLog("embedded_record_start_requested")

        handler.post {
            try {
                updateRepeatingRequest()
                val startSetupAtMs = System.currentTimeMillis()
                val outputSize = recordingSize
                val file = File(context.cacheDir, "uts-camera-watermark-${System.currentTimeMillis()}.mp4")
                val nextRecorder = CameraMp4Recorder(
                    output = file,
                    width = outputSize.width,
                    height = outputSize.height,
                    fps = targetFps,
                    bitrate = targetBitrate,
                    includeAudio = includeAudio,
                    perfLogger = ::perfLogDuration
                )
                outputFile = file
                recorder = nextRecorder
                nextRecorder.start()
                perfLogDuration("embedded_recorder_start_setup", startSetupAtMs)
                recordFrameStats.reset()
                snapshotFramePending.set(false)
                recordingStartedAt = System.currentTimeMillis()
                stoppingRecording = false
                finishingRecording = false
                recording = true
                runOnUiThread {
                    startPreviewSnapshotLoop()
                    if (maxDurationMs > 0L) {
                        removeCallbacks(autoStopRunnable)
                        postDelayed(autoStopRunnable, maxDurationMs)
                    }
                    emit(
                        "recordstart",
                        "{\"width\":${outputSize.width},\"height\":${outputSize.height},\"fps\":$targetFps}"
                    )
                }
            } catch (throwable: Throwable) {
                cleanupRecorderState(deleteFile = true)
                if (isClosedCameraDeviceError(throwable) && startAttempt < CAMERA_READY_START_RETRY_COUNT) {
                    runOnUiThread {
                        restartCamera()
                        postDelayed({
                            startRecordInternal(fps, bitrate, audioEnabled, maxMs, minMs, startAttempt + 1)
                        }, CAMERA_READY_START_RETRY_MS)
                    }
                    return@post
                }
                runOnUiThread {
                    emitError(
                        classifyRecorderStartError(throwable),
                        "Recorder start failed: ${throwable.javaClass.simpleName}: ${throwable.message ?: "unknown"}"
                    )
                }
            }
        }
    }

    fun stopRecord() {
        if (!recording || finishingRecording) return
        val handler = cameraHandler
        removeCallbacks(autoStopRunnable)
        val stopRequestedAtMs = System.currentTimeMillis()
        stoppingRecording = true
        finishingRecording = true
        stopPreviewSnapshotLoop()
        perfLog("embedded_record_stop_requested")

        if (handler == null) {
            emitError(MarkVideoNative.ERR_CAMERA_UNAVAILABLE, "Camera thread is not running.")
            return
        }

        handler.post {
            finishRecordingOnCameraThread(stopRequestedAtMs)
        }
    }

    fun takePhoto() {
        if (!hasCameraPermission()) {
            requestRequiredPermissions(includeAudioPermission = false, emitDeniedOnTimeout = true)
            emitError(MarkVideoNative.ERR_PERMISSION_DENIED, "Please grant camera permission, then take a photo again.")
            return
        }
        if (recording || finishingRecording || !previewView.isAvailable) return
        startCameraThread()
        openCameraWhenPreviewReady()
        if (cameraDevice == null) {
            emitError(MarkVideoNative.ERR_CAMERA_UNAVAILABLE, "Camera preview is not ready.")
            return
        }
        updateWatermarkRatiosFromOverlay()
        val photoSize = chooseRecordingSizeFromPreview()
        val snapshot = previewView.getBitmap(photoSize.width, photoSize.height)
        if (snapshot == null) {
            emitError(MarkVideoNative.ERR_PHOTO_CAPTURE_FAILED, "Photo capture failed.")
            return
        }

        val handler = cameraHandler
        if (handler == null) {
            snapshot.recycle()
            emitError(MarkVideoNative.ERR_CAMERA_UNAVAILABLE, "Camera thread is not running.")
            return
        }

        handler.post {
            val file = File(context.cacheDir, "uts-camera-watermark-${System.currentTimeMillis()}.jpg")
            try {
                drawWatermark(snapshot)
                FileOutputStream(file).use { output ->
                    if (!snapshot.compress(Bitmap.CompressFormat.JPEG, 92, output)) {
                        throw MarkVideoException(
                            MarkVideoNative.ERR_PHOTO_CAPTURE_FAILED,
                            "Unable to encode photo."
                        )
                    }
                }
                val savedPath = publishPhotoToGallery(file)
                runOnUiThread {
                    emit(
                        "photo",
                        "{\"tempFilePath\":\"${jsonEscape(file.absolutePath)}\",\"savedFilePath\":\"${jsonEscape(savedPath)}\",\"width\":${photoSize.width},\"height\":${photoSize.height}}"
                    )
                }
            } catch (throwable: Throwable) {
                file.delete()
                runOnUiThread {
                    emitError(
                        classifyPhotoError(throwable),
                        throwable.message ?: "Photo capture failed."
                    )
                }
            } finally {
                snapshot.recycle()
            }
        }
    }

    fun switchCamera() {
        if (recording || finishingRecording) return
        cameraFacing = if (cameraFacing == "front") "back" else "front"
        restartCamera()
    }

    fun setWatermarkStyle(
        text: String,
        imagePath: String,
        x: Number,
        y: Number,
        textColor: String,
        fontSize: Number,
        textBold: Boolean,
        imageWidth: Number,
        imageHeight: Number,
        imageGap: Number,
        boxWidth: Number,
        boxHeight: Number,
        backgroundColor: String,
        borderRadius: Number,
        padding: Number
    ) {
        releaseWatermarkImages()
        watermarkStyle = WatermarkStyle(
            text = text,
            imagePath = imagePath,
            textColor = parseColorExtra(textColor, Color.WHITE),
            fontSize = fontSize.toFloat().coerceIn(0f, 512f),
            textBold = textBold,
            imageWidth = imageWidth.toFloat().coerceIn(0f, 2048f),
            imageHeight = imageHeight.toFloat().coerceIn(0f, 2048f),
            imageGap = imageGap.toFloat().coerceIn(0f, 512f),
            boxWidthRatio = boxWidth.toFloat().coerceIn(0f, 1f),
            boxHeightRatio = boxHeight.toFloat().coerceIn(0f, 1f),
            backgroundColor = parseColorExtra(backgroundColor, Color.argb(155, 0, 0, 0)),
            borderRadius = borderRadius.toFloat().coerceIn(0f, 512f),
            padding = padding.toFloat().coerceIn(0f, 512f)
        )
        watermarkCenterXRatio = x.toFloat().coerceIn(0f, 1f)
        watermarkCenterYRatio = y.toFloat().coerceIn(0f, 1f)
        watermarkOverlay.invalidate()
        emitWatermarkChange()
    }

    fun clearWatermarkStyle() {
        releaseWatermarkImages()
        watermarkStyle = WatermarkStyle(text = "", backgroundColor = Color.TRANSPARENT)
        watermarkOverlay.invalidate()
        emitWatermarkChange()
    }

    fun getWatermarkPosition(): String {
        return "{\"x\":$watermarkCenterXRatio,\"y\":$watermarkCenterYRatio}"
    }

    fun release() {
        removeCallbacks(autoStopRunnable)
        stopPreviewSnapshotLoop()
        cancelActiveRecording()
        releaseWatermarkImages()
        releaseSnapshotBitmap()
        closeCamera(releaseSurface = true)
        stopCameraThread()
        eventCallback = null
    }

    private fun restartCamera() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread { restartCamera() }
            return
        }
        closeCamera(releaseSurface = false)
        openCameraWhenPreviewReady()
    }

    private fun resetCameraAfterRecording() {
        val reset = Runnable {
            closeCamera(releaseSurface = false)
            runOnUiThread {
                if (isAttachedToWindow && previewView.isAvailable) {
                    openCameraWhenPreviewReady()
                }
            }
        }
        cameraHandler?.post(reset) ?: reset.run()
    }

    private fun cancelActiveRecording() {
        val activeRecorder = recorder
        val file = outputFile
        recording = false
        stoppingRecording = false
        finishingRecording = false
        recorder = null
        outputFile = null
        if (activeRecorder == null && file == null) return

        val cleanup = Runnable {
            try {
                activeRecorder?.finish()
            } catch (_: Throwable) {
            }
            try {
                file?.delete()
            } catch (_: Throwable) {
            }
        }
        cameraHandler?.post(cleanup) ?: cleanup.run()
    }

    private fun cleanupRecorderState(deleteFile: Boolean) {
        stopPreviewSnapshotLoop()
        snapshotFramePending.set(false)
        recording = false
        stoppingRecording = false
        finishingRecording = false
        firstFrameLogged = false
        val activeRecorder = recorder
        val file = outputFile
        recorder = null
        outputFile = null
        recordFrameStats.reset()
        try {
            activeRecorder?.finish()
        } catch (_: Throwable) {
        }
        if (deleteFile) {
            try {
                file?.delete()
            } catch (_: Throwable) {
            }
        }
    }

    private fun finishRecordingOnCameraThread(stopRequestedAtMs: Long) {
        val activeRecorder = recorder
        val file = outputFile

        if (
            activeRecorder != null &&
            activeRecorder.frameCount == 0 &&
            System.currentTimeMillis() - stopRequestedAtMs < FIRST_FRAME_STOP_GRACE_MS
        ) {
            requestPreviewSnapshotFrame()
            cameraHandler?.postDelayed({
                finishRecordingOnCameraThread(stopRequestedAtMs)
            }, 80L)
            return
        }

        recording = false
        recorder = null
        outputFile = null

        try {
            activeRecorder?.finish()
            perfLogDuration("embedded_record_stop_finish", stopRequestedAtMs)
            if (file == null || activeRecorder == null || activeRecorder.frameCount == 0) {
                file?.delete()
                throw MarkVideoException(
                    MarkVideoNative.ERR_NO_FRAMES,
                    "No frames were recorded."
                )
            }

            val durationMs = max(1L, System.currentTimeMillis() - recordingStartedAt)
            if (minDurationMs > 0L && durationMs < minDurationMs) {
                file.delete()
                throw MarkVideoException(
                    MarkVideoNative.ERR_RECORDING_TOO_SHORT,
                    "Recording is shorter than ${minDurationMs}ms."
                )
            }
            val stats = recordFrameStats.copy(encoded = activeRecorder.frameCount)
            perfLog(
                "embedded_frame_stats received=${stats.received} " +
                    "dropped_busy=${stats.droppedBusy} " +
                    "dropped_fps=${stats.droppedFps} " +
                    "processed=${stats.processed} " +
                    "encoded=${stats.encoded}"
            )
            val savedPath = publishToGallery(file)
            lastRecordingFinishedAtMs = System.currentTimeMillis()
            finishingRecording = false
            stoppingRecording = false
            runOnUiThread {
                emit(
                    "recordstop",
                    "{" +
                        "\"tempFilePath\":\"${jsonEscape(file.absolutePath)}\"," +
                        "\"savedFilePath\":\"${jsonEscape(savedPath)}\"," +
                        "\"durationMs\":$durationMs," +
                        "\"width\":${activeRecorder.width}," +
                        "\"height\":${activeRecorder.height}," +
                        "\"watermarkText\":\"${jsonEscape(watermarkStyle.text)}\"," +
                        "\"stats\":{" +
                            "\"received\":${stats.received}," +
                            "\"droppedBusy\":${stats.droppedBusy}," +
                            "\"droppedFps\":${stats.droppedFps}," +
                            "\"processed\":${stats.processed}," +
                            "\"encoded\":${stats.encoded}" +
                        "}" +
                    "}"
                )
            }
        } catch (throwable: Throwable) {
            file?.delete()
            lastRecordingFinishedAtMs = System.currentTimeMillis()
            finishingRecording = false
            stoppingRecording = false
            runOnUiThread {
                emitError(
                    classifyRecorderStopError(throwable),
                    throwable.message ?: "Recorder stop failed."
                )
            }
        } finally {
            resetCameraAfterRecording()
        }
    }

    private fun startCameraThread() {
        if (cameraThread != null) return
        cameraThread = HandlerThread("uts-markvideo-embedded-camera").also {
            it.start()
            cameraHandler = Handler(it.looper)
        }
    }

    private fun stopCameraThread() {
        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null
    }

    private fun handleWatermarkOverlayTouch(view: View, event: MotionEvent): Boolean {
        if (recording || finishingRecording) return true

        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                watermarkDragArmed = false
                watermarkDragging = false
                view.postDelayed(watermarkLongPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
                true
            }
            MotionEvent.ACTION_MOVE -> {
                if (watermarkDragArmed) {
                    watermarkDragging = true
                    updateWatermarkOverlayPosition(event.x, event.y)
                }
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                view.removeCallbacks(watermarkLongPressRunnable)
                if (watermarkDragging) {
                    updateWatermarkRatiosFromOverlay()
                    emitWatermarkChange()
                }
                watermarkDragArmed = false
                watermarkDragging = false
                true
            }
            else -> true
        }
    }

    private fun updateWatermarkOverlayPosition(rawX: Float, rawY: Float) {
        if (watermarkOverlay.width <= 0 || watermarkOverlay.height <= 0) return
        watermarkCenterXRatio = (rawX / watermarkOverlay.width).coerceIn(0f, 1f)
        watermarkCenterYRatio = (rawY / watermarkOverlay.height).coerceIn(0f, 1f)
        watermarkOverlay.invalidate()
    }

    private fun updateWatermarkRatiosFromOverlay() {
        watermarkCenterXRatio = watermarkCenterXRatio.coerceIn(0f, 1f)
        watermarkCenterYRatio = watermarkCenterYRatio.coerceIn(0f, 1f)
    }

    private fun openCameraWhenPreviewReady() {
        if (cameraHandler == null || !hasCameraPermission()) return
        if (!previewView.isAvailable && previewSurface == null) return
        if (openingCamera || openingCameraSession || cameraSessionReady) return
        if (cameraDevice != null) {
            createCaptureSession()
            return
        }
        openCamera()
    }

    private fun isCameraReadyForRecording(): Boolean {
        return cameraDevice != null && captureSession != null && cameraSessionReady && ensurePreviewSurface() != null
    }

    private fun scheduleStartRecordRetry(
        fps: Number,
        bitrate: Number,
        audioEnabled: Boolean,
        maxMs: Number,
        minMs: Number,
        startAttempt: Int
    ) {
        if (startAttempt >= CAMERA_READY_START_RETRY_COUNT) {
            emitError(MarkVideoNative.ERR_CAMERA_UNAVAILABLE, "Camera preview is not ready.")
            return
        }
        postDelayed({
            startRecordInternal(fps, bitrate, audioEnabled, maxMs, minMs, startAttempt + 1)
        }, CAMERA_READY_START_RETRY_MS)
    }

    private fun ensurePreviewSurface(): Surface? {
        previewSurface?.let { return it }
        val texture = previewView.surfaceTexture ?: return null
        texture.setDefaultBufferSize(captureSize.width, captureSize.height)
        return Surface(texture).also { previewSurface = it }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val handler = cameraHandler ?: return

        try {
            openingCamera = true
            val cameraId = selectCamera(manager)
            val characteristics = manager.getCameraCharacteristics(cameraId)
            captureSize = chooseCaptureSize(characteristics)
            previewView.surfaceTexture?.setDefaultBufferSize(captureSize.width, captureSize.height)
            perfLog("embedded_camera_open_start id=$cameraId size=${captureSize.width}x${captureSize.height}")
            imageReader = ImageReader.newInstance(
                captureSize.width,
                captureSize.height,
                android.graphics.ImageFormat.YUV_420_888,
                2
            ).apply {
                setOnImageAvailableListener({ reader ->
                    reader.acquireLatestImage()?.close()
                }, handler)
            }
            manager.openCamera(cameraId, cameraStateCallback, handler)
        } catch (throwable: Throwable) {
            openingCamera = false
            emitError(
                MarkVideoNative.ERR_CAMERA_UNAVAILABLE,
                throwable.message ?: "Open camera failed."
            )
        }
    }

    private fun closeCamera(releaseSurface: Boolean) {
        cameraSessionReady = false
        openingCameraSession = false
        try {
            captureSession?.close()
        } catch (_: Throwable) {
        }
        captureSession = null
        try {
            cameraDevice?.close()
        } catch (_: Throwable) {
        }
        cameraDevice = null
        try {
            imageReader?.close()
        } catch (_: Throwable) {
        }
        imageReader = null
        if (releaseSurface) {
            releasePreviewSurface()
        }
        openingCamera = false
    }

    private fun releasePreviewSurface() {
        try {
            previewSurface?.release()
        } catch (_: Throwable) {
        }
        previewSurface = null
    }

    private val cameraStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            openingCamera = false
            openingCameraSession = false
            cameraDevice = camera
            createCaptureSession()
        }

        override fun onDisconnected(camera: CameraDevice) {
            openingCamera = false
            openingCameraSession = false
            cameraSessionReady = false
            if (cameraDevice == camera) {
                cameraDevice = null
                captureSession = null
            }
            try {
                camera.close()
            } catch (_: Throwable) {
            }
            emitError(MarkVideoNative.ERR_CAMERA_UNAVAILABLE, "Camera disconnected.")
        }

        override fun onError(camera: CameraDevice, error: Int) {
            openingCamera = false
            openingCameraSession = false
            cameraSessionReady = false
            if (cameraDevice == camera) {
                cameraDevice = null
                captureSession = null
            }
            try {
                camera.close()
            } catch (_: Throwable) {
            }
            emitError(MarkVideoNative.ERR_CAMERA_UNAVAILABLE, "Camera error: $error")
        }
    }

    private fun createCaptureSession() {
        if (openingCameraSession) return
        val camera = cameraDevice ?: return
        val reader = imageReader ?: return
        val preview = ensurePreviewSurface() ?: run {
            emitError(MarkVideoNative.ERR_CAMERA_UNAVAILABLE, "Preview surface is unavailable.")
            return
        }
        val handler = cameraHandler ?: return

        try {
            cameraSessionReady = false
            openingCameraSession = true
            camera.createCaptureSession(
                listOf(preview, reader.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        try {
                            openingCameraSession = false
                            if (cameraDevice != camera) {
                                session.close()
                                return
                            }
                            captureSession = session
                            cameraSessionReady = true
                            updateRepeatingRequest()
                            runOnUiThread {
                                emit("ready", "{\"facing\":\"$cameraFacing\"}")
                            }
                        } catch (throwable: Throwable) {
                            emitError(
                                MarkVideoNative.ERR_CAMERA_UNAVAILABLE,
                                throwable.message ?: "Camera preview request failed."
                            )
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        openingCameraSession = false
                        cameraSessionReady = false
                        emitError(MarkVideoNative.ERR_CAMERA_UNAVAILABLE, "Camera session configure failed.")
                    }
                },
                handler
            )
        } catch (throwable: Throwable) {
            openingCameraSession = false
            emitError(
                MarkVideoNative.ERR_CAMERA_UNAVAILABLE,
                throwable.message ?: "Create camera session failed."
            )
        }
    }

    private fun updateRepeatingRequest() {
        val camera = cameraDevice ?: return
        val session = captureSession ?: return
        val reader = imageReader ?: return
        val preview = ensurePreviewSurface() ?: return
        val handler = cameraHandler ?: return
        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(preview)
            addTarget(reader.surface)
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            selectFpsRange()?.let { range ->
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
            }
        }.build()
        session.setRepeatingRequest(request, null, handler)
    }

    private fun startPreviewSnapshotLoop() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread { startPreviewSnapshotLoop() }
            return
        }
        if (snapshotCaptureRunning) return
        snapshotCaptureRunning = true
        previewView.removeCallbacks(snapshotCaptureRunnable)
        previewView.post(snapshotCaptureRunnable)
    }

    private fun stopPreviewSnapshotLoop() {
        snapshotCaptureRunning = false
        snapshotFramePending.set(false)
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread { stopPreviewSnapshotLoop() }
            return
        }
        previewView.removeCallbacks(snapshotCaptureRunnable)
    }

    private fun requestPreviewSnapshotFrame() {
        val handler = cameraHandler ?: return
        if (!recording || stoppingRecording && recorder?.frameCount != 0) return
        if (!snapshotFramePending.compareAndSet(false, true)) {
            if (recording) {
                recordFrameStats.droppedBusy += 1
            }
            return
        }

        runOnUiThread {
            val snapshotStartedAtMs = System.currentTimeMillis()
            if (!recording || stoppingRecording && recorder?.frameCount != 0) {
                snapshotFramePending.set(false)
                return@runOnUiThread
            }
            if (!previewView.isAvailable) {
                snapshotFramePending.set(false)
                scheduleNextPreviewSnapshotFrame(snapshotStartedAtMs)
                return@runOnUiThread
            }
            val snapshotTarget = reusableSnapshotBitmap?.takeIf {
                !it.isRecycled &&
                    it.width == recordingSize.width &&
                    it.height == recordingSize.height
            } ?: Bitmap.createBitmap(
                recordingSize.width,
                recordingSize.height,
                Bitmap.Config.ARGB_8888
            ).also {
                reusableSnapshotBitmap?.recycle()
                reusableSnapshotBitmap = it
            }
            val snapshot = previewView.getBitmap(snapshotTarget)
            if (snapshot == null) {
                snapshotFramePending.set(false)
                scheduleNextPreviewSnapshotFrame(snapshotStartedAtMs)
                return@runOnUiThread
            }
            handler.post {
                try {
                    encodePreviewSnapshotFrame(snapshot)
                } finally {
                    snapshotFramePending.set(false)
                    scheduleNextPreviewSnapshotFrame(snapshotStartedAtMs)
                }
            }
        }
    }

    private fun encodePreviewSnapshotFrame(sourceBitmap: Bitmap) {
        if (!recording || recorder == null) return
        recordFrameStats.received += 1
        recordFrameStats.processed += 1
        drawWatermark(sourceBitmap)
        if (!firstFrameLogged) {
            firstFrameLogged = true
            perfLogDuration("embedded_first_encoded_frame_after_start", recorderStartRequestedAtMs)
        }
        if (recorder?.encodeFrame(sourceBitmap) == true) {
            recordFrameStats.encoded += 1
        }
        previewFrameCounter += 1
    }

    private fun scheduleNextPreviewSnapshotFrame(snapshotStartedAtMs: Long) {
        if (!snapshotCaptureRunning || !recording || finishingRecording) return
        val elapsedMs = System.currentTimeMillis() - snapshotStartedAtMs
        val targetIntervalMs = max(1L, 1000L / targetFps)
        val delayMs = max(0L, targetIntervalMs - elapsedMs)
        runOnUiThread {
            if (snapshotCaptureRunning && recording && !finishingRecording) {
                previewView.postDelayed(snapshotCaptureRunnable, delayMs)
            }
        }
    }

    private fun drawWatermark(source: Bitmap) {
        drawWatermarkOnCanvas(Canvas(source), source.width, source.height)
    }

    private fun drawWatermarkOnCanvas(canvas: Canvas, width: Int, height: Int) {
        if (!watermarkStyle.hasContent()) return

        val bandHeight = if (watermarkStyle.boxHeightRatio > 0f) {
            max(1f, height * watermarkStyle.boxHeightRatio)
        } else {
            max(72f, height * 0.16f)
        }
        val bandWidth = if (watermarkStyle.boxWidthRatio > 0f) {
            max(1f, width * watermarkStyle.boxWidthRatio)
        } else {
            width * 0.88f
        }
        val centerX = width * watermarkCenterXRatio
        val centerY = height * watermarkCenterYRatio
        val bandLeft = (centerX - bandWidth / 2f).coerceIn(0f, width - bandWidth)
        val bandTop = (centerY - bandHeight / 2f).coerceIn(0f, height - bandHeight)
        val bandRect = RectF(bandLeft, bandTop, bandLeft + bandWidth, bandTop + bandHeight)

        if (Color.alpha(watermarkStyle.backgroundColor) > 0) {
            val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = watermarkStyle.backgroundColor
            }
            canvas.drawRoundRect(bandRect, watermarkStyle.borderRadius, watermarkStyle.borderRadius, bandPaint)
        }

        val hasText = watermarkStyle.text.isNotBlank()
        val textLines = if (hasText) {
            watermarkStyle.text.split('\n')
        } else {
            emptyList()
        }
        val logo = getScaledWatermarkImage(defaultLogoHeight = (bandHeight * 0.68f).toInt())
        if (!hasText && logo == null) return

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = watermarkStyle.textColor
            textSize = if (watermarkStyle.fontSize > 0f) watermarkStyle.fontSize else max(24f, width / 20f)
            isFakeBoldText = watermarkStyle.textBold
        }
        val contentPadding = min(
            if (watermarkStyle.padding > 0f) watermarkStyle.padding else max(16f, width * 0.035f),
            bandRect.width() * 0.22f
        )
        val contentGap = if (logo != null && hasText) {
            if (watermarkStyle.imageGap > 0f) watermarkStyle.imageGap else max(12f, width * 0.02f)
        } else {
            0f
        }
        val logoWidth = logo?.width?.toFloat() ?: 0f
        val textMaxWidth = max(24f, bandRect.width() - contentPadding * 2f - logoWidth - contentGap)

        while (hasText && measureMaxLineWidth(textLines, textPaint) > textMaxWidth && textPaint.textSize > 20f) {
            textPaint.textSize -= 2f
        }

        val textWidth = if (hasText) measureMaxLineWidth(textLines, textPaint) else 0f
        val contentWidth = logoWidth + contentGap + textWidth
        var cursorX = bandRect.left + (bandRect.width() - contentWidth) / 2f

        if (logo != null) {
            val logoTop = bandRect.centerY() - logo.height / 2f
            canvas.drawBitmap(logo, cursorX, logoTop, Paint(Paint.ANTI_ALIAS_FLAG))
            cursorX += logo.width + contentGap
        }

        if (!hasText) return

        textPaint.textAlign = Paint.Align.LEFT
        val lineHeight = textPaint.fontSpacing
        val textBlockHeight = lineHeight * textLines.size
        val firstBaseline = bandRect.centerY() - textBlockHeight / 2f - textPaint.ascent()
        textLines.forEachIndexed { index, line ->
            canvas.drawText(line, cursorX, firstBaseline + lineHeight * index, textPaint)
        }
    }

    private fun measureMaxLineWidth(lines: List<String>, paint: Paint): Float {
        var maxWidth = 0f
        lines.forEach { line ->
            maxWidth = max(maxWidth, paint.measureText(line))
        }
        return maxWidth
    }

    private fun getScaledWatermarkImage(defaultLogoHeight: Int): Bitmap? {
        val source = watermarkImage ?: loadWatermarkImage()?.also { watermarkImage = it } ?: return null
        val targetHeight = when {
            watermarkStyle.imageHeight > 0f -> watermarkStyle.imageHeight.toInt()
            watermarkStyle.imageWidth > 0f -> max(1, (source.height * (watermarkStyle.imageWidth / source.width)).toInt())
            else -> defaultLogoHeight
        }
        val targetWidth = when {
            watermarkStyle.imageWidth > 0f -> watermarkStyle.imageWidth.toInt()
            else -> max(1, (source.width * (targetHeight.toFloat() / source.height)).toInt())
        }
        val existing = scaledWatermarkImage
        if (existing != null && existing.width == targetWidth && existing.height == targetHeight) return existing
        existing?.recycle()
        return Bitmap.createScaledBitmap(source, max(1, targetWidth), max(1, targetHeight), true).also {
            scaledWatermarkImage = it
        }
    }

    private fun loadWatermarkImage(): Bitmap? {
        if (watermarkStyle.imagePath.isBlank()) return null
        return try {
            val uri = Uri.parse(watermarkStyle.imagePath)
            if (uri.scheme == "content" || uri.scheme == "file") {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    BitmapFactory.decodeStream(input)
                }
            } else {
                BitmapFactory.decodeFile(watermarkStyle.imagePath)
            }
        } catch (throwable: Throwable) {
            perfLog("embedded_watermark_image_decode_failed=${throwable.javaClass.simpleName}")
            null
        }
    }

    private fun releaseWatermarkImages() {
        scaledWatermarkImage?.recycle()
        scaledWatermarkImage = null
        watermarkImage?.recycle()
        watermarkImage = null
    }

    private fun releaseSnapshotBitmap() {
        reusableSnapshotBitmap?.recycle()
        reusableSnapshotBitmap = null
    }

    private fun selectCamera(manager: CameraManager): String {
        val preferredFacing = if (cameraFacing == "front") {
            CameraCharacteristics.LENS_FACING_FRONT
        } else {
            CameraCharacteristics.LENS_FACING_BACK
        }
        return manager.cameraIdList.firstOrNull { cameraId ->
            val facing = manager.getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.LENS_FACING)
            facing == preferredFacing
        } ?: manager.cameraIdList.first()
    }

    private fun chooseCaptureSize(characteristics: CameraCharacteristics): Size {
        val sizes = characteristics
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(android.graphics.ImageFormat.YUV_420_888)
            ?: return Size(640, 480)

        return sizes
            .filter { it.width <= 1280 && it.height <= 720 && it.width % 2 == 0 && it.height % 2 == 0 }
            .maxByOrNull { it.width * it.height }
            ?: Size(640, 480)
    }

    private fun chooseRecordingSizeFromPreview(): Size {
        val sourceWidth = previewView.width.takeIf { it > 0 } ?: captureSize.width
        val sourceHeight = previewView.height.takeIf { it > 0 } ?: captureSize.height
        val longEdgeScale = MAX_RECORDING_LONG_EDGE.toDouble() / max(sourceWidth, sourceHeight).toDouble()
        val pixelScale = sqrt(MAX_RECORDING_PIXELS.toDouble() / (sourceWidth * sourceHeight).toDouble())
        val scale = min(1.0, min(longEdgeScale, pixelScale))
        val width = evenDimension((sourceWidth * scale).toInt())
        val height = evenDimension((sourceHeight * scale).toInt())
        return Size(width, height)
    }

    private fun evenDimension(value: Int): Int {
        val safeValue = max(2, value)
        return if (safeValue % 2 == 0) safeValue else safeValue - 1
    }

    private fun selectFpsRange(): Range<Int>? {
        val characteristics = cameraDevice?.id?.let { cameraId ->
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            manager.getCameraCharacteristics(cameraId)
        } ?: return null
        val ranges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?: return null

        return ranges
            .filter { range -> range.upper >= targetFps }
            .minWithOrNull(
                compareBy<Range<Int>>(
                    { range -> range.upper - targetFps },
                    { range -> if (range.lower <= targetFps) 0 else range.lower - targetFps },
                    { range -> range.upper - range.lower }
                )
            )
            ?: ranges.maxByOrNull { range -> range.upper }
    }

    private fun hasRequiredPermissions(): Boolean {
        if (!hasCameraPermission()) return false
        return !includeAudio || context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasCameraPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestRequiredPermissions(includeAudioPermission: Boolean, emitDeniedOnTimeout: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val activity = UTSAndroid.getUniActivity() ?: return
        val permissions = if (includeAudioPermission) {
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        } else {
            arrayOf(Manifest.permission.CAMERA)
        }
        activity.requestPermissions(permissions, REQUEST_REQUIRED_PERMISSIONS)
        waitForPermissions(checkIndex = 0, emitDeniedOnTimeout = emitDeniedOnTimeout)
    }

    private fun waitForPermissions(checkIndex: Int, emitDeniedOnTimeout: Boolean) {
        postDelayed({
            if (!isAttachedToWindow) return@postDelayed
            if (hasRequiredPermissions()) {
                startCameraThread()
                openCameraWhenPreviewReady()
                return@postDelayed
            }
            if (checkIndex < PERMISSION_CHECK_MAX_COUNT) {
                waitForPermissions(checkIndex + 1, emitDeniedOnTimeout)
            } else if (emitDeniedOnTimeout) {
                emitError(MarkVideoNative.ERR_PERMISSION_DENIED, "Camera or microphone permission denied.")
            }
        }, PERMISSION_CHECK_INTERVAL_MS)
    }

    private fun isClosedCameraDeviceError(throwable: Throwable): Boolean {
        var current: Throwable? = throwable
        while (current != null) {
            val message = current.message ?: ""
            if (
                message.contains("CameraDevice was already closed", ignoreCase = true) ||
                message.contains("Camera is closed", ignoreCase = true)
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private fun classifyRecorderStartError(throwable: Throwable): Int {
        val message = throwable.message ?: ""
        return when {
            throwable is MarkVideoException -> throwable.code
            message.contains("encoder", ignoreCase = true) ||
                message.contains("codec", ignoreCase = true) ||
                message.contains("YUV420", ignoreCase = true) -> MarkVideoNative.ERR_ENCODER_UNAVAILABLE
            else -> MarkVideoNative.ERR_RECORDER_START_FAILED
        }
    }

    private fun classifyRecorderStopError(throwable: Throwable): Int {
        return when (throwable) {
            is MarkVideoException -> throwable.code
            else -> MarkVideoNative.ERR_RECORDER_STOP_FAILED
        }
    }

    private fun classifyPhotoError(throwable: Throwable): Int {
        return when (throwable) {
            is MarkVideoException -> throwable.code
            else -> MarkVideoNative.ERR_PHOTO_CAPTURE_FAILED
        }
    }

    private fun publishToGallery(source: File): String {
        val displayName = "uts-markvideo-${System.currentTimeMillis()}.mp4"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(android.provider.MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(android.provider.MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES)
                put(android.provider.MediaStore.Video.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw MarkVideoException(
                    MarkVideoNative.ERR_RECORDER_STOP_FAILED,
                    "Unable to create gallery video entry."
                )

            try {
                resolver.openOutputStream(uri)?.use { output ->
                    FileInputStream(source).use { input ->
                        input.copyTo(output)
                    }
                } ?: throw MarkVideoException(
                    MarkVideoNative.ERR_RECORDER_STOP_FAILED,
                    "Unable to write gallery video."
                )
                values.clear()
                values.put(android.provider.MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                return uri.toString()
            } catch (throwable: Throwable) {
                resolver.delete(uri, null, null)
                throw throwable
            }
        }

        val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        val outputFile = File(moviesDir, displayName)
        FileInputStream(source).use { input ->
            FileOutputStream(outputFile).use { output ->
                input.copyTo(output)
            }
        }
        MediaScannerConnection.scanFile(context, arrayOf(outputFile.absolutePath), arrayOf("video/mp4"), null)
        return Uri.fromFile(outputFile).toString()
    }

    private fun publishPhotoToGallery(source: File): String {
        val displayName = "uts-markvideo-${System.currentTimeMillis()}.jpg"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
                put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw MarkVideoException(
                    MarkVideoNative.ERR_PHOTO_CAPTURE_FAILED,
                    "Unable to create gallery photo entry."
                )

            try {
                resolver.openOutputStream(uri)?.use { output ->
                    FileInputStream(source).use { input ->
                        input.copyTo(output)
                    }
                } ?: throw MarkVideoException(
                    MarkVideoNative.ERR_PHOTO_CAPTURE_FAILED,
                    "Unable to write gallery photo."
                )
                values.clear()
                values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                return uri.toString()
            } catch (throwable: Throwable) {
                resolver.delete(uri, null, null)
                throw throwable
            }
        }

        val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val outputFile = File(picturesDir, displayName)
        FileInputStream(source).use { input ->
            FileOutputStream(outputFile).use { output ->
                input.copyTo(output)
            }
        }
        MediaScannerConnection.scanFile(context, arrayOf(outputFile.absolutePath), arrayOf("image/jpeg"), null)
        return Uri.fromFile(outputFile).toString()
    }

    private fun parseColorExtra(value: String?, fallback: Int): Int {
        val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return fallback
        return try {
            if (raw.startsWith("#") && raw.length == 9) {
                val red = raw.substring(1, 3).toInt(16)
                val green = raw.substring(3, 5).toInt(16)
                val blue = raw.substring(5, 7).toInt(16)
                val alpha = raw.substring(7, 9).toInt(16)
                Color.argb(alpha, red, green, blue)
            } else {
                Color.parseColor(raw)
            }
        } catch (_: Throwable) {
            fallback
        }
    }

    private fun emitWatermarkChange() {
        emit("watermarkchange", getWatermarkPosition())
    }

    private fun emitError(code: Int, message: String) {
        emit("error", "{\"errCode\":$code,\"errMsg\":\"${jsonEscape(message)}\"}")
    }

    private fun emit(name: String, payload: String) {
        runOnUiThread {
            eventCallback?.invoke(name, payload)
        }
    }

    private fun runOnUiThread(action: () -> Unit) {
        val activity = UTSAndroid.getUniActivity()
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else if (activity != null) {
            activity.runOnUiThread { action() }
        } else {
            Handler(Looper.getMainLooper()).post { action() }
        }
    }

    private fun perfLog(message: String) {
        if (perfLogging) {
            Log.d(PERF_TAG, message)
        }
    }

    private fun perfLogDuration(label: String, startMs: Long) {
        if (perfLogging && startMs > 0L) {
            Log.d(PERF_TAG, "$label=${System.currentTimeMillis() - startMs}ms")
        }
    }

    private fun jsonEscape(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
    }

    private inner class WatermarkOverlayView(context: Context) : View(context) {
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            drawWatermarkOnCanvas(canvas, width, height)
        }
    }

    private data class WatermarkStyle(
        val text: String = "UTS 即拍即有水印",
        val imagePath: String = "",
        val textColor: Int = Color.WHITE,
        val fontSize: Float = 30f,
        val textBold: Boolean = true,
        val imageWidth: Float = 0f,
        val imageHeight: Float = 0f,
        val imageGap: Float = 18f,
        val boxWidthRatio: Float = 0.88f,
        val boxHeightRatio: Float = 0.16f,
        val backgroundColor: Int = Color.argb(155, 0, 0, 0),
        val borderRadius: Float = 18f,
        val padding: Float = 28f
    ) {
        fun hasContent(): Boolean {
            return text.isNotBlank() || imagePath.isNotBlank() || Color.alpha(backgroundColor) > 0
        }
    }

    private data class RecordingFrameStats(
        var received: Int = 0,
        var droppedBusy: Int = 0,
        var droppedFps: Int = 0,
        var processed: Int = 0,
        var encoded: Int = 0
    ) {
        fun reset() {
            received = 0
            droppedBusy = 0
            droppedFps = 0
            processed = 0
            encoded = 0
        }
    }

    private class MarkVideoException(
        val code: Int,
        message: String
    ) : RuntimeException(message)

    private class CameraMp4Recorder(
        private val output: File,
        val width: Int,
        val height: Int,
        private val fps: Int,
        private val bitrate: Int,
        private val includeAudio: Boolean,
        private val perfLogger: ((String, Long) -> Unit)? = null
    ) {
        private val frameSize = width * height
        private val quarterFrameSize = frameSize / 4
        private val muxerLock = Object()
        private var videoEncoder: MediaCodec? = null
        private var audioEncoder: MediaCodec? = null
        private var audioRecord: AudioRecord? = null
        private var audioThread: Thread? = null
        @Volatile private var audioRunning = false
        private var videoStartedAtNs = 0L
        private var lastVideoPresentationTimeUs = 0L
        private var audioStartedAtNs = 0L
        private var muxer: MediaMuxer? = null
        private var colorFormat: Int = 0
        private var videoTrackIndex = -1
        private var audioTrackIndex = -1
        private var muxerStarted = false
        private val reusablePixels = IntArray(frameSize)
        private val reusableYuv = ByteArray(frameSize + quarterFrameSize * 2)
        var frameCount: Int = 0
            private set

        fun start() {
            try {
                val muxerStartMs = System.currentTimeMillis()
                muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                perfLogger?.invoke("embedded_muxer_create", muxerStartMs)
                startVideoEncoder()
                if (includeAudio) {
                    startAudioEncoder()
                }
            } catch (throwable: Throwable) {
                finish()
                throw throwable
            }
        }

        private fun startVideoEncoder() {
            val startMs = System.currentTimeMillis()
            val codecInfo = selectEncoder()
            colorFormat = selectColorFormat(codecInfo)
            val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
                setInteger(MediaFormat.KEY_BIT_RATE, if (bitrate > 0) bitrate else width * height * 3)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }

            videoEncoder = MediaCodec.createByCodecName(codecInfo.name).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
            videoStartedAtNs = System.nanoTime()
            lastVideoPresentationTimeUs = 0L
            perfLogger?.invoke("embedded_video_encoder_start", startMs)
        }

        @SuppressLint("MissingPermission")
        private fun startAudioEncoder() {
            val startMs = System.currentTimeMillis()
            val audioMimeForDebug = "audio/mp4a-latm"
            val minBufferSize = AudioRecord.getMinBufferSize(
                AUDIO_SAMPLE_RATE,
                AUDIO_CHANNEL_CONFIG,
                AUDIO_PCM_FORMAT
            )
            val recordBufferSize = max(minBufferSize, AUDIO_SAMPLE_RATE / 5)

            val audioFormat = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                AUDIO_SAMPLE_RATE,
                AUDIO_CHANNEL_COUNT
            ).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BIT_RATE)
            }

            audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                AUDIO_SAMPLE_RATE,
                AUDIO_CHANNEL_CONFIG,
                AUDIO_PCM_FORMAT,
                recordBufferSize
            )
            check(audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
                "AudioRecord failed to initialize."
            }
            audioRunning = true
            audioStartedAtNs = System.nanoTime()
            audioThread = Thread({
                encodeAudioLoop(recordBufferSize, audioMimeForDebug)
            }, "uts-markvideo-embedded-audio").apply {
                start()
            }
            perfLogger?.invoke("embedded_audio_encoder_start", startMs)
        }

        fun encodeFrame(bitmap: Bitmap): Boolean {
            val activeEncoder = videoEncoder ?: return false
            var encoded = false
            val inputIndex = activeEncoder.dequeueInputBuffer(TIMEOUT_US)
            if (inputIndex >= 0) {
                val inputBuffer = activeEncoder.getInputBuffer(inputIndex) ?: return false
                bitmap.getPixels(reusablePixels, 0, width, 0, 0, width, height)
                argbToYuv420(reusablePixels, reusableYuv)
                inputBuffer.clear()
                inputBuffer.put(reusableYuv)
                activeEncoder.queueInputBuffer(
                    inputIndex,
                    0,
                    reusableYuv.size,
                    nextVideoPresentationTimeUs(),
                    0
                )
                frameCount += 1
                encoded = true
            }
            drainVideo(endOfStream = false)
            return encoded
        }

        fun finish() {
            val deadlineMs = System.currentTimeMillis() + FINISH_TIMEOUT_MS
            audioRunning = false
            try {
                audioRecord?.stop()
            } catch (_: Throwable) {
            }
            audioThread?.join(max(1L, min(1500L, deadlineMs - System.currentTimeMillis())))
            audioThread = null

            val activeEncoder = videoEncoder
            try {
                if (activeEncoder != null) {
                    queueVideoEndOfStream(activeEncoder, deadlineMs)
                    drainVideo(endOfStream = true, deadlineMs = deadlineMs)
                }
            } finally {
                try {
                    activeEncoder?.stop()
                } catch (_: Throwable) {
                }
                activeEncoder?.release()
                videoEncoder = null
                synchronized(muxerLock) {
                    try {
                        muxer?.release()
                    } catch (_: Throwable) {
                    }
                    muxer = null
                    muxerStarted = false
                    videoTrackIndex = -1
                    audioTrackIndex = -1
                }
            }
        }

        private fun queueVideoEndOfStream(activeEncoder: MediaCodec, deadlineMs: Long) {
            val bufferInfo = MediaCodec.BufferInfo()
            while (System.currentTimeMillis() < deadlineMs) {
                val inputIndex = activeEncoder.dequeueInputBuffer(TIMEOUT_US)
                if (inputIndex >= 0) {
                    activeEncoder.queueInputBuffer(
                        inputIndex,
                        0,
                        0,
                        lastVideoPresentationTimeUs + 1_000_000L / fps,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    )
                    return
                }
                drainVideo(endOfStream = false, deadlineMs = deadlineMs, bufferInfo = bufferInfo)
            }
            throw IllegalStateException("Timed out waiting for video encoder input buffer.")
        }

        private fun drainVideo(
            endOfStream: Boolean,
            deadlineMs: Long = Long.MAX_VALUE,
            bufferInfo: MediaCodec.BufferInfo = MediaCodec.BufferInfo()
        ) {
            val activeEncoder = videoEncoder ?: return
            val activeMuxer = muxer ?: return

            while (System.currentTimeMillis() < deadlineMs) {
                val outputIndex = activeEncoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                when {
                    outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (!endOfStream) return
                    }

                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        videoTrackIndex = addMuxerTrack(activeMuxer, activeEncoder.outputFormat, isAudio = false)
                    }

                    outputIndex >= 0 -> {
                        val encodedData = activeEncoder.getOutputBuffer(outputIndex)
                            ?: error("Encoder output buffer is null.")
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            bufferInfo.size = 0
                        }
                        if (bufferInfo.size != 0) {
                            encodedData.position(bufferInfo.offset)
                            encodedData.limit(bufferInfo.offset + bufferInfo.size)
                            writeMuxerSample(activeMuxer, videoTrackIndex, encodedData, bufferInfo)
                        }
                        activeEncoder.releaseOutputBuffer(outputIndex, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            return
                        }
                    }
                }
            }

            if (endOfStream) {
                throw IllegalStateException("Timed out waiting for video encoder end of stream.")
            }
        }

        private fun encodeAudioLoop(recordBufferSize: Int, audioMimeForDebug: String) {
            val codec = audioEncoder ?: return
            val recorder = audioRecord ?: return
            val bufferInfo = MediaCodec.BufferInfo()

            try {
                recorder.startRecording()
                while (audioRunning) {
                    val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex) ?: continue
                        inputBuffer.clear()
                        val bytesRead = recorder.read(inputBuffer, min(recordBufferSize, inputBuffer.remaining()))
                        if (bytesRead > 0) {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                bytesRead,
                                audioPresentationTimeUs(),
                                0
                            )
                        }
                    }
                    drainAudio(codec, bufferInfo, endOfStream = false, audioMimeForDebug = audioMimeForDebug)
                }

                val deadlineMs = System.currentTimeMillis() + FINISH_TIMEOUT_MS
                queueAudioEndOfStream(codec, bufferInfo, audioMimeForDebug, deadlineMs)
                drainAudio(codec, bufferInfo, endOfStream = true, audioMimeForDebug = audioMimeForDebug, deadlineMs = deadlineMs)
            } finally {
                try {
                    recorder.stop()
                } catch (_: Throwable) {
                }
                try {
                    recorder.release()
                } catch (_: Throwable) {
                }
                try {
                    codec.stop()
                } catch (_: Throwable) {
                }
                codec.release()
                audioRecord = null
                audioEncoder = null
            }
        }

        private fun queueAudioEndOfStream(
            codec: MediaCodec,
            bufferInfo: MediaCodec.BufferInfo,
            audioMimeForDebug: String,
            deadlineMs: Long
        ) {
            while (System.currentTimeMillis() < deadlineMs) {
                val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inputIndex >= 0) {
                    codec.queueInputBuffer(
                        inputIndex,
                        0,
                        0,
                        audioPresentationTimeUs(),
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    )
                    return
                }
                drainAudio(codec, bufferInfo, endOfStream = false, audioMimeForDebug = audioMimeForDebug, deadlineMs = deadlineMs)
            }
            throw IllegalStateException("Timed out waiting for audio encoder input buffer.")
        }

        private fun drainAudio(
            codec: MediaCodec,
            bufferInfo: MediaCodec.BufferInfo,
            endOfStream: Boolean,
            audioMimeForDebug: String,
            deadlineMs: Long = Long.MAX_VALUE
        ) {
            val activeMuxer = muxer ?: return

            while (System.currentTimeMillis() < deadlineMs) {
                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                when {
                    outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (!endOfStream) return
                    }

                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        check(audioMimeForDebug == "audio/mp4a-latm")
                        audioTrackIndex = addMuxerTrack(activeMuxer, codec.outputFormat, isAudio = true)
                    }

                    outputIndex >= 0 -> {
                        val encodedData = codec.getOutputBuffer(outputIndex)
                            ?: error("Audio encoder output buffer is null.")
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            bufferInfo.size = 0
                        }
                        if (bufferInfo.size != 0) {
                            encodedData.position(bufferInfo.offset)
                            encodedData.limit(bufferInfo.offset + bufferInfo.size)
                            writeMuxerSample(activeMuxer, audioTrackIndex, encodedData, bufferInfo)
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            return
                        }
                    }
                }
            }

            if (endOfStream) {
                throw IllegalStateException("Timed out waiting for audio encoder end of stream.")
            }
        }

        private fun addMuxerTrack(activeMuxer: MediaMuxer, format: MediaFormat, isAudio: Boolean): Int {
            synchronized(muxerLock) {
                val index = activeMuxer.addTrack(format)
                if (isAudio) {
                    audioTrackIndex = index
                } else {
                    videoTrackIndex = index
                }
                if (!muxerStarted && videoTrackIndex >= 0 && (!includeAudio || audioTrackIndex >= 0)) {
                    activeMuxer.start()
                    muxerStarted = true
                }
                return index
            }
        }

        private fun writeMuxerSample(
            activeMuxer: MediaMuxer,
            trackIndex: Int,
            encodedData: java.nio.ByteBuffer,
            bufferInfo: MediaCodec.BufferInfo
        ) {
            synchronized(muxerLock) {
                if (!muxerStarted || trackIndex < 0) return
                activeMuxer.writeSampleData(trackIndex, encodedData, bufferInfo)
            }
        }

        private fun argbToYuv420(pixels: IntArray, yuv: ByteArray) {
            val planar = isPlanar(colorFormat)
            var yIndex = 0

            for (row in 0 until height) {
                for (col in 0 until width) {
                    val pixel = pixels[row * width + col]
                    val red = (pixel shr 16) and 0xff
                    val green = (pixel shr 8) and 0xff
                    val blue = pixel and 0xff
                    val y = min(255, max(0, ((66 * red + 129 * green + 25 * blue + 128) shr 8) + 16))
                    val u = min(255, max(0, ((-38 * red - 74 * green + 112 * blue + 128) shr 8) + 128))
                    val v = min(255, max(0, ((112 * red - 94 * green - 18 * blue + 128) shr 8) + 128))

                    yuv[yIndex++] = y.toByte()

                    if (row % 2 == 0 && col % 2 == 0) {
                        val uvIndex = (row / 2) * (width / 2) + (col / 2)
                        if (planar) {
                            yuv[frameSize + uvIndex] = u.toByte()
                            yuv[frameSize + quarterFrameSize + uvIndex] = v.toByte()
                        } else {
                            val offset = frameSize + uvIndex * 2
                            yuv[offset] = u.toByte()
                            yuv[offset + 1] = v.toByte()
                        }
                    }
                }
            }
        }

        private fun nextVideoPresentationTimeUs(): Long {
            val elapsedUs = max(0L, (System.nanoTime() - videoStartedAtNs) / 1000L)
            val nextUs = max(lastVideoPresentationTimeUs + 1L, elapsedUs)
            lastVideoPresentationTimeUs = nextUs
            return nextUs
        }

        private fun audioPresentationTimeUs(): Long {
            return max(0L, (System.nanoTime() - audioStartedAtNs) / 1000L)
        }

        private fun selectEncoder(): MediaCodecInfo {
            return MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { codec ->
                codec.isEncoder && codec.supportedTypes.any { it.equals(MIME_TYPE, ignoreCase = true) }
            } ?: error("No AVC encoder found on this device.")
        }

        private fun selectColorFormat(codecInfo: MediaCodecInfo): Int {
            val supported = codecInfo.getCapabilitiesForType(MIME_TYPE).colorFormats.toSet()
            val preferred = listOf(
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedPlanar,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar,
                MediaCodecInfo.CodecCapabilities.COLOR_TI_FormatYUV420PackedSemiPlanar
            )
            return preferred.firstOrNull { supported.contains(it) }
                ?: error("No supported YUV420 encoder color format. Formats: ${supported.joinToString()}")
        }

        private fun isPlanar(colorFormat: Int): Boolean {
            return colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar ||
                colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedPlanar
        }
    }

    private companion object {
        const val PERF_TAG = "UTSMarkVideoPerf"
        const val REQUEST_REQUIRED_PERMISSIONS = 4108
        const val PERMISSION_CHECK_INTERVAL_MS = 400L
        const val PERMISSION_CHECK_MAX_COUNT = 20
        const val MIME_TYPE = "video/avc"
        const val TIMEOUT_US = 10_000L
        const val FINISH_TIMEOUT_MS = 4_000L
        const val FIRST_FRAME_STOP_GRACE_MS = 800L
        const val RECORD_RESTART_COOLDOWN_MS = 500L
        const val CAMERA_READY_START_RETRY_COUNT = 20
        const val CAMERA_READY_START_RETRY_MS = 120L
        const val MAX_RECORDING_LONG_EDGE = 960
        const val MAX_RECORDING_PIXELS = 720 * 960
        const val AUDIO_SAMPLE_RATE = 44_100
        const val AUDIO_CHANNEL_COUNT = 1
        const val AUDIO_BIT_RATE = 64_000
        const val AUDIO_CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_PCM_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }
}
