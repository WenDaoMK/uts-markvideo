import { access, readFile } from 'node:fs/promises';
import path from 'node:path';
import test from 'node:test';
import assert from 'node:assert/strict';

const root = path.resolve(import.meta.dirname, '..');

const requiredFiles = [
  'README.md',
  'App.vue',
  'main.js',
  'manifest.json',
  'pages.json',
  'pages/index/index.vue',
  'uni_modules/uts-markvideo/package.json',
  'uni_modules/uts-markvideo/utssdk/interface.uts',
  'uni_modules/uts-markvideo/utssdk/app-android/index.uts',
  'uni_modules/uts-markvideo/utssdk/app-android/MarkVideoCameraActivity.kt',
  'uni_modules/uts-markvideo/utssdk/app-android/MarkVideoNative.kt',
  'uni_modules/uts-markvideo/utssdk/app-android/AndroidManifest.xml',
  'uni_modules/uts-markvideo/utssdk/app-ios/index.uts',
];

test('demo app contains the native UTS plugin MVP files', async () => {
  for (const file of requiredFiles) {
    await access(path.join(root, file));
  }
});

test('plugin package is named uts-markvideo', async () => {
  const text = await readFile(
    path.join(root, 'uni_modules/uts-markvideo/package.json'),
    'utf8',
  );
  const pkg = JSON.parse(text);
  assert.equal(pkg.id, 'uts-markvideo');
  assert.equal(pkg.name, 'uts-markvideo');
});

test('camera MVP exposes a recordWatermarkVideo API', async () => {
  const interfaceText = await readFile(
    path.join(root, 'uni_modules/uts-markvideo/utssdk/interface.uts'),
    'utf8',
  );
  const androidBridge = await readFile(
    path.join(root, 'uni_modules/uts-markvideo/utssdk/app-android/index.uts'),
    'utf8',
  );
  const page = await readFile(path.join(root, 'pages/index/index.vue'), 'utf8');

  assert.match(interfaceText, /RecordWatermarkVideo/);
  assert.match(androidBridge, /recordWatermarkVideo/);
  assert.match(page, /recordWatermarkVideo/);
});

test('Android manifest registers the native camera activity', async () => {
  const manifest = await readFile(
    path.join(root, 'uni_modules/uts-markvideo/utssdk/app-android/AndroidManifest.xml'),
    'utf8',
  );

  assert.match(manifest, /MarkVideoCameraActivity/);
});
