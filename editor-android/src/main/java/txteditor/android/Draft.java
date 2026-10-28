package txteditor.android;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import txteditor.core.TextCodec;

final class Draft {
    String text = "", saved = "", uri = "", name = "未命名.txt", pattern = "", replacement = "";
    int start, end, scroll;
    boolean search;

    void write(DataOutputStream out) throws IOException {
        out.writeInt(1);
        writeString(out, text); writeString(out, saved); writeString(out, uri); writeString(out, name);
        writeString(out, pattern); writeString(out, replacement);
        out.writeInt(start); out.writeInt(end); out.writeInt(scroll); out.writeBoolean(search);
    }

    static Draft read(DataInputStream in) throws IOException {
        if (in.readInt() != 1) throw new IOException("草稿版本不支持");
        Draft draft = new Draft();
        draft.text = readString(in); draft.saved = readString(in); draft.uri = readString(in); draft.name = readString(in);
        draft.pattern = readString(in); draft.replacement = readString(in);
        draft.start = in.readInt(); draft.end = in.readInt(); draft.scroll = in.readInt(); draft.search = in.readBoolean();
        return draft;
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
