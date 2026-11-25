package txteditor.core;

import java.util.ArrayDeque;
import java.util.Deque;
import txteditor.core.text.LineIndex;

/** Single-document model. Use on one UI thread; all offsets are UTF-16. */
public final class EditorModel {
    public static final int HISTORY_LIMIT = 100;
    /** 事务内两次替换间隔不超过该毫秒数才可能合并为一条历史。 */
    public static final long MERGE_WINDOW_MILLIS = 500;
    private static final long MERGE_WINDOW_NANOS = MERGE_WINDOW_MILLIS * 1_000_000L;
    private final LineIndex lines = new LineIndex();
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
        lines.load(buffer);
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

    /** 将持久化偏移吸附到当前正文的有效字符边界。 */
    public int clampOffset(int offset) {
        int bounded = Math.max(0, Math.min(length(), offset));
        return isBoundary(bounded) ? bounded : bounded - 1;
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
        String inserted = TextCodec.normalize(text);
        int caret = start + inserted.length();
        replace(start, end, inserted, caret, caret);
    }

    private void replace(int start, int end, String inserted, int afterStart, int afterEnd) {
        checkRange(start, end);
        String removed = buffer.subSequence(start, end);
        if (!removed.equals(inserted)) {
            Edit edit = new Edit(start, removed, inserted, selectionStart, selectionEnd, afterStart, afterEnd);
            updateBuffer(start, end, inserted);
            redo.clear();
            record(edit);
        }
        selectionStart = afterStart; selectionEnd = afterEnd;
    }

    public boolean undo() {
        commitPending();
        if (undo.isEmpty()) return false;
        Edit edit = undo.pop();
        updateBuffer(edit.start, edit.start + edit.inserted.length(), edit.removed);
        selectionStart = edit.beforeStart;
        selectionEnd = edit.beforeEnd;
        redo.push(edit);
        return true;
    }

    public boolean redo() {
        commitPending();
        if (redo.isEmpty()) return false;
        Edit edit = redo.pop();
        updateBuffer(edit.start, edit.start + edit.removed.length(), edit.inserted);
        selectionStart = edit.afterStart; selectionEnd = edit.afterEnd;
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

    /** 从选区开头往前找完整匹配，未找到时回绕到最后一处。 */
    public int findPrevious(String pattern, int before) {
        TextCodec.validateUnicode(pattern);
        if (!isBoundary(before)) throw new IllegalArgumentException("查找起点不在字符边界");
        return Kmp.previous(buffer, pattern, before);
    }

    /** 重叠的字面匹配分别计数。 */
    public int countMatches(String pattern) {
        TextCodec.validateUnicode(pattern);
        return Kmp.count(buffer, pattern);
    }

    /** 从左到右替换非重叠匹配，整条命令只产生一项撤销。 */
    public int replaceAll(String pattern, String replacement) {
        TextCodec.validateUnicode(pattern);
        String inserted = TextCodec.normalize(replacement);
        int count = Kmp.replaceAll(buffer, pattern, inserted, null);
        if (count == 0 || pattern.equals(inserted)) return count;
        long outputLength = length() + (long) count * (inserted.length() - pattern.length());
        if (outputLength > TextCodec.FILE_LIMIT_BYTES) throw new IllegalArgumentException(TextKeys.REPLACE_TOO_LARGE);
        StringBuilder out = new StringBuilder((int) outputLength);
        Kmp.replaceAll(buffer, pattern, inserted, out);
        if (TextCodec.exceedsUtf8Limit(out, TextCodec.FILE_LIMIT_BYTES))
            throw new IllegalArgumentException(TextKeys.REPLACE_TOO_LARGE);
        endTransaction();
        replace(0, length(), out.toString());
        return count;
    }

    public enum Command {
        INDENT(TextKeys.INDENT), OUTDENT(TextKeys.OUTDENT), DUPLICATE(TextKeys.DUPLICATE_LINE),
        TASK(TextKeys.INSERT_TASK), FENCE(TextKeys.INSERT_FENCE);

        public final String title;
        Command(String title) { this.title = title; }
        public boolean markdownOnly() { return this == TASK || this == FENCE; }
    }

    public void apply(Command command) {
        endTransaction();
        int start = selectionStart, end = selectionEnd;
        int first = lineStartAt(start);
        if (command == Command.DUPLICATE) {
            int stop = lineEndAt(start);
            replace(stop, stop, "\n" + buffer.subSequence(first, stop));
            return;
        }
        if (command == Command.TASK) {
            int prefix = first;
            while (prefix < length() && (buffer.charAt(prefix) == ' ' || buffer.charAt(prefix) == '\t')) prefix++;
            replace(prefix, prefix, "- [ ] ", Math.max(prefix + 6, start + 6), Math.max(prefix + 6, end + 6));
            return;
        }
        if (command == Command.FENCE) {
            int stop = lineEndAt(end > start && buffer.charAt(end - 1) == '\n' ? end - 1 : end);
            String content = buffer.subSequence(first, stop);
            int longest = 2, run = 0;
            for (int i = 0; i < content.length(); i++) {
                run = content.charAt(i) == (char) 96 ? run + 1 : 0;
                longest = Math.max(longest, run);
            }
            StringBuilder marker = new StringBuilder();
            for (int i = 0; i <= longest; i++) marker.append((char) 96);
            int body = first + marker.length() + 1;
            replace(first, stop, marker + "\n" + content + "\n" + marker,
                    body + start - first, body + Math.min(end, stop) - first);
            return;
        }
        int stop = lineEndAt(end > start && buffer.charAt(end - 1) == '\n' ? end - 1 : end);
        int mappedStart = start, mappedEnd = end;
        StringBuilder out = new StringBuilder();
        for (int line = first; ; ) {
            int next = lineEndAt(line);
            int removed = 0, added = 0;
            if (command == Command.INDENT) {
                out.append("    ");
                added = 4;
            } else if (line < next && buffer.charAt(line) == '\t') removed = 1;
            else while (removed < 4 && line + removed < next && buffer.charAt(line + removed) == ' ') removed++;
            if (start >= line) mappedStart += added - Math.min(removed, start - line);
            if (end >= line) mappedEnd += added - Math.min(removed, end - line);
            out.append(buffer, line + removed, next);
            if (next == stop) break;
            out.append('\n');
            line = next + 1;
        }
        replace(first, stop, out.toString(), mappedStart, mappedEnd);
    }

    private int lineStartAt(int offset) {
        return lines.start(lines.lineOf(offset));
    }

    private int lineEndAt(int offset) {
        return lines.end(lines.lineOf(offset));
    }

    /** 行号从 1 开始，返回行首偏移，无此行返回 -1。 */
    public int lineStart(int line) {
        return line < 1 || line > lines.count() ? -1 : lines.start(line - 1);
    }

    public int getCodePointCount() { return Character.codePointCount(buffer, 0, length()); }
    public int getLineCount() { return lines.count(); }
    public int lineAt(int offset) { return lines.lineOf(offset) + 1; }

    private void updateBuffer(int start, int oldEnd, String inserted) {
        buffer.replace(start, oldEnd, inserted);
        lines.update(buffer, start, oldEnd, start + inserted.length());
    }

    private void record(Edit edit) {
        if (!inTransaction) { push(edit); return; }
        if (pending == null) { pending = edit; pendingAt = System.nanoTime(); return; }
        if (mergeable(edit)) {
            int head = edit.start - pending.start;
            pending = new Edit(pending.start, pending.removed,
                    pending.inserted.substring(0, head) + edit.inserted
                            + pending.inserted.substring(head + edit.removed.length()),
                    pending.beforeStart, pending.beforeEnd, edit.afterStart, edit.afterEnd);
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
        final int start, beforeStart, beforeEnd, afterStart, afterEnd;
        final String removed, inserted;
        Edit(int start, String removed, String inserted, int beforeStart, int beforeEnd, int afterStart, int afterEnd) {
            this.start = start;
            this.removed = removed;
            this.inserted = inserted;
            this.beforeStart = beforeStart;
            this.beforeEnd = beforeEnd;
            this.afterStart = afterStart; this.afterEnd = afterEnd;
        }
    }
}
