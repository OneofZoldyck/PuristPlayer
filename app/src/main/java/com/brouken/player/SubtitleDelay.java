package com.brouken.player;

/**
 * Purist: current subtitle delay, shared between the UI thread (panel) and the playback thread (renderer).
 * Positive = subtitles appear later, negative = earlier.
 */
final class SubtitleDelay {

    private volatile long delayUs;

    long getUs() {
        return delayUs;
    }

    long getMs() {
        return delayUs / 1000;
    }

    void setMs(long delayMs) {
        delayUs = delayMs * 1000;
    }
}
