#include <cassert>
#include <iostream>
#include <windows.h>

#include "pen_input_math.h"

int main() {
    assert(MapNormalizedPenCoordinate(0, 100, 2800) == 100);
    assert(MapNormalizedPenCoordinate(65535, 100, 2800) == 2899);
    assert(MapNormalizedPenCoordinate(0, -2800, 2800) == -2800);
    assert(MapNormalizedPenCoordinate(65535, -2800, 2800) == -1);
    assert(MapNormalizedPenCoordinate(32768, 0, 2800) == 1400);

    // InjectSyntheticPointerInput uses coordinates relative to the virtual
    // desktop's top-left, not signed desktop coordinates. A display placed
    // left of the primary therefore maps [-2800, -1] to [0, 2799].
    assert(MapNormalizedPenCoordinateForInjection(0, -2800, 2800, -2800) == 0);
    assert(MapNormalizedPenCoordinateForInjection(65535, -2800, 2800, -2800) == 2799);
    assert(MapNormalizedPenCoordinateForInjection(0, 0, 2560, -2800) == 2800);
    assert(MapNormalizedPenCoordinateForInjection(65535, 0, 2560, -2800) == 5359);

    // The same translation is required for a display above the primary.
    assert(MapNormalizedPenCoordinateForInjection(0, -1752, 1752, -1752) == 0);
    assert(MapNormalizedPenCoordinateForInjection(65535, -1752, 1752, -1752) == 1751);

    assert(MapNormalizedPenPressure(0, false) == 0);
    assert(MapNormalizedPenPressure(0, true) == 1);
    assert(MapNormalizedPenPressure(65535, true) == 1024);

    assert(MapPenTiltHundredths(-12000) == -90);
    assert(MapPenTiltHundredths(-6049) == -60);
    assert(MapPenTiltHundredths(0) == 0);
    assert(MapPenTiltHundredths(6051) == 61);
    assert(MapPenTiltHundredths(12000) == 90);

    std::cout << "pen input mapping tests passed\n";
    return 0;
}
