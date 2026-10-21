#include <cassert>
#include <cstdint>
#include <iostream>

#include <winsock2.h>

#include "pen_macro.h"

namespace {

PenPayload MakePen(uint8_t action, uint16_t x, uint16_t y) {
    PenPayload payload{};
    payload.action = action;
    payload.tool = PEN_TOOL_STYLUS;
    payload.x = htons(x);
    payload.y = htons(y);
    return payload;
}

} // namespace

int main() {
    PenMacroRecorder recorder;
    recorder.Observe(1000, MakePen(PEN_ACTION_DOWN, 10000, 20000));
    recorder.Observe(1100, MakePen(PEN_ACTION_MOVE, 12000, 21000));
    recorder.Observe(1200, MakePen(PEN_ACTION_UP, 14000, 22000));
    assert(recorder.LastStroke().size() == 3);

    const auto anchored = PenMacroRecorder::Reanchor(recorder.LastStroke(), 30000, 40000);
    assert(ntohs(anchored[0].payload.x) == 30000);
    assert(ntohs(anchored[0].payload.y) == 40000);
    assert(ntohs(anchored[2].payload.x) == 34000);
    assert(ntohs(anchored[2].payload.y) == 42000);

    recorder.Observe(2000, MakePen(PEN_ACTION_DOWN, 5000, 6000));
    recorder.Observe(220000, MakePen(PEN_ACTION_UP, 5100, 6050));
    assert(recorder.LastTap().size() == 2);

    recorder.Start();
    recorder.Observe(3000, MakePen(PEN_ACTION_DOWN, 100, 200));
    recorder.Observe(3100, MakePen(PEN_ACTION_UP, 100, 200));
    auto macro = recorder.Stop();
    assert(macro.size() == 2);
    assert(!recorder.IsRecording());

    std::cout << "pen macro recorder tests passed\n";
    return 0;
}
