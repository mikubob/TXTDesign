package txteditor.android;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.EditText;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;
import txteditor.core.EditorModel;
import txteditor.core.SessionSnapshot;
import txteditor.core.TextCodec;
import txteditor.core.text.LanguageId;

public final class DeviceTests extends Instrumentation {
    private int checks;
    private Bundle arguments;
    private MainActivity activity;
    private EditorViewModel state;
    private CoreEditText editor;
    @Override public void onCreate(Bundle args) { super.onCreate(args); arguments = args; start(); }
    private void equal(Object expected, Object actual) {
        checks++; if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
    private void ui(Runnable action) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        runOnMainSync(() -> { try { action.run(); } catch (Throwable error) { failure.set(error); } });
        if (failure.get() != null) throw new AssertionError(failure.get());
        waitForIdleSync();
    }
    private void bind(MainActivity current) throws Exception {
        activity = current;
        Field field = MainActivity.class.getDeclaredField("state"); field.setAccessible(true);
        state = (EditorViewModel) field.get(activity); editor = activity.findViewById(R.id.editor);
    }
    private void ready() throws Exception {
        for (int i = 0; i < 100; i++) {
            boolean[] busy = {true}; ui(() -> busy[0] = state.busy);
            if (!busy[0]) return;
            Thread.sleep(50);
        }
        throw new AssertionError("文件任务未完成");
    }
    private void clickText(String text) throws Exception {
        for (int i = 0; i < 40; i++) {
            AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
            if (root != null) {
                for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(text)) {
                    if (text.contentEquals(node.getText())) {
                        AccessibilityNodeInfo clickable = node;
                        while (clickable != null && !clickable.isClickable()) clickable = clickable.getParent();
                        if (clickable != null && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                            waitForIdleSync(); return;
                        }
                    }
                }
            }
            Thread.sleep(50);
        }
        throw new AssertionError("未找到系统按钮：" + text);
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            ActivityMonitor previewMonitor = arguments.containsKey("preview") ? addMonitor(MainActivity.class.getName(), null, false) : null;
            Intent intent = new Intent(Intent.ACTION_MAIN).setClassName(getTargetContext(), "txteditor.android.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            bind((MainActivity) startActivitySync(intent)); ready();
            if (previewMonitor != null) { preview(previewMonitor); removeMonitor(previewMonitor); }
            else if ("true".equals(arguments.getString("restore"))) {
                ui(() -> {
                    equal("进程恢复😀\n", editor.getText().toString()); equal(true, state.core.isModified());
                    equal(false, state.core.canUndo()); equal(2, state.core.getLineCount());
                    equal("content://txteditor.android.tests.docs/saved", state.uri);
                });
            } else exercise();
            result.putString("stream", "DeviceTests passed: " + checks + " checks\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", "DeviceTests FAILED: " + android.util.Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void exercise() throws Exception {
        ui(() -> {
            equal(true, activity.findViewById(R.id.toolbar) instanceof com.google.android.material.appbar.MaterialToolbar);
            equal(true, activity.findViewById(R.id.save) instanceof com.google.android.material.button.MaterialButton);
            equal(true, activity.findViewById(R.id.pattern) instanceof com.google.android.material.textfield.TextInputEditText);
            for (int id : new int[]{R.id.undo, R.id.redo, R.id.files}) {
                android.view.View control = activity.findViewById(id);
                equal(true, control.getWidth() >= 48 * activity.getResources().getDisplayMetrics().density);
                equal(true, control.getContentDescription() != null);
            }
            activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
            state.search = false; state.pattern = ""; state.replacement = "";
            if (activity.findViewById(R.id.replace_panel).getVisibility() == android.view.View.VISIBLE)
                activity.findViewById(R.id.replace_toggle).performClick();
            state.newDocument(); editor.setShowSoftInputOnFocus(false);
            ((android.view.inputmethod.InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE))
                    .hideSoftInputFromWindow(editor.getWindowToken(), 0);
        });
        // Keep the installed keyboard from asynchronously finishing the synthetic test connection.
        Thread.sleep(500); waitForIdleSync();
        InputConnection[] connection = new InputConnection[1];
        ui(() -> {
            editor.requestFocus(); connection[0] = editor.onCreateInputConnection(new EditorInfo());
            connection[0].beginBatchEdit(); connection[0].setComposingText("zhong", 1); connection[0].endBatchEdit();
        });
        ui(() -> equal("", state.core.getText()));
        ui(() -> connection[0].setComposingText("中", 1));
        ui(() -> equal("", state.core.getText()));
        ui(() -> connection[0].finishComposingText());
        ui(() -> {
            equal("中", state.core.getText()); activity.findViewById(R.id.undo).performClick(); equal("", editor.getText().toString());
            activity.findViewById(R.id.redo).performClick(); equal("中", editor.getText().toString());
        });
        ui(() -> { editor.setSelection(0, 1); editor.getText().replace(0, 1, "中文😀\nabc abc"); });
        ui(() -> { equal("中文😀\nabc abc", state.core.getText()); equal(11, state.core.getCodePointCount()); });
        ui(() -> {
            activity.findViewById(R.id.search_toggle).performClick();
            equal(android.view.View.VISIBLE, activity.findViewById(R.id.search_panel).getVisibility());
            equal(android.view.View.GONE, activity.findViewById(R.id.replace_panel).getVisibility());
            activity.findViewById(R.id.replace_toggle).performClick();
            ((EditText) activity.findViewById(R.id.pattern)).setText("abc");
            ((EditText) activity.findViewById(R.id.replacement)).setText("测试");
            editor.setSelection(0); activity.findViewById(R.id.find_next).performClick();
            equal(5, state.core.getSelectionStart()); equal(8, state.core.getSelectionEnd());
            activity.findViewById(R.id.replace_one).performClick(); equal("中文😀\n测试 abc", state.core.getText());
            activity.findViewById(R.id.undo).performClick(); equal("中文😀\nabc abc", editor.getText().toString());
        });
        EditorModel previous = state.core;
        ActivityMonitor monitor = addMonitor(MainActivity.class.getName(), null, false);
        ui(() -> activity.setRequestedOrientation(activity.getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT
                ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT));
        Activity rotated = waitForMonitorWithTimeout(monitor, 5000); removeMonitor(monitor);
        if (rotated == null) throw new AssertionError("旋转重建未发生");
        bind((MainActivity) rotated); ready();
        ui(() -> {
            equal(previous, state.core); equal("中文😀\nabc abc", editor.getText().toString()); equal(true, state.core.canUndo());
            equal("abc", ((EditText) activity.findViewById(R.id.pattern)).getText().toString());
            equal(android.view.View.VISIBLE, activity.findViewById(R.id.replace_panel).getVisibility());
            equal(true, editor.getHeight() >= 90 * activity.getResources().getDisplayMetrics().density);
        });
        ui(() -> activity.getOnBackPressedDispatcher().onBackPressed());
        ui(() -> { equal(false, state.search); equal(false, activity.isFinishing()); });
        ui(() -> activity.getOnBackPressedDispatcher().onBackPressed()); clickText("取消");
        ui(() -> { equal(false, activity.isFinishing()); equal("中文😀\nabc abc", state.core.getText()); });
        ui(() -> {
            ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("fixture", "跨行\n😀"));
            editor.requestFocus(); editor.setSelection(0, editor.length()); editor.onTextContextMenuItem(android.R.id.paste);
        });
        ui(() -> equal("跨行\n😀", state.core.getText()));
        ui(() -> { editor.setSelection(0, 2); editor.onTextContextMenuItem(android.R.id.cut); });
        ui(() -> { equal("\n😀", state.core.getText()); activity.findViewById(R.id.undo).performClick(); equal("跨行\n😀", state.core.getText()); });
        ui(() -> activity.findViewById(R.id.files).performClick()); clickText("打开"); clickText("放弃");
        // The actual system SAF picker is cancelled; neither URI nor text may change.
        Thread.sleep(350); sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); waitForIdleSync();
        ui(() -> { equal("跨行\n😀", state.core.getText()); equal("", state.uri); });
        // Same fixed UTF-16 sequence published by editor-core/README.md, driven through the widget.
        ui(() -> {
            state.core.load("中文😀\nabc abc"); editor.render(); editor.setSelection(0, 2);
            editor.onCreateInputConnection(new EditorInfo()).commitText("新", 1);
        });
        ui(() -> {
            equal("新😀\nabc abc", state.core.getText()); equal(1, state.core.getSelectionStart());
            activity.findViewById(R.id.undo).performClick(); equal("中文😀\nabc abc", editor.getText().toString());
            equal(0, state.core.getSelectionStart()); equal(2, state.core.getSelectionEnd());
            activity.findViewById(R.id.redo).performClick(); editor.setSelection(0);
            activity.findViewById(R.id.search_toggle).performClick();
            activity.findViewById(R.id.find_next).performClick(); equal(4, state.core.getSelectionStart());
            ((EditText) activity.findViewById(R.id.replacement)).setText("中😀");
            activity.findViewById(R.id.replace_one).performClick(); equal("新😀\n中😀 abc", state.core.getText());
            equal(7, state.core.getSelectionStart()); activity.findViewById(R.id.undo).performClick();
            equal("新😀\nabc abc", state.core.getText()); equal(4, state.core.getSelectionStart()); equal(7, state.core.getSelectionEnd());
        });
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            ui(() -> { editor.setSelection(4, 7); editor.onCreateInputConnection(new EditorInfo()).commitText("新接口", 1, null); });
            ui(() -> {
                equal("新😀\n新接口 abc", state.core.getText()); activity.findViewById(R.id.undo).performClick();
                equal("新😀\nabc abc", state.core.getText()); equal(4, state.core.getSelectionStart()); equal(7, state.core.getSelectionEnd());
            });
        }
        ui(() -> { state.core.load("A😀B"); editor.render(); editor.setSelection(3); editor.getText().delete(2, 3); });
        ui(() -> { equal("AB", editor.getText().toString()); state.core.undo(); editor.render(); equal("A😀B", editor.getText().toString()); });
        ui(() -> {
            state.core.load(""); editor.render();
            for (int i = 0; i < 105; i++) { editor.getText().append("字"); editor.sync(true); }
            for (int i = 0; i < 100; i++) activity.findViewById(R.id.undo).performClick();
            equal("字字字字字", editor.getText().toString()); equal(false, state.core.canUndo());
            for (int i = 0; i < 100; i++) activity.findViewById(R.id.redo).performClick();
            equal(105, editor.length()); equal(false, state.core.canRedo());
        });
        ui(() -> state.open(Uri.parse("content://txteditor.android.tests.docs/valid"))); ready();
        ui(() -> { equal("中文😀\n第二行\n", editor.getText().toString()); equal(false, state.core.isModified()); });
        ui(() -> state.open(Uri.parse("content://txteditor.android.tests.docs/invalid"))); ready();
        ui(() -> equal("中文😀\n第二行\n", editor.getText().toString()));
        ui(() -> state.open(Uri.parse("content://txteditor.android.tests.docs/missing"))); ready();
        ui(() -> equal("中文😀\n第二行\n", state.core.getText()));
        ui(() -> { editor.getText().append("保存😀"); });
        String[] snapshot = new String[1]; ui(() -> snapshot[0] = state.core.getText());
        ui(() -> state.save(Uri.parse("content://txteditor.android.tests.docs/saved"))); ready();
        ui(() -> equal(false, state.core.isModified()));
        try (InputStream in = getTargetContext().getContentResolver().openInputStream(Uri.parse("content://txteditor.android.tests.docs/saved"))) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(); byte[] buf = new byte[256]; int n;
            while ((n = in.read(buf)) != -1) bytes.write(buf, 0, n);
            equal(snapshot[0], TextCodec.decodeUtf8(bytes.toByteArray()));
            equal(false, bytes.toByteArray()[0] == (byte) 0xEF);
        }
        ui(() -> { editor.getText().append("失败"); });
        ui(() -> state.save(Uri.parse("content://txteditor.android.tests.docs/fail"))); ready();
        ui(() -> {
            equal(true, state.core.isModified()); equal(snapshot[0] + "失败", state.core.getText());
            equal(true, new File(getTargetContext().getFilesDir(), "recovery.bin").exists());
            equal("content://txteditor.android.tests.docs/saved", state.uri);
        });
        // Finish with a durable dirty document, then the script force-stops and runs the restore case.
        ui(() -> { state.core.load(""); state.saved = ""; editor.render(); editor.getText().append("进程恢复😀\n"); });
        ui(() -> { editor.sync(true); state.persist(); });
        File draft = new File(getTargetContext().getFilesDir(), "draft.bin");
        Field executor = EditorViewModel.class.getDeclaredField("io"); executor.setAccessible(true);
        ((java.util.concurrent.ExecutorService) executor.get(state)).submit(() -> { }).get(5, java.util.concurrent.TimeUnit.SECONDS);
        equal("进程恢复😀\n", DraftStore.read(draft).snapshot.text);
        // 快照字段顺序经 AtomicFile 真实落盘再读回，字段不能串位。
        File probe = new File(getTargetContext().getFilesDir(), "draft-probe.bin");
        SessionSnapshot expected = new SessionSnapshot("快照😀", "旧", "草稿.txt", 1, 3, 42, "查", "换", true);
        DraftStore.write(probe, expected, "content://example/doc/9");
        DraftFormat.Decoded stored = DraftStore.read(probe);
        equal("content://example/doc/9", stored.uri); equal("快照😀", stored.snapshot.text);
        equal("旧", stored.snapshot.savedText); equal("草稿.txt", stored.snapshot.fileName);
        equal("查", stored.snapshot.pattern); equal("换", stored.snapshot.replacement);
        equal(1, stored.snapshot.selectionStart); equal(3, stored.snapshot.selectionEnd);
        equal(42, stored.snapshot.scroll); equal(true, stored.snapshot.searchOpen);
        DraftStore.delete(probe); equal(false, probe.exists());
    }

    private void preview(ActivityMonitor monitor) throws Exception {
        boolean landscape = arguments.getString("preview").contains("landscape");
        int orientation = landscape ? Configuration.ORIENTATION_LANDSCAPE : Configuration.ORIENTATION_PORTRAIT;
        ui(() -> activity.setRequestedOrientation(landscape ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT));
        for (int i = 0; i < 100; i++) {
            Thread.sleep(50); waitForIdleSync();
            if (monitor.getLastActivity() != activity) bind((MainActivity) monitor.getLastActivity());
            if (activity.getResources().getConfiguration().orientation == orientation) break;
        }
        ready();
        ui(() -> equal(orientation, activity.getResources().getConfiguration().orientation));
        String mode = arguments.getString("preview");
        boolean javaMode = mode.startsWith("java"), markdownMode = mode.startsWith("markdown");
        ui(() -> {
            state.newDocument(); state.search = false;
            state.name = javaMode ? "示例.java" : markdownMode ? "说明.md" : "随手记.txt";
            state.language = javaMode ? LanguageId.JAVA : markdownMode ? LanguageId.MARKDOWN : LanguageId.PLAIN;
            state.core.load(javaMode ? JAVA_SAMPLE : markdownMode ? MD_SAMPLE
                    : "简文本，让思绪流动\n\n今天，留一点时间给文字。\n在这里记录灵感、整理课程笔记，或写下一段还没成形的想法。\n\n把注意力交给内容\n• 中文与表情自然输入 😀\n• 随时撤销，安心修改\n• 查找与替换，快速定位\n\n每一次保存，都由你掌握。\n");
            state.saved = state.core.getText();
            state.documentVersion++; state.notifyChange();
            if (!javaMode && !markdownMode) {
                editor.getText().append("\n从一个想法，开始。\n"); editor.sync(true);
            }
            editor.setSelection(0); editor.scrollTo(0, 0);
            if ("true".equals(arguments.getString("search"))) {
                activity.findViewById(R.id.search_toggle).performClick();
                if (activity.findViewById(R.id.replace_panel).getVisibility() != android.view.View.VISIBLE)
                    activity.findViewById(R.id.replace_toggle).performClick();
                ((EditText) activity.findViewById(R.id.pattern)).setText("文字");
                ((EditText) activity.findViewById(R.id.replacement)).setText("灵感");
            }
            if ("true".equals(arguments.getString("keyboard"))) {
                android.view.View target = state.search ? activity.findViewById(R.id.pattern) : editor;
                target.requestFocus();
                ((android.view.inputmethod.InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(target, 0);
            } else {
                editor.clearFocus();
                ((android.view.inputmethod.InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(editor.getWindowToken(), 0);
            }
        });
        if (markdownMode) { ui(() -> activity.findViewById(R.id.mode_preview).performClick()); Thread.sleep(600); }
        Thread.sleep(4000); waitForIdleSync();
        // Display changes can recreate the Activity while the keyboard is opening.
        if (monitor.getLastActivity() != activity) { bind((MainActivity) monitor.getLastActivity()); ready(); }
        android.accessibilityservice.AccessibilityServiceInfo info = getUiAutomation().getServiceInfo();
        info.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        getUiAutomation().setServiceInfo(info);
        for (android.view.accessibility.AccessibilityWindowInfo window : getUiAutomation().getWindows()) {
            if (window.getType() != android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD || window.getRoot() == null) continue;
            for (AccessibilityNodeInfo node : window.getRoot().findAccessibilityNodeInfosByText("OK"))
                if ("OK".contentEquals(node.getText())) node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }
        Thread.sleep(250); waitForIdleSync();
        String name = arguments.getString("preview");
        screenshot(name);
        ui(() -> {
            if (!markdownMode && editor.getHeight() < editor.getLineHeight() + editor.getPaddingTop() + editor.getPaddingBottom())
                throw new AssertionError("正文高度不足：" + editor.getHeight());
            checks++;
            if ("true".equals(arguments.getString("keyboard")))
                equal(true, androidx.core.view.ViewCompat.getRootWindowInsets(editor).isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()));
        });
        if ("light".equals(name)) {
            ui(() -> activity.findViewById(R.id.files).performClick()); Thread.sleep(300); screenshot("file-menu");
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); waitForIdleSync();
            ui(() -> activity.getOnBackPressedDispatcher().onBackPressed()); Thread.sleep(300); screenshot("leave-dialog"); clickText("取消");
        }
    }

    private void screenshot(String name) throws Exception {
        File directory = getTargetContext().getExternalFilesDir("previews");
        android.graphics.Bitmap bitmap = getUiAutomation().takeScreenshot();
        if (bitmap == null) throw new AssertionError("截图失败");
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(new File(directory, name + ".png"))) {
            if (!bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)) throw new AssertionError("截图写入失败");
        } finally { bitmap.recycle(); }
    }

    private static final String JAVA_SAMPLE = "package demo;\n\n"
            + "import java.util.List;\n\n"
            + "/** 语法高亮示例 */\n"
            + "public class 示例 {\n"
            + "    // 行注释\n"
            + "    private static final int VALUE = 42;\n"
            + "    private String text = \"中文字符串\";\n\n"
            + "    /* 块注释\n"
            + "       跨行 */\n"
            + "    @Override\n"
            + "    public String toString() {\n"
            + "        return text + VALUE + 'x' + 3.14f;\n"
            + "    }\n"
            + "}\n";

    private static final String MD_SAMPLE = "# 标题\n\n"
            + "段落包含 **粗体**、*斜体*、`行内代码` 与 [链接](https://example.com)。\n\n"
            + "- 项目一\n"
            + "- 项目二\n"
            + "  - 嵌套项目\n\n"
            + "> 引用一行\n\n"
            + "```java\n"
            + "class A { int value = 1; }\n"
            + "```\n\n"
            + "| 列 | 值 |\n"
            + "| --- | --- |\n"
            + "| a | 1 |\n\n"
            + "---\n";
}
