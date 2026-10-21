const { app, BrowserWindow, ipcMain, Tray, Menu, nativeImage, Notification } = require('electron');
const path = require('path');
const { spawn, execFile, exec, execSync } = require('child_process');
const readline = require('readline');
const fs = require('fs');
const { resolveHostExePath } = require('./lib/runtime-paths');
const { normalizeTouchConfig } = require('./lib/touch-config');

let mainWindow = null;
let tray = null;
let hostProcess = null;
let isQuitting = false;
let currentConfig = {
    profile: 'esports',
    fps: 120,
    bitrate: 60,
    width: 1752,
    height: 2800,
    bufferMode: 'lowlatency',
    orientation: 'portrait',
    touchInput: 'auto',
    touchPressure: 55
};
let latestStats = { fps: 0, mbps: 0, encodeMs: 0, dropped: 0, frames: 0 };
let streamStatus = 'idle'; // 'idle', 'connecting', 'streaming'
let activeStreamDeviceName = '';
let activeStreamSerial = '';
let hasShownTrayTip = false;
let lastTrayUpdateTime = 0;
const PACKAGED_CLIENT_VERSION_CODE = 12;

const WHEEL_ACTIONS = Object.freeze({
    none: 0,
    keyChord: 1,
    rightClick: 2,
    recordingZone: 3,
    recordStart: 4,
    recordStop: 5,
    recordCancel: 6,
    saveLastStroke: 7,
    saveLastTap: 8,
    replayLastStroke: 9,
    replayLastTap: 10,
    replayLatest: 11,
    leftClick: 13,
    doubleClick: 14,
    macroLibrary: 15,
    executeMacro: 16,
    toggleEraserMode: 19,
    enterMouseMode: 20,
    exitMouseMode: 21
});

function defaultWheelConfig() {
    return {
        version: 2,
        revision: 1,
        slots: [
            { label: '单击', action: 'leftClick' },
            { label: '双击', action: 'doubleClick' },
            { label: '右键', action: 'rightClick' },
            { label: 'Enter', action: 'keyChord', chord: 'Enter' },
            { label: '复制', action: 'keyChord', chord: 'Ctrl+C' },
            { label: '粘贴', action: 'keyChord', chord: 'Ctrl+V' },
            { label: '撤销', action: 'keyChord', chord: 'Ctrl+Z' },
            { label: '重做', action: 'keyChord', chord: 'Ctrl+Y' },
            { label: '宏操作库', action: 'macroLibrary' },
            { label: '橡皮擦模式', action: 'toggleEraserMode' },
            { label: '保存笔迹', action: 'saveLastStroke' },
            { label: '重复笔迹', action: 'replayLastStroke' },
            { label: '进入鼠标模式', action: 'enterMouseMode' },
            { label: '退出鼠标模式', action: 'exitMouseMode' },
            { label: '录制区', action: 'recordingZone' },
            { label: '最近录制', action: 'replayLatest' },
        ]
    };
}

function wheelPaths() {
    const root = path.join(app.getPath('userData'), 'input');
    return {
        root,
        json: path.join(root, 'wheel-config.json'),
        binary: path.join(root, 'wheel-config.bin'),
        macros: path.join(root, 'macros'),
        macroLibraryJson: path.join(root, 'macro-library.json'),
        macroLibraryBinary: path.join(root, 'macro-library.bin'),
        touchConfigJson: path.join(root, 'touch-input.json')
    };
}

function loadTouchConfig() {
    const configPath = wheelPaths().touchConfigJson;
    try {
        return normalizeTouchConfig(JSON.parse(fs.readFileSync(configPath, 'utf8')));
    } catch (error) {
        return normalizeTouchConfig();
    }
}

function saveTouchConfig(config) {
    const paths = wheelPaths();
    const normalized = normalizeTouchConfig(config);
    fs.mkdirSync(paths.root, { recursive: true });
    fs.writeFileSync(paths.touchConfigJson, JSON.stringify(normalized, null, 2), 'utf8');
    currentConfig = { ...currentConfig, ...normalized };
    return normalized;
}

function parseChord(chord) {
    const parts = String(chord || '').split('+').map(part => part.trim().toUpperCase()).filter(Boolean);
    let modifiers = 0;
    let keyCode = 0;
    const namedKeys = {
        BACKSPACE: 8, TAB: 9, ENTER: 13, RETURN: 13, ESC: 27, ESCAPE: 27,
        SPACE: 32, PAGEUP: 33, PGUP: 33, PAGEDOWN: 34, PGDN: 34,
        END: 35, HOME: 36, LEFT: 37, UP: 38, RIGHT: 39, DOWN: 40,
        INSERT: 45, DELETE: 46
    };
    for (let index = 1; index <= 12; index++) namedKeys[`F${index}`] = 111 + index;
    for (const part of parts) {
        if (part === 'CTRL' || part === 'CONTROL') modifiers |= 1;
        else if (part === 'SHIFT') modifiers |= 2;
        else if (part === 'ALT') modifiers |= 4;
        else if (part === 'WIN' || part === 'WINDOWS' || part === 'META') modifiers |= 8;
        else if (/^[A-Z0-9]$/.test(part)) keyCode = part.charCodeAt(0);
        else if (namedKeys[part]) keyCode = namedKeys[part];
        else throw new Error(`不支持的按键: ${part}`);
    }
    if (!keyCode) throw new Error('快捷键必须包含一个主按键');
    return { modifiers, keyCode };
}

function normalizeWheelConfig(config) {
    if (!config || !Array.isArray(config.slots) || config.slots.length !== 16) {
        throw new Error('轮盘必须正好包含内环 8 格和外环 8 格');
    }
    return {
        version: 2,
        revision: (Number(config.revision) || Date.now()) >>> 0,
        slots: config.slots.map((slot, index) => {
            const action = Object.prototype.hasOwnProperty.call(WHEEL_ACTIONS, slot.action)
                ? slot.action
                : 'none';
            const label = String(slot.label || `功能 ${index + 1}`).trim().slice(0, 24);
            const normalized = { label, action };
            if (action === 'keyChord') {
                parseChord(slot.chord);
                normalized.chord = String(slot.chord).trim();
            } else if (action === 'executeMacro') {
                const macroId = Number(slot.macroId) >>> 0;
                if (!macroId) throw new Error(`轮盘槽位 ${index + 1} 没有选择宏`);
                normalized.macroId = macroId;
            }
            return normalized;
        })
    };
}

function copyUtf8Label(buffer, offset, label) {
    let bytes = Buffer.alloc(0);
    for (const character of String(label)) {
        const next = Buffer.from(character, 'utf8');
        if (bytes.length + next.length > 31) break;
        bytes = Buffer.concat([bytes, next]);
    }
    bytes.copy(buffer, offset);
}

function serializeWheelConfig(config) {
    const payload = Buffer.alloc(8 + 16 * 40);
    payload.writeUInt16BE(2, 0);
    payload.writeUInt8(8, 2);
    payload.writeUInt8(8, 3);
    payload.writeUInt32BE(config.revision >>> 0, 4);
    config.slots.forEach((slot, index) => {
        const offset = 8 + index * 40;
        payload.writeUInt8(WHEEL_ACTIONS[slot.action], offset);
        let modifiers = 0;
        let keyCode = 0;
        if (slot.action === 'keyChord') ({ modifiers, keyCode } = parseChord(slot.chord));
        payload.writeUInt8(modifiers, offset + 1);
        payload.writeUInt16BE(keyCode, offset + 2);
        payload.writeUInt32BE(slot.action === 'executeMacro' ? slot.macroId >>> 0 : 0, offset + 4);
        copyUtf8Label(payload, offset + 8, slot.label);
    });
    return payload;
}

function saveWheelConfig(config) {
    const normalized = normalizeWheelConfig({ ...config, revision: Date.now() });
    const paths = wheelPaths();
    fs.mkdirSync(paths.root, { recursive: true });
    fs.mkdirSync(paths.macros, { recursive: true });
    fs.writeFileSync(paths.json, JSON.stringify(normalized, null, 2), 'utf8');
    fs.writeFileSync(paths.binary, serializeWheelConfig(normalized));
    return normalized;
}

function loadWheelConfig() {
    const paths = wheelPaths();
    try {
        if (fs.existsSync(paths.json)) {
            const normalized = normalizeWheelConfig(JSON.parse(fs.readFileSync(paths.json, 'utf8')));
            const legacyPenSlot = normalized.slots[8];
            let migrated = false;
            if (legacyPenSlot && legacyPenSlot.action === 'keyChord' &&
                String(legacyPenSlot.chord).toUpperCase() === 'B' &&
                ['画笔', 'Pen'].includes(legacyPenSlot.label)) {
                normalized.slots[8] = { label: '宏操作库', action: 'macroLibrary' };
                migrated = true;
            }
            const legacyEraserSlot = normalized.slots[9];
            if (legacyEraserSlot && legacyEraserSlot.action === 'keyChord' &&
                String(legacyEraserSlot.chord).toUpperCase() === 'E' &&
                ['橡皮擦', 'Eraser'].includes(legacyEraserSlot.label)) {
                normalized.slots[9] = { label: '橡皮擦模式', action: 'toggleEraserMode' };
                migrated = true;
            }
            if (normalized.slots[12]?.action === 'saveLastTap') {
                normalized.slots[12] = { label: '进入鼠标模式', action: 'enterMouseMode' };
                migrated = true;
            }
            if (normalized.slots[13]?.action === 'replayLastTap') {
                normalized.slots[13] = { label: '退出鼠标模式', action: 'exitMouseMode' };
                migrated = true;
            }
            if (normalized.slots[15]?.action === 'replayLatest' && normalized.slots[15].label === '回放保存') {
                normalized.slots[15].label = '最近录制';
                migrated = true;
            }
            if (migrated) return saveWheelConfig(normalized);
            fs.mkdirSync(paths.root, { recursive: true });
            fs.mkdirSync(paths.macros, { recursive: true });
            fs.writeFileSync(paths.binary, serializeWheelConfig(normalized));
            return normalized;
        }
    } catch (error) {
        console.warn('[Wheel] Config invalid, restoring defaults:', error.message);
    }
    return saveWheelConfig(defaultWheelConfig());
}

const MACRO_STEP_TYPES = Object.freeze({
    keyChord: 1,
    mouseClick: 2,
    delay: 3,
    penPlayback: 4
});

function nextMacroId(items = []) {
    const used = new Set(items.map(item => Number(item.id) >>> 0));
    let id = Date.now() >>> 0;
    if (!id) id = 1;
    while (used.has(id)) id = (id + 1) >>> 0 || 1;
    return id;
}

function normalizeMacroStep(step) {
    const type = Object.prototype.hasOwnProperty.call(MACRO_STEP_TYPES, step?.type)
        ? step.type
        : null;
    if (!type) throw new Error('宏包含不支持的步骤');
    if (type === 'keyChord') {
        const chord = String(step.chord || '').trim();
        parseChord(chord);
        return { type, chord };
    }
    if (type === 'mouseClick') {
        const button = ['left', 'double', 'right'].includes(step.button) ? step.button : 'left';
        return { type, button };
    }
    if (type === 'delay') {
        const ms = Math.max(0, Math.min(10000, Math.round(Number(step.ms) || 0)));
        return { type, ms };
    }
    const recordingId = Number(step.recordingId) >>> 0;
    if (!recordingId) throw new Error('笔迹回放步骤缺少录制 ID');
    return { type, recordingId };
}

function normalizeMacroLibrary(library) {
    const rawItems = Array.isArray(library?.items) ? library.items : [];
    if (rawItems.length > 256) throw new Error('宏操作库最多支持 256 个项目');
    const ids = new Set();
    const items = rawItems.map((item, index) => {
        const id = Number(item.id) >>> 0;
        if (!id || ids.has(id)) throw new Error(`宏 ${index + 1} 的 ID 无效或重复`);
        ids.add(id);
        const name = String(item.name || `宏 ${index + 1}`).trim().slice(0, 24);
        const steps = (Array.isArray(item.steps) ? item.steps : []).map(normalizeMacroStep);
        if (steps.length === 0 || steps.length > 128) throw new Error(`${name} 必须包含 1 到 128 个步骤`);
        return {
            id,
            name,
            category: String(item.category || '未分类').trim().slice(0, 24),
            favorite: !!item.favorite,
            createdAt: Number(item.createdAt) || Date.now(),
            updatedAt: Number(item.updatedAt) || Date.now(),
            steps
        };
    });
    const ignoredRecordingIds = [...new Set((Array.isArray(library?.ignoredRecordingIds)
        ? library.ignoredRecordingIds : []).map(value => Number(value) >>> 0).filter(Boolean))];
    return { version: 1, revision: (Number(library?.revision) || Date.now()) >>> 0,
        ignoredRecordingIds, items };
}

function serializeMacroLibrary(library) {
    const chunks = [];
    const header = Buffer.alloc(16);
    header.write('RML1', 0, 'ascii');
    header.writeUInt16BE(1, 4);
    header.writeUInt16BE(0, 6);
    header.writeUInt32BE(library.revision >>> 0, 8);
    header.writeUInt32BE(library.items.length, 12);
    chunks.push(header);
    for (const item of library.items) {
        const itemHeader = Buffer.alloc(40);
        itemHeader.writeUInt32BE(item.id >>> 0, 0);
        itemHeader.writeUInt16BE(item.steps.length, 4);
        copyUtf8Label(itemHeader, 8, item.name);
        chunks.push(itemHeader);
        for (const step of item.steps) {
            const encoded = Buffer.alloc(12);
            encoded.writeUInt8(MACRO_STEP_TYPES[step.type], 0);
            if (step.type === 'keyChord') {
                const parsed = parseChord(step.chord);
                encoded.writeUInt8(parsed.modifiers, 1);
                encoded.writeUInt16BE(parsed.keyCode, 2);
            } else if (step.type === 'mouseClick') {
                encoded.writeUInt32BE({ left: 1, double: 2, right: 3 }[step.button], 4);
            } else if (step.type === 'delay') {
                encoded.writeUInt32BE(step.ms >>> 0, 4);
            } else if (step.type === 'penPlayback') {
                encoded.writeUInt32BE(step.recordingId >>> 0, 4);
            }
            chunks.push(encoded);
        }
    }
    return Buffer.concat(chunks);
}

function discoverRecordedMacros(library) {
    const paths = wheelPaths();
    fs.mkdirSync(paths.macros, { recursive: true });
    const known = new Set();
    for (const id of library.ignoredRecordingIds || []) known.add(id >>> 0);
    for (const item of library.items) {
        for (const step of item.steps) {
            if (step.type === 'penPlayback') known.add(step.recordingId >>> 0);
        }
    }
    const numericFiles = fs.readdirSync(paths.macros).filter(name => /^\d+\.rdm$/i.test(name));
    let changed = false;
    for (const fileName of numericFiles) {
        const recordingId = Number(path.basename(fileName, '.rdm')) >>> 0;
        if (!recordingId || known.has(recordingId)) continue;
        const stat = fs.statSync(path.join(paths.macros, fileName));
        library.items.push({
            id: recordingId,
            name: `录制 ${stat.mtime.toLocaleString('zh-CN', { hour12: false })}`.slice(0, 24),
            category: '笔操作录制',
            favorite: false,
            createdAt: stat.mtimeMs,
            updatedAt: stat.mtimeMs,
            steps: [{ type: 'penPlayback', recordingId }]
        });
        known.add(recordingId);
        changed = true;
    }
    const legacyPath = path.join(paths.macros, 'latest.rdm');
    if (library.items.length === 0 && known.size === 0 && fs.existsSync(legacyPath)) {
        const recordingId = nextMacroId(library.items);
        fs.copyFileSync(legacyPath, path.join(paths.macros, `${recordingId}.rdm`));
        library.items.push({
            id: recordingId,
            name: '旧版最近录制',
            category: '笔操作录制',
            favorite: false,
            createdAt: Date.now(),
            updatedAt: Date.now(),
            steps: [{ type: 'penPlayback', recordingId }]
        });
        changed = true;
    }
    return changed;
}

function saveMacroLibrary(library) {
    const normalized = normalizeMacroLibrary({ ...library, revision: Date.now() });
    const paths = wheelPaths();
    fs.mkdirSync(paths.root, { recursive: true });
    fs.mkdirSync(paths.macros, { recursive: true });
    fs.writeFileSync(paths.macroLibraryJson, JSON.stringify(normalized, null, 2), 'utf8');
    fs.writeFileSync(paths.macroLibraryBinary, serializeMacroLibrary(normalized));
    return normalized;
}

function loadMacroLibrary() {
    const paths = wheelPaths();
    let library = { version: 1, revision: 1, ignoredRecordingIds: [], items: [] };
    try {
        if (fs.existsSync(paths.macroLibraryJson)) {
            library = normalizeMacroLibrary(JSON.parse(fs.readFileSync(paths.macroLibraryJson, 'utf8')));
        }
    } catch (error) {
        console.warn('[Macro] Library invalid, preserving file and starting empty:', error.message);
    }
    const discovered = discoverRecordedMacros(library);
    if (discovered || !fs.existsSync(paths.macroLibraryBinary)) return saveMacroLibrary(library);
    fs.writeFileSync(paths.macroLibraryBinary, serializeMacroLibrary(library));
    return library;
}

function resourcePath(...parts) {
    return path.join(process.resourcesPath, ...parts);
}

// Dynamic Path Resolution
function getHostExePath() {
    return resolveHostExePath({
        resourcesPath: process.resourcesPath,
        appDir: __dirname
    });
}

function getAdbPath() {
    const candidates = [
        process.env.REDISPLAY_ADB,
        resourcePath('bin', 'adb.exe'),
        path.join(__dirname, '..', 'bin', 'adb.exe'),
        path.join(__dirname, 'resources', 'bin', 'adb.exe'),
        path.join(__dirname, '..', 'resources', 'bin', 'adb.exe'),
        path.join(__dirname, '..', '..', 'platform-tools', 'adb.exe'),
        path.join(__dirname, '..', 'ReDisplay-Win64', 'resources', 'bin', 'adb.exe'),
        'C:\\Program Files\\QtScrcpy-win-x64-v3.2.0\\adb.exe',
        path.join(process.env.ANDROID_SDK_ROOT || '', 'platform-tools', 'adb.exe'),
        path.join(process.env.ANDROID_HOME || '', 'platform-tools', 'adb.exe'),
        path.join(process.env.LOCALAPPDATA || '', 'Android', 'Sdk', 'platform-tools', 'adb.exe'),
        path.join(process.env.LOCALAPPDATA || '', 'ScreenExt', 'bin', 'adb.exe')
    ].filter(Boolean);
    for (const p of candidates) {
        if (fs.existsSync(p)) return p;
    }
    return 'adb.exe';
}

function getApkPath() {
    const candidates = [
        resourcePath('Client', 'ReDisplay.apk'),
        path.join(__dirname, '..', 'Client', 'ReDisplay.apk'),
        path.join(__dirname, 'resources', 'Client', 'ReDisplay.apk'),
        path.join(__dirname, '..', '..', 'Client', 'ReDisplay.apk'),
        path.join(__dirname, 'Client', 'ReDisplay.apk')
    ];
    for (const p of candidates) {
        if (fs.existsSync(p)) return p;
    }
    return '';
}

function getDriverScriptPath(action) {
    const scriptName = (action === 'uninstall') ? 'uninstall_driver.bat' : 'install_driver.bat';
    const candidates = [
        resourcePath('Driver', scriptName),
        path.join(__dirname, '..', 'Driver', scriptName),
        path.join(__dirname, 'resources', 'Driver', scriptName),
        path.join(__dirname, '..', '..', 'Driver', scriptName),
        path.join(__dirname, 'Driver', scriptName)
    ];
    for (const p of candidates) {
        if (fs.existsSync(p)) return p;
    }
    return '';
}

function createWindow() {
    const appIcon = fs.existsSync(path.join(__dirname, 'assets', 'icon.ico'))
        ? path.join(__dirname, 'assets', 'icon.ico')
        : path.join(__dirname, 'assets', 'icon.png');

    mainWindow = new BrowserWindow({
        title: 'ReDisplay - 超低延迟有线副屏系统',
        width: 1040,
        height: 800,
        minWidth: 900,
        minHeight: 680,
        frame: false,
        backgroundColor: '#141218',
        icon: appIcon,
        webPreferences: {
            preload: path.join(__dirname, 'preload.js'),
            nodeIntegration: false,
            contextIsolation: true
        }
    });

    mainWindow.loadFile(path.join(__dirname, 'src', 'index.html'));

    mainWindow.webContents.on('console-message', (event, details) => {
        console.log(`[Renderer] ${details.message}`);
    });

    // Fix: Safely handle minimize to tray on close without calling non-existent Tray.isSupported()
    mainWindow.on('close', (event) => {
        if (!isQuitting) {
            event.preventDefault();
            mainWindow.hide();
            if (!hasShownTrayTip && tray) {
                hasShownTrayTip = true;
                try {
                    tray.displayBalloon({
                        title: 'ReDisplay 已最小化至系统托盘',
                        content: '副屏服务保持在后台无感运行。双击托盘图标可随时恢复主界面。'
                    });
                } catch (e) {}
            }
        }
    });
}

function sendTrayConfigChange(patch) {
    currentConfig = { ...currentConfig, ...patch };
    updateTrayMenu();
    if (mainWindow && !mainWindow.isDestroyed()) {
        mainWindow.webContents.send('tray-action', { action: 'change-config', config: currentConfig });
    }
}

function updateTrayMenu() {
    if (!tray) return;

    const isStreaming = (streamStatus === 'streaming');
    const isConnecting = (streamStatus === 'connecting');

    // Tooltip with live status
    if (isStreaming) {
        const fpsStr = latestStats.fps > 0 ? `${latestStats.fps.toFixed(1)} FPS` : `${currentConfig.fps || 120} Hz`;
        const mbpsStr = latestStats.mbps > 0 ? `${latestStats.mbps.toFixed(1)} Mbps` : `${currentConfig.bitrate || 60} Mbps`;
        tray.setToolTip(`ReDisplay: 正在串流\n• 性能: ${fpsStr} | ${mbpsStr}\n• 规格: ${currentConfig.width || 1752}x${currentConfig.height || 2800}\n• 设备: ${activeStreamDeviceName || activeStreamSerial || '已连接'}`);
    } else if (isConnecting) {
        tray.setToolTip('ReDisplay: 正在建立副屏连接通道...');
    } else {
        tray.setToolTip('ReDisplay: 待机就绪 (双击打开控制台)');
    }

    const template = [];

    // 1. Dynamic Status Header (Informational, non-clickable)
    if (isStreaming) {
        const fpsStr = latestStats.fps > 0 ? `${latestStats.fps.toFixed(1)} FPS` : `${currentConfig.fps || 120} Hz`;
        const mbpsStr = latestStats.mbps > 0 ? `${latestStats.mbps.toFixed(1)} Mbps` : `${currentConfig.bitrate || 60} Mbps`;
        template.push({
            label: `● 串流中 · ${fpsStr} · ${mbpsStr}`,
            enabled: false
        });
        template.push({
            label: `   规格: ${currentConfig.width || 1752}×${currentConfig.height || 2800} (${currentConfig.bufferMode === 'smooth' ? '平滑优先' : '极低延迟'})`,
            enabled: false
        });
        if (activeStreamDeviceName || activeStreamSerial) {
            template.push({
                label: `   目标: ${activeStreamDeviceName || activeStreamSerial}`,
                enabled: false
            });
        }
    } else if (isConnecting) {
        template.push({
            label: '⏳ 正在连接安卓设备与启动副屏...',
            enabled: false
        });
    } else {
        template.push({
            label: '○ 副屏服务: 待机就绪',
            enabled: false
        });
    }

    template.push({ type: 'separator' });

    // 2. Primary Service Action
    if (isStreaming) {
        template.push({
            label: '⏹ 停止副屏服务',
            click: () => {
                stopHostProcess();
                if (mainWindow && !mainWindow.isDestroyed()) {
                    mainWindow.webContents.send('tray-action', { action: 'stop' });
                }
            }
        });
    } else {
        template.push({
            label: '▶ 启动副屏服务',
            click: () => {
                if (mainWindow && !mainWindow.isDestroyed()) {
                    mainWindow.webContents.send('tray-action', { action: 'start' });
                } else {
                    startHostProcess(currentConfig);
                }
            }
        });
    }

    // 3. Quick Performance Profile Submenu
    template.push({
        label: '⚡ 性能预设档位',
        submenu: [
            {
                label: '⚡ 极速电竞直通 (120Hz · 60Mbps · ≤10ms)',
                type: 'radio',
                checked: currentConfig.profile === 'esports',
                click: () => sendTrayConfigChange({ profile: 'esports', fps: 120, bitrate: 60, bufferMode: 'lowlatency' })
            },
            {
                label: '🎬 极致超清画质 (120Hz · 85Mbps · 高保真)',
                type: 'radio',
                checked: currentConfig.profile === 'quality',
                click: () => sendTrayConfigChange({ profile: 'quality', fps: 120, bitrate: 85, bufferMode: 'lowlatency' })
            },
            {
                label: '🔋 节能平滑办公 (60Hz · 30Mbps · 平滑)',
                type: 'radio',
                checked: currentConfig.profile === 'office',
                click: () => sendTrayConfigChange({ profile: 'office', fps: 60, bitrate: 30, bufferMode: 'smooth' })
            }
        ]
    });

    // 4. Quick Resolution Submenu
    template.push({
        label: '📐 副屏分辨率与方向',
        submenu: [
            {
                label: '1752 × 2800 (16:10 竖屏 · 2.8K)',
                type: 'radio',
                checked: (currentConfig.width === 1752 && currentConfig.height === 2800),
                click: () => sendTrayConfigChange({ width: 1752, height: 2800, orientation: 'portrait' })
            },
            {
                label: '2800 × 1752 (16:10 横屏 · 2.8K)',
                type: 'radio',
                checked: (currentConfig.width === 2800 && currentConfig.height === 1752),
                click: () => sendTrayConfigChange({ width: 2800, height: 1752, orientation: 'landscape' })
            },
            {
                label: '1600 × 2560 (16:10 竖屏 · 2.5K)',
                type: 'radio',
                checked: (currentConfig.width === 1600 && currentConfig.height === 2560),
                click: () => sendTrayConfigChange({ width: 1600, height: 2560, orientation: 'portrait' })
            },
            {
                label: '2560 × 1600 (16:10 横屏 · 2.5K)',
                type: 'radio',
                checked: (currentConfig.width === 2560 && currentConfig.height === 1600),
                click: () => sendTrayConfigChange({ width: 2560, height: 1600, orientation: 'landscape' })
            },
            {
                label: '1200 × 1920 (16:10 竖屏 · 1200P)',
                type: 'radio',
                checked: (currentConfig.width === 1200 && currentConfig.height === 1920),
                click: () => sendTrayConfigChange({ width: 1200, height: 1920, orientation: 'portrait' })
            },
            {
                label: '1920 × 1200 (16:10 横屏 · 1200P)',
                type: 'radio',
                checked: (currentConfig.width === 1920 && currentConfig.height === 1200),
                click: () => sendTrayConfigChange({ width: 1920, height: 1200, orientation: 'landscape' })
            },
            {
                label: '1080 × 1920 (9:16 竖屏 · 1080P)',
                type: 'radio',
                checked: (currentConfig.width === 1080 && currentConfig.height === 1920),
                click: () => sendTrayConfigChange({ width: 1080, height: 1920, orientation: 'portrait' })
            },
            {
                label: '1920 × 1080 (16:9 横屏 · 1080P)',
                type: 'radio',
                checked: (currentConfig.width === 1920 && currentConfig.height === 1080),
                click: () => sendTrayConfigChange({ width: 1920, height: 1080, orientation: 'landscape' })
            }
        ]
    });

    template.push({ type: 'separator' });

    // 5. Control Center & Windows Display Settings
    template.push({
        label: mainWindow && mainWindow.isVisible() ? '隐藏主控制台' : '显示主控制台',
        click: () => {
            if (mainWindow) {
                if (mainWindow.isVisible()) {
                    mainWindow.hide();
                } else {
                    mainWindow.show();
                    mainWindow.focus();
                }
                updateTrayMenu();
            }
        }
    });

    template.push({
        label: '打开 Windows 显示设置',
        click: () => {
            exec('start ms-settings:display');
        }
    });

    template.push({ type: 'separator' });

    // 6. Exit
    template.push({
        label: '退出 ReDisplay',
        click: () => {
            isQuitting = true;
            stopHostProcess();
            app.quit();
        }
    });

    const contextMenu = Menu.buildFromTemplate(template);
    tray.setContextMenu(contextMenu);
}

function createTray() {
    const iconPath = fs.existsSync(path.join(__dirname, 'assets', 'tray.ico'))
        ? path.join(__dirname, 'assets', 'tray.ico')
        : path.join(__dirname, 'assets', 'tray.png');
    tray = new Tray(iconPath);
    updateTrayMenu();

    tray.on('double-click', () => {
        if (mainWindow) {
            mainWindow.show();
            mainWindow.focus();
            updateTrayMenu();
        }
    });
    tray.on('click', () => {
        if (mainWindow) {
            mainWindow.show();
            mainWindow.focus();
            updateTrayMenu();
        }
    });

    return updateTrayMenu;
}

// 1. Client APK Check & Installation
function checkClientInstalled(serial) {
    const adb = getAdbPath();
    const deviceArgs = serial ? ['-s', serial] : [];
    const args = [...deviceArgs, 'shell', 'dumpsys', 'package', 'com.redisplay.client'];
    return new Promise((resolve) => {
        execFile(adb, args, (err, stdout) => {
            const match = !err && stdout ? stdout.match(/\bversionCode=(\d+)/) : null;
            const versionCode = match ? parseInt(match[1], 10) : 0;
            const installed = versionCode > 0;
            resolve({
                installed,
                versionCode,
                requiredVersionCode: PACKAGED_CLIENT_VERSION_CODE,
                outdated: installed && versionCode < PACKAGED_CLIENT_VERSION_CODE
            });
        });
    });
}

function installClientApk(serial) {
    const adb = getAdbPath();
    const apk = getApkPath();
    if (!apk || !fs.existsSync(apk)) {
        return Promise.resolve({ success: false, error: '未在系统中找到 ReDisplay.apk' });
    }
    const deviceArgs = serial ? ['-s', serial] : [];
    // Non-streaming install avoids INSTALL_FAILED_USER_RESTRICTED on some
    // MIUI/HyperOS devices while still using the normal Package Manager path.
    const installArgs = [...deviceArgs, 'install', '--no-streaming', '-r', apk];

    const resultText = (stdout, stderr) => `${stdout || ''}${stderr || ''}`;
    const formatInstallResult = (err, stdout, stderr) => {
        const out = resultText(stdout, stderr);
        if (out.includes('Success')) {
            return { success: true, message: '客户端应用安装成功' };
        }
        if (out.includes('INSTALL_FAILED_USER_RESTRICTED')) {
            return { success: false, error: '设备限制了USB安装，请在安卓【开发者选项】中开启【USB安装 (允许通过USB安装应用)】' };
        }
        return { success: false, error: out.trim() || err?.message || '安装失败' };
    };

    return new Promise((resolve) => {
        execFile(adb, installArgs, (err, stdout, stderr) => {
            const firstOut = resultText(stdout, stderr);
            if (!firstOut.includes('INSTALL_FAILED_UPDATE_INCOMPATIBLE')) {
                resolve(formatInstallResult(err, stdout, stderr));
                return;
            }

            // Older hand-built APKs were signed with a different debug key.
            // This path runs only after the user explicitly requests install.
            execFile(adb, [...deviceArgs, 'uninstall', 'com.redisplay.client'], (uninstallErr, uninstallStdout, uninstallStderr) => {
                const uninstallOut = resultText(uninstallStdout, uninstallStderr);
                if (uninstallErr || !uninstallOut.includes('Success')) {
                    resolve({ success: false, error: `旧版客户端签名不兼容，自动卸载失败：${uninstallOut.trim() || uninstallErr?.message}` });
                    return;
                }
                execFile(adb, installArgs, (retryErr, retryStdout, retryStderr) => {
                    resolve(formatInstallResult(retryErr, retryStdout, retryStderr));
                });
            });
        });
    });
}

// 2. Hardware & Device Probing
function detectDeviceSpecs(serial) {
    const adb = getAdbPath();
    const prefix = serial ? `"${adb}" -s ${serial}` : `"${adb}"`;

    return new Promise((resolve) => {
        exec(`${prefix} shell "wm size; dumpsys display | grep -E 'mBaseDisplayInfo|modes|supportedModes'; cat /sys/class/udc/*/current_speed 2>/dev/null"`, (err, stdout) => {
            if (err || !stdout) {
                resolve({ width: 0, height: 0, fps: 60, usbTier: 'USB 链路未知' });
                return;
            }

            let width = 0;
            let height = 0;
            let fps = 60;
            let usbTier = 'USB 2.0 (480Mbps)';

            // Parse wm size
            const sizeMatch = stdout.match(/Physical size:\s*(\d+)x(\d+)/i) || stdout.match(/size:\s*(\d+)x(\d+)/i);
            if (sizeMatch) {
                width = parseInt(sizeMatch[1], 10);
                height = parseInt(sizeMatch[2], 10);
            }

            // Parse FPS
            const fpsMatches = stdout.match(/fps\s*=?\s*(\d+(\.\d+)?)/gi);
            if (fpsMatches) {
                for (const fm of fpsMatches) {
                    const val = parseFloat(fm.replace(/fps\s*=?\s*/i, ''));
                    if (val >= 119) {
                        fps = 120;
                        break;
                    } else if (val >= 89 && fps < 90) {
                        fps = 90;
                    } else if (val >= 59 && fps < 60) {
                        fps = 60;
                    }
                }
            }

            // Parse USB speed
            if (stdout.includes('super-speed-plus')) {
                usbTier = 'USB 3.2 Gen 2 (10Gbps 极速)';
            } else if (stdout.includes('super-speed')) {
                usbTier = 'USB 3.0 / 3.1 (5Gbps 高速)';
            } else if (stdout.includes('high-speed')) {
                usbTier = 'USB 2.0 (480Mbps)';
            }

            resolve({ width, height, fps, usbTier });
        });
    });
}

// 3. USB Benchmark
function testUsbSpeed(serial) {
    const adb = getAdbPath();
    const prefix = serial ? `"${adb}" -s ${serial}` : `"${adb}"`;
    const tmpDir = app.getPath('temp');
    const tmpFile = path.join(tmpDir, 'redisplay_speedtest.bin');

    try {
        const buf = Buffer.alloc(10 * 1024 * 1024, 0xAA);
        fs.writeFileSync(tmpFile, buf);

        const t0 = Date.now();
        return new Promise((resolve) => {
            exec(`${prefix} push "${tmpFile}" /data/local/tmp/speedtest.bin`, (err, stdout, stderr) => {
                const durationSec = (Date.now() - t0) / 1000;
                fs.unlink(tmpFile, () => {});
                exec(`${prefix} shell rm -f /data/local/tmp/speedtest.bin`);

                if (err) {
                    resolve({ success: false, error: err.message });
                    return;
                }

                const out = (stdout || '') + (stderr || '');
                const match = out.match(/([\d.]+)\s*MB\/s/i);
                let speedMBps = match ? parseFloat(match[1]) : (10 / durationSec);
                speedMBps = Math.round(speedMBps * 10) / 10;

                let tier = 'USB 2.0 (480Mbps)';
                if (speedMBps > 300) {
                    tier = 'USB 3.2 Gen 2 (10Gbps 极速)';
                } else if (speedMBps > 80) {
                    tier = 'USB 3.0 / 3.1 (5Gbps 高速)';
                }

                resolve({ success: true, speedMBps, tier });
            });
        });
    } catch (e) {
        return Promise.resolve({ success: false, error: e.message });
    }
}

// 4. Driver Management
function manageDriver(action) {
    const batPath = getDriverScriptPath(action);
    if (!batPath || !fs.existsSync(batPath)) {
        return Promise.resolve({ success: false, error: `未找到驱动脚本文件` });
    }

    return new Promise((resolve) => {
        const batDir = path.dirname(batPath);
        const psCmd = `Start-Process -FilePath "cmd.exe" -ArgumentList "/c cd /d ""${batDir}"" && ""${batPath}""" -Verb RunAs -Wait`;
        exec(`powershell -Command "${psCmd}"`, (err) => {
            if (err) {
                resolve({ success: false, error: '用户取消提权或驱动执行失败' });
            } else {
                resolve({ success: true, message: action === 'uninstall' ? '驱动卸载已执行' : '驱动安装已成功完成' });
            }
        });
    });
}

// 5. Host Process Management (100% Invisible, No Console Window)
async function startHostProcess(config) {
    if (hostProcess) {
        try { hostProcess.kill('SIGINT'); } catch (e) {}
        hostProcess = null;
    }

    streamStatus = 'connecting';
    currentConfig = { ...currentConfig, ...config };
    updateTrayMenu();
    const exePath = getHostExePath();
    if (!exePath) {
        const error = '电脑端组件 ReDisplayHost.exe 缺失。请关闭所有 ReDisplay 窗口后重新启动。';
        streamStatus = 'idle';
        updateTrayMenu();
        return { success: false, error };
    }

    // Auto-check and auto-install/update the client APK. The pen protocol was
    // introduced in v4, so merely finding the package is not sufficient.
    if (config.serial) {
        if (mainWindow && !mainWindow.isDestroyed()) {
            mainWindow.webContents.send('stream-event', {
                type: 'state',
                status: 'connecting',
                message: '检查安卓端客户端应用...'
            });
        }
        const checkRes = await checkClientInstalled(config.serial);
        if (!checkRes.installed || checkRes.outdated) {
            if (mainWindow && !mainWindow.isDestroyed()) {
                mainWindow.webContents.send('stream-event', {
                    type: 'state',
                    status: 'connecting',
                    message: checkRes.outdated
                        ? '检测到旧版客户端，正在升级 ReDisplay.apk...'
                        : '客户端未安装，正在自动向设备部署 ReDisplay.apk...'
                });
            }
            const installRes = await installClientApk(config.serial);
            if (!installRes.success) {
                if (mainWindow && !mainWindow.isDestroyed()) {
                    mainWindow.webContents.send('stream-event', {
                        type: 'state',
                        status: 'error',
                        message: `自动安装客户端失败: ${installRes.error}`
                    });
                }
                streamStatus = 'idle';
                updateTrayMenu();
                return { success: false, error: installRes.error };
            }
        }
    }

    loadWheelConfig();
    loadMacroLibrary();
    const inputPaths = wheelPaths();
    const args = [
        '--gui',
        '--wheel-config', inputPaths.binary,
        '--macro-dir', inputPaths.macros,
        '--macro-library', inputPaths.macroLibraryBinary
    ];
    if (config.display !== undefined && config.display !== null && config.display !== '') {
        args.push('--display', String(config.display));
    }
    if (config.fps) {
        args.push('--fps', String(config.fps));
    }
    if (config.bitrate) {
        args.push('--bitrate', String(config.bitrate));
    }
    if (config.width) {
        args.push('--width', String(config.width));
    }
    if (config.height) {
        args.push('--height', String(config.height));
    }
    if (config.bufferMode) {
        args.push('--buffer-mode', String(config.bufferMode));
    }
    if (currentConfig.touchInput) {
        args.push('--touch-input', String(currentConfig.touchInput));
    }
    if (currentConfig.touchPressure) {
        args.push('--touch-pressure', String(currentConfig.touchPressure));
    }
    if (config.serial) {
        args.push('--serial', String(config.serial));
    }
    if (config.port) {
        args.push('--port', String(config.port));
    }

    activeStreamSerial = config.serial || '';
    console.log('[Host] Spawning silently:', exePath, args.join(' '));

    try {
        hostProcess = spawn(exePath, args, {
            cwd: path.dirname(exePath),
            windowsHide: true,
            stdio: ['pipe', 'pipe', 'pipe']
        });

        const rl = readline.createInterface({
            input: hostProcess.stdout,
            terminal: false
        });

        rl.on('line', (line) => {
            const trimmed = line.trim();
            if (!trimmed) return;
            try {
                const data = JSON.parse(trimmed);
                if (data.type === 'state') {
                    if (data.status === 'streaming') {
                        streamStatus = 'streaming';
                        updateTrayMenu();
                    } else if (data.status === 'connecting' || data.status === 'launching_app') {
                        streamStatus = 'connecting';
                        updateTrayMenu();
                    }
                } else if (data.type === 'device_selected') {
                    activeStreamDeviceName = data.model || '';
                    activeStreamSerial = data.serial || '';
                    updateTrayMenu();
                } else if (data.type === 'stats') {
                    latestStats = {
                        fps: data.fps || 0,
                        mbps: data.mbps || 0,
                        encodeMs: data.encodeMs || 0,
                        dropped: data.dropped || 0,
                        frames: data.frames || 0
                    };
                    const now = Date.now();
                    if (now - lastTrayUpdateTime > 1500) {
                        lastTrayUpdateTime = now;
                        updateTrayMenu();
                    }
                }
                if (mainWindow && !mainWindow.isDestroyed()) {
                    mainWindow.webContents.send('stream-event', data);
                }
            } catch (e) {
                console.log('[Host stdout]', trimmed);
            }
        });

        hostProcess.stderr.on('data', (data) => {
            console.error('[Host stderr]', data.toString());
        });

        hostProcess.on('exit', (code, signal) => {
            console.log(`[Host] Process exited with code ${code}`);
            hostProcess = null;
            streamStatus = 'idle';
            latestStats = { fps: 0, mbps: 0, encodeMs: 0, dropped: 0, frames: 0 };
            updateTrayMenu();
            if (mainWindow && !mainWindow.isDestroyed()) {
                mainWindow.webContents.send('stream-status', { status: 'stopped', code });
            }
        });

        hostProcess.on('error', (err) => {
            console.error('[Host] Failed to spawn:', err);
            hostProcess = null;
            streamStatus = 'idle';
            latestStats = { fps: 0, mbps: 0, encodeMs: 0, dropped: 0, frames: 0 };
            updateTrayMenu();
            if (mainWindow && !mainWindow.isDestroyed()) {
                mainWindow.webContents.send('stream-status', { status: 'error', message: err.message });
            }
        });

        return { success: true };
    } catch (err) {
        streamStatus = 'idle';
        updateTrayMenu();
        return { success: false, error: err.message };
    }
}

function stopHostProcess(serial) {
    console.log('[Host] Stopping secondary display stream...');
    if (hostProcess) {
        try { hostProcess.kill('SIGINT'); } catch (e) {}
        try { hostProcess.kill(); } catch (e) {}
        hostProcess = null;
    }
    streamStatus = 'idle';
    latestStats = { fps: 0, mbps: 0, encodeMs: 0, dropped: 0, frames: 0 };
    updateTrayMenu();

    // Only kill the C++ Host engine (NEVER ReDisplay.exe, which is the GUI application itself!)
    exec('taskkill /F /IM ReDisplayHost.exe >nul 2>&1');

    // Clean up channel and exit Android client application
    const adbPath = getAdbPath();
    const targetSerial = serial || activeStreamSerial || '';
    const adbCmd = targetSerial ? `"${adbPath}" -s ${targetSerial}` : `"${adbPath}"`;

    exec(`${adbCmd} forward --remove tcp:27183 >nul 2>&1`);
    exec(`${adbCmd} shell am force-stop com.redisplay.client >nul 2>&1`);
    console.log('[Host] Stream channel cleared and Android client stopped.');

    if (mainWindow && !mainWindow.isDestroyed()) {
        mainWindow.webContents.send('stream-status', { status: 'stopped' });
    }
    return { success: true };
}

let isRestarting = false;

async function restartHostProcess(config) {
    if (isRestarting) {
        console.log('[Host] Restart already in progress, waiting...');
    }
    isRestarting = true;
    try {
        console.log('[Host] Performing atomic hot-restart of secondary display...');
        
        // 1. Terminate running host process
        if (hostProcess) {
            try { hostProcess.kill('SIGINT'); } catch (e) {}
            try { hostProcess.kill('SIGTERM'); } catch (e) {}
            hostProcess = null;
        }

        // 2. Synchronously ensure old ReDisplayHost.exe is dead
        try {
            execSync('taskkill /F /IM ReDisplayHost.exe >nul 2>&1');
        } catch (e) {}

        // Small grace period for Windows sockets/D3D cleanup
        await new Promise(r => setTimeout(r, 250));

        // 3. Start new host process with new parameters
        return await startHostProcess(config);
    } finally {
        isRestarting = false;
    }
}

// Single instance enforcement
const gotTheLock = app.requestSingleInstanceLock();
if (!gotTheLock) {
    app.quit();
} else {
    app.on('second-instance', () => {
        if (mainWindow) {
            if (mainWindow.isMinimized()) mainWindow.restore();
            mainWindow.show();
            mainWindow.focus();
        }
    });

    app.whenReady().then(() => {
        currentConfig = { ...currentConfig, ...loadTouchConfig() };
        createWindow();
        createTray();

        // Window controls
        ipcMain.on('window-minimize', () => mainWindow?.minimize());
        ipcMain.on('window-maximize', () => {
            if (mainWindow?.isMaximized()) {
                mainWindow.unmaximize();
            } else {
                mainWindow?.maximize();
            }
        });
        ipcMain.on('window-close', () => mainWindow?.close());
        ipcMain.on('window-hide-to-tray', () => mainWindow?.hide());
        ipcMain.on('config-changed', (event, newConfig) => {
            currentConfig = { ...currentConfig, ...newConfig };
            updateTrayMenu();
        });

        // Stream IPC
        ipcMain.handle('start-stream', (event, config) => startHostProcess(config));
        ipcMain.handle('stop-stream', (event, serial) => stopHostProcess(serial));
        ipcMain.handle('restart-stream', async (event, config) => restartHostProcess(config));
        ipcMain.handle('get-service-status', () => ({ running: !!hostProcess }));
        ipcMain.handle('get-wheel-config', () => loadWheelConfig());
        ipcMain.handle('get-touch-config', () => loadTouchConfig());
        ipcMain.handle('save-touch-config', async (event, config) => {
            try {
                const saved = saveTouchConfig(config);
                let restarted = false;
                if (hostProcess) {
                    const result = await restartHostProcess(currentConfig);
                    if (!result.success) return result;
                    restarted = true;
                }
                return { success: true, config: saved, restarted };
            } catch (error) {
                return { success: false, error: error.message };
            }
        });
        ipcMain.handle('save-wheel-config', async (event, config) => {
            try {
                const saved = saveWheelConfig(config);
                let restarted = false;
                if (hostProcess) {
                    const result = await restartHostProcess(currentConfig);
                    if (!result.success) return result;
                    restarted = true;
                }
                return { success: true, config: saved, restarted };
            } catch (error) {
                return { success: false, error: error.message };
            }
        });
        ipcMain.handle('reset-wheel-config', async () => {
            const saved = saveWheelConfig(defaultWheelConfig());
            let restarted = false;
            if (hostProcess) {
                const result = await restartHostProcess(currentConfig);
                if (!result.success) return result;
                restarted = true;
            }
            return { success: true, config: saved, restarted };
        });
        ipcMain.handle('get-macro-library', () => loadMacroLibrary());
        ipcMain.handle('save-macro-library', async (event, library) => {
            try {
                const saved = saveMacroLibrary(library);
                let restarted = false;
                if (hostProcess) {
                    const result = await restartHostProcess(currentConfig);
                    if (!result.success) return result;
                    restarted = true;
                }
                return { success: true, library: saved, restarted };
            } catch (error) {
                return { success: false, error: error.message };
            }
        });

        // Hardware & Displays
        ipcMain.handle('get-displays', async () => {
            const exePath = getHostExePath();
            if (!exePath) return [];
            return new Promise((resolve) => {
                execFile(exePath, ['--list-displays'], { cwd: path.dirname(exePath) }, (err, stdout) => {
                    if (err || !stdout) {
                        resolve([]);
                        return;
                    }
                    try {
                        const lines = stdout.split(/\r?\n/);
                        for (const line of lines) {
                            const trimmed = line.trim();
                            if (trimmed.startsWith('{') && trimmed.includes('"displays"')) {
                                const data = JSON.parse(trimmed);
                                resolve(data.displays || []);
                                return;
                            }
                        }
                        const data = JSON.parse(stdout.trim());
                        resolve(data.displays || []);
                    } catch (e) {
                        resolve([]);
                    }
                });
            });
        });

        ipcMain.handle('get-devices', async () => {
            const exePath = getHostExePath();
            if (!exePath) return [];
            return new Promise((resolve) => {
                execFile(exePath, ['--list-devices'], { cwd: path.dirname(exePath) }, (err, stdout) => {
                    if (err || !stdout) {
                        resolve([]);
                        return;
                    }
                    try {
                        const lines = stdout.split(/\r?\n/);
                        for (const line of lines) {
                            const trimmed = line.trim();
                            if (trimmed.startsWith('{') && trimmed.includes('"devices"')) {
                                const data = JSON.parse(trimmed);
                                resolve(data.devices || []);
                                return;
                            }
                        }
                        const data = JSON.parse(stdout.trim());
                        resolve(data.devices || []);
                    } catch (e) {
                        resolve([]);
                    }
                });
            });
        });

        ipcMain.handle('get-gpu-info', async () => {
            const exePath = getHostExePath();
            if (!exePath) return { gpuName: 'ReDisplayHost.exe 缺失' };
            return new Promise((resolve) => {
                execFile(exePath, ['--list-displays'], { cwd: path.dirname(exePath) }, (err, stdout) => {
                    if (!err && stdout) {
                        try {
                            const data = JSON.parse(stdout.trim());
                            if (data.displays) {
                                for (const d of data.displays) {
                                    if (d.adapter && !d.adapter.includes('Virtual') && !d.adapter.includes('Microsoft')) {
                                        resolve({ gpuName: d.adapter });
                                        return;
                                    }
                                }
                            }
                        } catch (e) {}
                    }
                    resolve({ gpuName: 'NVIDIA GeForce GPU' });
                });
            });
        });

        // Device Probing & Benchmarking
        ipcMain.handle('check-client-installed', (event, serial) => checkClientInstalled(serial));
        ipcMain.handle('install-client-apk', (event, serial) => installClientApk(serial));
        ipcMain.handle('detect-device-specs', (event, serial) => detectDeviceSpecs(serial));
        ipcMain.handle('test-usb-speed', (event, serial) => testUsbSpeed(serial));

        // Driver & Windows Settings
        ipcMain.handle('check-driver-status', () => {
            const xmlPath = 'C:\\VirtualDisplayDriver\\vdd_settings.xml';
            return fs.existsSync(xmlPath);
        });
        ipcMain.handle('manage-driver', (event, action) => manageDriver(action));
        ipcMain.handle('open-display-settings', () => {
            exec('start ms-settings:display');
            return true;
        });
    });

    app.on('before-quit', () => {
        isQuitting = true;
        stopHostProcess();
    });

    app.on('window-all-closed', () => {});
}
