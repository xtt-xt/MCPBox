# 01 · Tools

[← Back to README](../../README.en.md) ｜ [中文](../01-工具清单.md)

MCPBox registers **74 MCP tools**. They are grouped into **tool packs** (see [03 Packs & sessions](03-packs-and-sessions.md)):
`tools/list` only returns the tools of the core pack plus the active packs, but `tools/call` is **not filtered by packs** — calling a tool by name is allowed (it still goes through the permission matrix).

The "Permission key" column is the switch a tool uses (see [02 Permissions](02-permissions.md)); that key's default decides whether an approval pops up.

## core · Basics (4, always on)

The pack-management tools (`list_packs` / `activate_pack` / `deactivate_pack` / `reset_packs` / `manage_pack`) also live in this pack but are **only visible when "let the AI manage packs" is on** — off by default.

| Tool | Permission key | What it does |
|---|---|---|
| `server_info` | `system.info` | Server status: version, port, transports, roots, permissions, packs, available operations |
| `get_device_info` | `system.info` | Phone model, OS version, battery, storage usage. **Per-tool default: ask** |
| `notify_user` | `system.info` | Post a notification on the phone to report completion or ask the user to do something |
| `get_token` | `system.info` | Read the access token, port and addresses. **Calling it needs no token**; per-tool default: ask |

## file.read · Reading files (8, on by default)

| Tool | Permission key | What it does |
|---|---|---|
| `list_dir` | `fs.read` | List a directory (name, type, size, mtime), sortable by name / size / time |
| `directory_tree` | `fs.read` | Indented tree of a folder, to see at a glance what is inside |
| `file_info` | `fs.read` | Details of one file or directory: size, mtime, permissions, child count |
| `read_file` | `fs.read` | Read a text file with line numbers (large files in chunks; images go through `read_image`) |
| `read_image` | `fs.read` | Load an image from the phone for the model to look at (png / jpg / gif / webp / bmp) |
| `search_files` | `fs.read` | Search by file name (glob or regex) or by file contents (regex) |
| `file_hash` | `fs.read` | md5 / sha1 / sha256, to compare two files |
| `storage_info` | `system.info` | Accessible roots plus total / free space per partition |

## memory · Memory store (7, on by default)

Turning off the "memory" master switch removes these tools from `tools/list` entirely.

Each memory is a Markdown file and the AI reads and writes whole notes. See [04 Memory](04-memory.md).

| Tool | Perm key | What it does |
|---|---|---|
| `memory_list` | `memory` | List every note (folder + name + one-line summary), optionally one folder |
| `memory_read` | `memory` | Read one note in full: summary, type, body, and both directions of links (with the other note's summary) |
| `memory_write` | `memory` | Write a whole note (creates if missing); summary / type / body / links optional, `oldName` renames |
| `memory_append` | `memory` | Append a paragraph to the body (newline-separated); creates the note if missing |
| `memory_search` | `memory` | Keyword search over name / summary / type / folder / body / links, hits include direct neighbours |
| `memory_delete` | `memory` | Delete notes (links pointing at them are removed too) |
| `memory_stats` | `memory` | Note count, link count, and the folder / type / link-type breakdown |

> Import / export is not a tool: use "Memory → ⋯" in the app to export / import a zip.

## file.write · Writing files (9, off by default)

| Tool | Permission key | What it does |
|---|---|---|
| `write_file` | `fs.write` | Create / overwrite / append a file (base64 works for binaries); parent dirs are created |
| `edit_file` | `fs.write` | Find and replace inside a file (literal or regex, replace-all supported) — safer than rewriting |
| `make_dir` | `fs.write` | Create a directory (parents included by default) |
| `copy_path` | `fs.write` | Copy a file or a whole directory |
| `move_path` | `fs.write` | Move a file / directory, also used to rename |
| `delete_path` | `fs.delete` | Delete a file or directory (recursive must be explicit); goes to the trash by default |
| `list_trash` | `fs.read` | List the trash, so AI mistakes can be undone |
| `restore_trash` | `fs.write` | Restore a trashed item to its original or a new location |
| `empty_trash` | `fs.delete` | Permanently empty the trash (irreversible) |

## shell · Commands & custom tools (8, off by default)

| Tool | Permission key | What it does |
|---|---|---|
| `run_shell` | `shell.exec` | Run a shell command on the phone and return the output, like typing in Termux. Dangerous commands ask for approval; `timeoutMs=0` means no limit |
| `shell_info` | `system.info` | Available backends (app sandbox / Root / Shizuku), current identity, env vars, timeouts |
| `create_custom_tool` | `tools.manage` | Define a new MCP tool: name, description, parameters, command template (`{{param}}` placeholders) |
| `update_custom_tool` | `tools.manage` | Edit an existing custom tool |
| `delete_custom_tool` | `tools.manage` | Delete a custom tool |
| `list_custom_tools` | `system.info` | List every custom tool with its definition (to edit or export it) |
| `export_custom_tools` | `fs.write` | Export all custom tools to one JSON file |
| `import_custom_tools` | `tools.manage` | Import custom tools from a JSON file (same name = update) |

## ui · UI automation (8, off by default, needs Root / Shizuku)

Screenshots, view dumps and injected taps are system privileges the app's own UID does not have, so this pack needs Root or Shizuku. Dumping the UI **falls back across all available privileged backends** (root first).

| Tool | Permission key | What it does |
|---|---|---|
| `ui_screenshot` | `ui.control` | Screenshot the screen and show it to the model (optionally saved to a file) |
| `ui_dump` | `ui.control` | Dump the UI: view tree / interactive elements with absolute page coordinates |
| `ui_tap` | `ui.control` | Tap a point on the screen |
| `ui_swipe` | `ui.control` | Swipe / scroll |
| `ui_input` | `ui.control` | Type text into a field (Chinese goes through "write clipboard → paste") |
| `ui_key` | `ui.control` | Press a system key (back / home / recents / enter…) |
| `ui_launch` | `ui.control` | Launch an app, or see which app is in the foreground |
| `ui_wait` | `ui.control` | Wait for an element / text to appear (or disappear) |

## browser · Built-in browser (18, off by default, no Root needed)

Pages run in the app's own WebView; the login state is isolated from your system browser and belongs to MCPBox only. URLs to the LAN and localhost are **blocked by default** so page scripts cannot reach the local MCP port. A floating ball appears whenever a page is open. Every action goes through an approval (`browser.control` defaults to **ask**).

| Tool | Permission key | What it does |
|---|---|---|
| `browser_open` | `browser.control` | Open a new page and load a URL; can set this page's identity (mobile / desktop) and cookies |
| `browser_navigate` | `browser.control` | Navigate an existing page (cheaper than opening new pages when following links) |
| `browser_history` | `browser.control` | Back / forward / reload |
| `browser_pages` | `browser.control` | List every open page and mark the current one |
| `browser_switch` | `browser.control` | Make a page the current one (the floating window follows it) |
| `browser_close` | `browser.control` | Close one page, or all of them |
| `browser_content` | `browser.control` | Read the page: text / Markdown / interactive elements (with coordinates) / links / HTML / meta |
| `browser_click` | `browser.control` | Click an element (by index / text / CSS selector / coordinates) |
| `browser_input` | `browser.control` | Fill inputs, textareas, selects and checkboxes (React / Vue controlled components included); can submit |
| `browser_scroll` | `browser.control` | Scroll the page (bottom / top / position / screens / to an element) |
| `browser_wait` | `browser.control` | Wait for a selector or text to appear / disappear — steadier than a fixed sleep |
| `browser_eval` | `browser.control` | Run JavaScript in the page context (escape hatch; uses the page's own login state) |
| `browser_screenshot` | `browser.control` | Render the page to an image for the model (works with the page in the background) |
| `browser_search` | `browser.control` | Search the web (Bing / Baidu / Google / DuckDuckGo / Zhihu / Weibo / Bilibili / GitHub / Wikipedia…) |
| `browser_save` | `fs.write` | Save the page to a file on the phone (markdown / text / html) |
| `browser_storage` | `browser.control` | Manage login state and identity **per page**: read / set cookies, clear one site's cookies, clear cache, get / set UA |
| `browser_engines` | `browser.control` | List / add / remove search engines (your own ones are saved in settings) |
| `browser_download` | `fs.write` | Download an http(s) link straight to the phone (image / PDF / zip / text), without the browser |

## location · Location (2, off by default)

Takes **one fix** only: no continuous tracking, nothing in the background, and no background
location permission needed. The `location` key defaults to **ask** — every fix needs a tap on the phone.

| Tool | Permission key | What it does |
|---|---|---|
| `get_location` | `location` | One fix: latitude/longitude, accuracy, altitude, speed, bearing, provider (GPS / network / passive), fix time, mock flag. Configurable timeout and GPS preference |
| `location_status` | `system.info` | Whether location works at all: permission granted, precise or not, GPS and network providers on/off, hardware present. **Reads no coordinates**, so it needs no location permission |

> Coordinates are raw WGS-84; maps in mainland China render them shifted. Turning coordinates into
> a street address needs a third-party service over the network — this tool does not do it.

**Whether a live fix is possible**: Android only delivers locations while the app has a visible UI
when the permission is "while using the app". So the app grabs one position each time it comes to
the foreground (at most once per 60 s), and the tool prefers that copy. If the app has been in the
background and the cached copy has gone stale, it falls back to the system's last known location
(Root / Shizuku required) and says so in the output, including how old it is.

## my.tools · My tools (dynamic, off by default)

Custom tools created in the app (or by the AI with `create_custom_tool`) land in this pack automatically. Its contents are dynamic: one entry per custom tool.

## Pack-management tools

| Tool | Permission key | What it does |
|---|---|---|
| `list_packs` / `activate_pack` / `deactivate_pack` / `reset_packs` | `system.info` | Inspect and switch pack visibility; **no approval** (they do not widen real capabilities — the permission matrix does the blocking) |
| `manage_pack` | `tools.manage` | Create / edit / delete custom packs |
