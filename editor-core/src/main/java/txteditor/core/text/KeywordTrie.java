package txteditor.core.text;

/**
 * 关键字判定用 Trie（左孩子右兄弟，四个平行数组）。标识符只扫一遍，
 * 扫完直接 match，不切片、不建 String、不哈希。
 */
public final class KeywordTrie {
    private int[] firstChild = new int[32];
    private int[] nextSibling = new int[32];
    private char[] label = new char[32];
    private byte[] kind = new byte[32];
    private int nodes = 1;

    public KeywordTrie() { firstChild[0] = -1; }

    public void add(String word, byte type) {
        int node = 0;
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            int child = firstChild[node];
            while (child != -1 && label[child] != c) child = nextSibling[child];
            if (child == -1) {
                child = newNode(c);
                nextSibling[child] = firstChild[node];
                firstChild[node] = child;
            }
            node = child;
        }
        kind[node] = type;
    }

    /** 返回 TokenType，非关键字返回 0（PLAIN）。 */
    public byte match(CharSequence text, int start, int end) {
        int node = 0;
        for (int i = start; i < end; i++) {
            char c = text.charAt(i);
            int child = firstChild[node];
            while (child != -1 && label[child] != c) child = nextSibling[child];
            if (child == -1) return 0;
            node = child;
        }
        return kind[node];
    }

    private int newNode(char c) {
        if (nodes == label.length) {
            int capacity = nodes * 2;
            firstChild = java.util.Arrays.copyOf(firstChild, capacity);
            nextSibling = java.util.Arrays.copyOf(nextSibling, capacity);
            label = java.util.Arrays.copyOf(label, capacity);
            kind = java.util.Arrays.copyOf(kind, capacity);
        }
        firstChild[nodes] = -1;
        nextSibling[nodes] = -1;
        label[nodes] = c;
        return nodes++;
    }
}
