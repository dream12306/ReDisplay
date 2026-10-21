#pragma once

#include <cstdint>
#include <string>
#include <vector>

enum class MacroStepType : uint8_t {
    KeyChord = 1,
    MouseClick = 2,
    Delay = 3,
    PenPlayback = 4
};

struct MacroStep {
    MacroStepType type = MacroStepType::Delay;
    uint8_t modifiers = 0;
    uint16_t keyCode = 0;
    uint32_t parameter = 0;
    uint32_t auxiliary = 0;
};

struct MacroDefinition {
    uint32_t id = 0;
    std::string label;
    std::vector<MacroStep> steps;
};

struct MacroLibrary {
    uint32_t revision = 1;
    std::vector<MacroDefinition> items;

    const MacroDefinition* Find(uint32_t id) const;
};

MacroLibrary LoadMacroLibrary(const std::string& path, bool* loadedFromFile = nullptr);
std::vector<uint8_t> BuildMacroCatalogPayload(const MacroLibrary& library);
