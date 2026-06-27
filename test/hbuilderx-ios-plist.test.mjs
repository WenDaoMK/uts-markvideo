import { mkdir, mkdtemp, readdir, rm, writeFile } from 'node:fs/promises';
import { spawnSync } from 'node:child_process';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import assert from 'node:assert/strict';

const root = path.resolve(import.meta.dirname, '..');
const scriptPath = path.join(root, 'scripts/fix-hbuilderx-ios-base-plist.mjs');

function commandWorks(command, args = ['--help']) {
  const result = spawnSync(command, args, { encoding: 'utf8' });
  return result.status === 0;
}

test('fix-hbuilderx-ios-base-plist converts XML app Info.plist inside an IPA', {
  skip: process.platform !== 'darwin',
}, async (t) => {
  for (const command of ['zip', 'unzip', 'plutil']) {
    const args = command === 'plutil' ? ['-help'] : ['--help'];
    if (!commandWorks(command, args)) {
      t.skip(`${command} is unavailable`);
      return;
    }
  }

  const tempDir = await mkdtemp(path.join(os.tmpdir(), 'hbuilderx-plist-test-'));
  t.after(() => rm(tempDir, { recursive: true, force: true }));

  const appDir = path.join(tempDir, 'Payload', 'Test.app');
  const ipaPath = path.join(tempDir, 'test.ipa');
  const backupDir = path.join(tempDir, 'backups');
  await mkdir(appDir, { recursive: true });
  await writeFile(path.join(appDir, 'Info.plist'), `<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleIdentifier</key>
  <string>com.example.test</string>
</dict>
</plist>
`, 'utf8');

  const zip = spawnSync('zip', ['-qry', ipaPath, 'Payload'], {
    cwd: tempDir,
    encoding: 'utf8',
  });
  assert.equal(zip.status, 0, zip.stderr || zip.stdout);

  const result = spawnSync(process.execPath, [scriptPath, '--backup-dir', backupDir, ipaPath], {
    cwd: root,
    encoding: 'utf8',
  });
  assert.equal(result.status, 0, result.stderr || result.stdout);
  assert.match(result.stdout, /\[fixed\]/);

  const plist = spawnSync('unzip', ['-p', ipaPath, 'Payload/Test.app/Info.plist']);
  assert.equal(plist.status, 0, plist.stderr?.toString() || plist.stdout?.toString());
  assert.equal(plist.stdout.subarray(0, 6).toString('ascii'), 'bplist');

  const backups = await readdir(backupDir);
  assert.deepEqual(backups, ['test.ipa.bak']);
});
