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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import txteditor.core.EditorModel;
import txteditor.core.SessionSnapshot;
import txteditor.core.TextCodec;
import txteditor.core.TextKeys;
import txteditor.core.text.LanguageId;
import txteditor.core.text.md.MdDocument;
import txteditor.core.text.md.MdParser;

final class EditorViewModel extends ViewModel {
    static final int NONE = 0, NEW = 1, OPEN = 2, EXIT = 3;
    /** 视图模式：源码编辑 / Markdown 预览。 */
    static final int SOURCE = 0, PREVIEW = 1;
    final EditorModel core = new EditorModel();
    private final Context app;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final File draftFile, recoveryFile, recentFile;
    Runnable observer;
    boolean busy, search, saveFailed;
    LanguageId language = LanguageId.PLAIN;
    int viewMode = SOURCE;
    String name = LanguageId.PLAIN.defaultFileName(), uri = "", saved = "", pattern = "", replacement = "", message = "";
    String conflictUri = "";
    private String fingerprint = "";
    int positionTop, recentTop = -1;
    int scroll, afterSave, readyAction, documentVersion, textVersion;
    private final Runnable delayedDraft = this::persist;

    /** 打开时区分 16 MiB 上限与其他失败，避免把大小问题误报成编码问题。 */
    private static final class TooLarge extends IOException {
        TooLarge(String message) { super(message); }
    }

    EditorViewModel(Context context) {
        app = context.getApplicationContext();
        draftFile = new File(app.getFilesDir(), "draft.bin");
        recoveryFile = new File(app.getFilesDir(), "recovery.bin");
        recentFile = new File(app.getFilesDir(), RecentFiles.NAME);
        busy = true;
        io.execute(() -> {
            DraftFormat.Decoded result = null; String error = "";
            File candidate = Math.max(recoveryFile.lastModified(), new File(recoveryFile + ".bak").lastModified())
                    > Math.max(draftFile.lastModified(), new File(draftFile + ".bak").lastModified()) ? recoveryFile : draftFile;
            if (candidate.exists() || new File(candidate + ".bak").exists()) {
                try { result = DraftStore.read(candidate); }
                catch (Exception failure) { error = app.getString(R.string.draft_read_failed, detail(failure)); }
            }
            final DraftFormat.Decoded restored = result; final String problem = error;
            main.post(() -> {
                if (restored != null) {
                    SessionSnapshot draft = restored.snapshot;
                    core.load(draft.text); core.markSaved(draft.savedText); saved = draft.savedText;
                    core.setSelection(TextEdits.boundary(draft.text, draft.selectionStart, false),
                            TextEdits.boundary(draft.text, Math.max(draft.selectionStart, draft.selectionEnd), true));
                    uri = restored.uri; fingerprint = restored.fingerprint; name = draft.fileName; pattern = draft.pattern;
                    replacement = draft.replacement; search = draft.searchOpen; scroll = draft.scroll;
                    language = LanguageId.parse(draft.language);
                    viewMode = language == LanguageId.MARKDOWN && draft.viewMode == PREVIEW ? PREVIEW : SOURCE;
                    message = app.getString(R.string.draft_restored);
                } else message = problem;
                documentVersion++; textVersion++; busy = false; notifyChange();
            });
        });
    }

    void notifyChange() { if (observer != null) observer.run(); }

    SessionSnapshot snapshot() {
        return new SessionSnapshot(core.getText(), saved, name, core.getSelectionStart(), core.getSelectionEnd(),
                scroll, pattern, replacement, search, language.name(), viewMode);
    }

    /** 选区和面板状态也要存草稿，只有正文变化才使预览映射过期。 */
    void edited(boolean textChanged) {
        if (textChanged) textVersion++;
        main.removeCallbacks(delayedDraft); main.postDelayed(delayedDraft, 700);
    }

    void persist() { persist(false); }

    private void persist(boolean clearRecovery) {
        main.removeCallbacks(delayedDraft);
        SessionSnapshot draft = snapshot(); String target = uri, baseline = fingerprint;
        io.execute(() -> {
            try {
                DraftStore.write(draftFile, draft, target, baseline);
                if (clearRecovery) DraftStore.delete(recoveryFile);
            }
            catch (Exception error) { main.post(() -> { message = app.getString(R.string.draft_save_failed, detail(error)); notifyChange(); }); }
        });
    }

    void rememberPosition() {
        if (uri.isEmpty()) return;
        String target = uri;
        int caret = core.getSelectionEnd(), top = positionTop;
        io.execute(() -> RecentFiles.remember(recentFile, target, caret, top));
    }

    /** 类型决定默认后缀与高亮语言，草稿随新建一起写入。 */
    void newDocument(LanguageId type) {
        rememberPosition();
        language = type;
        core.load(""); saved = ""; uri = ""; fingerprint = ""; name = type.defaultFileName(); scroll = positionTop = 0; recentTop = -1; viewMode = SOURCE;
        documentVersion++; textVersion++; persist(); notifyChange();
    }

    /** 保持当前类型的新建。 */
    void newDocument() { newDocument(language); }

    void open(Uri target) {
        rememberPosition();
        busy = true; notifyChange();
        io.execute(() -> {
            try {
                String text;
                MessageDigest checksum = sha256();
                try (InputStream in = app.getContentResolver().openInputStream(target)) {
                    if (in == null) throw new IOException(app.getString(R.string.cannot_read_file));
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192]; int count;
                    while ((count = in.read(buffer)) != -1) {
                        if (bytes.size() + count > TextCodec.FILE_LIMIT_BYTES)
                            throw new TooLarge(TextKeys.of(TextKeys.TOO_LARGE_REFUSED, "16 MiB"));
                        bytes.write(buffer, 0, count);
                        checksum.update(buffer, 0, count);
                    }
                    text = TextCodec.decodeUtf8(bytes.toByteArray());
                }
                String baseline = encodeFingerprint(checksum.digest());
                String filename = displayName(target);
                // 后缀优先，MIME 与首行兜底：SAF 对 .java/.c 常报 application/octet-stream。
                LanguageId detected = LanguageId.detect(filename, mime(target), text);
                RecentFiles.Entry recent = RecentFiles.add(recentFile, target.toString(), filename);
                main.post(() -> {
                    core.load(text); saved = text; uri = target.toString(); fingerprint = baseline; name = filename;
                    int caret = core.clampOffset(recent.caret);
                    core.setSelection(caret, caret);
                    scroll = 0; positionTop = recentTop = core.clampOffset(recent.top);
                    language = detected; viewMode = SOURCE;
                    documentVersion++; textVersion++; busy = false; message = app.getString(R.string.opened_utf8); persist(); notifyChange();
                });
            } catch (TooLarge error) {
                main.post(() -> {
                    busy = false; message = error.getMessage(); notifyChange();
                });
            } catch (SecurityException denied) {
                // 最近文件重开时授权可能已失效，提示重新选择而不是报一堆堆栈。
                main.post(() -> {
                    busy = false; message = app.getString(R.string.no_read_permission); notifyChange();
                });
            } catch (CharacterCodingException error) {
                main.post(() -> {
                    busy = false; message = TextKeys.of(TextKeys.NOT_UTF8); notifyChange();
                });
            } catch (Exception error) {
                main.post(() -> {
                    busy = false; message = app.getString(R.string.open_failed, detail(error)); notifyChange();
                });
            }
        });
    }

    void save(Uri target) { save(target, false); }

    void save(Uri target, boolean overwrite) {
        SessionSnapshot draft = snapshot();
        int top = positionTop;
        String previous = uri, baseline = fingerprint;
        busy = true; notifyChange();
        io.execute(() -> {
            try {
                if (TextCodec.exceedsUtf8Limit(draft.text, TextCodec.FILE_LIMIT_BYTES)) {
                    main.post(() -> saveError(TextKeys.TOO_LARGE_TO_SAVE));
                    return;
                }
                if (!overwrite && target.toString().equals(previous) && !matchesBaseline(target, baseline)) {
                    main.post(() -> { busy = false; conflictUri = target.toString(); notifyChange(); });
                    return;
                }
                // 写 URI 前必须先可靠落盘；提供者截断后失败时仍可恢复。
                DraftStore.write(recoveryFile, draft, previous, baseline);
                byte[] bytes = TextCodec.encodeUtf8(draft.text);
                ContentResolver resolver = app.getContentResolver();
                try (OutputStream out = resolver.openOutputStream(target, "wt")) {
                    if (out == null) throw new IOException(app.getString(R.string.no_output_stream));
                    out.write(bytes); out.flush();
                }
                String filename = target.toString().equals(previous) ? draft.fileName : displayName(target);
                String written = encodeFingerprint(sha256().digest(bytes));
                RecentFiles.add(recentFile, target.toString(), filename);
                RecentFiles.remember(recentFile, target.toString(), draft.selectionEnd, top);
                main.post(() -> {
                    core.markSaved(draft.text); saved = draft.text; uri = target.toString(); name = filename;
                    fingerprint = written;
                    if (!uri.equals(previous)) {
                        language = LanguageId.forFileName(filename);
                        if (language != LanguageId.MARKDOWN) viewMode = SOURCE;
                    }
                    busy = false; message = app.getString(R.string.saved_utf8);
                    readyAction = afterSave; afterSave = NONE;
                    persist(true); notifyChange();
                });
            } catch (Exception error) {
                main.post(() -> saveError(TextKeys.of(TextKeys.SAVE_FAILED) + "\n" + detail(error)));
            }
        });
    }

    private void saveError(String detail) {
        busy = false; afterSave = NONE; saveFailed = true; message = detail;
        persist(); notifyChange();
    }

    private boolean matchesBaseline(Uri target, String baseline) {
        if (baseline.isEmpty()) return false;
        try (InputStream in = app.getContentResolver().openInputStream(target)) {
            if (in == null) return false;
            MessageDigest checksum = sha256();
            byte[] buffer = new byte[8192];
            int size = 0, count;
            while ((count = in.read(buffer)) != -1) {
                size += count;
                if (size > TextCodec.FILE_LIMIT_BYTES) return false;
                checksum.update(buffer, 0, count);
            }
            return baseline.equals(encodeFingerprint(checksum.digest()));
        } catch (IOException | SecurityException unavailable) {
            return false;
        }
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException(unavailable); }
    }

    private static String encodeFingerprint(byte[] digest) { return Base64.getEncoder().encodeToString(digest); }

    private String mime(Uri target) {
        try { return app.getContentResolver().getType(target); }
        catch (RuntimeException ignored) { return null; }
    }

    /** 后台解析 Markdown 块；结果只在文档没换过时回调。 */
    void preview(String text, Consumer<MdDocument> sink) {
        final int version = documentVersion;
        io.execute(() -> {
            MdDocument document = MdParser.parse(text);
            main.post(() -> { if (version == documentVersion) sink.accept(document); });
        });
    }

    private String displayName(Uri target) {
        try (Cursor cursor = app.getContentResolver().query(target, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String result = cursor.getString(0); if (result != null && !result.isEmpty()) return result;
            }
        } catch (RuntimeException ignored) { /* 名称查询失败不影响已经完成的读写。 */ }
        return app.getString(R.string.default_document_name);
    }

    private static String detail(Exception error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    @Override protected void onCleared() { main.removeCallbacks(delayedDraft); io.shutdown(); }
}
