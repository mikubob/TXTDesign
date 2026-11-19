# TXTDesign 项目协作规范

本文件适用于整个仓库，长期有效。开始修改前先读本文件，再读目标模块的 `README.md`；涉及高亮、Markdown、预览或性能阈值时，同时读 `docs/多语言高亮与Markdown渲染设计.md`。代码与实测结果优先于文档：发现文档和实现不一致，先核实，再在同一次改动里把文档改对。

本文件只记录长期适用的规则，不写某一版的功能清单、修复过程和验收结论——这些属于各模块的 `README.md`、`TEST-REPORT.md` 和 `DESIGN.md`。

## 1. 项目定位与不可破坏的前提

TXTDesign 是离线、中文界面、严格 UTF-8 的纯文本编辑器，两个平台共用一个纯 Java 8 编辑核心。

- **离线。** 应用运行时不联网，安卓不申请网络与存储权限，不使用 WebView、JS 引擎或远程资源。任何需要联网才能工作的功能一律不做。
- **双端一致。** 同一份文本、同一个核心词法器和解析器，两端只在绘制与文件通路上不同。文本结果、字符统计、历史语义、编码规则必须一致；行为不一致视为缺陷。
- **算法是交付物。** `GapBuffer`、`Kmp`、各语言手写 DFA 词法器、`KeywordTrie`、`LineIndex`、`MdParser` 是本项目的核心内容，也是课程设计目标的依据。禁止用 `String.indexOf`、正则、第三方解析器或现成编辑组件替换它们；优化只能在这些实现内部做。
- **Java 8 语言级。** 核心与两端源码均以 `--release 8` / `sourceCompatibility 1.8` 编译。禁止 `var`、`record`、文本块、`switch` 表达式、`List.of`、`String.repeat`、`Stream.toList` 等 Java 9+ 语法与 API。本机装了 JDK 21 也不代表可以使用高版本特性。
- **零新增依赖是默认值。** `editor-core` 不得有任何第三方依赖。Windows 只固定使用 `editor-windows/libs/flatlaf-3.7.2.jar`；Android 只使用 `build.gradle` 里已锁定的 AGP 8.9.2、Material 1.12.0、AppCompat 1.7.0、Lifecycle 2.8.7。新增或升级依赖属于独立任务，必须先征得用户同意，并同步 `THIRD-PARTY-NOTICES.md`、`libs/README.md`、构建脚本与 `.github/workflows/build.yml`。
- **不改构建体系。** 本项目刻意不用 Maven，Windows 用 `javac`+`jar`+PowerShell，安卓用固定 Wrapper 的 Gradle。不得为了"更规范"引入 Maven、Kotlin、Compose、JUnit、代码格式化器或脚手架。

## 2. 模块边界与依赖方向

| 模块 | 职责 | 必须遵守 |
| --- | --- | --- |
| `editor-core` | 文本模型、间隙缓冲区、KMP、编码、词法、Markdown AST、会话快照 | 纯 Java 8；不 import `java.awt`、`javax.swing`、`android.*`、`java.nio.file.Path`、`java.net.URI`；不做文件 I/O、主题、剪贴板、对话框、线程调度 |
| `editor-android` | Activity、`CoreEditText` 输入适配、ViewModel、草稿、SAF 文件通路、预览绘制 | 只调用 core 的 public API；通过 `sourceSets` 直接编译 `../editor-core/src/main/java`，禁止复制、改写核心文件；文件访问走 `ContentResolver`，不转真实路径 |
| `editor-windows` | Swing 窗口、`DocumentFilter` 适配、主题、安全写入、高亮绘制、预览 | 构建时只读 `../editor-core/src`，产物只写本模块 `build/`，禁止写入 `editor-core/` 与 `editor-android/`；文件读写走 `SafeFiles` / `EditorStore` |

- 依赖方向只能是 `editor-core ← editor-android`、`editor-core ← editor-windows`，反向依赖一律禁止。
- 视图层不共享：高亮上色、预览控件、主题、图标、快捷键各自在平台模块实现。两端共享的只有 core 的词法结果、AST 和文案键。
- 共享文案只放在 `TextKeys`。新增跨端提示先加键再各自呈现，禁止在两端各写一份同义中文字符串。
- 新增顶层目录或新模块需先说明理由并获用户同意。不建 `common`、`shared-ui`、`utils` 之类的横向层。

## 3. 文本与文件不变量（优先级最高）

这一节的规则是数据安全底线，任何"简化"都不得触碰。

- **偏移一律 UTF-16。** 不得在代理项中间落光标、选区或 token 边界；用 `EditorModel.isBoundary` 吸附。删除一个表情必须删掉完整代理项对。字符统计用码点数（`getCodePointCount`），列号按 UTF-16 单元计。
- **严格 UTF-8。** 读文件接受文件首 BOM，`CRLF`/`CR` 统一为 `LF`；保存写出无 BOM 的 UTF-8 + LF。非法 UTF-8 必须拒绝打开并保留原内容，不允许用替换字符"救回来"。
- **16 MiB 上限。** 超限时桌面端只读打开并保留原文件：只读取并展示开头 16 MiB（`TextKeys.TOO_LARGE_PARTIAL`），禁止整份读入内存；安卓端直接拒绝打开并保留当前内容（`TextKeys.TOO_LARGE_REFUSED`）。截断只能发生在只读视图里，必须在文案中说明"仅显示开头部分"，不允许悄悄少读；不提供分块编辑。提示文案必须与各自的实际行为一致，不得用"已按只读方式打开"描述被拒绝的打开。放宽上限属于独立设计任务。
- **`markSaved` 只在平台写入成功后调用。** 写入失败保持 dirty、保留文本、原路径和恢复副本，给用户"另存为 / 继续编辑"两条路；禁止先清脏标记再写文件。
- **覆盖前先有恢复副本。** Windows 走同目录 `.txteditor-*.pending` + force + 原子移动，移动不支持时先 `.backup`；安卓覆盖前先用 `AtomicFile` 落 `recovery.bin`，备份失败就不覆盖。任何简化都不得减少这一层。
- **草稿与恢复。** 编辑后 700 ms 防抖落盘，切后台与退出前各落一次；草稿带版本号，新格式必须能读旧格式（缺省类型/视图模式），禁止让升级清空用户草稿。损坏的草稿丢弃恢复但保留文件。
- **未保存离开三选一。** 新建、打开、关闭、返回都必须能"保存 / 放弃 / 取消"；保存失败或用户取消文件选择时，不执行后续离开动作。
- **线程。** `EditorModel` 非线程安全，只在一个 UI 线程上访问；文件 I/O 在后台（SwingWorker / 单线程执行器），后台只碰不可变文本快照与文件。
- **路径与密钥。** 不把本机绝对路径写进源码、文档默认值或测试断言；签名文件、`local.properties`、`dist/`、`build/` 永不入库。

## 4. 编辑历史与输入适配

- 历史双栈上限 100 条（`HISTORY_LIMIT`），无实际内容变化不入历史，也不清空重做栈；`load` 清历史，`markSaved` 不清；撤销回保存点自动清除 dirty。
- 编辑事务只用于合并粒度：相邻、同段、间隔小于 500 ms（`MERGE_WINDOW_MILLIS`）才可能合并；事务不支持嵌套。不要为了"更细的历史"随意改这两个语义。
- 输入法：保留控件组合态，候选提交时以一次最小区间替换送入核心；程序回写（加载、撤销、主题、缩放）必须用抑制标记挡住回调；禁止逐字 `setText`，禁止启用平台自带撤销，禁止把组合中的文本提前提交。
- 查找是字面匹配、区分大小写、从选区末尾开始、末尾最多回绕一次，`findNext` 不改选区；替换词为空表示删除。空词时相关按钮禁用，不用弹窗代替禁用。
- 新增平台行为前先问：这个状态该由 `EditorModel` 拥有，还是视图自己的？文本、选区、历史、保存基准属于模型；颜色、滚动、缩放、面板开合属于视图。不要把视图状态塞进模型或历史。

## 5. 高亮、预览与降级阈值

- 高亮与预览只读 `getText()` 快照，绝不写回模型，绝不产生历史记录。样式变化不进撤销栈。
- token / 块边界必须落在 `isBoundary` 认可的位置；颜色只作辅助，语义不能只靠颜色表达。
- 阈值是硬约定，改动必须同步 `docs/多语言高亮与Markdown渲染设计.md` §11 与两端实现：

| 条件 | 行为 |
| --- | --- |
| 文本 > 2 MiB | 关闭自动高亮与预览，状态栏说明 |
| 单行 > 100 K 字符 | 只渲染可视区间 |
| Markdown 节点 > 200 K | 截断 AST，预览降级为纯文本 |
| 容器/行内嵌套 > 32 | 不再深入，按字面处理 |
| 1 MB Java 全量词法 | 桌面 < 60 ms、中端安卓 < 150 ms |
| 按键到重绘 | 桌面 < 16 ms、安卓 < 32 ms |
| 1 MB Markdown 块解析 | 桌面 < 120 ms、安卓 < 300 ms |

- 防抖参数（高亮 80 ms、预览约 200 ms、草稿 700 ms）是有意为之，不要改成每次按键全量重扫。
- Markdown 预览不执行内嵌 HTML，不加载远程图片，链接只放行 `http`、`https`、`mailto`；`file:`、`javascript:`、`data:` 按普通文本显示。安卓跳转前先 `resolveActivity` 判空。
- 复杂度要如实描述：`getText()` 是 O(N) 快照，按键、统计、高亮都带 O(N) 成本，不得把整次按键说成 O(1)。

## 6. 代码风格

- 可读性优先于行数。主流程按执行顺序从上到下，先处理无效输入和失败，再走正常路径；不写多层嵌套和需要解码的一行表达式。
- 排版与相邻文件保持一致，包括现有的紧凑写法（如 `if (x) { fb.replace(...); return; }`）。不因个人风格重排无关文件，不引入新的格式化规则。
- 命名直接表达职责，沿用现有词汇：`EditorModel`、`TextCodec`、`SafeFiles`、`DraftStore`、`HighlightController`、`TokenSink`。新类要能一句话说清职责，否则说明它该合并进现有类。
- 不提前抽象。单次使用的逻辑就近实现；只有真实复用、复杂度值得单独阅读，或代表独立概念时才抽方法或类。禁止新增只做转发的包装、`Manager`/`Helper` 套壳、为"以后可能用到"预留的参数和开关。
- 外部数据只在信任边界校验一次：文件字节、URI、草稿字节、平台 Intent。校验后向内传递含义明确的值，不在各层重复判断同一条件。安全、可访问性、防数据丢失的检查不在此列，照常保留。
- 异常不得吞掉。失败必须有用户可见反馈，且不丢数据；不用 `try/catch` 把失败包装成空结果或成功状态。只在需要资源释放、失败清理、异常转换的边界捕获。
- 注释用中文，讲"为什么"和看不出约束，不复述赋值和循环。实现与注释不一致时，同一次改动里改注释。已知取舍沿用仓库既有写法：

  ```java
  // shortcut: 80 ms 内多次编辑就整体重扫；要连打也增量，需先合并脏区间再喂 update。
  ```

- 历史英文 Javadoc 保留，不批量翻译；新写或改到已过时的注释时用中文。
- 新增文件编码 UTF-8；`.java`、`.md`、`.xml`、`.gradle`、`.properties`、`.yml` 用 LF，`.ps1`、`.cmd`、`.bat` 用 CRLF，与 `.gitattributes` 一致。不要把 CRLF 混进 Java 文件。
- 生成物先改源头：版本号改 `editor-android/build.gradle`，打包参数改 `package.ps1`，工作流改 `.github/workflows/build.yml`。不手工改 `build/`、`dist/` 里的 APK、EXE、JAR。

## 7. 界面与文案

- 简洁程度以 Linear 官网和苹果备忘录为参考。删掉标语、重复标题、冗余说明和次要标签；页面需要额外文字才看得懂时，先改布局和信息层级。补充信息用 tooltip / 长按提示，不常驻。
- 危险或不可逆操作（覆盖文件、放弃未保存内容、关闭文档）必须就地说明后果与范围，并保留确认步骤。不用"确定 / 取消"这种无信息量的按钮文案。
- 可访问性不打折：图标按钮要有无障碍名称与提示（安卓 `contentDescription`、Swing `AccessibleName` + tooltip），触摸区不小于 48 dp，禁用状态可感知，键盘焦点可见、顺序合理，正文对比度不低于 4.5:1。
- 浅色 / 深色必须同时成立。新颜色从现有色板派生，不另建主题系统；新增的强调色、边框、代码配色两套都要检查。
- 既有布局阈值不要随手改：Windows 最小窗口 720×520 逻辑像素（经 `UIScale` 缩放），矮窗口先收抬头、文件信息行；安卓可用高度低于 160 dp 时隐藏工具栏，键盘、系统栏、刘海用 `WindowInsetsCompat` 处理。
- 状态栏口径固定：字符数按码点、空文档 1 行、末尾 LF 增 1 行、列按 UTF-16、显示 `UTF-8` / `LF`。改统计口径等于改契约，两端同步改并补检查。
- 文案用中文，术语两端一致（文件类型名、菜单项、失败提示）。跨端提示走 `TextKeys`。
- 不用假数据、假状态或"演示模式"充数；截图和预览图必须来自真实渲染，不能拿设计稿冒充。

## 8. 构建与验证

按改动范围选最小但足够的检查，跑完把实际命令与结果写进交付说明。

| 改动 | 必跑 |
| --- | --- |
| `editor-core` 算法 / 词法 / Markdown | `powershell -ExecutionPolicy Bypass -File .\editor-core\build.ps1`，再跑 `.\editor-windows\build.ps1` 验证桌面端编译与适配 |
| Windows 代码 | `powershell -ExecutionPolicy Bypass -File .\editor-windows\build.ps1`；有可见变化加 `-GuiTest`，并按需用 `-Dflatlaf.uiScale=1.5` 复跑 `GuiTest` |
| Android 代码 | `powershell -ExecutionPolicy Bypass -File .\editor-android\build.ps1 -Test`（编译 + `adapterCheck` + `lintDebug` + 签名校验） |
| Android 设备行为（SAF、输入法、旋转、草稿、强杀恢复） | `.\editor-android\device-test.ps1 -Serial emulator-5554`；只对模拟器或专用测试机执行 |
| 界面截图 / 预览图 | `.\editor-android\preview.ps1 -Serial emulator-5554`（仅模拟器，会改演示草稿与显示设置） |
| 安装器 | `.\editor-windows\package.ps1 -Version X.Y.Z`，需 JDK 21 与 WiX 3.14.1 在 PATH |
| 纯文档 | 只核对内容、链接、路径与实现一致，不启动应用、不跑无关测试 |

- 测试是带 `-ea` 的 `main` 程序，靠断言和退出码判定，没有 JUnit。新增非平凡逻辑（分支、循环、解析、编码、历史、文件通路）必须补一个能失败的小检查；随机对照测试使用固定种子，失败可复现。
- 测试调用真实实现，不把生产算法抄一份到测试里自证；只对文件系统、设备、系统对话框等边界做替身，并说明真实联调范围。
- `device-test.ps1` 会替换当前文档与草稿，写明只对模拟器 / 专用测试机执行。不要在存有真实文档的设备上运行，也不要在脚本里加卸载步骤。
- JVM 测试不等于设备测试，Swing 脚本驱动不等于真人输入法和真实按键；`README` / `TEST-REPORT` 中引用的检查项数必须和本次实际输出一致，数字变了就同步改，不沿用旧数字。
- 构建产物只落 `build/`、`dist/`，不入库。手工验证后清理演示文件，不把测试文档留在仓库里。

## 9. 文档与发布

- `README.md` 面向使用者：功能、安装、升级、从源码构建。内部设计、审查、验收细节放各模块 `TEST-REPORT.md` / `DESIGN.md`，不要把验收记录堆到 README 底部。
- 行为变化同步对应文档：模块 README 的操作说明、`TEST-REPORT.md` 的实测数字、`docs/多语言高亮与Markdown渲染设计.md` 的阈值和实现偏差（附录 D）。文档里不写未验证的结论。
- 版本发布：先改 `editor-android/build.gradle` 的 `versionCode` / `versionName`，再打三段式标签 `vX.Y.Z` 并推送；工作流据标签推导安装器版本并创建 Release。不手工上传产物替换自动构建结果。
- 发布说明只写本次真实新增与修复，不把上一版功能包装成新功能；签名、包体、体积、校验值等表述必须有实际证据。
- 新增第三方代码或资源必须同时更新 `editor-windows/THIRD-PARTY-NOTICES.md`（或对应模块的声明）和 `libs/README.md` 的 SHA-256；构建脚本里的校验值同步改。

## 10. Git 与工作区

- 提交信息用中文，动词开头，一句话说清范围与结果，例如 `为两端加入 Markdown 实时预览与最近文件，发布 v1.5.0`。一次提交只做一件事，不把重构和功能混在一起。
- 不自动提交、推送、打标签或发 Release；这些动作按当次授权执行。
- 保留工作区已有未提交改动，不 `reset --hard`、不 `checkout --`、不顺手提交无关文件。禁止 `git add -A` 一把梭。
- 不提交：`build/`、`.gradle/`、`local.properties`、`.idea/`、`signing/`（含 `*.keystore`）、`dist/`、`.tools/`、`.workbuddy/`。
- 不删除 `.workbuddy/`，它是项目协作数据。
- 跨模块改动、算法改动、持久化格式改动、发布打包改动，在交付前做一次独立复审（可用子代理）；主代理负责修复确认的问题并复验，通常每轮只复审一次。

## 11. 交付说明

完成改动后，用简短几段说明：

1. 改了哪些文件、做了什么，以及为什么这样做；
2. 实际执行了哪些命令、结果如何（通过/失败、关键数字）；
3. 哪些没有验证，以及原因（缺设备、缺交互桌面、需真人输入法等）；
4. 可能影响既有功能、兼容性或用户数据的改动，单独点出。

不宣称未执行的检查通过，不把静态检查、替身测试或构建成功说成真机联调完成。

## 12. 红线清单

以下行为一律视为错误，需要回退或重做：

1. 在 `editor-core` 里引入平台 API 或第三方依赖。
2. 把核心算法复制到某一端，或改成两套实现。
3. 用高版本 Java 语法 / API。
4. 拆开代理项、按 `char` 统计字符、按字节截断可编辑文本，或把超限文件的整份内容读进内存。
5. 未成功写入就调用 `markSaved`，或失败后丢弃用户文本。
6. 覆盖文件前不写恢复副本，或删掉 pending / backup / AtomicFile 逻辑。
7. 把高亮、预览、缩放等视图状态写进模型或撤销历史。
8. 把编码错误、写入失败包装成成功或空结果。
9. 未经同意新增依赖、升级 AGP/FlatLaf/Gradle、引入 Maven 或格式化工具。
10. 提交签名文件、密钥、本机路径、构建产物。
11. 在存有真实文档的设备上跑会覆盖数据的测试脚本。
12. 在交付说明里声称未执行的验证已完成。
