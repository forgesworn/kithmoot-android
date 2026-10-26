package dev.forgesworn.kithmoot.media.attachment;

import android.os.SharedMemory;
import dev.forgesworn.kithmoot.media.attachment.IIsolatedAttachmentCallback;

interface IIsolatedAttachmentDecoder {
    const int ERROR_INVALID_INPUT = 1;
    const int ERROR_UNSUPPORTED_TYPE = 2;
    const int ERROR_LIMIT = 3;
    const int ERROR_CORRUPT = 4;
    const int ERROR_CANCELLED = 5;
    const int ERROR_SERVICE = 6;

    void decode(in SharedMemory plaintext, int plaintextSize, String mime, IIsolatedAttachmentCallback callback);
    void cancel();
}
