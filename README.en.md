# MCPBox

[中文](README.md) | **English**

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![Build & Sign](https://github.com/xtt-xt/MCPBox/actions/workflows/build.yml/badge.svg)](https://github.com/xtt-xt/MCPBox/actions/workflows/build.yml)
[![Latest release](https://img.shields.io/github/v/release/xtt-xt/MCPBox?label=release)](https://github.com/xtt-xt/MCPBox/releases/latest)

Turn an Android phone into an **MCP server**: AI clients read and write files on the phone, run commands, and drive a browser or the UI over MCP. No computer needed, no Termux needed.

**Every sensitive action needs one tap on the phone** — allow once / always allow / deny / always deny.

```
AI client ──HTTP(MCP)──▶ MCPBox on the phone ──▶ files / shell / browser
                              └──▶ approval (overlay / notification)
```

- **One APK**: the MCP server, the terminal and the browser all live in one process
- **Three execution identities**: app sandbox / Root / Shizuku — only elevated when you need it
- **Minimal exposure by default**: the AI starts with 22 tools (30% of the full set); open more on demand

[Download](#download) · [Quick start](#quick-start) · [Features](#features) · [Supported clients](#supported-clients) · [FAQ](#faq) · [Build](#build) · [Documentation](#documentation)

## Download

Grab `MCPBox-v1.4.0-151-release.apk` (about 3.9 MB) from [Releases](https://github.com/xtt-xt/MCPBox/releases/latest).

- Requires Android 8.0 (API 26) or newer
- Root is optional; only running commands and UI control need Root or Shizuku

## Quick start

1. **Start the server**: open the app and turn the service on from the home page — default port `8720`
2. **Grant permissions**: the home page's environment check walks you through it. With Root or Shizuku, file access, overlay and battery-optimization exemption are granted automatically when you open the app; the notification permission still needs one tap in the system dialog
3. **Copy the URL**: the home page's "Copy address" gives you a full URL with the token baked in
4. **Add the MCP server in your client**:

```
Transport: Streamable HTTP (recommended) or SSE
URL:       http://<phone LAN IP>:8720/mcp
Token:     already included in the URL above
```

5. **Open tool packs as needed**: Permissions → Tool packs. For shell or UI control, request Shizuku on the Terminal page (Root is detected automatically)

> Clients fetch the tool list only once, when connecting — **reconnect the client (or restart the app) after changing packs**.

## Features

| Feature | In one line | Details |
|---|---|---|
| **72 MCP tools** | Files, shell, UI, browser, memory, custom tools, token | [01](docs/en/01-tools.md) |
| **Tool packs** | The AI sees only 22 tools by default; open more on demand, or let the AI manage packs itself | [03](docs/en/03-packs-and-sessions.md) |
| **Permissions & approvals** | 9 permission keys with three states, path / command rules, four-state per-tool policy; approvals via overlay or notification | [02](docs/en/02-permissions.md) |
| **Built-in terminal** | App sandbox / Root / Shizuku identities; dangerous commands ask first | [01](docs/en/01-tools.md) |
| **Built-in browser** | The AI browses by itself: read text, click, fill forms, scroll, run JS, screenshot, search; per-page cookies and UA; no Root needed | [06](docs/en/06-browser-and-ui-automation.md) |
| **UI automation** | Screenshot, dump the view tree, tap / swipe / type Chinese / launch apps (needs Root or Shizuku) | [06](docs/en/06-browser-and-ui-automation.md) |
| **Memory store** | Long-term memory for the AI: entities + observations + relations, browsable and editable in the app | [04](docs/en/04-memory.md) |
| **File transfer & static hosting** | `POST /upload` / `GET /download`, whole directories as one zip, and `/web` to preview web artifacts straight from the phone | [05](docs/en/05-file-transfer-and-web.md) |
| **SSH / SFTP** | A built-in server so a client's SSH workspace can read and write phone files; switched separately from the MCP service | [11](docs/en/11-ssh.md) |
| **Backup · stats · localization** | One-tap backup of memory / settings / custom tools / stats / backdrop; request heatmap; Chinese and English | [07](docs/en/07-stats-backup-onboarding.md) |

## Supported clients

Any client that speaks **Streamable HTTP** (MCP 2025-06-18) or **SSE** works — RikkaHub, Claude Code, Cursor and Cline, for example. Clients that only speak stdio (such as Claude Desktop) can bridge through something like `mcp-remote`.

Three things to fill in: the URL ending in `/mcp`, the access token, and the transport.

## How it compares

| Approach | What it takes | Approvals | Limits |
|---|---|---|---|
| Running an MCP server yourself in Termux | A hand-built Python / Node environment | None — the script gets whatever permissions it has | Everything you reinstall is work again; nothing brings it back after a reboot |
| Running the server on a computer and syncing phone files | An always-on computer | On the computer | Breaks the moment the phone is offline; files have to be synced both ways |
| **MCPBox** | One APK | **Every sensitive action gets a tap on the phone** | Android only; commands and UI control still need Root / Shizuku |

## FAQ

**Do I need Root?**
No. File access, the memory store, the built-in browser and static hosting work out of the box; only running commands and UI control need Root or Shizuku.

**Does it upload my files anywhere?**
No. There is no account, no developer server and no telemetry; everything lives in the app's private directory, and only clients holding the access token can call tools.

**Does it keep running when the screen is off or the app is in the background?**
A foreground service plus a wake lock keep it alive while locked. If the system's battery saver kills it, it does not come back on its own — that was never a feature; add it to the battery-optimization allowlist. Swiping the app away is not guaranteed to keep it running either.

**A computer on the LAN can't connect.**
Check that Settings → Network & access → "Allow LAN access" is enabled, then make sure the router isn't using AP isolation or a guest network. The port can be changed on the same page (1024–65535).

**I changed the tool packs but the AI still doesn't see the new tools.**
Clients fetch the tool list once per connection — reconnect the client or restart the app.

**I lost the token.**
The home page's "Copy address" always shows it; you can also reset it under Permissions → Remote access, which invalidates the old one immediately — remember to update your client config.

**Does uninstalling lose my data?**
Yes. Settings, memory and stats live in the app's private directory and go away with the app; export a backup under Settings → App → Backup & restore first if you want to keep them.

## Build

Requires JDK 17. The repository ships a Gradle wrapper:

```bash
./gradlew :harness:e2e          # end-to-end tests
./gradlew :app:assembleRelease  # release APK
```

Signing, versioning and release steps: [08 Build and release](docs/en/08-build-and-release.md).

## Documentation

Full docs live in [`docs/en/`](docs/en/) (English) and [`docs/`](docs/) (Chinese).

| Doc | Contents |
|---|---|
| [01 Tools](docs/en/01-tools.md) | All 72 tools: pack, permission key, what it does |
| [02 Permissions & approvals](docs/en/02-permissions.md) | Permission matrix, preset sessions, per-tool policy, path / command rules, timeouts, approval modes |
| [03 Packs & sessions](docs/en/03-packs-and-sessions.md) | Why packs exist, their contents and defaults, AI pack control, `/mcp/p/<name>` session isolation |
| [04 Memory](docs/en/04-memory.md) | Entities / observations / relations, storage and UI, import & export, merge vs replace |
| [05 File transfer & web](docs/en/05-file-transfer-and-web.md) | Upload/download, upload page, zip directories, `/web` hosting, private app dirs, console security |
| [06 Browser & UI automation](docs/en/06-browser-and-ui-automation.md) | The 18 browser tools, per-page login state, floating window, known limits; the 8 UI automation tools |
| [07 Stats, backup, onboarding](docs/en/07-stats-backup-onboarding.md) | What stats count, backup & restore, first-run wizard, developer mode, localization |
| [08 Build & release](docs/en/08-build-and-release.md) | Three modules, repository layout, build & test, signing, GitHub Actions, release flow |
| [09 Terms of Use](docs/en/09-terms.md) | Acceptance, authorization, acceptable use, risk and liability |
| [10 Privacy Policy](docs/en/10-privacy.md) | Where data lives, permission purposes, network access, token and logs, backups |
| [11 SSH service](docs/en/11-ssh.md) | Built-in SSH / SFTP, auth and keys, command switch and identity, sandbox and read-only, real-device notes |

## Terms and license

Please read the [Terms of Use](docs/en/09-terms.md) and the [Privacy Policy](docs/en/10-privacy.md) (Chinese: [用户协议](docs/09-用户协议.md) / [隐私政策](docs/10-隐私政策.md)) before using the app. Both are also available in the app under About.

In one sentence: files, memory, statistics and tokens stay on your own phone. There is no account, no developer server and no telemetry.

Copyright (C) 2026 xtt · Released under the **GNU General Public License v3.0** — see [LICENSE](LICENSE). All dependencies use permissive licenses (Apache-2.0 / MIT: AndroidX, Jetpack Compose, Kotlin, kotlinx-coroutines, Shizuku) and are compatible with GPL-3.0.
