#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>

inline LONG MapNormalizedPenCoordinate(uint16_t value, LONG origin, int extent) {
    if (extent <= 1) return origin;
    const double scaled = static_cast<double>(value) * static_cast<double>(extent - 1) / 65535.0;
    return origin + static_cast<LONG>(std::lround(scaled));
}

// InjectSyntheticPointerInput defines ptPixelLocation relative to the virtual
// desktop's top-left. MONITORINFO, however, reports signed virtual-screen
// coordinates, so displays left of or above the primary must be translated.
inline LONG MapNormalizedPenCoordinateForInjection(
    uint16_t value,
    LONG displayOrigin,
    int extent,
    LONG virtualDesktopOrigin
) {
    return MapNormalizedPenCoordinate(value, displayOrigin, extent) - virtualDesktopOrigin;
}

inline UINT32 MapNormalizedPenPressure(uint16_t value, bool inContact) {
    UINT32 pressure = static_cast<UINT32>(std::lround(
        static_cast<double>(value) * 1024.0 / 65535.0
    ));
    pressure = (std::min)(pressure, 1024u);
    if (inContact && pressure == 0) pressure = 1;
    return pressure;
}

inline INT32 MapPenTiltHundredths(int16_t value) {
    const int degrees = static_cast<int>(std::lround(static_cast<double>(value) / 100.0));
    return static_cast<INT32>((std::max)(-90, (std::min)(90, degrees)));
}
