# 第三方依赖

FlatLaf 3.7.2 — Copyright 2019–2026 FormDev Software GmbH / Karl Tauber and contributors.

- 项目：https://github.com/JFormDesigner/FlatLaf
- 官方说明：https://www.formdev.com/flatlaf/
- 许可：Apache License 2.0，完整文本位于 `libs/FlatLaf-LICENSE.txt` 及最终 JAR 的 `META-INF/LICENSE`。
- 使用未修改的官方 Maven Central 二进制，包含原生库。最终 JAR 合并其类和资源，替换应用入口清单，不修改第三方类。
- 没有引入 FlatLaf Extras、额外图标库或字体包；工具栏线条图标由本模块实现。

Windows EXE 安装器还包含由 Eclipse Temurin JDK 21 生成的运行时，按 GPLv2 with Classpath Exception 及各组件的第三方许可再分发。完整运行时许可保留在安装目录 `runtime/legal/`；项目及源码入口：https://adoptium.net/ 、https://github.com/adoptium/temurin21-binaries 。JAR 版本不包含 Java 运行时。
