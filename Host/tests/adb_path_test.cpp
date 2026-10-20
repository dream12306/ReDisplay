#include "adb.h"

#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <iostream>

namespace fs = std::filesystem;

int main() {
    std::error_code ec;
    const fs::path testDir = fs::temp_directory_path() / "redisplay_adb_path_test";
    const fs::path fakeAdb = testDir / "adb.exe";
    fs::create_directories(testDir, ec);
    if (ec) {
        std::cerr << "[FAIL] could not create test directory" << std::endl;
        return 1;
    }

    std::ofstream(fakeAdb, std::ios::binary).put('\0');
    _putenv_s("REDISPLAY_ADB", fakeAdb.string().c_str());

    AdbManager adb;
    const bool resolved = fs::equivalent(fs::path(adb.GetAdbPath()), fakeAdb, ec);

    _putenv_s("REDISPLAY_ADB", "");
    fs::remove_all(testDir, ec);

    if (!resolved) {
        std::cerr << "[FAIL] REDISPLAY_ADB was not resolved" << std::endl;
        return 1;
    }

    std::cout << "[PASS] ADB path resolution regression test" << std::endl;
    return 0;
}
