package txteditor.core;

import java.util.ArrayDeque;
import java.util.Deque;

/** Single-document model. Use on one UI thread; all offsets are UTF-16. */
public final class EditorModel {
    public static final int HISTORY_LIMIT = 100;
    /** 事务内两次替换间隔不超过该毫秒数才可能合并为一条历史。 */
    public static final long MERGE_WINDOW_MILLIS = 500;
    private static final long MERGE_WINDOW_NANOS = MERGE_WINDOW_MILLIS * 1_000_000L;
    private final GapBuffer buffer = new GapBuffer();
    private final Deque<Edit> undo = new ArrayDeque<Edit>();
    private final Deque<Edit> redo = new ArrayDeque<Edit>();
    private String savedText = "";
    private int selectionStart;
    private int selectionEnd;
    private boolean inTransaction;
    private Edit pending;
    private long pendingAt;

    public String getText() { return buffer.toString(); }
    public int length() { return buffer.length(); }
    public int getSelectionStart() { return selectionStart; }
    public int getSelectionEnd() { return selectionEnd; }
    /** 最近一次保存的文本；会话快照用它恢复未保存状态。 */
    public String getSavedText() { return savedText; }
    public boolean canUndo() { return !undo.isEmpty() || pending != null && !pending.removed.equals(pending.inserted); }
    public boolean canRedo() { return !redo.isEmpty(); }
    public boolean isModified() { return !savedText.contentEquals(buffer); }
    public void markSaved() { savedText = getText(); }
    /** The snapshot actually written; permits adapters to keep newer edits dirty. */
    public void markSaved(String snapshot) { savedText = TextCodec.normalize(snapshot); }

    public void load(String text) {
        String normalized = TextCodec.normalize(text);
        buffer.replace(0, buffer.length(), normalized);
        undo.clear();
        redo.clear();
        pending = null;
        inTransaction = false;
        selectionStart = selectionEnd = 0;
        savedText = normalized;
    }

    public void setSelection(int start, int end) {
        checkRange(start, end);
        selectionStart = start;
        selectionEnd = end;
    }

    public boolean isBoundary(int index) {
        return index >= 0 && index <= length() && (index == 0 || index == length()
                || !Character.isHighSurrogate(buffer.charAt(index - 1))
                || !Character.isLowSurrogate(buffer.charAt(index)));
    }

    /** Opens a merge window; every edit until endTransaction may share one undo entry. */
    public void beginTransaction() {
        if (inTransaction) throw new IllegalStateException("事务不能嵌套");
        inTransaction = true;
    }

    /** Closes the window and commits whatever was merged. Safe to call when no transaction is open. */
    public void endTransaction() {
        if (!inTransaction) return;
        commitPending();
        inTransaction = false;
    }

    public void replace(int start, int end, String text) {
        checkRange(start, end);
        String inserted = TextCodec.normalize(text);
        String removed = buffer.subSequence(start, end);
        int caret = start + inserted.length();
        if (!removed.equals(inserted)) {
            Edit edit = new Edit(start, removed, inserted, selectionStart, selectionEnd, caret);
            buffer.replace(start, end, inserted);
            redo.clear();
            record(edit);
        }
        selectionStart = selectionEnd = caret;
    }

    public boolean undo() {
        commitPending();
        if (undo.isEmpty()) return false;
        Edit edit = undo.pop();
        buffer.replace(edit.start, edit.start + edit.inserted.length(), edit.removed);
        selectionStart = edit.beforeStart;
        selectionEnd = edit.beforeEnd;
        redo.push(edit);
        return true;
    }

    public boolean redo() {
        commitPending();
        if (redo.isEmpty()) return false;
        Edit edit = redo.pop();
        buffer.replace(edit.start, edit.start + edit.removed.length(), edit.inserted);
        selectionStart = selectionEnd = edit.afterCaret;
        undo.push(edit);
        return true;
    }

    /** Returns an offset, does not change selection; wraps at most once. */
    public int findNext(String pattern, int from) {
        TextCodec.validateUnicode(pattern);
        if (!isBoundary(from)) throw new IllegalArgumentException("查找起点不在字符边界");
        int result = Kmp.indexOf(buffer, pattern, from);
        return result < 0 && from > 0 ? Kmp.indexOf(buffer, pattern, 0) : result;
    }

    public int getCodePointCount() { return Character.codePointCount(buffer, 0, length()); }
    public int getLineCount() {
        int lines = 1;
        for (int i = 0; i < length(); i++) if (buffer.charAt(i) == '\n') lines++;
        return lines;
    }

    private void record(Edit edit) {
        if (!inTransaction) { push(edit); return; }
        if (pending == null) { pending = edit; pendingAt = System.nanoTime(); return; }
        if (mergeable(edit)) {
            int head = edit.start - pending.start;
            pending = new Edit(pending.start, pending.removed,
                    pending.inserted.substring(0, head) + edit.inserted
                            + pending.inserted.substring(head + edit.removed.length()),
                    pending.beforeStart, pending.beforeEnd, edit.afterCaret);
            pendingAt = System.nanoTime();
            return;
        }
        commitPending();
        pending = edit;
        pendingAt = System.nanoTime();
    }

    /** 相邻（落在已合并结果内）、同段落、间隔小于合并窗口。 */
    private boolean mergeable(Edit edit) {
        if (System.nanoTime() - pendingAt >= MERGE_WINDOW_NANOS) return false;
        if (edit.start < pending.start || edit.start + edit.removed.length() > pending.start + pending.inserted.length()) return false;
        return !crossesParagraph(pending.removed) && !crossesParagraph(pending.inserted)
                && !crossesParagraph(edit.removed) && !crossesParagraph(edit.inserted);
    }

    private static boolean crossesParagraph(String text) { return text.indexOf('\n') >= 0; }

    private void commitPending() {
        Edit done = pending;
        pending = null;
        if (done != null && !done.removed.equals(done.inserted)) push(done);
    }

    private void push(Edit edit) {
        undo.push(edit);
        if (undo.size() > HISTORY_LIMIT) undo.removeLast();
    }

    private void checkRange(int start, int end) {
        if (start < 0 || end < start || end > length()) throw new IndexOutOfBoundsException(start + ".." + end);
        if (!isBoundary(start) || !isBoundary(end)) throw new IllegalArgumentException("不能拆开 UTF-16 代理项对");
    }

    private static final class Edit {
        final int start, beforeStart, beforeEnd, afterCaret;
        final String removed, inserted;
        Edit(int start, String removed, String inserted, int beforeStart, int beforeEnd, int afterCaret) {
            this.start = start;
            this.removed = removed;
            this.inserted = inserted;
            this.beforeStart = beforeStart;
            this.beforeEnd = beforeEnd;
            this.afterCaret = afterCaret;
        }
    }
}
