package txteditor.android;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Random;
import txteditor.core.EditorModel;

public final class AdapterTest {
    private static int checks;
    private static void equal(Object expected, Object actual) {
        checks++;
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
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
            TextEdits.commit(model, next, start + insert.length(), start + insert.length());
            equal(next, model.getText());
            if (!old.equals(next)) { model.undo(); equal(old, model.getText()); model.redo(); equal(next, model.getText()); }
        }
        Draft draft = new Draft(); draft.text = "中😀\n"; draft.saved = "以前"; draft.uri = "content://example/doc/1";
        draft.start = 1; draft.end = 3; draft.search = true; draft.pattern = "中";
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); draft.write(new DataOutputStream(bytes));
        Draft read = Draft.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
        equal(draft.text, read.text); equal(draft.saved, read.saved); equal(draft.uri, read.uri);
        equal(1, read.start); equal(3, read.end); equal(true, read.search);
        try { Draft.read(new DataInputStream(new ByteArrayInputStream(new byte[]{0, 0, 0, 2}))); throw new AssertionError(); }
        catch (IOException expected) { checks++; }
        System.out.println("AdapterTest passed: " + checks + " checks (seed 1949)");
    }
}
