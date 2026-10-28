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
    /** LanguageId 名称；未知值按纯文本处理。 */
    public final String language;
    /** 0 = 源码，1 = Markdown 预览。 */
    public final int viewMode;

    public SessionSnapshot(String text, String savedText, String fileName, int selectionStart, int selectionEnd,
                           int scroll, String pattern, String replacement, boolean searchOpen) {
        this(text, savedText, fileName, selectionStart, selectionEnd, scroll, pattern, replacement, searchOpen,
                "PLAIN", 0);
    }

    public SessionSnapshot(String text, String savedText, String fileName, int selectionStart, int selectionEnd,
                           int scroll, String pattern, String replacement, boolean searchOpen,
                           String language, int viewMode) {
        this.text = text;
        this.savedText = savedText;
        this.fileName = fileName;
        this.selectionStart = selectionStart;
        this.selectionEnd = selectionEnd;
        this.scroll = scroll;
        this.pattern = pattern;
        this.replacement = replacement;
        this.searchOpen = searchOpen;
        this.language = language == null ? "PLAIN" : language;
        this.viewMode = viewMode;
    }
}
