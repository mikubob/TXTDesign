package txteditor.core.text;

/** C 风格词法基类：C/C++/Java 只差关键字表，扫描规则共用一套。 */
class CStyleLexer implements Lexer {
    static final byte S_CODE = 0, S_COMMENT = 1, S_STRING = 2, S_CHAR = 3, S_TEXT_BLOCK = 4;

    private final KeywordTrie keywords = new KeywordTrie();
    private final boolean annotations;

    CStyleLexer(boolean annotations) {
        this.annotations = annotations;
        define();
    }

    /** 子类只登记关键字，不碰状态机。 */
    void define() { }

    final void keyword(String... words) { for (String w : words) keywords.add(w, TokenType.KEYWORD); }
    final void type(String... words) { for (String w : words) keywords.add(w, TokenType.TYPE); }
    final void literal(String... words) { for (String w : words) keywords.add(w, TokenType.LITERAL); }

    @Override public byte initialState() { return S_CODE; }

    @Override public byte lexLine(CharSequence text, int start, int end, byte state, TokenSink out) {
        int i = start;
        if (state == S_COMMENT) {
            int close = indexOf(text, start, end, "*/");
            if (close < 0) { out.add(start, end, TokenType.COMMENT); return S_COMMENT; }
            out.add(start, close + 2, TokenType.COMMENT);
            i = close + 2;
        } else if (state == S_STRING) {
            int close = quoteEnd(text, start, end, '"');
            if (close < 0) { out.add(start, end, TokenType.STRING); return S_STRING; }
            out.add(start, close + 1, TokenType.STRING);
            i = close + 1;
        } else if (state == S_TEXT_BLOCK) {
            int close = indexOf(text, start, end, "\"\"\"");
            if (close < 0) { out.add(start, end, TokenType.STRING); return S_TEXT_BLOCK; }
            out.add(start, close + 3, TokenType.STRING);
            i = close + 3;
        }
        while (i < end) {
            char c = text.charAt(i);
            if (c == ' ' || c == '\t' || c == '\r') { i++; continue; }
            if (c == '/' && i + 1 < end) {
                char next = text.charAt(i + 1);
                if (next == '/') { out.add(i, end, TokenType.COMMENT); return S_CODE; }
                if (next == '*') {
                    int close = indexOf(text, i + 2, end, "*/");
                    if (close < 0) { out.add(i, end, TokenType.COMMENT); return S_COMMENT; }
                    out.add(i, close + 2, TokenType.COMMENT);
                    i = close + 2;
                    continue;
                }
            }
            if (c == '"') {
                if (i + 2 < end && text.charAt(i + 1) == '"' && text.charAt(i + 2) == '"') {
                    int close = indexOf(text, i + 3, end, "\"\"\"");
                    if (close < 0) { out.add(i, end, TokenType.STRING); return S_TEXT_BLOCK; }
                    out.add(i, close + 3, TokenType.STRING);
                    i = close + 3;
                    continue;
                }
                int close = quoteEnd(text, i + 1, end, '"');
                if (close < 0) { out.add(i, end, TokenType.STRING); return S_STRING; }
                out.add(i, close + 1, TokenType.STRING);
                i = close + 1;
                continue;
            }
            if (c == '\'') {
                int close = quoteEnd(text, i + 1, end, '\'');
                if (close < 0) { out.add(i, end, TokenType.CHAR); return S_CHAR; }
                out.add(i, close + 1, TokenType.CHAR);
                i = close + 1;
                continue;
            }
            if (c == '#' && blankBefore(text, start, i)) { out.add(i, end, TokenType.PREPROCESSOR); return S_CODE; }
            if (Character.isDigit(c) || (c == '.' && i + 1 < end && Character.isDigit(text.charAt(i + 1)))) {
                int close = numberEnd(text, i, end);
                out.add(i, close, TokenType.NUMBER);
                i = close;
                continue;
            }
            if (c == '@' && annotations && i + 1 < end && identStart(text.charAt(i + 1))) {
                int close = i + 1;
                while (close < end && identPart(text.charAt(close))) close++;
                out.add(i, close, TokenType.ANNOTATION);
                i = close;
                continue;
            }
            if (identStart(c)) {
                int close = i + 1;
                while (close < end && identPart(text.charAt(close))) close++;
                byte kind = keywords.match(text, i, close);
                out.add(i, close, kind == 0 ? TokenType.IDENTIFIER : kind);
                i = close;
                continue;
            }
            out.add(i, i + 1, TokenType.OPERATOR);
            i++;
        }
        return S_CODE;
    }

    private static boolean identStart(char c) { return c == '_' || c == '$' || Character.isLetter(c); }
    private static boolean identPart(char c) { return identStart(c) || Character.isDigit(c); }

    private static boolean blankBefore(CharSequence text, int start, int at) {
        for (int i = start; i < at; i++) {
            char c = text.charAt(i);
            if (c != ' ' && c != '\t') return false;
        }
        return true;
    }

    private static int quoteEnd(CharSequence text, int from, int end, char quote) {
        for (int i = from; i < end; i++) {
            char c = text.charAt(i);
            if (c == '\\') i++;
            else if (c == quote) return i;
        }
        return -1;
    }

    private static int numberEnd(CharSequence text, int from, int end) {
        boolean hex = from + 1 < end && text.charAt(from) == '0'
                && (text.charAt(from + 1) == 'x' || text.charAt(from + 1) == 'X');
        int i = from;
        while (i < end) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '.' || c == '_') { i++; continue; }
            if ((c == '+' || c == '-') && i > from && !hex) {
                char previous = text.charAt(i - 1);
                if (previous == 'e' || previous == 'E') { i++; continue; }
            }
            break;
        }
        return i;
    }

    static int indexOf(CharSequence text, int from, int end, String needle) {
        int last = end - needle.length();
        for (int i = from; i <= last; i++) {
            int j = 0;
            while (j < needle.length() && text.charAt(i + j) == needle.charAt(j)) j++;
            if (j == needle.length()) return i;
        }
        return -1;
    }
}
