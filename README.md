<p align="center">
  <img src="assets/nextep-logo.png" alt="NeXtep icon" width="220">
</p>

<h2 align="center">NeXtep — A Tribute to and Continuation of OneStep</h2>

<p align="center">
  <a href="README.md">English</a> / <a href="README_CN.md">中文</a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Android-16%2B-3DDC84?style=flat&logo=android&logoColor=white" alt="Android 16+">
  <img src="https://img.shields.io/github/v/release/lujinxin/NeXtep?display_name=tag&sort=semver" alt="Latest release">
  <img src="https://img.shields.io/badge/status-stable-2EA44F?style=flat" alt="Status: stable">
  <img src="https://img.shields.io/badge/Root-Required-C62828?style=flat" alt="Root required">
  <img src="https://img.shields.io/badge/license-Apache--2.0-4C1?style=flat" alt="Apache License 2.0">
  <img src="https://img.shields.io/badge/LSPosed-Module-3F51B5?style=flat" alt="LSPosed module">
  <img src="https://img.shields.io/badge/Kotlin-Source-7F52FF?style=flat&logo=kotlin&logoColor=white" alt="Kotlin source">
</p>

<p align="center">
  <img src="assets/screenshots/workspace-overview.jpg" alt="NeXtep workspace overview" width="17%">&nbsp;
  <img src="assets/screenshots/quick-settings.jpg" alt="NeXtep Quick Settings workspace" width="17%">&nbsp;
  <img src="assets/screenshots/widgets-workspace.jpg" alt="NeXtep widgets workspace" width="17%">&nbsp;
  <img src="assets/screenshots/app-settings.jpg" alt="NeXtep app settings" width="17%">&nbsp;
  <img src="assets/screenshots/multitasking.jpg" alt="NeXtep multitasking workspace" width="17%">
</p>

# NeXtep

**NeXtep** is an **LSPosed module** that adds a **native side workspace** to ColorOS 16 while keeping the **stock launcher**. It presents **three live task slots**, lets the current app exchange places with a slot, and integrates workspace controls into SystemUI.

The project currently targets a **OnePlus PLK110 running Android 16 / ColorOS 16**. It relies on private Android and ColorOS behavior, so **other devices, ROM versions, and vendor launchers require adaptation**.

## Demo video

[Watch the NeXtep demo on Bilibili](https://www.bilibili.com/video/BV1mPYd6XEU8/)

## What's new in 1.0.0

- Establishes the first stable version, with improvements to task switching and system dialog layout.
- Keeps the workspace and its tasks through lock/unlock. Exiting and reopening the workspace restores eligible slot tasks; tasks opened fullscreen or removed from Recents are excluded.
- Keeps native ColorOS floating windows above workspace panels and excludes them from slot exchanges.
- Adds selectable top content: the round NeXtep icon, time with optional seconds, date and weekday, an empty area, or saved custom text.
- Adds a frosted wallpaper background with a 0–100 strength slider (default 50). Zero shows the unblurred wallpaper; higher values use GPU blur.
- Retains in-app update checks, release notes, and browser-based downloads.

## Architecture

The diagram below shows how the regular APK process coordinates with the LSPosed-injected SystemUI, Launcher, and system-server processes to provide the workspace and its three task slots.

<p align="center">
  <a href="assets/nextep-architecture.png">
    <img src="assets/nextep-architecture.png" alt="NeXtep architecture diagram" width="100%">
  </a>
</p>

## Features

- **Keeps the stock ColorOS launcher** instead of registering a replacement HOME app.
- **Opens the workspace quickly** from a Quick Settings tile or a top-right status-bar gesture.
- **Shows three live task slots** backed by lifecycle-bound virtual displays.
- **Exchanges tasks** between Display 0 and a selected slot with rollback-aware coordination.
- **Adapts the workspace for landscape video playback** while keeping the control strip and three task slots available, preserving sensor rotation, and restoring fullscreen tasks to system-managed bounds when landscape mode ends.
- **Supports flexible layouts and controls**, including left- and right-side layouts, media controls, wallpaper-backed panels, and configurable app shortcuts.
- **Remembers eligible slot tasks** across workspace sessions and preserves the workspace during lock/unlock.
- **Customizes top content and background**, with icon, clock, date, blank and text modes, plus adjustable wallpaper blur.
- **Coexists with native floating windows**, keeping them above workspace panels.
- **Applies compatibility hooks defensively** and fails open when a supported target cannot be resolved.

## Requirements

- **Android 16 / API 36** on the tested system (the APK minimum is API 35)
- **ColorOS 16** on a supported device build
- **KernelSU** or another compatible root solution
- **Zygisk and LSPosed** with modern libxposed API support
- The module enabled for the packages listed in `app/src/main/resources/META-INF/xposed/scope.list`

This module modifies **SystemUI, Launcher, and system-server behavior**. **Keep a working recovery path**, and disable the module if the device enters a boot loop or core UI becomes unstable.

## Build

See [docs/BUILDING.md](docs/BUILDING.md) for the required toolchain and build commands.

## Installation

1. Build or obtain the APK.
2. Install it on the target device.
3. Enable NeXtep in LSPosed for every package in the bundled scope list.
4. Reboot the device.
5. Open the NeXtep app to configure top content, frosted background strength, and app ordering, then add its Quick Settings tile.

## Settings

- **Top content:** select NeXtep Icon, time, date and weekday, blank, or custom text. Selections take effect immediately. The seconds switch appears beside the time option.
- **Custom text:** the input and Save text button appear only in text mode. Input is remembered when switching modes; press Save text to apply edits. Select NeXtep Icon to return to the default.
- **Frosted background:** adjust the slider from 0 to 100; release it to save and apply. The default is 50. Blur affects the control and slot backgrounds, leaving app content sharp.
- **App strip:** use automatic recent-use ordering or select and reorder shortcuts manually.

## Project status

The current source version is **1.0.0**, intended as the first stable version. Device support remains focused on **Android 16 / ColorOS 16 on OnePlus PLK110**. Updates are downloaded through the browser and are not installed automatically. No compatibility promise is made for untested releases or devices. Local recordings, extracted OEM packages, logs, and device dumps used during development are intentionally excluded from the repository.

## License and attribution

NeXtep is licensed under the [Apache License 2.0](LICENSE). See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for third-party attribution and design provenance.

## Acknowledgements

Special thanks to **Smartisan OS OneStep** for the product concept and interaction inspiration behind this project.

Copyright 2026 lujinxin.
