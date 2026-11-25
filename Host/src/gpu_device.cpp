#include "gpu_device.h"

#include <dxgi1_6.h>
#include <vector>

#include "gpu_adapter_policy.h"

namespace {

std::string WideToUtf8(const wchar_t* value) {
    if (!value || !*value) return {};
    const int size = WideCharToMultiByte(CP_UTF8, 0, value, -1, nullptr, 0, nullptr, nullptr);
    if (size <= 1) return {};
    std::vector<char> buffer(static_cast<size_t>(size));
    WideCharToMultiByte(CP_UTF8, 0, value, -1, buffer.data(), size, nullptr, nullptr);
    return std::string(buffer.data());
}

bool TryCreateDevice(IDXGIAdapter1* adapter, NvencDeviceResult& result) {
    DXGI_ADAPTER_DESC1 desc{};
    if (FAILED(adapter->GetDesc1(&desc))
            || !IsNvencAdapterCandidate(desc.VendorId, desc.Flags)) {
        return false;
    }

    const D3D_FEATURE_LEVEL levels[] = {
        D3D_FEATURE_LEVEL_12_1,
        D3D_FEATURE_LEVEL_12_0,
        D3D_FEATURE_LEVEL_11_1,
        D3D_FEATURE_LEVEL_11_0
    };
    D3D_FEATURE_LEVEL actualLevel{};
    const HRESULT hr = D3D11CreateDevice(
        adapter,
        D3D_DRIVER_TYPE_UNKNOWN,
        nullptr,
        D3D11_CREATE_DEVICE_BGRA_SUPPORT,
        levels,
        static_cast<UINT>(std::size(levels)),
        D3D11_SDK_VERSION,
        &result.device,
        &actualLevel,
        &result.context
    );
    if (FAILED(hr)) return false;

    result.adapterName = WideToUtf8(desc.Description);
    return true;
}

}

NvencDeviceResult CreateNvencD3D11Device() {
    NvencDeviceResult result;
    IDXGIFactory1* factory1 = nullptr;
    if (FAILED(CreateDXGIFactory1(IID_PPV_ARGS(&factory1)))) {
        result.error = "Cannot enumerate graphics adapters";
        return result;
    }

    // Windows 10 1803+ can enumerate by GPU preference. This makes the choice
    // deterministic on hybrid-graphics laptops instead of silently creating an
    // Intel device and handing it to NVENC.
    IDXGIFactory6* factory6 = nullptr;
    if (SUCCEEDED(factory1->QueryInterface(IID_PPV_ARGS(&factory6)))) {
        for (UINT index = 0;; ++index) {
            IDXGIAdapter1* adapter = nullptr;
            const HRESULT hr = factory6->EnumAdapterByGpuPreference(
                index,
                DXGI_GPU_PREFERENCE_HIGH_PERFORMANCE,
                IID_PPV_ARGS(&adapter)
            );
            if (hr == DXGI_ERROR_NOT_FOUND) break;
            if (SUCCEEDED(hr) && adapter) {
                const bool created = TryCreateDevice(adapter, result);
                adapter->Release();
                if (created) break;
            }
        }
        factory6->Release();
    }

    // Compatibility fallback for older Windows builds / DXGI implementations.
    if (!result.device) {
        for (UINT index = 0;; ++index) {
            IDXGIAdapter1* adapter = nullptr;
            const HRESULT hr = factory1->EnumAdapters1(index, &adapter);
            if (hr == DXGI_ERROR_NOT_FOUND) break;
            if (SUCCEEDED(hr) && adapter) {
                const bool created = TryCreateDevice(adapter, result);
                adapter->Release();
                if (created) break;
            }
        }
    }

    factory1->Release();
    if (!result.device) {
        result.error = "NVIDIA GPU unavailable. Disable Eco/iGPU-only mode, enable Standard/Hybrid/Discrete GPU mode, then restart Windows.";
    }
    return result;
}
