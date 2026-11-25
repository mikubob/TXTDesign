package txteditor.android;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import txteditor.core.SessionSnapshot;
import txteditor.core.TextCodec;

/** 草稿字节格式：共享 SessionSnapshot 加安卓侧的 SAF URI。 */
final class DraftFormat {
    private DraftFormat() { }

    /** 版本 3 追加原文件校验值；旧草稿仍可恢复。 */
    private static final int VERSION = 3;

    static final class Decoded {
        final SessionSnapshot snapshot;
        final String uri, fingerprint;
        Decoded(SessionSnapshot snapshot, String uri, String fingerprint) {
            this.snapshot = snapshot; this.uri = uri; this.fingerprint = fingerprint;
        }
    }

    // 字段顺序沿用旧 Draft 格式，升级前写下的草稿仍能恢复。
    static void write(DataOutputStream out, SessionSnapshot snapshot, String uri) throws IOException {
        write(out, snapshot, uri, "");
    }

    static void write(DataOutputStream out, SessionSnapshot snapshot, String uri, String fingerprint) throws IOException {
        out.writeInt(VERSION);
        writeString(out, snapshot.text); writeString(out, snapshot.savedText); writeString(out, uri);
        writeString(out, snapshot.fileName); writeString(out, snapshot.pattern); writeString(out, snapshot.replacement);
        out.writeInt(snapshot.selectionStart); out.writeInt(snapshot.selectionEnd); out.writeInt(snapshot.scroll);
        out.writeBoolean(snapshot.searchOpen);
        writeString(out, snapshot.language); out.writeInt(snapshot.viewMode);
        writeString(out, fingerprint);
    }

    static Decoded read(DataInputStream in) throws IOException {
        int version = in.readInt();
        if (version < 1 || version > VERSION) throw new IOException("草稿版本不支持");
        String text = readString(in); String saved = readString(in); String uri = readString(in);
        String name = readString(in); String pattern = readString(in); String replacement = readString(in);
        int start = in.readInt(); int end = in.readInt(); int scroll = in.readInt(); boolean search = in.readBoolean();
        String language = "PLAIN";
        int viewMode = 0;
        if (version >= 2) {
            language = readString(in);
            viewMode = in.readInt() == 1 ? 1 : 0;
        }
        return new Decoded(new SessionSnapshot(text, saved, name, start, end, scroll, pattern, replacement, search,
                language, viewMode), uri, version >= 3 ? readString(in) : "");
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = TextCodec.encodeUtf8(value); out.writeInt(bytes.length); out.write(bytes);
    }

    private static String readString(DataInputStream in) throws IOException {
        int length = in.readInt();
        // 输入来自本地文件或测试字节数组，剩余字节数就是字段可读上限。
        if (length < 0 || length > in.available()) throw new IOException("草稿字段长度无效");
        byte[] bytes = new byte[length]; in.readFully(bytes); return TextCodec.decodeUtf8(bytes);
    }
}
