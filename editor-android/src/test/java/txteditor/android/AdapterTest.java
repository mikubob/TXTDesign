package txteditor.android;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Random;
import txteditor.core.EditorModel;
import txteditor.core.SessionSnapshot;
import txteditor.core.text.LanguageId;

public final class AdapterTest {
    private static int checks;
    private static void equal(Object expected, Object actual) {
        checks++;
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }

    /** TextEdits 的三元组必须能原样重建新文本，且文本没变时必须为 null。 */
    private static void sameChange(String before, int[] change, String after) {
        checks++;
        if (before.equals(after)) {
            if (change != null) throw new AssertionError("文本未变却返回了三元组");
            return;
        }
        if (change == null || change.length != 3) throw new AssertionError("缺少变更三元组");
        int start = change[0], oldEnd = change[1], newEnd = change[2];
        if (start < 0 || oldEnd < start || oldEnd > before.length() || newEnd < start || newEnd > after.length())
            throw new AssertionError("三元组越界 " + start + "," + oldEnd + "," + newEnd);
        String rebuilt = before.substring(0, start) + after.substring(start, newEnd) + before.substring(oldEnd);
        if (!rebuilt.equals(after)) throw new AssertionError("三元组无法重建 " + start + "," + oldEnd + "," + newEnd);
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes("UTF-8"); out.writeInt(bytes.length); out.write(bytes);
    }

    public static void main(String[] args) throws Exception {
        EditorModel model = new EditorModel();
        TextEdits.commit(model, "中文😀\n", 5, 5);
        equal("中文😀\n", model.getText()); equal(4, model.getCodePointCount()); equal(2, model.getLineCount());
        model.undo(); equal("", model.getText()); model.redo(); equal("中文😀\n", model.getText());
        model.load("A😀B"); model.setSelection(3, 3);
        TextEdits.commit(model, "A\uD83DB", 2, 2); // Some keyboards delete only the low surrogate.
        equal("AB", model.getText()); equal(1, model.getSelectionStart()); model.undo(); equal("A😀B", model.getText()); equal(3, model.getSelectionStart());
        model.load("😀😀"); TextEdits.commit(model, "😁😀", 2, 2); equal("😁😀", model.getText());
        model.undo(); equal("😀😀", model.getText());
        model.load("hello 世界\n"); model.setSelection(6, 8);
        TextEdits.commit(model, "hello 中文\n", 8, 8); model.undo();
        equal(6, model.getSelectionStart()); equal(8, model.getSelectionEnd());
        model.load("");
        // Composition candidates stay in the widget; only the final committed candidate reaches this method.
        TextEdits.commit(model, "中文", 2, 2); model.undo(); equal("", model.getText());
        model.load("abc abc"); int hit = model.findNext("abc", 3); equal(4, hit);
        model.setSelection(hit, hit + 3); model.replace(hit, hit + 3, "中😀");
        equal("abc 中😀", model.getText()); model.undo(); equal("abc abc", model.getText());
        Random random = new Random(1949);
        String[] pieces = {"中", "😀", "\n", "x", "😁", "", "abc"};
        model.load("");
        for (int i = 0; i < 2000; i++) {
            String old = model.getText();
            int start = TextEdits.boundary(old, random.nextInt(old.length() + 1), false);
            int end = TextEdits.boundary(old, start + random.nextInt(old.length() - start + 1), true);
            String insert = pieces[random.nextInt(pieces.length)];
            String next = old.substring(0, start) + insert + old.substring(end);
            int[] change = TextEdits.commit(model, next, start + insert.length(), start + insert.length());
            equal(next, model.getText());
            sameChange(old, change, next);
            if (!old.equals(next)) { model.undo(); equal(old, model.getText()); model.redo(); equal(next, model.getText()); }
        }
        // A1：整次提交包在事务里，撤销一步回到提交前的文本与选区。
        model.load("中文😀\nabc abc"); model.setSelection(0, 2);
        model.beginTransaction();
        try { TextEdits.commit(model, "新😀\nabc abc", 1, 1); }
        finally { model.endTransaction(); }
        equal("新😀\nabc abc", model.getText());
        equal(true, model.undo()); equal("中文😀\nabc abc", model.getText());
        equal(0, model.getSelectionStart()); equal(2, model.getSelectionEnd());
        // B1：三元组的边界用例（首、尾、整段、跨行、代理项、无变化、CRLF 归一化）。
        model.load("");
        sameChange("", TextEdits.commit(model, "", 0, 0), "");
        model.load("abc");
        sameChange("abc", TextEdits.commit(model, "xabc", 1, 1), "xabc");
        model.load("abc");
        sameChange("abc", TextEdits.commit(model, "abcx", 4, 4), "abcx");
        model.load("abc");
        sameChange("abc", TextEdits.commit(model, "aXc", 2, 2), "aXc");
        model.load("abc");
        sameChange("abc", TextEdits.commit(model, "", 0, 0), "");
        model.load("a\nb\nc");
        sameChange("a\nb\nc", TextEdits.commit(model, "a\nc", 2, 2), "a\nc");
        model.load("A😀B");
        sameChange("A😀B", TextEdits.commit(model, "A😁B", 3, 3), "A😁B");
        model.load("a\nb");
        sameChange("a\nb", TextEdits.commit(model, "a\r\nb", 3, 3), "a\nb"); // 归一化后无变化
        model.load("中文😀");
        sameChange("中文😀", TextEdits.commit(model, "中文😀😀", 4, 4), "中文😀😀");
        // B2：语言识别——后缀优先、MIME 与首行兜底、未知回落纯文本。
        equal(LanguageId.JAVA, LanguageId.forFileName("Foo.java"));
        equal(LanguageId.MARKDOWN, LanguageId.forFileName("README.MARKDOWN"));
        equal(LanguageId.PLAIN, LanguageId.forFileName("notes.txt"));
        equal(LanguageId.PLAIN, LanguageId.forFileName("noext"));
        equal(LanguageId.CPP, LanguageId.forFileName("a.hpp"));
        equal(LanguageId.XML, LanguageId.forFileName("page.html"));
        equal(LanguageId.MARKDOWN, LanguageId.detect("README.md", "text/plain", null));
        equal(LanguageId.JAVA, LanguageId.detect("Main.java", "application/octet-stream", null));
        equal(LanguageId.JAVA, LanguageId.detect("Main", "text/x-java", null));
        equal(LanguageId.JSON, LanguageId.detect("blob", null, "  {\"a\":1}"));
        equal(LanguageId.XML, LanguageId.detect("blob", "application/octet-stream", "<?xml version=\"1.0\"?>"));
        equal(LanguageId.PLAIN, LanguageId.detect("blob", "application/octet-stream", "随便一段中文"));
        equal(LanguageId.PLAIN, LanguageId.detect(null, null, null));
        equal("未命名.md", LanguageId.MARKDOWN.defaultFileName());
        equal("未命名.txt", LanguageId.PLAIN.defaultFileName());
        equal(LanguageId.PLAIN, LanguageId.parse("NOPE"));
        // A2：草稿字段顺序固定，往返后每个字段落在对应的 SessionSnapshot 成员上。
        SessionSnapshot snapshot = new SessionSnapshot("中😀\n", "以前", "草稿.md", 1, 3, 7, "中", "换", true,
                "MARKDOWN", 1);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DraftFormat.write(new DataOutputStream(bytes), snapshot, "content://example/doc/1");
        DraftFormat.Decoded read = DraftFormat.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
        equal(snapshot.text, read.snapshot.text); equal(snapshot.savedText, read.snapshot.savedText);
        equal("content://example/doc/1", read.uri); equal(snapshot.fileName, read.snapshot.fileName);
        equal(1, read.snapshot.selectionStart); equal(3, read.snapshot.selectionEnd); equal(7, read.snapshot.scroll);
        equal("中", read.snapshot.pattern); equal("换", read.snapshot.replacement); equal(true, read.snapshot.searchOpen);
        equal("MARKDOWN", read.snapshot.language); equal(1, read.snapshot.viewMode);
        // A3：版本 1 的旧草稿必须仍能读，缺省纯文本 + 源码模式。
        ByteArrayOutputStream legacy = new ByteArrayOutputStream();
        DataOutputStream old = new DataOutputStream(legacy);
        old.writeInt(1);
        writeString(old, "旧😀"); writeString(old, "旧"); writeString(old, "content://old/doc");
        writeString(old, "旧.md"); writeString(old, ""); writeString(old, "");
        old.writeInt(1); old.writeInt(2); old.writeInt(3); old.writeBoolean(false);
        DraftFormat.Decoded restored = DraftFormat.read(new DataInputStream(new ByteArrayInputStream(legacy.toByteArray())));
        equal("旧😀", restored.snapshot.text); equal("旧.md", restored.snapshot.fileName);
        equal(1, restored.snapshot.selectionStart); equal(2, restored.snapshot.selectionEnd); equal(3, restored.snapshot.scroll);
        equal("PLAIN", restored.snapshot.language); equal(0, restored.snapshot.viewMode);
        // 未知视图模式按源码处理，脏值不会让界面进入预览。
        SessionSnapshot odd = new SessionSnapshot("x", "x", "x.md", 0, 0, 0, "", "", false, "MARKDOWN", 7);
        ByteArrayOutputStream oddBytes = new ByteArrayOutputStream();
        DraftFormat.write(new DataOutputStream(oddBytes), odd, "");
        equal(0, DraftFormat.read(new DataInputStream(new ByteArrayInputStream(oddBytes.toByteArray()))).snapshot.viewMode);
        try { DraftFormat.read(new DataInputStream(new ByteArrayInputStream(new byte[]{0, 0, 0, 3}))); throw new AssertionError(); }
        catch (IOException expected) { checks++; }
        System.out.println("AdapterTest passed: " + checks + " checks (seed 1949)");
    }
}
