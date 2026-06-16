import { mkdtemp, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import test from 'node:test';
import assert from 'node:assert/strict';

const root = path.resolve(import.meta.dirname, '..');
const script = path.join(root, 'scripts/create-hbuilderx-pack-config.mjs');

async function runConfig(env) {
  const dir = await mkdtemp(path.join(tmpdir(), 'uts-pack-config-'));
  const output = path.join(dir, 'pack.json');
  try {
    const result = spawnSync(process.execPath, [script, output], {
      cwd: root,
      env: {
        ...process.env,
        ...env,
      },
      encoding: 'utf8',
    });
    return {
      result,
      config: result.status === 0 ? JSON.parse(await readFile(output, 'utf8')) : null,
    };
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
}

test('iOS prisonbreak package config does not require signing files', async () => {
  const { result, config } = await runConfig({
    PROJECT_PATH: root,
    PACK_PLATFORM: 'ios',
    IOS_BUNDLE_ID: 'com.example.utsmarkvideo',
    IOS_IS_PRISONBREAK: 'true',
  });

  assert.equal(result.status, 0, result.stderr || result.stdout);
  assert.equal(config.platform, 'ios');
  assert.equal(config.ios.bundle, 'com.example.utsmarkvideo');
  assert.equal(config.ios.isprisonbreak, true);
  assert.equal(config.ios.profile, undefined);
  assert.equal(config.ios.certfile, undefined);
  assert.equal(config.ios.certpassword, undefined);
});

test('signed iOS package config still requires signing files', async () => {
  const { result } = await runConfig({
    PROJECT_PATH: root,
    PACK_PLATFORM: 'ios',
    IOS_BUNDLE_ID: 'com.example.utsmarkvideo',
    IOS_IS_PRISONBREAK: 'false',
  });

  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /IOS_PROFILE_FILE is required/);
});
