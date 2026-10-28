package txteditor.windows;

import txteditor.core.EditorModel;
import javax.swing.*;
import javax.swing.text.*;
import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

public final class DesktopTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try { bindingTests(); }
            catch (Exception failure) { throw new RuntimeException(failure); }
        });
        fileTests();
        System.out.println("DesktopTest PASS: " + checks + " checks");
    }

    private static void bindingTests() throws Exception {
        EditorModel model = new EditorModel();
        JTextArea area = new JTextArea();
        List<String> errors = new ArrayList<String>();
        EditorBinding binding = new EditorBinding(area, model, () -> { }, errors::add);
        AbstractDocument document = (AbstractDocument) area.getDocument();
        area.replaceSelection("中文😀\r\n尾"); eq("中文😀\n尾", model.getText()); eq(model.getText(), area.getText());
        area.select(0, 2); area.replaceSelection("新"); eq("新😀\n尾", model.getText());
        model.undo(); binding.refresh(); eq("中文😀\n尾", area.getText()); eq(0, area.getSelectionStart()); eq(2, area.getSelectionEnd());
        model.redo(); binding.refresh(); eq("新😀\n尾", area.getText());
        document.remove(2, 1); eq("新\n尾", area.getText()); eq(model.getText(), area.getText());
        model.undo(); binding.refresh(); eq("新😀\n尾", area.getText());
        area.setCaretPosition(2); eq(3, area.getCaretPosition()); eq(3, model.getSelectionEnd());
        area.select(1, 2); eq(1, area.getSelectionStart()); eq(3, area.getSelectionEnd());
        document.insertString(2, "中", null); eq("新😀中\n尾", model.getText()); eq(model.getText(), area.getText());
        document.insertString(0, "\uD800", null); eq(1, errors.size()); eq(model.getText(), area.getText());
        model.load(""); binding.refresh();
        SimpleAttributeSet attrs = new SimpleAttributeSet();
        attrs.addAttribute(StyleConstants.ComposedTextAttribute, new java.text.AttributedString("ni").getIterator());
        document.insertString(0, "ni", attrs); eq("", model.getText()); eq("ni", area.getText());
        document.remove(0, 2); document.insertString(0, "你", null);
        binding.finishInput(); eq("你", model.getText()); eq(model.getText(), area.getText());
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

    static void check(boolean value) { checks++; if (!value) throw new AssertionError("check " + checks); }
    static void eq(Object expected, Object actual) { checks++; if (!expected.equals(actual)) throw new AssertionError(expected + " != " + actual); }
}
