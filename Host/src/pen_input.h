#pragma once

#include <winsock2.h>
#include <windows.h>

#include <atomic>
#include <string>
#include <thread>

class PenInputReceiver {
public:
    PenInputReceiver(
        SOCKET socket,
        std::atomic<bool>& connectionAlive,
        LONG displayLeft,
        LONG displayTop,
        int displayWidth,
        int displayHeight,
        std::string macroDirectory
    );
    ~PenInputReceiver();

    PenInputReceiver(const PenInputReceiver&) = delete;
    PenInputReceiver& operator=(const PenInputReceiver&) = delete;

    void Start();
    void Stop();
    uint64_t GetInjectedEventCount() const { return m_injectedEvents.load(); }

private:
    void Run();

    SOCKET m_socket;
    std::atomic<bool>& m_connectionAlive;
    LONG m_displayLeft;
    LONG m_displayTop;
    int m_displayWidth;
    int m_displayHeight;
    std::string m_macroDirectory;
    std::atomic<bool> m_running{ false };
    std::atomic<uint64_t> m_injectedEvents{ 0 };
    std::thread m_thread;
};
