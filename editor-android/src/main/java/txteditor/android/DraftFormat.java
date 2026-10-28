package txteditor.android;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import txteditor.core.SessionSnapshot;
import txteditor.core.TextCodec;

/** 草稿字节格式：共享 SessionSnapshot 加安卓侧的 SAF URI。 */
final class DraftFormat {
    private DraftFormat() { }

    static final class Decoded {
        final SessionSnapshot snapshot;
        final String uri;
        Decoded(SessionSnapshot snapshot, String uri) { this.snapshot = snapshot; this.uri = uri; }
    }

    // 字段顺序沿用旧 Draft 格式，升级前写下的草稿仍能恢复。
    static void write(DataOutputStream out, SessionSnapshot snapshot, String uri) throws IOException {
        out.writeInt(1);
        writeString(out, snapshot.text); writeString(out, snapshot.savedText); writeString(out, uri);
        writeString(out, snapshot.fileName); writeString(out, snapshot.pattern); writeString(out, snapshot.replacement);
        out.writeInt(snapshot.selectionStart); out.writeInt(snapshot.selectionEnd); out.writeInt(snapshot.scroll);
        out.writeBoolean(snapshot.searchOpen);
    }

    static Decoded read(DataInputStream in) throws IOException {
        if (in.readInt() != 1) throw new IOException("草稿版本不支持");
        String text = readString(in); String saved = readString(in); String uri = readString(in);
        String name = readString(in); String pattern = readString(in); String replacement = readString(in);
        int start = in.readInt(); int end = in.readInt(); int scroll = in.readInt(); boolean search = in.readBoolean();
        return new Decoded(new SessionSnapshot(text, saved, name, start, end, scroll, pattern, replacement, search), uri);
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = TextCodec.encodeUtf8(value); out.writeInt(bytes.length); out.write(bytes);
    }

    private static String readString(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > 32 * 1024 * 1024) throw new IOException("草稿字段长度无效");
        byte[] bytes = new byte[length]; in.readFully(bytes); return TextCodec.decodeUtf8(bytes);
    }
}
