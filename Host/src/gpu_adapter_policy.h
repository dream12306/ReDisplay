#pragma once

#include <cstdint>

constexpr uint32_t NVIDIA_VENDOR_ID = 0x10DE;
constexpr uint32_t DXGI_SOFTWARE_ADAPTER_FLAG = 0x2;

inline bool IsNvencAdapterCandidate(uint32_t vendorId, uint32_t flags) {
    return vendorId == NVIDIA_VENDOR_ID
        && (flags & DXGI_SOFTWARE_ADAPTER_FLAG) == 0;
}
