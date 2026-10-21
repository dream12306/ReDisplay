#pragma once

#include <cstdint>

#include "protocol.h"

enum class PlaybackPenDisposition : uint8_t {
    Suppress,
    CancelAndInject
};

// While synthetic pen playback owns the pointer, passive packets from the
// wheel-selection gesture must not disturb it. A fresh DOWN is the only
// unambiguous signal that the user wants to take control back.
constexpr PlaybackPenDisposition ClassifyLivePenDuringPlayback(uint8_t action) {
    return action == PEN_ACTION_DOWN
        ? PlaybackPenDisposition::CancelAndInject
        : PlaybackPenDisposition::Suppress;
}
