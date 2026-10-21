#pragma once

#include <cstdint>

#include "protocol.h"

enum class PointerInputMode : uint8_t {
    Pen,
    Eraser,
    Mouse
};

inline uint8_t ResolvePenTool(PointerInputMode mode, uint8_t sourceTool) {
    return mode == PointerInputMode::Eraser ? PEN_TOOL_ERASER : sourceTool;
}

enum class MouseInputTransition : uint8_t {
    None,
    Move,
    LeftDown,
    LeftUp
};

class MouseInputState {
public:
    MouseInputTransition Handle(uint8_t penAction) {
        switch (penAction) {
        case PEN_ACTION_HOVER_ENTER:
        case PEN_ACTION_HOVER_MOVE:
        case PEN_ACTION_MOVE:
            return MouseInputTransition::Move;
        case PEN_ACTION_DOWN:
            if (m_buttonDown) return MouseInputTransition::Move;
            m_buttonDown = true;
            return MouseInputTransition::LeftDown;
        case PEN_ACTION_UP:
        case PEN_ACTION_CANCEL:
            if (!m_buttonDown) return MouseInputTransition::None;
            m_buttonDown = false;
            return MouseInputTransition::LeftUp;
        case PEN_ACTION_HOVER_EXIT:
        default:
            return MouseInputTransition::None;
        }
    }

    bool IsButtonDown() const { return m_buttonDown; }

    bool Reset() {
        const bool wasDown = m_buttonDown;
        m_buttonDown = false;
        return wasDown;
    }

private:
    bool m_buttonDown = false;
};
