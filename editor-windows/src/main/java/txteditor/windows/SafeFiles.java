package txteditor.windows;

import txteditor.core.TextCodec;
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
    private static final Mover SYSTEM_MOVE = (from, to, atomic) -> {
        if (atomic) Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        else Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
    };

    static String read(Path path) throws IOException { return TextCodec.decodeUtf8(Files.readAllBytes(path)); }
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
            throw new IOException("保存失败；编辑内容仍在窗口中。恢复文本：" + temp
                    + (backup == null ? "" : "；原文件备份：" + backup) + "\n" + failure.getMessage(), failure);
        }
        // Cleanup cannot turn an already completed save into a reported failure.
        if (backup != null) { try { Files.deleteIfExists(backup); } catch (IOException ignored) { backup.toFile().deleteOnExit(); } }
    }
}
