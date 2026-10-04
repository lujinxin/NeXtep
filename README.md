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

**NeXtep** is an **LSPosed module** that adds a **native side workspace** to ColorOS 16 / 17 while keeping the **stock launcher**. It provides **one main window and three live task slots**, supports task exchanges by tap or drag and adding apps from Recents, and integrates workspace controls into SystemUI.

The project primarily targets **OnePlus PLK110**, previously verified on **Android 16 / ColorOS 16**. Feature and compatibility testing for the latest version is based on **Android 17 / ColorOS 17**. It relies on private Android and ColorOS behavior, so **other devices, ROM versions, and vendor launchers require adaptation**.

## Demo video

[Watch the NeXtep demo on Bilibili](https://www.bilibili.com/video/BV1iKHj6fEtL)

## What's new in 1.1.0

- **Drag and exchange slot tasks:** long-press a slot and drop it onto another slot or the main window. Supports moving into empty slots, exchanging occupied slots, target highlighting, and placement animations in portrait and landscape.
- **Move a slot app to the background:** drop it onto the control area when the “松手移到后台” (Release to move to background) hint appears. The task remains in the background and the current main window stays in place. App-icon and slot drags no longer trigger the ColorOS drag-and-share panel.
- **Add apps consecutively from Recents:** center a task card and tap the plus in an empty slot. Recents stays open after adding an app, including when the third slot is filled.
- **Open the playback app from its media card:** tap the artwork, title, or another area outside the transport buttons. If the app occupies a slot, it exchanges with the main window. Previous, play/pause, and next retain their media-control actions.
- **Customize scrolling text and typography:** enable looping for both short and long text; disable it for centered text with ellipsis on overflow. Search, preview, and select fonts installed on the device, with 10–28 sp sizes and bold styling.
- **Updated settings interface:** separate Settings and About tabs, consistent groups and colors, and light/dark themes. Update checks, releases, source links, and license information are available in About.
- **ColorOS 17 wallpaper and assistant compatibility:** fixes workspace backgrounds that differ from the current live wallpaper, plus duplicated scaling and misplaced input on the assistant panel's profile and settings pages.
- **Return landscape video to a slot:** improves exiting fullscreen during slot placement. The verified Bilibili scenarios restore the complete portrait page while preserving playing or paused state.
- **Brightness mirror positioning:** fixes the floating brightness slider offset during a held drag in workspace mode. Verified with both sidebar positions, the long-press brightness panel, and fullscreen mode after exiting the workspace.

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
- **Exchanges tasks by tap or drag** between the main window and a slot, or moves and exchanges apps between slots.
- **Adds apps consecutively from Recents**, placing the centered task card in an empty slot while keeping Recents open.
- **Moves slot apps to the background** by dropping them onto the control area, freeing the slot without removing the task.
- **Adapts the workspace for landscape video playback** while keeping the control strip and three task slots available, preserving sensor rotation, and restoring fullscreen tasks to system-managed bounds when landscape mode ends.
- **Supports flexible layouts and controls**, including left- and right-side layouts, media controls, opening the playback app from its media card, wallpaper-backed panels, and configurable app shortcuts.
- **Remembers eligible slot tasks** across workspace sessions and preserves the workspace during lock/unlock.
- **Customizes top content and background**, with icon, clock, date, blank and text modes, looping text, system fonts, text size, bold styling, and adjustable wallpaper blur.
- **Coexists with native floating windows**, keeping them above workspace panels.
- **Applies compatibility hooks defensively** and fails open when a supported target cannot be resolved.

## Requirements

- **OnePlus PLK110**: previously verified on **Android 16 / API 36, ColorOS 16**; this round uses **Android 17 / API 37, ColorOS 17** (the APK minimum is API 35)
- A matching **ColorOS 16 / 17** build on an adapted device
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
5. Use the Settings tab to configure top content, text styling, frosted background strength, and app ordering, then add its Quick Settings tile. The About tab provides version information and update checks.

## Usage

After enabling the module and rebooting, unlock the device. You can open or close the workspace in either of two ways:

- **Gesture:** swipe horizontally to the left from the top-right status-bar area. Swiping down opens the system notification shade or Control Center as usual.
- **Control Center toggle:** open the NeXtep app, tap **添加 NeXtep 到控制中心** (Add NeXtep to Control Center), and confirm. Then pull down Control Center and tap the **NeXtep** tile to toggle the workspace. If the tile cannot be added from the app, add it from Control Center's edit screen.

With the workspace open, manage tasks as follows:

- **Tap a slot:** exchange its app with the current main window. When a regular app occupies the main window, tapping an empty slot's plus moves that app into the slot.
- **Add from the app strip:** long-press an app icon, drag it onto a slot, and release.
- **Drag between windows:** long-press an occupied slot. Drop it onto an empty slot to move, an occupied slot to exchange, or the main window to exchange with the main app.
- **Move to the background:** drag a slot to the control area and release when “松手移到后台” (Release to move to background) appears. The app task is retained and the current main window stays unchanged.
- **Add from Recents:** open system Recents, center the target card, then tap an empty slot's plus. Add apps consecutively while Recents stays open; exit split screen before adding a grouped split-screen card.
- **Open the playback app:** tap the media card's artwork or title. An app in a slot exchanges with the main window; otherwise it opens in the main window. The three transport buttons continue to control playback.

## Settings

- **Settings / About:** configure workspace appearance and app ordering in Settings; view the version, check for updates, and open releases or source code in About.
- **Top content:** select NeXtep Icon, time, date and weekday, blank, or custom text. Selections take effect immediately. The seconds switch appears beside the time option.
- **Custom text:** the input and Save text button appear only in text mode. Input is remembered when switching modes; press Save text to apply edits. Select NeXtep Icon to return to the default.
- **Looping text:** off by default. When enabled, both short and long text scroll continuously; when disabled, text is centered and overflow is ellipsized. Scrolling pauses while the workspace is hidden.
- **Font and size:** search, preview, and select system fonts in the custom-text settings, or use a generic font family. System default follows the device's font setting. Sizes range from 10 to 28 sp (default 16 sp), with bold styling and an input preview.
- **Frosted background:** adjust the slider from 0 to 100; release it to save and apply. The default is 50. Blur affects the control and slot backgrounds, leaving app content sharp.
- **App strip:** use automatic recent-use ordering or select and reorder shortcuts manually.

## Project status

The current source version is **1.1.0** (`versionCode = 6`). This round verified core drag, Recents, text, and settings interactions on **OnePlus PLK110 running Android 17 / ColorOS 17**, along with wallpaper, assistant, video-to-slot, and brightness-slider fixes. Not every app or edge case has been covered. Previous Android 16 / ColorOS 16 support records remain available; other devices and ROMs require separate adaptation.

Updates are downloaded through the browser and are not installed automatically. No compatibility promise is made for untested releases or devices. Local recordings, extracted OEM packages, logs, and device dumps used during development are intentionally excluded from the repository.

## License and attribution

NeXtep is licensed under the [Apache License 2.0](LICENSE). See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for third-party attribution and design provenance.

## Acknowledgements

Special thanks to **Smartisan OS OneStep** for the product concept and interaction inspiration behind this project.

Copyright 2026 lujinxin.
