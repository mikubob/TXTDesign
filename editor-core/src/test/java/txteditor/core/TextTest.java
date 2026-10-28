package txteditor.core;

import java.util.Random;
import txteditor.core.text.LanguageId;
import txteditor.core.text.SyntaxHighlighter;
import txteditor.core.text.TokenSink;
import txteditor.core.text.TokenType;
import txteditor.core.text.LineIndex;
import txteditor.core.text.md.MdDocument;
import txteditor.core.text.md.MdParser;
import txteditor.core.text.md.MdSpan;

/** 高亮与 Markdown 的 JVM 测试；重点是"增量结果 == 全量结果"。 */
public final class TextTest {
    private static int checks;

    public static void main(String[] args) {
        languages();
        lineIndex();
        javaTokens();
        multiLineStates();
        incremental();
        markdown();
        System.out.println("TextTest " + checks + " checks passed");
    }

    private static void languages() {
        eq(LanguageId.MARKDOWN, LanguageId.forFileName("笔记.md"));
        eq(LanguageId.JAVA, LanguageId.forFileName("A.JAVA"));
        eq(LanguageId.C, LanguageId.forFileName("main.c"));
        eq(LanguageId.CPP, LanguageId.forFileName("a.hpp"));
        eq(LanguageId.PLAIN, LanguageId.forFileName("readme"));
        eq(LanguageId.PLAIN, LanguageId.forFileName("a."));
        eq(LanguageId.JSON, LanguageId.detect("unknown", null, "  {\"a\":1}"));
        eq(LanguageId.XML, LanguageId.detect("unknown", null, "<?xml version=\"1.0\"?>"));
        eq(LanguageId.MARKDOWN, LanguageId.detect("unknown", "text/markdown", null));
        eq("未命名.md", LanguageId.MARKDOWN.defaultFileName());
        eq(LanguageId.PLAIN, LanguageId.parse("不存在的语言"));
        eq(LanguageId.JAVA, LanguageId.parse("JAVA"));
    }

    private static void lineIndex() {
        LineIndex index = new LineIndex();
        index.load("a\nbb\nccc");
        eq(3, index.count());
        eq(0, index.start(0));
        eq(1, index.end(0));
        eq(2, index.start(1));
        eq(4, index.end(1));
        eq(8, index.end(2));
        eq(0, index.lineOf(0));
        eq(1, index.lineOf(3));
        eq(2, index.lineOf(7));

        int[] change = index.update("a\nbbX\nccc", 4, 4, 5);
        eq(1, change[0]);
        eq(2, change[1]);
        eq(0, change[2]);
        eq(0, change[3]);
        eq(3, index.count());
        eq(6, index.start(2));

        change = index.update("a\n\nbbX\nccc", 1, 1, 2);
        eq(0, change[0]);
        eq(1, change[2]);
        eq(4, index.count());
        eq(2, index.start(1));
        eq(3, index.start(2));
        eq(7, index.start(3));

        change = index.update("a\nccc", 1, 6, 1);
        eq(2, index.count());
        eq(2, index.start(1));
        eq(5, index.end(1));
    }

    private static void javaTokens() {
        SyntaxHighlighter highlighter = new SyntaxHighlighter();
        highlighter.setLanguage(LanguageId.JAVA);
        highlighter.load("public class A {\n    int x = 42; // 注释\n    String s = \"hi\";\n}\n");
        TokenSink out = new TokenSink();
        highlighter.paint(0, 3, out);
        check(has(out, TokenType.KEYWORD, "public", highlighter.text()));
        check(has(out, TokenType.KEYWORD, "class", highlighter.text()));
        check(has(out, TokenType.TYPE, "int", highlighter.text()));
        check(has(out, TokenType.NUMBER, "42", highlighter.text()));
        check(has(out, TokenType.COMMENT, "// 注释", highlighter.text()));
        check(has(out, TokenType.STRING, "\"hi\"", highlighter.text()));
        check(has(out, TokenType.OPERATOR, "{", highlighter.text()));

        highlighter.setLanguage(LanguageId.C);
        highlighter.load("#include <stdio.h>\nint main(void) { return 0; }\n");
        highlighter.paint(0, 1, out);
        check(has(out, TokenType.PREPROCESSOR, "#include <stdio.h>", highlighter.text()));
        check(has(out, TokenType.TYPE, "int", highlighter.text()));

        highlighter.setLanguage(LanguageId.JSON);
        highlighter.load("{\"a\": [1, true, null]}");
        highlighter.paint(0, 0, out);
        check(has(out, TokenType.STRING, "\"a\"", highlighter.text()));
        check(has(out, TokenType.LITERAL, "true", highlighter.text()));

        highlighter.setLanguage(LanguageId.XML);
        highlighter.load("<a href=\"x\"><!-- c --></a>");
        highlighter.paint(0, 0, out);
        check(has(out, TokenType.TAG, "a", highlighter.text()));
        check(has(out, TokenType.ATTRIBUTE, "href", highlighter.text()));
        check(has(out, TokenType.STRING, "\"x\"", highlighter.text()));
        check(has(out, TokenType.COMMENT, "<!-- c -->", highlighter.text()));
    }

    private static void multiLineStates() {
        SyntaxHighlighter highlighter = new SyntaxHighlighter();
        highlighter.setLanguage(LanguageId.JAVA);
        highlighter.load("/*\n中间\n*/\nint a;\n");
        TokenSink out = new TokenSink();
        highlighter.paint(0, 0, out);
        check(out.size() == 1 && out.type(0) == TokenType.COMMENT);
        highlighter.paint(1, 1, out);
        check(out.size() == 1 && out.type(0) == TokenType.COMMENT);
        highlighter.paint(2, 2, out);
        check(out.size() == 1 && out.type(0) == TokenType.COMMENT);
        highlighter.paint(3, 3, out);
        check(has(out, TokenType.TYPE, "int", highlighter.text()));

        // 未闭合的注释一直延续到文件尾，不能报错也不能吞掉边界。
        highlighter.load("/* 没有结束\n仍注释");
        highlighter.paint(0, 1, out);
        check(out.size() == 2);
        eq(TokenType.COMMENT, out.type(1));
    }

    private static void incremental() {
        Random random = new Random(20261028L);
        for (LanguageId language : new LanguageId[] {LanguageId.JAVA, LanguageId.MARKDOWN, LanguageId.C}) {
            StringBuilder buffer = new StringBuilder(seed(language));
            SyntaxHighlighter highlighter = new SyntaxHighlighter();
            highlighter.setLanguage(language);
            highlighter.load(buffer.toString());
            for (int step = 0; step < 1200; step++) {
                int start, oldEnd, newEnd;
                int operation = random.nextInt(4);
                if (operation == 0 || buffer.length() == 0) {
                    int at = random.nextInt(buffer.length() + 1);
                    String insert = fragment(random, language);
                    start = at;
                    oldEnd = at;
                    buffer.insert(at, insert);
                    newEnd = at + insert.length();
                } else if (operation == 1) {
                    int at = random.nextInt(buffer.length());
                    int length = Math.min(buffer.length() - at, 1 + random.nextInt(6));
                    start = at;
                    oldEnd = at + length;
                    buffer.delete(at, oldEnd);
                    newEnd = at;
                } else {
                    int at = random.nextInt(buffer.length());
                    int length = Math.min(buffer.length() - at, 1 + random.nextInt(4));
                    String insert = fragment(random, language);
                    start = at;
                    oldEnd = at + length;
                    buffer.replace(at, oldEnd, insert);
                    newEnd = at + insert.length();
                }
                highlighter.update(buffer.toString(), start, oldEnd, newEnd);
                eq(dump(highlighter), full(buffer.toString(), language));
            }
        }
    }

    private static void markdown() {
        String source = "# 标题\n\n正文 **粗体** 与 `code` 和 [链接](http://a.b)\n\n"
                + "```java\nint x;\n```\n\n- 项目\n> 引用\n";
        MdDocument document = MdParser.parse(source);
        eq(5, document.count());
        eq(MdDocument.HEADING, document.type(0));
        eq((byte) 1, document.level(0));
        eq("标题", document.source(0));
        eq(MdDocument.PARAGRAPH, document.type(1));
        eq(MdDocument.CODE, document.type(2));
        eq("java", document.language(2));
        eq("int x;", document.source(2));
        eq(MdDocument.LIST_ITEM, document.type(3));
        eq("项目", document.source(3));
        eq(MdDocument.QUOTE, document.type(4));
        eq((byte) 1, document.quote(4));

        MdSpan[] spans = document.inline(1);
        eq(1, count(spans, MdSpan.BOLD));
        eq(1, count(spans, MdSpan.CODE));
        eq(1, count(spans, MdSpan.LINK));
        boolean hidesMarkers = false;
        for (MdSpan span : spans) if (span.marker) hidesMarkers = true;
        check(hidesMarkers);

        MdDocument tasks = MdParser.parse("- [x] 已完成\n- [ ] 未完成\n\n| a | b |\n|---|---|\n| 1 | 2 |\n");
        eq(3, tasks.count());
        check(tasks.checked(0));
        check(!tasks.checked(1));
        eq(MdDocument.TABLE, tasks.type(2));

        eq(0, MdParser.parse("").count());
        eq(1, MdParser.parse("没有块标记的一段文字").count());
    }

    private static String seed(LanguageId language) {
        if (language == LanguageId.MARKDOWN) {
            return "# 标题\n\n正文 **粗体** 与 `code`。\n\n```java\nint x = 1;\n```\n\n- 甲\n- 乙\n";
        }
        return "public class A {\n    int x = 42; // 注释\n    String s = \"hi\";\n"
                + "    /* 块\n       注释 */\n}\n";
    }

    private static String fragment(Random random, LanguageId language) {
        String[] pieces = language == LanguageId.MARKDOWN
                ? new String[] {"a", "\n", "*", "`", "#", ">", "- ", "|", "[x](y)", "```", "中文", " "}
                : new String[] {"x", "\n", "/*", "*/", "\"", "'", "\\", "#", "@", "1", "_", " ", "中"};
        return pieces[random.nextInt(pieces.length)];
    }

    private static String full(String text, LanguageId language) {
        SyntaxHighlighter fresh = new SyntaxHighlighter();
        fresh.setLanguage(language);
        fresh.load(text);
        return dump(fresh);
    }

    private static String dump(SyntaxHighlighter highlighter) {
        TokenSink out = new TokenSink();
        highlighter.paint(0, highlighter.lineCount() - 1, out);
        StringBuilder builder = new StringBuilder(out.size() * 8);
        for (int i = 0; i < out.size(); i++) {
            builder.append(out.type(i)).append(':').append(out.start(i)).append('-').append(out.end(i)).append(';');
        }
        return builder.toString();
    }

    private static boolean has(TokenSink out, byte type, String text, String source) {
        for (int i = 0; i < out.size(); i++) {
            if (out.type(i) != type) continue;
            if (out.end(i) <= source.length() && source.substring(out.start(i), out.end(i)).equals(text)) return true;
        }
        return false;
    }

    private static int count(MdSpan[] spans, byte type) {
        int total = 0;
        for (MdSpan span : spans) if (span.type == type && !span.marker) total++;
        return total;
    }

    private static void eq(Object expected, Object actual) {
        checks++;
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError("expected " + expected + " but was " + actual);
        }
    }

    private static void check(boolean condition) {
        checks++;
        if (!condition) throw new AssertionError("check failed");
    }

    private TextTest() { }
}
