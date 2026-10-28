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
import java.nio.charset.CharacterCodingException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import txteditor.core.EditorModel;
import txteditor.core.SessionSnapshot;
import txteditor.core.TextCodec;
import txteditor.core.TextKeys;

final class EditorViewModel extends ViewModel {
    static final int NONE = 0, NEW = 1, OPEN = 2, EXIT = 3;
    final EditorModel core = new EditorModel();
    private final Context app;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final File draftFile, recoveryFile;
    Runnable observer;
    boolean busy, search, saveFailed;
    String name = "未命名.txt", uri = "", saved = "", pattern = "", replacement = "", message = "";
    int scroll, afterSave, readyAction, documentVersion;
    private final Runnable delayedDraft = this::persist;

    /** 打开时区分 16 MiB 上限与其他失败，避免把大小问题误报成编码问题。 */
    private static final class TooLarge extends IOException {
        TooLarge(String message) { super(message); }
    }

    EditorViewModel(Context context) {
        app = context.getApplicationContext();
        draftFile = new File(app.getFilesDir(), "draft.bin");
        recoveryFile = new File(app.getFilesDir(), "recovery.bin");
        busy = true;
        io.execute(() -> {
            DraftFormat.Decoded result = null; String error = "";
            File candidate = Math.max(recoveryFile.lastModified(), new File(recoveryFile + ".bak").lastModified())
                    > Math.max(draftFile.lastModified(), new File(draftFile + ".bak").lastModified()) ? recoveryFile : draftFile;
            if (candidate.exists() || new File(candidate + ".bak").exists()) {
                try { result = DraftStore.read(candidate); }
                catch (Exception failure) { error = "草稿读取失败，原副本仍保留：" + detail(failure); }
            }
            final DraftFormat.Decoded restored = result; final String problem = error;
            main.post(() -> {
                if (restored != null) {
                    SessionSnapshot draft = restored.snapshot;
                    core.load(draft.text); core.markSaved(draft.savedText); saved = draft.savedText;
                    core.setSelection(TextEdits.boundary(draft.text, draft.selectionStart, false),
                            TextEdits.boundary(draft.text, Math.max(draft.selectionStart, draft.selectionEnd), true));
                    uri = restored.uri; name = draft.fileName; pattern = draft.pattern;
                    replacement = draft.replacement; search = draft.searchOpen; scroll = draft.scroll;
                    message = "已恢复本地草稿（编辑历史从此处重新记录）";
                } else message = problem;
                documentVersion++; busy = false; notifyChange();
            });
        });
    }

    void notifyChange() { if (observer != null) observer.run(); }

    SessionSnapshot snapshot() {
        return new SessionSnapshot(core.getText(), saved, name, core.getSelectionStart(), core.getSelectionEnd(),
                scroll, pattern, replacement, search);
    }

    void edited() { main.removeCallbacks(delayedDraft); main.postDelayed(delayedDraft, 700); }

    void persist() {
        main.removeCallbacks(delayedDraft);
        SessionSnapshot draft = snapshot(); String target = uri;
        io.execute(() -> {
            try { DraftStore.write(draftFile, draft, target); }
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
                        if (bytes.size() + count > 16 * 1024 * 1024) throw new TooLarge(TextKeys.of(TextKeys.TOO_LARGE, "16 MiB"));
                        bytes.write(buffer, 0, count);
                    }
                    text = TextCodec.decodeUtf8(bytes.toByteArray());
                }
                String filename = displayName(target);
                main.post(() -> {
                    core.load(text); saved = text; uri = target.toString(); name = filename; scroll = 0;
                    documentVersion++; busy = false; message = "已打开 UTF-8 文本"; persist(); notifyChange();
                });
            } catch (TooLarge error) {
                main.post(() -> {
                    busy = false; message = error.getMessage(); notifyChange();
                });
            } catch (CharacterCodingException error) {
                main.post(() -> {
                    busy = false; message = TextKeys.of(TextKeys.NOT_UTF8); notifyChange();
                });
            } catch (Exception error) {
                main.post(() -> {
                    busy = false; message = "打开失败：" + detail(error); notifyChange();
                });
            }
        });
    }

    void save(Uri target) {
        SessionSnapshot draft = snapshot(); String previous = uri;
        busy = true; notifyChange();
        io.execute(() -> {
            try {
                // 写 URI 前必须先可靠落盘；提供者截断后失败时仍可恢复。
                DraftStore.write(recoveryFile, draft, previous);
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
                    busy = false; afterSave = NONE; saveFailed = true;
                    message = TextKeys.of(TextKeys.SAVE_FAILED);
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
