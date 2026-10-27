package dev.forgesworn.kithmoot.media.recording;

import android.os.SharedMemory;
import android.view.Surface;
import dev.forgesworn.kithmoot.media.recording.IIsolatedRecordingPlayerCallback;

interface IIsolatedRecordingPlayer {
    const int ERROR_INVALID_INPUT = 1;
    const int ERROR_UNSUPPORTED_TYPE = 2;
    const int ERROR_CORRUPT = 3;
    const int ERROR_CANCELLED = 4;
    const int ERROR_SERVICE = 5;
    const int ERROR_TIMEOUT = 6;

    const int STATE_PREPARED = 1;
    const int STATE_STARTED = 2;
    const int STATE_PAUSED = 3;
    const int STATE_COMPLETED = 4;

    void open(in SharedMemory plaintext, long plaintextSize, String mime, in Surface surface, IIsolatedRecordingPlayerCallback callback);
    void setSurface(in Surface surface);
    void play();
    void pause();
    void seekTo(int positionMs);
    void close();
}
