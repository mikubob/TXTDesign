# 安卓 Material 3 改造验证记录

日期：2026-10-28。以下为本次改造后实际执行结果，旧 UI 的测试记录不计入本次通过数。改动限定在 editor-android/；共享核心按源码引用，未修改核心或 Windows 模块。

## 环境与安装包

- Windows 11，Windows PowerShell 5.1，JDK 21.0.9。
- 标准 Gradle Wrapper 8.11.1（官方 SHA-256 校验）、AGP 8.9.2；compileSdk 35 revision 2、Build Tools 36.0.0、targetSdk 35、minSdk 26、Java 8 字节码。
- Material 1.12.0、AppCompat 1.7.0、Lifecycle ViewModel 2.8.7。构建不再调用旧的手工 AAPT2/javac/D8 拼装链路或 libs JAR。
- 测试实例：Medium Phone API 37.0，Android 17，x86_64，软件图形渲染；1080×2400、420 dpi。未连接实体手机。
- APK：[build/txteditor-debug.apk](build/txteditor-debug.apk)，**6,699,832 字节**，包名 txteditor.android，versionCode 2 / versionName 1.1。
- APK SHA-256：`701334C2D10F30F5349260D936E8ECCA19D2D9DEB6F960A497B39A0F14160398`。
- 签名证书 SHA-256：`F478EB230EE017DAF791045DAC50B665BB0859DF444EAB3F053B1581FE0E3D8B`，与原 build/debug.keystore 相同。apksigner v2 校验通过；主/测试 APK 均以 adb install -r 安装成功。没有卸载应用或清除用户数据。
- 原调试证书已迁移至 signing/debug.keystore，避免 Gradle clean 删除证书；请单独备份。测试只覆盖测试模拟器的演示文档与草稿。

## 构建与静态检查

实际入口：

```powershell
powershell -ExecutionPolicy Bypass -File .\editor-android\build.ps1 -Test
powershell -ExecutionPolicy Bypass -File .\editor-android\device-test.ps1 -Serial emulator-5554
powershell -ExecutionPolicy Bypass -File .\editor-android\preview.ps1 -Serial emulator-5554
```

主 APK、独立测试 APK、签名校验通过。现有固定种子 JVM 自检通过：**AdapterTest passed: 5576 checks (seed 1949)**，包含 2,000 步随机输入/删除/替换、逐步撤销重做、中文/代理项边界、KMP、草稿序列化与未知版本拒绝。调用的是真实共享核心源码。

依赖缓存后，标准 Wrapper 的 `--offline assembleDebug adapterCheck` 也实际通过。完整构建与设备日志：[build/final-device-run.txt](build/final-device-run.txt)；离线日志：[build/offline-build.txt](build/offline-build.txt)。

Android Lint **0 errors，10 warnings**，没有设 baseline 或关闭错误检查。剩余警告：固定依赖有新版提示（4）、原备份配置的 dataExtractionRules 提示（1）、最低 API 下 v26 图标目录冗余（1）、嵌套布局权重（1）、未提供主题单色图标（1）、不对称内边距提示（2）。报告：[build/reports/lint-results-debug.html](build/reports/lint-results-debug.html)。JDK 21 对 Java 8 编译级别及旧 SAF 回调仍有弃用提示。

## 设备功能回归

最终结果：**主回归 70 项通过，强杀后新进程恢复 5 项通过**。原始输出：[主回归](build/device-test-false.txt)、[恢复回归](build/device-test-true.txt)。

| 检查 | 本次结果 |
| --- | --- |
| 实例确为 MaterialToolbar / MaterialButton / TextInputEditText；图标说明和 48 dp 点击宽度 | 通过 |
| InputConnection 拼音候选更新、中文组合提交、一次撤销 | 通过 |
| 中文、表情、选区替换、跨行剪贴板粘贴/剪切与撤销 | 通过 |
| 删除半个 UTF-16 代理项时保持完整表情边界 | 通过 |
| Android 33+ TextAttribute 版本的 commitText 与撤销选区还原 | 通过 |
| 通过编辑控件产生 105 次编辑，界面撤销仅保留最近 100 次，再重做 100 次 | 通过 |
| KMP 下一处、单次替换、撤销替换；共享核心固定操作序列 | 通过 |
| 查找/替换分层展开、返回先关闭面板、关闭后取消离开 | 通过 |
| 横竖屏实际重建：同一个核心对象、历史、文本、查找字段及替换展开状态 | 通过 |
| 系统 SAF 打开选择器取消，保持原文与 URI | 通过 |
| ContentResolver 读 BOM/CRLF/CR，严格 UTF-8；非法 UTF-8 和失效 URI 不覆盖原文 | 通过 |
| 保存后读取真实 UTF-8 字节，无 BOM，修改状态更新 | 通过 |
| 故障 Provider 返回读端关闭的管道，制造真实写入失败 | 通过；保留文本、原 URI、修改状态与 recovery.bin |
| 后台等待草稿完成、脚本 force-stop、新进程恢复 | 通过；文本、修改状态、URI、行数恢复，历史按设计重新开始 |

MainActivity 使用 AppCompat 自身的 ViewModelStore 与 OnBackPressedDispatcher，原手工 Store 已移除。CoreEditText 保留输入连接适配，仅接入 AppCompatEditText 基类。测试 Provider 与演示内容只在独立测试 APK 中；正式 APK 不预置演示笔记。

## 真实界面截图

**8 种场景共 20 项方向/正文高度/键盘可见性断言通过，10 张截图均已打开检查。** 截图来自实际安装的最终 APK，没有用设计稿代替运行结果。

| 场景 | 截图与检查 |
| --- | --- |
| 浅色竖屏 | [light.png](previews/light.png)，文件名、保存、紧凑操作、阅读留白与统计 |
| 深色竖屏 | [dark.png](previews/dark.png)，正文/图标/系统栏明暗一致 |
| 查找与替换展开 | [search.png](previews/search.png)，Material 输入框与分层替换 |
| 竖屏键盘 | [keyboard.png](previews/keyboard.png)，键盘可见，正文保留 |
| 横屏键盘 | [landscape-keyboard.png](previews/landscape-keyboard.png)，实际横屏，查找和正文分栏 |
| 320 dp 小屏横屏键盘 | [small-landscape.png](previews/small-landscape.png)，840×1491 像素旋转，正文可见完整行 |
| 320 dp 小屏、1.5 倍字体 | [large-type.png](previews/large-type.png)，正文、文件名和保存入口可用 |
| 320 dp 小屏、1.5 倍字体、查找及键盘 | [large-type-search.png](previews/large-type-search.png)，正文至少保留完整行；截图含 Gboard 自身的字体变更提示 |
| 文件菜单与未保存保护 | [file-menu.png](previews/file-menu.png)、[leave-dialog.png](previews/leave-dialog.png) |

原始布局输出：[build/final-preview-run.txt](build/final-preview-run.txt)，各场景也保存了 previews/*.txt。主界面、菜单和对话框另外重取了一次，避开故障回归排队的系统 Toast。其余部分截图保留了应用的草稿恢复 Toast，未修改截图内容。

窗口很矮时隐藏副标题和统计；第二行操作收起后仍可从更多菜单访问。可用高度低于 160 dp 时暂时隐藏工具栏，收起键盘后恢复。搜索区可滚动，正文仍使用平台输入控件；小屏与大字体不强制关闭用户的字体缩放。预览脚本在 finally 中恢复原屏幕尺寸、字体、旋转和明暗设置。

## 未验证与边界

- 未测试实体手机、厂商中文键盘候选点击、TalkBack 人工朗读、Android 8–16 设备矩阵。
- 未验证不同商业文档提供者的持久化授权、权限撤销及提供者自身故障；本次文件故障来自独立测试 Provider。
- 未计时 1 千/1 万/10 万文档的完整 UI 响应；不把核心算法复杂度当作界面性能结果。
- SAF 不保证原子覆盖。失败时原文件可能部分写入；应用保留编辑内容和覆盖前成功落盘的私有恢复副本，供另存为重试。
- 草稿空闲 700 ms 后落盘，正常后台立即安排写入；写入前断电或强杀仍可能丢失最近输入。卸载会清除草稿与恢复副本；本次未执行卸载。
