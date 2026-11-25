package txteditor.core;

/** Literal, case-sensitive KMP over a logical character sequence. */
public final class Kmp {
    private Kmp() { }

    public static int indexOf(CharSequence text, String pattern, int from) {
        if (from < 0 || from > text.length()) throw new IndexOutOfBoundsException("from: " + from);
        int[] prefix = prefix(pattern);
        for (int i = from, j = 0; i < text.length(); i++) {
            while (j > 0 && text.charAt(i) != pattern.charAt(j)) j = prefix[j - 1];
            if (text.charAt(i) == pattern.charAt(j)) j++;
            if (j == pattern.length()) return i - j + 1;
        }
        return -1;
    }

    /** 在一遍扫描中找出光标左侧最近一项，同时记录回绕用的最后一项。 */
    static int previous(CharSequence text, String pattern, int before) {
        return scan(text, pattern, before, false);
    }

    static int count(CharSequence text, String pattern) {
        return scan(text, pattern, 0, true);
    }

    private static int scan(CharSequence text, String pattern, int before, boolean countOnly) {
        int[] prefix = prefix(pattern);
        int count = 0, previous = -1, last = -1;
        for (int i = 0, j = 0; i < text.length(); i++) {
            char current = text.charAt(i);
            while (j > 0 && current != pattern.charAt(j)) j = prefix[j - 1];
            if (current == pattern.charAt(j)) j++;
            if (j == pattern.length()) {
                last = i - j + 1;
                if (i < before) previous = last;
                count++;
                j = prefix[j - 1];
            }
        }
        return countOnly ? count : previous >= 0 ? previous : last;
    }

    static int replaceAll(CharSequence text, String pattern, String replacement, StringBuilder out) {
        int[] prefix = prefix(pattern);
        int count = 0, copied = 0;
        for (int i = 0, j = 0; i < text.length(); i++) {
            char current = text.charAt(i);
            while (j > 0 && current != pattern.charAt(j)) j = prefix[j - 1];
            if (current == pattern.charAt(j)) j++;
            if (j == pattern.length()) {
                if (out != null) out.append(text, copied, i - j + 1).append(replacement);
                copied = i + 1;
                count++;
                j = 0;
            }
        }
        if (out != null && count > 0) out.append(text, copied, text.length());
        return count;
    }

    private static int[] prefix(String pattern) {
        if (pattern.isEmpty()) throw new IllegalArgumentException("查找词不能为空");
        int[] prefix = new int[pattern.length()];
        for (int i = 1, j = 0; i < pattern.length(); i++) {
            while (j > 0 && pattern.charAt(i) != pattern.charAt(j)) j = prefix[j - 1];
            if (pattern.charAt(i) == pattern.charAt(j)) j++;
            prefix[i] = j;
        }
        return prefix;
    }
}
