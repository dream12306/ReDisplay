const assert = require('assert');
const fs = require('fs');
const os = require('os');
const path = require('path');

const projectRoot = path.resolve(__dirname, '..');
const packageJson = require(path.join(projectRoot, 'package.json'));
const { resolveHostExePath } = require('../lib/runtime-paths');
const { normalizeTouchConfig } = require('../lib/touch-config');

assert.strictEqual(
    packageJson.build.portable && packageJson.build.portable.unpackDirName,
    true,
    'electron-builder 26.15.3 requires true to omit its fixed UNPACK_DIR_NAME'
);
assert(
    packageJson.build.files.includes('lib/**/*'),
    'runtime path resolver must be included in app.asar'
);
assert.deepStrictEqual(
    normalizeTouchConfig({ touchInput: 'finger', touchPressure: 120 }),
    { touchInput: 'finger', touchPressure: 100 },
    'finger input configuration must clamp pressure before reaching Host'
);
assert.deepStrictEqual(
    normalizeTouchConfig({ touchInput: 'invalid', touchPressure: 'bad' }),
    { touchInput: 'auto', touchPressure: 55 },
    'invalid touch configuration must fall back to safe defaults'
);
assert(
    packageJson.build.extraResources.some(item =>
        item.to === 'Client/ReDisplay.apk' && /Client[\\/]ReDisplay\.apk$/.test(item.from)
    ),
    'the current Android client APK must be packaged with the desktop app'
);
assert(
    packageJson.build.extraResources.some(item =>
        item.to === 'bin/ReDisplayHost.exe' && /ReDisplayHost\.exe$/.test(item.from)
    ),
    'ReDisplayHost.exe must be packaged under resources/bin'
);

const sandbox = fs.mkdtempSync(path.join(os.tmpdir(), 'redisplay-path-test-'));
try {
    const resourcesPath = path.join(sandbox, 'resources');
    const appDir = path.join(resourcesPath, 'app.asar');
    fs.mkdirSync(path.join(resourcesPath, 'bin'), { recursive: true });
    const expected = path.join(resourcesPath, 'bin', 'ReDisplayHost.exe');
    fs.writeFileSync(expected, 'test');

    assert.strictEqual(resolveHostExePath({ resourcesPath, appDir }), expected);
    fs.unlinkSync(expected);
    assert.strictEqual(
        resolveHostExePath({ resourcesPath, appDir }),
        null,
        'missing packaged Host must not fall back to a known-invalid resources/Host path'
    );
} finally {
    fs.rmSync(sandbox, { recursive: true, force: true });
}

console.log('packaging regression tests passed');
