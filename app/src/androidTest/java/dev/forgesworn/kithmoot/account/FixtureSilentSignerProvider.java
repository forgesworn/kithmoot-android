package dev.forgesworn.kithmoot.account;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

/**
 * A test-only NIP-55 content provider. It lives in the instrumentation APK, a different package from
 * KithMoot, so Nip55Signer's silent path crosses a real ContentResolver. It is Java because Android runs
 * it in the instrumentation package's own process, which has no Kotlin runtime. The payload chooses the
 * reply: "answer:TEXT" returns TEXT, "reject" refuses, "none" returns no cursor, and "hang" does not reply
 * until long after KithMoot's silent budget.
 */
public final class FixtureSilentSignerProvider extends ContentProvider {
    /** Twice SIGNER_SILENT_TIMEOUT_MS: a caller still waiting when the budget ends was held by the signer. */
    static final long HANG_MS = 40_000L;

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        String payload = projection != null && projection.length > 0 && projection[0] != null ? projection[0] : "";
        if (payload.startsWith("answer:")) {
            MatrixCursor cursor = new MatrixCursor(new String[] {"result"});
            cursor.addRow(new Object[] {payload.substring("answer:".length())});
            return cursor;
        }
        if (payload.equals("reject")) {
            MatrixCursor cursor = new MatrixCursor(new String[] {"rejected"});
            cursor.addRow(new Object[] {"true"});
            return cursor;
        }
        if (payload.equals("hang")) {
            try {
                Thread.sleep(HANG_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
