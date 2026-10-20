#include "stream_latency_policy.h"

#include <cstdlib>
#include <iostream>

static void Require(bool condition, const char* message) {
    if (!condition) {
        std::cerr << "[FAIL] " << message << std::endl;
        std::exit(1);
    }
}

int main() {
    IdrRefreshPolicy policy(1'000'000);

    Require(policy.ShouldForceIdr(0), "the first frame must be an IDR");
    policy.OnFrameEncoded(true, 0);

    Require(!policy.ShouldForceIdr(999'999), "IDR was forced before the wall-clock deadline");
    Require(policy.ShouldForceIdr(1'000'000), "wall-clock IDR deadline was ignored");
    policy.OnFrameEncoded(true, 1'000'000);

    policy.RequestIdr();
    Require(policy.ShouldForceIdr(1'000'001), "queue loss did not request an immediate IDR");
    policy.OnFrameEncoded(true, 1'000'001);
    Require(!policy.ShouldForceIdr(1'000'002), "IDR request was not cleared after recovery");

    policy.OnFrameEncoded(false, 2'500'000);
    Require(policy.ShouldForceIdr(2'500'000), "a failed forced-IDR attempt cleared the deadline");

    std::cout << "[PASS] stream latency policy regression tests" << std::endl;
    return 0;
}
