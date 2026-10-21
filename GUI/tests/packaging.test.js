const assert = require('assert');
const fs = require('fs');
const os = require('os');
const path = require('path');

const projectRoot = path.resolve(__dirname, '..');
const packageJson = require(path.join(projectRoot, 'package.json'));
const { resolveHostExePath } = require('../lib/runtime-paths');

assert.strictEqual(
    packageJson.build.portable && packageJson.build.portable.unpackDirName,
    true,
    'electron-builder 26.15.3 requires true to omit its fixed UNPACK_DIR_NAME'
);
assert(
    packageJson.build.files.includes('lib/**/*'),
    'runtime path resolver must be included in app.asar'
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
