package dev.forgesworn.kithmoot.media.recording;

oneway interface IIsolatedRecordingPlayerCallback {
    void onPrepared(int durationMs, int videoWidth, int videoHeight);
    void onState(int state, int positionMs);
    void onError(int errorCode);
}
