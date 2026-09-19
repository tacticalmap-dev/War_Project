# 第三方组件

本 mod 自带并分发下列第三方代码；Chromium 的二进制文件**不随本 mod 分发**，由使用者机器在首次运行时从公开构建站下载。

## 1. java-cef（CEF 的 Java 绑定，`org.cef.*`）

- 来源：<https://github.com/chromiumembedded/java-cef>，固定提交 `d5e3cece98755ff1e5af39261e6a486a5d9adb5d`
- 许可：BSD 3-Clause（全文见 `src/cefApi/LICENSE-java-cef.txt`，作者信息见 `src/cefApi/AUTHORS-java-cef.txt`）
- 使用方式：源码原样复制到 `src/cefApi/java/org/cef/`（126 个 `.java`），随本 mod 一起编译进 jar；**未做任何修改**。

## 2. Chromium Embedded Framework（CEF）二进制

- 来源：公开的 java-cef 构建产物 `https://mcef-download.cinemamod.com/java-cef-builds/d5e3cece98755ff1e5af39261e6a486a5d9adb5d/windows_amd64.tar.gz`（Chromium 116.0.5845.190，约 119 MiB）
- 许可：BSD 3-Clause（CEF 项目：<https://bitbucket.org/chromiumembedded/cef>）
- 使用方式：**不打包进本 mod**；运行时由 `CefNatives` 下载并解压到 `<gameDir>/war_project-cef/windows_amd64/`，或用者自行预置 tar.gz 离线安装。

## 3. 说明

- 本 mod **不含** MCEF（Minecraft Chromium Embedded Framework）的任何代码；其"如何把 CEF 接进 Minecraft"的做法仅作为设计参考，实现（下载器、引导、OSR 上传、输入桥、消息路由、页面内联）均为本项目自行编写。
- LiteLoader/其它运行期依赖：无。本 mod 的 Chromium 后端对第三方 mod 零依赖。
