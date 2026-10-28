package txteditor.android;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import androidx.appcompat.app.AppCompatActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public final class MainActivity extends AppCompatActivity {
    private static final int PICK_OPEN = 10, PICK_SAVE = 11;
    private EditorViewModel state;
    private CoreEditText editor;
    private EditText pattern, replacement;
    private int renderedVersion = -1;
    private boolean settingFields;
    private boolean replacing;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        state = new ViewModelProvider(this, new ViewModelProvider.Factory() {
            @Override public <T extends ViewModel> T create(Class<T> type) {
                return type.cast(new EditorViewModel(getApplicationContext()));
            }
        }).get(EditorViewModel.class);
        setContentView(R.layout.main);
        View root = findViewById(R.id.root);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime() | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
        root.getViewTreeObserver().addOnGlobalLayoutListener(this::fitSearchPanel);
        editor = findViewById(R.id.editor);
        pattern = findViewById(R.id.pattern); replacement = findViewById(R.id.replacement);
        editor.bind(state.core, () -> { captureFields(); state.edited(); refresh(); });
        ActionMode.Callback noNativeHistory = new ActionMode.Callback() {
            public boolean onCreateActionMode(ActionMode mode, Menu menu) { return true; }
            public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
                menu.removeItem(android.R.id.undo); menu.removeItem(android.R.id.redo); return false;
            }
            public boolean onActionItemClicked(ActionMode mode, MenuItem item) { return false; }
            public void onDestroyActionMode(ActionMode mode) { }
        };
        editor.setCustomSelectionActionModeCallback(noNativeHistory);
        editor.setCustomInsertionActionModeCallback(noNativeHistory);
        TextWatcher fields = new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) { }
            public void afterTextChanged(Editable s) { if (!settingFields) { captureFields(); state.edited(); } }
        };
        pattern.addTextChangedListener(fields); replacement.addTextChangedListener(fields);
        findViewById(R.id.files).setOnClickListener(this::filesMenu);
        findViewById(R.id.save).setOnClickListener(v -> save(false));
        findViewById(R.id.undo).setOnClickListener(v -> history(false));
        findViewById(R.id.redo).setOnClickListener(v -> history(true));
        replacing = savedInstanceState != null ? savedInstanceState.getBoolean("replacing") : !state.replacement.isEmpty();
        findViewById(R.id.search_toggle).setOnClickListener(v -> showSearch(!state.search));
        findViewById(R.id.close_search).setOnClickListener(v -> showSearch(false));
        findViewById(R.id.replace_toggle).setOnClickListener(v -> { replacing = !replacing; refresh(); });
        findViewById(R.id.find_next).setOnClickListener(v -> find(false));
        findViewById(R.id.replace_one).setOnClickListener(v -> find(true));
        pattern.setOnEditorActionListener((v, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            find(false); return true;
        });
        replacement.setOnEditorActionListener((v, action, event) -> {
            if (action != EditorInfo.IME_ACTION_DONE) return false;
            find(true); return true;
        });
        state.observer = this::refresh;
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (state.search) showSearch(false); else guard(EditorViewModel.EXIT);
            }
        });
        refresh();
    }

    private void refresh() {
        if (renderedVersion != state.documentVersion) {
            editor.render();
            settingFields = true;
            pattern.setText(state.pattern); replacement.setText(state.replacement);
            settingFields = false;
            editor.post(() -> editor.scrollTo(0, state.scroll));
            renderedVersion = state.documentVersion;
        }
        ((TextView) findViewById(R.id.filename)).setText(getString(R.string.document_title, state.name, state.core.isModified() ? " *" : ""));
        ((TextView) findViewById(R.id.status)).setText(state.busy ? getString(R.string.file_busy) :
                getString(R.string.document_status, state.core.getCodePointCount(), state.core.getLineCount(),
                        getString(state.core.isModified() ? R.string.unsaved : R.string.saved)));
        findViewById(R.id.search_panel).setVisibility(state.search ? View.VISIBLE : View.GONE);
        findViewById(R.id.replace_panel).setVisibility(replacing ? View.VISIBLE : View.GONE);
        MaterialButton toggle = findViewById(R.id.replace_toggle);
        toggle.setText(replacing ? R.string.hide_replace : R.string.show_replace);
        toggle.setIconResource(replacing ? R.drawable.ic_collapse : R.drawable.ic_expand);
        ViewCompat.setStateDescription(findViewById(R.id.search_toggle), state.search ? "已展开" : "已收起");
        ViewCompat.setStateDescription(toggle, replacing ? "已展开" : "已收起");
        findViewById(R.id.search_panel).post(this::fitSearchPanel);
        int[] controls = {R.id.save, R.id.files, R.id.search_toggle, R.id.close_search, R.id.replace_toggle, R.id.find_next, R.id.replace_one, R.id.pattern, R.id.replacement, R.id.editor};
        for (int id : controls) findViewById(id).setEnabled(!state.busy);
        findViewById(R.id.undo).setEnabled(!state.busy && state.core.canUndo());
        findViewById(R.id.redo).setEnabled(!state.busy && state.core.canRedo());
        if (!state.message.isEmpty()) {
            String message = state.message; state.message = "";
            if (message.startsWith("保存失败")) new MaterialAlertDialogBuilder(this).setTitle("保存未完成")
                    .setMessage(message).setPositiveButton("另存为", (d, w) -> save(true)).setNegativeButton("继续编辑", null).show();
            else Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        }
        if (!state.busy && state.readyAction != EditorViewModel.NONE) {
            int action = state.readyAction; state.readyAction = EditorViewModel.NONE; execute(action);
        }
    }

    private void showSearch(boolean show) {
        editor.sync(true); state.search = show;
        if (!show) {
            editor.requestFocus();
            ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(editor.getWindowToken(), 0);
        }
        refresh(); state.edited();
        if (show) pattern.requestFocus();
    }

    private void fitSearchPanel() {
        View root = findViewById(R.id.root), panel = findViewById(R.id.search_panel);
        int height = root.getHeight() - root.getPaddingTop() - root.getPaddingBottom();
        if (height <= 0) return;
        float density = getResources().getDisplayMetrics().density;
        boolean compact = height < 300 * density;
        boolean landscape = getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        findViewById(R.id.subtitle).setVisibility(compact ? View.GONE : View.VISIBLE);
        findViewById(R.id.status).setVisibility(compact ? View.GONE : View.VISIBLE);
        findViewById(R.id.edit_actions).setVisibility(compact && (landscape || height < 240 * density) ? View.GONE : View.VISIBLE);
        View toolbar = findViewById(R.id.toolbar);
        // A very short IME window needs the remaining height for actual text lines.
        toolbar.setVisibility(height < 160 * density ? View.GONE : View.VISIBLE);
        int toolbarHeight = Math.round((compact ? 48 : 64) * density);
        if (toolbar.getMinimumHeight() != toolbarHeight) toolbar.setMinimumHeight(toolbarHeight);
        View toolbarContent = findViewById(R.id.toolbar_content);
        int toolbarPadding = Math.round((compact ? 0 : 8) * density);
        if (toolbarContent.getPaddingTop() != toolbarPadding)
            toolbarContent.setPadding(toolbarContent.getPaddingLeft(), toolbarPadding, toolbarContent.getPaddingRight(), toolbarPadding);
        int vertical = Math.round((height < 200 * density ? 2 : compact ? 8 : 20) * density);
        if (editor.getPaddingTop() != vertical) editor.setPadding(editor.getPaddingLeft(), vertical, editor.getPaddingRight(), vertical);
        LinearLayout workspace = findViewById(R.id.workspace);
        workspace.setOrientation(landscape ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        LinearLayout.LayoutParams body = (LinearLayout.LayoutParams) editor.getLayoutParams();
        int bodyWidth = landscape ? 0 : LinearLayout.LayoutParams.MATCH_PARENT;
        int bodyHeight = landscape ? LinearLayout.LayoutParams.MATCH_PARENT : 0;
        if (body.width != bodyWidth || body.height != bodyHeight) {
            body.width = bodyWidth; body.height = bodyHeight; editor.setLayoutParams(body);
        }
        int panelWidth = landscape ? Math.min(Math.round(320 * density), (int) (workspace.getWidth() * 0.45f)) : LinearLayout.LayoutParams.MATCH_PARENT;
        int panelHeight = landscape ? LinearLayout.LayoutParams.MATCH_PARENT : Math.min(findViewById(R.id.search_content).getHeight(),
                Math.max(Math.round(48 * density), workspace.getHeight() - Math.round(96 * density)));
        if (panelHeight != 0 && (panel.getLayoutParams().width != panelWidth || panel.getLayoutParams().height != panelHeight)) {
            android.view.ViewGroup.LayoutParams params = panel.getLayoutParams();
            params.width = panelWidth; params.height = panelHeight; panel.setLayoutParams(params);
        }
    }

    private void captureFields() {
        state.pattern = pattern.getText().toString(); state.replacement = replacement.getText().toString();
        state.scroll = editor.getScrollY();
    }

    private void filesMenu(View anchor) {
        editor.sync(true);
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, "新建"); menu.getMenu().add(0, 2, 1, "打开");
        menu.getMenu().add(0, 3, 2, "另存为"); menu.getMenu().add(0, 4, 3, "关闭");
        if (findViewById(R.id.edit_actions).getVisibility() == View.GONE) {
            menu.getMenu().add(0, 5, 4, "撤销").setEnabled(state.core.canUndo());
            menu.getMenu().add(0, 6, 5, "重做").setEnabled(state.core.canRedo());
            menu.getMenu().add(0, 7, 6, state.search ? "关闭查找" : "查找与替换");
        }
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: guard(EditorViewModel.NEW); break;
                case 2: guard(EditorViewModel.OPEN); break;
                case 3: save(true); break;
                case 4: guard(EditorViewModel.EXIT); break;
                case 5: history(false); break;
                case 6: history(true); break;
                case 7: showSearch(!state.search); break;
                default: return false;
            }
            return true;
        });
        menu.show();
    }

    private void history(boolean redo) {
        editor.sync(true);
        if (redo) state.core.redo(); else state.core.undo();
        editor.render(); captureFields(); state.edited(); refresh();
    }

    private void find(boolean replace) {
        editor.sync(true); captureFields();
        String needle = state.pattern;
        if (needle.isEmpty()) { Toast.makeText(this, "请输入查找词", Toast.LENGTH_SHORT).show(); return; }
        try {
            int start = state.core.getSelectionStart(), end = state.core.getSelectionEnd();
            if (!replace || !state.core.getText().substring(start, end).equals(needle)) {
                start = state.core.findNext(needle, end);
                if (start < 0) { Toast.makeText(this, "未找到匹配", Toast.LENGTH_SHORT).show(); return; }
                end = start + needle.length(); state.core.setSelection(start, end);
            }
            if (replace) state.core.replace(start, end, state.replacement);
            editor.render(); editor.requestFocus(); state.edited(); refresh();
        } catch (IllegalArgumentException error) { Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show(); }
    }

    private void guard(int action) {
        if (state.busy) return;
        editor.sync(true); captureFields();
        if (!state.core.isModified()) { execute(action); return; }
        new MaterialAlertDialogBuilder(this).setTitle("保存更改？").setMessage(state.name + " 有未保存的内容")
                .setPositiveButton("保存", (d, w) -> { state.afterSave = action; save(false); })
                .setNegativeButton("放弃", (d, w) -> execute(action)).setNeutralButton("取消", null).show();
    }

    private void execute(int action) {
        if (action == EditorViewModel.NEW) state.newDocument();
        else if (action == EditorViewModel.OPEN) launchPicker(false);
        else if (action == EditorViewModel.EXIT) {
            // 放弃退出后清除未保存内容；普通退到后台仍恢复草稿。
            state.newDocument(); finish();
        }
    }

    private void save(boolean asNew) {
        if (state.busy) return;
        editor.sync(true); captureFields();
        if (asNew || state.uri.isEmpty()) launchPicker(true); else state.save(Uri.parse(state.uri));
    }

    private void launchPicker(boolean save) {
        Intent intent = new Intent(save ? Intent.ACTION_CREATE_DOCUMENT : Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE).setType("text/plain");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        if (save) intent.putExtra(Intent.EXTRA_TITLE, state.name);
        try { startActivityForResult(intent, save ? PICK_SAVE : PICK_OPEN); }
        catch (ActivityNotFoundException error) {
            state.afterSave = EditorViewModel.NONE;
            Toast.makeText(this, "系统没有可用的文件选择器", Toast.LENGTH_LONG).show();
        }
    }

    @android.annotation.SuppressLint("WrongConstant") // Only the READ/WRITE grant bits below reach ContentResolver.
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_OPEN && request != PICK_SAVE) return;
        if (result != RESULT_OK || data == null || data.getData() == null) { state.afterSave = EditorViewModel.NONE; return; }
        Uri uri = data.getData();
        if ((data.getFlags() & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0) {
            try { getContentResolver().takePersistableUriPermission(uri,
                    data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION)); }
            catch (SecurityException ignored) { /* 临时授权仍可用，失效后提示重新选择。 */ }
        }
        if (request == PICK_OPEN) state.open(uri); else state.save(uri);
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        editor.sync(true); captureFields();
        out.putBoolean("replacing", replacing);
        super.onSaveInstanceState(out);
    }

    @Override protected void onStop() {
        if (!state.busy) { editor.sync(true); captureFields(); state.persist(); }
        super.onStop();
    }

    @Override protected void onDestroy() {
        state.observer = null;
        super.onDestroy();
    }
}
