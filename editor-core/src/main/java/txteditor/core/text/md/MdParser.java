package txteditor.core.text.md;

import java.util.ArrayList;
import java.util.List;
import txteditor.core.text.LineIndex;

/**
 * Markdown 块级解析：单遍扫描 + 隐式容器（引用/列表用缩进和标记表示）。
 * 不做 CommonMark 全集，只覆盖首版声明支持的子集，未知结构按段落处理。
 */
public final class MdParser {
    private static final class Block {
        final byte type;
        final int start;
        int end;
        int content;
        int contentStop = -1;
        byte level, quote;
        String language;
        boolean checked;

        Block(byte type, int start, int end, int content) {
            this.type = type;
            this.start = start;
            this.end = end;
            this.content = content;
        }
    }

    public static MdDocument parse(String source) {
        String text = source == null ? "" : source;
        LineIndex lines = new LineIndex();
        lines.load(text);
        List<Block> blocks = new ArrayList<Block>();
        int n = lines.count();
        int i = 0;
        while (i < n) {
            int s = lines.start(i), e = lines.end(i);
            int p = skip(text, s, e);
            if (p >= e) { i++; continue; }
            if (fence(text, p, e) >= 3) { i = code(text, lines, i, n, p, e, blocks); continue; }
            char c = text.charAt(p);
            if (c == '#') {
                int level = run(text, p, e, '#');
                if (level <= 6 && (p + level >= e || text.charAt(p + level) == ' ')) {
                    Block block = new Block(MdDocument.HEADING, s, stop(text, e), skip(text, p + level, e));
                    block.level = (byte) level;
                    blocks.add(block);
                    i++;
                    continue;
                }
            }
            if (c == '>') {
                int depth = 0, q = p;
                while (q < e && text.charAt(q) == '>') { depth++; q++; }
                Block block = new Block(MdDocument.QUOTE, s, stop(text, e), skip(text, q, e));
                block.quote = (byte) depth;
                blocks.add(block);
                i++;
                continue;
            }
            int marker = listEnd(text, p, e);
            if (marker > 0) {
                Block block = new Block(MdDocument.LIST_ITEM, s, stop(text, e), marker);
                block.level = (byte) ((p - s) / 2);
                if (marker + 2 < e && text.charAt(marker) == '[' && text.charAt(marker + 2) == ']'
                        && (text.charAt(marker + 1) == ' ' || text.charAt(marker + 1) == 'x'
                        || text.charAt(marker + 1) == 'X')) {
                    block.checked = text.charAt(marker + 1) != ' ';
                    block.content = skip(text, marker + 3, e);
                }
                blocks.add(block);
                i++;
                continue;
            }
            if (rule(text, p, e)) {
                blocks.add(new Block(MdDocument.RULE, s, stop(text, e), s));
                i++;
                continue;
            }
            if (i + 1 < n && tableRow(text, s, e)
                    && tableDelimiter(text, lines.start(i + 1), lines.end(i + 1))) {
                i = table(text, lines, i, n, blocks);
                continue;
            }
            int j = i + 1;
            while (j < n) {
                int js = lines.start(j), je = lines.end(j);
                if (skip(text, js, je) >= je || blockStart(text, lines, j, n)) break;
                j++;
            }
            blocks.add(new Block(MdDocument.PARAGRAPH, s, stop(text, lines.end(j - 1)), s));
            i = j;
        }
        return freeze(text, blocks);
    }

    private static MdDocument freeze(String text, List<Block> blocks) {
        int size = blocks.size();
        byte[] type = new byte[size], level = new byte[size], quote = new byte[size];
        int[] start = new int[size], end = new int[size], content = new int[size], contentStop = new int[size];
        String[] language = new String[size];
        boolean[] checked = new boolean[size];
        for (int k = 0; k < size; k++) {
            Block b = blocks.get(k);
            type[k] = b.type;
            start[k] = b.start;
            end[k] = b.end;
            content[k] = b.content;
            contentStop[k] = b.contentStop;
            level[k] = b.level;
            quote[k] = b.quote;
            language[k] = b.language;
            checked[k] = b.checked;
        }
        return new MdDocument(text, type, start, end, content, contentStop, level, quote, language, checked);
    }

    private static int code(String text, LineIndex lines, int i, int n, int p, int e, List<Block> blocks) {
        char mark = text.charAt(p);
        String info = text.substring(Math.min(e, p + fence(text, p, e)), e).trim();
        Block block = new Block(MdDocument.CODE, lines.start(i), text.length(), Math.min(text.length(), stop(text, e)));
        if (!info.isEmpty()) block.language = info.split("\\s+")[0];
        int j = i + 1;
        while (j < n) {
            int js = lines.start(j), je = lines.end(j);
            int jp = skip(text, js, je);
            if (jp < je && fence(text, jp, je) >= 3 && text.charAt(jp) == mark) break;
            j++;
        }
        block.end = j < n ? Math.min(text.length(), stop(text, lines.end(j))) : text.length();
        block.contentStop = j < n ? lines.start(j) : text.length();
        blocks.add(block);
        return j < n ? j + 1 : n;
    }

    private static int table(String text, LineIndex lines, int i, int n, List<Block> blocks) {
        int j = i;
        while (j < n) {
            int s = lines.start(j), e = lines.end(j);
            if (skip(text, s, e) >= e || !tableRow(text, s, e)) break;
            j++;
        }
        blocks.add(new Block(MdDocument.TABLE, lines.start(i), stop(text, lines.end(j - 1)), lines.start(i)));
        return j;
    }

    private static boolean blockStart(String text, LineIndex lines, int line, int n) {
        int s = lines.start(line), e = lines.end(line);
        int p = skip(text, s, e);
        if (p >= e) return true;
        if (fence(text, p, e) >= 3 || listEnd(text, p, e) > 0 || rule(text, p, e)) return true;
        char c = text.charAt(p);
        if (c == '>') return true;
        if (c == '#') {
            int level = run(text, p, e, '#');
            return level <= 6 && (p + level >= e || text.charAt(p + level) == ' ');
        }
        return line + 1 < n && tableRow(text, s, e)
                && tableDelimiter(text, lines.start(line + 1), lines.end(line + 1));
    }

    private static boolean tableRow(String text, int start, int end) {
        for (int i = start; i < end; i++) if (text.charAt(i) == '|') return true;
        return false;
    }

    private static boolean tableDelimiter(String text, int start, int end) {
        boolean dash = false;
        for (int i = start; i < end; i++) {
            char c = text.charAt(i);
            if (c == '-') dash = true;
            else if (c != '|' && c != ':' && c != ' ' && c != '\t') return false;
        }
        return dash;
    }

    private static boolean rule(String text, int start, int end) {
        char c = text.charAt(start);
        if (c != '-' && c != '*' && c != '_') return false;
        int count = run(text, start, end, c);
        if (count < 3) return false;
        for (int i = start + count; i < end; i++) {
            if (text.charAt(i) != ' ' && text.charAt(i) != '\t') return false;
        }
        return true;
    }

    private static int listEnd(String text, int start, int end) {
        if (start >= end) return 0;
        char c = text.charAt(start);
        if ((c == '-' || c == '*' || c == '+') && start + 1 < end && text.charAt(start + 1) == ' ') {
            return skip(text, start + 2, end);
        }
        int i = start;
        while (i < end && Character.isDigit(text.charAt(i))) i++;
        if (i > start && i + 1 < end && (text.charAt(i) == '.' || text.charAt(i) == ')')
                && text.charAt(i + 1) == ' ') {
            return skip(text, i + 2, end);
        }
        return 0;
    }

    private static int fence(String text, int start, int end) {
        if (start >= end) return 0;
        char c = text.charAt(start);
        if (c != '`' && c != '~') return 0;
        int count = run(text, start, end, c);
        return count >= 3 ? count : 0;
    }

    private static int run(String text, int start, int end, char c) {
        int i = start;
        while (i < end && text.charAt(i) == c) i++;
        return i - start;
    }

    private static int skip(String text, int start, int end) {
        int i = start;
        while (i < end && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) i++;
        return i;
    }

    /** 末尾含换行，便于块之间连续切分；最后一行没有换行时不越界。 */
    private static int stop(String text, int lineEnd) {
        return lineEnd < text.length() ? lineEnd + 1 : lineEnd;
    }

    private MdParser() { }
}
