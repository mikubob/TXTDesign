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
    private Runnable changed;
    private HighlightController highlight;
    private boolean writing;
    private int batch, inputDepth, platformBatch;
    private final Runnable flush = () -> sync(false);

    public CoreEditText(Context context, AttributeSet attrs) {
        super(context, attrs);
        addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) { }
            public void afterTextChanged(Editable s) {
                if (!writing) { removeCallbacks(flush); post(flush); }
            }
        });
    }

    void bind(EditorModel core, Runnable listener) { model = core; changed = listener; render(); }

    void attachHighlight(HighlightController controller) { highlight = controller; controller.attach(this); }

    void render() {
        writing = true;
        String text = model.getText();
        boolean replaced = false;
        if (!getText().toString().equals(text)) { getText().replace(0, length(), text); replaced = true; }
        setSelection(model.getSelectionStart(), model.getSelectionEnd());
        writing = false;
        // 整体回写会清掉 span，需要重扫；普通按键走 TextEdits 的三元组增量。
        if (replaced && highlight != null) highlight.reload();
    }

    void sync(boolean finishComposition) {
        if (model == null || writing) return;
        if (finishComposition) {
            BaseInputConnection.removeComposingSpans(getText());
            batch = 0;
        }
        if (!finishComposition && (batch > 0 || platformBatch > 0 || BaseInputConnection.getComposingSpanStart(getText()) >= 0)) return;
        // 一次提交（含 IME 组合结束、长串输入）只产生一条历史，撤销整条还原。
        // shortcut: 每次 sync 独立成事务，连续按键仍是逐条历史；要合并连打需等输入空闲再结束事务。
        int[] change;
        model.beginTransaction();
        try { change = TextEdits.commit(model, getText().toString(), getSelectionStart(), getSelectionEnd()); }
        finally { model.endTransaction(); }
        if (change != null && highlight != null) highlight.onTextChanged(change[0], change[1], change[2]);
        if (!model.getText().equals(getText().toString())) render();
        if (changed != null) changed.run();
    }

    @Override protected void onScrollChanged(int left, int top, int oldLeft, int oldTop) {
        super.onScrollChanged(left, top, oldLeft, oldTop);
        if (highlight != null) highlight.onScroll();
    }

    @Override protected void onSelectionChanged(int start, int end) {
        super.onSelectionChanged(start, end);
        if (model != null && !writing && inputDepth == 0 && batch == 0 && platformBatch == 0 && getText().toString().equals(model.getText())) {
            String text = model.getText();
            int a = TextEdits.boundary(text, Math.min(start, end), false);
            int b = TextEdits.boundary(text, Math.max(start, end), true);
            model.setSelection(a, b);
        }
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
            if (keyCode == KeyEvent.KEYCODE_Y || event.isShiftPressed()) model.redo(); else model.undo();
            render(); if (changed != null) changed.run(); return true;
        }
        return super.onKeyShortcut(keyCode, event);
    }
}
