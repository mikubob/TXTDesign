package txteditor.core.text;

/** JSON：字符串、数字、字面量、标点；不校验结构合法性。 */
final class JsonLexer implements Lexer {
    @Override public byte initialState() { return 0; }

    @Override public byte lexLine(CharSequence text, int start, int end, byte state, TokenSink out) {
        boolean inString = state == 1;
        int i = start;
        if (inString) {
            int close = stringEnd(text, start, end);
            if (close < 0) { out.add(start, end, TokenType.STRING); return 1; }
            out.add(start, close + 1, TokenType.STRING);
            i = close + 1;
        }
        while (i < end) {
            char c = text.charAt(i);
            if (c == ' ' || c == '\t' || c == '\r') { i++; continue; }
            if (c == '"') {
                int close = stringEnd(text, i + 1, end);
                if (close < 0) { out.add(i, end, TokenType.STRING); return 1; }
                out.add(i, close + 1, TokenType.STRING);
                i = close + 1;
                continue;
            }
            if (Character.isDigit(c) || c == '-') {
                int close = i + 1;
                while (close < end && (Character.isDigit(text.charAt(close)) || ".eE+-".indexOf(text.charAt(close)) >= 0)) close++;
                out.add(i, close, TokenType.NUMBER);
                i = close;
                continue;
            }
            if (Character.isLetter(c)) {
                int close = i + 1;
                while (close < end && Character.isLetter(text.charAt(close))) close++;
                String word = text.subSequence(i, close).toString();
                out.add(i, close, "true".equals(word) || "false".equals(word) || "null".equals(word)
                        ? TokenType.LITERAL : TokenType.IDENTIFIER);
                i = close;
                continue;
            }
            out.add(i, i + 1, TokenType.OPERATOR);
            i++;
        }
        return 0;
    }

    private static int stringEnd(CharSequence text, int from, int end) {
        for (int i = from; i < end; i++) {
            char c = text.charAt(i);
            if (c == '\\') i++;
            else if (c == '"') return i;
        }
        return -1;
    }
}
