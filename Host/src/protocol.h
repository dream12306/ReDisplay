#pragma once
#include <cstdint>

#pragma pack(push, 1)

constexpr uint32_t REDISPLAY_MAGIC = 0x52445350; // "RDSP"

enum PacketType : uint8_t {
    PACKET_TYPE_VIDEO = 1,
    PACKET_TYPE_CONFIG = 2,
    PACKET_TYPE_PING = 3,
    PACKET_TYPE_PONG = 4,
    PACKET_TYPE_PEN = 5,
    PACKET_TYPE_WHEEL_CONFIG = 6,
    PACKET_TYPE_INPUT_COMMAND = 7
};

enum PacketFlags : uint8_t {
    FLAG_NONE = 0,
    FLAG_KEYFRAME = 1 << 0
};

struct PacketHeader {
    uint32_t magic;         // 0x52445350
    uint8_t  packet_type;   // PacketType
    uint8_t  flags;         // PacketFlags
    uint16_t reserved;      // 0
    uint64_t timestamp_us;  // Microsecond timestamp
    uint32_t payload_size;  // Size in bytes
};

struct ConfigPayload {
    uint32_t width;
    uint32_t height;
    uint32_t fps;
};

enum PenAction : uint8_t {
    PEN_ACTION_HOVER_ENTER = 1,
    PEN_ACTION_HOVER_MOVE = 2,
    PEN_ACTION_HOVER_EXIT = 3,
    PEN_ACTION_DOWN = 4,
    PEN_ACTION_MOVE = 5,
    PEN_ACTION_UP = 6,
    PEN_ACTION_CANCEL = 7
};

enum PenTool : uint8_t {
    PEN_TOOL_STYLUS = 1,
    PEN_TOOL_ERASER = 2
};

enum PenButtons : uint16_t {
    PEN_BUTTON_NONE = 0,
    PEN_BUTTON_BARREL = 1 << 0,
    PEN_BUTTON_SECONDARY = 1 << 1
};

enum InputAction : uint8_t {
    INPUT_ACTION_NONE = 0,
    INPUT_ACTION_KEY_CHORD = 1,
    INPUT_ACTION_RIGHT_CLICK = 2,
    INPUT_ACTION_RECORDING_ZONE = 3, // Android-local navigation action.
    INPUT_ACTION_RECORD_START = 4,
    INPUT_ACTION_RECORD_STOP = 5,
    INPUT_ACTION_RECORD_CANCEL = 6,
    INPUT_ACTION_SAVE_LAST_STROKE = 7,
    INPUT_ACTION_SAVE_LAST_TAP = 8,
    INPUT_ACTION_REPLAY_LAST_STROKE = 9,
    INPUT_ACTION_REPLAY_LAST_TAP = 10,
    INPUT_ACTION_REPLAY_LATEST = 11,
    INPUT_ACTION_BACK = 12, // Android-local navigation action.
    INPUT_ACTION_LEFT_CLICK = 13,
    INPUT_ACTION_DOUBLE_CLICK = 14
};

enum InputModifiers : uint8_t {
    INPUT_MODIFIER_NONE = 0,
    INPUT_MODIFIER_CTRL = 1 << 0,
    INPUT_MODIFIER_SHIFT = 1 << 1,
    INPUT_MODIFIER_ALT = 1 << 2,
    INPUT_MODIFIER_WIN = 1 << 3
};

// Android -> Host. All multi-byte fields are encoded in network byte order.
// Coordinates and pressure use the full uint16 range so the transport stays
// independent of either device's physical resolution and pressure levels.
struct PenPayload {
    uint32_t sequence;
    uint8_t action;
    uint8_t tool;
    uint16_t buttons;
    uint16_t x;
    uint16_t y;
    uint16_t pressure;
    int16_t tilt_x_hundredths;
    int16_t tilt_y_hundredths;
    uint16_t reserved;
};

// Android -> Host. Used for wheel selections and recorder control. The
// selected action is sent instead of a slot number so config revisions cannot
// make the tablet and Host disagree about what a slot means.
struct InputCommandPayload {
    uint32_t sequence;
    uint8_t action;
    uint8_t modifiers;
    uint16_t key_code;
    uint16_t x;
    uint16_t y;
    uint32_t parameter;
    uint32_t reserved;
};

constexpr uint16_t WHEEL_CONFIG_SCHEMA_VERSION = 2;
constexpr uint8_t WHEEL_INNER_SLOT_COUNT = 8;
constexpr uint8_t WHEEL_OUTER_SLOT_COUNT = 8;
constexpr uint8_t WHEEL_SLOT_COUNT = WHEEL_INNER_SLOT_COUNT + WHEEL_OUTER_SLOT_COUNT;

// Host -> Android. Labels are UTF-8, NUL-terminated when shorter than the
// fixed field. All multi-byte numeric fields use network byte order.
struct WheelConfigHeader {
    uint16_t schema_version;
    uint8_t inner_slots;
    uint8_t outer_slots;
    uint32_t revision;
};

struct WheelSlotPayload {
    uint8_t action;
    uint8_t modifiers;
    uint16_t key_code;
    uint32_t parameter;
    char label[32];
};

static_assert(sizeof(PacketHeader) == 20, "PacketHeader wire size changed");
static_assert(sizeof(PenPayload) == 20, "PenPayload wire size changed");
static_assert(sizeof(InputCommandPayload) == 20, "InputCommandPayload wire size changed");
static_assert(sizeof(WheelConfigHeader) == 8, "WheelConfigHeader wire size changed");
static_assert(sizeof(WheelSlotPayload) == 40, "WheelSlotPayload wire size changed");

#pragma pack(pop)
