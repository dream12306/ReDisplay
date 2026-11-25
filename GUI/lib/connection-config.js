'use strict';

function isValidIpv4(value) {
    const parts = String(value || '').trim().split('.');
    if (parts.length !== 4 || !parts.every(part => {
        if (!/^\d{1,3}$/.test(part)) return false;
        const value = Number(part);
        return value >= 0 && value <= 255 && String(value) === part;
    })) return false;
    const octets = parts.map(Number);
    return octets[0] !== 0 && octets[0] !== 127 && octets[0] < 224
        && !(octets[0] === 169 && octets[1] === 254);
}

function splitAddress(value, fallbackPort = 27183) {
    const text = String(value || '').trim();
    const separator = text.lastIndexOf(':');
    if (separator > 0 && text.indexOf(':') === separator) {
        const address = text.slice(0, separator).trim();
        const port = Number.parseInt(text.slice(separator + 1), 10);
        return { address, port: port >= 1 && port <= 65535 ? port : fallbackPort };
    }
    return { address: text, port: fallbackPort };
}

function normalizeConnectionConfig(config = {}) {
    const transport = config.transport === 'wifi' ? 'wifi' : 'usb';
    const parsed = splitAddress(config.wirelessAddress, config.wirelessPort || 27183);
    const parsedPort = Number.parseInt(config.wirelessPort ?? parsed.port, 10);
    return {
        transport,
        wirelessAddress: isValidIpv4(parsed.address) ? parsed.address : '',
        wirelessPort: parsedPort >= 1 && parsedPort <= 65535 ? parsedPort : 27183
    };
}

module.exports = { isValidIpv4, splitAddress, normalizeConnectionConfig };
