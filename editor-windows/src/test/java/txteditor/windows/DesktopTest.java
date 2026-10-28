package txteditor.windows;

import txteditor.core.EditorModel;
import txteditor.core.SessionSnapshot;
import txteditor.core.text.LanguageId;
import txteditor.core.text.SyntaxHighlighter;
import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.text.*;
import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public final class DesktopTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try { bindingTests(); groupingTests(); highlightTests(); previewTests(); chooserTests(); }
            catch (Exception failure) { throw new RuntimeException(failure); }
        });
        fileTests();
        sessionTests();
        System.out.println("DesktopTest PASS: " + checks + " checks");
    }

    private static void bindingTests() throws Exception {
        EditorModel model = new EditorModel();
        JTextPane area = new JTextPane();
        List<String> errors = new ArrayList<String>();
        EditorBinding binding = new EditorBinding(area, model, () -> { }, errors::add);
        AbstractDocument document = (AbstractDocument) area.getDocument();
        area.replaceSelection("中文😀\r\n尾"); eq("中文😀\n尾", model.getText()); eq(model.getText(), SyntaxPainter.text(area));
        area.select(0, 2); area.replaceSelection("新"); eq("新😀\n尾", model.getText());
        model.undo(); binding.refresh(); eq("中文😀\n尾", SyntaxPainter.text(area)); eq(0, area.getSelectionStart()); eq(2, area.getSelectionEnd());
        model.redo(); binding.refresh(); eq("新😀\n尾", SyntaxPainter.text(area));
        document.remove(2, 1); eq("新\n尾", SyntaxPainter.text(area)); eq(model.getText(), SyntaxPainter.text(area));
        model.undo(); binding.refresh(); eq("新😀\n尾", SyntaxPainter.text(area));
        area.setCaretPosition(2); eq(3, area.getCaretPosition()); eq(3, model.getSelectionEnd());
        area.select(1, 2); eq(1, area.getSelectionStart()); eq(3, area.getSelectionEnd());
        document.insertString(2, "中", null); eq("新😀中\n尾", model.getText()); eq(model.getText(), SyntaxPainter.text(area));
        document.insertString(0, "\uD800", null); eq(1, errors.size()); eq(model.getText(), SyntaxPainter.text(area));
        model.load(""); binding.refresh();
        SimpleAttributeSet attrs = new SimpleAttributeSet();
        attrs.addAttribute(StyleConstants.ComposedTextAttribute, new java.text.AttributedString("ni").getIterator());
        document.insertString(0, "ni", attrs); eq("", model.getText()); eq("ni", SyntaxPainter.text(area));
        document.remove(0, 2); document.insertString(0, "你", null);
        binding.finishInput(); eq("你", model.getText()); eq(model.getText(), SyntaxPainter.text(area));
        check(model.undo()); eq("", model.getText()); check(!model.canUndo());
        model.load("旧"); binding.refresh(); area.selectAll();
        document.replace(0, 1, "shi", attrs); document.replace(0, 3, "是", null);
        binding.finishInput(); eq("是", model.getText());
        model.undo(); eq("旧", model.getText()); check(!model.canUndo());
    }

    private static void fileTests() throws Exception {
        Path directory = Files.createTempDirectory("txteditor-test-");
        try {
            Path target = directory.resolve("中文.txt");
            SafeFiles.write(target, "中文😀\r\n"); eq("中文😀\n", SafeFiles.read(target));
            byte[] output = Files.readAllBytes(target); check(!(output[0] == (byte) 0xef));
            Files.write(target, new byte[] {(byte) 0xef,(byte) 0xbb,(byte) 0xbf,65,13,10,66,13});
            eq("A\nB\n", SafeFiles.read(target));
            Files.write(target, new byte[] {(byte) 0xff});
            try { SafeFiles.read(target); throw new AssertionError("invalid UTF-8 accepted"); }
            catch (CharacterCodingException expected) { checks++; }
            SafeFiles.write(target, "原文");
            SafeFiles.write(target, "新文😀", (from, to, atomic) -> {
                if (atomic) throw new AtomicMoveNotSupportedException(from.toString(), to.toString(), "test");
                Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
            });
            eq("新文😀", SafeFiles.read(target));
            try {
                SafeFiles.write(target, "待恢复", (from, to, atomic) -> {
                    if (atomic) throw new AtomicMoveNotSupportedException(from.toString(), to.toString(), "test");
                    Files.write(to, new byte[] {0});
                    throw new IOException("模拟替换中途失败");
                });
                throw new AssertionError("write should fail");
            } catch (IOException expected) { check(expected.getMessage().contains(".pending")); check(expected.getMessage().contains(".backup")); }
            eq("新文😀", SafeFiles.read(target));
            boolean foundPending = false, foundBackup = false;
            try (DirectoryStream<Path> files = Files.newDirectoryStream(directory)) {
                for (Path path : files) {
                    if (path.toString().endsWith(".pending")) { eq("待恢复", SafeFiles.read(path)); foundPending = true; }
                    if (path.toString().endsWith(".backup")) { eq("新文😀", SafeFiles.read(path)); foundBackup = true; }
                }
            }
            check(foundPending && foundBackup);
            EditorModel model = new EditorModel(); model.load("原文"); model.replace(0, 2, "保留内容");
            try { SafeFiles.write(directory.resolve("不存在/失败.txt"), model.getText()); throw new AssertionError("expected failure"); }
            catch (IOException expected) { eq("保留内容", model.getText()); check(model.isModified()); }
        } finally {
            try (DirectoryStream<Path> files = Files.newDirectoryStream(directory)) { for (Path path : files) Files.deleteIfExists(path); }
            Files.delete(directory);
        }
    }

    /** 连续提交合并成一条历史；换行断开合并。 */
    private static void groupingTests() throws Exception {
        EditorModel model = new EditorModel();
        JTextPane area = new JTextPane();
        EditorBinding binding = new EditorBinding(area, model, () -> { }, message -> { });
        AbstractDocument document = (AbstractDocument) area.getDocument();
        for (String piece : new String[] {"连", "续", "输", "入"}) document.insertString(document.getLength(), piece, null);
        check(model.canUndo());
        check(model.undo()); eq("", model.getText()); check(!model.canUndo());
        check(model.redo()); eq("连续输入", model.getText());
        binding.finishInput();
        document.insertString(document.getLength(), "\n", null);
        document.insertString(document.getLength(), "新", null);
        binding.finishInput();
        check(model.undo()); eq("连续输入\n", model.getText());
        check(model.undo()); eq("连续输入", model.getText());
    }

    /** 语言识别、脏行区间与上色窗口：都是平台层自己算的部分。 */
    private static void highlightTests() {
        eq(LanguageId.JAVA, LanguageId.forFileName("A.java"));
        eq(LanguageId.MARKDOWN, LanguageId.forFileName("说明.MD"));
        eq(LanguageId.CPP, LanguageId.forFileName("a.hpp"));
        eq(LanguageId.PLAIN, LanguageId.forFileName("无后缀"));
        eq(LanguageId.XML, LanguageId.detect("a.txt", null, "<?xml version=\"1.0\"?>"));
        eq(LanguageId.JSON, LanguageId.detect("a.txt", "application/json", "x"));
        eq(LanguageId.MARKDOWN, LanguageId.detect("a.txt", "text/markdown", ""));
        eq(LanguageId.JAVA, LanguageId.detect("A.java", "application/octet-stream", ""));
        eq(".md", LanguageId.MARKDOWN.defaultSuffix);

        SyntaxHighlighter highlighter = new SyntaxHighlighter();
        highlighter.setLanguage(LanguageId.JAVA);
        String code = "class A {\n  // x\n}\n";
        String edited = "class A {\n  int y;\n}\n";
        highlighter.load(code);
        eq(4, highlighter.lineCount()); // 末尾换行也算一行
        eq(1, highlighter.update(edited, 10, 16, 18)); // 只改第二行 → 脏行 1
        String prepended = "// 头\n" + edited;
        eq(0, highlighter.update(prepended, 0, 0, 5)); // 文件头插入一行 → 从第 0 行脏
        eq(5, highlighter.lineCount());

        int[] visible = SyntaxPainter.paintRange(1000, 500, 540, false);
        eq(500, visible[0]); eq(540, visible[1]);
        int[] prefetch = SyntaxPainter.paintRange(1000, 500, 540, true);
        eq(300, prefetch[0]); eq(740, prefetch[1]);
        int[] head = SyntaxPainter.paintRange(10, 0, 3, true);
        eq(0, head[0]); eq(9, head[1]);
    }

    /** 预览按块排版：标记符不出现，块之间换行，代码块与表格保留内容。 */
    private static void previewTests() {
        MarkdownPreview preview = new MarkdownPreview();
        preview.render("# 标题\n\n段落 **粗体** 与 `代码`\n\n- 一\n- 二\n\n```java\nint x;\n```\n\n"
                + "| a | b |\n| --- | --- |\n| 1 | 2 |\n", false);
        String text = SyntaxPainter.text(preview.pane());
        check(!text.contains("**")); check(!text.contains("```")); check(!text.contains("---"));
        check(text.contains("标题")); check(text.contains("粗体")); check(text.contains("int x;"));
        check(text.split("\n").length >= 7); // 每个块独占一段
        check(preview.pane().getDocument() instanceof DefaultStyledDocument);
        preview.render("", true);
        check(SyntaxPainter.text(preview.pane()).contains("空文档"));
    }

    private static void chooserTests() {
        JFileChooser markdown = EditorWindow.chooser(null, LanguageId.MARKDOWN.defaultFileName());
        check(markdown.isAcceptAllFileFilterUsed());
        check(((FileNameExtensionFilter) markdown.getFileFilter()).getExtensions().length > 0);
        check(markdown.getFileFilter().getDescription().contains("Markdown"));
        eq(LanguageId.MARKDOWN.defaultFileName(), markdown.getSelectedFile().getName());
        check(EditorWindow.chooser(null, "a.java").getFileFilter().getDescription().contains("源代码"));
        check(EditorWindow.chooser(null, "a.txt").getFileFilter().getDescription().contains("纯文本"));
        check(EditorWindow.chooser(null, "a.java").getChoosableFileFilters().length >= 3);
    }

    private static void sessionTests() throws Exception {
        Path directory = Files.createTempDirectory("txteditor-session-");
        String previous = System.getProperty("txteditor.data");
        System.setProperty("txteditor.data", directory.toString());
        try {
            check(EditorStore.readSession() == null); eq(0L, EditorStore.sessionTime());
            SessionSnapshot out = new SessionSnapshot("中文😀\n第二行", "中文", "目录/文件.txt", 2, 4, 120, "中文", "\n", true);
            EditorStore.writeSession(out);
            SessionSnapshot in = EditorStore.readSession();
            eq(out.text, in.text); eq(out.savedText, in.savedText); eq(out.fileName, in.fileName);
            eq(out.selectionStart, in.selectionStart); eq(out.selectionEnd, in.selectionEnd); eq(out.scroll, in.scroll);
            eq(out.pattern, in.pattern); eq(out.replacement, in.replacement); eq(true, in.searchOpen);
            check(EditorStore.sessionTime() > 0);
            Files.write(directory.resolve("session.dat"), new byte[] {(byte) 0xff, 1, 2});
            check(EditorStore.readSession() == null);
            Files.write(directory.resolve("session.dat"), "txteditor-session 1\nabc\n".getBytes("UTF-8"));
            check(EditorStore.readSession() == null);
            // v2：language 与 viewMode 往返，头部已升到 2
            EditorStore.writeSession(new SessionSnapshot("文", "", null, 0, 0, 0, "", "", false, "JAVA", 1));
            check(SafeFiles.read(directory.resolve("session.dat")).startsWith("txteditor-session 2\n"));
            SessionSnapshot latest = EditorStore.readSession();
            eq("JAVA", latest.language); eq(1, latest.viewMode); eq("文", latest.text);
            // v1：缺 language/viewMode，按纯文本 + 源码补齐
            Files.write(directory.resolve("session.dat"), ("txteditor-session 1\n"
                    + "1\n旧\n" + "0\n\n" + "0\n\n" + "0\n0\n0\n" + "0\n\n" + "0\n\n" + "true\n").getBytes("UTF-8"));
            SessionSnapshot legacy = EditorStore.readSession();
            eq("旧", legacy.text); eq("PLAIN", legacy.language); eq(0, legacy.viewMode); check(legacy.searchOpen);
            EditorStore.clearSession(); check(!Files.exists(directory.resolve("session.dat")));
            Properties prefs = new Properties(); prefs.setProperty("theme", "dark");
            EditorStore.savePrefs(prefs); eq("dark", EditorStore.loadPrefs().getProperty("theme"));
            Path target = directory.resolve("外部.txt");
            SafeFiles.write(target, "一");
            long modified = Files.getLastModifiedTime(target).toMillis(), size = Files.size(target);
            check(!EditorStore.externallyModified(target, modified, size));
            SafeFiles.write(target, "一二三四");
            check(EditorStore.externallyModified(target, modified, size));
            check(!EditorStore.externallyModified(directory.resolve("不存在.txt"), modified, size));
            check(!EditorStore.tooLarge(16L << 20)); check(EditorStore.tooLarge((16L << 20) + 1));
        } finally {
            if (previous == null) System.clearProperty("txteditor.data"); else System.setProperty("txteditor.data", previous);
            try (DirectoryStream<Path> files = Files.newDirectoryStream(directory)) { for (Path path : files) Files.deleteIfExists(path); }
            Files.delete(directory);
        }
    }

    static void check(boolean value) { checks++; if (!value) throw new AssertionError("check " + checks); }
    static void eq(Object expected, Object actual) { checks++; if (!expected.equals(actual)) throw new AssertionError(expected + " != " + actual); }
}
