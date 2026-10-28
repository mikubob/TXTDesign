package txteditor.android;

import android.content.Context;
import android.content.res.Configuration;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.Layout;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.CharacterStyle;
import android.view.inputmethod.BaseInputConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import txteditor.core.text.LanguageId;
import txteditor.core.text.SyntaxHighlighter;
import txteditor.core.text.TokenSink;
import txteditor.core.text.TokenType;

/**
 * 源码高亮：词法与 token 只在后台线程算，主线程按版本号贴 span。
 * 样式是视图状态，不进 EditorModel、不进撤销历史；setSpan 不改字符，不会触发 TextWatcher。
 */
final class HighlightController {
    /** 可视窗口上下各多贴的行数。 */
    private static final int MARGIN = 50;
    /** 连续输入只跑最后一次。 */
    private static final long DEBOUNCE = 80;
    /** 超过 2 MiB 关闭自动高亮，滚动仍然流畅优先。 */
    private static final int MAX_TEXT = 2 * 1024 * 1024;
    private static final int[] NONE = new int[0];

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final TokenSink sink = new TokenSink();
    private final List<TokenSpan> pool = new ArrayList<TokenSpan>();
    /** 只在 worker 线程访问；语言变化时整体重建。 */
    private SyntaxHighlighter engine = new SyntaxHighlighter();
    private CoreEditText view;
    private volatile LanguageId language = LanguageId.PLAIN;
    /** 文本版本与已贴 span 的版本；不相等时旧 span 视为过期。 */
    private int version, applied = -1;
    /** 已贴 span 覆盖的字符区间，滚动时判断是否需要补色。 */
    private int paintedFrom = -1, paintedTo = -1;
    /** 上次派发之后累计的变更；只有恰好一次编辑才能直接喂增量三元组。 */
    private int changeStart, changeOldEnd, changeNewEnd, changes;
    /** 上一次派发被跳过（超限/纯文本），下次必须全量对齐。 */
    private boolean forceFull;
    private boolean paintQueued;
    private final Runnable lex = () -> dispatch(false);

    void attach(CoreEditText target) { view = target; }

    void shutdown() {
        main.removeCallbacks(lex);
        view = null;
        worker.shutdownNow();
    }

    void setLanguage(LanguageId id) {
        LanguageId next = id == null ? LanguageId.PLAIN : id;
        if (next == language) return;
        language = next;
        reload();
    }

    /** 文本被整体回写（撤销、查找替换、恢复草稿）后重扫全文。 */
    void reload() {
        main.removeCallbacks(lex);
        version++;
        changes = 0;
        clearSpans();
        dispatch(true);
    }

    /** 由 CoreEditText 在提交一次编辑后调用，三元组来自 TextEdits.commit。 */
    void onTextChanged(int start, int oldEnd, int newEnd) {
        if (language == LanguageId.PLAIN) return;
        changes++;
        if (changes == 1) {
            changeStart = start;
            changeOldEnd = oldEnd;
            changeNewEnd = newEnd;
        }
        version++;
        applied = -1;
        main.removeCallbacks(lex);
        main.postDelayed(lex, DEBOUNCE);
    }

    void onScroll() {
        if (view == null || language == LanguageId.PLAIN || applied != version) return;
        if (visibleFrom() >= paintedFrom && visibleTo() <= paintedTo) return;
        requestPaint();
    }

    private void dispatch(boolean full) {
        if (view == null) return;
        main.removeCallbacks(lex);
        if (language == LanguageId.PLAIN || view.getText().length() > MAX_TEXT) {
            changes = 0;
            forceFull = true;
            clearSpans();
            return;
        }
        // 组合态先不贴：下划线等输入法 span 与高亮同时改容易闪烁，组合结束后的提交会再触发一次。
        if (BaseInputConnection.getComposingSpanStart(view.getText()) >= 0) return;
        // shortcut: 80 ms 内多次编辑就整体重扫；要连打也增量，需先合并脏区间再喂 update。
        boolean whole = full || forceFull || changes != 1;
        Job job = new Job(version, view.getText().toString(), language, whole,
                changeStart, changeOldEnd, changeNewEnd, visibleFrom(), visibleTo());
        forceFull = false;
        changes = 0;
        worker.execute(() -> run(job));
    }

    private void run(Job job) {
        try {
            if (engine.language() != job.language) {
                engine = new SyntaxHighlighter();
                engine.setLanguage(job.language);
                engine.load(job.text);
            } else if (job.full) engine.load(job.text);
            else engine.update(job.text, job.start, job.oldEnd, job.newEnd);
        } catch (RuntimeException error) {
            // 三元组与缓存文本失配时退回全量，保证颜色最终正确。
            engine = new SyntaxHighlighter();
            engine.setLanguage(job.language);
            engine.load(job.text);
        }
        Painted painted = tokens(job.from, job.to);
        main.post(() -> {
            if (job.version == version) apply(painted);
        });
    }

    private void requestPaint() {
        if (paintQueued) return;
        paintQueued = true;
        final int expected = version, from = visibleFrom(), to = visibleTo();
        worker.execute(() -> {
            Painted painted = tokens(from, to);
            main.post(() -> {
                paintQueued = false;
                if (expected == version) apply(painted);
            });
        });
    }

    /** 现算 [fromOffset, toOffset] 覆盖的行窗口，返回绝对偏移的 token 流。 */
    private Painted tokens(int fromOffset, int toOffset) {
        int lines = engine.lineCount();
        if (lines <= 0) return new Painted(NONE, 0, 0);
        int first = Math.max(0, engine.lineOf(fromOffset) - MARGIN);
        int last = Math.min(lines - 1, engine.lineOf(toOffset) + MARGIN);
        sink.clear();
        engine.paint(first, last, sink);
        int size = sink.size();
        int[] out = new int[size * 3];
        for (int i = 0; i < size; i++) {
            out[i * 3] = sink.start(i);
            out[i * 3 + 1] = sink.end(i);
            out[i * 3 + 2] = sink.type(i);
        }
        return new Painted(out, engine.lineStart(first), engine.lineEnd(last));
    }

    private void apply(Painted painted) {
        if (view == null || painted == null) return;
        Editable text = view.getText();
        for (TokenSpan span : text.getSpans(0, text.length(), TokenSpan.class)) text.removeSpan(span);
        int size = painted.tokens.length / 3, used = 0, width = text.length();
        for (int i = 0; i < size; i++) {
            int start = painted.tokens[i * 3], end = painted.tokens[i * 3 + 1], type = painted.tokens[i * 3 + 2];
            int color = color(view.getContext(), (byte) type);
            if (color == 0) continue;
            // 同色相邻 token 合并，span 数量通常能减半。
            while (i + 1 < size && painted.tokens[(i + 1) * 3 + 2] == type && painted.tokens[(i + 1) * 3] == end) {
                i++;
                end = painted.tokens[i * 3 + 1];
            }
            if (start >= end || end > width) continue;
            TokenSpan span = used < pool.size() ? pool.get(used) : create();
            span.color = color;
            text.setSpan(span, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            used++;
        }
        applied = version;
        paintedFrom = painted.from;
        paintedTo = painted.to;
        // 贴色期间可能已经滚动（例如恢复草稿后跳到远处），窗口没盖住可视区就再补一次。
        if (visibleFrom() < paintedFrom || visibleTo() > paintedTo) requestPaint();
    }

    private TokenSpan create() {
        TokenSpan span = new TokenSpan();
        pool.add(span);
        return span;
    }

    private void clearSpans() {
        applied = -1;
        paintedFrom = paintedTo = -1;
        if (view == null) return;
        Editable text = view.getText();
        for (TokenSpan span : text.getSpans(0, text.length(), TokenSpan.class)) text.removeSpan(span);
    }

    private int visibleFrom() {
        Layout layout = view == null ? null : view.getLayout();
        return layout == null ? 0 : layout.getLineStart(visualLine(layout, 0));
    }

    private int visibleTo() {
        Layout layout = view == null ? null : view.getLayout();
        return layout == null ? 0 : layout.getLineEnd(visualLine(layout, view.getHeight()));
    }

    private int visualLine(Layout layout, int offsetY) {
        int line = layout.getLineForVertical(Math.max(0, view.getScrollY() + offsetY));
        return Math.max(0, Math.min(line, layout.getLineCount() - 1));
    }

    /** 可变色的 span：ForegroundColorSpan 色值不可改，无法按池复用。 */
    private static final class TokenSpan extends CharacterStyle {
        int color;

        @Override public void updateDrawState(TextPaint paint) { paint.setColor(color); }
    }

    private static final class Job {
        final int version;
        final String text;
        final LanguageId language;
        final boolean full;
        final int start, oldEnd, newEnd, from, to;

        Job(int version, String text, LanguageId language, boolean full,
            int start, int oldEnd, int newEnd, int from, int to) {
            this.version = version;
            this.text = text;
            this.language = language;
            this.full = full;
            this.start = start;
            this.oldEnd = oldEnd;
            this.newEnd = newEnd;
            this.from = from;
            this.to = to;
        }
    }

    private static final class Painted {
        final int[] tokens;
        final int from, to;

        Painted(int[] tokens, int from, int to) {
            this.tokens = tokens;
            this.from = from;
            this.to = to;
        }
    }

    /** token 类型 → 颜色；0 表示不上色。明暗两套取自设计文档附录 A。 */
    static int color(Context context, byte type) {
        boolean dark = (context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        switch (type) {
            case TokenType.KEYWORD: return dark ? 0xFFC586C0 : 0xFFAF00DB;
            case TokenType.TYPE: return dark ? 0xFF4EC9B0 : 0xFF267F99;
            case TokenType.LITERAL: return dark ? 0xFF569CD6 : 0xFF0000FF;
            case TokenType.NUMBER: return dark ? 0xFFB5CEA8 : 0xFF098658;
            case TokenType.STRING: return dark ? 0xFFCE9178 : 0xFFA31515;
            case TokenType.CHAR: return dark ? 0xFFCE9178 : 0xFFA31515;
            case TokenType.COMMENT: return dark ? 0xFF6A9955 : 0xFF008000;
            case TokenType.ANNOTATION: return dark ? 0xFFDCDCAA : 0xFF808000;
            case TokenType.PREPROCESSOR: return dark ? 0xFF9CDCFE : 0xFF808080;
            case TokenType.OPERATOR: return dark ? 0xFFD4D4D4 : 0xFF1A2130;
            case TokenType.TAG: return dark ? 0xFF569CD6 : 0xFF800000;
            case TokenType.ATTRIBUTE: return dark ? 0xFF9CDCFE : 0xFFA31515;
            case TokenType.HEADING: return dark ? 0xFF9CDCFE : 0xFF2859BD;
            case TokenType.LINK: return dark ? 0xFF4FA3FF : 0xFF0B5FD0;
            case TokenType.CODE: return dark ? 0xFFCE9178 : 0xFFA31515;
            case TokenType.MARKER:
            case TokenType.LIST_MARKER:
            case TokenType.QUOTE:
            case TokenType.TABLE:
                return dark ? 0xFF7F8C9E : 0xFF8A94A6;
            default: return 0;
        }
    }
}
