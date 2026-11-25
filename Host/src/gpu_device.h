#pragma once

#include <d3d11.h>
#include <string>

struct NvencDeviceResult {
    ID3D11Device* device = nullptr;
    ID3D11DeviceContext* context = nullptr;
    std::string adapterName;
    std::string error;
};

NvencDeviceResult CreateNvencD3D11Device();
