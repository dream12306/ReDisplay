#include <cassert>
#include <iostream>

#include <winsock2.h>

#include "protocol.h"
#include "wheel_config.h"

int main() {
    auto payload = BuildDefaultWheelConfigPayload();
    assert(ValidateWheelConfigPayload(payload));
    assert(payload.size() == sizeof(WheelConfigHeader) + WHEEL_SLOT_COUNT * sizeof(WheelSlotPayload));
    const auto* slots = reinterpret_cast<const WheelSlotPayload*>(
        payload.data() + sizeof(WheelConfigHeader)
    );
    assert(slots[0].action == INPUT_ACTION_LEFT_CLICK);
    assert(slots[1].action == INPUT_ACTION_DOUBLE_CLICK);
    assert(slots[2].action == INPUT_ACTION_RIGHT_CLICK);
    assert(slots[8].action == INPUT_ACTION_KEY_CHORD);
    assert(ntohs(slots[8].key_code) == 'B');
    assert(slots[14].action == INPUT_ACTION_RECORDING_ZONE);

    auto badVersion = payload;
    badVersion[0] = 0;
    badVersion[1] = 3;
    assert(!ValidateWheelConfigPayload(badVersion));

    auto badAction = payload;
    badAction[sizeof(WheelConfigHeader)] = 255;
    assert(!ValidateWheelConfigPayload(badAction));

    auto badLabel = payload;
    auto* firstSlot = reinterpret_cast<WheelSlotPayload*>(badLabel.data() + sizeof(WheelConfigHeader));
    for (char& c : firstSlot->label) c = 'x';
    assert(!ValidateWheelConfigPayload(badLabel));

    std::cout << "[PASS] wheel config validation tests\n";
    return 0;
}

