package txteditor.windows;

import javax.swing.SwingUtilities;
import java.nio.file.*;
import java.util.Arrays;
import java.util.Locale;

/** EDT edit/search time includes status, layout and immediate paint; excludes event-queue wait. */
public final class DesktopBenchmark {
    public static void main(String[] args) throws Exception {
        final EditorWindow[] holder = new EditorWindow[1];
        SwingUtilities.invokeAndWait(() -> { EditorTheme.install(false); holder[0] = new EditorWindow(); holder[0].setVisible(true); });
        EditorWindow window = holder[0];
        Path directory = Files.createTempDirectory("txteditor-benchmark-");
        Path target = directory.resolve("benchmark.txt");
        try {
            System.out.println("java=" + System.getProperty("java.runtime.version") + ", os=" + System.getProperty("os.name"));
            System.out.println("UTF16_units,operation,runs,median_ms,max_ms");
            for (int size : new int[] {1000, 10000, 100000}) {
                StringBuilder content = new StringBuilder();
                for (int j = 0; j < size / 5; j++) content.append("abc中\n");
                String text = content.toString();
                for (int operation = 0; operation < 4; operation++) {
                    long[] samples = new long[30];
                    for (int run = -10; run < 30; run++) {
                        final int op = operation;
                        final long[] elapsed = new long[1];
                        if (op < 2) {
                            SwingUtilities.invokeAndWait(() -> {
                                window.model.load(text); window.binding.refresh(); window.area.setCaretPosition(size / 2);
                                window.validate();
                                long start = System.nanoTime();
                                if (op == 0) window.area.replaceSelection("字");
                                else window.model.findNext("不存在aaaaab", 0);
                                window.area.paintImmediately(window.area.getVisibleRect());
                                elapsed[0] = System.nanoTime() - start;
                            });
                        } else {
                            if (op == 3) SafeFiles.write(target, text);
                            long start = System.nanoTime();
                            if (op == 2) SafeFiles.write(target, text);
                            else if (!SafeFiles.read(target).equals(text)) throw new AssertionError("read mismatch");
                            elapsed[0] = System.nanoTime() - start;
                        }
                        if (run >= 0) samples[run] = elapsed[0];
                    }
                    Arrays.sort(samples);
                    System.out.printf(Locale.ROOT, "%d,%s,30,%.6f,%.6f%n", size,
                            new String[] {"UI_insert_1_with_paint", "UI_KMP_with_paint", "UTF8_safe_save", "UTF8_open"}[operation],
                            (samples[14] + samples[15]) / 2.0 / 1_000_000, samples[29] / 1_000_000.0);
                }
            }
        } finally {
            SwingUtilities.invokeAndWait(window::dispose);
            Files.deleteIfExists(target); Files.delete(directory);
        }
    }
}
