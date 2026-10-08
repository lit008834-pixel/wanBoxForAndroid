# wanBoxForAndroid

<p align="center">
  <img src="docs/logo.png" width="128" height="128" alt="wanBoxForAndroid Logo">
  <br>
  <b>面向 Root 设备的独立 Android 代理项目：正式版 v3.0.6；当前预览版 v3.0.7-preview.7，均使用 sing-box v1.15.0-alpha.10</b>
  <br>
  <b>An independent, root-focused Android proxy project: stable v3.0.6 and current preview v3.0.7-preview.7 use sing-box v1.15.0-alpha.10</b>
</p>

<p align="center">
  <a href="https://github.com/lit008834-pixel/wanBoxForAndroid/releases/tag/v3.0.6"><img src="https://img.shields.io/badge/Stable-v3.0.6-blue.svg?style=flat-square" alt="Latest stable release"></a>
  <a href="https://github.com/lit008834-pixel/wanBoxForAndroid/releases/tag/v3.0.7-preview.7"><img src="https://img.shields.io/badge/Preview-v3.0.7--preview.7-5b7cfa.svg?style=flat-square" alt="Latest preview release"></a>
  <a href="https://android-arsenal.com/api?level=21"><img src="https://img.shields.io/badge/Stable%20Android-5.0%2B%20(API%2021%2B)-brightgreen.svg?style=flat-square" alt="Stable minimum Android version"></a>
  <a href="https://android-arsenal.com/api?level=31"><img src="https://img.shields.io/badge/Preview%20Android-12%2B%20(API%2031%2B)-brightgreen.svg?style=flat-square" alt="Preview minimum Android version"></a>
  <a href="https://www.gnu.org/licenses/gpl-3.0"><img src="https://img.shields.io/badge/License-GPL--3.0-orange.svg?style=flat-square" alt="License"></a>
  <a href="https://t.me/OwnBoxs"><img src="https://img.shields.io/badge/Telegram-@OwnBoxs-2CA5E0.svg?logo=telegram&style=flat-square" alt="Telegram"></a>
</p>

---
<div align="center">

## 支持本项目

·如果这个项目对你有帮助,请加入 TG 频道就是对我的最大鼓励

·If this project helps you, joining our TG channel is the best support you can give me.

---
## 📖 项目介绍 / Introduction

**wanBoxForAndroid 从 OwnBoxForAndroid 的早期代码基础发展而来，目前由本仓库独立维护，拥有自己的代码、版本发布与功能路线；它不是 OwnBoxForAndroid 的官方版本，也不再以持续同步上游作为项目目标。项目面向 Root Android 设备，重点发展 Root TUN 与模块化运行。当前 v3.0.7-preview.7 仍保留 VPN TUN 和 Root TUN；纯 Root 模块模式尚未发布。**

**wanBoxForAndroid evolved from an early OwnBoxForAndroid codebase and is now independently maintained with its own code, releases, and roadmap. It is not an official OwnBoxForAndroid release, nor does it aim to continuously sync upstream. The project focuses on rooted Android devices, with Root TUN and module-based operation as its direction. Current v3.0.7-preview.7 still includes VPN TUN and Root TUN; pure Root-module operation has not yet been released.**

项目起源、当前状态与发展方向详见[项目定位与发展说明](docs/project-positioning.zh-CN.md)。

---

## 📥 发行版下载 / Downloads

请前往 **wanBoxForAndroid 自己的 GitHub Releases 页面**下载本仓库发布的 APK，并查看对应版本说明：

Download APKs published by **this wanBoxForAndroid repository** and read the release notes on its GitHub Releases page:

👉 **[前往本仓库 GitHub Releases 下载 / Download from this repository's GitHub Releases](https://github.com/lit008834-pixel/wanBoxForAndroid/releases)**

---

### 📦 安装包架构说明 / Architecture Notes

从 **v3.0.7-preview.6** 起，本仓库新构建只发布 **arm64-v8a**，面向 Android 12 及以上。内部 x86_64 模拟器测试包不作为下载附件。

From **v3.0.7-preview.6**, new public builds are **arm64-v8a only** and require Android 12 or newer. Internal x86_64 emulator fixtures are not release downloads.

以下其他架构说明仅适用于仍保留对应附件的历史版本。

* **ARM64 (v8a)**：适用于绝大多数主流 64 位安卓手机与平板设备；
* **ARM64 (v8a)**: Suitable for the vast majority of mainstream 64-bit Android phones and tablets;

* **ARMeabi-v7a**：适用于较老款的 32 位安卓设备或部分电视盒子；
* **ARMeabi-v7a**: Suitable for older 32-bit Android devices or some TV boxes;

* **x86_64 / x86**：适用于主流 PC 电脑安卓模拟器。
* **x86_64 / x86**: Suitable for mainstream Android emulators running on PC.

---

## 💬 反馈与建议 / Issues & Feedback

遇到任何使用问题、连接异常或有新的功能建议，欢迎前往 **wanBoxForAndroid 自己的仓库议题页**提交反馈：

If you encounter any issues, connection errors, or have feature requests, please submit an issue to the **wanBoxForAndroid repository**:

👉 **[前往本仓库提交议题 / Submit an issue to this repository](https://github.com/lit008834-pixel/wanBoxForAndroid/issues)**
