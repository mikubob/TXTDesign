package txteditor.core;

/** Literal, case-sensitive KMP over a logical character sequence. */
public final class Kmp {
    private Kmp() { }

    public static int indexOf(CharSequence text, String pattern, int from) {
        if (pattern.isEmpty()) throw new IllegalArgumentException("查找词不能为空");
        if (from < 0 || from > text.length()) throw new IndexOutOfBoundsException("from: " + from);
        int[] prefix = new int[pattern.length()];
        for (int i = 1, j = 0; i < pattern.length(); i++) {
            while (j > 0 && pattern.charAt(i) != pattern.charAt(j)) j = prefix[j - 1];
            if (pattern.charAt(i) == pattern.charAt(j)) j++;
            prefix[i] = j;
        }
        for (int i = from, j = 0; i < text.length(); i++) {
            while (j > 0 && text.charAt(i) != pattern.charAt(j)) j = prefix[j - 1];
            if (text.charAt(i) == pattern.charAt(j)) j++;
            if (j == pattern.length()) return i - j + 1;
        }
        return -1;
    }
}
