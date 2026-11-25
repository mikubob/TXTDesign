package txteditor.core.text;

import java.util.Arrays;

/** 同一份词法快照里的括号位置与配对，供两端视图查询。 */
public final class BracketPairs {
    private int[] offsets = new int[32], partners = new int[32];
    private int count;
    private final String text;
    private final LineIndex lines = new LineIndex();

    BracketPairs(SyntaxHighlighter syntax) {
        text = syntax.text();
        lines.load(text);
        TokenSink tokens = new TokenSink();
        int[] stack = new int[32];
        int depth = 0;
        for (int line = 0; line < syntax.lineCount(); line++) {
            syntax.paint(line, line, tokens);
            int token = 0;
            for (int i = syntax.lineStart(line), end = syntax.lineEnd(line); i < end; i++) {
                char c = text.charAt(i);
                int kind = "([{)]}".indexOf(c);
                if (kind < 0) continue;
                while (token < tokens.size() && tokens.end(token) <= i) token++;
                if (token < tokens.size() && tokens.start(token) <= i && ignored(tokens.type(token))) continue;
                if (count == offsets.length) {
                    offsets = Arrays.copyOf(offsets, count * 2);
                    partners = Arrays.copyOf(partners, count * 2);
                }
                offsets[count] = i;
                partners[count] = -1;
                if (kind < 3) {
                    if (depth == stack.length) stack = Arrays.copyOf(stack, depth * 2);
                    stack[depth++] = count;
                } else if (depth > 0) {
                    int open = stack[depth - 1];
                    if ("([{".charAt(kind - 3) == text.charAt(offsets[open])) {
                        depth--;
                        partners[open] = i;
                        partners[count] = offsets[open];
                    } else depth = 0;
                }
                count++;
            }
        }
    }

    private static boolean ignored(byte type) {
        return type == TokenType.STRING || type == TokenType.CHAR || type == TokenType.COMMENT || type == TokenType.CODE;
    }

    /** 返回当前行打开或包围当前行的最内层多行代码块，中间至少有一行。 */
    public int[] fold(int caret) {
        int line = lines.lineOf(caret);
        int end = Arrays.binarySearch(offsets, 0, count, lines.end(line));
        for (int i = end >= 0 ? end - 1 : -end - 2; i >= 0; i--) {
            int start = offsets[i], stop = partners[i];
            char c = text.charAt(start);
            if ((c != '{' && c != '[') || stop < lines.start(line) || stop <= start) continue;
            int head = lines.lineOf(start), close = lines.lineOf(stop);
            if (close - head >= 2 && line <= close) return new int[] {head, close};
        }
        return new int[0];
    }

    public int[] near(int caret) {
        int at = Arrays.binarySearch(offsets, 0, count, caret - 1);
        if (at < 0 || partners[at] < 0) at = Arrays.binarySearch(offsets, 0, count, caret);
        return at < 0 || partners[at] < 0 ? new int[0] : new int[] {offsets[at], partners[at]};
    }
}
