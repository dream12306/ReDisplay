#include "pen_input.h"

#include <ws2tcpip.h>

#include <algorithm>
#include <chrono>
#include <condition_variable>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <limits>
#include <mutex>
#include <vector>

#include "pen_macro.h"
#include "pen_input_math.h"
#include "protocol.h"

namespace {

bool RecvAll(SOCKET socket, void* data, size_t size) {
    char* cursor = static_cast<char*>(data);
    while (size > 0) {
        const int chunk = static_cast<int>((std::min)(
            size,
            static_cast<size_t>((std::numeric_limits<int>::max)())
        ));
        const int received = recv(socket, cursor, chunk, 0);
        if (received == SOCKET_ERROR || received == 0) return false;
        cursor += received;
        size -= static_cast<size_t>(received);
    }
    return true;
}

int16_t NetworkToSignedShort(int16_t value) {
    return static_cast<int16_t>(ntohs(static_cast<uint16_t>(value)));
}

uint64_t MonotonicTimeUs() {
    return static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::microseconds>(
        std::chrono::steady_clock::now().time_since_epoch()
    ).count());
}

bool IsValidCommand(const InputCommandPayload& command) {
    if (command.action > INPUT_ACTION_DOUBLE_CLICK) return false;
    if ((command.modifiers & ~(INPUT_MODIFIER_CTRL | INPUT_MODIFIER_SHIFT |
        INPUT_MODIFIER_ALT | INPUT_MODIFIER_WIN)) != 0) return false;
    const uint16_t keyCode = ntohs(command.key_code);
    return command.action != INPUT_ACTION_KEY_CHORD || (keyCode > 0 && keyCode <= 0xFF);
}

void SendKeyChord(uint8_t modifiers, uint16_t keyCode) {
    std::vector<INPUT> inputs;
    auto appendKey = [&inputs](WORD key, bool up) {
        INPUT input{};
        input.type = INPUT_KEYBOARD;
        input.ki.wVk = key;
        input.ki.dwFlags = up ? KEYEVENTF_KEYUP : 0;
        inputs.push_back(input);
    };
    const WORD modifierKeys[] = { VK_CONTROL, VK_SHIFT, VK_MENU, VK_LWIN };
    const uint8_t modifierFlags[] = {
        INPUT_MODIFIER_CTRL, INPUT_MODIFIER_SHIFT, INPUT_MODIFIER_ALT, INPUT_MODIFIER_WIN
    };
    for (size_t index = 0; index < 4; ++index) {
        if ((modifiers & modifierFlags[index]) != 0) appendKey(modifierKeys[index], false);
    }
    appendKey(static_cast<WORD>(keyCode), false);
    appendKey(static_cast<WORD>(keyCode), true);
    for (size_t index = 4; index-- > 0;) {
        if ((modifiers & modifierFlags[index]) != 0) appendKey(modifierKeys[index], true);
    }
    if (!inputs.empty()) SendInput(static_cast<UINT>(inputs.size()), inputs.data(), sizeof(INPUT));
}

void SendMouseClick(DWORD downFlag, DWORD upFlag, int clickCount) {
    std::vector<INPUT> inputs(static_cast<size_t>(clickCount) * 2);
    for (int click = 0; click < clickCount; ++click) {
        inputs[click * 2].type = INPUT_MOUSE;
        inputs[click * 2].mi.dwFlags = downFlag;
        inputs[click * 2 + 1].type = INPUT_MOUSE;
        inputs[click * 2 + 1].mi.dwFlags = upFlag;
    }
    SendInput(static_cast<UINT>(inputs.size()), inputs.data(), sizeof(INPUT));
}

struct MacroFileHeader {
    char magic[4];
    uint32_t version;
    uint32_t eventCount;
};

bool SaveMacro(const std::string& directory, const std::vector<RecordedPenEvent>& events) {
    if (directory.empty() || events.empty() || events.size() > 65536) return false;
    std::error_code error;
    std::filesystem::create_directories(std::filesystem::u8path(directory), error);
    if (error) return false;
    const auto path = std::filesystem::u8path(directory) / "latest.rdm";
    std::ofstream output(path, std::ios::binary | std::ios::trunc);
    if (!output) return false;
    const MacroFileHeader header{ {'R', 'D', 'M', '1'}, 1, static_cast<uint32_t>(events.size()) };
    output.write(reinterpret_cast<const char*>(&header), sizeof(header));
    output.write(
        reinterpret_cast<const char*>(events.data()),
        static_cast<std::streamsize>(events.size() * sizeof(RecordedPenEvent))
    );
    return output.good();
}

std::vector<RecordedPenEvent> LoadMacro(const std::string& directory) {
    if (directory.empty()) return {};
    const auto path = std::filesystem::u8path(directory) / "latest.rdm";
    std::ifstream input(path, std::ios::binary);
    MacroFileHeader header{};
    if (!input.read(reinterpret_cast<char*>(&header), sizeof(header)) ||
        std::memcmp(header.magic, "RDM1", 4) != 0 || header.version != 1 ||
        header.eventCount == 0 || header.eventCount > 65536) {
        return {};
    }
    std::vector<RecordedPenEvent> events(header.eventCount);
    if (!input.read(
        reinterpret_cast<char*>(events.data()),
        static_cast<std::streamsize>(events.size() * sizeof(RecordedPenEvent))
    )) return {};
    return events;
}

class SyntheticPenInjector {
public:
    SyntheticPenInjector(LONG left, LONG top, int width, int height)
        : m_left(left),
          m_top(top),
          m_width(width),
          m_height(height),
          m_virtualLeft(GetSystemMetrics(SM_XVIRTUALSCREEN)),
          m_virtualTop(GetSystemMetrics(SM_YVIRTUALSCREEN)) {}

    bool Initialize() {
        m_device = CreateSyntheticPointerDevice(PT_PEN, 1, POINTER_FEEDBACK_DEFAULT);
        if (m_device == nullptr) {
            std::cerr << "[Ink] CreateSyntheticPointerDevice failed: " << GetLastError() << std::endl;
            return false;
        }
        return true;
    }

    ~SyntheticPenInjector() {
        Reset();
        if (m_device != nullptr) {
            DestroySyntheticPointerDevice(m_device);
            m_device = nullptr;
        }
    }

    bool Inject(const PenPayload& wirePayload) {
        if (m_device == nullptr) return false;

        const uint8_t action = wirePayload.action;
        const uint16_t buttons = ntohs(wirePayload.buttons);
        const bool eraser = wirePayload.tool == PEN_TOOL_ERASER;
        const LONG x = MapNormalizedPenCoordinateForInjection(
            ntohs(wirePayload.x), m_left, m_width, m_virtualLeft
        );
        const LONG y = MapNormalizedPenCoordinateForInjection(
            ntohs(wirePayload.y), m_top, m_height, m_virtualTop
        );
        const INT32 tiltX = MapPenTiltHundredths(NetworkToSignedShort(wirePayload.tilt_x_hundredths));
        const INT32 tiltY = MapPenTiltHundredths(NetworkToSignedShort(wirePayload.tilt_y_hundredths));

        POINTER_FLAGS pointerFlags = POINTER_FLAG_NONE;
        bool inContact = false;
        bool leaveRange = false;

        switch (action) {
        case PEN_ACTION_HOVER_ENTER:
        case PEN_ACTION_HOVER_MOVE:
            pointerFlags = POINTER_FLAG_INRANGE | POINTER_FLAG_UPDATE;
            if (!m_inRange) pointerFlags |= POINTER_FLAG_NEW;
            break;
        case PEN_ACTION_DOWN:
            inContact = true;
            pointerFlags = POINTER_FLAG_INRANGE | POINTER_FLAG_INCONTACT | POINTER_FLAG_DOWN;
            if (!m_inRange) pointerFlags |= POINTER_FLAG_NEW;
            pointerFlags |= (buttons & PEN_BUTTON_BARREL) ? POINTER_FLAG_SECONDBUTTON : POINTER_FLAG_FIRSTBUTTON;
            break;
        case PEN_ACTION_MOVE:
            inContact = true;
            pointerFlags = POINTER_FLAG_INRANGE | POINTER_FLAG_INCONTACT | POINTER_FLAG_UPDATE;
            pointerFlags |= (buttons & PEN_BUTTON_BARREL) ? POINTER_FLAG_SECONDBUTTON : POINTER_FLAG_FIRSTBUTTON;
            if (!m_inRange) pointerFlags |= POINTER_FLAG_NEW;
            break;
        case PEN_ACTION_UP:
            pointerFlags = POINTER_FLAG_INRANGE | POINTER_FLAG_UP;
            break;
        case PEN_ACTION_HOVER_EXIT:
            pointerFlags = POINTER_FLAG_UPDATE;
            leaveRange = true;
            break;
        case PEN_ACTION_CANCEL:
            pointerFlags = (m_inContact ? POINTER_FLAG_UP : POINTER_FLAG_UPDATE) | POINTER_FLAG_CANCELED;
            leaveRange = true;
            break;
        default:
            return false;
        }

        POINTER_TYPE_INFO info{};
        info.type = PT_PEN;
        POINTER_PEN_INFO& pen = info.penInfo;
        pen.pointerInfo.pointerType = PT_PEN;
        pen.pointerInfo.pointerId = 1;
        pen.pointerInfo.pointerFlags = pointerFlags;
        pen.pointerInfo.ptPixelLocation = { x, y };
        pen.pointerInfo.ptPixelLocationRaw = { x, y };

        if (action == PEN_ACTION_DOWN) {
            pen.pointerInfo.ButtonChangeType = (buttons & PEN_BUTTON_BARREL)
                ? POINTER_CHANGE_SECONDBUTTON_DOWN
                : POINTER_CHANGE_FIRSTBUTTON_DOWN;
        } else if (action == PEN_ACTION_UP || action == PEN_ACTION_CANCEL) {
            pen.pointerInfo.ButtonChangeType = (m_buttons & PEN_BUTTON_BARREL)
                ? POINTER_CHANGE_SECONDBUTTON_UP
                : POINTER_CHANGE_FIRSTBUTTON_UP;
        }

        pen.penMask = PEN_MASK_PRESSURE | PEN_MASK_TILT_X | PEN_MASK_TILT_Y;
        pen.pressure = MapNormalizedPenPressure(ntohs(wirePayload.pressure), inContact);
        pen.tiltX = tiltX;
        pen.tiltY = tiltY;
        if (eraser) pen.penFlags |= PEN_FLAG_ERASER;
        if (buttons & PEN_BUTTON_BARREL) pen.penFlags |= PEN_FLAG_BARREL;

        if (!InjectSyntheticPointerInput(m_device, &info, 1)) {
            std::cerr << "[Ink] InjectSyntheticPointerInput failed: " << GetLastError() << std::endl;
            return false;
        }

        m_lastX = x;
        m_lastY = y;
        m_pressure = pen.pressure;
        m_tiltX = tiltX;
        m_tiltY = tiltY;
        m_eraser = eraser;
        m_buttons = buttons;
        m_inContact = inContact;
        m_inRange = !leaveRange;
        return true;
    }

    void Reset() {
        if (m_device == nullptr || (!m_inRange && !m_inContact)) return;

        POINTER_TYPE_INFO info{};
        info.type = PT_PEN;
        POINTER_PEN_INFO& pen = info.penInfo;
        pen.pointerInfo.pointerType = PT_PEN;
        pen.pointerInfo.pointerId = 1;
        pen.pointerInfo.ptPixelLocation = { m_lastX, m_lastY };
        pen.pointerInfo.ptPixelLocationRaw = { m_lastX, m_lastY };
        pen.pointerInfo.pointerFlags = (m_inContact ? POINTER_FLAG_UP : POINTER_FLAG_UPDATE) | POINTER_FLAG_CANCELED;
        pen.penMask = PEN_MASK_PRESSURE | PEN_MASK_TILT_X | PEN_MASK_TILT_Y;
        pen.pressure = 0;
        pen.tiltX = m_tiltX;
        pen.tiltY = m_tiltY;
        if (m_eraser) pen.penFlags |= PEN_FLAG_ERASER;
        InjectSyntheticPointerInput(m_device, &info, 1);

        m_inRange = false;
        m_inContact = false;
        m_buttons = 0;
        m_pressure = 0;
    }

private:
    HSYNTHETICPOINTERDEVICE m_device = nullptr;
    LONG m_left;
    LONG m_top;
    int m_width;
    int m_height;
    LONG m_virtualLeft;
    LONG m_virtualTop;
    LONG m_lastX = 0;
    LONG m_lastY = 0;
    UINT32 m_pressure = 0;
    INT32 m_tiltX = 0;
    INT32 m_tiltY = 0;
    uint16_t m_buttons = 0;
    bool m_inRange = false;
    bool m_inContact = false;
    bool m_eraser = false;
};

} // namespace

PenInputReceiver::PenInputReceiver(
    SOCKET socket,
    std::atomic<bool>& connectionAlive,
    LONG displayLeft,
    LONG displayTop,
    int displayWidth,
    int displayHeight,
    std::string macroDirectory
) : m_socket(socket),
    m_connectionAlive(connectionAlive),
    m_displayLeft(displayLeft),
    m_displayTop(displayTop),
    m_displayWidth(displayWidth),
    m_displayHeight(displayHeight),
    m_macroDirectory(std::move(macroDirectory)) {}

PenInputReceiver::~PenInputReceiver() {
    Stop();
}

void PenInputReceiver::Start() {
    if (m_running.exchange(true)) return;
    m_thread = std::thread(&PenInputReceiver::Run, this);
}

void PenInputReceiver::Stop() {
    if (!m_running.exchange(false)) {
        if (m_thread.joinable()) m_thread.join();
        return;
    }
    shutdown(m_socket, SD_RECEIVE);
    if (m_thread.joinable()) m_thread.join();
}

void PenInputReceiver::Run() {
    SetThreadPriority(GetCurrentThread(), THREAD_PRIORITY_ABOVE_NORMAL);
    SyntheticPenInjector injector(m_displayLeft, m_displayTop, m_displayWidth, m_displayHeight);
    const bool injectionAvailable = injector.Initialize();
    PenMacroRecorder recorder;
    std::vector<RecordedPenEvent> latestMacro = LoadMacro(m_macroDirectory);
    std::mutex injectorMutex;
    std::mutex playbackWaitMutex;
    std::condition_variable playbackWait;
    std::atomic<bool> cancelPlayback{ false };
    std::thread playbackThread;

    auto stopPlayback = [&]() {
        cancelPlayback = true;
        playbackWait.notify_all();
        if (playbackThread.joinable()) playbackThread.join();
        cancelPlayback = false;
    };

    auto startPlayback = [&](std::vector<RecordedPenEvent> events) {
        stopPlayback();
        if (!injectionAvailable || events.empty()) return;
        playbackThread = std::thread([&, events = std::move(events)]() {
            SetThreadPriority(GetCurrentThread(), THREAD_PRIORITY_ABOVE_NORMAL);
            uint64_t previousTimestamp = events.front().timestampUs;
            for (const auto& event : events) {
                if (cancelPlayback || !m_running || !m_connectionAlive) break;
                const uint64_t delayUs = event.timestampUs >= previousTimestamp
                    ? (std::min)(event.timestampUs - previousTimestamp, uint64_t{ 500000 })
                    : 0;
                previousTimestamp = event.timestampUs;
                if (delayUs > 0) {
                    std::unique_lock<std::mutex> waitLock(playbackWaitMutex);
                    if (playbackWait.wait_for(
                        waitLock,
                        std::chrono::microseconds(delayUs),
                        [&]() { return cancelPlayback.load() || !m_running || !m_connectionAlive; }
                    )) break;
                }
                std::lock_guard<std::mutex> lock(injectorMutex);
                if (injector.Inject(event.payload)) m_injectedEvents++;
            }
            std::lock_guard<std::mutex> lock(injectorMutex);
            injector.Reset();
        });
    };

    auto positionCursor = [&](uint16_t normalizedX, uint16_t normalizedY) {
        const LONG virtualLeft = GetSystemMetrics(SM_XVIRTUALSCREEN);
        const LONG virtualTop = GetSystemMetrics(SM_YVIRTUALSCREEN);
        const LONG x = MapNormalizedPenCoordinateForInjection(
            normalizedX, m_displayLeft, m_displayWidth, virtualLeft
        ) + virtualLeft;
        const LONG y = MapNormalizedPenCoordinateForInjection(
            normalizedY, m_displayTop, m_displayHeight, virtualTop
        ) + virtualTop;
        SetCursorPos(x, y);
    };

    while (m_running && m_connectionAlive) {
        PacketHeader header{};
        if (!RecvAll(m_socket, &header, sizeof(header))) {
            if (m_running) m_connectionAlive = false;
            break;
        }

        const uint32_t magic = ntohl(header.magic);
        const uint32_t payloadSize = ntohl(header.payload_size);
        if (magic != REDISPLAY_MAGIC || payloadSize > 64 * 1024) {
            std::cerr << "[Ink] Invalid reverse-channel packet" << std::endl;
            m_connectionAlive = false;
            break;
        }

        std::vector<uint8_t> payload(payloadSize);
        if (payloadSize > 0 && !RecvAll(m_socket, payload.data(), payloadSize)) {
            if (m_running) m_connectionAlive = false;
            break;
        }

        if (header.packet_type == PACKET_TYPE_PEN && payloadSize == sizeof(PenPayload)) {
            PenPayload pen{};
            std::memcpy(&pen, payload.data(), sizeof(pen));
            stopPlayback();
            recorder.Observe(MonotonicTimeUs(), pen);
            std::lock_guard<std::mutex> lock(injectorMutex);
            if (injectionAvailable && injector.Inject(pen)) {
                m_injectedEvents++;
            }
        } else if (header.packet_type == PACKET_TYPE_INPUT_COMMAND &&
            payloadSize == sizeof(InputCommandPayload)) {
            InputCommandPayload command{};
            std::memcpy(&command, payload.data(), sizeof(command));
            if (!IsValidCommand(command)) continue;
            const uint16_t anchorX = ntohs(command.x);
            const uint16_t anchorY = ntohs(command.y);
            switch (command.action) {
            case INPUT_ACTION_KEY_CHORD:
                SendKeyChord(command.modifiers, ntohs(command.key_code));
                break;
            case INPUT_ACTION_RIGHT_CLICK:
                positionCursor(anchorX, anchorY);
                SendMouseClick(MOUSEEVENTF_RIGHTDOWN, MOUSEEVENTF_RIGHTUP, 1);
                break;
            case INPUT_ACTION_LEFT_CLICK:
                positionCursor(anchorX, anchorY);
                SendMouseClick(MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP, 1);
                break;
            case INPUT_ACTION_DOUBLE_CLICK:
                positionCursor(anchorX, anchorY);
                SendMouseClick(MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP, 2);
                break;
            case INPUT_ACTION_RECORD_START:
                stopPlayback();
                recorder.Start();
                std::cout << "[Ink] Macro recording started" << std::endl;
                break;
            case INPUT_ACTION_RECORD_STOP: {
                auto recorded = recorder.Stop();
                if (!recorded.empty()) {
                    latestMacro = std::move(recorded);
                    SaveMacro(m_macroDirectory, latestMacro);
                    std::cout << "[Ink] Macro recording saved (" << latestMacro.size() << " events)" << std::endl;
                }
                break;
            }
            case INPUT_ACTION_RECORD_CANCEL:
                recorder.Cancel();
                break;
            case INPUT_ACTION_SAVE_LAST_STROKE:
                if (!recorder.LastStroke().empty()) {
                    latestMacro = recorder.LastStroke();
                    SaveMacro(m_macroDirectory, latestMacro);
                }
                break;
            case INPUT_ACTION_SAVE_LAST_TAP:
                if (!recorder.LastTap().empty()) {
                    latestMacro = recorder.LastTap();
                    SaveMacro(m_macroDirectory, latestMacro);
                }
                break;
            case INPUT_ACTION_REPLAY_LAST_STROKE:
                startPlayback(PenMacroRecorder::Reanchor(recorder.LastStroke(), anchorX, anchorY));
                break;
            case INPUT_ACTION_REPLAY_LAST_TAP:
                startPlayback(PenMacroRecorder::Reanchor(recorder.LastTap(), anchorX, anchorY));
                break;
            case INPUT_ACTION_REPLAY_LATEST:
                startPlayback(PenMacroRecorder::Reanchor(latestMacro, anchorX, anchorY));
                break;
            default:
                break;
            }
        }
    }

    stopPlayback();
    std::lock_guard<std::mutex> lock(injectorMutex);
    injector.Reset();
}
