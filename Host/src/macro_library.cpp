#include "macro_library.h"

#include <winsock2.h>

#include <algorithm>
#include <cstring>
#include <fstream>
#include <iterator>

#include "protocol.h"

namespace {

constexpr uint16_t kLibraryVersion = 1;
constexpr size_t kLibraryHeaderSize = 16;
constexpr size_t kItemHeaderSize = 40;
constexpr size_t kStepSize = 12;
constexpr size_t kMaxItems = 256;
constexpr size_t kMaxStepsPerItem = 128;

uint16_t ReadU16(const uint8_t* data) {
    return static_cast<uint16_t>((static_cast<uint16_t>(data[0]) << 8) | data[1]);
}

uint32_t ReadU32(const uint8_t* data) {
    return (static_cast<uint32_t>(data[0]) << 24) |
        (static_cast<uint32_t>(data[1]) << 16) |
        (static_cast<uint32_t>(data[2]) << 8) |
        static_cast<uint32_t>(data[3]);
}

void WriteU16(std::vector<uint8_t>& output, uint16_t value) {
    output.push_back(static_cast<uint8_t>(value >> 8));
    output.push_back(static_cast<uint8_t>(value));
}

void WriteU32(std::vector<uint8_t>& output, uint32_t value) {
    output.push_back(static_cast<uint8_t>(value >> 24));
    output.push_back(static_cast<uint8_t>(value >> 16));
    output.push_back(static_cast<uint8_t>(value >> 8));
    output.push_back(static_cast<uint8_t>(value));
}

bool IsValidStep(const MacroStep& step) {
    switch (step.type) {
    case MacroStepType::KeyChord:
        return step.keyCode > 0 && step.keyCode <= 0xFF &&
            (step.modifiers & ~(INPUT_MODIFIER_CTRL | INPUT_MODIFIER_SHIFT |
                INPUT_MODIFIER_ALT | INPUT_MODIFIER_WIN)) == 0;
    case MacroStepType::MouseClick:
        return step.parameter >= 1 && step.parameter <= 3;
    case MacroStepType::Delay:
        return step.parameter <= 10000;
    case MacroStepType::PenPlayback:
        return step.parameter != 0;
    default:
        return false;
    }
}

} // namespace

const MacroDefinition* MacroLibrary::Find(uint32_t id) const {
    const auto found = std::find_if(items.begin(), items.end(), [id](const auto& item) {
        return item.id == id;
    });
    return found == items.end() ? nullptr : &*found;
}

MacroLibrary LoadMacroLibrary(const std::string& path, bool* loadedFromFile) {
    if (loadedFromFile != nullptr) *loadedFromFile = false;
    MacroLibrary library;
    if (path.empty()) return library;

    std::ifstream input(path, std::ios::binary);
    if (!input) return library;
    const std::vector<uint8_t> bytes{
        std::istreambuf_iterator<char>(input), std::istreambuf_iterator<char>{}
    };
    if (bytes.size() < kLibraryHeaderSize || std::memcmp(bytes.data(), "RML1", 4) != 0 ||
        ReadU16(bytes.data() + 4) != kLibraryVersion) return library;

    const uint32_t revision = ReadU32(bytes.data() + 8);
    const uint32_t itemCount = ReadU32(bytes.data() + 12);
    if (itemCount > kMaxItems) return library;

    size_t offset = kLibraryHeaderSize;
    std::vector<MacroDefinition> parsed;
    parsed.reserve(itemCount);
    for (uint32_t index = 0; index < itemCount; ++index) {
        if (offset + kItemHeaderSize > bytes.size()) return library;
        MacroDefinition item;
        item.id = ReadU32(bytes.data() + offset);
        const uint16_t stepCount = ReadU16(bytes.data() + offset + 4);
        if (item.id == 0 || stepCount == 0 || stepCount > kMaxStepsPerItem ||
            std::memchr(bytes.data() + offset + 8, '\0', 32) == nullptr) return library;
        item.label.assign(reinterpret_cast<const char*>(bytes.data() + offset + 8));
        offset += kItemHeaderSize;

        item.steps.reserve(stepCount);
        for (uint16_t stepIndex = 0; stepIndex < stepCount; ++stepIndex) {
            if (offset + kStepSize > bytes.size()) return library;
            MacroStep step;
            step.type = static_cast<MacroStepType>(bytes[offset]);
            step.modifiers = bytes[offset + 1];
            step.keyCode = ReadU16(bytes.data() + offset + 2);
            step.parameter = ReadU32(bytes.data() + offset + 4);
            step.auxiliary = ReadU32(bytes.data() + offset + 8);
            if (!IsValidStep(step)) return library;
            item.steps.push_back(step);
            offset += kStepSize;
        }
        if (library.Find(item.id) != nullptr || std::any_of(parsed.begin(), parsed.end(), [&](const auto& existing) {
            return existing.id == item.id;
        })) return library;
        parsed.push_back(std::move(item));
    }
    if (offset != bytes.size()) return library;
    library.revision = revision == 0 ? 1 : revision;
    library.items = std::move(parsed);
    if (loadedFromFile != nullptr) *loadedFromFile = true;
    return library;
}

std::vector<uint8_t> BuildMacroCatalogPayload(const MacroLibrary& library) {
    const size_t count = (std::min)(library.items.size(), kMaxItems);
    std::vector<uint8_t> payload;
    payload.reserve(sizeof(MacroCatalogHeader) + count * sizeof(MacroCatalogItemPayload));
    WriteU16(payload, MACRO_CATALOG_SCHEMA_VERSION);
    WriteU16(payload, static_cast<uint16_t>(count));
    WriteU32(payload, library.revision);
    for (size_t index = 0; index < count; ++index) {
        WriteU32(payload, library.items[index].id);
        char label[32]{};
        const size_t labelSize = (std::min)(library.items[index].label.size(), sizeof(label) - 1);
        std::memcpy(label, library.items[index].label.data(), labelSize);
        payload.insert(payload.end(), label, label + sizeof(label));
    }
    return payload;
}
