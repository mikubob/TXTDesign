package txteditor.core;

import java.nio.charset.CharacterCodingException;
import java.util.Random;

public final class CoreTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        EditorModel m = new EditorModel();
        eq(1, m.getLineCount()); eq(0, m.getCodePointCount()); check(!m.undo() && !m.redo());
        rejects(() -> m.replace(-1, 0, "x")); rejects(() -> m.replace(1, 0, "x"));
        m.load("A😀中\r\n尾\r");
        eq("A😀中\n尾\n", m.getText()); eq(7, m.length()); eq(6, m.getCodePointCount()); eq(3, m.getLineCount());
        rejects(() -> m.replace(2, 3, "")); rejects(() -> m.setSelection(2, 2));
        rejects(() -> m.replace(0, 0, "\uD800")); rejects(() -> m.load("\uDC00"));
        eq("A😀中\n尾\n", m.getText());
        m.setSelection(1, 3); m.replace(1, 3, "新😀");
        check(m.isModified()); check(m.undo()); eq(1, m.getSelectionStart()); eq(3, m.getSelectionEnd());
        check(!m.isModified()); check(m.redo()); eq(4, m.getSelectionEnd());
        m.markSaved(); check(!m.isModified()); m.undo(); check(m.isModified()); m.redo(); check(!m.isModified());
        m.undo(); m.replace(0, 1, "B"); check(!m.canRedo());
        m.load("A"); m.replace(0, 1, "B"); String snapshot = m.getText();
        m.replace(0, 1, "C"); m.markSaved(snapshot); check(m.isModified()); m.undo(); check(!m.isModified());
        m.load("");
        for (int i = 0; i < 105; i++) m.replace(m.length(), m.length(), "x");
        int count = 0; while (m.undo()) count++;
        eq(100, count); eq("xxxxx", m.getText());
        while (m.redo()) { }
        eq(105, m.length());
        m.undo(); m.replace(0, 1, "x"); check(m.canRedo()); // no-op preserves redo
        m.load("aaaaab😀aaaaab"); eq(1, m.findNext("aaaab", 0)); eq(8, m.findNext("aaaaab", 6));
        eq(0, m.findNext("aaaaab", m.length())); eq(6, m.findNext("😀", 0));
        eq(-1, m.findNext("不存在", 0)); rejects(() -> m.findNext("", 0));
        eq("中文😀\nX\n", TextCodec.decodeUtf8(new byte[] {(byte)0xef,(byte)0xbb,(byte)0xbf,
                (byte)0xe4,(byte)0xb8,(byte)0xad,(byte)0xe6,(byte)0x96,(byte)0x87,
                (byte)0xf0,(byte)0x9f,(byte)0x98,(byte)0x80,13,10,88,13}));
        eq("中文😀\n", TextCodec.decodeUtf8(TextCodec.encodeUtf8("中文😀\r\n")));
        try { TextCodec.decodeUtf8(new byte[] {(byte)0xc3, 0x28}); throw new AssertionError("bad UTF-8 accepted"); }
        catch (CharacterCodingException expected) { checks++; }
        randomEdits(); randomKmp();
        System.out.println("CoreTest PASS: " + checks + " checks");
    }

    private static void randomEdits() {
        Random random = new Random(20261028L);
        GapBuffer gap = new GapBuffer();
        StringBuilder reference = new StringBuilder();
        EditorModel model = new EditorModel();
        String[] samples = {"", "a", "中文", "😀", "\n", "跨\n行😀", repeat("扩", 180)};
        for (int i = 0; i < 12000; i++) {
            int a = random.nextInt(reference.length() + 1), b = random.nextInt(reference.length() + 1);
            int start = Math.min(a, b), end = Math.max(a, b);
            while (start > 0 && !model.isBoundary(start)) start--;
            while (end < model.length() && !model.isBoundary(end)) end++;
            String inserted = samples[random.nextInt(samples.length)];
            String before = reference.toString();
            model.setSelection(start, end);
            gap.replace(start, end, inserted); reference.replace(start, end, inserted); model.replace(start, end, inserted);
            gap.checkInvariant();
            eq(reference.toString(), gap.toString()); eq(reference.toString(), model.getText()); eq(reference.length(), gap.length());
            if (!before.equals(reference.toString()) && i % 11 == 0) {
                check(model.undo()); eq(before, model.getText()); eq(start, model.getSelectionStart()); eq(end, model.getSelectionEnd());
                check(model.redo()); eq(reference.toString(), model.getText());
            }
        }
    }

    private static void randomKmp() {
        Random r = new Random(17);
        for (int i = 0; i < 6000; i++) {
            StringBuilder s = new StringBuilder(), p = new StringBuilder();
            for (int j = r.nextInt(180); j > 0; j--) s.append("aaab中".charAt(r.nextInt(5)));
            for (int j = 1 + r.nextInt(12); j > 0; j--) p.append("aaab中".charAt(r.nextInt(5)));
            GapBuffer gap = new GapBuffer(); gap.replace(0, 0, s.toString());
            int from = r.nextInt(s.length() + 1);
            eq(s.indexOf(p.toString(), from), Kmp.indexOf(gap, p.toString(), from));
        }
    }

    static String repeat(String text, int count) { StringBuilder s = new StringBuilder(); while (count-- > 0) s.append(text); return s.toString(); }
    static void check(boolean condition) { checks++; if (!condition) throw new AssertionError("check " + checks); }
    static void eq(Object expected, Object actual) { checks++; if (!expected.equals(actual)) throw new AssertionError(expected + " != " + actual); }
    static void rejects(Runnable action) {
        try { action.run(); throw new AssertionError("invalid input accepted"); }
        catch (IllegalArgumentException | IndexOutOfBoundsException expected) { checks++; }
    }
}
