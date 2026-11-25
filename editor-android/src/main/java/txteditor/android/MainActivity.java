package txteditor.android;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
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
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import txteditor.core.TextCodec;
import txteditor.core.TextKeys;
import txteditor.core.text.LanguageId;

public final class MainActivity extends AppCompatActivity {
    private static final int PICK_OPEN = 10, PICK_SAVE = 11;
    private EditorViewModel state;
    private CoreEditText editor;
    private EditText pattern, replacement;
    private HighlightController highlight;
    private RecyclerView preview;
    private MarkdownAdapter previewAdapter;
    private LanguageId appliedLanguage = LanguageId.PLAIN;
    /** 新建文档的类型；先记下来，未保存确认走完保存后仍用这一次的选择。 */
    private LanguageId newType = LanguageId.PLAIN;
    private int renderedVersion = -1, previewVersion = -1, previewPos;
    private boolean settingFields, replacing, previewShown, settingMode, previewTooLarge;
    /** 编辑停下 200ms 再重排预览；逐键解析长文档会拖慢输入。 */
    private final Runnable rebuildPreview = this::buildPreview;
    private String pendingUri;
    private androidx.appcompat.app.AlertDialog conflictDialog;

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
        editor.bind(state.core, () -> { captureFields(); state.edited(); refresh(); hideMatchCount(); });
        highlight = new HighlightController(this::updateDocumentStatus);
        editor.attachHighlight(highlight);
        preview = findViewById(R.id.preview);
        preview.setLayoutManager(new LinearLayoutManager(this));
        previewAdapter = new MarkdownAdapter(this);
        preview.setAdapter(previewAdapter);
        ((MaterialButtonToggleGroup) findViewById(R.id.mode_group)).addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (settingMode || !isChecked) return;
            setMode(checkedId == R.id.mode_preview);
        });
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
            public void afterTextChanged(Editable s) { if (!settingFields) { captureFields(); state.edited(); updateFindButtons(); hideMatchCount(); } }
        };
        pattern.addTextChangedListener(fields); replacement.addTextChangedListener(fields);
        findViewById(R.id.files).setOnClickListener(this::filesMenu);
        findViewById(R.id.save).setOnClickListener(v -> save(false));
        findViewById(R.id.undo).setOnClickListener(v -> history(false));
        findViewById(R.id.redo).setOnClickListener(v -> history(true));
        replacing = savedInstanceState != null ? savedInstanceState.getBoolean("replacing") : !state.replacement.isEmpty();
        if (savedInstanceState != null) {
            previewPos = savedInstanceState.getInt("previewPos", 0);
            pendingUri = savedInstanceState.getString("pendingUri");
            newType = LanguageId.parse(savedInstanceState.getString("newType"));
        }
        findViewById(R.id.search_toggle).setOnClickListener(v -> showSearch(!state.search));
        findViewById(R.id.close_search).setOnClickListener(v -> showSearch(false));
        findViewById(R.id.replace_toggle).setOnClickListener(v -> { replacing = !replacing; refresh(); });
        findViewById(R.id.find_next).setOnClickListener(v -> find(false));
        MaterialButton previous = findViewById(R.id.find_previous);
        previous.setText(TextKeys.PREVIOUS_MATCH);
        previous.setOnClickListener(v -> find(false, true));
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
                if (state.search) showSearch(false);
                else if (isPreview()) setMode(false);
                else guard(EditorViewModel.EXIT);
            }
        });
        refresh();
    }

    private void refresh() {
        if (state.language != appliedLanguage) {
            appliedLanguage = state.language;
            highlight.setLanguage(state.language);
        }
        if (renderedVersion != state.documentVersion) {
            editor.render();
            settingFields = true;
            pattern.setText(state.pattern); replacement.setText(state.replacement);
            settingFields = false;
            editor.post(() -> editor.scrollTo(0, state.scroll));
            renderedVersion = state.documentVersion;
            hideMatchCount();
        }
        applyMode();
        ((TextView) findViewById(R.id.filename)).setText(getString(R.string.document_title, state.name, state.core.isModified() ? " *" : ""));
        ((TextView) findViewById(R.id.subtitle)).setText(getString(R.string.subtitle_language, state.language.title));
        updateDocumentStatus();
        findViewById(R.id.search_panel).setVisibility(state.search && !isPreview() ? View.VISIBLE : View.GONE);
        findViewById(R.id.replace_panel).setVisibility(replacing ? View.VISIBLE : View.GONE);
        MaterialButton toggle = findViewById(R.id.replace_toggle);
        toggle.setText(replacing ? R.string.hide_replace : R.string.show_replace);
        toggle.setIconResource(replacing ? R.drawable.ic_collapse : R.drawable.ic_expand);
        ViewCompat.setStateDescription(findViewById(R.id.search_toggle), getString(state.search ? R.string.state_expanded : R.string.state_collapsed));
        ViewCompat.setStateDescription(toggle, getString(replacing ? R.string.state_expanded : R.string.state_collapsed));
        findViewById(R.id.search_panel).post(this::fitSearchPanel);
        int[] controls = {R.id.save, R.id.files, R.id.search_toggle, R.id.close_search, R.id.replace_toggle, R.id.find_next, R.id.find_previous, R.id.replace_one, R.id.pattern, R.id.replacement, R.id.editor, R.id.mode_source, R.id.mode_preview};
        for (int id : controls) findViewById(id).setEnabled(!state.busy);
        updateFindButtons();
        findViewById(R.id.undo).setEnabled(!state.busy && state.core.canUndo());
        findViewById(R.id.redo).setEnabled(!state.busy && state.core.canRedo());
        if (!state.conflictUri.isEmpty() && (conflictDialog == null || !conflictDialog.isShowing())) {
            Uri target = Uri.parse(state.conflictUri);
            conflictDialog = new MaterialAlertDialogBuilder(this)
                    .setMessage(getString(R.string.file_conflict, state.name))
                    .setPositiveButton(R.string.confirm_overwrite, (d, w) -> {
                        state.conflictUri = "";
                        state.save(target, true);
                    })
                    .setNeutralButton(R.string.save_as, (d, w) -> {
                        state.conflictUri = "";
                        save(true);
                    })
                    .setNegativeButton(R.string.cancel, (d, w) -> cancelConflict())
                    .setOnCancelListener(d -> cancelConflict()).show();
        }
        if (!state.message.isEmpty()) {
            String message = state.message; state.message = "";
            boolean failed = state.saveFailed; state.saveFailed = false;
            if (failed) new MaterialAlertDialogBuilder(this).setTitle(R.string.save_incomplete)
                    .setMessage(message).setPositiveButton(R.string.save_as, (d, w) -> save(true))
                    .setNegativeButton(R.string.continue_editing, null).show();
            else Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        }
        if (!state.busy && state.readyAction != EditorViewModel.NONE) {
            int action = state.readyAction; state.readyAction = EditorViewModel.NONE; execute(action);
        }
    }

    private void cancelConflict() {
        state.conflictUri = "";
        state.afterSave = EditorViewModel.NONE;
    }

    private void updateDocumentStatus() {
        TextView status = findViewById(R.id.status);
        if (state.busy) { status.setText(R.string.file_busy); return; }
        String stats = getString(R.string.document_status, state.core.getCodePointCount(), state.core.getLineCount(),
                getString(state.core.isModified() ? R.string.unsaved : R.string.saved));
        status.setText(highlight.isLimited() ? TextKeys.STYLING_TOO_LARGE + "  ·  " + stats : stats);
    }

    private boolean isPreview() {
        return state.language == LanguageId.MARKDOWN && state.viewMode == EditorViewModel.PREVIEW;
    }

    /** 源码常驻，Markdown 预览作为同屏第二块；编辑时预览按 textVersion 防抖重排。 */
    private void applyMode() {
        boolean markdown = state.language == LanguageId.MARKDOWN;
        boolean live = isPreview();
        findViewById(R.id.mode_group).setVisibility(markdown ? View.VISIBLE : View.GONE);
        findViewById(R.id.preview).setVisibility(live ? View.VISIBLE : View.GONE);
        int checked = live ? R.id.mode_preview : R.id.mode_source;
        MaterialButtonToggleGroup group = findViewById(R.id.mode_group);
        if (group.getCheckedButtonId() != checked) {
            settingMode = true;
            group.check(checked);
            settingMode = false;
        }
        if (live && previewVersion != state.textVersion) {
            preview.removeCallbacks(rebuildPreview);
            preview.postDelayed(rebuildPreview, 200);
        }
        if (!live && previewShown) editor.post(() -> editor.scrollTo(0, state.scroll));
        previewShown = live;
    }

    private void buildPreview() {
        if (!isPreview()) return;
        previewVersion = state.textVersion;
        if (preview.getVisibility() == View.VISIBLE && previewAdapter.getItemCount() > 0) savePreviewScroll();
        String text = state.core.getText();
        if (TextCodec.exceedsUtf8Limit(text, TextCodec.STYLING_LIMIT_BYTES)) {
            previewAdapter.setDocument(null);
            if (!previewTooLarge) Toast.makeText(this, TextKeys.STYLING_TOO_LARGE, Toast.LENGTH_LONG).show();
            previewTooLarge = true;
            return;
        }
        previewTooLarge = false;
        int version = previewVersion;
        state.preview(text, document -> {
            if (isFinishing() || isDestroyed() || version != state.textVersion) return;
            previewAdapter.setDocument(document);
            preview.scrollToPosition(previewPos);
        });
    }

    private void savePreviewScroll() {
        RecyclerView.LayoutManager manager = preview.getLayoutManager();
        if (manager instanceof LinearLayoutManager) previewPos = ((LinearLayoutManager) manager).findFirstVisibleItemPosition();
    }

    /** 切换源码 / 预览：光标、选区和滚动位置都留在核心与 state 里，回来时原样恢复。 */
    private void setMode(boolean preview) {
        if (state.busy || settingMode) return;
        if (!preview) savePreviewScroll();
        editor.sync(true);
        captureFields();
        state.viewMode = preview ? EditorViewModel.PREVIEW : EditorViewModel.SOURCE;
        state.edited();
        refresh();
        if (preview) ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(editor.getWindowToken(), 0);
        else editor.requestFocus();
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
        findViewById(R.id.subtitle).setVisibility(compact || root.getWidth() < 300 * density ? View.GONE : View.VISIBLE);
        findViewById(R.id.status).setVisibility(compact ? View.GONE : View.VISIBLE);
        // 预览分屏时源码仍可编辑，撤销与查找保持可用。
        boolean hideActions = compact && (landscape || height < 240 * density);
        findViewById(R.id.edit_actions).setVisibility(hideActions ? View.GONE : View.VISIBLE);
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
        View previewView = findViewById(R.id.preview);
        LinearLayout.LayoutParams second = (LinearLayout.LayoutParams) previewView.getLayoutParams();
        if (second.width != bodyWidth || second.height != bodyHeight) {
            second.width = bodyWidth; second.height = bodyHeight; previewView.setLayoutParams(second);
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
        state.scroll = editor.getScrollY(); // 编辑区常驻，预览分屏时滚动位置照常记录
    }

    private void filesMenu(View anchor) {
        editor.sync(true);
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, R.string.new_document_item); menu.getMenu().add(0, 2, 1, R.string.open_item);
        // 最近打开按时间倒序（RecentFiles 写入时已置顶），直接续用上次的 SAF 授权。
        final java.util.List<RecentFiles.Entry> recents = RecentFiles.read(new java.io.File(getFilesDir(), RecentFiles.NAME));
        if (!recents.isEmpty()) {
            android.view.SubMenu recent = menu.getMenu().addSubMenu(0, 9, 2, R.string.recent_files);
            for (int i = 0; i < recents.size(); i++) recent.add(0, 100 + i, i, recents.get(i).name);
        }
        menu.getMenu().add(0, 3, 3, R.string.save_as); menu.getMenu().add(0, 4, 4, R.string.close_document);
        menu.getMenu().add(0, 10, 5, TextKeys.GO_TO_LINE);
        if (findViewById(R.id.edit_actions).getVisibility() == View.GONE) {
            if (!isPreview()) {
                menu.getMenu().add(0, 5, 5, R.string.undo).setEnabled(state.core.canUndo());
                menu.getMenu().add(0, 6, 6, R.string.redo).setEnabled(state.core.canRedo());
                menu.getMenu().add(0, 7, 7, state.search ? R.string.search_panel_close : R.string.search_panel_open);
            }
            if (state.language == LanguageId.MARKDOWN) {
                menu.getMenu().add(0, 8, 8, isPreview() ? R.string.preview_hide : R.string.preview_show);
            }
        }
        menu.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id >= 100) {
                if (id - 100 < recents.size()) { pendingUri = recents.get(id - 100).uri; guard(EditorViewModel.OPEN); }
                return true;
            }
            switch (id) {
                case 1: newTypeDialog(); break;
                case 2: pendingUri = null; guard(EditorViewModel.OPEN); break;
                case 3: save(true); break;
                case 4: guard(EditorViewModel.EXIT); break;
                case 5: history(false); break;
                case 6: history(true); break;
                case 7: showSearch(!state.search); break;
                case 8: setMode(!isPreview()); break;
                case 10: goToLine(); break;
                default: return false;
            }
            return true;
        });
        menu.show();
    }

    /** 新建可选类型：默认后缀与高亮语言一起定下来，选完仍走未保存确认。 */
    private void newTypeDialog() {
        LanguageId[] types = LanguageId.values();
        String[] labels = new String[types.length];
        for (int i = 0; i < types.length; i++) labels[i] = getString(R.string.new_type_item, types[i].title, types[i].defaultSuffix);
        new MaterialAlertDialogBuilder(this).setTitle(R.string.new_document_title)
                .setItems(labels, (dialog, which) -> { newType = types[which]; guard(EditorViewModel.NEW); }).show();
    }

    private void history(boolean redo) {
        editor.sync(true);
        if (redo) state.core.redo(); else state.core.undo();
        hideMatchCount();
        editor.render(); captureFields(); state.edited(); refresh();
    }

    /** 空查找词时禁用「下一处 / 替换」，与桌面端一致；输入法的直接提交仍由 find() 兜住。 */
    private void updateFindButtons() {
        boolean ready = !state.busy && !state.pattern.isEmpty();
        findViewById(R.id.find_next).setEnabled(ready);
        findViewById(R.id.find_previous).setEnabled(ready);
        findViewById(R.id.replace_one).setEnabled(ready);
    }

    private void hideMatchCount() { findViewById(R.id.match_count).setVisibility(View.GONE); }

    private void find(boolean replace) { find(replace, false); }

    private void find(boolean replace, boolean previous) {
        if (state.busy) return;
        editor.sync(true); captureFields();
        String needle = state.pattern;
        if (needle.isEmpty()) { Toast.makeText(this, R.string.need_pattern, Toast.LENGTH_SHORT).show(); return; }
        try {
            int start = state.core.getSelectionStart(), end = state.core.getSelectionEnd();
            if (!replace || !state.core.getText().substring(start, end).equals(needle)) {
                start = previous ? state.core.findPrevious(needle, start) : state.core.findNext(needle, end);
                if (start < 0) {
                    showMatchCount(0);
                    return;
                }
                end = start + needle.length(); state.core.setSelection(start, end);
            }
            if (replace) state.core.replace(start, end, state.replacement);
            editor.render(); editor.requestFocus(); state.edited(); refresh();
            showMatchCount(state.core.countMatches(needle));
        } catch (IllegalArgumentException error) { Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show(); }
    }

    private void showMatchCount(int count) {
        TextView result = findViewById(R.id.match_count);
        result.setText(TextKeys.of(TextKeys.MATCH_COUNT, count));
        result.setVisibility(View.VISIBLE);
    }

    private void goToLine() {
        if (state.busy) return;
        editor.sync(true);
        String hint = TextKeys.of(TextKeys.LINE_RANGE, state.core.getLineCount());
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint(hint);
        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(TextKeys.GO_TO_LINE).setView(input)
                .setPositiveButton(TextKeys.GO_TO_LINE, null).setNegativeButton(R.string.cancel, null).show();
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            int offset = -1;
            try { offset = state.core.lineStart(Integer.parseInt(input.getText().toString().trim())); }
            catch (NumberFormatException invalid) { /* 与越界行号使用同一范围提示。 */ }
            if (offset < 0) { input.setError(hint); return; }
            dialog.dismiss();
            state.core.setSelection(offset, offset);
            editor.render(); editor.requestFocus(); editor.bringPointIntoView(offset); captureFields();
            state.persist();
        });
    }

    private void guard(int action) {
        if (state.busy) return;
        editor.sync(true); captureFields();
        if (!state.core.isModified()) { execute(action); return; }
        new MaterialAlertDialogBuilder(this).setTitle(R.string.leave_title).setMessage(getString(R.string.leave_message, state.name))
                .setPositiveButton(R.string.save, (d, w) -> { state.afterSave = action; save(false); })
                .setNegativeButton(R.string.discard, (d, w) -> execute(action)).setNeutralButton(R.string.cancel, null).show();
    }

    private void execute(int action) {
        if (action == EditorViewModel.NEW) state.newDocument(newType);
        else if (action == EditorViewModel.OPEN) {
            String uri = pendingUri; pendingUri = null;
            if (uri == null) launchPicker(false); else state.open(Uri.parse(uri));
        } else if (action == EditorViewModel.EXIT) {
            // 放弃退出后清除未保存内容；普通退到后台仍恢复草稿。
            state.newDocument(); finish();
        }
    }

    private void save(boolean asNew) {
        if (state.busy) return;
        editor.sync(true); captureFields();
        if (TextCodec.exceedsUtf8Limit(editor.getText(), TextCodec.FILE_LIMIT_BYTES)) {
            state.afterSave = EditorViewModel.NONE;
            Toast.makeText(this, TextKeys.TOO_LARGE_TO_SAVE, Toast.LENGTH_LONG).show();
            return;
        }
        if (asNew || state.uri.isEmpty()) launchPicker(true); else state.save(Uri.parse(state.uri));
    }

    private void launchPicker(boolean save) {
        Intent intent = new Intent(save ? Intent.ACTION_CREATE_DOCUMENT : Intent.ACTION_OPEN_DOCUMENT);
        // */* 而不是 text/plain：不少提供者给 .java/.c 的 MIME 是 application/octet-stream，
        // 用 text/plain 过滤会让这些文件在选择器里直接消失；类型改由后缀判定。
        intent.addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        if (save) intent.putExtra(Intent.EXTRA_TITLE, state.name);
        try { startActivityForResult(intent, save ? PICK_SAVE : PICK_OPEN); }
        catch (ActivityNotFoundException error) {
            state.afterSave = EditorViewModel.NONE;
            Toast.makeText(this, R.string.no_file_picker, Toast.LENGTH_LONG).show();
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
        if (previewShown) savePreviewScroll();
        out.putBoolean("replacing", replacing);
        out.putInt("previewPos", previewPos);
        out.putString("pendingUri", pendingUri);
        out.putString("newType", newType.name());
        super.onSaveInstanceState(out);
    }

    @Override protected void onStop() {
        if (!state.busy) { editor.sync(true); captureFields(); state.persist(); }
        super.onStop();
    }

    @Override protected void onDestroy() {
        state.observer = null;
        highlight.shutdown();
        super.onDestroy();
    }
}
