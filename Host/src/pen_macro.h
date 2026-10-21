#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <vector>

#include <winsock2.h>

#include "protocol.h"

struct RecordedPenEvent {
    uint64_t timestampUs = 0;
    PenPayload payload{};
};

class PenMacroRecorder {
public:
    void Start() {
        m_recording = true;
        m_recorded.clear();
    }

    std::vector<RecordedPenEvent> Stop() {
        m_recording = false;
        return m_recorded;
    }

    void Cancel() {
        m_recording = false;
        m_recorded.clear();
    }

    bool IsRecording() const { return m_recording; }

    void Observe(uint64_t timestampUs, const PenPayload& payload) {
        const RecordedPenEvent event{ timestampUs, payload };
        if (m_recording && m_recorded.size() < kMaxMacroEvents) {
            m_recorded.push_back(event);
        }

        switch (payload.action) {
        case PEN_ACTION_DOWN:
            m_activeStroke.clear();
            m_activeStroke.push_back(event);
            break;
        case PEN_ACTION_MOVE:
            if (!m_activeStroke.empty() && m_activeStroke.size() < kMaxStrokeEvents) {
                m_activeStroke.push_back(event);
            }
            break;
        case PEN_ACTION_UP:
            if (!m_activeStroke.empty()) {
                m_activeStroke.push_back(event);
                m_lastStroke = m_activeStroke;
                if (IsTap(m_lastStroke)) m_lastTap = m_lastStroke;
                m_activeStroke.clear();
            }
            break;
        case PEN_ACTION_CANCEL:
            m_activeStroke.clear();
            break;
        default:
            break;
        }
    }

    const std::vector<RecordedPenEvent>& LastStroke() const { return m_lastStroke; }
    const std::vector<RecordedPenEvent>& LastTap() const { return m_lastTap; }

    static std::vector<RecordedPenEvent> Reanchor(
        const std::vector<RecordedPenEvent>& source,
        uint16_t anchorX,
        uint16_t anchorY
    ) {
        if (source.empty()) return {};
        const int baseX = ntohs(source.front().payload.x);
        const int baseY = ntohs(source.front().payload.y);
        std::vector<RecordedPenEvent> result = source;
        for (auto& event : result) {
            const int x = ntohs(event.payload.x) - baseX + anchorX;
            const int y = ntohs(event.payload.y) - baseY + anchorY;
            event.payload.x = htons(static_cast<uint16_t>((std::max)(0, (std::min)(65535, x))));
            event.payload.y = htons(static_cast<uint16_t>((std::max)(0, (std::min)(65535, y))));
        }
        return result;
    }

private:
    static bool IsTap(const std::vector<RecordedPenEvent>& events) {
        if (events.size() < 2) return false;
        const uint64_t duration = events.back().timestampUs >= events.front().timestampUs
            ? events.back().timestampUs - events.front().timestampUs
            : 0;
        const int x0 = ntohs(events.front().payload.x);
        const int y0 = ntohs(events.front().payload.y);
        int maxDistanceSquared = 0;
        for (const auto& event : events) {
            const int dx = ntohs(event.payload.x) - x0;
            const int dy = ntohs(event.payload.y) - y0;
            maxDistanceSquared = (std::max)(maxDistanceSquared, dx * dx + dy * dy);
        }
        return duration <= 500000 && maxDistanceSquared <= 1200 * 1200;
    }

    static constexpr size_t kMaxStrokeEvents = 8192;
    static constexpr size_t kMaxMacroEvents = 65536;

    bool m_recording = false;
    std::vector<RecordedPenEvent> m_recorded;
    std::vector<RecordedPenEvent> m_activeStroke;
    std::vector<RecordedPenEvent> m_lastStroke;
    std::vector<RecordedPenEvent> m_lastTap;
};
