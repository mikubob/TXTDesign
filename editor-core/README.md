# 共享编辑核心（公共接口已可使用）

纯 Java 8，无 Swing、Android、Path 或 URI 依赖；不需 Maven/Gradle 依赖。包名 `txteditor.core`，源码在 `src/main/java`。安卓工程直接引用此源码目录，不复制算法。

```java
import txteditor.core.EditorModel;
import txteditor.core.TextCodec;
EditorModel model = new EditorModel();
model.load(TextCodec.decodeUtf8(bytes)); // 严格 UTF-8，去文件首 BOM，CRLF/CR -> LF；清历史、选区及修改标记
model.setSelection(0, 0);               // start <= end，UTF-16 偏移
model.replace(0, 0, "中文😀\n");         // 插入/删除/替换统一接口，完成后光标在插入末尾
String text = model.getText();
int hit = model.findNext("中文", model.getSelectionEnd()); // 字面区分大小写，最多回绕一次；-1 无匹配
if (hit >= 0) model.setSelection(hit, hit + "中文".length());
model.undo(); model.redo();             // boolean 表示是否执行；恢复操作前/后选区
byte[] output = TextCodec.encodeUtf8(model.getText()); // LF，无编码器附加 BOM
// 平台文件写入成功后才调用：
model.markSaved();                      // 或 markSaved(实际写入的文本快照)
boolean dirty = model.isModified();
```

接口还包括 `length()`、`canUndo()`、`canRedo()`、`getSelectionStart()`、`getSelectionEnd()`、`getCodePointCount()`、`getLineCount()`、`isBoundary(index)`。模型非线程安全，在 UI 线程使用，工作线程只操作不可变文本快照与文件。

`replace`、`load` 会先验证完整 Unicode 并统一换行，再修改内容；非法范围抛 `IndexOutOfBoundsException`，拆代理项、孤立代理项、空查找词抛 `IllegalArgumentException`；严格 UTF-8 解码失败抛 `CharacterCodingException`。拒绝操作不改文本和历史。界面应把误落代理项中间的光标吸附到边界；删除一个表情时扩展到完整代理项对。

无内容变化不入历史，也不清空重做栈。历史双栈最多保留最近 100 条编辑；`load` 清历史，`markSaved` 不清历史。修改状态比较当前文本与最近成功保存快照，撤销回保存点自动清除标记。空文档 1 行，末尾 LF 新增 1 行。字符数是 Unicode 码点数，并非字形数。

`findNext` 不修改选区；调用方选中返回的匹配。单次替换：当前选区等于查找词则 `replace`，否则先 `findNext` 再替换。平台输入适配不得使用独立原生撤销；组合输入应保留控件组合态，提交后同步到模型。加载与撤销重做的程序回写需抑制输入回调。

实现：`GapBuffer` 自行维护 char 数组及间隙；`Kmp` 自行构造前缀表；`EditorModel` 用两个 `ArrayDeque` 保存可逆编辑。逻辑读取 O(1)，插入/删除 O(d+k)，扩容另加 O(N)，KMP O(N+M)/O(M)，导出、统计、修改状态 O(N)。历史空间 O(H)。UI 文本镜像和统计会增加 O(N)，不能把整次按键称为 O(1)。

构建与测试：在本目录运行 `powershell -ExecutionPolicy Bypass -File .\build.ps1`。JDK 9+ 以 `--release 8` 编译；输出 `build/editor-core.jar`。2026-10-28 在 Java 21 上实际通过 `CoreTest` 的 48,448 次检查（随机编辑 12,000 步、KMP 对照 6,000 组及边界测试）。`src/test/java/txteditor/core/Benchmark.java` 可在构建后运行，10 次预热、30 次重复；原始记录见 `benchmark-results.csv`，完整测试说明见 `../editor-windows/TEST-REPORT.md`。文件路径、选择器、安全写入均由平台模块负责。

供安卓界面对照的固定序列（偏移均为 UTF-16）：`load("中文😀\nabc abc")` → `setSelection(0,2)` → `replace(0,2,"新")` 得 `新😀\nabc abc`/光标1 → `undo()` 得原文/选区0..2 → `redo()` 得新文/光标1 → `findNext("abc",0)` 得4 → `setSelection(4,7)` → `replace(4,7,"中😀")` 得 `新😀\n中😀 abc`/光标7 → `undo()` 得 `新😀\nabc abc`/选区4..7。真实两端集成应按此序列再核对，而非把核心 JVM 测试当作安卓设备测试。
