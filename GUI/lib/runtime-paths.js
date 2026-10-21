const fs = require('fs');
const path = require('path');

function hostExeCandidates({ resourcesPath, appDir }) {
    return [
        path.join(resourcesPath, 'bin', 'ReDisplayHost.exe'),
        path.join(appDir, '..', 'bin', 'ReDisplayHost.exe'),
        path.join(appDir, 'resources', 'bin', 'ReDisplayHost.exe'),
        path.join(appDir, '..', 'Host', 'ReDisplayHost.exe'),
        path.join(appDir, '..', 'Host', 'ReDisplay.exe'),
        path.join(appDir, '..', '..', 'Host', 'ReDisplayHost.exe'),
        path.join(appDir, '..', '..', 'Host', 'ReDisplay.exe'),
        path.join(appDir, 'bin', 'ReDisplayHost.exe'),
        path.join(appDir, 'ReDisplayHost.exe')
    ];
}

function resolveHostExePath(context, existsSync = fs.existsSync) {
    return hostExeCandidates(context).find(candidate => existsSync(candidate)) || null;
}

module.exports = { hostExeCandidates, resolveHostExePath };
