#pragma once

#include <atomic>
#include <cstdint>

// Keeps decoder recovery bounded by wall-clock time. Desktop capture is
// event-driven, so a frame-count GOP can become many seconds long while the
// screen is mostly static.
class IdrRefreshPolicy {
public:
    explicit IdrRefreshPolicy(uint64_t maxIntervalUs = 1'000'000)
        : m_maxIntervalUs(maxIntervalUs) {}

    void Reset() {
        m_forceNext.store(true, std::memory_order_release);
        m_hasEncodedIdr = false;
        m_lastIdrUs = 0;
    }

    void RequestIdr() {
        m_forceNext.store(true, std::memory_order_release);
    }

    bool ShouldForceIdr(uint64_t nowUs) const {
        if (!m_hasEncodedIdr || m_forceNext.load(std::memory_order_acquire)) {
            return true;
        }
        return nowUs >= m_lastIdrUs && nowUs - m_lastIdrUs >= m_maxIntervalUs;
    }

    void OnFrameEncoded(bool isIdr, uint64_t nowUs) {
        if (!isIdr) return;
        m_lastIdrUs = nowUs;
        m_hasEncodedIdr = true;
        m_forceNext.store(false, std::memory_order_release);
    }

private:
    const uint64_t m_maxIntervalUs;
    std::atomic<bool> m_forceNext{ true };
    bool m_hasEncodedIdr = false;
    uint64_t m_lastIdrUs = 0;
};
