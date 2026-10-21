#include "wheel_config.h"

#include <winsock2.h>

#include <algorithm>
#include <cstring>
#include <fstream>
#include <iterator>

#include "protocol.h"

namespace {

constexpr size_t kPayloadSize = sizeof(WheelConfigHeader) +
    WHEEL_SLOT_COUNT * sizeof(WheelSlotPayload);

bool IsValidAction(uint8_t action) {
    return action <= INPUT_ACTION_DOUBLE_CLICK;
}

void CopyLabel(char (&target)[32], const char* source) {
    std::memset(target, 0, sizeof(target));
    const size_t count = (std::min)(std::strlen(source), sizeof(target) - 1);
    std::memcpy(target, source, count);
}

WheelSlotPayload MakeSlot(
    uint8_t action,
    const char* label,
    uint8_t modifiers = INPUT_MODIFIER_NONE,
    uint16_t keyCode = 0,
    uint32_t parameter = 0
) {
    WheelSlotPayload slot{};
    slot.action = action;
    slot.modifiers = modifiers;
    slot.key_code = htons(keyCode);
    slot.parameter = htonl(parameter);
    CopyLabel(slot.label, label);
    return slot;
}

} // namespace

bool ValidateWheelConfigPayload(const std::vector<uint8_t>& payload) {
    if (payload.size() != kPayloadSize) return false;

    WheelConfigHeader header{};
    std::memcpy(&header, payload.data(), sizeof(header));
    if (ntohs(header.schema_version) != WHEEL_CONFIG_SCHEMA_VERSION ||
        header.inner_slots != WHEEL_INNER_SLOT_COUNT ||
        header.outer_slots != WHEEL_OUTER_SLOT_COUNT) {
        return false;
    }

    const auto* slots = reinterpret_cast<const WheelSlotPayload*>(
        payload.data() + sizeof(WheelConfigHeader)
    );
    for (size_t index = 0; index < WHEEL_SLOT_COUNT; ++index) {
        const auto& slot = slots[index];
        if (!IsValidAction(slot.action) ||
            (slot.modifiers & ~(INPUT_MODIFIER_CTRL | INPUT_MODIFIER_SHIFT |
                INPUT_MODIFIER_ALT | INPUT_MODIFIER_WIN)) != 0 ||
            std::memchr(slot.label, '\0', sizeof(slot.label)) == nullptr) {
            return false;
        }
        const uint16_t keyCode = ntohs(slot.key_code);
        if (slot.action == INPUT_ACTION_KEY_CHORD && (keyCode == 0 || keyCode > 0xFF)) {
            return false;
        }
    }
    return true;
}

std::vector<uint8_t> BuildDefaultWheelConfigPayload() {
    WheelConfigHeader header{};
    header.schema_version = htons(WHEEL_CONFIG_SCHEMA_VERSION);
    header.inner_slots = WHEEL_INNER_SLOT_COUNT;
    header.outer_slots = WHEEL_OUTER_SLOT_COUNT;
    header.revision = htonl(1);

    const WheelSlotPayload slots[WHEEL_SLOT_COUNT] = {
        MakeSlot(INPUT_ACTION_LEFT_CLICK, "Click"),
        MakeSlot(INPUT_ACTION_DOUBLE_CLICK, "Double click"),
        MakeSlot(INPUT_ACTION_RIGHT_CLICK, "Right click"),
        MakeSlot(INPUT_ACTION_KEY_CHORD, "Enter", INPUT_MODIFIER_NONE, 0x0D),
        MakeSlot(INPUT_ACTION_KEY_CHORD, "Copy", INPUT_MODIFIER_CTRL, 'C'),
        MakeSlot(INPUT_ACTION_KEY_CHORD, "Paste", INPUT_MODIFIER_CTRL, 'V'),
        MakeSlot(INPUT_ACTION_KEY_CHORD, "Undo", INPUT_MODIFIER_CTRL, 'Z'),
        MakeSlot(INPUT_ACTION_KEY_CHORD, "Redo", INPUT_MODIFIER_CTRL, 'Y'),
        MakeSlot(INPUT_ACTION_KEY_CHORD, "Pen", INPUT_MODIFIER_NONE, 'B'),
        MakeSlot(INPUT_ACTION_KEY_CHORD, "Eraser", INPUT_MODIFIER_NONE, 'E'),
        MakeSlot(INPUT_ACTION_SAVE_LAST_STROKE, "Save stroke"),
        MakeSlot(INPUT_ACTION_REPLAY_LAST_STROKE, "Repeat stroke"),
        MakeSlot(INPUT_ACTION_SAVE_LAST_TAP, "Save tap"),
        MakeSlot(INPUT_ACTION_REPLAY_LAST_TAP, "Repeat tap"),
        MakeSlot(INPUT_ACTION_RECORDING_ZONE, "Recording"),
        MakeSlot(INPUT_ACTION_REPLAY_LATEST, "Replay saved"),
    };

    std::vector<uint8_t> payload(kPayloadSize);
    std::memcpy(payload.data(), &header, sizeof(header));
    std::memcpy(payload.data() + sizeof(header), slots, sizeof(slots));
    return payload;
}

std::vector<uint8_t> LoadWheelConfigPayload(const std::string& path, bool* loadedFromFile) {
    if (loadedFromFile != nullptr) *loadedFromFile = false;
    if (path.empty()) return BuildDefaultWheelConfigPayload();

    std::ifstream input(path, std::ios::binary);
    if (!input) return BuildDefaultWheelConfigPayload();
    std::vector<uint8_t> payload{
        std::istreambuf_iterator<char>(input),
        std::istreambuf_iterator<char>{}
    };
    if (!ValidateWheelConfigPayload(payload)) return BuildDefaultWheelConfigPayload();
    if (loadedFromFile != nullptr) *loadedFromFile = true;
    return payload;
}

