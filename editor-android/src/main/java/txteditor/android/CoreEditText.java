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

    void render() {
        writing = true;
        String text = model.getText();
        if (!getText().toString().equals(text)) getText().replace(0, length(), text);
        setSelection(model.getSelectionStart(), model.getSelectionEnd());
        writing = false;
    }

    void sync(boolean finishComposition) {
        if (model == null || writing) return;
        if (finishComposition) {
            BaseInputConnection.removeComposingSpans(getText());
            batch = 0;
        }
        if (!finishComposition && (batch > 0 || platformBatch > 0 || BaseInputConnection.getComposingSpanStart(getText()) >= 0)) return;
        TextEdits.commit(model, getText().toString(), getSelectionStart(), getSelectionEnd());
        if (!model.getText().equals(getText().toString())) render();
        if (changed != null) changed.run();
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
