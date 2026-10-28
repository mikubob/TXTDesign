package txteditor.core.text;

/**
 * Markdown 源码高亮：围栏、标题、引用、列表、行内标记。
 * 围栏内部只上"代码"色，不嵌套别的语言；预览里的代码块由解析器按语言另跑词法。
 */
final class MarkdownLexer implements Lexer {
    private static final byte TEXT = 0, FENCE_TICK = 1, FENCE_TILDE = 2;

    @Override public byte initialState() { return TEXT; }

    @Override public byte lexLine(CharSequence text, int start, int end, byte state, TokenSink out) {
        int first = start;
        while (first < end && (text.charAt(first) == ' ' || text.charAt(first) == '\t')) first++;
        if (state != TEXT) {
            char fence = state == FENCE_TICK ? '`' : '~';
            out.add(start, end, TokenType.CODE);
            return closes(text, first, end, fence) ? TEXT : state;
        }
        int fenceEnd = fenceRun(text, first, end);
        if (fenceEnd > 0) {
            out.add(start, end, TokenType.CODE);
            return text.charAt(first) == '`' ? FENCE_TICK : FENCE_TILDE;
        }
        int i = first;
        if (i < end && text.charAt(i) == '#') {
            int close = i;
            while (close < end && text.charAt(close) == '#') close++;
            if (close - i <= 6 && (close >= end || text.charAt(close) == ' ')) {
                out.add(i, close, TokenType.HEADING);
                inline(text, close, end, out);
                return TEXT;
            }
        }
        if (i < end && text.charAt(i) == '>') {
            int close = i;
            while (close < end && text.charAt(close) == '>') close++;
            out.add(i, close, TokenType.QUOTE);
            inline(text, close, end, out);
            return TEXT;
        }
        int marker = listMarker(text, i, end);
        if (marker > 0) {
            out.add(i, marker, TokenType.LIST_MARKER);
            inline(text, marker, end, out);
            return TEXT;
        }
        if (i < end && (text.charAt(i) == '-' || text.charAt(i) == '*' || text.charAt(i) == '_')
                && repeat(text, i, end, text.charAt(i)) >= 3 && blank(text, i + 3, end)) {
            out.add(i, end, TokenType.OPERATOR);
            return TEXT;
        }
        inline(text, start, end, out);
        return TEXT;
    }

    private static void inline(CharSequence text, int from, int end, TokenSink out) {
        int i = from;
        while (i < end) {
            char c = text.charAt(i);
            if (c == '\\') { i += 2; continue; }
            if (c == '`') {
                int close = i + 1;
                while (close < end && text.charAt(close) != '`') close++;
                if (close < end) { out.add(i, close + 1, TokenType.CODE); i = close + 1; continue; }
            }
            if (c == '*' || c == '_' || c == '~') {
                int run = repeat(text, i, end, c);
                out.add(i, i + run, run >= 2 ? TokenType.STRONG : TokenType.EMPHASIS);
                i += run;
                continue;
            }
            if (c == '[' || (c == '!' && i + 1 < end && text.charAt(i + 1) == '[')) {
                int open = c == '!' ? i + 1 : i;
                int bracket = find(text, open + 1, end, ']');
                if (bracket > 0 && bracket + 1 < end && text.charAt(bracket + 1) == '(') {
                    int close = find(text, bracket + 2, end, ')');
                    if (close > 0) { out.add(i, close + 1, TokenType.LINK); i = close + 1; continue; }
                }
            }
            i++;
        }
    }

    private static int listMarker(CharSequence text, int from, int end) {
        if (from >= end) return 0;
        char c = text.charAt(from);
        if ((c == '-' || c == '*' || c == '+') && from + 1 < end && text.charAt(from + 1) == ' ') return from + 2;
        int i = from;
        while (i < end && Character.isDigit(text.charAt(i))) i++;
        if (i > from && i + 1 < end && (text.charAt(i) == '.' || text.charAt(i) == ')')
                && text.charAt(i + 1) == ' ') return i + 2;
        return 0;
    }

    private static boolean closes(CharSequence text, int from, int end, char fence) {
        return repeat(text, from, end, fence) >= 3;
    }

    /** 行首连续 3 个以上围栏字符时返回其结束位置，否则 0。 */
    private static int fenceRun(CharSequence text, int from, int end) {
        if (from >= end) return 0;
        char c = text.charAt(from);
        if (c != '`' && c != '~') return 0;
        int run = repeat(text, from, end, c);
        return run >= 3 ? from + run : 0;
    }

    private static int repeat(CharSequence text, int from, int end, char c) {
        int i = from;
        while (i < end && text.charAt(i) == c) i++;
        return i - from;
    }

    private static boolean blank(CharSequence text, int from, int end) {
        for (int i = from; i < end; i++) {
            char c = text.charAt(i);
            if (c != ' ' && c != '\t' && c != '\r') return false;
        }
        return true;
    }

    private static int find(CharSequence text, int from, int end, char c) {
        for (int i = from; i < end; i++) if (text.charAt(i) == c) return i;
        return -1;
    }
}
