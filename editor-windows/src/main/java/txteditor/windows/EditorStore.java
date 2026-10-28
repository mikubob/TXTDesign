package txteditor.windows;

import txteditor.core.SessionSnapshot;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/** 会话草稿与视图偏好的落盘；写入复用 SafeFiles 的临时文件加原子移动。 */
final class EditorStore {
    /** 与安卓端一致的 16 MiB 大文件口径。 */
    static final long LARGE_FILE_LIMIT = 16L << 20;
    private static final String HEADER = "txteditor-session 2";
    /** 旧草稿没有 language/viewMode，读到时按纯文本 + 源码模式补齐。 */
    private static final String HEADER_V1 = "txteditor-session 1";
    private static final String SESSION = "session.dat";
    private static final String PREFS = "prefs.properties";

    private EditorStore() { }

    /** 默认 %LOCALAPPDATA%\TXTDesign\session；测试用 -Dtxteditor.data 指向临时目录。 */
    static Path directory() {
        String override = System.getProperty("txteditor.data");
        if (override != null && !override.isEmpty()) return Paths.get(override);
        String local = System.getenv("LOCALAPPDATA");
        Path base = local == null ? Paths.get(System.getProperty("java.io.tmpdir")) : Paths.get(local);
        return base.resolve("TXTDesign").resolve("session");
    }

    static boolean tooLarge(long size) { return size > LARGE_FILE_LIMIT; }

    static void writeSession(SessionSnapshot snapshot) throws IOException {
        StringBuilder out = new StringBuilder(HEADER).append('\n');
        field(out, snapshot.text);
        field(out, snapshot.savedText);
        field(out, snapshot.fileName);
        out.append(snapshot.selectionStart).append('\n').append(snapshot.selectionEnd).append('\n')
                .append(snapshot.scroll).append('\n');
        field(out, snapshot.pattern);
        field(out, snapshot.replacement);
        out.append(snapshot.searchOpen).append('\n');
        field(out, snapshot.language);
        out.append(snapshot.viewMode).append('\n');
        Path file = directory().resolve(SESSION);
        Files.createDirectories(file.getParent());
        SafeFiles.write(file, out.toString());
    }

    /** 缺失或损坏返回 null；文件原地保留，不做 .bak 轮转。 */
    static SessionSnapshot readSession() {
        try {
            Cursor cursor = new Cursor(SafeFiles.read(directory().resolve(SESSION)));
            String header = cursor.line();
            boolean latest = HEADER.equals(header);
            if (!latest && !HEADER_V1.equals(header)) return null;
            String text = cursor.field(), savedText = cursor.field(), fileName = cursor.field();
            int start = cursor.number(), end = cursor.number(), scroll = cursor.number();
            String pattern = cursor.field(), replacement = cursor.field();
            boolean open = Boolean.parseBoolean(cursor.line());
            String language = latest ? cursor.field() : "PLAIN";
            int viewMode = latest ? cursor.number() : 0;
            return new SessionSnapshot(text, savedText, fileName, start, end, scroll, pattern, replacement, open,
                    language, viewMode == 1 ? 1 : 0);
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    static void clearSession() {
        try { Files.deleteIfExists(directory().resolve(SESSION)); }
        catch (IOException ignored) { }
    }

    static long sessionTime() {
        try { return Files.getLastModifiedTime(directory().resolve(SESSION)).toMillis(); }
        catch (IOException missing) { return 0; }
    }

    /** 比较打开时记下的修改时间与大小；文件已消失时按未修改处理，保存会重建。 */
    static boolean externallyModified(Path path, long modified, long size) {
        try { return Files.getLastModifiedTime(path).toMillis() != modified || Files.size(path) != size; }
        catch (IOException missing) { return false; }
    }

    static long modifiedTime(Path path) {
        try { return Files.getLastModifiedTime(path).toMillis(); }
        catch (IOException missing) { return 0; }
    }

    static long size(Path path) {
        try { return Files.size(path); }
        catch (IOException missing) { return -1; }
    }

    static Properties loadPrefs() {
        Properties prefs = new Properties();
        try { prefs.load(new StringReader(SafeFiles.read(directory().resolve(PREFS)))); }
        catch (IOException | RuntimeException unreadable) { }
        return prefs;
    }

    static void savePrefs(Properties prefs) {
        try {
            StringWriter text = new StringWriter();
            prefs.store(text, "TXTDesign 视图状态");
            Path file = directory().resolve(PREFS);
            Files.createDirectories(file.getParent());
            SafeFiles.write(file, text.toString());
        } catch (IOException ignored) { }
    }

    private static void field(StringBuilder out, String value) {
        String safe = value == null ? "" : value;
        out.append(safe.length()).append('\n').append(safe).append('\n');
    }

    /** 长度前缀的字段读取；任何越界都当成损坏。 */
    private static final class Cursor {
        private final String text;
        private int at;
        Cursor(String text) { this.text = text; }
        String line() {
            int end = text.indexOf('\n', at);
            if (end < 0) throw new IllegalArgumentException("会话文件不完整");
            String value = text.substring(at, end);
            at = end + 1;
            return value;
        }
        String field() {
            int length = Integer.parseInt(line());
            if (length < 0 || at + length >= text.length()) throw new IllegalArgumentException("会话文件不完整");
            String value = text.substring(at, at + length);
            at += length;
            if (text.charAt(at) != '\n') throw new IllegalArgumentException("会话文件不完整");
            at++;
            return value;
        }
        int number() { return Integer.parseInt(line()); }
    }
}
