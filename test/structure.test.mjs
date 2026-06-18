import { access, readFile } from 'node:fs/promises';
import path from 'node:path';
import test from 'node:test';
import assert from 'node:assert/strict';

const root = path.resolve(import.meta.dirname, '..');

const files = {
  readme: 'README.md',
  api: 'docs/api.md',
  prd: 'docs/prd-embedded-camera-component.md',
  page: 'pages/index/index.vue',
  nvuePage: 'pages/index/index.nvue',
  interface: 'uni_modules/uts-markvideo/utssdk/interface.uts',
  androidFunctionBridge: 'uni_modules/uts-markvideo/utssdk/app-android/index.uts',
  androidComponent: 'uni_modules/uts-markvideo/utssdk/app-android/index.vue',
  androidEmbeddedView: 'uni_modules/uts-markvideo/utssdk/app-android/MarkVideoEmbeddedCameraView.kt',
  androidActivity: 'uni_modules/uts-markvideo/utssdk/app-android/MarkVideoCameraActivity.kt',
  androidNative: 'uni_modules/uts-markvideo/utssdk/app-android/MarkVideoNative.kt',
  androidManifest: 'uni_modules/uts-markvideo/utssdk/app-android/AndroidManifest.xml',
  iosBridge: 'uni_modules/uts-markvideo/utssdk/app-ios/index.uts',
  iosRecorder: 'uni_modules/uts-markvideo/utssdk/app-ios/MarkVideoRecorder.swift',
  iosPlist: 'uni_modules/uts-markvideo/utssdk/app-ios/Info.plist',
};

async function text(key) {
  return readFile(path.join(root, files[key]), 'utf8');
}

test('project contains the embedded camera component files and compatibility files', async () => {
  const required = [
    'README.md',
    'docs/api.md',
    'docs/roadmap.md',
    'docs/prd-embedded-camera-component.md',
    'App.vue',
    'main.js',
    'manifest.json',
    'pages.json',
    'pages/index/index.vue',
    'pages/index/index.nvue',
    'uni_modules/uts-markvideo/package.json',
    'uni_modules/uts-markvideo/utssdk/interface.uts',
    'uni_modules/uts-markvideo/utssdk/app-android/index.vue',
    'uni_modules/uts-markvideo/utssdk/app-android/MarkVideoEmbeddedCameraView.kt',
    'uni_modules/uts-markvideo/utssdk/app-android/index.uts',
    'uni_modules/uts-markvideo/utssdk/app-android/MarkVideoCameraActivity.kt',
    'uni_modules/uts-markvideo/utssdk/app-android/MarkVideoNative.kt',
    'uni_modules/uts-markvideo/utssdk/app-android/AndroidManifest.xml',
    'uni_modules/uts-markvideo/utssdk/app-ios/index.uts',
    'uni_modules/uts-markvideo/utssdk/app-ios/Info.plist',
    'uni_modules/uts-markvideo/utssdk/app-ios/MarkVideoRecorder.swift',
  ];

  for (const file of required) {
    await access(path.join(root, file));
  }
});

test('PRD defines host-owned watermark styles and embedded component behavior', async () => {
  const prd = await text('prd');

  assert.match(prd, /嵌入页面/);
  assert.match(prd, /宿主页面负责/);
  assert.match(prd, /水印样式由宿主应用维护/);
  assert.match(prd, /组件只需要提供“接受当前水印样式”的能力/);
  assert.match(prd, /拍照是同一个嵌入式相机组件的一部分/);
  assert.match(prd, /保存到系统相册即可，不提供相册目录名称配置/);
  assert.match(prd, /预览显示方式不提供裁切、完整显示、拉伸/);
  assert.match(prd, /nvue\/uvue/);
});

test('Android UTS component entry exposes the embedded camera component contract', async () => {
  const component = await text('androidComponent');

  assert.match(component, /name:\s*'uts-markvideo-camera'/);
  assert.match(component, /emits:\s*\['ready', 'recordstart', 'recordstop', 'photo', 'watermarkchange', 'error'\]/);
  assert.match(component, /NVLoad\(\)\s*:\s*MarkVideoEmbeddedCameraView/);
  assert.match(component, /new MarkVideoEmbeddedCameraView\(\$androidContext!\)/);
  assert.match(component, /this\.\$emit\(eventName, payload\)/);
  assert.match(component, /startRecord\(options\s*:\s*UTSJSONObject \| null = null\)/);
  assert.match(component, /setWatermarkStyleFlat\(/);
  assert.match(component, /setWatermarkStyle\(style\s*:\s*UTSJSONObject \| null\)/);
  assert.match(component, /getString\(key, fallback\)/);
  assert.match(component, /getNumber\(key, fallback\)/);
  assert.match(component, /getBoolean\(key, fallback\)/);
  assert.doesNotMatch(component, /any = null/);
  assert.doesNotMatch(component, /source\[key\]/);

  for (const method of [
    'startRecord',
    'stopRecord',
    'takePhoto',
    'switchCamera',
    'setWatermarkStyle',
    'clearWatermarkStyle',
    'getWatermarkPosition',
  ]) {
    assert.match(component, new RegExp(`'${method}'`));
    assert.match(component, new RegExp(`${method}\\(`));
  }
});

test('Android embedded view owns preview, recording, photo, watermark, drag, and gallery output', async () => {
  const view = await text('androidEmbeddedView');

  assert.match(view, /class MarkVideoEmbeddedCameraView\(context: Context\) : FrameLayout\(context\)/);
  assert.match(view, /TextureView/);
  assert.match(view, /WatermarkOverlayView/);
  assert.match(view, /fun startRecord\(/);
  assert.match(view, /fun stopRecord\(\)/);
  assert.match(view, /fun takePhoto\(\)/);
  assert.match(view, /if \(!hasCameraPermission\(\)\)/);
  assert.match(view, /includeAudioPermission = false/);
  assert.match(view, /fun switchCamera\(\)/);
  assert.match(view, /fun setWatermarkStyle\(/);
  assert.match(view, /fun clearWatermarkStyle\(\)/);
  assert.match(view, /fun getWatermarkPosition\(\): String/);
  assert.match(view, /cancelActiveRecording\(\)/);
  assert.match(view, /handleWatermarkOverlayTouch/);
  assert.match(view, /ViewConfiguration\.getLongPressTimeout/);
  assert.match(view, /drawWatermarkOnCanvas\(canvas, width, height\)/);
  assert.match(view, /watermarkStyle\.text\.split\('\\n'\)/);
  assert.match(view, /measureMaxLineWidth/);
  assert.match(view, /max\(24f, bandRect\.width\(\) - contentPadding \* 2f - logoWidth - contentGap\)/);
  assert.match(view, /textLines\.forEachIndexed/);
  assert.match(view, /return text\.isNotBlank\(\) \|\| imagePath\.isNotBlank\(\)/);
  assert.match(view, /MediaStore\.Video\.Media/);
  assert.match(view, /MediaStore\.Images\.Media/);
  assert.match(view, /Environment\.DIRECTORY_MOVIES/);
  assert.match(view, /Environment\.DIRECTORY_PICTURES/);
  assert.doesNotMatch(view, /Movies\/uts-markvideo/);
  assert.doesNotMatch(view, /Pictures\/uts-markvideo/);
});

test('embedded view keeps watermark options flat across the native boundary', async () => {
  const component = await text('androidComponent');
  const nvuePage = await text('nvuePage');
  const view = await text('androidEmbeddedView');
  const api = await text('api');
  const page = await text('page');

  for (const field of [
    'text',
    'imagePath',
    'x',
    'y',
    'textColor',
    'fontSize',
    'textBold',
    'imageWidth',
    'imageHeight',
    'imageGap',
    'boxWidth',
    'boxHeight',
    'backgroundColor',
    'borderRadius',
    'padding',
  ]) {
    assert.match(component, new RegExp(`'${field}'`));
    assert.match(page, new RegExp(`${field}:`));
    assert.match(nvuePage, new RegExp(`${field}:`));
  }

  assert.match(view, /data class WatermarkStyle/);
  assert.match(api, /水印字段保持扁平/);
  assert.match(api, /JSONObject/);
  assert.doesNotMatch(component, /textStyle/);
  assert.doesNotMatch(component, /imageStyle/);
  assert.doesNotMatch(component, /boxStyle/);
});

test('demo page uses the embedded component and host-owned controls', async () => {
  const page = await text('page');
  const nvuePage = await text('nvuePage');

  for (const demoPage of [page, nvuePage]) {
    assert.match(demoPage, /<uts-markvideo-camera/);
    assert.match(demoPage, /ref="camera"/);
    assert.match(demoPage, /@recordstop="handleRecordStop"/);
    assert.match(demoPage, /watermarkStyles:\s*\[/);
    assert.match(demoPage, /applyWatermarkStyle\(style\)/);
    assert.match(demoPage, /cameraMethod\(methodName\)/);
    assert.match(demoPage, /typeof method !== 'function'/);
    assert.match(demoPage, /startRecord\(\{/);
    assert.match(demoPage, /stopRecord\(\)/);
    assert.match(demoPage, /takePhoto\(\)/);
    assert.match(demoPage, /setWatermarkStyleFlat\(/);
    assert.match(demoPage, /clearWatermarkStyle\(\)/);
    assert.match(demoPage, /this\.facing = this\.facing === 'front' \? 'back' : 'front'/);
    assert.match(demoPage, /enablePhoto/);
    assert.match(demoPage, /v-if="videoPath"/);
    assert.match(demoPage, /savedFilePath/);
    assert.doesNotMatch(demoPage, /recordWatermarkVideo/);
    assert.doesNotMatch(demoPage, /Open camera recorder/);
    assert.doesNotMatch(demoPage, /v-model\.number="width"/);
    assert.doesNotMatch(demoPage, /v-model\.number="height"/);
  }
});

test('API docs describe embedded component first and legacy recordWatermarkVideo second', async () => {
  const api = await text('api');
  const readme = await text('readme');

  assert.match(api, /主入口：内嵌相机组件/);
  assert.match(api, /nvue\/uvue/);
  assert.match(api, /<uts-markvideo-camera/);
  assert.match(api, /组件使用者必须在页面样式里指定组件宽高/);
  assert.match(api, /setWatermarkStyle/);
  assert.match(api, /clearWatermarkStyle/);
  assert.match(api, /getWatermarkPosition/);
  assert.match(api, /视频保存到系统相册，不提供相册目录名称配置/);
  assert.match(api, /照片保存到系统相册，不提供相册目录名称配置/);
  assert.match(api, /兼容入口：`recordWatermarkVideo\(\)`/);
  assert.doesNotMatch(api, /previewFit/);

  assert.match(readme, /页面内嵌组件/);
  assert.match(readme, /pages\/index\/index\.nvue/);
  assert.match(readme, /旧的 `recordWatermarkVideo\(\)` 独立原生录制页仍然保留/);
});

test('legacy recordWatermarkVideo API remains available for compatibility', async () => {
  const interfaceText = await text('interface');
  const androidBridge = await text('androidFunctionBridge');
  const iosBridge = await text('iosBridge');
  const nativeBridge = await text('androidNative');
  const activity = await text('androidActivity');

  assert.match(interfaceText, /RecordWatermarkVideo/);
  assert.match(interfaceText, /MarkVideoWatermarkOptions/);
  assert.match(interfaceText, /enablePhoto\?: boolean/);
  assert.doesNotMatch(interfaceText, /previewFit/);
  assert.match(androidBridge, /export const recordWatermarkVideo/);
  assert.match(androidBridge, /options\.watermark\?\.imagePath/);
  assert.match(androidBridge, /options\.camera\?\.enablePhoto/);
  assert.match(androidBridge, /decodePathList\(photoSavedFilePathsText\)/);
  assert.match(iosBridge, /recordWatermarkVideo/);
  assert.match(nativeBridge, /openCameraRecorder/);
  assert.match(activity, /class MarkVideoCameraActivity : Activity/);
});

test('legacy bridge still avoids nested UTS object and UTSArray callback issues', async () => {
  const interfaceText = await text('interface');
  const androidBridge = await text('androidFunctionBridge');
  const nativeBridge = await text('androidNative');

  assert.doesNotMatch(interfaceText, /MarkVideoWatermarkTextStyle/);
  assert.doesNotMatch(interfaceText, /textStyle\?:/);
  assert.doesNotMatch(androidBridge, /textStyle/);
  assert.match(nativeBridge, /pendingRecordSuccess: \(\(String, String, Number/);
  assert.match(nativeBridge, /encodePathList\(photoTempFilePaths\)/);
  assert.match(nativeBridge, /encodePathList\(photoSavedFilePaths\)/);
  assert.doesNotMatch(nativeBridge, /onSuccess: \(String, String, Long, Int, Int, String, Array<String>, Array<String>/);
});

test('embedded and legacy paths expose stable media error codes', async () => {
  const api = await text('api');
  const page = await text('page');
  const nativeBridge = await text('androidNative');
  const view = await text('androidEmbeddedView');

  for (const code of ['1000', '1001', '1002', '1003', '1004', '1005', '1006', '1007', '1008', '1009']) {
    assert.match(api, new RegExp(`\`${code}\``));
    assert.match(page, new RegExp(`${code}:`));
  }

  assert.match(nativeBridge, /ERR_PERMISSION_DENIED = 1001/);
  assert.match(nativeBridge, /ERR_NO_FRAMES = 1006/);
  assert.match(nativeBridge, /ERR_PHOTO_CAPTURE_FAILED = 1009/);
  assert.match(view, /emitError\(MarkVideoNative\.ERR_PERMISSION_DENIED/);
  assert.match(view, /MarkVideoNative\.ERR_NO_FRAMES/);
  assert.match(view, /MarkVideoNative\.ERR_PHOTO_CAPTURE_FAILED/);
});

test('Android embedded recorder reports frame stats and uses TextureView snapshots', async () => {
  const view = await text('androidEmbeddedView');
  const page = await text('page');

  assert.match(view, /recordFrameStats\.received \+= 1/);
  assert.match(view, /recordFrameStats\.droppedBusy \+= 1/);
  assert.match(view, /recordFrameStats\.processed \+= 1/);
  assert.match(view, /recordFrameStats\.encoded \+= 1/);
  assert.match(view, /cleanupRecorderState\(deleteFile = true\)/);
  assert.match(view, /cameraSessionReady/);
  assert.match(view, /openingCameraSession/);
  assert.match(view, /resetCameraAfterRecording\(\)/);
  assert.match(view, /closeCamera\(releaseSurface = false\)/);
  assert.match(view, /isClosedCameraDeviceError\(throwable\)/);
  assert.match(view, /CameraDevice was already closed/);
  assert.match(view, /CAMERA_READY_START_RETRY_COUNT/);
  assert.match(view, /outputFile = file[\s\S]*recorder = nextRecorder[\s\S]*nextRecorder\.start\(\)/);
  assert.match(view, /catch \(throwable: Throwable\) \{[\s\S]*finish\(\)[\s\S]*throw throwable/);
  assert.match(view, /previewView\.getBitmap\(snapshotTarget\)/);
  assert.match(view, /updateRepeatingRequest\(\)/);
  assert.match(view, /set\(CaptureRequest\.CONTROL_AE_TARGET_FPS_RANGE, range\)/);
  assert.match(view, /scheduleNextPreviewSnapshotFrame/);
  assert.match(view, /CameraMp4Recorder/);
  assert.match(view, /MediaCodec/);
  assert.match(view, /MediaMuxer/);
  assert.match(view, /RECORD_RESTART_COOLDOWN_MS/);
  assert.match(view, /lastRecordingFinishedAtMs/);
  assert.match(view, /Recorder start failed: \$\{throwable\.javaClass\.simpleName\}/);
  assert.match(page, /formatStats\(res\.stats, res\.durationMs\)/);
  assert.match(page, /实际约 \$\{actualFps\}fps/);
});

test('nvue page uses only class selectors supported by nvue css', async () => {
  const app = await readFile(path.join(root, 'App.vue'), 'utf8');
  const nvuePage = await text('nvuePage');

  assert.doesNotMatch(app, /\bbody\s*\{/);
  assert.doesNotMatch(app, /\bpage\s*\{/);
  assert.doesNotMatch(nvuePage, /\[[^\]]+\]\s*\{/);
  assert.doesNotMatch(nvuePage, /\.button\[disabled\]/);
  assert.doesNotMatch(nvuePage, /<button/);
  assert.doesNotMatch(nvuePage, /width:\s*100%/);
  assert.match(nvuePage, /\.cameraFrame\s*\{[\s\S]*width:\s*702rpx[\s\S]*height:\s*936rpx/);
  assert.match(nvuePage, /\.cameraView\s*\{[\s\S]*width:\s*702rpx[\s\S]*height:\s*936rpx/);
  assert.match(nvuePage, /\.actionButton\s*\{[\s\S]*width:\s*300rpx[\s\S]*height:\s*72rpx/);
  assert.match(nvuePage, /\.disabled\s*\{/);
});

test('native manifests and iOS compatibility files remain present', async () => {
  const manifest = await readFile(path.join(root, 'manifest.json'), 'utf8');
  const androidManifest = await text('androidManifest');
  const iosPlist = await text('iosPlist');
  const swift = await text('iosRecorder');

  assert.match(androidManifest, /android\.permission\.CAMERA/);
  assert.match(androidManifest, /android\.permission\.RECORD_AUDIO/);
  assert.match(androidManifest, /MarkVideoCameraActivity/);
  assert.match(manifest, /NSCameraUsageDescription/);
  assert.match(manifest, /NSMicrophoneUsageDescription/);
  assert.match(iosPlist, /NSCameraUsageDescription/);
  assert.match(iosPlist, /NSMicrophoneUsageDescription/);
  assert.match(swift, /AVCaptureSession/);
  assert.match(swift, /AVAssetWriter/);
});

test('Vue 3 app entry is declared in manifest', async () => {
  const main = await readFile(path.join(root, 'main.js'), 'utf8');
  const manifest = JSON.parse(await readFile(path.join(root, 'manifest.json'), 'utf8'));

  assert.match(main, /createSSRApp/);
  assert.equal(manifest.vueVersion, '3');
  assert.equal(manifest['app-plus'].usingComponents, true);
  assert.equal(manifest['app-plus'].nvueCompiler, 'uni-app');
  assert.equal(manifest['app-plus'].nvueStyleCompiler, 'uni-app');
});

test('GitHub Actions workflow can request Android or iOS cloud packages', async () => {
  const workflow = await readFile(
    path.join(root, '.github/workflows/cloud-package.yml'),
    'utf8',
  );

  assert.match(workflow, /workflow_dispatch/);
  assert.match(workflow, /DCLOUD_USERNAME/);
  assert.match(workflow, /DCLOUD_PASSWORD/);
  assert.match(workflow, /HBuilderX/);
  assert.match(workflow, /cli.*pack/s);
  assert.match(workflow, /platform/);
  assert.match(workflow, /android/);
  assert.match(workflow, /ios/);
  assert.match(workflow, /actions\/upload-artifact/);
});
