package txteditor.core.text.md;

/** 行内样式片段；marker 为 true 的片段是标记符本身，渲染时应隐藏。 */
public final class MdSpan {
    public static final byte BOLD = 1, ITALIC = 2, STRIKE = 3, CODE = 4, LINK = 5;

    public final byte type;
    public final int start;
    public final int end;
    public final boolean marker;

    public MdSpan(byte type, int start, int end, boolean marker) {
        this.type = type;
        this.start = start;
        this.end = end;
        this.marker = marker;
    }
}
