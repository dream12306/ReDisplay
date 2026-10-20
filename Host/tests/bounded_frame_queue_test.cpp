#include "bounded_frame_queue.h"

#include <cstdlib>
#include <iostream>

static void Require(bool condition, const char* message) {
    if (!condition) {
        std::cerr << "[FAIL] " << message << std::endl;
        std::exit(1);
    }
}

int main() {
    BoundedFrameQueue<int> queue(2);
    std::size_t dropped = 0;

    // Reproduce sustained network backpressure with a one-second GOP at 120 Hz.
    for (int frame = 0; frame < 120 * 60 * 10; ++frame) {
        const bool isKeyframe = (frame % 120) == 0;
        dropped += queue.Push(std::move(frame), isKeyframe);
        Require(queue.Size() <= 2, "queue exceeded its hard capacity");
    }
    Require(queue.Size() == 2, "queue should retain exactly its configured capacity");
    Require(dropped == (120u * 60u * 10u) - 2u, "drop accounting is incorrect");

    int first = -1;
    int second = -1;
    Require(queue.TryPop(first), "first retained frame is missing");
    Require(queue.TryPop(second), "second retained frame is missing");
    Require(first == 120 * 60 * 10 - 120, "latest keyframe was not retained");
    Require(second == 120 * 60 * 10 - 1, "latest delta frame was not retained");
    Require(queue.Empty(), "queue should be empty after two pops");

    // A fresh keyframe must discard stale frames immediately.
    int staleA = 1;
    int staleB = 2;
    int freshKeyframe = 3;
    queue.Push(std::move(staleA), false);
    queue.Push(std::move(staleB), false);
    Require(queue.Push(std::move(freshKeyframe), true) == 2, "fresh keyframe did not clear stale frames");
    Require(queue.Size() == 1, "keyframe replacement left stale entries behind");

    std::cout << "[PASS] bounded frame queue regression tests" << std::endl;
    return 0;
}
