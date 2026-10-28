package txteditor.core.text;

/**
 * 一段文本的 token 结果。平铺存放：每 token 3 个 int（起、止、保留）+ 1 个 byte 类型，
 * 不用对象，避免长文档每按键几万次分配。
 */
public final class TokenSink {
    private int[] spans = new int[3 * 32];
    private byte[] types = new byte[32];
    private int size;

    public void clear() { size = 0; }
    public int size() { return size; }
    public int start(int index) { return spans[index * 3]; }
    public int end(int index) { return spans[index * 3 + 1]; }
    public byte type(int index) { return types[index]; }

    public void add(int start, int end, byte type) {
        if (start >= end) return;
        if (size == types.length) grow();
        int at = size * 3;
        spans[at] = start;
        spans[at + 1] = end;
        types[size] = type;
        size++;
    }

    private void grow() {
        int capacity = Math.max(16, types.length * 2);
        int[] wider = new int[capacity * 3];
        System.arraycopy(spans, 0, wider, 0, size * 3);
        byte[] kinds = new byte[capacity];
        System.arraycopy(types, 0, kinds, 0, size);
        spans = wider;
        types = kinds;
    }
}
