package txteditor.core.text;

/** XML / HTML：标签名、属性名、属性值、注释；不做配对校验。 */
final class XmlLexer implements Lexer {
    private static final byte TEXT = 0, COMMENT = 1, TAG = 2;

    @Override public byte initialState() { return TEXT; }

    @Override public byte lexLine(CharSequence text, int start, int end, byte state, TokenSink out) {
        int i = start;
        if (state == COMMENT) {
            int close = CStyleLexer.indexOf(text, start, end, "-->");
            if (close < 0) { out.add(start, end, TokenType.COMMENT); return COMMENT; }
            out.add(start, close + 3, TokenType.COMMENT);
            i = close + 3;
        } else if (state == TAG) {
            i = tag(text, i, end, out);
            if (i < 0) return TAG;
        }
        while (i < end) {
            char c = text.charAt(i);
            if (c != '<') { i++; continue; }
            if (i + 3 < end && text.charAt(i + 1) == '!' && text.charAt(i + 2) == '-'
                    && text.charAt(i + 3) == '-') {
                int close = CStyleLexer.indexOf(text, i + 4, end, "-->");
                if (close < 0) { out.add(i, end, TokenType.COMMENT); return COMMENT; }
                out.add(i, close + 3, TokenType.COMMENT);
                i = close + 3;
                continue;
            }
            int nameStart = i + 1;
            if (nameStart < end && text.charAt(nameStart) == '/') nameStart++;
            if (nameStart >= end || !nameStart(text.charAt(nameStart))) { i++; continue; }
            int nameEnd = nameStart;
            while (nameEnd < end && nameChar(text.charAt(nameEnd))) nameEnd++;
            out.add(i, nameStart, TokenType.OPERATOR);
            out.add(nameStart, nameEnd, TokenType.TAG);
            i = tag(text, nameEnd, end, out);
            if (i < 0) return TAG;
        }
        return TEXT;
    }

    /** 从上一次位置扫到 '>'；行尾仍未闭合返回 -1，交给下一行接着扫。 */
    private static int tag(CharSequence text, int from, int end, TokenSink out) {
        int i = from;
        while (i < end) {
            char c = text.charAt(i);
            if (c == '>') { out.add(i, i + 1, TokenType.OPERATOR); return i + 1; }
            if (c == '"' || c == '\'') {
                int close = i + 1;
                while (close < end && text.charAt(close) != c) close++;
                if (close >= end) { out.add(i, end, TokenType.STRING); return -1; }
                out.add(i, close + 1, TokenType.STRING);
                i = close + 1;
                continue;
            }
            if (nameStart(c)) {
                int close = i + 1;
                while (close < end && (nameChar(text.charAt(close)) || text.charAt(close) == ':')) close++;
                out.add(i, close, TokenType.ATTRIBUTE);
                i = close;
                continue;
            }
            i++;
        }
        return -1;
    }

    private static boolean nameStart(char c) { return Character.isLetter(c) || c == '_' || c == ':'; }
    private static boolean nameChar(char c) { return Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.'; }
}
