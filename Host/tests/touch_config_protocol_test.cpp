#include "protocol.h"

#include <cassert>
#include <cstddef>
#include <cstdint>
#include <iostream>

int main() {
    static_assert(sizeof(ConfigPayload) == 16, "Config payload must remain wire-compatible");
    static_assert(offsetof(ConfigPayload, touch_input_mode) == 12);
    static_assert(offsetof(ConfigPayload, touch_pressure_percent) == 13);

    ConfigPayload payload{};
    payload.touch_input_mode = TOUCH_INPUT_FINGER;
    payload.touch_pressure_percent = 55;
    assert(payload.touch_input_mode == 2);
    assert(payload.touch_pressure_percent == 55);

    std::cout << "touch config protocol tests passed\n";
    return 0;
}
