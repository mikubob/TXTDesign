package txteditor.core.text.md;

import java.util.ArrayList;
import java.util.List;

/** 解析结果：按源文本顺序排列的块，加上按需计算的行内样式。 */
public final class MdDocument {
    public static final byte PARAGRAPH = 1, HEADING = 2, CODE = 3, QUOTE = 4, LIST_ITEM = 5, RULE = 6, TABLE = 7;

    private final String text;
    private final byte[] type, level, quote;
    private final int[] start, end, content, contentStop;
    private final String[] language;
    private final boolean[] checked;
    private final int count;
    private MdSpan[][] inline;

    MdDocument(String text, byte[] type, int[] start, int[] end, int[] content, int[] contentStop, byte[] level,
               byte[] quote, String[] language, boolean[] checked) {
        this.text = text;
        this.type = type;
        this.start = start;
        this.end = end;
        this.content = content;
        this.contentStop = contentStop;
        this.level = level;
        this.quote = quote;
        this.language = language;
        this.checked = checked;
        this.count = type.length;
    }

    public int count() { return count; }
    public String text() { return text; }
    public byte type(int block) { return type[block]; }
    public int start(int block) { return start[block]; }
    public int end(int block) { return end[block]; }
    public int content(int block) { return content[block]; }
    /** 标题级别 / 列表缩进层级。 */
    public byte level(int block) { return level[block]; }
    public byte quote(int block) { return quote[block]; }
    public String language(int block) { return language[block]; }
    public boolean checked(int block) { return checked[block]; }

    public int contentEnd(int block) {
        int stop = contentStop[block] >= 0 ? contentStop[block] : end[block];
        return stop > content[block] && stop <= text.length() && text.charAt(stop - 1) == '\n' ? stop - 1 : stop;
    }

    /** 块正文（已跳过标题/列表/引用标记）。 */
    public String source(int block) {
        return text.substring(content[block], Math.min(contentEnd(block), text.length()));
    }

    /** 从行首到块尾的源码行，代码块与表格渲染用。 */
    public String raw(int block) {
        return text.substring(start[block], Math.min(contentEnd(block), text.length()));
    }

    public MdSpan[] inline(int block) {
        if (inline == null) inline = new MdSpan[count][];
        MdSpan[] cached = inline[block];
        if (cached != null) return cached;
        List<MdSpan> spans = new ArrayList<MdSpan>();
        if (type[block] != CODE) scan(content[block], Math.min(contentEnd(block), text.length()), spans);
        cached = spans.toArray(new MdSpan[0]);
        inline[block] = cached;
        return cached;
    }

    /** 只认首版支持的行内标记；嵌套强调按最外层处理。 */
    private void scan(int from, int to, List<MdSpan> out) {
        int i = from;
        while (i < to) {
            char c = text.charAt(i);
            if (c == '\\') { i += 2; continue; }
            if (c == '`') {
                int close = find(i + 1, to, '`');
                if (close > 0) {
                    out.add(new MdSpan(MdSpan.CODE, i, i + 1, true));
                    out.add(new MdSpan(MdSpan.CODE, i + 1, close, false));
                    out.add(new MdSpan(MdSpan.CODE, close, close + 1, true));
                    i = close + 1;
                    continue;
                }
            }
            if (c == '*' || c == '_' || c == '~') {
                int run = repeat(i, to, c);
                int width = run >= 2 ? 2 : 1;
                if (c != '~' || width == 2) {
                    int close = findRun(i + width, to, c, width);
                    if (close > 0) {
                        byte kind = c == '~' ? MdSpan.STRIKE : width == 2 ? MdSpan.BOLD : MdSpan.ITALIC;
                        out.add(new MdSpan(kind, i, i + width, true));
                        out.add(new MdSpan(kind, i + width, close, false));
                        out.add(new MdSpan(kind, close, close + width, true));
                        i = close + width;
                        continue;
                    }
                }
                i += Math.max(1, run);
                continue;
            }
            if (c == '[') {
                int bracket = find(i + 1, to, ']');
                if (bracket > 0 && bracket + 1 < to && text.charAt(bracket + 1) == '(') {
                    int close = find(bracket + 2, to, ')');
                    if (close > 0) {
                        out.add(new MdSpan(MdSpan.LINK, i, i + 1, true));
                        out.add(new MdSpan(MdSpan.LINK, i + 1, bracket, false));
                        out.add(new MdSpan(MdSpan.LINK, bracket, bracket + 2, true));
                        out.add(new MdSpan(MdSpan.LINK, bracket + 2, close, true));
                        out.add(new MdSpan(MdSpan.LINK, close, close + 1, true));
                        i = close + 1;
                        continue;
                    }
                }
            }
            i++;
        }
    }

    private int repeat(int from, int to, char c) {
        int i = from;
        while (i < to && text.charAt(i) == c) i++;
        return i - from;
    }

    private int find(int from, int to, char c) {
        for (int i = from; i < to; i++) if (text.charAt(i) == c) return i;
        return -1;
    }

    private int findRun(int from, int to, char c, int width) {
        for (int i = from; i + width <= to; i++) {
            if (text.charAt(i) != c) continue;
            int run = repeat(i, to, c);
            if (run >= width) return i;
        }
        return -1;
    }
}
