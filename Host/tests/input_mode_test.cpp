#include <cassert>
#include <iostream>

#include "input_mode.h"

int main() {
    MouseInputState mouse;
    assert(mouse.Handle(PEN_ACTION_HOVER_MOVE) == MouseInputTransition::Move);
    assert(mouse.Handle(PEN_ACTION_DOWN) == MouseInputTransition::LeftDown);
    assert(mouse.Handle(PEN_ACTION_MOVE) == MouseInputTransition::Move);
    assert(mouse.Handle(PEN_ACTION_UP) == MouseInputTransition::LeftUp);
    assert(mouse.Handle(PEN_ACTION_HOVER_EXIT) == MouseInputTransition::None);

    assert(mouse.Handle(PEN_ACTION_DOWN) == MouseInputTransition::LeftDown);
    assert(mouse.Handle(PEN_ACTION_CANCEL) == MouseInputTransition::LeftUp);
    assert(!mouse.IsButtonDown());

    assert(ResolvePenTool(PointerInputMode::Pen, PEN_TOOL_STYLUS) == PEN_TOOL_STYLUS);
    assert(ResolvePenTool(PointerInputMode::Eraser, PEN_TOOL_STYLUS) == PEN_TOOL_ERASER);
    assert(ResolvePenTool(PointerInputMode::Pen, PEN_TOOL_ERASER) == PEN_TOOL_ERASER);

    std::cout << "input mode tests passed\n";
    return 0;
}
