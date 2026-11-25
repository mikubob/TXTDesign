package txteditor.android;

import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import androidx.appcompat.widget.AppCompatEditText;
import txteditor.core.EditorModel;

public final class CoreEditText extends AppCompatEditText {
    private EditorModel model;
    private java.util.function.Consumer<Boolean> changed;
    private HighlightController highlight;
    private boolean writing, lineNumbers, keepViewport, drawingCaret;
    private int numberWidth;
    private int foldedHead = -1, foldedClose = -1;
    private float spacingBeforeFold;
    private final HiddenText hiddenText = new HiddenText();
    private final android.text.style.LineHeightSpan hiddenHeight = (text, start, end, oldY, newY, metrics) -> {
        metrics.top = metrics.ascent = metrics.descent = metrics.bottom = 0;
    };
    private final android.graphics.Paint numberPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

    void setLineNumbers(boolean visible) {
        lineNumbers = visible;
        updateNumberWidth();
        invalidate();
    }

    boolean lineNumbers() { return lineNumbers; }

    void restoreScroll(int top) {
        android.text.Layout layout = getLayout();
        int limit = layout == null ? 0 : Math.max(0, layout.getHeight() + getTotalPaddingTop() + getTotalPaddingBottom() - getHeight());
        scrollTo(0, Math.max(0, Math.min(top, limit)));
    }

    private void updateNumberWidth() {
        if (model == null) return;
        numberPaint.set(getPaint());
        int digits = lineNumbers ? Math.max(2, String.valueOf(model.getLineCount()).length()) : 0;
        if (foldedHead > 0) digits++;
        int width = (lineNumbers || foldedHead > 0) ? (int) Math.ceil(digits * numberPaint.measureText("0") + 12 * getResources().getDisplayMetrics().density) : 0;
        if (width == numberWidth) return;
        setPadding(getPaddingLeft() - numberWidth + width, getPaddingTop(), getPaddingRight(), getPaddingBottom());
        numberWidth = width;
    }

    @Override protected void onDraw(android.graphics.Canvas canvas) {
        super.onDraw(canvas);
        android.text.Layout layout = getLayout();
        if ((!lineNumbers && foldedHead < 0) || layout == null || model == null) return;
        int padding = getTotalPaddingTop();
        int first = layout.getLineForVertical(Math.max(0, getScrollY() - padding));
        int last = layout.getLineForVertical(getScrollY() + getHeight() - padding);
        numberPaint.setColor(getCurrentTextColor());
        numberPaint.setTextAlign(android.graphics.Paint.Align.RIGHT);
        float x = getScrollX() + getPaddingLeft() - 8 * getResources().getDisplayMetrics().density;
        for (int visual = first; visual <= last; visual++) {
            int offset = layout.getLineStart(visual);
            if (offset > 0 && getText().charAt(offset - 1) != '\n') continue;
            if (layout.getLineBottom(visual) <= layout.getLineTop(visual)) continue;
            int line = model.lineAt(offset);
            String label = line == foldedHead ? lineNumbers ? line + "…" : "…" : lineNumbers ? String.valueOf(line) : "";
            canvas.drawText(label, x, padding + layout.getLineBaseline(visual), numberPaint);
        }
    }
    boolean toggleFold() {
        if (foldedHead > 0) { expandFold(); return true; }
        int[] range = highlight.fold(model.getSelectionEnd());
        if (range.length == 0) return false;
        foldedHead = range[0] + 1; foldedClose = range[1] + 1;
        int head = model.lineStart(foldedHead);
        setSelection(head);
        int start = model.lineStart(foldedHead + 1), end = model.lineStart(foldedClose);
        spacingBeforeFold = getLineSpacingExtra();
        setLineSpacing(0, getLineSpacingMultiplier());
        getText().setSpan(hiddenText, start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        getText().setSpan(hiddenHeight, start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        updateNumberWidth(); requestLayout(); invalidate();
        return true;
    }

    void expandFold() {
        if (foldedHead < 0) return;
        foldedHead = foldedClose = -1;
        getText().removeSpan(hiddenText);
        getText().removeSpan(hiddenHeight);
        setLineSpacing(spacingBeforeFold, getLineSpacingMultiplier());
        updateNumberWidth(); requestLayout(); invalidate();
    }

    private static final class HiddenText extends android.text.style.ReplacementSpan {
        @Override public int getSize(android.graphics.Paint paint, CharSequence text, int start, int end,
                                     android.graphics.Paint.FontMetricsInt metrics) {
            if (metrics != null) metrics.top = metrics.ascent = metrics.descent = metrics.bottom = 0;
            return 0;
        }
        @Override public void draw(android.graphics.Canvas canvas, CharSequence text, int start, int end,
                                   float x, int top, int y, int bottom, android.graphics.Paint paint) { }
    }

    private int batch, inputDepth, platformBatch;
    private final Runnable flush = () -> sync(false);

    public CoreEditText(Context context, AttributeSet attrs) {
        super(context, attrs);
        addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) { }
            public void afterTextChanged(Editable s) {
                if (!writing) {
                    if (foldedHead > 0) post(CoreEditText.this::expandFold);
                    removeCallbacks(flush); post(flush);
                }
            }
        });
    }

    void bind(EditorModel core, java.util.function.Consumer<Boolean> listener) { model = core; changed = listener; render(); }

    void attachHighlight(HighlightController controller) { highlight = controller; controller.attach(this); }

    void render() {
        writing = true;
        String text = model.getText();
        boolean replaced = false;
        if (!getText().toString().equals(text)) {
            expandFold();
            getText().replace(0, length(), text);
            replaced = true;
        }
        setSelection(model.getSelectionStart(), model.getSelectionEnd());
        writing = false;
        updateNumberWidth();
        if (highlight != null) highlight.onSelection();
        // 整体回写会清掉 span，需要重扫；普通按键走 TextEdits 的三元组增量。
        if (replaced && highlight != null) highlight.reload();
    }

    boolean isComposing() {
        return batch > 0 || platformBatch > 0 || BaseInputConnection.getComposingSpanStart(getText()) >= 0;
    }

    void sync(boolean finishComposition) {
        if (model == null || writing) return;
        if (finishComposition) {
            BaseInputConnection.removeComposingSpans(getText());
            batch = 0;
        }
        if (!finishComposition && isComposing()) return;
        // 一次提交（含 IME 组合结束、长串输入）只产生一条历史，撤销整条还原。
        // shortcut: 每次 sync 独立成事务，连续按键仍是逐条历史；要合并连打需等输入空闲再结束事务。
        int[] change;
        model.beginTransaction();
        try { change = TextEdits.commit(model, getText().toString(), getSelectionStart(), getSelectionEnd()); }
        finally { model.endTransaction(); }
        if (change != null) updateNumberWidth();
        if (change != null && highlight != null) highlight.onTextChanged(change[0], change[1], change[2]);
        if (!model.getText().equals(getText().toString())) render();
        if (changed != null) changed.accept(change != null);
    }

    @Override protected void onScrollChanged(int left, int top, int oldLeft, int oldTop) {
        super.onScrollChanged(left, top, oldLeft, oldTop);
        if (top != oldTop && !drawingCaret) keepViewport = true;
        if (highlight != null) highlight.onScroll();
    }

    @Override protected void onSelectionChanged(int start, int end) {
        keepViewport = false;
        super.onSelectionChanged(start, end);
        if (model != null && foldedHead > 0 && (hiddenOffset(start) || hiddenOffset(end))) post(this::expandFold);
        if (model != null && !writing && inputDepth == 0 && batch == 0 && platformBatch == 0 && getText().toString().equals(model.getText())) {
            String text = model.getText();
            int a = TextEdits.boundary(text, Math.min(start, end), false);
            int b = TextEdits.boundary(text, Math.max(start, end), true);
            model.setSelection(a, b);
            if (highlight != null) highlight.onSelection();
        }
    }

    @Override public boolean onPreDraw() {
        drawingCaret = true;
        try { return super.onPreDraw(); }
        finally { drawingCaret = false; }
    }

    @Override public boolean bringPointIntoView(int offset) {
        // 分屏重排时 TextView 会尝试把旧光标滚回屏内，用户滚动后等实际选区变化再跟随。
        return (!drawingCaret || !keepViewport) && super.bringPointIntoView(offset);
    }

    private boolean hiddenOffset(int offset) {
        if (offset < 0) return false;
        int line = model.lineAt(offset);
        return line > foldedHead && line < foldedClose;
    }

    // TextView batches also cover newer InputConnection overloads delegated by the framework.
    @Override public void onBeginBatchEdit() { platformBatch++; super.onBeginBatchEdit(); }
    @Override public void onEndBatchEdit() {
        super.onEndBatchEdit(); platformBatch = Math.max(0, platformBatch - 1); post(flush);
    }

    @Override public InputConnection onCreateInputConnection(EditorInfo info) {
        InputConnection connection = super.onCreateInputConnection(info);
        if (connection == null) return null;
        return new InputConnectionWrapper(connection, false) {
            @Override public boolean beginBatchEdit() { batch++; return super.beginBatchEdit(); }
            @Override public boolean endBatchEdit() {
                boolean result = super.endBatchEdit(); batch = Math.max(0, batch - 1); post(flush); return result;
            }
            @Override public boolean commitText(CharSequence text, int cursor) {
                inputDepth++;
                try { return super.commitText(text, cursor); }
                finally { inputDepth--; post(flush); }
            }
            @Override public boolean setComposingText(CharSequence text, int cursor) {
                inputDepth++;
                try { return super.setComposingText(text, cursor); }
                finally { inputDepth--; post(flush); }
            }
            @Override public boolean deleteSurroundingText(int before, int after) {
                inputDepth++;
                try { return super.deleteSurroundingText(before, after); }
                finally { inputDepth--; post(flush); }
            }
            @Override public boolean deleteSurroundingTextInCodePoints(int before, int after) {
                inputDepth++;
                try { return super.deleteSurroundingTextInCodePoints(before, after); }
                finally { inputDepth--; post(flush); }
            }
            @Override public boolean finishComposingText() {
                boolean result = super.finishComposingText(); post(flush); return result;
            }
        };
    }

    @Override public boolean onTextContextMenuItem(int id) {
        if (id == android.R.id.undo || id == android.R.id.redo) return true;
        inputDepth++;
        try { return super.onTextContextMenuItem(id); }
        finally { inputDepth--; post(flush); }
    }

    @Override public boolean onKeyDown(int code, KeyEvent event) {
        inputDepth++;
        try { return super.onKeyDown(code, event); }
        finally { inputDepth--; post(flush); }
    }

    @Override public boolean onKeyShortcut(int keyCode, KeyEvent event) {
        if (event.isCtrlPressed() && (keyCode == KeyEvent.KEYCODE_Z || keyCode == KeyEvent.KEYCODE_Y)) {
            sync(true);
            boolean applied = keyCode == KeyEvent.KEYCODE_Y || event.isShiftPressed() ? model.redo() : model.undo();
            render(); if (changed != null) changed.accept(applied); return true;
        }
        return super.onKeyShortcut(keyCode, event);
    }
}
