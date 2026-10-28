package txteditor.android;

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
import java.nio.charset.StandardCharsets;

/** Test APK only: deterministic readable, invalid-UTF8, unavailable and failed-write documents. */
public final class TestDocuments extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return "text/plain"; }
    @Override public Cursor query(Uri uri, String[] p, String s, String[] a, String order) {
        MatrixCursor cursor = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME});
        cursor.addRow(new Object[]{uri.getLastPathSegment() + ".txt"}); return cursor;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        try {
            String name = uri.getLastPathSegment();
            if (name.equals("missing")) throw new FileNotFoundException("测试 URI 已失效");
            if (name.equals("fail") && mode.contains("w")) {
                ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe(); pipe[0].close(); return pipe[1];
            }
            File file = new File(getContext().getCacheDir(), name + ".txt");
            if (!file.exists()) {
                try (FileOutputStream out = new FileOutputStream(file)) {
                    out.write(name.equals("invalid") ? new byte[]{(byte) 0xC3, 0x28} : "\uFEFF中文😀\r\n第二行\r".getBytes(StandardCharsets.UTF_8));
                }
            }
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode));
        } catch (Exception error) { throw new FileNotFoundException(error.toString()); }
    }
    @Override public Uri insert(Uri u, ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri u, String s, String[] a) { throw new UnsupportedOperationException(); }
}
