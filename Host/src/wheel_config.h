#pragma once

#include <cstdint>
#include <string>
#include <vector>

std::vector<uint8_t> BuildDefaultWheelConfigPayload();
std::vector<uint8_t> LoadWheelConfigPayload(const std::string& path, bool* loadedFromFile = nullptr);
bool ValidateWheelConfigPayload(const std::vector<uint8_t>& payload);

