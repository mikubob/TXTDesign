package txteditor.core;

/** 与平台无关的会话快照；序列化由各端自己负责。 */
public final class SessionSnapshot {
    public final String text;
    public final String savedText;
    public final String fileName;
    public final int selectionStart;
    public final int selectionEnd;
    public final int scroll;
    public final String pattern;
    public final String replacement;
    public final boolean searchOpen;

    public SessionSnapshot(String text, String savedText, String fileName, int selectionStart, int selectionEnd,
                           int scroll, String pattern, String replacement, boolean searchOpen) {
        this.text = text;
        this.savedText = savedText;
        this.fileName = fileName;
        this.selectionStart = selectionStart;
        this.selectionEnd = selectionEnd;
        this.scroll = scroll;
        this.pattern = pattern;
        this.replacement = replacement;
        this.searchOpen = searchOpen;
    }
}
