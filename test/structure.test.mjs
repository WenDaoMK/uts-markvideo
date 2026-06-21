import { access, readFile } from 'node:fs/promises';
import path from 'node:path';
import test from 'node:test';
import assert from 'node:assert/strict';

const root = path.resolve(import.meta.dirname, '..');

const requiredFiles = [
  'App.vue',
  'main.js',
  'manifest.json',
  'pages.json',
  'pages/index/index.nvue',
  'pages/cameraX/index.nvue',
  'uni_modules/xyc-markvideo/package.json',
  'uni_modules/xyc-markvideo/utssdk/app-android/AndroidManifest.xml',
  'uni_modules/xyc-markvideo/utssdk/app-android/XycNativeCameraView.kt',
  'uni_modules/xyc-markvideo/utssdk/app-android/index.vue',
  'uni_modules/xyc-markvideo/utssdk/app-ios/index.vue',
];

const removedPaths = [
  'pages/index/index.vue',
  'pages/index/cameraService.js',
  'pages/camera',
  'pages/camera/camera.vue',
  'uni_modules/uts-markvideo',
  'uni_modules/uts-markvideo/package.json',
];

async function exists(relativePath) {
  try {
    await access(path.join(root, relativePath));
    return true;
  } catch {
    return false;
  }
}

test('project contains the xyc-markvideo cameraX mainline files', async () => {
  for (const file of requiredFiles) {
    await access(path.join(root, file));
  }
});

test('legacy uts-markvideo route and plugin files are deleted', async () => {
  for (const file of removedPaths) {
    assert.equal(await exists(file), false, `${file} should not exist`);
  }
});

test('pages.json routes only the new nvue camera mainline', async () => {
  const pagesJson = JSON.parse(await readFile(path.join(root, 'pages.json'), 'utf8'));
  const paths = pagesJson.pages.map((page) => page.path);

  assert.deepEqual(paths, [
    'pages/index/index',
    'pages/cameraX/index',
  ]);
  assert.doesNotMatch(JSON.stringify(pagesJson), /pages\/camera\/camera/);
});

test('index.nvue navigates directly to cameraX without watermark payload setup', async () => {
  const page = await readFile(path.join(root, 'pages/index/index.nvue'), 'utf8');

  assert.match(page, /uni\.navigateTo\(\{[\s\S]*url: '\/pages\/cameraX\/index'/);
  assert.match(page, /30 fps/);
  assert.doesNotMatch(page, /DEFAULT_TEMPLATES/);
  assert.doesNotMatch(page, /xyc-camera-payload/);
  assert.doesNotMatch(page, /embedded-camera-payload/);
  assert.doesNotMatch(page, /uts-markvideo/);
  assert.doesNotMatch(page, /recordWatermarkVideo/);
  assert.doesNotMatch(page, /setWatermark|clearWatermark|watermarkTemplate/);
});

test('cameraX nvue page owns UI and calls xyc-markvideo native camera methods', async () => {
  const page = await readFile(path.join(root, 'pages/cameraX/index.nvue'), 'utf8');

  assert.match(page, /<xyc-markvideo/);
  assert.match(page, /ref="nativeCamera"/);
  assert.match(page, /:target-fps="targetFps"/);
  assert.match(page, /targetFps: 30/);
  assert.match(page, /@cameraready="handleCameraReady"/);
  assert.match(page, /@photodone="handlePhotoDone"/);
  assert.match(page, /@recordstart="handleRecordStart"/);
  assert.match(page, /@recorddone="handleRecordDone"/);
  assert.match(page, /resolveNativeCamera\(\)/);
  assert.match(page, /nativeCamera\.switchMode\(mode\)/);
  assert.match(page, /nativeCamera\.takePhoto\(\)/);
  assert.match(page, /handlePhotoDone\(event\)/);
  assert.match(page, /拍照请求已受理|拍照中/);
  assert.match(page, /nativeCamera\.startRecord\(\{ fps: this\.targetFps \}\)/);
  assert.match(page, /nativeCamera\.stopRecord\(\)/);
  assert.match(page, /nativeCamera\.restartCamera\(\)/);
  assert.match(page, /class="topBar"/);
  assert.match(page, /class="bottomPanel"/);
  assert.match(page, /视频/);
  assert.match(page, /照片/);
  assert.doesNotMatch(page, /<uts-markvideo-camera/);
  assert.doesNotMatch(page, /@\/uni_modules\/uts-markvideo/);
  assert.doesNotMatch(page, /recordWatermarkVideo/);
  assert.doesNotMatch(page, /setWatermark|clearWatermark|watermarkTemplate|watermarkFrame/);
});

test('xyc-markvideo package advertises Android nvue component support only', async () => {
  const pkg = JSON.parse(await readFile(
    path.join(root, 'uni_modules/xyc-markvideo/package.json'),
    'utf8',
  ));

  assert.equal(pkg.id, 'xyc-markvideo');
  assert.equal(pkg.name, 'xyc-markvideo');
  assert.equal(pkg.dcloudext.type, 'component-uts');
  assert.equal(pkg.uni_modules.platforms.client['uni-app'].app.nvue, 'y');
  assert.equal(pkg.uni_modules.platforms.client['uni-app'].app.android, 'y');
  assert.equal(pkg.uni_modules.platforms.client['uni-app'].app.ios, '-');
  assert.equal(pkg.uni_modules.platforms.client['uni-app-x'].app.android, 'y');
  assert.equal(pkg.uni_modules.platforms.client['uni-app-x'].app.ios, '-');
});

test('xyc-markvideo Android component bridges to native camera view', async () => {
  const android = await readFile(
    path.join(root, 'uni_modules/xyc-markvideo/utssdk/app-android/index.vue'),
    'utf8',
  );

  assert.match(android, /name: 'xyc-markvideo'/);
  assert.match(android, /import \{ XycNativeCameraView \} from 'uts\.xyc\.markvideo\.android'/);
  assert.match(android, /NVLoad\(\) : FrameLayout/);
  assert.match(android, /new XycNativeCameraView/);
  assert.match(android, /new XycNativeCameraView\(\$androidContext!\)/);
  assert.match(android, /'nativeviewready'/);
  assert.match(android, /'cameraready'/);
  assert.match(android, /'photodone'/);
  assert.match(android, /'recordstart'/);
  assert.match(android, /'recorddone'/);
  assert.match(android, /expose: \['setStatus', 'switchMode', 'takePhoto', 'startRecord', 'stopRecord', 'restartCamera', 'destroyCamera'\]/);
  assert.match(android, /switchMode\(mode : string\)/);
  assert.match(android, /takePhoto\(\)/);
  assert.match(android, /startRecord\(options : any = \{\}\)/);
  assert.match(android, /stopRecord\(\)/);
  assert.doesNotMatch(android, /createPendingResult/);
  assert.doesNotMatch(android, /待接入/);
  assert.doesNotMatch(android, /uts-markvideo/);
  assert.doesNotMatch(android, /recordWatermarkVideo/);
});

test('xyc-markvideo Android native view uses camera preview, photo, and 30fps recording', async () => {
  const nativeView = await readFile(
    path.join(root, 'uni_modules/xyc-markvideo/utssdk/app-android/XycNativeCameraView.kt'),
    'utf8',
  );

  assert.match(nativeView, /class XycNativeCameraView/);
  assert.match(nativeView, /SurfaceView/);
  assert.match(nativeView, /Camera\.open/);
  assert.match(nativeView, /takePicture/);
  assert.match(nativeView, /拍照请求已受理/);
  assert.match(nativeView, /MediaRecorder/);
  assert.match(nativeView, /setVideoFrameRate\(targetFps\)/);
  assert.match(nativeView, /DEFAULT_TARGET_FPS = 30/);
  assert.match(nativeView, /setPreviewFpsRange/);
  assert.match(nativeView, /RECORD_AUDIO/);
  assert.doesNotMatch(nativeView, /Watermark|watermark|setWatermark|clearWatermark/);
});

test('xyc-markvideo Android manifest declares camera and microphone permissions', async () => {
  const androidManifest = await readFile(
    path.join(root, 'uni_modules/xyc-markvideo/utssdk/app-android/AndroidManifest.xml'),
    'utf8',
  );

  assert.match(androidManifest, /android\.permission\.CAMERA/);
  assert.match(androidManifest, /android\.permission\.RECORD_AUDIO/);
});

test('Vue 3 app entry is still declared in manifest', async () => {
  const main = await readFile(path.join(root, 'main.js'), 'utf8');
  const manifest = JSON.parse(await readFile(path.join(root, 'manifest.json'), 'utf8'));

  assert.match(main, /createSSRApp/);
  assert.equal(manifest.vueVersion, '3');
});
