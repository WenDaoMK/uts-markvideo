#!/usr/bin/env node
import { access, copyFile, mkdir, mkdtemp, readFile, readdir, rm } from 'node:fs/promises';
import { spawnSync } from 'node:child_process';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const projectRoot = path.resolve(scriptDir, '..');

const defaultIpaPaths = [
  '/Applications/HBuilderX.app/Contents/HBuilderX/plugins/uniappx-launcher/base/iPhone_base_signed.ipa',
  path.join(projectRoot, 'unpackage/debug/iOS_debug_5.07.ipa'),
];

function nowStamp() {
  return new Date().toISOString().replace(/[-:]/g, '').replace(/\..+$/, '').replace('T', '-');
}

function usage() {
  return [
    'Usage: node scripts/fix-hbuilderx-ios-base-plist.mjs [options] [ipa ...]',
    '',
    'Converts Payload/*.app/Info.plist inside HBuilderX uni-app x iOS debug IPAs to binary plist.',
    '',
    'Options:',
    '  --backup-dir <dir>  Directory for original IPA backups.',
    '  --no-backup         Rewrite changed IPAs without saving a backup.',
    '  -h, --help          Show this help.',
  ].join('\n');
}

function parseArgs(argv) {
  const options = {
    backup: true,
    backupDir: path.resolve(projectRoot, '..', '.diagnostic-backups', `hbuilderx-ios-plist-binary-${nowStamp()}`),
    ipaPaths: [],
  };

  for (let index = 0; index < argv.length; index += 1) {
    const item = argv[index];
    if (item === '-h' || item === '--help') {
      options.help = true;
      continue;
    }
    if (item === '--no-backup') {
      options.backup = false;
      continue;
    }
    if (item === '--backup-dir') {
      const value = argv[index + 1];
      if (!value) {
        throw new Error('--backup-dir requires a directory path.');
      }
      options.backupDir = path.resolve(value);
      index += 1;
      continue;
    }
    if (item.startsWith('--')) {
      throw new Error(`Unknown option: ${item}`);
    }
    options.ipaPaths.push(path.resolve(item));
  }

  options.usingDefaultPaths = options.ipaPaths.length === 0;
  if (options.usingDefaultPaths) {
    options.ipaPaths = [...defaultIpaPaths];
  }
  return options;
}

function run(command, args, options = {}) {
  const result = spawnSync(command, args, {
    cwd: options.cwd ?? projectRoot,
    encoding: options.encoding ?? 'utf8',
  });

  if (result.status !== 0) {
    const output = `${result.stderr ?? ''}${result.stdout ?? ''}`.trim();
    throw new Error(`${command} ${args.join(' ')} failed${output ? `:\n${output}` : '.'}`);
  }

  return result;
}

async function exists(targetPath) {
  try {
    await access(targetPath);
    return true;
  } catch {
    return false;
  }
}

async function findAppInfoPlists(unpackDir) {
  const payloadDir = path.join(unpackDir, 'Payload');
  const entries = await readdir(payloadDir, { withFileTypes: true });
  const plists = [];

  for (const entry of entries) {
    if (!entry.isDirectory() || !entry.name.endsWith('.app')) {
      continue;
    }
    const plistPath = path.join(payloadDir, entry.name, 'Info.plist');
    if (await exists(plistPath)) {
      plists.push(plistPath);
    }
  }

  return plists;
}

async function plistIsBinary(plistPath) {
  const data = await readFile(plistPath);
  return data.subarray(0, 6).toString('ascii') === 'bplist';
}

async function convertIpa(ipaPath, options) {
  if (!await exists(ipaPath)) {
    if (options.usingDefaultPaths) {
      console.log(`[skip] ${ipaPath}: file not found`);
      return { skipped: true, changed: false };
    }
    throw new Error(`${ipaPath} does not exist.`);
  }

  const tempDir = await mkdtemp(path.join(os.tmpdir(), 'hbuilderx-ios-plist-'));
  const unpackDir = path.join(tempDir, 'unpacked');
  const fixedIpa = path.join(tempDir, 'fixed.ipa');

  try {
    await mkdir(unpackDir, { recursive: true });
    run('unzip', ['-q', ipaPath, '-d', unpackDir]);

    const plists = await findAppInfoPlists(unpackDir);
    if (plists.length === 0) {
      throw new Error(`${ipaPath} has no Payload/*.app/Info.plist.`);
    }

    let changed = false;
    for (const plistPath of plists) {
      if (await plistIsBinary(plistPath)) {
        continue;
      }
      run('plutil', ['-convert', 'binary1', plistPath]);
      if (!await plistIsBinary(plistPath)) {
        throw new Error(`plutil did not produce a binary plist: ${plistPath}`);
      }
      changed = true;
    }

    if (!changed) {
      console.log(`[ok] ${ipaPath}: Info.plist already uses binary plist format`);
      return { skipped: false, changed: false };
    }

    if (options.backup) {
      await mkdir(options.backupDir, { recursive: true });
      const backupPath = path.join(options.backupDir, `${path.basename(ipaPath)}.bak`);
      await copyFile(ipaPath, backupPath);
      console.log(`[backup] ${backupPath}`);
    }

    run('zip', ['-qry', fixedIpa, '.'], { cwd: unpackDir });
    await copyFile(fixedIpa, ipaPath);
    console.log(`[fixed] ${ipaPath}: converted app Info.plist to binary plist`);
    return { skipped: false, changed: true };
  } finally {
    await rm(tempDir, { recursive: true, force: true });
  }
}

async function main() {
  const options = parseArgs(process.argv.slice(2));
  if (options.help) {
    console.log(usage());
    return;
  }

  let changedCount = 0;
  for (const ipaPath of options.ipaPaths) {
    const result = await convertIpa(ipaPath, options);
    if (result.changed) {
      changedCount += 1;
    }
  }

  console.log(`[done] changed ${changedCount} IPA(s)`);
}

main().catch((error) => {
  console.error(`[error] ${error.message}`);
  process.exitCode = 1;
});
