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
 * 最近打开的文档：URI + 名称 + 打开时间，最新的排最前，最多 8 条。
 * 只用 java.io，纯 JVM 也能跑（AdapterTest 校验排序）；写入先落临时文件再改名。
 */
final class RecentFiles {
    static final String NAME = "recent.bin";
    private static final int VERSION = 1, LIMIT = 8;

    static final class Entry {
        final String uri, name;
        final long time;
        Entry(String uri, String name, long time) { this.uri = uri; this.name = name; this.time = time; }
    }

    private RecentFiles() { }

    /** 读不出来就当没有记录：最近文件只是便利入口，不该拦住打开菜单。 */
    static List<Entry> read(File file) {
        List<Entry> entries = new ArrayList<Entry>();
        if (!file.exists()) return entries;
        try (DataInputStream in = new DataInputStream(new FileInputStream(file))) {
            if (in.readInt() != VERSION) return entries;
            int count = Math.max(0, Math.min(LIMIT, in.readInt()));
            for (int i = 0; i < count; i++) entries.add(new Entry(in.readUTF(), in.readUTF(), in.readLong()));
        } catch (IOException | RuntimeException unreadable) {
            entries.clear();
        }
        return entries;
    }

    /** 打开或保存成功后记录；同一 URI 只留最新一条，超出的旧记录丢弃。 */
    static void add(File file, String uri, String name) {
        if (uri == null || uri.isEmpty()) return;
        List<Entry> entries = read(file);
        for (int i = entries.size() - 1; i >= 0; i--) if (entries.get(i).uri.equals(uri)) entries.remove(i);
        entries.add(0, new Entry(uri, name == null || name.isEmpty() ? uri : name, System.currentTimeMillis()));
        while (entries.size() > LIMIT) entries.remove(entries.size() - 1);
        write(file, entries);
    }

    private static void write(File file, List<Entry> entries) {
        File temp = new File(file.getPath() + ".tmp");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(VERSION); out.writeInt(entries.size());
            for (Entry entry : entries) { out.writeUTF(entry.uri); out.writeUTF(entry.name); out.writeLong(entry.time); }
            out.flush();
            try (FileOutputStream stream = new FileOutputStream(temp)) { stream.write(bytes.toByteArray()); }
            // shortcut: 非原子改名，掉电时最多丢这份最近列表；正文有草稿与回收副本兜底。
            if (!temp.renameTo(file)) { file.delete(); if (!temp.renameTo(file)) temp.delete(); }
        } catch (IOException | RuntimeException error) {
            temp.delete();
        }
    }
}
