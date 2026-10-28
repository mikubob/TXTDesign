package txteditor.android;

import android.util.AtomicFile;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import txteditor.core.SessionSnapshot;

final class DraftStore {
    private DraftStore() { }

    static void write(File file, SessionSnapshot snapshot, String uri) throws IOException {
        AtomicFile atomic = new AtomicFile(file);
        FileOutputStream stream = null;
        try {
            stream = atomic.startWrite();
            DataOutputStream out = new DataOutputStream(stream);
            DraftFormat.write(out, snapshot, uri); out.flush(); atomic.finishWrite(stream);
        } catch (IOException | RuntimeException error) {
            if (stream != null) atomic.failWrite(stream);
            throw error;
        }
    }

    static DraftFormat.Decoded read(File file) throws IOException {
        try (DataInputStream in = new DataInputStream(new AtomicFile(file).openRead())) { return DraftFormat.read(in); }
    }

    static void delete(File file) { new AtomicFile(file).delete(); }
}
