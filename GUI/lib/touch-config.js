'use strict';

const ACCEPTED_TOUCH_MODES = new Set(['auto', 'stylus', 'finger']);

function normalizeTouchConfig(config = {}) {
    const touchInput = ACCEPTED_TOUCH_MODES.has(config.touchInput) ? config.touchInput : 'auto';
    const parsedPressure = Number.parseInt(config.touchPressure, 10);
    const touchPressure = Number.isFinite(parsedPressure)
        ? Math.max(10, Math.min(100, parsedPressure))
        : 55;
    return { touchInput, touchPressure };
}

module.exports = { normalizeTouchConfig };
