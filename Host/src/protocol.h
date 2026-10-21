#pragma once
#include <cstdint>

#pragma pack(push, 1)

constexpr uint32_t REDISPLAY_MAGIC = 0x52445350; // "RDSP"

enum PacketType : uint8_t {
    PACKET_TYPE_VIDEO = 1,
    PACKET_TYPE_CONFIG = 2,
    PACKET_TYPE_PING = 3,
    PACKET_TYPE_PONG = 4,
    PACKET_TYPE_PEN = 5
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

static_assert(sizeof(PacketHeader) == 20, "PacketHeader wire size changed");
static_assert(sizeof(PenPayload) == 20, "PenPayload wire size changed");

#pragma pack(pop)
