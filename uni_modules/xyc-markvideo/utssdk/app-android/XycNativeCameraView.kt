package uts.xyc.markvideo.android

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.Camera
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max

class XycNativeCameraView(context: Context) : FrameLayout(context), SurfaceHolder.Callback {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val previewView = SurfaceView(context)
    private val statusView = TextView(context)
    private var eventCallback: ((String, String) -> Unit)? = null

    private var camera: Camera? = null
    private var mediaRecorder: MediaRecorder? = null
    private var activeCameraId = -1
    private var holderReady = false
    private var currentMode = "photo"
    private var targetFps = DEFAULT_TARGET_FPS
    private var previewSize = XycSize(1280, 720)
    private var videoSize = XycSize(1280, 720)
    private var recording = false
    private var photoBusy = false
    private var recordingStartedAt = 0L
    private var outputFile: File? = null

    init {
        setBackgroundColor(Color.BLACK)
        previewView.holder.addCallback(this)
        addView(
            previewView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )

        statusView.setTextColor(Color.WHITE)
        statusView.textSize = 13f
        statusView.gravity = Gravity.CENTER
        statusView.setBackgroundColor(Color.argb(90, 0, 0, 0))
        statusView.setPadding(dp(14), 0, dp(14), 0)
        addView(
            statusView,
            LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36), Gravity.CENTER)
        )
        setStatus("相机初始化中")
    }

    fun setEventCallback(callback: (String, String) -> Unit) {
        eventCallback = callback
    }

    fun setStatus(text: String) {
        runOnMain {
            statusView.text = text
        }
    }

    fun setMode(mode: String) {
        currentMode = if (mode == "video") "video" else "photo"
    }

    fun setTargetFps(fps: Int) {
        targetFps = fps.coerceIn(15, DEFAULT_TARGET_FPS)
    }

    fun switchMode(mode: String): String {
        setMode(mode)
        return ok(payload().put("mode", currentMode))
    }

    fun restartCamera(): String {
        return runOnMainSync {
            closeCamera()
            openCameraIfReady()
        }
    }

    fun destroyCamera(): String {
        return runOnMainSync {
            closeCamera()
            ok(payload())
        }
    }

    fun takePhoto(): String {
        return runOnMainSync {
            val activeCamera = camera ?: return@runOnMainSync failAndEmit(
                "1104",
                "相机未就绪",
                "Camera is not open."
            )
            if (recording) {
                return@runOnMainSync failAndEmit("1403", "录像中不能拍照", "takePhoto while recording")
            }
            if (photoBusy) {
                return@runOnMainSync failAndEmit("1302", "拍照处理中", "takePhoto while photoBusy")
            }

            photoBusy = true
            setStatus("拍照中")
            activeCamera.takePicture(null, null) { data, callbackCamera ->
                val file = File(context.cacheDir, "xyc-markvideo-photo-${System.currentTimeMillis()}.jpg")
                try {
                    file.writeBytes(data)
                    val dataPayload = mediaPayload(
                        tempFilePath = file.absolutePath,
                        durationMs = 0L,
                        width = previewSize.width,
                        height = previewSize.height
                    )
                    emit("photodone", dataPayload)
                    setStatus("拍照完成")
                } catch (throwable: Throwable) {
                    file.delete()
                    failAndEmit("1301", "拍照失败", throwable.message ?: throwable.javaClass.simpleName)
                } finally {
                    photoBusy = false
                    try {
                        callbackCamera.startPreview()
                    } catch (_: Throwable) {
                    }
                }
            }
            ok(payload().put("message", "拍照请求已受理"))
        }
    }

    fun startRecord(optionsJson: String): String {
        return runOnMainSync {
            if (recording) {
                return@runOnMainSync failAndEmit("1403", "当前状态不允许执行该操作", "duplicate startRecord")
            }
            if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
                requestPermission(Manifest.permission.RECORD_AUDIO, REQUEST_AUDIO_PERMISSION)
                return@runOnMainSync failAndEmit("1002", "麦克风权限未授权", "RECORD_AUDIO permission is not granted.")
            }
            val activeCamera = camera ?: return@runOnMainSync failAndEmit(
                "1104",
                "相机未就绪",
                "Camera is not open."
            )
            val holder = previewView.holder ?: return@runOnMainSync failAndEmit(
                "1104",
                "相机未就绪",
                "Preview holder is null."
            )

            val fps = parseFps(optionsJson)
            targetFps = fps
            val file = File(context.cacheDir, "xyc-markvideo-video-${System.currentTimeMillis()}.mp4")

            try {
                activeCamera.stopPreview()
            } catch (_: Throwable) {
            }

            try {
                activeCamera.unlock()
                val recorder = MediaRecorder()
                mediaRecorder = recorder
                recorder.setCamera(activeCamera)
                recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
                recorder.setVideoSource(MediaRecorder.VideoSource.CAMERA)
                recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                recorder.setVideoSize(videoSize.width, videoSize.height)
                recorder.setVideoFrameRate(targetFps)
                recorder.setVideoEncodingBitRate(max(1_800_000, videoSize.width * videoSize.height * 4))
                recorder.setAudioEncodingBitRate(64_000)
                recorder.setAudioSamplingRate(44_100)
                recorder.setOrientationHint(90)
                recorder.setPreviewDisplay(holder.surface)
                recorder.setOutputFile(file.absolutePath)
                recorder.prepare()
                recorder.start()

                outputFile = file
                recordingStartedAt = System.currentTimeMillis()
                recording = true
                setStatus("录像中")
                emit("recordstart", payload().put("message", "录像中").put("fps", targetFps))
                ok(payload().put("fps", targetFps))
            } catch (throwable: Throwable) {
                file.delete()
                releaseRecorder()
                try {
                    activeCamera.lock()
                    activeCamera.setPreviewDisplay(holder)
                    activeCamera.startPreview()
                } catch (_: Throwable) {
                }
                failAndEmit("1401", "录像开始失败", throwable.message ?: throwable.javaClass.simpleName)
            }
        }
    }

    fun stopRecord(): String {
        return runOnMainSync {
            if (!recording) {
                return@runOnMainSync failAndEmit("1403", "当前状态不允许执行该操作", "stopRecord while not recording")
            }
            val recorder = mediaRecorder ?: return@runOnMainSync failAndEmit(
                "1402",
                "录像停止失败",
                "MediaRecorder is null."
            )
            val file = outputFile ?: return@runOnMainSync failAndEmit(
                "1402",
                "录像停止失败",
                "Output file is null."
            )

            var stopError: String? = null
            try {
                recorder.stop()
            } catch (throwable: Throwable) {
                stopError = throwable.message ?: throwable.javaClass.simpleName
            }

            releaseRecorder()
            recording = false
            outputFile = null
            restartPreviewAfterRecord()

            if (stopError != null) {
                file.delete()
                return@runOnMainSync failAndEmit("1402", "录像停止失败", stopError)
            }

            val durationMs = max(1L, System.currentTimeMillis() - recordingStartedAt)
            val data = mediaPayload(
                tempFilePath = file.absolutePath,
                durationMs = durationMs,
                width = videoSize.width,
                height = videoSize.height
            ).put("fps", targetFps)
            setStatus("录像完成")
            emit("recorddone", data)
            ok(data)
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        holderReady = true
        openCameraIfReady()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        holderReady = true
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        holderReady = false
        closeCamera()
    }

    private fun openCameraIfReady(): String {
        if (!holderReady) {
            return failAndEmit("1104", "相机未就绪", "Preview surface is not ready.")
        }
        if (!hasPermission(Manifest.permission.CAMERA)) {
            requestPermission(Manifest.permission.CAMERA, REQUEST_CAMERA_PERMISSION)
            setStatus("请授权相机权限后重试")
            return failAndEmit("1001", "相机权限未授权", "CAMERA permission is not granted.")
        }
        if (camera != null) {
            return ok(cameraReadyPayload())
        }

        return try {
            activeCameraId = findBackCameraId()
            val activeCamera = Camera.open(activeCameraId)
            activeCamera.setDisplayOrientation(90)
            applyCameraParameters(activeCamera)
            activeCamera.setPreviewDisplay(previewView.holder)
            activeCamera.startPreview()
            camera = activeCamera
            setStatus("相机已准备")
            emit("cameraready", cameraReadyPayload())
            ok(cameraReadyPayload())
        } catch (throwable: Throwable) {
            closeCamera()
            failAndEmit("1101", "相机设备不可用", throwable.message ?: throwable.javaClass.simpleName)
        }
    }

    private fun applyCameraParameters(activeCamera: Camera) {
        val parameters = activeCamera.parameters
        val selectedPreviewSize = chooseSize(parameters.supportedPreviewSizes)
        previewSize = XycSize(selectedPreviewSize.width, selectedPreviewSize.height)
        videoSize = chooseVideoSize(parameters)

        parameters.setPreviewSize(previewSize.width, previewSize.height)
        parameters.supportedFocusModes?.let { modes ->
            when {
                modes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO) ->
                    parameters.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO
                modes.contains(Camera.Parameters.FOCUS_MODE_AUTO) ->
                    parameters.focusMode = Camera.Parameters.FOCUS_MODE_AUTO
            }
        }
        chooseFpsRange(parameters.supportedPreviewFpsRange)?.let { range ->
            parameters.setPreviewFpsRange(range[0], range[1])
        }
        try {
            parameters.setRecordingHint(true)
        } catch (_: Throwable) {
        }
        activeCamera.parameters = parameters
    }

    private fun restartPreviewAfterRecord() {
        val activeCamera = camera ?: return
        try {
            activeCamera.lock()
        } catch (_: Throwable) {
        }
        try {
            activeCamera.setPreviewDisplay(previewView.holder)
            activeCamera.startPreview()
        } catch (throwable: Throwable) {
            failAndEmit("1101", "相机设备不可用", throwable.message ?: throwable.javaClass.simpleName)
        }
    }

    private fun closeCamera() {
        if (recording) {
            try {
                mediaRecorder?.stop()
            } catch (_: Throwable) {
            }
        }
        releaseRecorder()
        outputFile = null
        recording = false
        photoBusy = false
        try {
            camera?.stopPreview()
        } catch (_: Throwable) {
        }
        try {
            camera?.release()
        } catch (_: Throwable) {
        }
        camera = null
        activeCameraId = -1
    }

    private fun releaseRecorder() {
        try {
            mediaRecorder?.reset()
        } catch (_: Throwable) {
        }
        try {
            mediaRecorder?.release()
        } catch (_: Throwable) {
        }
        mediaRecorder = null
    }

    private fun findBackCameraId(): Int {
        val info = Camera.CameraInfo()
        for (cameraIndex in 0 until Camera.getNumberOfCameras()) {
            Camera.getCameraInfo(cameraIndex, info)
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
                return cameraIndex
            }
        }
        return 0
    }

    private fun chooseSize(sizes: List<Camera.Size>?): Camera.Size {
        val available = sizes ?: emptyList()
        return available
            .filter { it.width <= 1280 && it.height <= 720 }
            .maxByOrNull { it.width * it.height }
            ?: available.maxByOrNull { it.width * it.height }
            ?: error("No camera size is available.")
    }

    private fun chooseVideoSize(parameters: Camera.Parameters): XycSize {
        val videoSizes = parameters.supportedVideoSizes ?: parameters.supportedPreviewSizes
        val selected = chooseSize(videoSizes)
        return XycSize(selected.width, selected.height)
    }

    private fun chooseFpsRange(ranges: List<IntArray>?): IntArray? {
        val target = targetFps * 1000
        return ranges
            ?.filter { it[0] <= target && it[1] >= target }
            ?.minByOrNull { abs(it[0] - target) + abs(it[1] - target) }
            ?: ranges?.maxByOrNull { it[1] }
    }

    private fun parseFps(optionsJson: String): Int {
        val match = Regex("\"fps\"\\s*:\\s*(\\d+)").find(optionsJson)
        return (match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: targetFps).coerceIn(15, DEFAULT_TARGET_FPS)
    }

    private fun cameraReadyPayload(): org.json.JSONObject {
        return payload()
            .put("message", "相机已准备")
            .put("mode", currentMode)
            .put("fps", targetFps)
            .put("previewWidth", previewSize.width)
            .put("previewHeight", previewSize.height)
            .put("videoWidth", videoSize.width)
            .put("videoHeight", videoSize.height)
    }

    private fun mediaPayload(tempFilePath: String, durationMs: Long, width: Int, height: Int): org.json.JSONObject {
        return payload()
            .put("tempFilePath", tempFilePath)
            .put("path", tempFilePath)
            .put("durationMs", durationMs)
            .put("width", width)
            .put("height", height)
    }

    private fun ok(data: org.json.JSONObject): String {
        return payload()
            .put("success", true)
            .put("errorCode", "")
            .put("errorMessage", "")
            .put("nativeMessage", "")
            .put("data", data)
            .toString()
    }

    private fun failAndEmit(errorCode: String, errorMessage: String, nativeMessage: String): String {
        val data = payload()
            .put("errorCode", errorCode)
            .put("errorMessage", errorMessage)
            .put("nativeMessage", nativeMessage)
        emit("nativeerror", data)
        setStatus(errorMessage)
        return payload()
            .put("success", false)
            .put("errorCode", errorCode)
            .put("errorMessage", errorMessage)
            .put("nativeMessage", nativeMessage)
            .put("data", payload())
            .toString()
    }

    private fun payload(): org.json.JSONObject {
        return org.json.JSONObject()
    }

    private fun emit(eventName: String, data: org.json.JSONObject) {
        eventCallback?.invoke(eventName, data.toString())
    }

    private fun requestPermission(permission: String, requestCode: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val activity = findActivity(context) ?: return
        activity.requestPermissions(arrayOf(permission), requestCode)
    }

    private fun hasPermission(permission: String): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun findActivity(startContext: Context): Activity? {
        var current: Context? = startContext
        while (current is ContextWrapper) {
            if (current is Activity) {
                return current
            }
            current = current.baseContext
        }
        return null
    }

    private fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }

    private fun <T> runOnMainSync(action: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return action()
        }

        var result: T? = null
        var error: Throwable? = null
        val latch = CountDownLatch(1)
        mainHandler.post {
            try {
                result = action()
            } catch (throwable: Throwable) {
                error = throwable
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(MAIN_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            throw IllegalStateException("Timed out waiting for main thread.")
        }
        error?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private data class XycSize(val width: Int, val height: Int)

    private companion object {
        const val DEFAULT_TARGET_FPS = 30
        const val REQUEST_CAMERA_PERMISSION = 7201
        const val REQUEST_AUDIO_PERMISSION = 7202
        const val MAIN_THREAD_TIMEOUT_MS = 4_000L
    }
}
