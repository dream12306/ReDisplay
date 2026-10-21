#include <cassert>
#include <iostream>

#include "pen_playback_policy.h"

int main() {
    // Selecting a wheel action with the pen is followed by terminal and hover
    // packets. None of those passive packets may cut short the playback.
    assert(ClassifyLivePenDuringPlayback(PEN_ACTION_UP) ==
        PlaybackPenDisposition::Suppress);
    assert(ClassifyLivePenDuringPlayback(PEN_ACTION_CANCEL) ==
        PlaybackPenDisposition::Suppress);
    assert(ClassifyLivePenDuringPlayback(PEN_ACTION_HOVER_ENTER) ==
        PlaybackPenDisposition::Suppress);
    assert(ClassifyLivePenDuringPlayback(PEN_ACTION_HOVER_MOVE) ==
        PlaybackPenDisposition::Suppress);
    assert(ClassifyLivePenDuringPlayback(PEN_ACTION_HOVER_EXIT) ==
        PlaybackPenDisposition::Suppress);

    // A fresh contact is an explicit user takeover: stop playback, then pass
    // this down packet through so handwriting resumes immediately.
    assert(ClassifyLivePenDuringPlayback(PEN_ACTION_DOWN) ==
        PlaybackPenDisposition::CancelAndInject);

    // MOVE without a DOWN cannot safely take ownership of the synthetic pen.
    assert(ClassifyLivePenDuringPlayback(PEN_ACTION_MOVE) ==
        PlaybackPenDisposition::Suppress);

    std::cout << "pen playback policy tests passed\n";
    return 0;
}
