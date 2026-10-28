package txteditor.android;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import androidx.lifecycle.ViewModel;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import txteditor.core.EditorModel;
import txteditor.core.TextCodec;

final class EditorViewModel extends ViewModel {
    static final int NONE = 0, NEW = 1, OPEN = 2, EXIT = 3;
    final EditorModel core = new EditorModel();
    private final Context app;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final File draftFile, recoveryFile;
    Runnable observer;
    boolean busy, search;
    String name = "未命名.txt", uri = "", saved = "", pattern = "", replacement = "", message = "";
    int scroll, afterSave, readyAction, documentVersion;
    private final Runnable delayedDraft = this::persist;

    EditorViewModel(Context context) {
        app = context.getApplicationContext();
        draftFile = new File(app.getFilesDir(), "draft.bin");
        recoveryFile = new File(app.getFilesDir(), "recovery.bin");
        busy = true;
        io.execute(() -> {
            Draft result = null; String error = "";
            File candidate = Math.max(recoveryFile.lastModified(), new File(recoveryFile + ".bak").lastModified())
                    > Math.max(draftFile.lastModified(), new File(draftFile + ".bak").lastModified()) ? recoveryFile : draftFile;
            if (candidate.exists() || new File(candidate + ".bak").exists()) {
                try { result = DraftStore.read(candidate); }
                catch (Exception failure) { error = "草稿读取失败，原副本仍保留：" + detail(failure); }
            }
            final Draft restored = result; final String problem = error;
            main.post(() -> {
                if (restored != null) {
                    core.load(restored.text); core.markSaved(restored.saved); saved = restored.saved;
                    core.setSelection(TextEdits.boundary(restored.text, restored.start, false),
                            TextEdits.boundary(restored.text, Math.max(restored.start, restored.end), true));
                    uri = restored.uri; name = restored.name; pattern = restored.pattern;
                    replacement = restored.replacement; search = restored.search; scroll = restored.scroll;
                    message = "已恢复本地草稿（编辑历史从此处重新记录）";
                } else message = problem;
                documentVersion++; busy = false; notifyChange();
            });
        });
    }

    void notifyChange() { if (observer != null) observer.run(); }

    Draft snapshot() {
        Draft draft = new Draft();
        draft.text = core.getText(); draft.saved = saved; draft.uri = uri; draft.name = name;
        draft.start = core.getSelectionStart(); draft.end = core.getSelectionEnd(); draft.scroll = scroll;
        draft.pattern = pattern; draft.replacement = replacement; draft.search = search;
        return draft;
    }

    void edited() { main.removeCallbacks(delayedDraft); main.postDelayed(delayedDraft, 700); }

    void persist() {
        main.removeCallbacks(delayedDraft);
        Draft draft = snapshot();
        io.execute(() -> {
            try { DraftStore.write(draftFile, draft); }
            catch (Exception error) { main.post(() -> { message = "本地草稿保存失败：" + detail(error); notifyChange(); }); }
        });
    }

    void newDocument() {
        core.load(""); saved = ""; uri = ""; name = "未命名.txt"; scroll = 0;
        documentVersion++; persist(); notifyChange();
    }

    void open(Uri target) {
        busy = true; notifyChange();
        io.execute(() -> {
            try {
                String text;
                try (InputStream in = app.getContentResolver().openInputStream(target)) {
                    if (in == null) throw new IOException("无法读取所选文件");
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192]; int count;
                    while ((count = in.read(buffer)) != -1) {
                        // shortcut: 首版针对小文本，超过 16 MiB 时需分页或流式编辑器。
                        if (bytes.size() + count > 16 * 1024 * 1024) throw new IOException("文件超过 16 MiB，请选择较小文本");
                        bytes.write(buffer, 0, count);
                    }
                    text = TextCodec.decodeUtf8(bytes.toByteArray());
                }
                String filename = displayName(target);
                main.post(() -> {
                    core.load(text); saved = text; uri = target.toString(); name = filename; scroll = 0;
                    documentVersion++; busy = false; message = "已打开 UTF-8 文本"; persist(); notifyChange();
                });
            } catch (Exception error) {
                main.post(() -> {
                    busy = false; message = "打开失败（仅支持有效 UTF-8）：" + detail(error); notifyChange();
                });
            }
        });
    }

    void save(Uri target) {
        Draft draft = snapshot();
        busy = true; notifyChange();
        io.execute(() -> {
            try {
                // 写 URI 前必须先可靠落盘；提供者截断后失败时仍可恢复。
                DraftStore.write(recoveryFile, draft);
                byte[] bytes = TextCodec.encodeUtf8(draft.text);
                ContentResolver resolver = app.getContentResolver();
                try (OutputStream out = resolver.openOutputStream(target, "wt")) {
                    if (out == null) throw new IOException("文档提供者未返回输出流");
                    out.write(bytes); out.flush();
                }
                String filename = displayName(target);
                main.post(() -> {
                    core.markSaved(draft.text); saved = draft.text; uri = target.toString(); name = filename;
                    busy = false; message = "已保存 UTF-8 文本";
                    readyAction = afterSave; afterSave = NONE;
                    persist(); io.execute(() -> DraftStore.delete(recoveryFile)); notifyChange();
                });
            } catch (Exception error) {
                main.post(() -> {
                    busy = false; afterSave = NONE;
                    message = "保存失败，编辑内容仍保留；覆盖前成功写入的恢复副本也会保留。可用“文件 → 另存为”重试：" + detail(error);
                    persist(); notifyChange();
                });
            }
        });
    }

    private String displayName(Uri target) {
        try (Cursor cursor = app.getContentResolver().query(target, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String result = cursor.getString(0); if (result != null && !result.isEmpty()) return result;
            }
        } catch (RuntimeException ignored) { /* 名称查询失败不影响已经完成的读写。 */ }
        return "文档.txt";
    }

    private static String detail(Exception error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    @Override protected void onCleared() { main.removeCallbacks(delayedDraft); io.shutdown(); }
}
