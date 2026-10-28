package txteditor.core.text;

/**
 * 高亮状态：行索引 + 每行行首状态（1 字节/行）。
 * token 不常驻，绘制时对可见行现算——省掉长文档每按键的整体平移与几万个小对象。
 */
public final class SyntaxHighlighter {
    private final LineIndex lines = new LineIndex();
    private final TokenSink scratch = new TokenSink();
    private LanguageId language = LanguageId.PLAIN;
    private Lexer lexer = Lexers.of(LanguageId.PLAIN);
    private byte[] states = new byte[16];
    private int count;
    private String text = "";

    public LanguageId language() { return language; }

    public void setLanguage(LanguageId id) {
        if (id == null) id = LanguageId.PLAIN;
        language = id;
        lexer = Lexers.of(id);
        if (count > 0) relex(0, count - 1);
    }

    public void load(String value) {
        text = value == null ? "" : value;
        lines.load(text);
        count = lines.count();
        ensure(count);
        relex(0, count - 1);
    }

    /** 同步到新文本；返回首个脏行，无变化返回 -1。 */
    public int update(String value, int changeStart, int oldEnd, int newEnd) {
        text = value == null ? "" : value;
        int[] change = lines.update(text, changeStart, oldEnd, newEnd);
        int first = change[0], keep = change[1], inserted = change[2], removed = change[3];
        int tail = count - (keep + removed);
        ensure(lines.count());
        if (tail > 0) System.arraycopy(states, keep + removed, states, keep + inserted, tail);
        for (int i = keep; i < keep + inserted; i++) states[i] = 0;
        count = lines.count();
        int lastChanged = lines.lineOf(Math.max(changeStart, newEnd));
        int last = relex(first, lastChanged);
        return last < first ? -1 : first;
    }

    /**
     * 现算 [fromLine, toLine] 的 token，偏移为全文绝对偏移。
     * 从该行缓存的行首状态起算，所以不需要重扫前面。
     */
    public void paint(int fromLine, int toLine, TokenSink out) {
        out.clear();
        if (count == 0) return;
        int from = Math.max(0, Math.min(fromLine, count - 1));
        int to = Math.max(from, Math.min(toLine, count - 1));
        byte state = states[from];
        for (int line = from; line <= to; line++) {
            state = lexer.lexLine(text, lines.start(line), lines.end(line), state, out);
        }
    }

    public int lineCount() { return count; }
    public int lineOf(int offset) { return lines.lineOf(offset); }
    public int lineStart(int line) { return lines.start(line); }
    public int lineEnd(int line) { return lines.end(line); }
    public byte stateAt(int line) { return states[line]; }
    public String text() { return text; }

    private int relex(int from, int lastChanged) {
        byte state = from == 0 ? lexer.initialState() : states[from];
        int last = from - 1;
        for (int line = from; line < count; line++) {
            if (line > lastChanged && states[line] == state) break;
            states[line] = state;
            scratch.clear();
            state = lexer.lexLine(text, lines.start(line), lines.end(line), state, scratch);
            last = line;
        }
        return last;
    }

    private void ensure(int size) {
        if (states.length >= size) return;
        states = java.util.Arrays.copyOf(states, Math.max(size, states.length * 2));
    }
}
