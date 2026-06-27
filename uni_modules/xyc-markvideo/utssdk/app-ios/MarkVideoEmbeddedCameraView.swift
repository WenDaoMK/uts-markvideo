import AVFoundation
import CoreImage
import Photos
import AudioToolbox
import UIKit

@objcMembers
public final class MarkVideoEmbeddedCameraView: UIView, AVCaptureVideoDataOutputSampleBufferDelegate, AVCaptureAudioDataOutputSampleBufferDelegate, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
    private static let captureQueueKey = DispatchSpecificKey<Bool>()
    private let session = AVCaptureSession()
    private let captureQueue = DispatchQueue(label: "uts.markvideo.embedded.capture")
    private let writerQueue = DispatchQueue(label: "uts.markvideo.embedded.writer")
    private let ciContext = CIContext()
    private let previewLayer: AVCaptureVideoPreviewLayer
    private let stateLock = NSLock()

    private var videoInput: AVCaptureDeviceInput?
    private var audioInput: AVCaptureDeviceInput?
    private var videoOutput: AVCaptureVideoDataOutput?
    private var audioOutput: AVCaptureAudioDataOutput?
    private var activeDevice: AVCaptureDevice?

    private var assetWriter: AVAssetWriter?
    private var writerVideoInput: AVAssetWriterInput?
    private var writerAudioInput: AVAssetWriterInput?
    private var pixelBufferAdaptor: AVAssetWriterInputPixelBufferAdaptor?
    private var outputURL: URL?
    private var firstVideoTime: CMTime?
    private var lastVideoTime: CMTime?
    private var lastEncodedFrameTime: CMTime?
    private var videoFrameCount = 0
    private var videoSize = CGSize(width: 720, height: 1280)
    private var latestVideoPixelBuffer: CVPixelBuffer?

    private var ready = false
    private var recording = false
    private var photoBusy = false
    private var recordStopPending = false
    private var destroyed = false
    private var videoPermissionRequestPending = false
    private var audioPermissionRequestPending = false
    private var recordPermissionPreparationPending = false
    private var currentMode = "photo"
    private var targetFps = 30
    private var cameraFacing = "back"
    private var zoom = "1x"
    private var flashMode = "off"
    private var cameraSoundEnabled = true
    private var activeTemplate: EmbeddedWatermarkTemplate?
    private var activeWatermarkImage: UIImage?
    private var frozenTemplate: EmbeddedWatermarkTemplate?
    private var frozenWatermarkImage: UIImage?
    private var lastPublishedMediaUri = ""
    private var lastPublishedMediaKind = ""
    private var pendingPreviewWidth = CGFloat(0)
    private var pendingPreviewHeight = CGFloat(0)
    private var pendingFacing = "back"
    private var pendingZoom = "1x"
    private var pendingFlashEnabled = false
    private var lastReadyPreviewWidth = 0
    private var lastReadyPreviewHeight = 0

    private var eventCallback: ((String, String) -> Void)?

    public override init(frame: CGRect) {
        previewLayer = AVCaptureVideoPreviewLayer(session: session)
        super.init(frame: frame)
        setupView()
    }

    required init?(coder: NSCoder) {
        previewLayer = AVCaptureVideoPreviewLayer(session: session)
        super.init(coder: coder)
        setupView()
    }

    private func setupView() {
        captureQueue.setSpecific(key: Self.captureQueueKey, value: true)
        backgroundColor = .black
        previewLayer.videoGravity = .resizeAspectFill
        if previewLayer.superlayer == nil {
            layer.addSublayer(previewLayer)
        }

    }

    deinit {
        destroyResources()
    }

    public override func layoutSubviews() {
        super.layoutSubviews()
        previewLayer.frame = bounds
        emitCameraReadyIfPreviewBoundsChanged()
    }

    public func setEventCallback(_ callback: @escaping (String, String) -> Void) {
        eventCallback = callback
    }

    private func isRecording() -> Bool {
        stateLock.lock()
        defer { stateLock.unlock() }
        return recording
    }

    private func setRecording(_ value: Bool) {
        stateLock.lock()
        recording = value
        stateLock.unlock()
    }

    private func storeLatestVideoPixelBuffer(_ buffer: CVPixelBuffer) {
        stateLock.lock()
        latestVideoPixelBuffer = buffer
        stateLock.unlock()
    }

    private func latestVideoPixelBufferSnapshot() -> CVPixelBuffer? {
        stateLock.lock()
        defer { stateLock.unlock() }
        return latestVideoPixelBuffer
    }

    private func setFrozenWatermark(template: EmbeddedWatermarkTemplate?, image: UIImage?) {
        stateLock.lock()
        frozenTemplate = template
        frozenWatermarkImage = image
        stateLock.unlock()
    }

    private func frozenWatermarkSnapshot() -> (template: EmbeddedWatermarkTemplate?, image: UIImage?) {
        stateLock.lock()
        defer { stateLock.unlock() }
        return (frozenTemplate, frozenWatermarkImage)
    }

    public func setStatus(_ text: String) {
        accessibilityLabel = text
    }

    public func switchMode(_ mode: String) -> String {
        if isRecording() {
            return failAndEmit("1403", "录像中不能切换模式", "switchMode while recording")
        }
        currentMode = mode == "video" ? "video" : "photo"
        return ok(cameraStatePayload(message: currentMode == "video" ? "视频模式" : "照片模式"))
    }

    public func setTargetFps(_ fps: NSNumber) {
        targetFps = max(15, min(30, fps.intValue))
    }

    public func setCameraSoundEnabled(_ enabled: Bool) -> String {
        cameraSoundEnabled = enabled
        return ok([
            "cameraSoundEnabled": cameraSoundEnabled,
            "message": cameraSoundEnabled ? "提示音已开启" : "提示音已关闭"
        ])
    }

    public func performHapticFeedback(_ type: String) -> String {
        let normalized = (type == "medium" || type == "heavy") ? type : "light"
        DispatchQueue.main.async {
            let generator: UIImpactFeedbackGenerator
            switch normalized {
            case "heavy":
                generator = UIImpactFeedbackGenerator(style: .heavy)
            case "medium":
                generator = UIImpactFeedbackGenerator(style: .medium)
            default:
                generator = UIImpactFeedbackGenerator(style: .light)
            }
            generator.prepare()
            generator.impactOccurred()
        }
        return ok([
            "hapticType": normalized,
            "applied": true
        ])
    }

    public func mountCamera(
        _ previewWidth: NSNumber,
        _ previewHeight: NSNumber,
        _ nextFacing: String,
        _ nextZoom: String,
        _ nextFlashEnabled: Bool
    ) -> String {
        destroyed = false
        pendingPreviewWidth = CGFloat(truncating: previewWidth)
        pendingPreviewHeight = CGFloat(truncating: previewHeight)
        pendingFacing = nextFacing == "front" ? "front" : "back"
        pendingZoom = validZoom(nextZoom) ? nextZoom : "1x"
        pendingFlashEnabled = nextFlashEnabled
        let videoAccess = requestVideoAccessIfNeeded()
        guard videoAccess.success else {
            if videoAccess.nativeMessage == "permission request is pending" {
                return fail(videoAccess.code, videoAccess.message, videoAccess.nativeMessage)
            }
            return failAndEmit(videoAccess.code, videoAccess.message, videoAccess.nativeMessage)
        }

        cameraFacing = nextFacing == "front" ? "front" : "back"
        zoom = validZoom(nextZoom) ? nextZoom : "1x"
        flashMode = "off"

        let configured = configureCameraSession(facing: cameraFacing, zoom: zoom)
        guard configured.success else {
            return failAndEmit(configured.code, configured.message, configured.nativeMessage)
        }

        let started = runOnCaptureQueueSync {
            if !self.session.isRunning {
                self.session.startRunning()
            }
            return self.session.isRunning
        }
        guard started else {
            return failAndEmit("1101", "相机设备不可用", "Camera session failed to start.")
        }

        if nextFlashEnabled {
            _ = applyFlashMode("on")
        }

        ready = true
        let data = updateReadyPreviewSize(
            requestedPreviewWidth: CGFloat(truncating: previewWidth),
            requestedPreviewHeight: CGFloat(truncating: previewHeight)
        )
        emit("cameraready", data)
        return ok(data)
    }

    public func preparePermissions() -> String {
        let videoAccess = requestVideoAccessIfNeeded()
        guard videoAccess.success else {
            if videoAccess.nativeMessage == "permission request is pending" {
                return fail(videoAccess.code, videoAccess.message, videoAccess.nativeMessage)
            }
            return failAndEmit(videoAccess.code, videoAccess.message, videoAccess.nativeMessage)
        }
        if !ready {
            return mountCamera(
                NSNumber(value: Double(max(1, bounds.width))),
                NSNumber(value: Double(max(1, bounds.height))),
                cameraFacing,
                zoom,
                flashMode != "off"
            )
        }
        return ok(cameraStatePayload(message: "权限已准备"))
    }

    public func prepareRecordPermissions() -> String {
        recordPermissionPreparationPending = true
        let videoAccess = requestVideoAccessIfNeeded()
        guard videoAccess.success else {
            return failAndEmit("1003", "请授权相机权限", videoAccess.nativeMessage)
        }
        let audioAccess = requestAudioAccessIfNeeded()
        guard audioAccess.success else {
            return failAndEmit("1003", "请授权麦克风权限", audioAccess.nativeMessage)
        }
        recordPermissionPreparationPending = false
        return ok(cameraStatePayload(message: "录像权限已准备"))
    }

    public func checkRecordPermissions() -> String {
        let videoAuthorized = AVCaptureDevice.authorizationStatus(for: .video) == .authorized
        let audioAuthorized = AVCaptureDevice.authorizationStatus(for: .audio) == .authorized
        if videoAuthorized && audioAuthorized {
            recordPermissionPreparationPending = false
            return ok(cameraStatePayload(message: "录像权限已准备"))
        }
        var missing: [String] = []
        if !videoAuthorized {
            missing.append("camera")
        }
        if !audioAuthorized {
            missing.append("microphone")
        }
        return fail("1003", recordPermissionMessage(missing), "Missing permissions: \(missing.joined(separator: ","))")
    }

    public func restartCamera() -> String {
        if isRecording() {
            return failAndEmit("1403", "当前状态不允许执行该操作", "restartCamera while recording")
        }
        ready = false
        runOnCaptureQueueSync {
            if self.session.isRunning {
                self.session.stopRunning()
            }
        }
        return mountCamera(
            NSNumber(value: Double(max(1, bounds.width))),
            NSNumber(value: Double(max(1, bounds.height))),
            cameraFacing,
            zoom,
            flashMode != "off"
        )
    }

    public func setWatermark(_ templateJSON: String) -> String {
        guard !isRecording() else {
            return failAndEmit("1403", "当前状态不允许执行该操作", "setWatermark while recording")
        }
        let parsed = EmbeddedWatermarkTemplate.parse(templateJSON)
        guard parsed.success, let template = parsed.template else {
            return failAndEmit(parsed.code, parsed.message, parsed.nativeMessage)
        }
        let image = loadImageIfNeeded(for: template)
        guard image.success else {
            return failAndEmit(image.code, image.message, image.nativeMessage)
        }

        activeTemplate = template
        activeWatermarkImage = image.image
        return ok([:])
    }

    public func clearWatermark() -> String {
        guard !isRecording() else {
            return failAndEmit("1403", "当前状态不允许执行该操作", "clearWatermark while recording")
        }
        activeTemplate = nil
        activeWatermarkImage = nil
        return ok([:])
    }

    public func getWatermarkPosition() -> String {
        guard ready else {
            return fail("1104", "相机未挂载或未就绪", "Camera is not ready.")
        }
        guard let template = activeTemplate else {
            return ok(["x": 0, "y": 0, "width": 0, "height": 0])
        }
        return ok([
            "x": template.positionX,
            "y": template.positionY,
            "width": template.boxWidth,
            "height": template.boxHeight
        ])
    }

    public func takePhoto(_ optionsJSON: String) -> String {
        guard ready else {
            return failAndEmit("1104", "相机未挂载或未就绪", "Camera is not ready.")
        }
        guard !isRecording() else {
            return failAndEmit("1403", "当前状态不允许执行该操作", "takePhoto while recording")
        }
        guard !recordStopPending else {
            return failAndEmit("1403", "请等待视频保存完成", "takePhoto while record stop pending")
        }
        guard !photoBusy else {
            return failAndEmit("1302", "拍照处理中", "takePhoto while photoBusy")
        }
        guard let sourceBuffer = latestVideoPixelBufferSnapshot() else {
            return failAndEmit("1301", "拍照失败", "No camera frame is available yet.")
        }

        let outputTemplate = templateFromOptions(optionsJSON) ?? activeTemplate
        let outputImage = imageForOutputTemplate(outputTemplate)
        photoBusy = true

        writerQueue.async { [weak self] in
            guard let self = self else { return }
            defer {
                DispatchQueue.main.async {
                    self.photoBusy = false
                }
            }
            do {
                let image = try self.makeWatermarkedImage(
                    from: sourceBuffer,
                    template: outputTemplate,
                    watermarkImage: outputImage
                )
                let tempPath = try self.writePhotoTempFile(image)
                let save = self.saveImageToGallerySynchronously(image)
                let data = self.photoData(
                    tempFilePath: tempPath,
                    albumFilePath: save.albumFilePath,
                    albumUri: save.albumFilePath,
                    savedToAlbum: save.success,
                    width: Int(image.size.width),
                    height: Int(image.size.height),
                    template: outputTemplate
                )
                DispatchQueue.main.async {
                    if !save.success {
                        self.emitNativeError("1501", "文件保存失败", save.nativeMessage)
                    }
                    if self.cameraSoundEnabled {
                        AudioServicesPlaySystemSound(1108)
                    }
                    if save.success {
                        self.rememberPublishedMedia(uri: save.albumFilePath, kind: "photo")
                    }
                    self.emit("photodone", data)
                }
            } catch {
                DispatchQueue.main.async {
                    _ = self.failAndEmit("1301", "拍照失败", error.localizedDescription)
                }
            }
        }
        return ok(["message": "拍照请求已受理"])
    }

    public func startRecord(_ optionsJSON: String) -> String {
        guard ready else {
            return failAndEmit("1104", "相机未挂载或未就绪", "Camera is not ready.")
        }
        guard !isRecording() else {
            return failAndEmit("1403", "当前状态不允许执行该操作", "duplicate startRecord")
        }
        guard !recordStopPending else {
            return failAndEmit("1403", "请等待视频保存完成", "startRecord while stop pending")
        }
        guard !photoBusy else {
            return failAndEmit("1403", "拍照处理中", "startRecord while photoBusy")
        }
        let audioAccess = requestAudioAccessIfNeeded()
        guard audioAccess.success else {
            return failAndEmit(audioAccess.code, audioAccess.message, audioAccess.nativeMessage)
        }

        let outputTemplate = templateFromOptions(optionsJSON) ?? activeTemplate
        let outputImage = imageForOutputTemplate(outputTemplate)
        setFrozenWatermark(template: outputTemplate, image: outputImage)

        let audioReady = ensureAudioInputs()
        guard audioReady.success else {
            return failAndEmit(audioReady.code, audioReady.message, audioReady.nativeMessage)
        }

        do {
            try writerQueue.sync {
                try self.prepareWriter()
                self.firstVideoTime = nil
                self.lastVideoTime = nil
                self.lastEncodedFrameTime = nil
                self.videoFrameCount = 0
            }
            setRecording(true)
            if cameraSoundEnabled {
                AudioServicesPlaySystemSound(1117)
            }
            let data = cameraStatePayload(message: "录像中")
                .merging(watermarkEventData(template: outputTemplate)) { _, next in next }
            emit("recordstart", data)
            return ok(data)
        } catch {
            writerQueue.sync {
                self.resetWriter()
            }
            setFrozenWatermark(template: nil, image: nil)
            return failAndEmit("1401", "录像开始失败", error.localizedDescription)
        }
    }

    public func stopRecord() -> String {
        guard isRecording() else {
            if recordStopPending {
                return ok(["message": "视频保存中"])
            }
            return failAndEmit("1403", "当前状态不允许执行该操作", "stopRecord while not recording")
        }
        recordStopPending = true
        setRecording(false)
        writerQueue.async { [weak self] in
            guard let self = self else { return }
            let finishResult = self.finishRecordingOnWriterQueue()
            guard finishResult.success, let url = finishResult.url else {
                DispatchQueue.main.async {
                    self.recordStopPending = false
                    self.setFrozenWatermark(template: nil, image: nil)
                    _ = self.failAndEmit("1402", "录像停止失败", finishResult.nativeMessage)
                }
                return
            }

            let template = self.frozenWatermarkSnapshot().template
            let save = self.saveVideoToGallerySynchronously(url)
            let thumbnailPath = self.createVideoThumbnail(from: url)
            let data = self.videoData(
                tempFilePath: url.path,
                albumFilePath: save.albumFilePath,
                albumUri: save.albumFilePath,
                savedToAlbum: save.success,
                durationMs: finishResult.durationMs,
                width: Int(self.videoSize.width),
                height: Int(self.videoSize.height),
                template: template,
                thumbnailPath: thumbnailPath
            )
            self.resetWriter()
            self.setFrozenWatermark(template: nil, image: nil)
            DispatchQueue.main.async {
                self.recordStopPending = false
                if !save.success {
                    self.emitNativeError("1501", "文件保存失败", save.nativeMessage)
                }
                if save.success {
                    self.rememberPublishedMedia(uri: save.albumFilePath, kind: "video")
                }
                self.emit("recorddone", data)
            }
        }
        return ok(["message": "视频保存中"])
    }

    public func switchFlash(_ enabled: Bool) -> String {
        return setFlashMode(enabled ? "on" : "off")
    }

    public func setFlashMode(_ mode: String) -> String {
        guard ready else {
            return failAndEmit("1104", "相机未挂载或未就绪", "Camera is not ready.")
        }
        let requestedMode = mode == "auto" ? "auto" : normalizeFlashMode(mode)
        let previousMode = flashMode
        if requestedMode == "auto" {
            let data = flashModePayload(requestedMode: requestedMode, previousMode: previousMode, applied: false)
            emit("flashchange", data)
            return ok(data)
        }
        let result = applyFlashMode(requestedMode)
        let applied = result.success
        if !applied && requestedMode != "off" {
            _ = applyFlashMode(previousMode)
        }
        let data = flashModePayload(requestedMode: requestedMode, previousMode: previousMode, applied: applied)
        emit("flashchange", data)
        return ok(data)
    }

    public func setZoom(_ nextZoom: String) -> String {
        return setZoomMode(nextZoom)
    }

    public func setZoomMode(_ nextZoom: String) -> String {
        guard ready else {
            return failAndEmit("1104", "相机未挂载或未就绪", "Camera is not ready.")
        }
        let requestedZoom = validZoom(nextZoom) ? nextZoom : "1x"
        let previousZoom = zoom
        let result = applyZoom(requestedZoom)
        let applied = result.success
        if !applied && requestedZoom != previousZoom {
            _ = applyZoom(previousZoom)
        }
        let data = cameraStatePayload(message: applied ? zoomModeMessage(zoom) : unsupportedZoomModeMessage(requestedZoom))
            .merging([
                "requestedZoomMode": requestedZoom,
                "applied": applied
            ]) { _, next in next }
        emit("zoomchange", data)
        return ok(data)
    }

    public func switchCamera() -> String {
        let nextFacing = cameraFacing == "front" ? "back" : "front"
        return switchCamera(nextFacing)
    }

    public func switchCamera(_ nextFacing: String) -> String {
        guard !isRecording() else {
            return failAndEmit("1403", "当前状态不允许执行该操作", "switchCamera while recording")
        }
        guard !recordStopPending && !photoBusy else {
            return failAndEmit("1105", "拍摄或保存中不能切换摄像头", "switchCamera while busy")
        }
        guard ready else {
            return failAndEmit("1104", "相机未挂载或未就绪", "Camera is not ready.")
        }
        let facing = nextFacing == "front" ? "front" : "back"
        let previousFacing = cameraFacing
        let previousZoom = zoom
        let previousFlashMode = flashMode
        let result = configureCameraSession(facing: facing, zoom: "1x")
        var applied = result.success
        if !applied {
            _ = configureCameraSession(facing: previousFacing, zoom: previousZoom)
            _ = applyFlashMode(previousFlashMode)
            applied = false
        } else {
            cameraFacing = facing
            zoom = "1x"
            _ = applyFlashMode("off")
        }
        let data = cameraStatePayload(message: applied ? cameraFacingMessage(cameraFacing) : unsupportedCameraFacingMessage(facing))
            .merging([
                "requestedCameraFacing": facing,
                "applied": applied
            ]) { _, next in next }
        emit("camerachange", data)
        return ok(data)
    }

    public func openSystemAlbum(_ mediaUri: String) -> String {
        let target = mediaUri.isEmpty ? lastPublishedMediaUri : mediaUri
        guard UIImagePickerController.isSourceTypeAvailable(.photoLibrary) else {
            return failAndEmit("1601", "打开系统相册失败", "Photo library is unavailable.")
        }
        let access = requestPhotoReadAccessForAlbum(target)
        if access.granted {
            presentSystemAlbum(target)
        } else if !access.pending {
            return failAndEmit("1602", "请授权相册访问权限", "Photo library read permission denied.")
        }
        return ok([
            "message": access.pending ? "请授权相册权限" : "已打开系统相册",
            "albumUri": target,
            "mediaKind": lastPublishedMediaKind.isEmpty ? "album" : lastPublishedMediaKind
        ])
    }

    private func presentSystemAlbum(_ target: String) {
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            guard let presenter = self.topMostViewController() else {
                self.emitNativeError("1601", "打开系统相册失败", "No view controller is available.")
                return
            }
            if presenter.presentedViewController is UIImagePickerController {
                return
            }
            let picker = UIImagePickerController()
            picker.sourceType = .photoLibrary
            picker.delegate = self
            if let mediaTypes = UIImagePickerController.availableMediaTypes(for: .photoLibrary) {
                picker.mediaTypes = mediaTypes
            }
            presenter.present(picker, animated: true)
        }
    }

    private func requestPhotoReadAccessForAlbum(_ target: String) -> (granted: Bool, pending: Bool) {
        if #available(iOS 14, *) {
            let status = PHPhotoLibrary.authorizationStatus(for: .readWrite)
            if status == .authorized || status == .limited {
                return (true, false)
            }
            if status != .notDetermined {
                return (false, false)
            }
            PHPhotoLibrary.requestAuthorization(for: .readWrite) { [weak self] nextStatus in
                guard let self = self else { return }
                if nextStatus == .authorized || nextStatus == .limited {
                    self.presentSystemAlbum(target)
                } else {
                    DispatchQueue.main.async {
                        self.emitNativeError("1602", "请授权相册访问权限", "Photo library read permission denied.")
                    }
                }
            }
            return (false, true)
        }
        let status = PHPhotoLibrary.authorizationStatus()
        if status == .authorized {
            return (true, false)
        }
        if status != .notDetermined {
            return (false, false)
        }
        PHPhotoLibrary.requestAuthorization { [weak self] nextStatus in
            guard let self = self else { return }
            if nextStatus == .authorized {
                self.presentSystemAlbum(target)
            } else {
                DispatchQueue.main.async {
                    self.emitNativeError("1602", "请授权相册访问权限", "Photo library read permission denied.")
                }
            }
        }
        return (false, true)
    }

    public func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
        picker.dismiss(animated: true)
    }

    public func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
        picker.dismiss(animated: true)
    }

    public func destroyCamera() -> String {
        destroyResources()
        return ok([:])
    }

    private func configureCameraSession(facing: String, zoom requestedZoom: String) -> NativeStatus {
        return runOnCaptureQueueSync {
            configureCameraSessionOnCaptureQueue(facing: facing, zoom: requestedZoom)
        }
    }

    private func configureCameraSessionOnCaptureQueue(facing: String, zoom requestedZoom: String) -> NativeStatus {
        let position: AVCaptureDevice.Position = facing == "front" ? .front : .back
        guard let camera = cameraDevice(facing: position, zoom: requestedZoom) else {
            return NativeStatus(false, "1101", "相机设备不可用", "No camera device.")
        }

        var didBeginConfiguration = false
        do {
            let nextVideoInput = try AVCaptureDeviceInput(device: camera)
            let nextVideoOutput = AVCaptureVideoDataOutput()
            nextVideoOutput.videoSettings = [
                kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA
            ]
            nextVideoOutput.alwaysDiscardsLateVideoFrames = true
            nextVideoOutput.setSampleBufferDelegate(self, queue: writerQueue)

            session.beginConfiguration()
            didBeginConfiguration = true
            session.sessionPreset = .hd1280x720
            if let videoInput = videoInput {
                session.removeInput(videoInput)
            }
            if let videoOutput = videoOutput {
                session.removeOutput(videoOutput)
            }
            guard session.canAddInput(nextVideoInput) else {
                session.commitConfiguration()
                return NativeStatus(false, "1101", "相机设备不可用", "Cannot add camera input.")
            }
            guard session.canAddOutput(nextVideoOutput) else {
                session.commitConfiguration()
                return NativeStatus(false, "1101", "相机设备不可用", "Cannot add video output.")
            }
            session.addInput(nextVideoInput)
            session.addOutput(nextVideoOutput)
            if let connection = nextVideoOutput.connection(with: .video) {
                connection.videoOrientation = .portrait
                if connection.isVideoMirroringSupported {
                    connection.automaticallyAdjustsVideoMirroring = false
                    connection.isVideoMirrored = false
                }
            }
            session.commitConfiguration()
            didBeginConfiguration = false

            videoInput = nextVideoInput
            videoOutput = nextVideoOutput
            activeDevice = camera
            return applyZoom(requestedZoom)
        } catch {
            if didBeginConfiguration {
                session.commitConfiguration()
            }
            return NativeStatus(false, "1101", "相机设备不可用", error.localizedDescription)
        }
    }

    private func ensureAudioInputs() -> NativeStatus {
        return runOnCaptureQueueSync {
            if audioInput != nil && audioOutput != nil {
                return NativeStatus.ok
            }
            guard let microphone = AVCaptureDevice.default(for: .audio) else {
                return NativeStatus(false, "1002", "麦克风权限被拒绝", "No microphone device.")
            }
            var didBeginConfiguration = false
            do {
                let nextInput = try AVCaptureDeviceInput(device: microphone)
                let nextOutput = AVCaptureAudioDataOutput()
                nextOutput.setSampleBufferDelegate(self, queue: writerQueue)
                session.beginConfiguration()
                didBeginConfiguration = true
                if session.canAddInput(nextInput) {
                    session.addInput(nextInput)
                } else {
                    session.commitConfiguration()
                    return NativeStatus(false, "1401", "录像开始失败", "Cannot add microphone input.")
                }
                if session.canAddOutput(nextOutput) {
                    session.addOutput(nextOutput)
                } else {
                    session.commitConfiguration()
                    return NativeStatus(false, "1401", "录像开始失败", "Cannot add audio output.")
                }
                session.commitConfiguration()
                didBeginConfiguration = false
                audioInput = nextInput
                audioOutput = nextOutput
                return NativeStatus.ok
            } catch {
                if didBeginConfiguration {
                    session.commitConfiguration()
                }
                return NativeStatus(false, "1401", "录像开始失败", error.localizedDescription)
            }
        }
    }

    private func destroyResources() {
        destroyed = true
        setRecording(false)
        ready = false
        runOnCaptureQueueSync {
            if self.session.isRunning {
                self.session.stopRunning()
            }
            self.session.beginConfiguration()
            for input in self.session.inputs {
                self.session.removeInput(input)
            }
            for output in self.session.outputs {
                self.session.removeOutput(output)
            }
            self.session.commitConfiguration()
            self.videoInput = nil
            self.audioInput = nil
            self.videoOutput = nil
            self.audioOutput = nil
            self.activeDevice = nil
        }
        writerQueue.sync {
            if let writer = self.assetWriter, writer.status == .writing {
                writer.cancelWriting()
            }
            self.resetWriter()
        }
        activeTemplate = nil
        activeWatermarkImage = nil
        setFrozenWatermark(template: nil, image: nil)
    }

    private func validZoom(_ value: String) -> Bool {
        return value == "wide" || value == "1x" || value == "2x"
    }

    private func availableZooms() -> [String] {
        var modes = ["1x"]
        if cameraDevice(facing: .back, zoom: "wide") != nil, cameraFacing == "back" {
            modes.insert("wide", at: 0)
        }
        if let device = activeDevice, device.activeFormat.videoMaxZoomFactor >= 2.0 {
            modes.append("2x")
        }
        return modes
    }

    private func availableCameraFacings() -> [String] {
        var facings: [String] = []
        if cameraDevice(facing: .back, zoom: "1x") != nil {
            facings.append("back")
        }
        if cameraDevice(facing: .front, zoom: "1x") != nil {
            facings.append("front")
        }
        return facings
    }

    private func cameraDevice(facing position: AVCaptureDevice.Position, zoom requestedZoom: String) -> AVCaptureDevice? {
        if requestedZoom == "wide", position == .back, #available(iOS 13.0, *) {
            let devices = AVCaptureDevice.DiscoverySession(
                deviceTypes: [.builtInUltraWideCamera],
                mediaType: .video,
                position: position
            ).devices
            if let device = devices.first {
                return device
            }
        }
        if #available(iOS 10.0, *) {
            return AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: position)
        }
        return AVCaptureDevice.default(for: .video)
    }

    private func runOnCaptureQueueSync<T>(_ block: () -> T) -> T {
        if DispatchQueue.getSpecific(key: Self.captureQueueKey) == true {
            return block()
        }
        return captureQueue.sync(execute: block)
    }

    private func applyZoom(_ nextZoom: String) -> NativeStatus {
        guard let device = activeDevice else {
            return NativeStatus(false, "1101", "相机设备不可用", "No active camera device.")
        }
        if nextZoom == "wide" {
            if cameraFacing == "back", #available(iOS 13.0, *), device.deviceType == .builtInUltraWideCamera {
                zoom = "wide"
                return NativeStatus.ok
            }
            if cameraFacing == "back", let ultraWide = cameraDevice(facing: .back, zoom: "wide"), ultraWide.uniqueID != device.uniqueID {
                return configureCameraSession(facing: cameraFacing, zoom: "wide")
            }
            return NativeStatus(false, "1103", "焦段不可用", "Ultra wide camera is unavailable.")
        }
        let desiredFactor: CGFloat = nextZoom == "2x" ? 2.0 : 1.0
        if nextZoom != "wide", #available(iOS 13.0, *), device.deviceType == .builtInUltraWideCamera {
            return configureCameraSession(facing: cameraFacing, zoom: nextZoom)
        }
        guard desiredFactor <= device.activeFormat.videoMaxZoomFactor else {
            return NativeStatus(false, "1103", "焦段不可用", "Requested zoom exceeds device maximum.")
        }
        do {
            try device.lockForConfiguration()
            device.videoZoomFactor = max(1.0, desiredFactor)
            device.unlockForConfiguration()
            zoom = nextZoom
            return NativeStatus.ok
        } catch {
            return NativeStatus(false, "1103", "焦段不可用", error.localizedDescription)
        }
    }

    private func applyFlashMode(_ mode: String) -> NativeStatus {
        guard let device = activeDevice else {
            return NativeStatus(false, "1101", "相机设备不可用", "No active camera device.")
        }
        let nextMode = normalizeFlashMode(mode)
        guard device.hasTorch else {
            if nextMode == "off" {
                flashMode = "off"
                return NativeStatus.ok
            }
            return NativeStatus(false, "1102", "闪光灯不可用", "Torch is unavailable.")
        }
        do {
            try device.lockForConfiguration()
            device.torchMode = nextMode == "off" ? .off : .on
            device.unlockForConfiguration()
            flashMode = nextMode
            return NativeStatus.ok
        } catch {
            return NativeStatus(false, "1102", "闪光灯不可用", error.localizedDescription)
        }
    }

    private func emitNativeError(_ code: String, _ message: String, _ nativeMessage: String) {
        let payload = Self.jsonString([
            "errorCode": code,
            "errorMessage": message,
            "nativeMessage": nativeMessage
        ])
        eventCallback?("nativeerror", payload)
    }

    private func requestVideoAccessIfNeeded() -> NativeStatus {
        let status = AVCaptureDevice.authorizationStatus(for: .video)
        switch status {
        case .authorized:
            videoPermissionRequestPending = false
            if recordPermissionPreparationPending {
                _ = requestAudioAccessIfNeeded()
            }
            return .ok
        case .notDetermined:
            if !videoPermissionRequestPending {
                videoPermissionRequestPending = true
                AVCaptureDevice.requestAccess(for: .video) { [weak self] isGranted in
                    DispatchQueue.main.async {
                        guard let self = self else { return }
                        self.videoPermissionRequestPending = false
                        if !isGranted {
                            self.recordPermissionPreparationPending = false
                            self.emitNativeError("1001", "相机权限被拒绝", "Camera permission denied.")
                            return
                        }
                        _ = self.mountCamera(
                            NSNumber(value: Double(self.pendingPreviewWidth)),
                            NSNumber(value: Double(self.pendingPreviewHeight)),
                            self.pendingFacing,
                            self.pendingZoom,
                            self.pendingFlashEnabled
                        )
                        if self.recordPermissionPreparationPending {
                            _ = self.requestAudioAccessIfNeeded()
                        }
                    }
                }
            }
            return NativeStatus(false, "1001", "相机权限未授权", "permission request is pending")
        default:
            videoPermissionRequestPending = false
            recordPermissionPreparationPending = false
            return NativeStatus(false, "1001", "相机权限被拒绝", "Camera permission denied.")
        }
    }

    private func requestAudioAccessIfNeeded() -> NativeStatus {
        let status = AVCaptureDevice.authorizationStatus(for: .audio)
        switch status {
        case .authorized:
            audioPermissionRequestPending = false
            recordPermissionPreparationPending = false
            return .ok
        case .notDetermined:
            if !audioPermissionRequestPending {
                audioPermissionRequestPending = true
                AVCaptureDevice.requestAccess(for: .audio) { [weak self] isGranted in
                    DispatchQueue.main.async {
                        guard let self = self else { return }
                        self.audioPermissionRequestPending = false
                        if !isGranted {
                            self.emitNativeError("1002", "麦克风权限被拒绝", "Microphone permission denied.")
                            return
                        }
                        self.recordPermissionPreparationPending = false
                    }
                }
            }
            return NativeStatus(false, "1003", "请授权麦克风权限", "permission request is pending")
        default:
            audioPermissionRequestPending = false
            recordPermissionPreparationPending = false
            return NativeStatus(false, "1002", "麦克风权限被拒绝", "Microphone permission denied.")
        }
    }

    private func templateFromOptions(_ optionsJSON: String) -> EmbeddedWatermarkTemplate? {
        guard
            let data = optionsJSON.data(using: .utf8),
            let object = try? JSONSerialization.jsonObject(with: data),
            let options = object as? [String: Any],
            let template = options["watermarkTemplate"] as? [String: Any],
            !template.isEmpty
        else {
            return nil
        }
        let parsed = EmbeddedWatermarkTemplate.parse(Self.jsonString(template))
        return parsed.template
    }

    private func imageForOutputTemplate(_ template: EmbeddedWatermarkTemplate?) -> UIImage? {
        guard let template = template else { return nil }
        if activeTemplate?.templateId == template.templateId {
            return activeWatermarkImage
        }
        if frozenTemplate?.templateId == template.templateId {
            return frozenWatermarkImage
        }
        return loadImageIfNeeded(for: template).image
    }

    private func loadImageIfNeeded(for template: EmbeddedWatermarkTemplate) -> (success: Bool, image: UIImage?, code: String, message: String, nativeMessage: String) {
        guard template.requiresImage else {
            return (true, nil, "", "", "")
        }
        guard let image = loadWatermarkImage(from: template.imagePath) else {
            return (false, nil, "1202", "水印图片资源不可读或解码失败", template.imagePath)
        }
        return (true, image, "", "", "")
    }

    private func loadWatermarkImage(from rawPath: String) -> UIImage? {
        let path = rawPath.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !path.isEmpty else { return nil }
        if let url = URL(string: path), url.isFileURL {
            return UIImage(contentsOfFile: url.path)
        }
        if path.hasPrefix("file://") {
            return UIImage(contentsOfFile: String(path.dropFirst("file://".count)))
        }
        if let image = UIImage(contentsOfFile: path) {
            return image
        }
        let bundlePath = path.hasPrefix("/") ? String(path.dropFirst()) : path
        if let image = UIImage(named: bundlePath) {
            return image
        }
        let resourceURL = URL(fileURLWithPath: bundlePath)
        let resourceName = resourceURL.deletingPathExtension().path
        let resourceExtension = resourceURL.pathExtension
        if
            !resourceName.isEmpty,
            !resourceExtension.isEmpty,
            let resourcePath = Bundle.main.path(forResource: resourceName, ofType: resourceExtension)
        {
            return UIImage(contentsOfFile: resourcePath)
        }
        return nil
    }

    private func prepareWriter() throws {
        let url = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("uts-ios-embedded-watermark-\(Int(Date().timeIntervalSince1970 * 1000)).mp4")
        try? FileManager.default.removeItem(at: url)
        outputURL = url

        let writer = try AVAssetWriter(outputURL: url, fileType: .mp4)
        let videoSettings: [String: Any] = [
            AVVideoCodecKey: AVVideoCodecType.h264,
            AVVideoWidthKey: Int(videoSize.width),
            AVVideoHeightKey: Int(videoSize.height)
        ]
        let videoInput = AVAssetWriterInput(mediaType: .video, outputSettings: videoSettings)
        videoInput.expectsMediaDataInRealTime = true
        let adaptor = AVAssetWriterInputPixelBufferAdaptor(
            assetWriterInput: videoInput,
            sourcePixelBufferAttributes: [
                kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
                kCVPixelBufferWidthKey as String: Int(videoSize.width),
                kCVPixelBufferHeightKey as String: Int(videoSize.height)
            ]
        )
        guard writer.canAdd(videoInput) else {
            throw NSError(domain: "uts.markvideo.embedded", code: 1, userInfo: [NSLocalizedDescriptionKey: "Cannot add video writer input."])
        }
        writer.add(videoInput)

        let audioInput = AVAssetWriterInput(
            mediaType: .audio,
            outputSettings: [
                AVFormatIDKey: kAudioFormatMPEG4AAC,
                AVSampleRateKey: 44_100,
                AVNumberOfChannelsKey: 1,
                AVEncoderBitRateKey: 64_000
            ]
        )
        audioInput.expectsMediaDataInRealTime = true
        if writer.canAdd(audioInput) {
            writer.add(audioInput)
            writerAudioInput = audioInput
        } else {
            writerAudioInput = nil
        }

        assetWriter = writer
        writerVideoInput = videoInput
        pixelBufferAdaptor = adaptor
    }

    private func resetWriter() {
        assetWriter = nil
        writerVideoInput = nil
        writerAudioInput = nil
        pixelBufferAdaptor = nil
        outputURL = nil
        firstVideoTime = nil
        lastVideoTime = nil
        lastEncodedFrameTime = nil
        videoFrameCount = 0
    }

    private func finishRecordingOnWriterQueue() -> (success: Bool, url: URL?, durationMs: Double, nativeMessage: String) {
        guard let url = outputURL, let writer = assetWriter else {
            resetWriter()
            return (false, nil, 0, "Writer was not started.")
        }
        guard videoFrameCount > 0 else {
            writer.cancelWriting()
            try? FileManager.default.removeItem(at: url)
            resetWriter()
            return (false, nil, 0, "No frames were recorded.")
        }

        writerVideoInput?.markAsFinished()
        writerAudioInput?.markAsFinished()
        let semaphore = DispatchSemaphore(value: 0)
        writer.finishWriting {
            semaphore.signal()
        }
        if semaphore.wait(timeout: .now() + 20) == .timedOut {
            writer.cancelWriting()
            resetWriter()
            return (false, nil, 0, "Timed out while finishing video.")
        }
        guard writer.status == .completed else {
            let message = writer.error?.localizedDescription ?? "Recorder finish failed."
            try? FileManager.default.removeItem(at: url)
            resetWriter()
            return (false, nil, 0, message)
        }

        let durationMs: Double
        if let first = firstVideoTime, let last = lastVideoTime {
            durationMs = max(1, CMTimeSubtract(last, first).seconds * 1000)
        } else {
            durationMs = 0
        }
        return (true, url, durationMs, "")
    }

    public func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        if output is AVCaptureVideoDataOutput {
            if let sourceBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) {
                storeLatestVideoPixelBuffer(sourceBuffer)
            }
            guard isRecording() else { return }
            appendVideo(sampleBuffer)
        } else if output is AVCaptureAudioDataOutput {
            guard isRecording() else { return }
            appendAudio(sampleBuffer)
        }
    }

    private func appendVideo(_ sampleBuffer: CMSampleBuffer) {
        guard
            let writer = assetWriter,
            let videoInput = writerVideoInput,
            let adaptor = pixelBufferAdaptor,
            let sourceBuffer = CMSampleBufferGetImageBuffer(sampleBuffer)
        else { return }

        let timestamp = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)
        guard shouldEncodeFrame(at: timestamp) else { return }
        if writer.status == .unknown {
            writer.startWriting()
            writer.startSession(atSourceTime: timestamp)
        }
        guard writer.status == .writing, videoInput.isReadyForMoreMediaData else { return }
        guard let watermarkedBuffer = makeWatermarkedPixelBuffer(from: sourceBuffer, adaptor: adaptor) else { return }
        if adaptor.append(watermarkedBuffer, withPresentationTime: timestamp) {
            if firstVideoTime == nil {
                firstVideoTime = timestamp
            }
            lastEncodedFrameTime = timestamp
            lastVideoTime = timestamp
            videoFrameCount += 1
        }
    }

    private func appendAudio(_ sampleBuffer: CMSampleBuffer) {
        guard firstVideoTime != nil, let writer = assetWriter, writer.status == .writing else { return }
        guard let audioInput = writerAudioInput, audioInput.isReadyForMoreMediaData else { return }
        audioInput.append(sampleBuffer)
    }

    private func shouldEncodeFrame(at timestamp: CMTime) -> Bool {
        guard let last = lastEncodedFrameTime else {
            return true
        }
        let frameInterval = CMTime(value: 1, timescale: CMTimeScale(max(1, targetFps)))
        return CMTimeCompare(CMTimeSubtract(timestamp, last), frameInterval) >= 0
    }

    private func makeWatermarkedPixelBuffer(
        from sourceBuffer: CVPixelBuffer,
        adaptor: AVAssetWriterInputPixelBufferAdaptor
    ) -> CVPixelBuffer? {
        guard let pool = adaptor.pixelBufferPool else { return nil }
        var outputBuffer: CVPixelBuffer?
        CVPixelBufferPoolCreatePixelBuffer(nil, pool, &outputBuffer)
        guard let targetBuffer = outputBuffer else { return nil }
        renderCameraAlignedFrame(from: sourceBuffer, to: targetBuffer)
        let watermark = frozenWatermarkSnapshot()
        drawWatermark(into: targetBuffer, template: watermark.template, watermarkImage: watermark.image)
        return targetBuffer
    }

    private func makeWatermarkedImage(
        from sourceBuffer: CVPixelBuffer,
        template: EmbeddedWatermarkTemplate?,
        watermarkImage: UIImage?
    ) throws -> UIImage {
        var outputBuffer: CVPixelBuffer?
        CVPixelBufferCreate(
            nil,
            CVPixelBufferGetWidth(sourceBuffer),
            CVPixelBufferGetHeight(sourceBuffer),
            kCVPixelFormatType_32BGRA,
            [
                kCVPixelBufferCGImageCompatibilityKey as String: true,
                kCVPixelBufferCGBitmapContextCompatibilityKey as String: true
            ] as CFDictionary,
            &outputBuffer
        )
        guard let targetBuffer = outputBuffer else {
            throw NSError(domain: "uts.markvideo.embedded", code: 2, userInfo: [NSLocalizedDescriptionKey: "Unable to allocate photo buffer."])
        }
        renderCameraAlignedFrame(from: sourceBuffer, to: targetBuffer)
        drawWatermark(into: targetBuffer, template: template, watermarkImage: watermarkImage)
        let outputImage = CIImage(cvPixelBuffer: targetBuffer)
        guard let cgImage = ciContext.createCGImage(outputImage, from: outputImage.extent) else {
            throw NSError(domain: "uts.markvideo.embedded", code: 3, userInfo: [NSLocalizedDescriptionKey: "Unable to render photo."])
        }
        return UIImage(cgImage: cgImage, scale: 1, orientation: .up)
    }

    private func renderCameraAlignedFrame(from sourceBuffer: CVPixelBuffer, to targetBuffer: CVPixelBuffer) {
        let sourceImage = CIImage(cvPixelBuffer: sourceBuffer)
        if cameraFacing == "front" {
            let width = CGFloat(CVPixelBufferGetWidth(sourceBuffer))
            let mirrored = sourceImage.transformed(by: CGAffineTransform(translationX: width, y: 0).scaledBy(x: -1, y: 1))
            ciContext.render(mirrored, to: targetBuffer)
            return
        }
        ciContext.render(sourceImage, to: targetBuffer)
    }

    private func drawWatermark(into buffer: CVPixelBuffer, template: EmbeddedWatermarkTemplate?, watermarkImage: UIImage?) {
        guard let template = template else { return }
        CVPixelBufferLockBaseAddress(buffer, [])
        defer { CVPixelBufferUnlockBaseAddress(buffer, []) }
        guard let base = CVPixelBufferGetBaseAddress(buffer) else { return }
        let width = CVPixelBufferGetWidth(buffer)
        let height = CVPixelBufferGetHeight(buffer)
        let bytesPerRow = CVPixelBufferGetBytesPerRow(buffer)
        let colorSpace = CGColorSpaceCreateDeviceRGB()
        guard let context = CGContext(
            data: base,
            width: width,
            height: height,
            bitsPerComponent: 8,
            bytesPerRow: bytesPerRow,
            space: colorSpace,
            bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue
        ) else { return }

        context.saveGState()
        context.translateBy(x: 0, y: CGFloat(height))
        context.scaleBy(x: 1, y: -1)
        let canvas = CGSize(width: CGFloat(width), height: CGFloat(height))
        let transform = EmbeddedWatermarkRenderer.outputTransform(template: template, canvasSize: canvas)
        EmbeddedWatermarkRenderer.draw(
            template: template,
            image: watermarkImage,
            in: EmbeddedWatermarkRenderer.watermarkRect(template: template, canvasSize: canvas, transform: transform),
            transform: transform,
            context: context
        )
        context.restoreGState()
    }

    private func writePhotoTempFile(_ image: UIImage) throws -> String {
        let url = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("uts-ios-embedded-watermark-\(Int(Date().timeIntervalSince1970 * 1000)).jpg")
        guard let data = image.jpegData(compressionQuality: 0.92) else {
            throw NSError(domain: "uts.markvideo.embedded", code: 4, userInfo: [NSLocalizedDescriptionKey: "Unable to encode photo."])
        }
        try data.write(to: url, options: .atomic)
        return url.path
    }

    private func writeThumbnailTempFile(_ image: UIImage) -> String {
        let url = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("uts-ios-embedded-watermark-thumb-\(Int(Date().timeIntervalSince1970 * 1000)).jpg")
        guard let data = image.jpegData(compressionQuality: 0.82) else {
            return ""
        }
        do {
            try data.write(to: url, options: .atomic)
            return url.path
        } catch {
            return ""
        }
    }

    private func createVideoThumbnail(from url: URL) -> String {
        let asset = AVURLAsset(url: url)
        let generator = AVAssetImageGenerator(asset: asset)
        generator.appliesPreferredTrackTransform = true
        do {
            let cgImage = try generator.copyCGImage(at: .zero, actualTime: nil)
            return writeThumbnailTempFile(UIImage(cgImage: cgImage))
        } catch {
            return ""
        }
    }

    private func saveImageToGallerySynchronously(_ image: UIImage) -> (success: Bool, albumFilePath: String, nativeMessage: String) {
        let access = requestPhotoWriteAccessSynchronously()
        guard access.granted else {
            return (false, "", access.pending ? "Photo library permission is pending." : "Photo library permission denied.")
        }
        let semaphore = DispatchSemaphore(value: 0)
        var localIdentifier = ""
        var nativeMessage = ""
        PHPhotoLibrary.shared().performChanges({
            let request = PHAssetChangeRequest.creationRequestForAsset(from: image)
            localIdentifier = request.placeholderForCreatedAsset?.localIdentifier ?? ""
        }, completionHandler: { success, error in
            if !success {
                nativeMessage = error?.localizedDescription ?? "Photo save failed."
            }
            semaphore.signal()
        })
        semaphore.wait()
        return (nativeMessage.isEmpty, localIdentifier, nativeMessage)
    }

    private func saveVideoToGallerySynchronously(_ url: URL) -> (success: Bool, albumFilePath: String, nativeMessage: String) {
        let access = requestPhotoWriteAccessSynchronously()
        guard access.granted else {
            return (false, "", access.pending ? "Photo library permission is pending." : "Photo library permission denied.")
        }
        let semaphore = DispatchSemaphore(value: 0)
        var localIdentifier = ""
        var nativeMessage = ""
        PHPhotoLibrary.shared().performChanges({
            let request = PHAssetChangeRequest.creationRequestForAssetFromVideo(atFileURL: url)
            localIdentifier = request?.placeholderForCreatedAsset?.localIdentifier ?? ""
        }, completionHandler: { success, error in
            if !success {
                nativeMessage = error?.localizedDescription ?? "Video save failed."
            }
            semaphore.signal()
        })
        semaphore.wait()
        return (nativeMessage.isEmpty, localIdentifier, nativeMessage)
    }

    private func requestPhotoWriteAccessSynchronously() -> (granted: Bool, pending: Bool) {
        if #available(iOS 14, *) {
            let status = PHPhotoLibrary.authorizationStatus(for: .addOnly)
            if status == .authorized || status == .limited {
                return (true, false)
            }
            if status != .notDetermined {
                return (false, false)
            }
            if Thread.isMainThread {
                PHPhotoLibrary.requestAuthorization(for: .addOnly) { nextStatus in
                    if nextStatus != .authorized && nextStatus != .limited {
                        DispatchQueue.main.async {
                            self.emitNativeError("1501", "文件保存失败", "Photo library permission denied.")
                        }
                    }
                }
                return (false, true)
            }
            let semaphore = DispatchSemaphore(value: 0)
            var granted = false
            PHPhotoLibrary.requestAuthorization(for: .addOnly) { nextStatus in
                granted = nextStatus == .authorized || nextStatus == .limited
                semaphore.signal()
            }
            if semaphore.wait(timeout: .now() + 30) == .timedOut {
                return (false, true)
            }
            return (granted, false)
        }
        let status = PHPhotoLibrary.authorizationStatus()
        if status == .authorized {
            return (true, false)
        }
        if status != .notDetermined {
            return (false, false)
        }
        if Thread.isMainThread {
            PHPhotoLibrary.requestAuthorization { nextStatus in
                if nextStatus != .authorized {
                    DispatchQueue.main.async {
                        self.emitNativeError("1501", "文件保存失败", "Photo library permission denied.")
                    }
                }
            }
            return (false, true)
        }
        let semaphore = DispatchSemaphore(value: 0)
        var granted = false
        PHPhotoLibrary.requestAuthorization { nextStatus in
            granted = nextStatus == .authorized
            semaphore.signal()
        }
        if semaphore.wait(timeout: .now() + 30) == .timedOut {
            return (false, true)
        }
        return (granted, false)
    }

    private func photoData(
        tempFilePath: String,
        albumFilePath: String,
        albumUri: String,
        savedToAlbum: Bool,
        width: Int,
        height: Int,
        template: EmbeddedWatermarkTemplate?
    ) -> [String: Any] {
        var data = baseMediaData(
            tempFilePath: tempFilePath,
            albumFilePath: albumFilePath,
            width: width,
            height: height,
            template: template
        )
        data["durationMs"] = 0
        appendAlbumData(&data, albumPath: albumFilePath, albumUri: albumUri, savedToAlbum: savedToAlbum, kind: "photo")
        data["message"] = savedToAlbum ? "照片已保存到相册" : "照片已生成，相册保存失败"
        return data
    }

    private func videoData(
        tempFilePath: String,
        albumFilePath: String,
        albumUri: String,
        savedToAlbum: Bool,
        durationMs: Double,
        width: Int,
        height: Int,
        template: EmbeddedWatermarkTemplate?,
        thumbnailPath: String
    ) -> [String: Any] {
        var data = baseMediaData(
            tempFilePath: tempFilePath,
            albumFilePath: albumFilePath,
            width: width,
            height: height,
            template: template
        )
        data["durationMs"] = durationMs
        appendAlbumData(&data, albumPath: albumFilePath, albumUri: albumUri, savedToAlbum: savedToAlbum, kind: "video")
        if !thumbnailPath.isEmpty {
            data["thumbnailPath"] = thumbnailPath
        }
        data["message"] = savedToAlbum ? "视频已保存到相册" : "视频已生成，相册保存失败"
        return data
    }

    private func baseMediaData(
        tempFilePath: String,
        albumFilePath: String,
        width: Int,
        height: Int,
        template: EmbeddedWatermarkTemplate?
    ) -> [String: Any] {
        return [
            "tempFilePath": tempFilePath,
            "path": tempFilePath,
            "albumFilePath": albumFilePath,
            "width": width,
            "height": height,
            "watermarkTemplateId": template?.templateId ?? "",
            "watermarkPositionX": template?.positionX ?? 0,
            "watermarkPositionY": template?.positionY ?? 0,
            "watermarkBoxWidth": template?.boxWidth ?? 0,
            "watermarkBoxHeight": template?.boxHeight ?? 0,
            "watermarkTemplateSnapshot": template?.snapshot ?? [:],
            "watermarkPhotoBurnIn": template != nil,
            "watermarkVideoBurnIn": template != nil
        ]
    }

    private func appendAlbumData(
        _ data: inout [String: Any],
        albumPath: String,
        albumUri: String,
        savedToAlbum: Bool,
        kind: String
    ) {
        data["savedToAlbum"] = savedToAlbum
        data["albumPath"] = albumPath
        data["albumUri"] = albumUri
        data["mediaKind"] = kind
        data["thumbnailPath"] = kind == "photo" ? data["tempFilePath"] ?? "" : ""
    }

    private func cameraReadyPayload(requestedPreviewWidth: CGFloat, requestedPreviewHeight: CGFloat) -> [String: Any] {
        var payload = cameraStatePayload(message: "相机已准备")
        let previewSize = resolvedPreviewSize(
            requestedPreviewWidth: requestedPreviewWidth,
            requestedPreviewHeight: requestedPreviewHeight
        )
        payload["previewWidth"] = Int(previewSize.width)
        payload["previewHeight"] = Int(previewSize.height)
        payload["pictureWidth"] = Int(videoSize.width)
        payload["pictureHeight"] = Int(videoSize.height)
        payload["videoWidth"] = Int(videoSize.width)
        payload["videoHeight"] = Int(videoSize.height)
        payload["fps"] = targetFps
        return payload
    }

    private func updateReadyPreviewSize(requestedPreviewWidth: CGFloat, requestedPreviewHeight: CGFloat) -> [String: Any] {
        let data = cameraReadyPayload(
            requestedPreviewWidth: requestedPreviewWidth,
            requestedPreviewHeight: requestedPreviewHeight
        )
        lastReadyPreviewWidth = data["previewWidth"] as? Int ?? 0
        lastReadyPreviewHeight = data["previewHeight"] as? Int ?? 0
        return data
    }

    private func emitCameraReadyIfPreviewBoundsChanged() {
        guard ready, bounds.width > 1, bounds.height > 1 else { return }
        let width = Int(bounds.width.rounded())
        let height = Int(bounds.height.rounded())
        guard width != lastReadyPreviewWidth || height != lastReadyPreviewHeight else { return }
        emit("cameraready", updateReadyPreviewSize(requestedPreviewWidth: bounds.width, requestedPreviewHeight: bounds.height))
    }

    private func resolvedPreviewSize(requestedPreviewWidth: CGFloat, requestedPreviewHeight: CGFloat) -> CGSize {
        let width = requestedPreviewWidth > 1 ? requestedPreviewWidth : bounds.width
        let height = requestedPreviewHeight > 1 ? requestedPreviewHeight : bounds.height
        if width > 1, height > 1 {
            return CGSize(width: width.rounded(), height: height.rounded())
        }
        return CGSize(width: videoSize.width, height: videoSize.height)
    }

    private func cameraStatePayload(message: String) -> [String: Any] {
        return [
            "message": message,
            "mode": currentMode,
            "flashMode": flashMode,
            "flashAvailable": activeDevice?.hasTorch == true,
            "flashEnabled": flashMode != "off",
            "zoom": zoom,
            "zoomMode": zoom,
            "availableZooms": availableZooms(),
            "availableZoomModes": availableZooms(),
            "cameraFacing": cameraFacing,
            "availableCameraFacings": availableCameraFacings()
        ]
    }

    private func watermarkEventData(template: EmbeddedWatermarkTemplate?) -> [String: Any] {
        return [
            "watermarkTemplateId": template?.templateId ?? "",
            "watermarkPositionX": template?.positionX ?? 0,
            "watermarkPositionY": template?.positionY ?? 0,
            "zoom": zoom,
            "zoomMode": zoom,
            "cameraFacing": cameraFacing
        ]
    }

    private func normalizeFlashMode(_ mode: String) -> String {
        return mode == "on" ? "on" : "off"
    }

    private func flashModePayload(requestedMode: String, previousMode: String, applied: Bool) -> [String: Any] {
        return cameraStatePayload(message: applied ? flashModeMessage(flashMode) : unsupportedFlashModeMessage(requestedMode))
            .merging([
                "requestedFlashMode": requestedMode,
                "previousFlashMode": previousMode,
                "nativeFlashMode": activeDevice?.torchMode == .on ? "torch" : "off",
                "actualFlashMode": activeDevice?.torchMode == .on ? "torch" : "off",
                "supportedFlashModes": activeDevice?.hasTorch == true ? ["off", "on"] : ["off"],
                "currentMode": currentMode,
                "applied": applied
            ]) { _, next in next }
    }

    private func flashModeMessage(_ mode: String) -> String {
        if mode == "on" {
            return "闪光灯：开"
        }
        if mode == "auto" {
            return "闪光灯：自动"
        }
        return "闪光灯：关"
    }

    private func unsupportedFlashModeMessage(_ mode: String) -> String {
        if mode == "auto" {
            return "当前设备不支持自动闪光灯"
        }
        if mode == "on" {
            return "当前设备不支持闪光灯常亮"
        }
        return "当前设备不支持该闪光灯模式"
    }

    private func zoomModeMessage(_ mode: String) -> String {
        if mode == "wide" {
            return "焦段：广角"
        }
        if mode == "2x" {
            return "焦段：2x"
        }
        return "焦段：1x"
    }

    private func unsupportedZoomModeMessage(_ mode: String) -> String {
        if mode == "wide" {
            return "当前设备未暴露广角镜头"
        }
        if mode == "2x" {
            return "当前设备不支持 2x 焦段"
        }
        return "当前设备不支持该焦段"
    }

    private func cameraFacingMessage(_ facing: String) -> String {
        return facing == "front" ? "前置摄像头" : "后置摄像头"
    }

    private func unsupportedCameraFacingMessage(_ facing: String) -> String {
        return facing == "front" ? "当前设备不支持前置摄像头" : "当前设备不支持后置摄像头"
    }

    private func recordPermissionMessage(_ missing: [String]) -> String {
        if missing.contains("camera") && missing.contains("microphone") {
            return "请授权相机和麦克风权限"
        }
        if missing.contains("microphone") {
            return "请授权麦克风权限"
        }
        return "请授权相机权限"
    }

    private func rememberPublishedMedia(uri: String, kind: String) {
        lastPublishedMediaUri = uri
        lastPublishedMediaKind = kind
    }

    private func topMostViewController() -> UIViewController? {
        let root: UIViewController?
        if #available(iOS 13.0, *) {
            root = UIApplication.shared.connectedScenes
                .compactMap { $0 as? UIWindowScene }
                .flatMap { $0.windows }
                .first { $0.isKeyWindow }?
                .rootViewController
        } else {
            root = UIApplication.shared.keyWindow?.rootViewController
        }

        var current = root
        while let presented = current?.presentedViewController {
            current = presented
        }
        if let navigation = current as? UINavigationController {
            return navigation.visibleViewController ?? navigation
        }
        if let tab = current as? UITabBarController {
            return tab.selectedViewController ?? tab
        }
        return current
    }

    private func emit(_ eventName: String, _ data: [String: Any]) {
        eventCallback?(eventName, Self.jsonString(data))
    }

    private func failAndEmit(_ code: String, _ message: String, _ nativeMessage: String = "") -> String {
        emitNativeError(code, message, nativeMessage)
        return fail(code, message, nativeMessage)
    }

    private func ok(_ data: [String: Any]) -> String {
        return Self.jsonString([
            "success": true,
            "errorCode": "",
            "errorMessage": "",
            "nativeMessage": "",
            "data": data
        ])
    }

    private func fail(_ code: String, _ message: String, _ nativeMessage: String = "") -> String {
        return Self.jsonString([
            "success": false,
            "errorCode": code,
            "errorMessage": message,
            "nativeMessage": nativeMessage,
            "data": [:]
        ])
    }

    private static func jsonString(_ object: [String: Any]) -> String {
        guard JSONSerialization.isValidJSONObject(object),
              let data = try? JSONSerialization.data(withJSONObject: object, options: []),
              let text = String(data: data, encoding: .utf8)
        else {
            return "{\"success\":false,\"errorCode\":\"9001\",\"errorMessage\":\"未知原生错误\",\"nativeMessage\":\"JSON encoding failed.\",\"data\":{}}"
        }
        return text
    }
}

private struct NativeStatus {
    let success: Bool
    let code: String
    let message: String
    let nativeMessage: String

    init(_ success: Bool, _ code: String = "", _ message: String = "", _ nativeMessage: String = "") {
        self.success = success
        self.code = code
        self.message = message
        self.nativeMessage = nativeMessage
    }

    static let ok = NativeStatus(true)
}

private struct EmbeddedWatermarkTemplate {
    var templateId: String
    var templateName: String
    var templateType: String
    var mainTitleText: String
    var subtitleText: String
    var mainTitleColor: UIColor
    var subtitleColor: UIColor
    var mainTitleColorRaw: String
    var subtitleColorRaw: String
    var mainTitleFontSize: CGFloat
    var subtitleFontSize: CGFloat
    var mainTitleBold: Bool
    var subtitleBold: Bool
    var imagePath: String
    var imageMimeType: String
    var imageWidth: CGFloat
    var imageHeight: CGFloat
    var imageTextGap: CGFloat
    var opacity: CGFloat
    var boxWidth: CGFloat
    var boxHeight: CGFloat
    var boxBackgroundColor: UIColor
    var boxBackgroundColorRaw: String
    var boxRadius: CGFloat
    var boxPadding: CGFloat
    var positionX: CGFloat
    var positionY: CGFloat
    var scale: CGFloat
    var rotation: CGFloat
    var previewWidth: CGFloat
    var previewHeight: CGFloat

    var requiresImage: Bool {
        return templateType == "image"
            || templateType == "mixed"
            || templateType == "image_title_subtitle"
            || !imagePath.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    var snapshot: [String: Any] {
        return [
            "templateId": templateId,
            "templateName": templateName,
            "templateType": templateType,
            "mainTitleText": mainTitleText,
            "subtitleText": subtitleText,
            "mainTitleColor": mainTitleColorRaw,
            "subtitleColor": subtitleColorRaw,
            "mainTitleFontSize": Double(mainTitleFontSize),
            "subtitleFontSize": Double(subtitleFontSize),
            "mainTitleBold": mainTitleBold,
            "subtitleBold": subtitleBold,
            "imagePath": imagePath,
            "imageMimeType": imageMimeType,
            "imageWidth": Double(imageWidth),
            "imageHeight": Double(imageHeight),
            "imageTextGap": Double(imageTextGap),
            "opacity": Double(opacity),
            "boxWidth": Double(boxWidth),
            "boxHeight": Double(boxHeight),
            "boxBackgroundColor": boxBackgroundColorRaw,
            "boxRadius": Double(boxRadius),
            "boxPadding": Double(boxPadding),
            "positionX": Double(positionX),
            "positionY": Double(positionY),
            "scale": Double(scale),
            "rotation": Double(rotation),
            "previewWidth": Double(previewWidth),
            "previewHeight": Double(previewHeight)
        ]
    }

    static func parse(_ text: String) -> (success: Bool, template: EmbeddedWatermarkTemplate?, code: String, message: String, nativeMessage: String) {
        guard
            let data = text.data(using: .utf8),
            let object = try? JSONSerialization.jsonObject(with: data),
            let raw = object as? [String: Any]
        else {
            return invalid("Template JSON is invalid.")
        }

        let templateId = string(raw["templateId"])
        let templateName = string(raw["templateName"])
        let templateType = string(raw["templateType"], "text")
        guard !templateId.isEmpty else { return invalid("templateId is empty.") }
        guard !templateName.isEmpty else { return invalid("templateName is empty.") }
        guard ["text", "image", "mixed", "title_text", "title_subtitle_text", "image_title_subtitle"].contains(templateType) else {
            return invalid("templateType is invalid.")
        }

        let mainTitleText = string(raw["mainTitleText"])
        var subtitleText = string(raw["subtitleText"])
        var imagePath = string(raw["nativeImagePath"], string(raw["imagePath"]))
        var imageMimeType = string(raw["imageMimeType"])
        var imageWidth = number(raw["imageWidth"], 0)
        var imageHeight = number(raw["imageHeight"], 0)
        let imageTextGap = number(raw["imageTextGap"], 8)

        if templateType == "title_text" {
            subtitleText = ""
            imagePath = ""
            imageMimeType = ""
            imageWidth = 0
            imageHeight = 0
        }
        if templateType == "title_subtitle_text" || templateType == "text" {
            imagePath = ""
            imageMimeType = ""
            imageWidth = 0
            imageHeight = 0
        }

        if templateType == "title_text", mainTitleText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return invalid("title_text requires mainTitleText.")
        }
        if templateType == "text",
           mainTitleText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
            subtitleText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return invalid("text requires mainTitleText or subtitleText.")
        }
        if templateType == "title_subtitle_text",
           mainTitleText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ||
            subtitleText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return invalid("title_subtitle_text requires mainTitleText and subtitleText.")
        }
        if templateType == "image_title_subtitle" {
            guard !mainTitleText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                  !subtitleText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                  !imagePath.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                  imageMimeType == "image/png",
                  imageWidth > 0,
                  imageHeight > 0
            else {
                return invalid("image_title_subtitle image or text fields are invalid.")
            }
        }
        if templateType == "image" || templateType == "mixed" {
            guard !imagePath.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                  imageWidth > 0,
                  imageHeight > 0
            else {
                return invalid("\(templateType) image fields are invalid.")
            }
        }

        let mainTitleColorRaw = string(raw["mainTitleColor"], "#26313B")
        let subtitleColorRaw = string(raw["subtitleColor"], "#56616D")
        let boxBackgroundColorRaw = string(raw["boxBackgroundColor"], "rgba(255,255,255,0.78)")
        guard let mainTitleColor = UIColor.embeddedCameraColor(mainTitleColorRaw),
              let subtitleColor = UIColor.embeddedCameraColor(subtitleColorRaw),
              let boxBackgroundColor = UIColor.embeddedCameraColor(boxBackgroundColorRaw)
        else {
            return invalid("Color format is invalid.")
        }

        let mainTitleFontSize = number(raw["mainTitleFontSize"], 16)
        let subtitleFontSize = number(raw["subtitleFontSize"], 12)
        let boxWidth = number(raw["boxWidth"], 0.64)
        let boxHeight = number(raw["boxHeight"], 0.16)
        let boxRadius = number(raw["boxRadius"], 8)
        let boxPadding = number(raw["boxPadding"], 10)
        let opacity = number(raw["opacity"], 1)
        let positionX = number(raw["positionX"], 0.18)
        let positionY = number(raw["positionY"], 0.25)
        let scale = number(raw["scale"], 1)
        let rotation = number(raw["rotation"], 0)
        let previewWidth = number(raw["previewWidth"], 0)
        let previewHeight = number(raw["previewHeight"], 0)

        guard range(mainTitleFontSize, 0, 72) else { return invalid("mainTitleFontSize is out of range.") }
        guard range(subtitleFontSize, 0, 48) else { return invalid("subtitleFontSize is out of range.") }
        guard range(imageWidth, 0, 512) else { return invalid("imageWidth is out of range.") }
        guard range(imageHeight, 0, 512) else { return invalid("imageHeight is out of range.") }
        guard range(imageTextGap, 0, 64) else { return invalid("imageTextGap is out of range.") }
        guard range(opacity, 0, 1) else { return invalid("opacity is out of range.") }
        guard range(boxWidth, 0.1, 1) else { return invalid("boxWidth is out of range.") }
        guard range(boxHeight, 0.05, 1) else { return invalid("boxHeight is out of range.") }
        guard range(boxRadius, 0, 80) else { return invalid("boxRadius is out of range.") }
        guard range(boxPadding, 0, 80) else { return invalid("boxPadding is out of range.") }
        guard range(positionX, 0, 1) else { return invalid("positionX is out of range.") }
        guard range(positionY, 0, 1) else { return invalid("positionY is out of range.") }
        guard range(scale, 0.3, 3) else { return invalid("scale is out of range.") }

        var template = EmbeddedWatermarkTemplate(
            templateId: templateId,
            templateName: templateName,
            templateType: templateType,
            mainTitleText: mainTitleText,
            subtitleText: subtitleText,
            mainTitleColor: mainTitleColor,
            subtitleColor: subtitleColor,
            mainTitleColorRaw: mainTitleColorRaw,
            subtitleColorRaw: subtitleColorRaw,
            mainTitleFontSize: mainTitleFontSize,
            subtitleFontSize: subtitleFontSize,
            mainTitleBold: bool(raw["mainTitleBold"], true),
            subtitleBold: bool(raw["subtitleBold"], false),
            imagePath: imagePath,
            imageMimeType: imageMimeType,
            imageWidth: imageWidth,
            imageHeight: imageHeight,
            imageTextGap: imageTextGap,
            opacity: opacity,
            boxWidth: boxWidth,
            boxHeight: boxHeight,
            boxBackgroundColor: boxBackgroundColor,
            boxBackgroundColorRaw: boxBackgroundColorRaw,
            boxRadius: boxRadius,
            boxPadding: boxPadding,
            positionX: positionX,
            positionY: positionY,
            scale: scale,
            rotation: rotation,
            previewWidth: max(0, previewWidth),
            previewHeight: max(0, previewHeight)
        )
        template.clampPosition()
        return (true, template, "", "", "")
    }

    mutating func clampPosition() {
        positionX = Self.clampedRatio(positionX, upper: 1 - boxWidth)
        positionY = Self.clampedRatio(positionY, upper: 1 - boxHeight)
    }

    static func clampedRatio(_ value: CGFloat, upper: CGFloat) -> CGFloat {
        guard value.isFinite else { return 0 }
        return min(max(value, 0), max(0, upper))
    }

    private static func invalid(_ nativeMessage: String) -> (Bool, EmbeddedWatermarkTemplate?, String, String, String) {
        return (false, nil, "1201", "水印模板参数无效", nativeMessage)
    }

    private static func string(_ value: Any?, _ fallback: String = "") -> String {
        if let value = value as? String {
            return value
        }
        return fallback
    }

    private static func number(_ value: Any?, _ fallback: CGFloat) -> CGFloat {
        if let value = value as? NSNumber {
            return CGFloat(truncating: value)
        }
        if let value = value as? Double {
            return CGFloat(value)
        }
        if let value = value as? String, let number = Double(value) {
            return CGFloat(number)
        }
        return fallback
    }

    private static func bool(_ value: Any?, _ fallback: Bool) -> Bool {
        if let value = value as? Bool {
            return value
        }
        if let value = value as? NSNumber {
            return value.boolValue
        }
        return fallback
    }

    private static func range(_ value: CGFloat, _ minValue: CGFloat, _ maxValue: CGFloat) -> Bool {
        return value.isFinite && value >= minValue && value <= maxValue
    }
}

private enum EmbeddedWatermarkRenderer {
    static func outputTransform(template: EmbeddedWatermarkTemplate, canvasSize: CGSize) -> WatermarkOutputTransform {
        let previewWidth = template.previewWidth > 0 ? template.previewWidth : canvasSize.width
        let previewHeight = template.previewHeight > 0 ? template.previewHeight : canvasSize.height
        let outputToPreviewScale = max(
            previewWidth / max(1, canvasSize.width),
            previewHeight / max(1, canvasSize.height)
        )
        let previewToOutputScale = 1 / max(0.0001, outputToPreviewScale)
        let previewOffsetX = max(0, (canvasSize.width * outputToPreviewScale - previewWidth) / 2)
        let previewOffsetY = max(0, (canvasSize.height * outputToPreviewScale - previewHeight) / 2)
        return WatermarkOutputTransform(
            previewWidth: previewWidth,
            previewHeight: previewHeight,
            previewToOutputScale: previewToOutputScale,
            previewOffsetX: previewOffsetX,
            previewOffsetY: previewOffsetY
        )
    }

    static func watermarkRect(
        template: EmbeddedWatermarkTemplate,
        canvasSize: CGSize,
        transform: WatermarkOutputTransform
    ) -> CGRect {
        var next = template
        next.clampPosition()
        let boxWidth = transform.previewWidth * next.boxWidth * next.scale * transform.previewToOutputScale
        let boxHeight = transform.previewHeight * next.boxHeight * next.scale * transform.previewToOutputScale
        let left = ((transform.previewWidth * next.positionX + transform.previewOffsetX) * transform.previewToOutputScale)
            .clamped(to: 0...max(0, canvasSize.width - boxWidth))
        let top = ((transform.previewHeight * next.positionY + transform.previewOffsetY) * transform.previewToOutputScale)
            .clamped(to: 0...max(0, canvasSize.height - boxHeight))
        return CGRect(
            x: left,
            y: top,
            width: boxWidth,
            height: boxHeight
        )
    }

    static func draw(
        template: EmbeddedWatermarkTemplate,
        image: UIImage?,
        in rect: CGRect,
        transform: WatermarkOutputTransform,
        context: CGContext
    ) {
        guard rect.width > 0, rect.height > 0 else { return }
        context.saveGState()
        context.translateBy(x: rect.midX, y: rect.midY)
        context.rotate(by: template.rotation * .pi / 180)
        context.translateBy(x: -rect.midX, y: -rect.midY)
        let scaledRadius = template.boxRadius * transform.previewToOutputScale
        let radius = min(scaledRadius, min(rect.width, rect.height) / 2)
        let path = UIBezierPath(roundedRect: rect, cornerRadius: radius)
        context.setFillColor(template.boxBackgroundColor.embeddedCameraColorByMultiplyingAlpha(template.opacity).cgColor)
        context.addPath(path.cgPath)
        context.fillPath()
        context.clip(to: rect)

        UIGraphicsPushContext(context)
        let scaledPadding = template.boxPadding * transform.previewToOutputScale
        let padding = min(scaledPadding, rect.width * 0.28, rect.height * 0.35)
        var contentRect = rect.insetBy(dx: padding, dy: padding)
        guard contentRect.width > 1, contentRect.height > 1 else {
            UIGraphicsPopContext()
            context.restoreGState()
            return
        }

        let hasTitle = !template.mainTitleText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        let hasSubtitle = !template.subtitleText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        let hasText = hasTitle || hasSubtitle

        if template.requiresImage, let image = image {
            let requestedImageWidth = template.imageWidth * transform.previewToOutputScale
            let requestedImageHeight = template.imageHeight * transform.previewToOutputScale
            let maxImageWidth = hasText ? contentRect.width * 0.38 : contentRect.width
            let imageFitScale = min(
                1,
                maxImageWidth / max(1, requestedImageWidth),
                contentRect.height / max(1, requestedImageHeight)
            )
            let imageWidth = requestedImageWidth * imageFitScale
            let imageHeight = requestedImageHeight * imageFitScale
            let imageRect = CGRect(
                x: hasText ? contentRect.minX : contentRect.midX - imageWidth / 2,
                y: contentRect.midY - imageHeight / 2,
                width: imageWidth,
                height: imageHeight
            )
            image.draw(in: imageRect)
            if hasText {
                let textLeft = imageRect.maxX + template.imageTextGap * transform.previewToOutputScale
                contentRect = CGRect(
                    x: textLeft,
                    y: contentRect.minY,
                    width: max(1, contentRect.maxX - textLeft),
                    height: contentRect.height
                )
            }
        }

        let titleFont = template.mainTitleBold
            ? UIFont.boldSystemFont(ofSize: template.mainTitleFontSize * transform.previewToOutputScale)
            : UIFont.systemFont(ofSize: template.mainTitleFontSize * transform.previewToOutputScale)
        let subtitleFont = template.subtitleBold
            ? UIFont.boldSystemFont(ofSize: template.subtitleFontSize * transform.previewToOutputScale)
            : UIFont.systemFont(ofSize: template.subtitleFontSize * transform.previewToOutputScale)
        let paragraph = NSMutableParagraphStyle()
        paragraph.lineBreakMode = .byTruncatingTail
        paragraph.alignment = .left
        let titleHeight = hasTitle ? min(titleFont.lineHeight, contentRect.height) : 0
        let subtitleHeight = hasSubtitle ? min(subtitleFont.lineHeight * 2, max(0, contentRect.height - titleHeight - 2)) : 0
        let totalTextHeight = titleHeight + (hasSubtitle ? 2 + subtitleHeight : 0)
        var y = contentRect.midY - totalTextHeight / 2
        var titleRect = CGRect(x: contentRect.minX, y: y, width: contentRect.width, height: 0)
        if hasTitle {
            titleRect = CGRect(x: contentRect.minX, y: y, width: contentRect.width, height: titleHeight)
            NSAttributedString(
                string: template.mainTitleText,
                attributes: [.font: titleFont, .foregroundColor: template.mainTitleColor, .paragraphStyle: paragraph]
            ).draw(with: titleRect, options: [.usesLineFragmentOrigin, .truncatesLastVisibleLine], context: nil)
        }
        if hasSubtitle {
            y = hasTitle ? titleRect.maxY + 2 : y
            let subtitleRect = CGRect(x: contentRect.minX, y: y, width: contentRect.width, height: subtitleHeight)
            NSAttributedString(
                string: template.subtitleText,
                attributes: [.font: subtitleFont, .foregroundColor: template.subtitleColor, .paragraphStyle: paragraph]
            ).draw(with: subtitleRect, options: [.usesLineFragmentOrigin, .truncatesLastVisibleLine], context: nil)
        }
        UIGraphicsPopContext()
        context.restoreGState()
    }
}

private struct WatermarkOutputTransform {
    let previewWidth: CGFloat
    let previewHeight: CGFloat
    let previewToOutputScale: CGFloat
    let previewOffsetX: CGFloat
    let previewOffsetY: CGFloat
}

private extension CGFloat {
    func clamped(to range: ClosedRange<CGFloat>) -> CGFloat {
        return Swift.min(Swift.max(self, range.lowerBound), range.upperBound)
    }
}

private extension UIColor {
    func embeddedCameraColorByMultiplyingAlpha(_ multiplier: CGFloat) -> UIColor {
        var red = CGFloat(0)
        var green = CGFloat(0)
        var blue = CGFloat(0)
        var alpha = CGFloat(0)
        if getRed(&red, green: &green, blue: &blue, alpha: &alpha) {
            return UIColor(red: red, green: green, blue: blue, alpha: alpha * max(0, min(1, multiplier)))
        }
        return withAlphaComponent(max(0, min(1, multiplier)))
    }

    static func embeddedCameraColor(_ raw: String) -> UIColor? {
        let text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if text.hasPrefix("#") {
            return hexColor(text)
        }
        if text.lowercased().hasPrefix("rgba("), text.hasSuffix(")") {
            return rgbaColor(text)
        }
        return nil
    }

    private static func hexColor(_ raw: String) -> UIColor? {
        var hex = raw
        hex.removeFirst()
        guard hex.count == 6 || hex.count == 8 else { return nil }
        var value: UInt64 = 0
        guard Scanner(string: hex).scanHexInt64(&value) else { return nil }
        if hex.count == 8 {
            let alpha = CGFloat((value & 0xFF000000) >> 24) / 255.0
            let red = CGFloat((value & 0x00FF0000) >> 16) / 255.0
            let green = CGFloat((value & 0x0000FF00) >> 8) / 255.0
            let blue = CGFloat(value & 0x000000FF) / 255.0
            return UIColor(red: red, green: green, blue: blue, alpha: alpha)
        }
        let red = CGFloat((value & 0xFF0000) >> 16) / 255.0
        let green = CGFloat((value & 0x00FF00) >> 8) / 255.0
        let blue = CGFloat(value & 0x0000FF) / 255.0
        return UIColor(red: red, green: green, blue: blue, alpha: 1)
    }

    private static func rgbaColor(_ raw: String) -> UIColor? {
        let inside = raw.dropFirst(5).dropLast()
        let parts = inside.split(separator: ",").map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
        guard parts.count == 4,
              let redValue = Double(parts[0]),
              let greenValue = Double(parts[1]),
              let blueValue = Double(parts[2]),
              let alphaValue = Double(parts[3]),
              redValue >= 0, redValue <= 255,
              greenValue >= 0, greenValue <= 255,
              blueValue >= 0, blueValue <= 255,
              alphaValue >= 0, alphaValue <= 1
        else {
            return nil
        }
        return UIColor(
            red: CGFloat(redValue / 255),
            green: CGFloat(greenValue / 255),
            blue: CGFloat(blueValue / 255),
            alpha: CGFloat(alphaValue)
        )
    }
}
