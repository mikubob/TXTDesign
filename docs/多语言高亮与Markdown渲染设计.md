# 多语言语法高亮与 Markdown 渲染设计

> 版本 1.0 · 2026-10-28 · 对应 TXTDesign v1.2 之后的 3.0 升级
> 本文只做设计，不含实现代码改动。安卓端与 Windows 端同步规划，共享核心继续用纯 Java 8。

## 1. 目标与范围

### 1.1 目标

1. 按文件后缀识别文本类型，打开 `.md`、`.java`、`.c`、`.h`、`.cpp`、`.json`、`.xml` 等文件时进入对应模式，不再一律当纯文本。
2. 源码模式按语言做**词法级高亮**（关键字、类型、字符串、注释、数字、注解等），视觉效果对齐 VS Code 的基础配色逻辑。
3. Markdown 文件支持**双模式**：源码高亮编辑，以及类似 Obsidian / 思源笔记的块级预览（标题层级、列表、引用、表格、代码块、分割线、大纲）。
4. 两端行为一致：同一份文本、同一个核心词法器与解析器，只有绘制层不同。
5. 全部算法自行实现，落在 `editor-core`，可被《数据结构课程设计》的课程目标 1（自研数据结构与算法）与课程目标 2（需求分析、模块划分、界面设计、测试记录）直接引用。

### 1.2 非目标（本期明确不做）

- 不做语义分析：没有补全、跳转定义、诊断、LSP。
- 不做 WebView 渲染，不引入 JS 引擎（离线、包体、一致性三条都不允许）。
- 不执行 Markdown 内嵌 HTML，不加载远程图片，不做数学公式、脚注、Mermaid（列入 P2）。
- 不改 `GapBuffer`、`Kmp`、`EditorModel` 的历史语义与 `TextCodec` 的严格 UTF-8 约定。
- 不做分块大文件（> 16 MiB 仍拒绝打开），只做阈值降级。

### 1.3 约束

| 约束 | 现状 | 对设计的影响 |
| --- | --- | --- |
| 语言 | 共享核心 Java 8，安卓 `sourceCompatibility 1.8` | 不能用 `var`、Records、`String.repeat`、`List.of` |
| 依赖 | Windows 无 Maven，`build.ps1` 用 `javac` + 本地 jar，FlatLaf 走 SHA-256 校验 | 新增运行时依赖要手工入库并校验，成本高；本设计**零新增依赖** |
| 存储 | `EditorModel` 内部 `GapBuffer`，对外 `getText()` 是 O(N) 快照 | 词法器只吃字符串快照，不回读模型内部 |
| 偏移 | 全项目统一 UTF-16 偏移，代理项不可拆 | token 边界必须落在 `EditorModel.isBoundary` 认可的边界上 |
| 体积 | APK 混淆后约 1.6 MB | 不引 sora-editor（LGPL，模块合计数 MB） |
| 文件 | 16 MiB 上限，`TextCodec` 严格 UTF-8、统一 LF | 词法只需处理 `\n`；编码失败仍走既有提示 |

## 2. 现状盘点（可复用的扩展点）

| 位置 | 现状 | 本次如何用 |
| --- | --- | --- |
| `EditorModel.replace` | 唯一编辑入口，UTF-16 偏移，事务合并 | 高亮只读快照，不参与编辑 |
| `TextEdits.commit` | 已用双向前后缀比较算出 `start / endOld / endNew` | **直接返回该三元组**，作为增量重扫的输入，不新增 diff 算法 |
| `CoreEditText` | `AppCompatEditText` + `Editable`，`writing` 标志抑制回写 | 加 span 不改字符，不会触发 `TextWatcher`；`render()` 同文本时不重建 Editable |
| `EditorBinding` | `JTextArea` + `PlainDocument` + `DocumentFilter` | 迁移到 `JTextPane`，`DocumentFilter` 接口不变，样式走 `StyledDocument` |
| `EditorTheme` | FlatLaf 明暗两套色板 | 新增 `TokenPalette`，从同一色板派生，不另建主题系统 |
| `DraftFormat` / `EditorStore` | 草稿带版本号（安卓 `int 1`，桌面 `txteditor-session 1`） | 升到 2，新增 `languageId`、`viewMode`，仍能读旧草稿 |
| `SessionSnapshot` | 纯结构体，平台层序列化 | 追加两个字段并保留旧构造器 |

关键判断：**高亮不能进 `EditorModel`**。模型负责文本与历史，样式是视图状态，撤销重做不应该撤销颜色。因此新代码全部落在 `editor-core` 的新子包与两端视图层，模型只多一个"当前语言"的外部字段（由平台持有）。

## 3. GitHub 调研与取舍

### 3.1 调研对象

| 项目 | 语言/平台 | 许可证 | 与本任务相关的做法 | 结论 |
| --- | --- | --- | --- | --- |
| [Rosemoe/sora-editor](https://github.com/Rosemoe/sora-editor) | Java/Kotlin, Android | LGPL-2.1 | 自绘编辑器；`IncrementalAnalyzeManager` 按行缓存分析结果，改动行向后重扫到状态收敛；可选 TextMate / tree-sitter 语法 | 架构思路采纳，代码不引入 |
| [noties/Markwon](https://github.com/noties/Markwon) | Java, Android | Apache-2.0 | commonmark-java 解析成 AST，再渲染为 `Spannable`，无 WebView；代码块高亮作为独立扩展 | 渲染管线（AST→视图）采纳；解析器自研 |
| [bobbylight/RSyntaxTextArea](https://github.com/bobbylight/RSyntaxTextArea) | Java, Swing | BSD-3-Clause | 50+ 语言的 JFlex 词法器；`Token` 列表 + `TokenPainter`；只重绘脏行 | token 分类与脏行重绘采纳；不替换现有 `JTextArea` 体系 |
| [vsch/flexmark-java](https://github.com/vsch/flexmark-java) | Java | BSD-2-Clause | 块级解析 → 行内解析两阶段；节点可延迟解析 | 两阶段模型采纳 |
| [commonmark/commonmark-java](https://github.com/commonmark/commonmark-java) | Java | BSD-2-Clause | 容器栈驱动的块解析；行内分隔符栈 | 算法参考（CommonMark 规范附录的解析策略） |
| [Lezer](https://lezer.codemirror.net/) / CodeMirror 6 | TypeScript | MIT | 错误容忍的 LR 增量解析，节点复用，64 位紧凑节点 | 只借鉴"增量复用 + 出错也能出树"，不引入解析器生成器 |
| [88250/lute](https://github.com/88250/lute)（思源笔记内核） | Go | MulanPSL-2.0 | 零正则、先建 AST 再渲染；中文中西文混排与自动空格 | 思路参考；中文排版规则列入 P2 |
| [Vditor](https://github.com/Vanessa219/vditor) / [MarkText](https://github.com/marktext/marktext) | TS | MIT | 编辑器与预览双栏、滚动同步、块级刷新 | 交互形态参考 |
| VS Code / [tm4e](https://github.com/eclipse/tm4e) | TS/Java | MIT / EPL-2.0 | TextMate 语法：正则 + begin/end 状态栈 | **明确不采用**：正则回溯在长行上不可控，且与"自研算法"要求冲突 |

### 3.2 调研结论

1. **整体不引入 sora-editor**。它是完整编辑器控件，自带文本存储、撤销、输入法处理，接入等于替换本项目 `GapBuffer + EditorModel + CoreEditText` 三层，与课程要求（自行实现数据结构）和现有测试体系正面冲突；LGPL-2.1 还会给后续分发带来合规负担。
2. **不走 WebView + highlight.js**。包体增加数 MB，离线与冷启动变差，两端字体与排版不可控，且核心算法变成 JS 库调用，无法写进课程报告。
3. **不引 Markwon / flexmark / RSyntaxTextArea**。三者都是"能直接用"的成熟库，但都自带解析器与渲染器，引入后本次要写的算法就只剩胶水代码。取舍是：**借用其结构，不借用其代码**。
4. 真正值得抄的三条经验：
   - 行级状态缓存 + 向后收敛判定（sora-editor、CodeMirror）——增量高亮的核心，比整篇重扫快一到两个数量级。
   - 块级解析与行内解析分离（flexmark、lute）——Markdown 的复杂度集中在"容器可以层层嵌套"，一次扫描分不清块与行内。
   - 脏行重绘而不是全文重排（RSyntaxTextArea）——Swing 与 Android 都适用。

许可证处理：本设计不复制上述项目源码，只吸收公开的算法思想；文档与代码注释中标注参考对象。新增 `THIRD-PARTY-NOTICES.md` 条目为"无新增依赖"。

## 4. 总体架构

```text
editor-core/src/main/java/txteditor/core/text/      ← 新增，纯 Java 8，无平台 API
  LanguageId.java        语言枚举（含后缀表）
  LanguageRegistry.java  后缀 / MIME / 首行嗅探 → LanguageId
  TokenType.java         token 分类（关键字、类型、字符串、注释…）
  TokenSink.java         int[] 平铺的 token 结果（零对象分配）
  LineIndex.java         行首偏移索引（带间隙的 int 数组）
  Lexer.java             词法状态机接口
  CLexer / JavaLexer / JsonLexer / XmlLexer / MarkdownLexer
  KeywordTrie.java       关键字 Trie（数组实现，左孩子右兄弟）
  HighlightState.java    行状态缓存 + 增量重扫 + 脏行区间
  md/MdParser.java       块级 + 行内两阶段解析
  md/MdNode.java         AST（结构体数组，左孩子右兄弟）
  md/MdBlock.java        块视图模型（供列表虚拟化）
  md/MdInline.java       行内样式片段
  md/MdOutline.java      标题树（大纲 / 滚动锚点）

editor-android/  CoreEditText + HighlightController + MarkdownAdapter/RecyclerView
editor-windows/  EditorBinding(JTextPane) + SyntaxPainter + MarkdownPreview
```

数据流（一次按键）：

```text
按键 → Editable/Document 变更 → TextEdits.commit 得到 (start, oldEnd, newEnd)
     → HighlightState.relex(text, 变更区间)        后台线程，O(受影响行)
     → TokenSink（不可变快照 + 版本号）
     → 主线程：版本号未过期才应用
        ├─ 源码模式：只给可视窗口 ±50 行贴 span
        └─ 预览模式：按块 hash 比较，只刷新变化的块
```

线程模型沿用现有约定：核心非线程安全，只在 UI 线程触碰模型；后台只处理 `String` 快照与 token 数组，回主线程前用版本号丢弃过期结果（与 `EditorViewModel.documentVersion` 同一套路）。

## 5. 文件类型识别

### 5.1 识别顺序

1. **后缀**（`LanguageRegistry` 内静态表，`HashMap<String, LanguageId>`，小写比较）：

| 后缀 | 语言 | 首版高亮 |
| --- | --- | --- |
| `.txt` `.log` `.ini` `.properties` | PLAIN | 无 |
| `.md` `.markdown` | MARKDOWN | 有 + 预览 |
| `.java` | JAVA | 有 |
| `.c` `.h` | C | 有 |
| `.cpp` `.cc` `.hpp` | CPP（复用 C 词法器 + `//`、类关键字） | 有 |
| `.json` | JSON | 有 |
| `.xml` `.html` `.htm` | XML/HTML（标签、属性、字符串、注释） | 有 |
| `.py` `.js` `.ts` `.sql` `.sh` `.bat` | 仅登记后缀，P1 补词法器 | 暂按 PLAIN |

2. **MIME 兜底**：安卓 `ContentResolver.getType(uri)` 返回 `text/markdown`、`text/x-java` 等时映射；Windows 用 `Files.probeContentType`。都不可靠，只作补充。
3. **首行嗅探**（前 1 KiB，O(1) 常量）：`#!/usr/bin/env python` → PY；`<?xml` → XML；`{` 开头且含 `"` → JSON 候选；`# ` + 空行结构 → 疑似 Markdown，仅提示不自动切换。

**不做内容猜测式自动切换语言**：猜错比不猜更让人困惑。识别结果写入状态栏，可手动覆盖（菜单选择语言），覆盖值随会话保存。

### 5.2 平台文件通路改动

| 端 | 现状 | 改动 |
| --- | --- | --- |
| 安卓 | `launchPicker` 固定 `setType("text/plain")` | 改为 `*/*`；`ACTION_OPEN_DOCUMENT` 很多提供者给 `.java` 的 MIME 是 `application/octet-stream`，用 `text/plain` 会直接看不到文件。选中后按后缀判定 |
| 安卓 | 保存默认名 `未命名.txt` | 按当前语言给默认名（`未命名.md` / `未命名.java`），仍允许用户改 |
| Windows | `JFileChooser` 单一文本过滤器 | 过滤器增加"源代码 / Markdown / 所有文件"，`FileNameExtensionFilter` 多后缀；拖放文件按后缀识别 |
| 两端 | 无二进制判断 | 读前 8 KiB：出现 `0x00` 或非法控制字符占比 > 30% → 拒绝打开并提示"这不是文本文件" |

编码策略保持 **严格 UTF-8**。已知风险：教学场景里 Windows 上的 `.java` 常是 GBK，会报"不是 UTF-8"。处理方案见 15 节风险表（P2 提供"按 GB18030 只读打开"）。

## 6. 词法高亮

### 6.1 为什么手写 DFA 而不是正则

TextMate 那套正则文法在编辑器里会出现灾难性回溯：一行 1 MB 的压缩 JSON 就能把 UI 卡死。手写状态机是**最长匹配 + 单遍扫描**，每个字符只读一次，复杂度严格 O(N)，且状态可缓存、可增量。

### 6.2 状态机设计

每个语言一个 `Lexer` 实现，内部是 `switch (state)` 的 DFA。以 Java 为例（C 只需替换关键字表与注释/预处理规则）：

```text
状态：CODE, LINE_COMMENT, BLOCK_COMMENT, STRING, CHAR, TEXT_BLOCK
CODE 下：
  '/' 后看下一字符：'/' → LINE_COMMENT；'*' → BLOCK_COMMENT（带嵌套深度 0/1）
  '"' → STRING；'\'' → CHAR；'"'*3 → TEXT_BLOCK
  数字 → 扫描 [0-9a-fA-FxXbB._lLfFdD] 最长匹配
  标识符首字符 → 扫到非标识符字符，整段交给 KeywordTrie 判定
  其余 → 逐字符产出 SYMBOL，或直接跳过（不产 token 的空白）
```

要点：

- **关键字在标识符扫描过程中一次判定**，不额外做字符串比较：扫完 `[start,end)` 后把这段喂给 `KeywordTrie.match`，返回 `KEYWORD / TYPE / LITERAL / null`。Trie 用 `int[] child/next/sibling/tokenType` 四个平行数组表示（左孩子右兄弟），构建在类初始化时一次完成，之后零分配、无哈希、无字符串切片。
- **token 结果平铺存放**：`TokenSink` 内部是 `int[] spans`（每 token 3 个槽：start、end、type）与 `byte[]` 类型表；1 MB 源码约 20 万 token ≈ 2.4 MB，比每个 token 一个对象省掉对象头与指针，GC 压力显著下降。
- **不产出空白与换行 token**，减少 40% 以上的数量；绘制时默认前景色就是空白色。
- 行内状态只有"跨行才有意义"的少数几个值（注释深度、字符串、fence 语言），编码成一个 `byte` 存进 `lineState[]`。

### 6.3 行索引 `LineIndex`

偏移↔行号是高频操作（滚动、脏行定位、预览锚点），不能每次 `indexOf('\n')`。

- 结构：`int[] starts`（`starts[i]` 为第 i 行首偏移）。
- 插入/删除行：只平移被删/被插位置之后的尾部（`arraycopy`），不重扫全文；16 MiB、约 20 万行的量级下平移是几十微秒。
- 查询：`lineOf(offset)` 用二分 `O(log L)`。
- 与文本同步：每次拿到变更区间后，只在变更区间内重扫换行、对其余行号整体加同一个 `delta`。
- 实现记录：M1 用的是整表平移而不是间隙数组（附录 D），因为行数上限内 `arraycopy` 成本可忽略，间隙化的收益要等分块文件才有。

### 6.4 增量重扫

核心不变量：**若某行的起始状态与缓存一致、且该行文本未变，则该行及其之后所有 token 都不会变**。

```java
// 缓存每行"行首状态"（byte/行），返回脏行区间 [firstDirty, lastDirty]
int relex(String text, int changeStart, int oldEnd, int newEnd) {
    int first = lines.lineOf(changeStart);
    int lastChanged = lines.lineOf(newEnd);
    byte state = first == 0 ? CODE : lineStartState[first];   // 上一行结束态即本行行首态
    int lastDirty = first;
    for (int line = first; line < lines.count(); line++) {
        if (line > lastChanged && lineStartState[line] == state) break;  // 行未变且状态相同，后面全不用动
        LexResult r = lexLine(text, lines.start(line), lines.end(line), state);
        lineStartState[line] = state;
        tokens.replaceLine(line, r.tokens);
        state = r.endState;
        lastDirty = line;
    }
    return pack(first, lastDirty);
}
```

注意 `lineStartState` 与行号一一对应：编辑改变了行数时，缓存数组要跟着 `LineIndex` 一起插入/删除，否则行号会错位。这是本设计里最容易写错的一处，测试必须覆盖"插入一行后继续编辑"的场景。

- 典型成本：O(编辑行 + 若干收敛行)；最坏情况（在文件头插入一个 `/*`）仍然 O(N)，这是词法本身的物理下界，不做假优化。
- 长行保护：单行超过 100 K 字符时，只扫可视窗口对应的字符区间，超出部分按"未高亮"处理，防止一行压缩文件拖死界面。
- 未闭合块注释 / 字符串 / 围栏一直延续到文件尾，是合法输入（编辑中间态经常如此），不得报错。

### 6.5 括号匹配与折叠（P1）

- 括号匹配：单遍扫描 + 栈（存 `int[]` 与 `byte[]` 两个平行数组，避免 `Stack<Integer>` 装箱），跳过字符串与注释区间；深度上限 256，超出后放弃匹配。
- 折叠区间：同一遍扫描产出 `(openOffset, closeOffset)` 对，按 open 升序存放，二分查找光标所在区间；不改 `EditorModel`。

## 7. Markdown 解析与渲染

### 7.1 两阶段解析

Markdown 的难点不是行内标记，而是**容器嵌套**（列表里的引用里的代码块）。一次线性扫描无法同时决定"这行是块还是行内"，所以分两阶段：

**阶段一：块级（线性扫描 + 容器栈）**

```text
对每一行：
  1. 计算行首缩进与可能的块起始标记（#、>、-、*、1.、```、|、---）
  2. 与栈顶容器比较：
     - 缩进不足 → 弹栈（关闭列表/引用），重试当前行
     - 匹配 → 继续
     - 新容器（引用/列表/围栏）→ 压栈，产出容器节点
  3. 围栏内（``` / ~~~）：整段吞掉，交给代码块高亮，不解析任何标记
  4. 空行：关闭段落，但不一定关容器（loose list 规则）
```

容器栈用 `byte[] kinds + int[] indents` 两个平行数组实现，深度上限 32（防御恶意输入 `>>>>…`）。产出的块序列天然按偏移有序，后续二分即可定位。

**阶段二：行内（分隔符栈）**

对段落/标题/表格单元格的文本做单遍扫描，遇到 `*`、`_`、`~~`、`` ` ``、`[`、`!`、`<` 时压入候选栈，按 CommonMark 规范附录的"分隔符栈"规则配对；`[` 另配一个链接栈处理 `[]()` 与 `[][]`。引用式链接定义（`[id]: url`）在块阶段收集进 `HashMap<String,String>`，行内阶段查表，O(1)。

支持范围（首版，明确写进 README）：

| 语法 | 首版 | 说明 |
| --- | --- | --- |
| 标题 `#`~`######`、Setext | 是 | 进大纲 |
| 段落、硬换行、分割线 | 是 | |
| 有序/无序/嵌套列表、任务列表 `- [ ]` | 是 | 缩进层级最多 6 |
| 引用（可嵌套） | 是 | |
| 围栏代码块 + 语言标记 | 是 | 内部复用第 6 节词法器 |
| 表格（GFM） | 是 | 首版等宽列 + 横向滚动 |
| 行内代码、粗体、斜体、删除线、链接、图片语法 | 是 | 图片只显示占位与 alt |
| 原始 HTML | 否 | 按纯文本显示，不执行、不解析 |
| 脚注、公式、Mermaid、目录变量 | 否（P2） | |

### 7.2 AST 表示

不用"每个节点一个对象"：1 MB 文档可能几十万节点，Android 上对象头就能吃掉十几 MB。改用**结构体数组**（左孩子右兄弟）：

```java
final class MdNode {          // 不实例化，仅作为字段布局说明
    byte  type;               // 标题/段落/列表/引用/代码块/表格/分割线
    byte  depth;              // 标题级别或列表层级
    int   start, end;         // 源文本 UTF-16 区间
    int   firstChild, next;   // 孩子/兄弟索引，-1 结束
}
// MdTree 内部：byte[] type, depth; int[] start, end, firstChild, next;
```

- 节点数上限 200 000，超出截断并降级为纯文本预览（防御 `*` 攻击）。
- 块列表单独维护 `int[] blockStart`（升序），供滚动同步二分、预览虚拟化按下标取块。
- 大纲是标题节点的**另一棵树**（按层级用栈构建），与正文树共享偏移，不复制文本。

### 7.3 增量解析

Markdown 的增量比词法难：一个 `>` 可能改变后面几十行的归属。策略是"块级重解 + 块复用"：

1. 由变更区间得到受影响块下标 `i`（二分 `blockStart`）。
2. **回退到安全边界**：向前找到最近一个"容器深度为 0 且前一行是空行"的位置，从这里开始重解；没有就从头。
3. 逐块计算签名 `hash(块文本 + 前一块类型 + 容器深度)`（FNV-1a，O(块长)）。
4. 新块签名与旧块相同 → 直接复用旧节点（不重新建 AST、不重新渲染）；不同 → 标记该块为脏，追加到刷新队列。
5. 直到连续 3 个块签名都命中旧序列，停止重解（收敛判定，容忍局部漂移）。

成本：编辑一段普通段落 ≈ O(段落长度 + 少量块)；在文档开头插入 `>` 则退化为 O(N)，与词法一致。

### 7.4 渲染：两端各自的形态

| | 安卓 | Windows |
| --- | --- | --- |
| 载体 | `RecyclerView` + 每块一个 ViewHolder | `JTextPane` + `StyledDocument`（连续段落），代码块以 `ComponentView` 嵌入 |
| 为什么 | 长文只测量可视块，滚动成本与文档长度无关；块级点击、代码块横向滚动都自然 | 复用 Swing 现成的排版、选择、复制、无障碍；`JTextArea` 换 `JTextPane` 是本次唯一大改 |
| 代码块 | `TextView` + 第 6 节 token span，横向 `HorizontalScrollView` | 只读 `JTextPane`，同一套 token 上色 |
| 表格 | 自绘等宽列（`TableLayout` + 横向滚动） | 首版等宽文本 + 表头背景；P1 换成自绘 `View` |
| 链接 | 点击用 `Intent.ACTION_VIEW`，只放行 `http/https/mailto` | `Desktop.browse`，同样的 scheme 白名单 |
| 图片 | 只显示 `[图片: alt]` 占位（离线，不请求网络） | 同 |

一致性保证：两端都消费同一份 `MdTree` 与同一份 token 流，差异只在绘制。测试用"结构快照"对比（块类型序列 + 每块纯文本 + 缩进层级），不比像素。

### 7.5 滚动同步与大纲（P1）

- 锚点 = 第一个可见块的 `start` 偏移；切换模式时反查另一侧（源码侧 `LineIndex.lineOf`，预览侧 `Arrays.binarySearch(blockStart, offset)`），滚动到该块顶部。
- 大纲面板：标题树中序遍历，点击跳转 = 设置选区到标题 `start` 并滚动。
- 不做逐像素同步（两端排版引擎不同，逐像素必然漂移）。

## 8. 安卓端设计

### 8.1 保持 `CoreEditText` 不变的部分

`CoreEditText` 的编辑、输入法、选区、事务逻辑一行不动。它已经用 `writing` 标志隔离程序回写，`render()` 在文本相同时不重建 `Editable`，所以**贴 span 不会污染编辑历史**（`setSpan` 触发的是 `SpanWatcher`，不是 `TextWatcher`）。

### 8.2 新增 `HighlightController`

```java
final class HighlightController {
    void attach(CoreEditText view, EditorModel model);   // 绑定
    void onTextChanged(int start, int oldEnd, int newEnd); // 由 CoreEditText 调用
    void setLanguage(LanguageId id);
    void onScroll();      // 只补可视窗口
    void onThemeChanged(); // 重新取色，不重算 token
}
```

实现要点：

1. **只给可视窗口贴 span**：`Layout.getLineForVertical(scrollY)` 到 `+height`，上下各留 50 行。滚动时移除窗口外的 span、补窗口内的。十万行文档也只持有几百个 span 对象。
2. **span 对象复用**：`setSpan(existingSpan, newStart, newEnd, flags)` 可以把同一个 span 实例挪到新位置，不必 `remove + new`。维护一个 `ArrayList<ForegroundColorSpan>` 池，按 token 数量增长，稳态零分配。
3. **同色合并**：相邻且类型相同的 token 合并成一个 span，通常能把数量压掉一半。
4. **抑制回调**：贴 span 期间置 `applying` 标志；`afterTextChanged` 里若文本与模型一致直接返回（现有逻辑已具备，只需在 controller 里再守一道）。
5. **防抖**：文本变化后 80 ms 触发一次后台词法；连续输入只跑最后一次。输入法组合态（`BaseInputConnection.getComposingSpanStart >= 0`）期间不贴 span，避免与下划线冲突。
6. **异步与版本号**：后台用 `ExecutorService`（已有单线程 io 池可复用，但高亮应独立一个池，避免被文件读写阻塞）；回主线程校验 `documentVersion` 与 `textVersion`，过期丢弃。

### 8.3 预览模式

- 顶部现有 toolbar 增加"源码 / 预览"分段按钮（`MaterialButtonToggleGroup`），预览态隐藏查找面板与撤/重做，保留菜单里的保存。
- `MarkdownAdapter` 按 `MdBlock` 绑定：标题（字号/字重映射）、段落（`Spannable` 行内样式）、列表（前缀符号 + 缩进）、引用（左侧色条）、代码块（等宽 + 横向滚动 + token span）、表格、分割线。
- 切换语言或文档时 `notifyDataSetChanged`；增量编辑时只 `notifyItemRangeChanged(dirtyFrom, dirtyCount)`。
- 预览不做编辑（首版只读）；点代码块的"复制"按钮复制该块源码。

### 8.4 状态与持久化

- `SessionSnapshot` 增加 `languageId`（`String` 存枚举名，未知值回落 `txt`）与 `viewMode`（`0` 源码 / `1` 预览）。
- 旧构造器保留（默认 `txt` + 源码），两端现有调用点不受影响。
- 安卓 `DraftFormat` 版本升到 2：先写旧 10 个字段，再追加两个字段；读到版本 1 时按默认值补齐。桌面 `EditorStore` 头改成 `txteditor-session 2`，读到 `1` 时同样补齐。

## 9. Windows 端设计

### 9.1 `JTextArea` → `JTextPane`（本次最大改动）

`JTextArea` 用 `PlainDocument`，无法给区间上色。换 `JTextPane` + `DefaultStyledDocument` 是唯一不改绘制层的正路，代价与对策：

| 风险 | 对策 |
| --- | --- |
| `JTextPane` 默认不换行，而现在 `area.setLineWrap(true)` | 内部类 `WrapPane extends JTextPane`，覆写 `getScrollableTracksViewportWidth()` 返回 `true`，段落按视口宽度折行 |
| `setText` 会清空样式 | `EditorBinding.refresh()` 改为"先写文本、后统一上色"，并保留 `writing` 标志避免 `DocumentFilter` 递归 |
| 大量 `setCharacterAttributes` 触发多次 `changedUpdate` 重绘 | 缩小重绘窗口（每行先归零再贴 token），窗口内一次提交；**不要**包 `doc.render(...)`：它拿读锁，而 `setCharacterAttributes` 要写锁，读锁不可升级，同线程会永久等待（实现时踩到，见附录 D）。样式对象按 `TokenType` 缓存（`SimpleAttributeSet` 每类一个，共十来个） |
| IME 组合态属性被覆盖 | 组合区间（`StyleConstants.ComposedTextAttribute`）跳过上色，现有 `EditorBinding` 已有 `hasComposition` 判定可复用 |
| 缩放 | 现在只改 `area.setFont`；`JTextPane` 需要遍历 `Style` 更新字号，或改用 `StyledDocument` 默认样式 size，`setZoom` 相应地一并改 |
| 无障碍 | 只设 `ForegroundColorSpan` 等视觉属性，不改文本内容；`AccessibleContext` 名称保持"纯文本编辑区" |

`EditorBinding` 的 `DocumentFilter` 逻辑保持原样（`JTextPane` 的文档同样是 `AbstractDocument`），只是 `replace()` 多一步：把 `(start, oldEnd, newEnd)` 交给 `SyntaxPainter`。

### 9.2 `SyntaxPainter`

```java
final class SyntaxPainter {
    void setLanguage(LanguageId id);
    void repaintLines(int firstLine, int lastLine);   // 只处理脏行
    void applyTheme(TokenPalette palette);
}
```

- 与安卓共用 `HighlightState`，只是输出目标从 span 变成 `AttributeSet`。
- 脏行范围用 `doc.getDefaultRootElement().getElement(line).getStartOffset()` 换算，`setCharacterAttributes(start, len, style, false)`。
- 首屏只上色可视行 + 200 行预取；滚动时补色（`JScrollPane` 的 `AdjustmentListener`）。
- 视口之外的样式**保留不清除**（否则来回滚动会反复重算），代价是内存，用"最近 5000 行"窗口淘汰。

### 9.3 预览

- 工具栏加"预览"切换（`Ctrl+E`），`CardLayout` 在编辑区与预览区之间切换；P1 提供 `JSplitPane` 左右分栏。
- 预览主体 `JTextPane`（只读、透明背景）+ `StyledDocument`：段落、标题、列表、引用都在同一文档里连续排版，滚动与选择天然可用。
- 代码块用 `ComponentView` 嵌入只读 `JTextPane`（自带高亮与横向滚动）；一个文档超过 200 个代码块时降级为等宽文本块（防止组件爆炸）。
- 表格首版渲染成等宽文本 + 表头底色，P1 再换自绘 `View`。
- 主题切换：预览侧的样式表由 `TokenPalette` 生成，与 `EditorTheme.install` 同一份色值，不新建主题系统。

## 10. 数据结构与算法（课程目标对应）

### 10.1 结构清单

| 结构 / 算法 | 用在哪里 | 为什么合适 | 复杂度 |
| --- | --- | --- | --- |
| 间隙缓冲区（已有 `GapBuffer`） | 文本存储 | 局部编辑常数小，无需整体搬移 | 插入 O(d+k) |
| 行间隙缓冲 `LineIndex` | 偏移↔行号 | 行首数组同样有"中间插入"特征，复用间隙思想，避免每次 `arraycopy` | 插入摊还 O(1)，查询 O(log L) |
| DFA 状态机（有向图） | 词法扫描 | 单遍、最长匹配、状态可缓存 | O(N) 时间，O(1) 额外空间 |
| Trie（左孩子右兄弟，数组实现） | 关键字/类型判定 | 与标识符扫描同遍完成，O(关键字长度)，无哈希、无字符串切片 | 查询 O(m) |
| 平铺数组（SoA） | token 流、AST | 去掉对象头与指针，缓存友好，Android 内存敏感 | 每 token 12 B |
| 栈（`byte[] + int[]`） | Markdown 容器栈、行内分隔符栈、括号匹配 | 嵌套结构的天然解法，深度可控 | O(1) 摊还 |
| 队列 / 合并式任务队列 | 高亮任务、脏块刷新 | 连续输入只保留最后一次，天然防抖 | O(1) |
| 左孩子右兄弟树 | Markdown AST、大纲 | 任意度数的树用两个 int 表示，遍历不用递归（显式栈，防爆栈） | 遍历 O(V) |
| 哈希表（`HashMap`） | 语言注册表、引用式链接定义、块签名缓存 | 平均 O(1) 查找 | O(1) |
| 二分查找 | 行号↔偏移、块定位、滚动锚点、光标处 token | 数据天然有序，不需额外结构 | O(log N) |
| 增量重扫 + 收敛判定 | 词法、块解析 | 把每次按键从 O(N) 降到 O(受影响区间) | 典型 O(Δ)，最坏 O(N) |
| 脏区间合并 | 重绘调度 | 比线段树常数小，区间数量本来很少 | O(区间数) |
| LRU（`LinkedHashMap` accessOrder） | 块 AST 缓存 | 回滚/来回切换时命中 | O(1) |
| 前缀和 / 行内偏移平移 | 行号整体位移 | 避免逐行 `+delta` | O(1) |

### 10.2 为什么不用某些"更高级"的结构

- **不用线段树维护脏行**：一次编辑最多产生一两段脏区间，线性合并比 `O(log L)` 的树操作更快，代码少 100 行。
- **不用后缀自动机/后缀数组**：本项目查找是"下一个匹配"，KMP 已够；后缀结构适合全文多模式检索，属于过度设计。
- **不用 rope**：`GapBuffer` 已满足 16 MiB 以内文档，rope 的平衡维护成本换不来收益。
- **不用 tree-sitter**：它是 C 运行时 + 语法文件，JNI 与体积都不合适，且与"自研算法"要求冲突。

### 10.3 与课程目标 / 毕业要求的对应

| 课程目标 | 本文对应内容 |
| --- | --- |
| 课程目标 1（指标点 1.3、4.2、4.3） | 第 6、7、10 节：自行实现 DFA 词法、Trie、行索引、容器栈、增量重扫与块复用；给出复杂度分析与取舍理由 |
| 课程目标 2（指标点 3.2、4.2、4.3、11.3） | 第 4、5、8、9、13 节：模块边界、双端界面设计、输入适配、文件保护、测试与验证记录 |

## 11. 性能预算与降级

| 指标 | 目标（桌面 / 中端安卓） | 手段 |
| --- | --- | --- |
| 1 MB Java 全量词法 | < 60 ms / < 150 ms | 单遍 DFA、Trie、平铺数组 |
| 按键到重绘 | < 16 ms / < 32 ms | 增量重扫、80 ms 防抖、只重绘脏行 |
| 1 MB Markdown 块解析 | < 120 ms / < 300 ms | 线性扫描 + 容器栈；不做正则 |
| 预览首屏 | < 100 ms / < 200 ms | 只渲染可视块（安卓）/ 只上色可视行（桌面） |
| 内存增量（1 MB 源码） | < 8 MB | token 12 B/个、AST 20 B/节点、行状态 1 B/行 |

降级阈值（写进代码常量，并在状态栏提示）：

| 条件 | 行为 |
| --- | --- |
| 文本 > 2 MiB | 关闭自动高亮与预览，菜单里可手动开启（单次） |
| 单行 > 100 K 字符 | 该行只高亮可视区间 |
| Markdown 节点 > 200 K | 截断 AST，预览降级为纯文本 |
| 围栏代码块 > 300 个（桌面预览） | 代码块降级为等宽文本 |
| 高亮连续 3 次超时（> 500 ms） | 自动降级为纯文本，并在状态栏说明 |

## 12. 兼容、持久化与安全

- **旧草稿**：两端都能读版本 1，缺省 `txt` + 源码模式；新版本写 2。升级后旧草稿不会丢。
- **撤销语义**：样式变化不进 `EditorModel` 历史；撤销只撤文本，颜色由重扫自然跟随。
- **保存**：高亮不改变文本，`markSaved` 与脏标记逻辑零改动；预览态保存仍走原 `save()`。
- **链接安全**：只放行 `http`、`https`、`mailto`；`file:`、`javascript:`、`data:` 一律当普通文本。Android 用 `Intent` 前先 `resolveActivity` 判空。
- **HTML**：Markdown 里的 `<script>`、`<img onerror>` 不解析、不执行，按字面显示。
- **超大输入**：解析器对嵌套深度（容器 32、行内 32）、节点数、单块长度都有硬上限，超限降级而不是抛异常。
- **无障碍**：高亮只用颜色 + 字重，不用颜色单独承载语义；明暗两套色板的正文对比度不低于 4.5:1；TalkBack 朗读文本不受 span 影响。

## 13. 测试与验证

### 13.1 核心（JVM，`editor-core` 测试）

1. **增量 == 全量**（最重要）：随机 5000 次编辑（插入/删除/替换、跨行、代理项边界），每一步把增量结果与"从头全量词法"逐 token 比较，必须完全一致。这是增量算法唯一的硬证明手段。
2. **不变量**：token 有序、不重叠、不越界、边界对齐代理项；`lineState` 数组长度与行数一致。
3. **词法边界**：未闭合注释/字符串/围栏到 EOF；空文件；只有 `\n`；1 MB 单行；CRLF（应已被 `TextCodec` 归一化）；中文注释与全角符号。
4. **Markdown 子集**：手写 120+ 用例（标题/嵌套列表/引用套代码块/表格/链接/强调嵌套/未闭合标记），期望输出为"块类型序列 + 纯文本"，与实现分离，便于人工核对。参考 CommonMark 规范示例自行改写，不直接拷测试文件。
5. **AST 复用**：同一文档二次解析，节点对象应全部命中复用；改动一段后，只有预期块变脏。
6. **性能基准**：沿用 `Benchmark` 的"10 次预热 + 30 次重复取中位数"方法，新增 `highlight-results.csv`。

### 13.2 安卓

- `AdapterTest` 增加：脏区间 → `notifyItemRangeChanged` 范围计算；语言识别表；`TextEdits.commit` 返回三元组的边界用例（这批可跑纯 JVM，不需要设备）。
- 设备测试：打开 `.java` / `.md` 各一份，滚动到万行处检查 span 窗口未泄漏；输入法组合态不贴色；深浅主题切换后颜色即时更新；预览 → 源码 → 预览往返不丢光标。

### 13.3 Windows

- `GuiTest`：切换预览、缩放、主题、查找替换在新 `JTextPane` 下不回归；换行、IME 组合、复制粘贴、无障碍名称。
- `DesktopTest`：文件过滤器、后缀识别、二进制拒绝、草稿版本 1→2 兼容。

### 13.4 跨端一致性

核心导出一份"token dump"（每行 `type:start:end`）作为 golden 文件，两端只验证"同一份文本 → 同一份 dump → 各自映射颜色"。渲染差异用结构快照比对，不比像素。

## 14. 实施阶段（建议 15 学时分配）

| 阶段 | 内容 | 产出 |
| --- | --- | --- |
| M1（3 学时） | `LanguageId`、`LanguageRegistry`、`LineIndex`、`TokenSink`、Java/C 词法器 + 全量高亮；两端先做"只上色不增量" | 核心测试通过；`.java`/`.c` 可高亮 |
| M2（4 学时） | `HighlightState` 增量重扫、脏行重绘、两端窗口化贴色、性能基准 | 增量==全量测试；按键延迟达标 |
| M3（4 学时） | `MdParser`（块 + 行内）、`MdTree`、代码块复用词法器；两端预览（安卓 RecyclerView、桌面 JTextPane + ComponentView） | `.md` 双模式可用 |
| M4（2 学时） | 类型识别接入文件通路、状态持久化升级、主题色板、大纲与滚动同步、降级策略 | 端到端可用 |
| M5（2 学时） | 测试补全、设备/GUI 回归、README 与 TEST-REPORT 更新、答辩材料 | 报告与截图 |

每个阶段结束都要能独立演示，避免"最后一周才跑通"。

## 15. 风险与对策

| 风险 | 影响 | 对策 |
| --- | --- | --- |
| `JTextArea` → `JTextPane` 回归（换行、缩放、IME、无障碍） | 桌面端主功能受损 | M1 先做 1 天 spike：只迁移控件 + 上色，跑通 `GuiTest` 再继续；保留 `EditorBinding` 接口不变，必要时可回退 |
| 安卓 span 数量失控 | 滚动卡顿、内存上涨 | 强制窗口化（可视 ±50 行）+ span 复用 + 同色合并；设备测试用 1 MB 文件验证 |
| Markdown 规范覆盖不全 | 用户觉得"渲染不对" | 文档明确列出支持表；不支持的语法按字面显示，不静默丢弃 |
| 中文 `.java` 是 GBK | 打开即报"不是 UTF-8" | 首版保持严格 UTF-8 并提示；P2 提供"按 GB18030 只读打开，另存为 UTF-8" |
| 预览与源码滚动漂移 | 体验割裂 | 只做块级锚点同步，明确不做逐像素同步 |
| 大文件 + 增量退化 | 输入卡顿 | 阈值降级 + 超时熔断，宁可不高亮也不卡 |
| 课程评审认为"用了现成库" | 影响成绩 | 零新增依赖；文档与答辩明确"参考思想、自行实现"，并给出与库版本的对比基准 |

## 16. 交付物清单

1. 本文档（设计依据）。
2. `editor-core` 新增 `txteditor.core.text` 与 `txteditor.core.text.md` 两个子包（纯 Java 8）。
3. 两端视图层改动（安卓 4 个类、桌面 4 个类，均在现有文件基础上增量修改）。
4. 测试：核心增量对照、Markdown 用例、两端回归、性能 CSV。
5. 文档更新：`README.md` 功能与结构、`editor-core/README.md` 接口、`editor-windows/DESIGN.md` 增补一节、`THIRD-PARTY-NOTICES.md` 注明无新增依赖。

## 附录 A：Token 类型

`PLAIN, KEYWORD, TYPE, LITERAL, NUMBER, STRING, CHAR, COMMENT, ANNOTATION, PREPROCESSOR, OPERATOR, IDENTIFIER, TAG, ATTRIBUTE, HEADING, LINK, CODE, EMPHASIS, LIST_MARKER, QUOTE_MARKER, TABLE_BORDER`

首版配色规则（明/暗各一套，值放在 `TokenPalette`）：

| 类型 | 浅色 | 深色 |
| --- | --- | --- |
| KEYWORD | `#AF00DB` | `#C586C0` |
| TYPE | `#267F99` | `#4EC9B0` |
| STRING | `#A31515` | `#CE9178` |
| NUMBER | `#098658` | `#B5CEA8` |
| COMMENT | `#008000`（斜体） | `#6A9955`（斜体） |
| ANNOTATION | `#808000` | `#DCDCAA` |
| PREPROCESSOR | `#808080` | `#9CDCFE` |
| OPERATOR | `#000000` | `#D4D4D4` |

## 附录 B：首版语言子集

- **Java**：关键字、基本类型、字面量（含 `"""` 文本块 P1）、`//` 与 `/* */`、注解 `@X`、数字后缀、泛型符号按运算符处理。不识别泛型嵌套与 lambda 语义。
- **C / C++**：预处理指令整行、`//`、`/* */`、字符/字符串（含转义）、数字（十六进制/浮点/后缀）、C++ 的 `class/namespace/template` 关键字子集。
- **JSON**：字符串、数字、`true/false/null`、标点；不校验结构合法性。
- **XML/HTML**：`<tag`、属性名、属性值、注释 `<!-- -->`、CDATA（P1）；不做标签配对校验。
- **Markdown**：见 7.1 支持表。

## 附录 C：参考资料

- sora-editor：<https://github.com/Rosemoe/sora-editor>
- Markwon：<https://github.com/noties/Markwon>
- RSyntaxTextArea：<https://github.com/bobbylight/RSyntaxTextArea>
- flexmark-java：<https://github.com/vsch/flexmark-java>
- commonmark-java：<https://github.com/commonmark/commonmark-java>
- Lezer Parser System：<https://lezer.codemirror.net/>
- Lute（思源笔记内核）：<https://github.com/88250/lute>
- Vditor：<https://github.com/Vanessa219/vditor>；MarkText：<https://github.com/marktext/marktext>
- tm4e（TextMate for Eclipse）：<https://github.com/eclipse/tm4e>
- CommonMark 规范附录《A parsing strategy》：<https://spec.commonmark.org/>

## 附录 D：实现记录（2026-10-28，与设计的偏差）

已落地：M1 语言识别 + 源码高亮、M2 增量重扫、M3 Markdown 解析与双端预览、M4 文件通路与草稿版本升级，两端可用。

| 设计写法 | 实际实现 | 原因 |
| --- | --- | --- |
| 独立 `LanguageRegistry` | 合并进 `LanguageId` 的静态方法 | 检测入口只该有一个，少一个类 |
| `LineIndex` 用间隙数组 | `int[]` + 尾部 `arraycopy` 平移 | 20 万行内平移几十微秒，间隙收益要等分块文件 |
| 每行常驻 `TokenSink` | 只存每行行首状态（1 B/行），绘制时 `paint(from,to)` 现算 | 1 MB 源码可省数 MB，且编辑后不用平移 token |
| Markdown 显式容器栈 | 单遍扫描 + 按标记/缩进判定，行内按最外层配对 | 首版子集够用；深层嵌套留 P1 |
| Windows 用 `doc.render(...)` 批量上色 | 缩小重绘窗口，每行先归零再贴 token | `render` 持读锁、`setCharacterAttributes` 要写锁，读锁不可升级，同线程会永久等待 |
| 代码块 `end` 含闭合围栏 | 单独记录 `contentStop`，正文不含围栏行 | 否则 `source(i)` 会把 ` ``` ` 带进代码内容 |

未做（P1）：括号匹配与折叠、大纲面板、源码↔预览块级滚动锚点、表格列宽/合并、预览随缩放、代码块复制按钮、Markdown 增量重解析（当前整篇重建）。

验证记录：

| 项目 | 结果 |
| --- | --- |
| `editor-core` CoreTest | 48,482 项通过 |
| `TextTest`（新增） | 3,679 项通过，含 Java/Markdown/C 各 1,200 步随机编辑的"增量 == 全量"逐 token 对照 |
| Windows DesktopTest | 115 项通过 |
| Windows GuiTest | 633 项通过（scale 1.25，明暗与预览截图）；沙箱拦截 `reg.exe` 导致 `build.ps1` 退出码 1，断言全 PASS |
| Android AdapterTest | 7,620 项通过（含 2,000 步随机编辑逐步重建三元组） |
| Android 设备测试（MuMu 模拟器，Android 15，1440×2560 / 640dpi） | `DeviceTests` 81 + 5 checks 通过；`preview.ps1` 11 组截图全通过，含 `java`（高亮）、`markdown`（块级预览）、`narrow-large-type`（210dp × 1.5 倍字体） |
| Android Lint / 打包 | 0 error / 10 warning（与改造前同组）；release APK 1,651,526 字节，较 1,633,070 增长约 1.1% |

已知限制：Windows 可视区之外的旧颜色要滚到才刷新；安卓 80 ms 内多次编辑退化为一次全量重扫；两端预览都在 2 MiB 处关闭；预览为只读；窄屏（< 300 dp）时标题栏隐藏副标题，查找按钮文字可能省略为“…”（原先副标题会一字一行把标题栏撑高，正文被压成 0 高，已按紧凑布局处理）。
