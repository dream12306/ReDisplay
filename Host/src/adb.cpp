#include "adb.h"
#include <windows.h>
#include <iostream>
#include <sstream>
#include <vector>
#include <filesystem>

namespace fs = std::filesystem;

static bool IsRegularFile(const fs::path& path) {
    std::error_code ec;
    return fs::is_regular_file(path, ec);
}

static fs::path GetExecutableDirectory() {
    std::vector<char> buffer(32768, 0);
    DWORD length = GetModuleFileNameA(NULL, buffer.data(), static_cast<DWORD>(buffer.size()));
    if (length == 0 || length >= buffer.size()) return {};
    return fs::path(std::string(buffer.data(), length)).parent_path();
}

static fs::path GetEnvironmentPath(const char* name) {
    DWORD required = GetEnvironmentVariableA(name, NULL, 0);
    if (required == 0) return {};
    std::vector<char> value(required, 0);
    if (GetEnvironmentVariableA(name, value.data(), required) == 0) return {};
    return fs::path(value.data());
}

AdbManager::AdbManager() {
    FindAdbPath();
}

bool AdbManager::FindAdbPath() {
    m_adbPath.clear();

    std::vector<fs::path> candidates;
    const fs::path explicitAdb = GetEnvironmentPath("REDISPLAY_ADB");
    if (!explicitAdb.empty()) candidates.push_back(explicitAdb);

    const fs::path exeDir = GetExecutableDirectory();
    if (!exeDir.empty()) {
        candidates.push_back(exeDir / "adb.exe");
        candidates.push_back(exeDir / "bin" / "adb.exe");
        candidates.push_back(exeDir / "resources" / "bin" / "adb.exe");
        candidates.push_back(exeDir.parent_path() / "resources" / "bin" / "adb.exe");

        // Development layouts often keep Android platform-tools beside the
        // repository. Walk a few ancestors without depending on the CWD.
        fs::path ancestor = exeDir;
        for (int i = 0; i < 4 && !ancestor.empty(); ++i) {
            candidates.push_back(ancestor / "platform-tools" / "adb.exe");
            candidates.push_back(ancestor / "resources" / "bin" / "adb.exe");
            fs::path parent = ancestor.parent_path();
            if (parent == ancestor) break;
            ancestor = parent;
        }
    }

    for (const char* sdkVariable : { "ANDROID_SDK_ROOT", "ANDROID_HOME" }) {
        const fs::path sdkRoot = GetEnvironmentPath(sdkVariable);
        if (!sdkRoot.empty()) candidates.push_back(sdkRoot / "platform-tools" / "adb.exe");
    }

    const fs::path localAppData = GetEnvironmentPath("LOCALAPPDATA");
    if (!localAppData.empty()) {
        candidates.push_back(localAppData / "Android" / "Sdk" / "platform-tools" / "adb.exe");
        candidates.push_back(localAppData / "ScreenExt" / "bin" / "adb.exe");
    }

    candidates.push_back("C:\\Program Files\\QtScrcpy-win-x64-v3.2.0\\adb.exe");
    candidates.push_back(fs::current_path() / "adb.exe");
    candidates.push_back(fs::current_path() / "resources" / "bin" / "adb.exe");

    for (const auto& candidate : candidates) {
        if (!IsRegularFile(candidate)) continue;
        std::error_code ec;
        const fs::path absolute = fs::absolute(candidate, ec);
        m_adbPath = (ec ? candidate : absolute).string();
        return true;
    }

    // Final fallback: system PATH.
    char buffer[MAX_PATH];
    if (SearchPathA(NULL, "adb.exe", NULL, MAX_PATH, buffer, NULL) > 0) {
        m_adbPath = buffer;
        return true;
    }
    return false;
}

std::string AdbManager::ExecuteCommand(const std::string& cmd) {
    if (m_adbPath.empty()) return "";

    std::string fullCmd = "\"" + m_adbPath + "\" " + cmd;
    std::string result;

    HANDLE hStdOutPipeRead = NULL;
    HANDLE hStdOutPipeWrite = NULL;

    SECURITY_ATTRIBUTES sa = { sizeof(SECURITY_ATTRIBUTES), NULL, TRUE };
    if (!CreatePipe(&hStdOutPipeRead, &hStdOutPipeWrite, &sa, 0)) return "";
    SetHandleInformation(hStdOutPipeRead, HANDLE_FLAG_INHERIT, 0);

    STARTUPINFOA si = { sizeof(STARTUPINFOA) };
    si.dwFlags |= STARTF_USESTDHANDLES | STARTF_USESHOWWINDOW;
    si.hStdOutput = hStdOutPipeWrite;
    si.hStdError = hStdOutPipeWrite;
    si.wShowWindow = SW_HIDE;

    PROCESS_INFORMATION pi = { 0 };
    std::vector<char> cmdVec(fullCmd.begin(), fullCmd.end());
    cmdVec.push_back(0);

    if (CreateProcessA(NULL, cmdVec.data(), NULL, NULL, TRUE, CREATE_NO_WINDOW, NULL, NULL, &si, &pi)) {
        CloseHandle(hStdOutPipeWrite);
        hStdOutPipeWrite = NULL;

        char buf[1024];
        DWORD bytesRead = 0;
        while (ReadFile(hStdOutPipeRead, buf, sizeof(buf) - 1, &bytesRead, NULL) && bytesRead > 0) {
            buf[bytesRead] = 0;
            result += buf;
        }

        WaitForSingleObject(pi.hProcess, 3000);
        CloseHandle(pi.hProcess);
        CloseHandle(pi.hThread);
    } else {
        CloseHandle(hStdOutPipeWrite);
    }

    CloseHandle(hStdOutPipeRead);
    return result;
}

std::vector<AdbDevice> AdbManager::GetDevices() {
    std::vector<AdbDevice> devices;
    std::string output = ExecuteCommand("devices -l");
    std::istringstream stream(output);
    std::string line;

    while (std::getline(stream, line)) {
        if (line.empty() || line.find("List of devices attached") != std::string::npos) continue;

        std::istringstream lineStream(line);
        std::string serial, state;
        lineStream >> serial >> state;

        if (state == "device") {
            AdbDevice dev;
            dev.serial = serial;
            std::string token;
            while (lineStream >> token) {
                if (token.rfind("model:", 0) == 0) {
                    dev.model = token.substr(6);
                } else if (token.rfind("product:", 0) == 0) {
                    dev.product = token.substr(8);
                }
            }
            if (dev.model.find("X810") != std::string::npos || 
                dev.product.find("gts9") != std::string::npos ||
                dev.model.find("Tab") != std::string::npos) {
                dev.is_tablet = true;
            }
            devices.push_back(dev);
        }
    }
    return devices;
}

bool AdbManager::SetupPortForward(const std::string& serial, int hostPort, int devicePort) {
    std::string cmd = "-s " + serial + " forward tcp:" + std::to_string(hostPort) + " tcp:" + std::to_string(devicePort);
    std::string out = ExecuteCommand(cmd);
    return true;
}

bool AdbManager::LaunchClientApp(const std::string& serial) {
    WakeDevice(serial);
    std::string cmd = "-s " + serial + " shell am start -n com.redisplay.client/.MainActivity";
    ExecuteCommand(cmd);
    return true;
}

bool AdbManager::WakeDevice(const std::string& serial) {
    std::string cmd = "-s " + serial + " shell input keyevent KEYCODE_WAKEUP";
    ExecuteCommand(cmd);
    return true;
}
