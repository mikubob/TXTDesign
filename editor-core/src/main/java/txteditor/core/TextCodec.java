package txteditor.core;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Shared encoding rules without filesystem or platform dependencies. */
public final class TextCodec {
    private TextCodec() { }

    public static final int FILE_LIMIT_BYTES = 16 * 1024 * 1024;
    public static final int STYLING_LIMIT_BYTES = 2 * 1024 * 1024;

    /** 用于已验证且换行已统一的编辑快照，超过预算即停止扫描。 */
    public static boolean exceedsUtf8Limit(CharSequence text, int limit) {
        if (text.length() > limit) return true;
        int bytes = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isHighSurrogate(ch)) { bytes += 4; i++; }
            else bytes += ch <= 0x7f ? 1 : ch <= 0x7ff ? 2 : 3;
            if (bytes > limit) return true;
        }
        return false;
    }

    public static String normalize(String text) {
        validateUnicode(text);
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    public static String decodeUtf8(byte[] bytes) throws CharacterCodingException {
        String text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        return normalize(text);
    }

    public static byte[] encodeUtf8(String text) throws CharacterCodingException {
        ByteBuffer bytes = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(normalize(text)));
        byte[] result = new byte[bytes.remaining()];
        bytes.get(result);
        return result;
    }

    public static void validateUnicode(String text) {
        if (text == null) throw new NullPointerException("text");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i)))
                    throw new IllegalArgumentException("文本包含不完整的 UTF-16 代理项");
            } else if (Character.isLowSurrogate(c)) {
                throw new IllegalArgumentException("文本包含不完整的 UTF-16 代理项");
            }
        }
    }
}
