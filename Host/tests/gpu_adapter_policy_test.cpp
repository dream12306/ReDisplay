#include <cassert>
#include <iostream>

#include "gpu_adapter_policy.h"

int main() {
    assert(IsNvencAdapterCandidate(0x10DE, 0));
    assert(!IsNvencAdapterCandidate(0x8086, 0));
    assert(!IsNvencAdapterCandidate(0x1002, 0));
    assert(!IsNvencAdapterCandidate(0x10DE, DXGI_SOFTWARE_ADAPTER_FLAG));
    std::cout << "GPU adapter policy tests passed" << std::endl;
    return 0;
}
