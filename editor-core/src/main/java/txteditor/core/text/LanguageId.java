package txteditor.core.text;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** 支持的文件类型；后缀识别与显示名都在这里，平台层不再各写一份。 */
public enum LanguageId {
    PLAIN("纯文本", ".txt"),
    MARKDOWN("Markdown", ".md"),
    JAVA("Java", ".java"),
    C("C", ".c"),
    CPP("C++", ".cpp"),
    JSON("JSON", ".json"),
    XML("XML", ".xml");

    public final String title;
    public final String defaultSuffix;

    LanguageId(String title, String defaultSuffix) {
        this.title = title;
        this.defaultSuffix = defaultSuffix;
    }

    /** 未知名字回落 PLAIN，保证旧草稿里的脏值不会让界面崩。 */
    public static LanguageId parse(String name) {
        if (name != null) for (LanguageId id : values()) if (id.name().equals(name)) return id;
        return PLAIN;
    }

    private static final Map<String, LanguageId> SUFFIX = new HashMap<String, LanguageId>();

    static {
        suffix(MARKDOWN, "md", "markdown", "mdown");
        suffix(JAVA, "java");
        suffix(C, "c", "h");
        suffix(CPP, "cpp", "cc", "cxx", "hpp", "hh");
        suffix(JSON, "json");
        suffix(XML, "xml", "html", "htm", "xhtml", "svg");
        suffix(PLAIN, "txt", "text", "log", "ini", "properties", "csv");
    }

    private static void suffix(LanguageId id, String... names) {
        for (String name : names) SUFFIX.put(name, id);
    }

    public static LanguageId forFileName(String fileName) {
        if (fileName == null) return PLAIN;
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) return PLAIN;
        LanguageId id = SUFFIX.get(fileName.substring(dot + 1).toLowerCase(Locale.ROOT));
        return id == null ? PLAIN : id;
    }

    /** 新建文档的默认文件名。 */
    public String defaultFileName() { return "未命名" + defaultSuffix; }

    /**
     * 后缀优先，MIME 次之，最后看首行。MIME 只作补充：安卓 SAF 对 .java/.c 常报
     * application/octet-stream，反过来信它会丢语言。
     */
    public static LanguageId detect(String fileName, String mimeType, String head) {
        LanguageId byName = forFileName(fileName);
        if (byName != PLAIN) return byName;
        if (mimeType != null) {
            String mime = mimeType.toLowerCase(Locale.ROOT);
            if (mime.contains("markdown")) return MARKDOWN;
            if (mime.contains("java")) return JAVA;
            if (mime.contains("json")) return JSON;
            if (mime.contains("xml") || mime.contains("html")) return XML;
        }
        if (head != null) {
            String start = head.length() > 64 ? head.substring(0, 64) : head;
            String trimmed = start.trim();
            if (trimmed.startsWith("<?xml") || trimmed.startsWith("<!DOCTYPE")) return XML;
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) return JSON;
        }
        return PLAIN;
    }
}
