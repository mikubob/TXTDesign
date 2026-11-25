package txteditor.core;

/** 两端共用的用户可见文案键；默认中文文案放在核心，平台只负责呈现方式。 */
public final class TextKeys {
    public static final String SAVE_FAILED = "save.failed";
    public static final String NOT_UTF8 = "file.notUtf8";
    public static final String NOT_FOUND = "search.notFound";
    public static final String EXTERNALLY_MODIFIED = "file.externallyModified";
    /** 超限且只读取了开头部分时使用；文案必须说明显示的不是完整内容。 */
    public static final String TOO_LARGE_PARTIAL = "file.tooLargePartial";
    /** 超限且不能按只读打开时使用；文案必须说明文件没有被打开。 */
    public static final String TOO_LARGE_REFUSED = "file.tooLargeRefused";

    public static final String PREVIOUS_MATCH = "上一处";
    public static final String MATCH_COUNT = "search.matchCount";
    public static final String GO_TO_LINE = "跳转到行";
    public static final String LINE_RANGE = "search.lineRange";
    public static final String STYLING_TOO_LARGE = "文档超过 2 MiB，自动高亮与预览已关闭。";
    public static final String PREVIEW_NODE_LIMIT = "Markdown 块超过 200,000 个，预览以纯文本显示。";
    public static final String TOO_LARGE_TO_SAVE = "文档超过 16 MiB，无法保存；编辑内容仍保留。";

    private TextKeys() { }

    /** Returns the default text for a key; unknown keys echo the key so a typo stays visible. */
    public static String of(String key, Object... args) {
        String template;
        if (SAVE_FAILED.equals(key)) template = "保存失败；编辑内容仍在窗口中。";
        else if (NOT_UTF8.equals(key)) template = "文件不是有效 UTF-8；请先在其他工具中转换编码。当前内容保留。";
        else if (MATCH_COUNT.equals(key)) template = "共 %d 处匹配";
        else if (LINE_RANGE.equals(key)) template = "输入行号（1–%d）";
        else if (NOT_FOUND.equals(key)) template = "未找到：%s";
        else if (EXTERNALLY_MODIFIED.equals(key)) template = "文件已被其他程序修改：%s";
        else if (TOO_LARGE_PARTIAL.equals(key)) template = "文件超过 %s 上限，仅显示开头部分，已按只读方式打开。";
        else if (TOO_LARGE_REFUSED.equals(key)) template = "文件超过 %s 上限，未打开；当前内容保留。";
        else return args.length == 0 ? key : key + "：" + args[0];
        return args.length == 0 ? template : String.format(template, args);
    }
}
