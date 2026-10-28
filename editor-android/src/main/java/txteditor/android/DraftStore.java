package txteditor.android;

import android.util.AtomicFile;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

final class DraftStore {
    private DraftStore() { }

    static void write(File file, Draft draft) throws IOException {
        AtomicFile atomic = new AtomicFile(file);
        FileOutputStream stream = null;
        try {
            stream = atomic.startWrite();
            DataOutputStream out = new DataOutputStream(stream);
            draft.write(out); out.flush(); atomic.finishWrite(stream);
        } catch (IOException | RuntimeException error) {
            if (stream != null) atomic.failWrite(stream);
            throw error;
        }
    }

    static Draft read(File file) throws IOException {
        try (DataInputStream in = new DataInputStream(new AtomicFile(file).openRead())) { return Draft.read(in); }
    }

    static void delete(File file) { new AtomicFile(file).delete(); }
}
