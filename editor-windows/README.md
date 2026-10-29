# Windows 文本编辑器

单文档、中文、离线的纯文本编辑器。Java + Swing + **FlatLaf 3.7.2**，共享 `../editor-core` 的间隙缓冲区、100 条历史和 KMP；正文仍是标准 `JTextArea`，通过原有 `DocumentFilter` 同步编辑。

## 启动与交付

运行需桌面版 Java 8+；本机实际构建和测试为 Windows 11 / JDK 21.0.9。Java 8 仅经过 `--release 8` 编译检查，未实机运行。

```powershell
cd editor-windows
java -jar .\build\txteditor-windows.jar
```

也可双击 `run.cmd`。最终 JAR 已包含共享核心、FlatLaf 类、资源、原生库和 Apache-2.0 许可；复制单个 JAR 到有 Java 的电脑即可启动，无需配置第三方 classpath。原生库正常由 FlatLaf 解压到临时目录；有执行限制的机器可能无法使用原生窗口装饰，见官方[原生库分发说明](https://www.formdev.com/flatlaf/native-libraries/)。

[GitHub Releases](https://github.com/mikubob/TXTDesign/releases/latest) 另提供 Windows x64 EXE 安装器，自带 Temurin Java 21，无需另装 Java。安装器按当前用户安装，支持开始菜单和桌面快捷方式。源码打包需 Temurin JDK 21 x64 与 WiX 3.14.1 均在 PATH，执行 `powershell -ExecutionPolicy Bypass -File .\package.ps1 -Version 1.4.0`；输出 `build/installer/TXTDesign-1.4.0.exe`。安装器尚未使用商业代码签名证书。捆绑运行时只含 `java.desktop` 与 `java.logging` 两个模块——应用只用 UTF-8，不需要字符集与本地化数据模块，安装包因此约为 28 MB。

## 界面与操作

- 顶部保留文件、编辑、查找菜单与快捷键，新增视图菜单。文件 → 新建 先选类型（纯文本 / Markdown / Java / C / C++ / JSON / XML），Ctrl+N 直接建纯文本。常用工具栏提供新建、打开、蓝色保存、撤销、重做及查找 / 替换；图标有提示与无障碍名称，空历史的撤销 / 重做会禁用。
- 统一浅色 / 深色主题、蓝色强调、中文字体层级、细边框、留白、圆角按钮和细滚动条。视图菜单提供跟随系统 / 浅色 / 深色三态，跟随系统读取注册表 `AppsUseLightTheme` 并每秒比对一次；右上角按钮循环切换。主题、窗口几何、缩放和最近文件存在 `%LOCALAPPDATA%\TXTDesign\session\prefs.properties`，启动首帧前先装主题，避免闪白。还原点落在已拔掉的显示器时回落主屏居中。
- 文档卡片显示文件名、完整路径提示和修改状态。路径过长时省略显示，悬停查看完整位置。底部显示码点数、行数、UTF-8 / LF、光标行列及缩放（列按 UTF-16 单元计数）。
- 查找栏默认收起。Ctrl+F 展开查找，Ctrl+H 或工具栏展开查找替换；宽度 ≥ 960 逻辑像素时停靠右侧（上限 320），不足时回到顶部条；关闭按钮 / Escape 收回并将焦点返回正文，展开状态写入可访问描述。收起保留查找词和替换词。Enter 执行相应查找 / 替换；结果在面板内提示。
- 正文缩放 70%–200%：底部 − / +、Ctrl+减号 / 等号、Ctrl+滚轮；Ctrl+0 或点击百分比重置。缩放不修改文件或历史。最小窗口 720×520，矮窗口自动收起抬头、文件信息行并收紧留白，把高度留给正文。

其他快捷键：Ctrl+N/O/S、Ctrl+Shift+S、Ctrl+Z/Y、Ctrl+X/C/V/A、F3、Alt+F4。查找区分大小写、按字面匹配，从选区末尾开始，末尾未找到最多回绕一次。空词时下一处 / 替换按钮禁用。“替换当前”在当前选区等于查找词时替换，否则先查找再替换；一次替换是一条可撤销命令。

## 从源码构建与验证

需要 JDK 9+，同时具备 java、javac、jar。固定依赖已位于 `libs/`，无需 Maven、Gradle 或在线下载。依赖来源、SHA-256 和恢复下载方式见 [libs/README.md](libs/README.md)；第三方许可见 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)。

```powershell
powershell -ExecutionPolicy Bypass -File .\build.ps1
# 可见窗口自动交互与组件渲染预览（需要交互桌面）
powershell -ExecutionPolicy Bypass -File .\build.ps1 -GuiTest
# 构建、测试后启动
powershell -ExecutionPolicy Bypass -File .\build.ps1 -Run
# 指定 150% FlatLaf 用户缩放的同一 GUI 回归
java -ea '-Dflatlaf.uiScale=1.5' -cp 'build/classes;build/test-classes;build/core-classes;libs/flatlaf-3.7.2.jar' txteditor.windows.GuiTest
# 10 次预热、30 次测量，会显示测试窗口
powershell -ExecutionPolicy Bypass -File .\benchmark.ps1
```

构建只读取 `../editor-core/src`，在本模块 `build/core-classes`、`build/core-test-classes` 编译并执行核心回归；不会调用核心构建脚本或改写核心 / 安卓目录。桌面源码和测试分别输出到 `build/classes`、`build/test-classes`，最终生成 `build/txteditor-windows.jar`。全体源码采用 `--release 8 -encoding UTF-8`。FlatLaf 保留官方原生库和多版本 JAR 资源，最终清单保留 `Multi-Release: true`。

默认构建执行核心、无头适配层及安全文件回归；`-GuiTest` 加测主题、缩放、快捷键绑定、查找面板显示 / 关闭 / 焦点、可见窗口剪贴板和文件对话框，并检查普通 / 最小窗口组件边界和文本对比度。GUI 测试通过 Swing 动作和组件驱动窗口，不代替真人输入法或系统真实按键验收。

预览位于 `build/preview-light.png`、`build/preview-dark.png` 和 `build/preview-*-compact.png`；150% 运行另加 `-150` 后缀。它们是**实际 Swing 组件渲染图**，不是设计稿，也不是桌面系统实拍。Robot 系统截图尝试另存为 `build/windows-screen-attempt*.png`，结果见 [TEST-REPORT.md](TEST-REPORT.md)。

## 文本和文件保护

打开严格 UTF-8，可读 BOM；CRLF / CR 统一为 LF，保存使用无 BOM 的 UTF-8、LF。非 UTF-8 显示编码提示并保留旧内容。另存为使用所选文件名，不自动追加后缀。取消选择、覆盖或离开保留当前状态。超过 16 MiB 的文件按只读打开并给出说明，与安卓口径一致。

会话草稿把文本、光标、选区、滚动、查找词和保存基准写入 `%LOCALAPPDATA%\TXTDesign\session\session.dat`，复用同一套临时文件加原子移动。编辑后 700ms 防抖落盘，切到后台与退出前各落一次；启动时若会话文件晚于上次正常退出（`lastExit`）就恢复文本与光标，状态栏只提示一次「已恢复上次会话」，新建或打开文件后清空会话。落盘失败只提示一次，不打断编辑；会话文件损坏时丢弃恢复但保留文件。

文件通路还包括拖放打开（多文件只取第一个并提示，未保存内容先走离开确认）、最近 5 条（缺失文件置灰）和保存前比对修改时间与大小（被其他程序改过时给「覆盖 / 另存为」两条路）。保存失败给「另存为 / 继续编辑」两条路，与安卓端一致；提示文案统一由 `TextKeys` 提供。

码点数不等同于复杂字形数量；空文档 1 行。内部索引采用 UTF-16，光标与选区吸附到完整代理项边界。修改状态比较最近成功保存文本，撤销回该文本后星号消失；历史保留最近 100 条，连续输入按编辑事务合并成一条（相邻、同段落、间隔小于 500ms），停顿、换行或保存 / 查找等动作会断开合并。

正常输入使用 `DocumentFilter` 局部回写；组合输入暂留控件，提交时以边界安全差分生成核心命令。撤销 / 重做 / 加载使用抑制标记。文件、查找、历史和主题切换前请求输入法提交，避免直接丢弃组合文本。

文件读写在 SwingWorker 中运行，等待期间禁用编辑、菜单和共用工具栏 Action。保存先写同目录 `.txteditor-*.pending` 临时文件并 force / 关闭，再尝试原子替换。不支持原子移动时，先复制 `.backup`，替换失败尝试恢复原文并保留恢复材料；错误框给出路径。只有成功完成才更新路径和保存基准。写入失败保持窗口打开，优先另存为；中途失败的 pending 可能不完整。该机制不承诺断电、硬盘故障或其他程序并发改写的事务保证。

## 验收材料与范围

实际验证、未测项目和预览记录见 [TEST-REPORT.md](TEST-REPORT.md)；算法设计见 [DESIGN.md](DESIGN.md)。请在验收机人工检查中文输入法候选 / 提交 / 取消、真实键盘快捷键和不同显示器的缩放。未新增账号、云同步、多标签、富文本、正则或全部替换。安卓及两端设备端到端验收由安卓任务完成。

FlatLaf 配置依据官方[入门](https://www.formdev.com/flatlaf/)、[主题定制](https://www.formdev.com/flatlaf/customizing/)和[组件属性](https://www.formdev.com/flatlaf/client-properties/)说明。
