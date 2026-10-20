#pragma once

#include <cstddef>
#include <deque>
#include <utility>

// A small, non-thread-safe queue used by the network sender. The caller owns
// synchronization. Keyframes are retained when possible, but the hard capacity
// is never exceeded.
template <typename T>
class BoundedFrameQueue {
public:
    explicit BoundedFrameQueue(std::size_t capacity)
        : m_capacity(capacity > 0 ? capacity : 1) {}

    std::size_t Push(T&& value, bool isKeyframe) {
        std::size_t dropped = 0;

        // A new keyframe supersedes every older queued frame. This both lowers
        // latency and guarantees that following delta frames have a fresh base.
        if (isKeyframe) {
            dropped = m_queue.size();
            m_queue.clear();
        }

        while (m_queue.size() >= m_capacity) {
            // Prefer dropping a delta frame so an already queued keyframe is
            // preserved. If all queued frames are keyframes, drop the oldest.
            auto dropIt = m_queue.begin();
            for (auto it = m_queue.begin(); it != m_queue.end(); ++it) {
                if (!it->isKeyframe) {
                    dropIt = it;
                    break;
                }
            }
            m_queue.erase(dropIt);
            ++dropped;
        }

        m_queue.push_back(Entry{ std::move(value), isKeyframe });
        return dropped;
    }

    bool TryPop(T& value) {
        if (m_queue.empty()) return false;
        value = std::move(m_queue.front().value);
        m_queue.pop_front();
        return true;
    }

    bool Empty() const { return m_queue.empty(); }
    std::size_t Size() const { return m_queue.size(); }

    std::size_t Clear() {
        const std::size_t count = m_queue.size();
        m_queue.clear();
        return count;
    }

private:
    struct Entry {
        T value;
        bool isKeyframe;
    };

    std::size_t m_capacity;
    std::deque<Entry> m_queue;
};
