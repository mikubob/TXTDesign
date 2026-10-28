package txteditor.core;

import java.util.Arrays;

/** UTF-16 storage; callers enforce Unicode boundaries. */
public final class GapBuffer implements CharSequence {
    private char[] data = new char[32];
    private int gapStart;
    private int gapEnd = data.length;

    public int length() { return data.length - (gapEnd - gapStart); }

    public char charAt(int index) {
        if (index < 0 || index >= length()) throw new IndexOutOfBoundsException("index: " + index);
        return data[index < gapStart ? index : index + gapEnd - gapStart];
    }

    public String subSequence(int start, int end) {
        checkRange(start, end);
        char[] result = new char[end - start];
        for (int i = start; i < end; i++) result[i - start] = charAt(i);
        return new String(result);
    }

    public void replace(int start, int end, String text) {
        checkRange(start, end);
        if (text == null) throw new NullPointerException("text");
        moveGap(start);
        gapEnd += end - start;
        if (text.length() > gapEnd - gapStart) {
            int capacity = Math.max(data.length * 2, length() + text.length() + 32);
            char[] grown = Arrays.copyOf(data, capacity);
            int tail = data.length - gapEnd;
            System.arraycopy(data, gapEnd, grown, capacity - tail, tail);
            gapEnd = capacity - tail;
            data = grown;
        }
        text.getChars(0, text.length(), data, gapStart);
        gapStart += text.length();
        checkInvariant();
    }

    private void moveGap(int position) {
        if (position < gapStart) {
            int count = gapStart - position;
            System.arraycopy(data, position, data, gapEnd - count, count);
            gapEnd -= count;
        } else {
            int count = position - gapStart;
            System.arraycopy(data, gapEnd, data, gapStart, count);
            gapEnd += count;
        }
        gapStart = position;
    }

    private void checkRange(int start, int end) {
        if (start < 0 || end < start || end > length()) throw new IndexOutOfBoundsException(start + ".." + end);
    }

    void checkInvariant() {
        if (gapStart < 0 || gapStart > gapEnd || gapEnd > data.length) throw new AssertionError("invalid gap");
    }

    @Override public String toString() { return subSequence(0, length()); }
}
