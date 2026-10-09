# MCPBox

[中文](README.md) | **English**

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![Build & Sign](https://github.com/xtt-xt/MCPBox/actions/workflows/build.yml/badge.svg)](https://github.com/xtt-xt/MCPBox/actions/workflows/build.yml)

Turn an Android phone into an **MCP file server**: AI clients (RikkaHub / Claude Desktop / Cursor / Cline…) read and write files on the phone, run commands, and drive a browser or the UI over MCP — and **every sensitive action goes through an approval on the phone**.

```
AI client ──HTTP(MCP)──▶ MCPBox on the phone ──▶ filesystem / shell / browser
                              │
                              └─▶ approval (overlay / notification): once / always / deny / always deny
```

- **One APK, no dependencies**: HTTP/MCP server, built-in browser and a persistent terminal all live in one process
- **Three shell identities**: app sandbox / Root / Shizuku (ADB shell)
- **Not "allow everything once"**: 9 permission keys with three states, plus path / command rules and a four-state per-tool policy

## Features

| Feature | In one line | Details |
|---|---|---|
| **72 MCP tools** | File read/write/search/delete, commands, UI automation, built-in browser, memory store, custom tools, token… | [01](docs/en/01-tools.md) |
| **Tool packs** | Out of the box the AI sees only 22 tools (30% of the full set); open more on demand, invisibly to the AI. Reconnect after changing | [03](docs/en/03-packs-and-sessions.md) |
| **Permissions & approvals** | 9 permission keys with three states, preset sessions, path / command rules, four-state per-tool policy; approvals via overlay or notification | [02](docs/en/02-permissions.md) |
| **Memory store** | Long-term memory for the AI: entities + observations + relations (a knowledge graph), browsable, editable and importable/exportable in the app | [04](docs/en/04-memory.md) |
| **File transfer** | `POST /upload` / `GET /download` plus an in-browser upload page (multi-select); zip lets you move a **whole directory in one request** | [05](docs/en/05-file-transfer-and-web.md) |
| **Static site hosting** | Open web artifacts straight from the phone at `http://127.0.0.1:8720/web/?token=…` — read-only, with directory listings | [05](docs/en/05-file-transfer-and-web.md) |
| **Built-in browser** | The AI browses by itself: read text and interactive elements, click / fill forms / scroll / run JS / screenshot / search, with **per-page** cookies and User-Agent. No Root needed | [06](docs/en/06-browser-and-ui-automation.md) |
| **UI automation** | Screenshot, dump the view tree, tap / swipe / type (incl. Chinese) / keys / launch apps / wait for elements (needs Root or Shizuku) | [06](docs/en/06-browser-and-ui-automation.md) |
| **Stats · Backup · Onboarding** | Request heatmap and uptime; one-tap backup/restore of memory, settings, custom tools and stats; five-step first-run wizard | [07](docs/en/07-stats-backup-onboarding.md) |
| **Localization** | Chinese / English built in; export a template, translate it into any language and import it back | [07](docs/en/07-stats-backup-onboarding.md) |

## Quick start

1. Install the APK, open the app and start the server on the home page (default port `8720`)
2. Grant file access / overlay / notification permissions — the home page's "environment check" walks you through it.
   With Root or Shizuku, file access, overlay and battery-optimization exemptions are **granted automatically** when you open the app; notification permission still needs one tap in the system dialog
3. Add the MCP server in your AI client:

```
Transport: Streamable HTTP (recommended) or SSE
URL:       http://<phone LAN IP>:8720/mcp
Token:     the full URL copied from the app home page's "Copy address" (token included)
```

4. Pick the tool packs you want under **Permissions → Tool packs** (22 tools by default);
   for shell or UI control, request Shizuku authorization on the Terminal page (Root is detected automatically)

> Mainstream MCP clients only fetch the tool list once, when connecting — **reconnect the client (or restart the app) after changing packs**.

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

## License

Copyright (C) 2026 xtt · Released under the **GNU General Public License v3.0** — see [LICENSE](LICENSE).

All dependencies use permissive licenses (Apache-2.0 / MIT: AndroidX, Jetpack Compose, Kotlin, kotlinx-coroutines, Shizuku) and are compatible with GPL-3.0.
