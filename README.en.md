# MCPBox

[中文](README.md) | **English**

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![Build & Sign](https://github.com/xtt-xt/MCPBox/actions/workflows/build.yml/badge.svg)](https://github.com/xtt-xt/MCPBox/actions/workflows/build.yml)

Turn an Android phone into an **MCP file server**: AI clients (RikkaHub / Claude Desktop / Cursor / Cline…) read and write files and run commands over the MCP protocol — while **every sensitive operation goes through an on-device floating approval popup**.

- Single APK, no external dependencies: the HTTP/MCP server, the built-in browser console and a persistent terminal all live in one process
- Three shell identities: **app sandbox** / **Root** / **Shizuku (ADB shell)**
- Permissions are not "allow everything once" but **8 switches × 3 states + path/command rules**, with the approval popup floating above every app

```
AI client ──HTTP(MCP)──▶ MCPBox on the phone ──▶ filesystem / shell
                              │
                              └─▶ floating approval (allow once / always allow / deny / always deny)
```

## Features

| | |
|---|---|
| **52 MCP tools** | File read/write/delete, search, image preview, trash, device info, shell, custom tools, token, memory, tool packs, UI automation… |
| **Tool packs** | Tools are split into 7 packs; only 22 ship by default (≈4100 tokens, 52% less than the full set). Packs are your own setting — the AI never notices |
| **Session isolation** | Point a client at `/mcp/p/<name>` for an independent session with its own activation state; persist, reset or delete it |
| **Presets** | One tap in the Permissions tab: all-allow / all-deny / all-ask / custom. Your custom set is remembered when you switch away and restored when you come back |
| **Per-call approval** | Top-level floating popup with a notification fallback: allow once / always allow / deny / always deny |
| **No timeouts (optional)** | Both the approval timeout and the command timeout can be turned off: the popup never auto-dismisses and commands run to completion |
| **Permission matrix** | 8 permission keys with three states (allow / ask / deny), plus path rules and command rules (prefix / exact / regex) |
| **Per-tool permissions** | Individual tools can be set to follow / allow / ask / deny; "ask" ignores the global matrix and prompts every time |
| **Memory** | Long-term memory for the AI: entities + observations + relations (a knowledge graph), browsable and editable in the app |
| **File gateway** | `POST /upload`, `GET /download`, plus an upload web page you can open in the phone's browser |
| **Private app dirs** | Read/write `/data/data/<package>` in three modes (off / read-only / read-write), forwarded through root |
| **UI automation** | Screenshot + read the UI tree (nodes & coordinates) + tap / swipe / type (incl. CJK) / key press / launch apps / wait for elements. Needs Shizuku or root |
| **Built-in terminal** | Persistent shell with `cd`/`export` state kept, command history, Ctrl-C and clear |
| **Custom tools** | Build your own MCP tools from command templates, with placeholders and JSON import/export |
| **Web console** | Try tools, read logs and handle approvals in a browser; supports **localhost-only** and **password protection** |
| **Two transports** | Streamable HTTP (`/mcp`) and legacy HTTP+SSE (`/sse` + `/messages`) |
| **Usage stats** | Daily request heatmap plus total run time, app launches and server starts; only ever grows, survives a settings reset, travels with backups |
| **Multilingual UI** | Chinese and English built in; export a template, translate it and import any language |

## Quick start

1. Install the APK, open the app and turn the server on from the Home tab (default port `8720`)
2. Grant file access, overlay and notification permissions (the "Environment check" section on Home walks through them)
3. Add an MCP server in your AI client:

```
Type:   Streamable HTTP (recommended) or SSE
URL:    http://<phone-LAN-IP>:8720/mcp
Token:  the one from "Connection addresses" on the Home tab (a full URL with the token)
```

4. For shell access, go to the Terminal tab and request Shizuku authorization (root is detected automatically)

## MCP tools

**Files (20)**: `list_dir` `directory_tree` `file_info` `read_file` `read_image` `search_files` `file_hash` `write_file` `edit_file` `make_dir` `copy_path` `move_path` `delete_path` `list_trash` `restore_trash` `empty_trash` `server_info` `get_device_info` `storage_info` `notify_user`

**Shell / extensions (8)**: `run_shell` `shell_info` `create_custom_tool` `update_custom_tool` `delete_custom_tool` `list_custom_tools` `export_custom_tools` `import_custom_tools`

**UI automation (8)**: `ui_screenshot` `ui_dump` `ui_tap` `ui_swipe` `ui_input` `ui_key` `ui_launch` `ui_wait`
(Requires root or Shizuku — screenshotting, reading the view tree and injecting input are all privileged.
`ui_input` types CJK by writing to the clipboard and simulating a paste.)

**Token (1)**: `get_token` (does not require the token itself — it exists to break the chicken-and-egg problem of an AI needing the token before it can transfer files)

**Tool packs (5)**: `list_packs` `activate_pack` `deactivate_pack` `reset_packs` (no approval; visibility only), `manage_pack` (create/update/delete custom packs; gated by the "custom tools" permission)

**Memory (10)**: `create_entities` `create_relations` `add_observations` `delete_entities` `delete_relations` `delete_observations` `read_graph` `search_nodes` `open_nodes` `memory_stats`

## Permission model

| Key | Default | Covers |
|---|---|---|
| `fs.read` | allow | Reading files, listing directories, viewing images |
| `fs.write` | ask | Writing, editing, creating, copying, moving |
| `fs.delete` | ask | Deleting (goes to the trash by default) |
| `shell.exec` | ask | Running commands (commands you type in the terminal yourself excluded) |
| `tools.manage` | ask | Creating / updating / deleting custom tools |
| `ui.control` | ask | Screen control: screenshot, read UI tree, tap, swipe, type (needs Root / Shizuku) |
| `system.info` | allow | Device info, storage info, fetching the token |
| `memory` | allow | Memory reads/writes (turn the master switch off and the memory tools disappear from `tools/list`) |

**Rules** override the switches with finer granularity:

- Path rule: `/data/data/me.rerere.rikkahub/` → allow writes (longest prefix wins)
- Command rule: prefix `pm list packages` → allow; regex `rm -rf` → deny (first match wins, in order)

Tapping "always allow / always deny" in an approval popup creates the matching rule automatically, so similar operations stop asking.

**Presets** (at the top of the Permissions tab) unify all 8 switches in one tap:

| Preset | Effect |
|---|---|
| Allow all | Every switch set to allow; the AI is never interrupted |
| Deny all | Every switch set to deny; the AI can only read server status |
| Ask all | Every switch set to ask; every sensitive action prompts (safest) |
| Custom | Unlocked — tune the 8 switches individually |

The first three **lock** the switches below (the rows become non-interactive) so the label can never disagree with the actual values.
Before switching to a fixed preset the app **snapshots your custom set**; switching back to Custom restores it exactly.

Individual tools can also be given their own permission under **Settings → AI tools → Tool manager**:

| Value | Meaning |
|---|---|
| Follow (default) | Uses the global permission matrix above |
| Allow | Everything this tool does is allowed without approval |
| Ask | **Ignores the global matrix** — this tool prompts on every call |
| Deny | Always denied, whatever the global settings say |

Choosing "always allow / always deny" in an "ask" popup stores that decision for **the tool itself** and never touches the global switches.

## Timeouts

Both timeouts can be turned **off**:

| Setting | Default | When unlimited |
|---|---|---|
| Approval timeout | 300 s | The popup never auto-dismisses and the AI waits for you as long as it takes (stored as 0) |
| Command timeout | 300 s | Commands run to completion and are never cut off |

`run_shell` and custom tools accept `timeoutMs = 0` for the same meaning.

## Memory

Long-term memory for the AI, structured as a classic knowledge-graph triple:

| Concept | Meaning |
|---|---|
| **Entity** | A node: project, tool, event, person, user preference… Names are unique |
| **Observations** | Short factual statements attached to an entity (de-duplicated on insert) |
| **Relations** | Directed edges between two entities: `from ──PART_OF──▸ to` |

Entity types, folders and relation predicates are **free text** — nothing is an enum. The AI naturally
grows structures like "project fact / user preference / event", and usually writes predicates in
upper snake case (`PART_OF`, `HAPPENS_AT`, `INVOLVES`, `CORRECTS`, `UPDATES`…).

- UI: **Settings → AI tools → Memory**. Search, filter by folder, and open an entity to rename it,
  change its type or folder, add/remove observations and relations; tapping a relation jumps to the
  other entity.
- Storage: `filesDir/memory/graph.json`, written atomically (`.tmp` then rename), so a power cut can
  lose at most the last write.
- `search_nodes` also returns the **direct neighbours** of matching entities, which makes it easy to
  follow a thread.
- Duplicate names are skipped rather than overwritten; renaming rewrites both ends of its relations;
  deleting an entity removes all of its relations.

## Statistics

Settings → **Statistics**. Everything here is an ever-growing, never-reset record:

| Metric | How it counts |
|---|---|
| **Request heatmap** | One count per JSON-RPC message POSTed to `/mcp` (including `initialize` / `ping` / notifications), grouped by the device's local calendar day |
| **Total run time** | Accumulated while the server is up; refreshed every second on the page |
| **App launches** | One per app open (once per process; rotation or switching back doesn't count) |
| **Server starts** | One per server start (restarts included) |
| **Days with requests** | How many days in the past year actually saw traffic |

- Storage: `filesDir/stats/stats.json`, written atomically; requests are flushed in 15-second batches,
  so a killed process loses at most those seconds.
- **Kept forever**: it lives outside `SharedPreferences`, so "reset all settings" does **not** clear it
  (and there is no clear button on purpose).
- Backup: the fourth backup part is "Statistics"; merging takes the **larger** value per day, so
  restoring the same file twice never doubles the numbers.

## Tool packs

Tool definitions are re-sent to the model on **every single turn**. All 52 tools are roughly 8600
tokens; twenty turns means 150k — and most turns don't need nearly that many.

So tools are split into packs, and `tools/list` only returns those in the **core pack plus the active
packs**:

| Pack | Tools | Ships |
|---|---|---|
| `core` | 4 | **always on** |
| `file.read` | 8 | on |
| `memory` | 10 | on |
| `file.write` | 9 | off |
| `shell` | 8 | off |
| `ui` | 8 | off |
| `my.tools` | dynamic | off |

22 tools by default ≈ 4100 tokens, **52% less than shipping everything**.

Packs are **your own long-lived setting**: tick them under "Permissions → Tool packs" and the AI simply
uses whatever it sees. It has no idea packs exist — zero friction, and no wasted turns. You can also
create your own packs in the app ("+ New tool pack") or let the AI call `manage_pack`.

> **⚠️ Reconnect for changes to take effect**
> Mainstream MCP clients **fetch the tool list once per connection** and never again (and the server
> has no way to tell them to refresh). After changing packs, reconnect the MCP server (or restart the
> app) so the AI sees the change.

### Optional: let the AI manage packs (off by default)

There is a switch under "Permissions → Tool packs". When it is on, the AI can see and call the five
pack-management tools (`list_packs` / `activate_pack` / `deactivate_pack` / `reset_packs` /
`manage_pack`), and the `initialize` instructions list the inactive packs.

**Why it is off by default**: mainstream clients fetch the tool list once per connection, and they
also **filter calls through their own cached tool table** — so once the AI activates a pack, it still
can't call the new tools this turn or the next, wasting several turns. Leaving it off saves those five
tools' worth of tokens and keeps the AI out of a trap.

### Session isolation: `/mcp/p/<name>`

The MCP protocol **cannot tell when an AI starts a new conversation** — the client calls `initialize`
once at startup and every conversation afterwards shares the same connection. So sessions are
separated by request path:

```
/mcp            → the default session
/mcp/p/coding   → a coding session (could activate file.write + shell for itself)
/mcp/p/writing  → a writing session (memory only)
```

Each state lives in `filesDir/profiles/<name>.json` and survives restarts. In the Permissions tab you
can select a session, reset it, or delete it (the bin icon on each row; `default` is the fallback
session and cannot be deleted).

**TTL fallback** (can be turned off, 30 minutes by default): after that long without a request the
session falls back to the default packs, so "start a new conversation later" also gets a clean slate.
Settings → AI tools → "Auto-reset session state".

## File transfer channel

No root and no USB cable:

```bash
# Push a file to the phone (the raw body is the file content)
curl -X POST --data-binary @app.apk \
  "http://<phone-ip>:8720/upload?path=/storage/emulated/0/xtt/app/mcp/app.apk&token=<token>"

# Pull a file off the phone
curl -o back.apk \
  "http://<phone-ip>:8720/download?path=/storage/emulated/0/xtt/app/mcp/app.apk&token=<token>"
```

Uploads go through the write permission and downloads through the read permission, so both still
prompt. Opening `http://127.0.0.1:8720/upload` in the phone's browser gives you a small upload page
(pick a file, type a path).

## Private app directories

Apps cannot read other apps' private directories themselves, so this is **forwarded through
root / Shizuku** when enabled:

- Three modes: **off** (default) / **read-only** / **read-write**
- Covered: `/data/data`, `/data/user/0`, `/data/user_de/0`, `/data/local/tmp`, `/data/app`, `/data/misc`, `/data/system`, `/data/adb`
- To expose just one app, use a path rule; in read-only mode writes and deletes are rejected outright
- Android 15+ mount-namespace isolation hides app data even from root; the project probes `su -mm` / `su -M` to enter the global namespace automatically

## Web console security

| Switch | Effect |
|---|---|
| Localhost only | Only answers `127.0.0.1` / `::1`; LAN devices get a 403 |
| Password protection | The browser must log in (12-hour cookie session); token-based calls are unaffected |
| Access password | Set separately; leave it blank to use the access token as the password |

## Architecture

```
mcpcore/   Pure Kotlin/JVM, zero Android dependencies: HTTP server, MCP protocol, tool
           implementations, permission matrix, file bridge, file gateway (runnable and testable on its own)
harness/   End-to-end tests: starts a real HTTP server and drives every tool and security policy
app/       Android layer: Compose UI, foreground service, floating approvals, Shizuku, terminal
```

## Building

```bash
# End-to-end tests (434 assertions)
./gradlew :harness:e2e

# Build
./gradlew :app:assembleDebug        # debug
./gradlew :app:assembleRelease      # release (unsigned when no keystore is present)
```

Requirements: JDK 17 and the Android SDK (`compileSdk 35`). Gradle comes from the bundled wrapper (8.13).

## Signing and releasing

Signing material is **never committed** (`keystore/` is in `.gitignore`). The build reads it from
environment variables:

| Variable | Meaning |
|---|---|
| `KEYSTORE_PATH` | Path to the keystore, defaults to `keystore/release.keystore` |
| `KEYSTORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | Key alias |
| `KEY_PASSWORD` | Key password |

**You can build without a keystore** — the release APK simply won't be signed.

### GitHub Actions

The repo ships [`.github/workflows/build.yml`](.github/workflows/build.yml): pushes to `main`, pull
requests and `v*` tags run the tests and the build, with artifacts under Actions. Tags additionally
create a Release with the APK attached.

To make CI produce a **signed** APK, add four secrets under
**Settings → Secrets and variables → Actions**:

| Secret | How to fill it |
|---|---|
| `KEYSTORE_BASE64` | `base64 -w0 keystore/release.keystore` (run locally, paste the whole output) |
| `KEYSTORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | Key alias |
| `KEY_PASSWORD` | Key password |

Publishing a version:

```bash
git tag v1.1.0 && git push origin v1.1.0
```

## Repository layout

```
app/                          Android app (Compose UI + service + floating overlay)
  src/main/java/com/xtt/mcpbox/
    ui/                       Screens: home / terminal / permissions / logs / settings / tools / memory
    server/                   Foreground service, approval receiver, boot autostart
    AndroidHost.kt            Device info, notifications and other system capabilities
    ShizukuShell.kt           Shizuku process launcher
mcpcore/src/main/kotlin/com/xtt/mcpbox/core/
  HttpServer.kt               Tiny HTTP/1.1 (keep-alive, chunked, SSE)
  McpServer.kt                Routing, auth, MCP protocol, web console security
  McpTools.kt / ToolsRead.kt / ToolsWrite.kt / ToolsShell.kt
  Approval.kt                 Permission matrix and approval centre
  PathSandbox.kt / TrashManager.kt / FileBridge.kt / FileGateway.kt
  Shell.kt                    Shell backends (app / root / Shizuku)
  WebConsole.kt / Config.kt / CustomTools.kt / EventLog.kt
  Memory.kt                   Memory: entities + observations + relations, atomic graph.json
  ToolPack.kt                 Tool packs: built-in definitions and custom pack storage
  ToolsUi.kt                  UI automation: screenshot / view tree / tap / swipe / input
  ProfileStore.kt             Sessions (URL profiles): activation state + TTL, atomic writes
  ToolsPacks.kt / ToolsMemory.kt / ToolsToken.kt / ToolMeta.kt / ToolPolicy.kt / LocalNet.kt
harness/                      End-to-end tests
```

## License

Copyright (C) 2026 xtt

Released under the **GNU General Public License v3.0** (GPL-3.0); see [LICENSE](LICENSE) for the full text.

> All dependencies use permissive licences (Apache-2.0 / MIT): AndroidX, Jetpack Compose, Kotlin,
> kotlinx-coroutines and Shizuku. They are compatible with GPL-3.0.
