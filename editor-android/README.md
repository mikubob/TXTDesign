# 简文本安卓端

Java + XML 的 Material 3 单文档 UTF-8 编辑器，最低 Android 8.0（API 26）。共享编辑核心直接编译相邻 `../editor-core/src/main/java`；没有复制核心或改写为 Compose/Kotlin。

## 构建与安装

这是可由 Android Studio 直接打开的标准 Gradle Android 工程，工程根目录为 **editor-android/**。固定版本：Gradle Wrapper **8.11.1**（含官方 SHA-256 校验）、AGP **8.9.2**、Material Components **1.12.0**、AppCompat **1.7.0**、Lifecycle ViewModel **2.8.7**。使用 API **35 revision 2**、Build Tools **36.0.0**、minSdk **26**、targetSdk **35**、Java **8** 字节码。本机已验证构建 JDK **21.0.9**；AGP 的 JDK 基线为 17，本工程已验证 JDK 21。

在项目根目录的 PowerShell 执行：

```powershell
$env:JAVA_HOME = 'D:\CodeEnvironment\JDK21\jdk-21.0.9'
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
powershell -ExecutionPolicy Bypass -File .\editor-android\build.ps1 -Test
```

首次构建需联网下载 Wrapper 与 Maven 依赖；SDK Manager 安装 Android SDK Platform 35 和 Build Tools 36.0.0。脚本也接受 `-Sdk 'SDK目录'`，默认依次读取 ANDROID_HOME、ANDROID_SDK_ROOT、LOCALAPPDATA/Android/Sdk。脚本生成本机 `local.properties`；它不纳入版本管理。

产物：**build/txteditor-android.apk**（脚本便捷副本）；Gradle 原始产物位于 `build/outputs/apk/release/SimpleText-release.apk`。发布变体启用 R8 混淆与资源裁剪并沿用调试证书签名，体积约为调试包的四分之一。`-Test` 执行真实编译、5,576 项 JVM 适配检查、Android Lint 和 APK 签名校验。依赖下载完成后，可在本模块运行：

```powershell
.\gradlew.bat --offline assembleRelease adapterCheck lintDebug
```

连接一台已授权手机或模拟器：

```powershell
powershell -ExecutionPolicy Bypass -File .\editor-android\build.ps1 -Install
# 多设备时明确指定：
powershell -ExecutionPolicy Bypass -File .\editor-android\build.ps1 -Install -Serial emulator-5554
```

也可直接把 APK 复制到手机并安装。包名保持 `txteditor.android`。现有调试证书从 `build/debug.keystore` 迁移至 **signing/debug.keystore**，新 APK 与旧版同签名，可覆盖安装保留草稿。请备份这个本地演示证书；Gradle clean 不会删除它。新检出首次使用脚本时会优先迁移旧证书，否则生成调试证书。证书不提交版本库，不用于商店发行。若安装报告签名不一致，脚本会停止，**不会卸载或清除用户数据**。

## 操作与界面

- Material 工具栏显示文件名、未保存星号、保存和更多操作。更多菜单提供新建（先选文件类型，决定默认后缀与高亮语言）、打开、另存为、关闭；首次保存进入系统另存为。
- 撤销、重做使用紧凑图标按钮，48 dp 点击区域、文字无障碍说明和长按提示。禁用状态会变淡。Ctrl+Z、Ctrl+Shift+Z、Ctrl+Y 仍进入同一共享核心，保留最近 100 条历史。
- 查找可展开、关闭；替换单独展开。Material 输入框支持清空，键盘“搜索”执行下一处，“完成”执行单次替换。按字面区分大小写，KMP 从选区末尾查找、末尾最多回绕一次；替换词留空表示删除。当前选区不是匹配时先查找再替换。
- 明暗主题跟随系统；蓝色强调、统一圆角和间距，正文使用易读的无衬线字体及额外行距。
- 横屏查找与正文左右分栏；可用高度很小时收起副标题、统计与第二行操作，把撤销/重做/查找保留在更多菜单。可用高度不足 160 dp 时暂时隐藏工具栏，收起键盘后恢复。搜索区可滚动，键盘、系统栏及刘海区域通过 WindowInsetsCompat 处理。
- 返回先收起查找面板，再执行原来的未保存离开保护。新建、打开、关闭及返回可保存、放弃或取消；保存失败、取消文件选择均不执行后续离开操作。
- 字符数按 Unicode 码点统计；空文档为 1 行，末尾换行增加 1 行。文件 I/O 期间暂停编辑。

## 文件与恢复

SAF 的 ACTION_OPEN_DOCUMENT / ACTION_CREATE_DOCUMENT 通过 ContentResolver 访问 URI，不转换文件路径，不申请存储或联网权限；提供者允许时保留持久化授权。URI 失效、文档移动或读取失败仍保留当前文本。

共享 TextCodec 严格解码 UTF-8，接受首 BOM，将 CRLF/CR 转 LF，保存不添加 BOM。非法 UTF-8 拒绝打开。首版打开上限 16 MiB；完整文本镜像与统计仍有 O(N) 成本。

覆盖 URI 前，先用 AtomicFile 落盘应用私有 recovery.bin；备份失败就不覆盖。提供者写入或关闭失败时，保留文本、原 URI、修改状态和恢复副本，Material 对话框提供“另存为”。成功后才更新保存基准并清理恢复副本。SAF 提供者不保证原子覆盖，失败后原目标可能部分写入，恢复副本用于保护编辑内容。

AppCompatActivity 通过自身 ViewModelStore / ViewModelProvider 管理唯一 EditorViewModel，不再手工保留 Store。旋转保留同一核心对象、选区、历史、查找字段及滚动位置；I/O 在单线程执行器进行，核心只在主线程访问。

编辑空闲 700 ms 和退到后台时写 draft.bin，包含文本、最近保存快照、URI、选区与查找状态。重启选择较新的草稿或覆盖前恢复副本；进程重建后历史重新开始，旋转保留历史。明确放弃后新建/关闭替换旧草稿。断电或草稿写入前强杀，仍可能丢失最后不到 700 ms 的输入。

CoreEditText 保留输入法组合区、代理项边界和核心同步适配，仅将控件基类接入 AppCompatEditText。候选提交后以一次最小区间替换送入核心；保存、后台与旋转前提交组合。不逐字 setText，不启用第二套原生撤销；程序回写抑制监听。UTF-16 选区吸附完整代理项边界，半个代理项删除会删除整个表情；完整字素簇处理不在本期范围。

## 设备回归与预览

```powershell
# 会替换测试安装的当前文档与草稿，只对测试模拟器执行
powershell -ExecutionPolicy Bypass -File .\editor-android\device-test.ps1 -Serial emulator-5554

# 先执行设备回归安装测试 APK，再生成真实屏幕截图
powershell -ExecutionPolicy Bypass -File .\editor-android\preview.ps1 -Serial emulator-5554
```

设备脚本构建主/测试 APK，再检查编辑、中文组合、选区、100 条历史、查找替换、旋转、SAF 取消、UTF-8 读写、失败恢复副本与强杀恢复。仅独立测试 APK 包含故障文档 Provider。实体测试机需显式加 `-TestInstance`；不要在含工作文档的安装上运行。脚本不会卸载主应用。

预览脚本仅接受模拟器，会写演示草稿、切换明暗/横竖屏/字体与屏幕尺寸；结束时恢复原显示设置。截图与断言输出存放 **previews/**。InputConnection 合成测试不能代替所有真机中文输入法候选点击。当前实测及未测项见 [TEST-REPORT.md](TEST-REPORT.md)。

## 结构与版本依据

`src/main/java`：Activity、核心控件适配、ViewModel、私有草稿；`src/main/res`：Material XML 与明暗主题；`src/test`：无设备 JVM 自检；`src/androidTest`：独立回归与截图；`gradle/wrapper`：标准 Wrapper；`signing`：本机调试签名。

采用已验证的稳定版本进行渐进升级，未追随最新组件版本：
[AGP 8.9 官方兼容表与 8.9.2 修复](https://developer.android.com/build/releases/agp-8-9-0-release-notes)、
[Gradle 8.11.1 Java 兼容表](https://docs.gradle.org/8.11.1/userguide/compatibility.html)、
[Material 1.12.0 官方发布](https://github.com/material-components/material-components-android/releases/tag/1.12.0)、
[Material3 / AppCompat 接入指南](https://github.com/material-components/material-components-android/blob/1.12.0/docs/getting-started.md)、
[AppCompat 官方版本记录](https://developer.android.com/jetpack/androidx/releases/appcompat#1.7.0)、
[SAF 官方文档](https://developer.android.com/training/data-storage/shared/documents-files)。
