package txteditor.core;

import java.util.Arrays;
import java.util.Random;

/** Small reproducible experiment, not a microbenchmark framework. */
public final class Benchmark {
    private static volatile int sink;
    public static void main(String[] args) {
        System.out.println("java=" + System.getProperty("java.runtime.version") + ", vm=" + System.getProperty("java.vm.name"));
        System.out.println("os=" + System.getProperty("os.name") + ", arch=" + System.getProperty("os.arch")
                + ", processors=" + Runtime.getRuntime().availableProcessors());
        System.out.println("UTF16_units,operation,runs,median_ms,max_ms");
        for (int size : new int[] {1000, 10000, 100000}) {
            String text = CoreTest.repeat("abc中\n", size / 5);
            for (int operation = 0; operation < 4; operation++) {
                long[] values = new long[30];
                for (int run = -10; run < 30; run++) {
                    EditorModel model = new EditorModel(); model.load(text);
                    Random random = new Random(17);
                    String paste = CoreTest.repeat("粘贴😀\n", 2000);
                    long start = System.nanoTime();
                    switch (operation) {
                        case 0:
                            for (int j = 0; j < 200; j++) model.replace(model.length(), model.length(), "字");
                            break;
                        case 1:
                            for (int j = 0; j < 100; j++) { int at = random.nextInt(model.length()); model.replace(at, at + 1, "改"); }
                            break;
                        case 2: model.replace(size / 2, size / 2, paste); break;
                        case 3: sink = model.findNext("不存在aaaaab", 0); break;
                        default: throw new AssertionError();
                    }
                    long elapsed = System.nanoTime() - start;
                    sink = model.length();
                    if (run >= 0) values[run] = elapsed;
                }
                Arrays.sort(values);
                System.out.printf(java.util.Locale.ROOT, "%d,%s,30,%.6f,%.6f%n", size,
                        new String[] {"append_200", "random_replace_100", "paste_10000", "KMP_no_match"}[operation],
                        (values[14] + values[15]) / 2.0 / 1_000_000, values[29] / 1_000_000.0);
            }
        }
    }
}
