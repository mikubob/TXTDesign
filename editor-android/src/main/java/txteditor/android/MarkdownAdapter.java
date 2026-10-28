package txteditor.android;

import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.UnderlineSpan;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import java.util.Locale;
import txteditor.core.text.LanguageId;
import txteditor.core.text.SyntaxHighlighter;
import txteditor.core.text.TokenSink;
import txteditor.core.text.TokenType;
import txteditor.core.text.md.MdDocument;
import txteditor.core.text.md.MdSpan;

/** Markdown 预览：一个块一项，长文只测量可视块。只读，覆盖标题/段落/列表/引用/代码/表格/分割线。 */
final class MarkdownAdapter extends RecyclerView.Adapter<MarkdownAdapter.Holder> {
    private static final int BLOCK = 0, CODE = 1, RULE = 2;
    private final Context context;
    private final LayoutInflater inflater;
    private MdDocument doc;

    MarkdownAdapter(Context context) {
        this.context = context;
        this.inflater = LayoutInflater.from(context);
    }

    void setDocument(MdDocument value) {
        // 整篇换文档：位置全部重排，按"先清空再插入"通知，比 notifyDataSetChanged 精确。
        int before = getItemCount();
        doc = value;
        int after = getItemCount();
        if (before > 0) notifyItemRangeRemoved(0, before);
        if (after > 0) notifyItemRangeInserted(0, after);
    }

    @Override public int getItemCount() { return doc == null ? 0 : doc.count(); }

    @Override public int getItemViewType(int position) {
        byte type = doc.type(position);
        if (type == MdDocument.CODE || type == MdDocument.TABLE) return CODE;
        return type == MdDocument.RULE ? RULE : BLOCK;
    }

    @Override public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
        int layout = viewType == CODE ? R.layout.preview_code : viewType == RULE ? R.layout.preview_rule : R.layout.preview_block;
        return new Holder(inflater.inflate(layout, parent, false));
    }

    @Override public void onBindViewHolder(Holder holder, int position) {
        TextView text = holder.text;
        if (text == null) return;
        // 回收复用：字号、字重、缩进每项都显式复位。
        text.setTypeface(Typeface.DEFAULT);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        text.setPadding(0, dp(4), 0, dp(4));
        switch (doc.type(position)) {
            case MdDocument.HEADING: heading(text, position); break;
            case MdDocument.CODE: code(text, position); break;
            case MdDocument.TABLE: text.setText(tableText(position)); break;
            case MdDocument.QUOTE: quote(text, position); break;
            case MdDocument.LIST_ITEM: listItem(text, position); break;
            case MdDocument.RULE: break;
            default: text.setText(inline(position)); break;
        }
    }

    private void heading(TextView text, int block) {
        int level = Math.max(1, Math.min(6, doc.level(block)));
        int[] sizes = {26, 22, 19, 17, 16, 15};
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizes[level - 1]);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        text.setPadding(0, dp(level <= 2 ? 12 : 8), 0, dp(4));
        text.setText(inline(block));
    }

    private void quote(TextView text, int block) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        for (int i = 0; i < Math.max(1, doc.quote(block)); i++) out.append("▎ ");
        out.append(inline(block));
        out.setSpan(new ForegroundColorSpan(context.getColor(R.color.on_surface_variant)),
                0, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setText(out);
    }

    private void listItem(TextView text, int block) {
        SpannableStringBuilder out = new SpannableStringBuilder(listPrefix(block));
        out.append(inline(block));
        text.setPadding(Math.max(0, doc.level(block)) * dp(16), dp(2), 0, dp(2));
        text.setText(out);
    }

    private void code(TextView text, int block) {
        String source = doc.source(block);
        SpannableStringBuilder out = new SpannableStringBuilder(source);
        LanguageId language = fenceLanguage(doc.language(block));
        if (language != LanguageId.PLAIN) {
            SyntaxHighlighter highlighter = new SyntaxHighlighter();
            highlighter.setLanguage(language);
            highlighter.load(source);
            TokenSink tokens = new TokenSink();
            highlighter.paint(0, highlighter.lineCount() - 1, tokens);
            for (int i = 0; i < tokens.size(); i++) {
                int color = HighlightController.color(context, tokens.type(i));
                if (color == 0) continue;
                out.setSpan(new ForegroundColorSpan(color), tokens.start(i), tokens.end(i),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        text.setText(out);
    }

    /** 表格首版按等宽源码显示，去掉只用于对齐的分隔行；自绘列对齐留待后续。 */
    private String tableText(int block) {
        String[] lines = doc.raw(block).split("\n");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i == 1) continue;
            if (out.length() > 0) out.append('\n');
            out.append(lines[i]);
        }
        return out.toString();
    }

    private String listPrefix(int block) {
        String text = doc.text();
        String head = text.substring(doc.start(block), Math.min(doc.content(block), text.length()));
        if (head.indexOf('[') >= 0) return doc.checked(block) ? "☑ " : "☐ ";
        int digits = 0;
        while (digits < head.length() && Character.isDigit(head.charAt(digits))) digits++;
        return digits > 0 ? head.substring(0, digits) + ". " : "• ";
    }

    /** 行内样式；marker 片段是标记符本身，直接跳过不显示。 */
    private CharSequence inline(int block) {
        String text = doc.text();
        int from = doc.content(block), to = Math.min(doc.contentEnd(block), text.length());
        SpannableStringBuilder out = new SpannableStringBuilder();
        int pos = from;
        for (MdSpan span : doc.inline(block)) {
            if (span.start < pos || span.end > to || span.end <= span.start) continue;
            out.append(text, pos, span.start);
            if (!span.marker) {
                int start = out.length();
                out.append(text, span.start, span.end);
                style(out, span.type, start, out.length());
            }
            pos = span.end;
        }
        if (pos < to) out.append(text, pos, to);
        return out;
    }

    private void style(SpannableStringBuilder out, byte type, int start, int end) {
        switch (type) {
            case MdSpan.BOLD: out.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); break;
            case MdSpan.ITALIC: out.setSpan(new StyleSpan(Typeface.ITALIC), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); break;
            case MdSpan.STRIKE: out.setSpan(new StrikethroughSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); break;
            case MdSpan.CODE:
                out.setSpan(new TypefaceSpan("monospace"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new ForegroundColorSpan(HighlightController.color(context, TokenType.CODE)),
                        start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                break;
            case MdSpan.LINK:
                out.setSpan(new UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new ForegroundColorSpan(HighlightController.color(context, TokenType.LINK)),
                        start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                break;
            default: break;
        }
    }

    private static LanguageId fenceLanguage(String fence) {
        if (fence == null || fence.isEmpty()) return LanguageId.PLAIN;
        String name = fence.toLowerCase(Locale.ROOT);
        if (name.equals("c++") || name.equals("cxx")) return LanguageId.CPP;
        return LanguageId.forFileName("fence." + name);
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final TextView text;

        Holder(View root) {
            super(root);
            text = root instanceof TextView ? (TextView) root : root.findViewById(R.id.code);
        }
    }
}
