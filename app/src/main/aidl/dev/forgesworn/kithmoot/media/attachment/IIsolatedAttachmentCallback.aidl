package dev.forgesworn.kithmoot.media.attachment;

import android.os.SharedMemory;

interface IIsolatedAttachmentCallback {
    void onDecoded(int width, int height, in int[] frameOffsets, in int[] frameDelaysMs, in SharedMemory rgba);
    void onError(int errorCode);
}
