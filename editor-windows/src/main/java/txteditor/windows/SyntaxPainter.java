package txteditor.windows;

import txteditor.core.text.LanguageId;
import txteditor.core.text.SyntaxHighlighter;
import txteditor.core.text.TokenSink;
import txteditor.core.text.TokenType;
import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import javax.swing.text.Element;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.Color;
import java.awt.Point;
import java.awt.Rectangle;

/**
 * 源码上色：只给可视行（滚动时向上下各预取 200 行）贴字符属性。
 * 行状态缓存与增量重扫都在核心 SyntaxHighlighter，这里只做 TokenType → AttributeSet 的映射。
 *
 * shortcut: 可视区之外的旧颜色要等滚动到那里才刷新（编辑改变了远处行的 token 时）。
 */
final class SyntaxPainter {
    private static final int PREFETCH = 200;
    /** 超过 2 MiB 不上色（设计 §11 的降级阈值）：每次按键要复制整篇文本，再大就只是负担。 */
    private static final int LIMIT = 2 << 20;

    private final JTextPane pane;
    private final SyntaxHighlighter highlighter = new SyntaxHighlighter();
    private final TokenSink sink = new TokenSink();
    private final SimpleAttributeSet[] styles = new SimpleAttributeSet[TokenType.COUNT];
    private boolean dark;
    private boolean enabled = true;
    private boolean dirty = true;
    private boolean queued;
    private int paintedFrom = -1, paintedTo = -1;
    private int editStart, editOldEnd, editNewEnd;
    private boolean pendingEdit, pendingReload;

    SyntaxPainter(JTextPane pane, boolean dark) {
        this.pane = pane;
        this.dark = dark;
        palette();
    }

    void setLanguage(LanguageId id) {
        if (id == highlighter.language()) return;
        highlighter.setLanguage(id);
        dirty = true;
        paintedFrom = paintedTo = -1;
        schedule();
    }

    /** 整篇换文本（打开文件、撤销重做、会话恢复）。 */
    void load(String text) {
        enabled = text.length() <= LIMIT;
        highlighter.load(enabled ? text : "");
        pendingEdit = pendingReload = false;
        dirty = true;
        paintedFrom = paintedTo = -1;
        schedule();
    }

    /**
     * 文档改了 [start, oldEnd) → [start, newEnd)。真正取文本与重扫放到 EDT 尾部，
     * 避免在 DocumentFilter 写锁里复制整篇文本、也避免在编辑事件中再发通知。
     */
    void edited(int start, int oldEnd, int newEnd) {
        if (!enabled) return;
        if (pendingEdit || pendingReload) pendingReload = true;
        else {
            pendingEdit = true;
            editStart = start;
            editOldEnd = oldEnd;
            editNewEnd = newEnd;
        }
        dirty = true;
        schedule();
    }

    void applyTheme(boolean nextDark) {
        if (dark == nextDark) return;
        dark = nextDark;
        palette();
        dirty = true;
        paintedFrom = paintedTo = -1;
        schedule();
    }

    /** 只重画可视行，用于滚动、缩放与窗口变化。 */
    void schedule() {
        if (queued) return;
        queued = true;
        SwingUtilities.invokeLater(() -> {
            queued = false;
            paint();
        });
    }

    private void paint() {
        if (!enabled || !pane.isDisplayable()) return;
        if (composing()) return; // 组合结束后 flushComposition → refresh 会重新排一次
        if (pendingEdit || pendingReload) applyPending();
        if (!enabled) return;
        int count = highlighter.lineCount();
        if (count <= 0) { paintedFrom = paintedTo = -1; return; }
        int[] visible = visibleLines(count);
        int[] range;
        if (paintedFrom < 0) {
            range = paintRange(count, visible[0], visible[1], true);
        } else if (dirty) {
            range = paintRange(count, visible[0], visible[1], false);
        } else {
            if (visible[0] >= paintedFrom && visible[1] <= paintedTo) return;
            range = paintRange(count, visible[0], visible[1], true);
        }
        apply(range[0], range[1]);
        paintedFrom = range[0];
        paintedTo = range[1];
        dirty = false;
    }

    /** JTextPane.getText() 会把 \n 换成平台换行符；同步、高亮与断言都必须用文档原文。 */
    static String text(JTextPane pane) {
        javax.swing.text.Document document = pane.getDocument();
        try {
            return document.getText(0, document.getLength());
        } catch (javax.swing.text.BadLocationException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private void applyPending() {
        boolean reload = pendingReload;
        pendingEdit = pendingReload = false;
        String text = text(pane);
        if (text.length() > LIMIT) { enabled = false; return; }
        if (reload) { // 多次编辑合并时偏移不再连续，只能整篇重扫
            highlighter.load(text);
            paintedFrom = paintedTo = -1;
        } else {
            highlighter.update(text, editStart, editOldEnd, editNewEnd);
        }
    }

    /** 可视行窗口：脏行只刷可视区，滚动/首帧向上下各扩 PREFETCH 行。 */
    static int[] paintRange(int count, int first, int last, boolean prefetch) {
        int from = prefetch ? Math.max(0, first - PREFETCH) : first;
        int to = prefetch ? Math.min(count - 1, last + PREFETCH) : last;
        from = Math.max(0, Math.min(from, count - 1));
        to = Math.max(from, Math.min(to, count - 1));
        return new int[] {from, to};
    }

    private void apply(int from, int to) {
        StyledDocument document = pane.getStyledDocument();
        int length = document.getLength();
        highlighter.paint(from, to, sink);
        SimpleAttributeSet plain = new SimpleAttributeSet(); // 空集：清掉上一轮留下的颜色
        for (int line = from; line <= to; line++) {
            int start = highlighter.lineStart(line), end = highlighter.lineEnd(line);
            if (start >= length) break;
            if (end > length) end = length;
            if (start < end) document.setCharacterAttributes(start, end - start, plain, true);
        }
        for (int i = 0; i < sink.size(); i++) {
            int start = sink.start(i), end = Math.min(sink.end(i), length);
            SimpleAttributeSet style = styles[sink.type(i)];
            if (style != null && start < end && start < length) document.setCharacterAttributes(start, end - start, style, true);
        }
    }

    private int[] visibleLines(int count) {
        Rectangle rect = pane.getVisibleRect();
        if (rect.height <= 0 || rect.width <= 0) return new int[] {0, Math.min(count - 1, PREFETCH)};
        int text = highlighter.text().length();
        int top = clamp(pane.viewToModel(new Point(rect.x + 1, rect.y + 1)), text);
        int bottom = clamp(pane.viewToModel(new Point(rect.x + 1, rect.y + rect.height - 1)), text);
        int first = highlighter.lineOf(top), last = highlighter.lineOf(bottom);
        if (last < first) last = first;
        return new int[] {first, last};
    }

    private static int clamp(int offset, int length) {
        return offset < 0 ? 0 : Math.min(offset, length);
    }

    /** 组合输入期间不贴色，否则会覆盖 StyleConstants.ComposedTextAttribute 的下划线。 */
    private boolean composing() {
        try {
            int caret = Math.min(pane.getCaretPosition(), pane.getDocument().getLength());
            Element run = pane.getStyledDocument().getCharacterElement(caret);
            return run.getAttributes().isDefined(StyleConstants.ComposedTextAttribute);
        } catch (RuntimeException gone) {
            return false;
        }
    }

    private void palette() {
        java.util.Arrays.fill(styles, null);
        style(TokenType.KEYWORD, color(TokenType.KEYWORD, dark), 0);
        style(TokenType.TYPE, color(TokenType.TYPE, dark), 0);
        style(TokenType.LITERAL, color(TokenType.LITERAL, dark), 0);
        style(TokenType.NUMBER, color(TokenType.NUMBER, dark), 0);
        style(TokenType.STRING, color(TokenType.STRING, dark), 0);
        style(TokenType.CHAR, color(TokenType.CHAR, dark), 0);
        style(TokenType.COMMENT, color(TokenType.COMMENT, dark), ITALIC);
        style(TokenType.ANNOTATION, color(TokenType.ANNOTATION, dark), 0);
        style(TokenType.PREPROCESSOR, color(TokenType.PREPROCESSOR, dark), 0);
        style(TokenType.OPERATOR, color(TokenType.OPERATOR, dark), 0);
        style(TokenType.TAG, color(TokenType.TAG, dark), 0);
        style(TokenType.ATTRIBUTE, color(TokenType.ATTRIBUTE, dark), 0);
        style(TokenType.HEADING, color(TokenType.HEADING, dark), BOLD);
        style(TokenType.LINK, color(TokenType.LINK, dark), 0);
        style(TokenType.CODE, color(TokenType.CODE, dark), 0);
        style(TokenType.EMPHASIS, color(TokenType.EMPHASIS, dark), ITALIC);
        style(TokenType.LIST_MARKER, color(TokenType.LIST_MARKER, dark), 0);
        style(TokenType.QUOTE, color(TokenType.QUOTE, dark), 0);
        style(TokenType.TABLE, color(TokenType.TABLE, dark), 0);
        style(TokenType.STRONG, color(TokenType.STRONG, dark), BOLD);
        style(TokenType.STRIKE, color(TokenType.STRIKE, dark), STRIKE);
        style(TokenType.MARKER, color(TokenType.MARKER, dark), 0);
    }

    private void style(byte type, Color color, int flags) {
        if (color == null && flags == 0) return;
        SimpleAttributeSet set = new SimpleAttributeSet();
        if (color != null) StyleConstants.setForeground(set, color);
        if ((flags & ITALIC) != 0) StyleConstants.setItalic(set, true);
        if ((flags & BOLD) != 0) StyleConstants.setBold(set, true);
        if ((flags & STRIKE) != 0) StyleConstants.setStrikeThrough(set, true);
        styles[type] = set;
    }

    private static final int ITALIC = 1, BOLD = 2, STRIKE = 4;

    /** 明暗两套 token 配色（设计附录 A），预览页复用同一份色值。 */
    static Color color(byte type, boolean dark) {
        switch (type) {
            case TokenType.KEYWORD: return dark ? new Color(0xC586C0) : new Color(0xAF00DB);
            case TokenType.TYPE: return dark ? new Color(0x4EC9B0) : new Color(0x267F99);
            case TokenType.LITERAL: return dark ? new Color(0x569CD6) : new Color(0x0033B3);
            case TokenType.NUMBER: return dark ? new Color(0xB5CEA8) : new Color(0x098658);
            case TokenType.STRING: return dark ? new Color(0xCE9178) : new Color(0xA31515);
            case TokenType.CHAR: return dark ? new Color(0xCE9178) : new Color(0xA31515);
            case TokenType.COMMENT: return dark ? new Color(0x6A9955) : new Color(0x008000);
            case TokenType.ANNOTATION: return dark ? new Color(0xDCDCAA) : new Color(0x6B6B00);
            case TokenType.PREPROCESSOR: return dark ? new Color(0x9CDCFE) : new Color(0x6B6B6B);
            case TokenType.OPERATOR: return dark ? new Color(0xD4D4D4) : new Color(0x383838);
            case TokenType.TAG: return dark ? new Color(0x569CD6) : new Color(0x800000);
            case TokenType.ATTRIBUTE: return dark ? new Color(0x9CDCFE) : new Color(0xB35C00);
            case TokenType.HEADING: return dark ? new Color(0x60A5FA) : new Color(0x1D4ED8);
            case TokenType.LINK: return dark ? new Color(0x60A5FA) : new Color(0x1D4ED8);
            case TokenType.CODE: return dark ? new Color(0xCE9178) : new Color(0xA31515);
            case TokenType.LIST_MARKER: return dark ? new Color(0x60A5FA) : new Color(0x2563EB);
            case TokenType.QUOTE: return dark ? new Color(0x94A3B8) : new Color(0x64748B);
            case TokenType.TABLE: return dark ? new Color(0x94A3B8) : new Color(0x64748B);
            case TokenType.MARKER: return dark ? new Color(0x94A3B8) : new Color(0x94A3B8);
            default: return null;
        }
    }
}
