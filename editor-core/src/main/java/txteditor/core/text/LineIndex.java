package txteditor.core.text;

/**
 * 行首偏移索引。starts[i] 是第 i 行首的 UTF-16 偏移。
 * 编辑时只平移受影响位置之后的部分，不重扫全文。
 */
public final class LineIndex {
    private int[] starts = new int[16];
    private int count = 1;
    private int length;

    public LineIndex() { starts[0] = 0; }

    public int count() { return count; }
    public int start(int line) { return starts[line]; }

    /** 行尾（不含换行）；最后一行到文本末尾。 */
    public int end(int line) {
        return line + 1 < count ? starts[line + 1] - 1 : length;
    }

    public void load(CharSequence text) {
        length = text.length();
        count = 1;
        if (starts.length < 16) starts = new int[16];
        starts[0] = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                if (count == starts.length) starts = java.util.Arrays.copyOf(starts, count * 2);
                starts[count++] = i + 1;
            }
        }
    }

    /** 最大 start <= offset 的行号。 */
    public int lineOf(int offset) {
        int low = 0, high = count - 1;
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            if (starts[middle] <= offset) low = middle; else high = middle - 1;
        }
        return low;
    }

    /**
     * 把索引更新到新文本，返回 {首受影响行, 保留行数, 新增行数, 删除行数}；
     * 后半段由调用方用它平移自己的并行数组。
     */
    public int[] update(CharSequence text, int changeStart, int oldEnd, int newEnd) {
        int first = lineOf(changeStart);
        int keep = first + 1;
        int removed = 0;
        while (keep + removed < count && starts[keep + removed] <= oldEnd) removed++;
        int delta = newEnd - oldEnd;
        for (int i = keep + removed; i < count; i++) starts[i] += delta;
        if (removed > 0) {
            System.arraycopy(starts, keep + removed, starts, keep, count - keep - removed);
            count -= removed;
        }
        int inserted = 0;
        int limit = Math.max(changeStart, newEnd);
        for (int i = changeStart; i < limit && i < text.length(); i++) {
            if (text.charAt(i) == '\n') insert(keep + inserted++, i + 1);
        }
        length = text.length();
        return new int[] {first, keep, inserted, removed};
    }

    private void insert(int at, int value) {
        if (count == starts.length) starts = java.util.Arrays.copyOf(starts, count * 2);
        System.arraycopy(starts, at, starts, at + 1, count - at);
        starts[at] = value;
        count++;
    }
}
