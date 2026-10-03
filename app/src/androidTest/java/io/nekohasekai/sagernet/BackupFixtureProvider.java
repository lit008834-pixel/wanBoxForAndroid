// @author 雾晚
package io.nekohasekai.sagernet;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Fictional test-only documents. The standalone provider process has no target-app
 * or Kotlin runtime class loader, so this fixture uses Android/Java classes only.
 * @author 雾晚
 */
public final class BackupFixtureProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return "application/octet-stream"; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        if ("query-fails".equals(uri.getLastPathSegment()))
            throw new IllegalArgumentException("fixture query failure");
        if ("named".equals(uri.getLastPathSegment())) {
            MatrixCursor cursor = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
            cursor.addRow(new Object[]{"fixture.JSON", null});
            return cursor;
        }
        MatrixCursor cursor = new MatrixCursor(new String[]{"unknown_column"});
        cursor.addRow(new Object[]{"opaque-document-id"});
        return cursor;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if ("denied".equals(uri.getLastPathSegment())) throw new SecurityException("fixture access denied");
        if (!"r".equals(mode)) throw new SecurityException("fixture is read only");
        File file = new File(getContext().getCacheDir(), "fictional-backup.json");
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write("{\"format\":\"wanbox.android.backup\",\"schemaVersion\":2,\"rules\":[]}".getBytes(StandardCharsets.UTF_8));
        } catch (IOException error) {
            throw new FileNotFoundException("fixture document unavailable");
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
