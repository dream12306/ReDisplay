#include <cassert>
#include <cstdint>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <vector>

#include "macro_library.h"

namespace {

void U16(std::vector<uint8_t>& bytes, uint16_t value) {
    bytes.push_back(static_cast<uint8_t>(value >> 8));
    bytes.push_back(static_cast<uint8_t>(value));
}

void U32(std::vector<uint8_t>& bytes, uint32_t value) {
    bytes.push_back(static_cast<uint8_t>(value >> 24));
    bytes.push_back(static_cast<uint8_t>(value >> 16));
    bytes.push_back(static_cast<uint8_t>(value >> 8));
    bytes.push_back(static_cast<uint8_t>(value));
}

void Label(std::vector<uint8_t>& bytes, const char* value) {
    char label[32]{};
    for (size_t index = 0; value[index] != '\0' && index < 31; ++index) label[index] = value[index];
    bytes.insert(bytes.end(), label, label + 32);
}

} // namespace

int main() {
    std::vector<uint8_t> bytes{'R', 'M', 'L', '1'};
    U16(bytes, 1);
    U16(bytes, 0);
    U32(bytes, 7);
    U32(bytes, 1);
    U32(bytes, 42);
    U16(bytes, 2);
    U16(bytes, 0);
    Label(bytes, "Copy and click");
    bytes.push_back(1);
    bytes.push_back(1);
    U16(bytes, 'C');
    U32(bytes, 0);
    U32(bytes, 0);
    bytes.push_back(2);
    bytes.push_back(0);
    U16(bytes, 0);
    U32(bytes, 1);
    U32(bytes, 0);

    const auto path = std::filesystem::temp_directory_path() / "redisplay-macro-library-test.bin";
    {
        std::ofstream output(path, std::ios::binary | std::ios::trunc);
        output.write(reinterpret_cast<const char*>(bytes.data()), static_cast<std::streamsize>(bytes.size()));
    }
    bool loaded = false;
    const MacroLibrary library = LoadMacroLibrary(path.string(), &loaded);
    assert(loaded);
    assert(library.revision == 7);
    assert(library.items.size() == 1);
    assert(library.items[0].id == 42);
    assert(library.items[0].steps.size() == 2);
    assert(library.Find(42) != nullptr);
    const auto catalog = BuildMacroCatalogPayload(library);
    assert(catalog.size() == 8 + 36);
    assert(catalog[2] == 0 && catalog[3] == 1);

    std::filesystem::remove(path);
    std::cout << "macro library tests passed\n";
    return 0;
}
