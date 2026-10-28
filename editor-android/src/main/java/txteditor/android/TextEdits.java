package txteditor.android;

import txteditor.core.EditorModel;
import txteditor.core.TextCodec;

/** Maps the platform's final text to one shared-core edit, including an IME commit. */
final class TextEdits {
    private TextEdits() { }

    static int boundary(String text, int offset, boolean forward) {
        int i = Math.max(0, Math.min(offset, text.length()));
        if (i > 0 && i < text.length() && Character.isHighSurrogate(text.charAt(i - 1))
                && Character.isLowSurrogate(text.charAt(i))) return forward ? i + 1 : i - 1;
        return i;
    }

    static String committedText(String text) {
        StringBuilder valid = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1))) {
                    valid.append(c).append(text.charAt(++i));
                }
            } else if (!Character.isLowSurrogate(c)) valid.append(c);
        }
        return TextCodec.normalize(valid.toString());
    }

    static void commit(EditorModel model, String platformText, int selectionStart, int selectionEnd) {
        String next = committedText(platformText);
        String old = model.getText();
        int start = 0;
        while (start < old.length() && start < next.length() && old.charAt(start) == next.charAt(start)) start++;
        start = Math.min(boundary(old, start, false), boundary(next, start, false));
        int endOld = old.length(), endNew = next.length();
        while (endOld > start && endNew > start && old.charAt(endOld - 1) == next.charAt(endNew - 1)) {
            endOld--; endNew--;
        }
        if (boundary(old, endOld, true) != endOld || boundary(next, endNew, true) != endNew) {
            endOld++; endNew++;
        }
        if (!old.equals(next)) model.replace(start, endOld, next.substring(start, endNew));
        int rawStart = boundary(platformText, Math.min(selectionStart, selectionEnd), false);
        int rawEnd = boundary(platformText, Math.max(selectionStart, selectionEnd), true);
        int a = boundary(next, committedText(platformText.substring(0, rawStart)).length(), false);
        int b = boundary(next, committedText(platformText.substring(0, rawEnd)).length(), true);
        model.setSelection(a, b);
    }
}
