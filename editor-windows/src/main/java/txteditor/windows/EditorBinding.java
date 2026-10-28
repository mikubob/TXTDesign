package txteditor.windows;

import txteditor.core.EditorModel;
import txteditor.core.TextCodec;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;
import javax.swing.text.Element;
import javax.swing.text.StyleConstants;
import java.awt.Toolkit;
import java.awt.event.InputMethodEvent;
import java.awt.event.InputMethodListener;
import java.util.function.Consumer;

/** The widget owns composition/display; committed edits always enter the shared model. */
final class EditorBinding extends DocumentFilter {
    final JTextArea area;
    final EditorModel model;
    private final Runnable changed;
    private final Consumer<String> error;
    private boolean writing;
    private boolean composing;
    private boolean grouping;

    EditorBinding(JTextArea area, EditorModel model, Runnable changed, Consumer<String> error) {
        this.area = area; this.model = model; this.changed = changed; this.error = error;
        ((AbstractDocument) area.getDocument()).setDocumentFilter(this);
        area.addCaretListener(event -> {
            if (writing || composing) return;
            int start = area.getSelectionStart(), end = area.getSelectionEnd();
            if (end <= model.length()) {
                if (start == end) { while (!model.isBoundary(start)) start++; end = start; }
                else { while (!model.isBoundary(start)) start--; while (!model.isBoundary(end)) end++; }
                model.setSelection(start, end);
                if (start != area.getSelectionStart() || end != area.getSelectionEnd()) syncSelection();
            }
            changed.run();
        });
        area.addInputMethodListener(new InputMethodListener() {
            public void inputMethodTextChanged(InputMethodEvent e) {
                SwingUtilities.invokeLater(() -> { if (!hasComposition(area.getDocument().getDefaultRootElement())) flushComposition(); });
            }
            public void caretPositionChanged(InputMethodEvent e) { }
        });
    }

    @Override public void insertString(FilterBypass fb, int offset, String text, AttributeSet attrs) throws BadLocationException {
        replace(fb, offset, 0, text, attrs);
    }
    @Override public void remove(FilterBypass fb, int offset, int length) throws BadLocationException {
        replace(fb, offset, length, "", null);
    }

    @Override public void replace(FilterBypass fb, int offset, int length, String value, AttributeSet attrs) throws BadLocationException {
        String text = value == null ? "" : value;
        if (writing) { fb.replace(offset, length, text, attrs); return; }
        if (attrs != null && attrs.isDefined(StyleConstants.ComposedTextAttribute)) {
            composing = true;
            fb.replace(offset, length, text, attrs);
            changed.run();
            return;
        }
        if (composing) {
            fb.replace(offset, length, text, attrs);
            if (!hasComposition(fb.getDocument().getDefaultRootElement())) flushComposition();
            return;
        }
        try {
            int start = offset, end = offset + length;
            if (length == 0) { while (!model.isBoundary(start)) start++; end = start; }
            else { while (!model.isBoundary(start)) start--; while (!model.isBoundary(end)) end++; }
            String normalized = TextCodec.normalize(text);
            beginGroup();
            model.replace(start, end, normalized);
            writing = true;
            try { fb.replace(start, end - start, normalized, attrs); }
            finally { writing = false; }
            // DefaultCaret's requested offset can bisect a pair after expanded deletion.
            SwingUtilities.invokeLater(() -> { if (!composing) syncSelection(); });
            changed.run();
        } catch (IllegalArgumentException | IndexOutOfBoundsException rejected) {
            Toolkit.getDefaultToolkit().beep();
            error.accept(rejected.getMessage());
        }
    }

    void finishInput() {
        if (area.getInputContext() != null) area.getInputContext().endComposition();
        flushComposition();
        endGroup();
    }

    /** 一次输入连续提交共用一条历史，合并规则由核心按相邻/同段/500ms 判定。 */
    private void beginGroup() {
        if (grouping) return;
        model.beginTransaction();
        grouping = true;
    }

    void endGroup() {
        if (!grouping) return;
        model.endTransaction();
        grouping = false;
    }

    private void flushComposition() {
        if (!composing) return;
        String old = model.getText(), now = area.getText();
        int start = 0;
        while (start < old.length() && start < now.length() && old.charAt(start) == now.charAt(start)) start++;
        while (!model.isBoundary(start)) start--;
        int oldEnd = old.length(), newEnd = now.length();
        while (oldEnd > start && newEnd > start && old.charAt(oldEnd - 1) == now.charAt(newEnd - 1)) { oldEnd--; newEnd--; }
        while (!model.isBoundary(oldEnd)) { oldEnd++; newEnd++; }
        try {
            beginGroup();
            model.replace(start, oldEnd, now.substring(start, newEnd));
            composing = false;
            if (!model.getText().equals(now)) refresh();
            else {
                int caret = Math.min(area.getCaretPosition(), model.length());
                while (!model.isBoundary(caret)) caret++;
                model.setSelection(caret, caret);
            }
            changed.run();
        } catch (IllegalArgumentException rejected) {
            composing = false;
            refresh();
            error.accept(rejected.getMessage());
        }
    }

    void refresh() {
        writing = true;
        try { area.setText(model.getText()); syncSelection(); }
        finally { writing = false; }
        changed.run();
    }

    void syncSelection() {
        writing = true;
        try { area.select(model.getSelectionStart(), model.getSelectionEnd()); }
        finally { writing = false; }
    }

    private static boolean hasComposition(Element element) {
        if (element.getAttributes().isDefined(StyleConstants.ComposedTextAttribute)) return true;
        for (int i = 0; i < element.getElementCount(); i++) if (hasComposition(element.getElement(i))) return true;
        return false;
    }
}
