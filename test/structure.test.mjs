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
  'uni_modules/xyc-markvideo/utssdk/app-android/index.vue',
  'uni_modules/xyc-markvideo/utssdk/app-ios/index.vue',
];

test('project contains the xyc-markvideo cameraX mainline files', async () => {
  for (const file of requiredFiles) {
    await access(path.join(root, file));
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

test('index.nvue stores xyc payload and navigates to cameraX', async () => {
  const page = await readFile(path.join(root, 'pages/index/index.nvue'), 'utf8');

  assert.match(page, /const DEFAULT_TEMPLATES/);
  assert.match(page, /templateId: 'title-only'/);
  assert.match(page, /templateId: 'title-subtitle'/);
  assert.match(page, /templateId: 'png-title-subtitle'/);
  assert.match(page, /uni\.setStorageSync\('xyc-camera-payload'/);
  assert.match(page, /uni\.navigateTo\(\{[\s\S]*url: '\/pages\/cameraX\/index'/);
  assert.doesNotMatch(page, /embedded-camera-payload/);
  assert.doesNotMatch(page, /uts-markvideo/);
  assert.doesNotMatch(page, /recordWatermarkVideo/);
});

test('cameraX nvue page owns UI and calls xyc-markvideo component methods', async () => {
  const page = await readFile(path.join(root, 'pages/cameraX/index.nvue'), 'utf8');

  assert.match(page, /<xyc-markvideo/);
  assert.match(page, /ref="nativeCamera"/);
  assert.match(page, /@nativeviewready="handleNativeReady"/);
  assert.match(page, /uni\.getStorageSync\('xyc-camera-payload'\)/);
  assert.match(page, /resolveNativeCamera\(\)/);
  assert.match(page, /nativeCamera\.switchMode\(mode\)/);
  assert.match(page, /nativeCamera\.takePhoto\(\)/);
  assert.match(page, /nativeCamera\.startRecord\(\)/);
  assert.match(page, /nativeCamera\.stopRecord\(\)/);
  assert.match(page, /normalizeNativeResult\(result, fallbackMessage\)/);
  assert.match(page, /拍照能力待接入/);
  assert.match(page, /录像能力待接入/);
  assert.match(page, /闪光灯原生能力待接入/);
  assert.match(page, /焦段 \$\{zoom\} 原生能力待接入/);
  assert.match(page, /class="topBar"/);
  assert.match(page, /class="watermarkFrame"/);
  assert.match(page, /class="zoomRail"/);
  assert.match(page, /class="bottomPanel"/);
  assert.match(page, /class="templatePanel"/);
  assert.match(page, /视频/);
  assert.match(page, /照片/);
  assert.match(page, /广角/);
  assert.doesNotMatch(page, /<uts-markvideo-camera/);
  assert.doesNotMatch(page, /@\/uni_modules\/uts-markvideo/);
  assert.doesNotMatch(page, /recordWatermarkVideo/);
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

test('xyc-markvideo Android component exposes the first native camera surface contract', async () => {
  const android = await readFile(
    path.join(root, 'uni_modules/xyc-markvideo/utssdk/app-android/index.vue'),
    'utf8',
  );

  assert.match(android, /name: 'xyc-markvideo'/);
  assert.match(android, /NVLoad\(\) : FrameLayout/);
  assert.match(android, /'nativeviewready'/);
  assert.match(android, /expose: \['setStatus', 'switchMode', 'takePhoto', 'startRecord', 'stopRecord'\]/);
  assert.match(android, /switchMode\(mode : string\)/);
  assert.match(android, /takePhoto\(\)/);
  assert.match(android, /startRecord\(\)/);
  assert.match(android, /stopRecord\(\)/);
  assert.match(android, /currentMode: 'video'/);
  assert.match(android, /const message = '拍照能力待接入'/);
  assert.match(android, /const result = createPendingResult\(message\)/);
  assert.match(android, /errorCode: '9001'/);
  assert.doesNotMatch(android, /setMode\(mode/);
  assert.doesNotMatch(android, /uts-markvideo/);
  assert.doesNotMatch(android, /recordWatermarkVideo/);
});

test('legacy uts-markvideo files are not part of the new registered route chain', async () => {
  const pagesJson = await readFile(path.join(root, 'pages.json'), 'utf8');
  const indexPage = await readFile(path.join(root, 'pages/index/index.nvue'), 'utf8');
  const cameraXPage = await readFile(path.join(root, 'pages/cameraX/index.nvue'), 'utf8');
  const xycPackage = await readFile(path.join(root, 'uni_modules/xyc-markvideo/package.json'), 'utf8');

  for (const text of [pagesJson, indexPage, cameraXPage, xycPackage]) {
    assert.doesNotMatch(text, /pages\/camera\/camera/);
    assert.doesNotMatch(text, /<uts-markvideo-camera/);
    assert.doesNotMatch(text, /recordWatermarkVideo/);
    assert.doesNotMatch(text, /@\/uni_modules\/uts-markvideo/);
  }
});

test('Vue 3 app entry is still declared in manifest', async () => {
  const main = await readFile(path.join(root, 'main.js'), 'utf8');
  const manifest = JSON.parse(await readFile(path.join(root, 'manifest.json'), 'utf8'));

  assert.match(main, /createSSRApp/);
  assert.equal(manifest.vueVersion, '3');
});
