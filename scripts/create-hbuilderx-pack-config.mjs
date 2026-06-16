import { writeFile } from 'node:fs/promises';

const output = process.argv[2];

if (!output) {
  throw new Error('Usage: node scripts/create-hbuilderx-pack-config.mjs <output-json>');
}

const env = process.env;
const platform = env.PACK_PLATFORM || 'android';
const platforms = platform.split(',').map((item) => item.trim()).filter(Boolean);

function required(name) {
  const value = env[name];
  if (!value) {
    throw new Error(`${name} is required for this package job.`);
  }
  return value;
}

function optionalBoolean(name, fallback = false) {
  const value = env[name];
  if (value == null || value === '') return fallback;
  return ['1', 'true', 'yes', 'on'].includes(value.toLowerCase());
}

const config = {
  project: required('PROJECT_PATH'),
  platform: platform,
  iscustom: false,
  safemode: optionalBoolean('SAFE_MODE'),
  sourceMap: false,
  isconfusion: false,
  splashads: false,
  rpads: false,
  unimpads: false,
};

if (platforms.includes('android')) {
  const androidPackType = Number(env.ANDROID_PACK_TYPE || '3');
  if (!Number.isInteger(androidPackType)) {
    throw new Error('ANDROID_PACK_TYPE must be an integer.');
  }

  config.android = {
    packagename: required('ANDROID_PACKAGE_NAME'),
    androidpacktype: androidPackType,
  };

  if (androidPackType === 0) {
    config.android.certalias = required('ANDROID_CERT_ALIAS');
    config.android.certfile = required('ANDROID_CERT_FILE');
    config.android.certpassword = required('ANDROID_CERT_PASSWORD');
    config.android.storePassword = required('ANDROID_STORE_PASSWORD');
  }
}

if (platforms.includes('ios')) {
  const isPrisonbreak = optionalBoolean('IOS_IS_PRISONBREAK');
  config.ios = {
    bundle: required('IOS_BUNDLE_ID'),
    supporteddevice: env.IOS_SUPPORTED_DEVICE || 'iPhone',
    isprisonbreak: isPrisonbreak,
    channels: env.IOS_CHANNELS || 'phone',
  };

  if (!isPrisonbreak) {
    config.ios.profile = required('IOS_PROFILE_FILE');
    config.ios.certfile = required('IOS_CERT_FILE');
    config.ios.certpassword = required('IOS_CERT_PASSWORD');
  }
}

await writeFile(output, `${JSON.stringify(config, null, 2)}\n`, 'utf8');
