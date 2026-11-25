package txteditor.android;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 最近打开的文档：URI + 名称 + 打开时间 + 源码位置，最新的排最前，最多 8 条。
 * 只用 java.io，纯 JVM 也能跑（AdapterTest 校验排序）；写入先落临时文件再改名。
 */
final class RecentFiles {
    static final String NAME = "recent.bin";
    private static final int VERSION = 2, LIMIT = 8;

    static final class Entry {
        final String uri, name;
        final long time;
        final int caret, top;
        Entry(String uri, String name, long time, int caret, int top) {
            this.uri = uri; this.name = name; this.time = time;
            this.caret = caret; this.top = Math.max(0, top);
        }
    }

    private RecentFiles() { }

    /** 读不出来就当没有记录：最近文件只是便利入口，不该拦住打开菜单。 */
    static List<Entry> read(File file) {
        List<Entry> entries = new ArrayList<Entry>();
        if (!file.exists()) return entries;
        try (DataInputStream in = new DataInputStream(new FileInputStream(file))) {
            int version = in.readInt();
            if (version != 1 && version != VERSION) return entries;
            int count = Math.max(0, Math.min(LIMIT, in.readInt()));
            for (int i = 0; i < count; i++) {
                entries.add(new Entry(in.readUTF(), in.readUTF(), in.readLong(),
                        version == 1 ? 0 : in.readInt(), version == 1 ? 0 : in.readInt()));
            }
        } catch (IOException | RuntimeException unreadable) {
            entries.clear();
        }
        return entries;
    }

    /** 打开或保存成功后记录；同一 URI 只留最新一条，超出的旧记录丢弃。 */
    static Entry add(File file, String uri, String name) {
        if (uri == null || uri.isEmpty()) return new Entry("", "", 0, 0, 0);
        List<Entry> entries = read(file);
        Entry previous = null;
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (entries.get(i).uri.equals(uri)) previous = entries.remove(i);
        }
        Entry entry = new Entry(uri, name == null || name.isEmpty() ? uri : name, System.currentTimeMillis(),
                previous == null ? 0 : previous.caret, previous == null ? 0 : previous.top);
        entries.add(0, entry);
        while (entries.size() > LIMIT) entries.remove(entries.size() - 1);
        write(file, entries);
        return entry;
    }

    static void remember(File file, String uri, int caret, int top) {
        List<Entry> entries = read(file);
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (!entry.uri.equals(uri)) continue;
            if (caret != entry.caret || top != entry.top) {
                entries.set(i, new Entry(uri, entry.name, entry.time, caret, top));
                write(file, entries);
            }
            return;
        }
    }

    private static void write(File file, List<Entry> entries) {
        File temp = new File(file.getPath() + ".tmp");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(VERSION); out.writeInt(entries.size());
            for (Entry entry : entries) {
                out.writeUTF(entry.uri); out.writeUTF(entry.name); out.writeLong(entry.time);
                out.writeInt(entry.caret); out.writeInt(entry.top);
            }
            out.flush();
            try (FileOutputStream stream = new FileOutputStream(temp)) { stream.write(bytes.toByteArray()); }
            // shortcut: 非原子改名，掉电时最多丢这份最近列表；正文有草稿与回收副本兜底。
            if (!temp.renameTo(file)) { file.delete(); if (!temp.renameTo(file)) temp.delete(); }
        } catch (IOException | RuntimeException error) {
            temp.delete();
        }
    }
}
