package txteditor.windows;

import txteditor.core.text.LanguageId;
import txteditor.core.text.SyntaxHighlighter;
import txteditor.core.text.TokenSink;
import txteditor.core.text.md.MdDocument;
import txteditor.core.text.md.MdParser;
import txteditor.core.text.md.MdSpan;
import javax.swing.JTextPane;
import javax.swing.text.AttributeSet;
import javax.swing.text.DefaultStyledDocument;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.Style;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyleContext;
import java.awt.Color;
import java.awt.Font;

/**
 * Markdown 只读预览：块级结构连续写进一个 DefaultStyledDocument，代码块只上色不嵌组件。
 * 文档先离线建好再挂到 JTextPane，避免每次插入触发一次重排。
 */
final class MarkdownPreview {
    private static final String FAMILY = "Microsoft YaHei UI";
    /** shortcut: 预览字号固定，不跟随编辑区缩放；等预览成为主视图再接 zoom 参数。 */
    private static final int BODY = 15, CODE = 14;
    /** 与源码高亮同一个降级阈值（设计 §11）：再大就只提示不解析了。 */
    private static final int LIMIT = 2 << 20;
    private final JTextPane pane = new JTextPane();
    private boolean dark;

    MarkdownPreview() {
        pane.setEditable(false);
        pane.setBorder(javax.swing.BorderFactory.createEmptyBorder(22, 26, 22, 26));
        pane.getAccessibleContext().setAccessibleName("Markdown 预览区");
    }

    JTextPane pane() { return pane; }

    void render(String source, boolean dark) {
        this.dark = dark;
        MdDocument document = MdParser.parse(source.length() > LIMIT ? "" : source);
        DefaultStyledDocument target = new DefaultStyledDocument();
        Style base = target.getStyle(StyleContext.DEFAULT_STYLE);
        StyleConstants.setFontFamily(base, FAMILY);
        StyleConstants.setFontSize(base, BODY);
        StyleConstants.setForeground(base, text());
        try {
            if (source.length() > LIMIT) target.insertString(0, "文档超过 2 MiB，预览已关闭。", muted());
            for (int block = 0; block < document.count(); block++) renderBlock(target, document, block);
            if (document.count() == 0 && source.length() <= LIMIT) target.insertString(0, "（空文档）", muted());
        } catch (javax.swing.text.BadLocationException impossible) {
            throw new IllegalStateException(impossible);
        }
        pane.setDocument(target);
        pane.setCaretPosition(0);
    }

    private void renderBlock(DefaultStyledDocument target, MdDocument document, int block) throws javax.swing.text.BadLocationException {
        switch (document.type(block)) {
            case MdDocument.HEADING: {
                int level = Math.max(1, Math.min(6, document.level(block)));
                SimpleAttributeSet attr = new SimpleAttributeSet();
                StyleConstants.setBold(attr, true);
                StyleConstants.setFontSize(attr, headingSize(level));
                StyleConstants.setForeground(attr, SyntaxPainter.color(txteditor.core.text.TokenType.HEADING, dark));
                StyleConstants.setSpaceAbove(attr, level <= 2 ? 16f : 12f);
                StyleConstants.setSpaceBelow(attr, 6f);
                insert(target, document.source(block), attr, attr);
                break;
            }
            case MdDocument.LIST_ITEM: {
                int level = Math.max(0, Math.min(6, document.level(block)));
                String prefix = repeat("  ", level) + (document.checked(block) ? "☑ " : "• ");
                SimpleAttributeSet marker = new SimpleAttributeSet();
                StyleConstants.setForeground(marker, SyntaxPainter.color(txteditor.core.text.TokenType.LIST_MARKER, dark));
                SimpleAttributeSet para = new SimpleAttributeSet();
                StyleConstants.setLeftIndent(para, 18f * (level + 1));
                StyleConstants.setSpaceBelow(para, 2f);
                inline(target, document, block, prefix, marker, para);
                break;
            }
            case MdDocument.QUOTE: {
                SimpleAttributeSet marker = new SimpleAttributeSet();
                StyleConstants.setForeground(marker, SyntaxPainter.color(txteditor.core.text.TokenType.QUOTE, dark));
                SimpleAttributeSet para = new SimpleAttributeSet();
                StyleConstants.setLeftIndent(para, 14f);
                StyleConstants.setRightIndent(para, 8f);
                StyleConstants.setSpaceBelow(para, 4f);
                SimpleAttributeSet body = new SimpleAttributeSet();
                StyleConstants.setForeground(body, SyntaxPainter.color(txteditor.core.text.TokenType.QUOTE, dark));
                StyleConstants.setItalic(body, true);
                int depth = Math.max(1, document.quote(block));
                inline(target, document, block, repeat("▎ ", depth), marker, para, body);
                break;
            }
            case MdDocument.RULE: {
                SimpleAttributeSet rule = new SimpleAttributeSet();
                StyleConstants.setForeground(rule, SyntaxPainter.color(txteditor.core.text.TokenType.TABLE, dark));
                StyleConstants.setSpaceAbove(rule, 8f);
                StyleConstants.setSpaceBelow(rule, 8f);
                insert(target, "────────────", rule, rule);
                break;
            }
            case MdDocument.CODE: code(target, document, block); break;
            case MdDocument.TABLE: table(target, document, block); break;
            default: {
                SimpleAttributeSet para = new SimpleAttributeSet();
                StyleConstants.setSpaceBelow(para, 6f);
                inline(target, document, block, null, null, para);
            }
        }
    }

    /** 标记符（**、``、[]() 等）不复制进预览，只保留正文与行内样式。 */
    private void inline(DefaultStyledDocument target, MdDocument document, int block, String prefix,
                        AttributeSet prefixAttr, AttributeSet paragraph) throws javax.swing.text.BadLocationException {
        inline(target, document, block, prefix, prefixAttr, paragraph, null);
    }

    private void inline(DefaultStyledDocument target, MdDocument document, int block, String prefix,
                        AttributeSet prefixAttr, AttributeSet paragraph, AttributeSet body)
            throws javax.swing.text.BadLocationException {
        int start = target.getLength();
        if (prefix != null) target.insertString(start, prefix, prefixAttr);
        int bodyStart = target.getLength();
        MdSpan[] spans = document.inline(block);
        int[] from = new int[spans.length], to = new int[spans.length];
        java.util.Arrays.fill(from, -1);
        StringBuilder out = new StringBuilder();
        String text = document.text();
        int pos = document.content(block), stop = document.contentEnd(block), span = 0;
        while (pos < stop) {
            if (span < spans.length && pos >= spans[span].end) { span++; continue; }
            if (span < spans.length && pos >= spans[span].start) {
                if (spans[span].marker) { pos = spans[span].end; span++; continue; }
                if (from[span] < 0) from[span] = out.length();
                out.append(text.charAt(pos++));
                if (pos >= spans[span].end) to[span] = out.length();
                continue;
            }
            out.append(text.charAt(pos++));
        }
        target.insertString(bodyStart, out.toString() + "\n", body);
        for (int i = 0; i < spans.length; i++) {
            if (from[i] < 0 || to[i] <= from[i]) continue;
            AttributeSet attr = spanStyle(spans[i].type);
            if (attr != null) target.setCharacterAttributes(bodyStart + from[i], to[i] - from[i], attr, false);
        }
        target.setParagraphAttributes(start, target.getLength() - start, paragraph, false);
    }

    private AttributeSet spanStyle(byte type) {
        SimpleAttributeSet attr = new SimpleAttributeSet();
        switch (type) {
            case MdSpan.BOLD:
                StyleConstants.setBold(attr, true);
                if (dark) StyleConstants.setForeground(attr, text());
                return attr;
            case MdSpan.ITALIC:
                StyleConstants.setItalic(attr, true);
                return attr;
            case MdSpan.STRIKE:
                StyleConstants.setStrikeThrough(attr, true);
                StyleConstants.setForeground(attr, SyntaxPainter.color(txteditor.core.text.TokenType.QUOTE, dark));
                return attr;
            case MdSpan.CODE: {
                StyleConstants.setFontFamily(attr, Font.MONOSPACED);
                StyleConstants.setFontSize(attr, BODY - 1);
                StyleConstants.setForeground(attr, SyntaxPainter.color(txteditor.core.text.TokenType.CODE, dark));
                StyleConstants.setBackground(attr, dark ? new Color(0x223046) : new Color(0xF1F5F9));
                return attr;
            }
            case MdSpan.LINK: {
                StyleConstants.setForeground(attr, SyntaxPainter.color(txteditor.core.text.TokenType.LINK, dark));
                StyleConstants.setUnderline(attr, true);
                return attr;
            }
            default:
                return null;
        }
    }

    private void code(DefaultStyledDocument target, MdDocument document, int block) throws javax.swing.text.BadLocationException {
        String code = document.source(block);
        if (code.endsWith("\n")) code = code.substring(0, code.length() - 1);
        int start = target.getLength();
        SimpleAttributeSet box = new SimpleAttributeSet();
        StyleConstants.setFontFamily(box, Font.MONOSPACED);
        StyleConstants.setFontSize(box, CODE);
        StyleConstants.setForeground(box, text());
        StyleConstants.setBackground(box, dark ? new Color(0x223046) : new Color(0xF1F5F9));
        StyleConstants.setLeftIndent(box, 12f);
        StyleConstants.setRightIndent(box, 12f);
        StyleConstants.setSpaceAbove(box, 4f);
        StyleConstants.setSpaceBelow(box, 8f);
        insert(target, code, box, box);
        LanguageId language = language(document.language(block));
        if (language == LanguageId.PLAIN) return;
        SyntaxHighlighter highlighter = new SyntaxHighlighter();
        highlighter.setLanguage(language);
        highlighter.load(code);
        TokenSink sink = new TokenSink();
        highlighter.paint(0, highlighter.lineCount() - 1, sink);
        for (int i = 0; i < sink.size(); i++) {
            SimpleAttributeSet attr = new SimpleAttributeSet();
            Color color = SyntaxPainter.color(sink.type(i), dark);
            if (color == null) continue;
            StyleConstants.setForeground(attr, color);
            target.setCharacterAttributes(start + sink.start(i), sink.end(i) - sink.start(i), attr, false);
        }
    }

    private void table(DefaultStyledDocument target, MdDocument document, int block) throws javax.swing.text.BadLocationException {
        String raw = document.raw(block);
        String[] lines = raw.split("\n", -1);
        SimpleAttributeSet cell = new SimpleAttributeSet();
        StyleConstants.setFontFamily(cell, Font.MONOSPACED);
        StyleConstants.setFontSize(cell, CODE);
        StyleConstants.setForeground(cell, text());
        StyleConstants.setSpaceBelow(cell, 0f);
        SimpleAttributeSet head = new SimpleAttributeSet(cell);
        StyleConstants.setBold(head, true);
        StyleConstants.setBackground(head, dark ? new Color(0x223046) : new Color(0xEFF3F8));
        int row = 0;
        for (String line : lines) {
            if (line.isEmpty()) continue;
            if (isDelimiter(line)) { row = -1; continue; } // 分隔行只用来识别表头，不显示
            insert(target, line, row == 0 ? head : cell, row == 0 ? head : cell);
            row++;
        }
    }

    private static boolean isDelimiter(String line) {
        boolean dash = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '-') dash = true;
            else if (c != '|' && c != ':' && c != ' ' && c != '\t') return false;
        }
        return dash;
    }

    /** 一段正文；attrs 同时用于字符与段落，保证底色、行距一致。 */
    private void insert(DefaultStyledDocument target, String text, AttributeSet attrs, AttributeSet paragraph)
            throws javax.swing.text.BadLocationException {
        int start = target.getLength();
        target.insertString(start, text + "\n", attrs);
        target.setParagraphAttributes(start, target.getLength() - start, paragraph, false);
    }

    private int headingSize(int level) {
        switch (level) {
            case 1: return BODY + 10;
            case 2: return BODY + 6;
            case 3: return BODY + 3;
            case 4: return BODY + 1;
            default: return BODY;
        }
    }

    private AttributeSet muted() {
        SimpleAttributeSet attr = new SimpleAttributeSet();
        StyleConstants.setForeground(attr, SyntaxPainter.color(txteditor.core.text.TokenType.QUOTE, dark));
        return attr;
    }

    private Color text() { return dark ? new Color(0xE5ECF6) : new Color(0x172033); }

    private static String repeat(String value, int times) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < times; i++) out.append(value);
        return out.toString();
    }

    /** 围栏标记里的语言名（java / c / json …）映射回语言，未知按纯文本。 */
    static LanguageId language(String name) {
        if (name == null || name.isEmpty()) return LanguageId.PLAIN;
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (lower.equals("js") || lower.equals("javascript") || lower.equals("ts") || lower.equals("python")
                || lower.equals("py") || lower.equals("sql") || lower.equals("sh") || lower.equals("bash")) {
            return LanguageId.PLAIN;
        }
        if (lower.equals("html") || lower.equals("htm") || lower.equals("svg")) return LanguageId.XML;
        if (lower.equals("c++") || lower.equals("cxx") || lower.equals("cc") || lower.equals("hpp")) return LanguageId.CPP;
        return LanguageId.forFileName("x." + lower);
    }
}
