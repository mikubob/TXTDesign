package txteditor.windows;

import txteditor.core.TextCodec;
import txteditor.core.TextKeys;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

final class SafeFiles {
    interface Mover { void move(Path from, Path to, boolean atomic) throws IOException; }

    /** 文件内容、本次是否只拿到了开头一段，以及打开时的文件大小。 */
    static final class Loaded {
        final String text;
        final boolean partial;
        final long size;
        Loaded(String text, boolean partial, long size) { this.text = text; this.partial = partial; this.size = size; }
    }

    private static final Mover SYSTEM_MOVE = (from, to, atomic) -> {
        if (atomic) Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        else Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
    };

    static String read(Path path) throws IOException { return TextCodec.decodeUtf8(Files.readAllBytes(path)); }

    /**
     * 打开文件。超过 limit 的大文件只读取开头 limit 字节用于只读展示——
     * 整份读入会让几百 MB 的文件直接把堆撑爆，而只读视图本来也改不回去。
     */
    static Loaded readForDisplay(Path path, long limit) throws IOException {
        long size = Files.size(path);
        if (size <= limit) return new Loaded(TextCodec.decodeUtf8(Files.readAllBytes(path)), false, size);
        int cap = (int) Math.min(limit, Integer.MAX_VALUE - 8);
        byte[] head = new byte[cap];
        int filled = 0;
        try (FileChannel input = FileChannel.open(path, StandardOpenOption.READ)) {
            ByteBuffer buffer = ByteBuffer.wrap(head);
            while (buffer.hasRemaining()) { if (input.read(buffer) < 0) break; }
            filled = buffer.position();
        }
        return new Loaded(TextCodec.decodeUtf8(cutAfterCharBoundary(head, filled)), true, size);
    }

    /** 截断点可能落在多字节序列中间，退回到最后一个完整字符，免得被当成非法 UTF-8 拒绝。 */
    private static byte[] cutAfterCharBoundary(byte[] bytes, int length) {
        int start = length - 1;
        while (start >= 0 && length - start <= 4 && (bytes[start] & 0xC0) == 0x80) start--;
        if (start < 0) return new byte[0];
        int lead = bytes[start] & 0xFF;
        int need = (lead & 0xE0) == 0xC0 ? 2 : (lead & 0xF0) == 0xE0 ? 3 : (lead & 0xF8) == 0xF0 ? 4 : 1;
        int end = length - start >= need ? length : start;
        byte[] trimmed = new byte[end];
        System.arraycopy(bytes, 0, trimmed, 0, end);
        return trimmed;
    }

    static void write(Path path, String text) throws IOException { write(path, text, SYSTEM_MOVE); }

    static void write(Path path, String text, Mover mover) throws IOException {
        byte[] bytes = TextCodec.encodeUtf8(text);
        Path target = path.toAbsolutePath();
        Path temp = Files.createTempFile(target.getParent(), ".txteditor-", ".pending");
        Path backup = null;
        try {
            try (FileChannel output = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) output.write(buffer);
                output.force(true);
            }
            try {
                mover.move(temp, target, true);
            } catch (AtomicMoveNotSupportedException unsupported) {
                if (Files.exists(target)) {
                    backup = Files.createTempFile(target.getParent(), ".txteditor-", ".backup");
                    Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING);
                }
                try { mover.move(temp, target, false); }
                catch (IOException failed) {
                    if (backup != null) {
                        try { Files.copy(backup, target, StandardCopyOption.REPLACE_EXISTING); }
                        catch (IOException restoreFailed) { failed.addSuppressed(restoreFailed); }
                    }
                    throw failed;
                }
            }
        } catch (IOException failure) {
            throw new IOException(TextKeys.of(TextKeys.SAVE_FAILED) + "恢复文本：" + temp
                    + (backup == null ? "" : "；原文件备份：" + backup) + "\n" + failure.getMessage(), failure);
        }
        // Cleanup cannot turn an already completed save into a reported failure.
        if (backup != null) { try { Files.deleteIfExists(backup); } catch (IOException ignored) { backup.toFile().deleteOnExit(); } }
    }
}
