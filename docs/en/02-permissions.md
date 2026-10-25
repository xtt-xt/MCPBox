# 02 · Permissions & approvals

[← Back to README](../../README.en.md) ｜ [中文](../02-权限与审批.md)

MCPBox's default philosophy: **what the AI may do is decided on the phone**. Enforcement happens server-side (permission matrix + rules + per-tool policy) and does not rely on the client being well-behaved — the client can be any MCP client.

## What the Permissions tab looks like

The bottom-bar **Permissions** tab is a security hub (entry page) grouped by the question being
answered, instead of one page with every control on it:

| Entry | What is inside |
|---|---|
| Preset (top dropdown) | Sets all 9 switches at once |
| Tool permissions | The 9 switches (grouped **files / system / network**, allow-by-default ones last in each group), tool packs and session isolation |
| File scope | Allowed folders, path rules, private-directory level |
| Commands | Command permission (jump), execution identity, command rules, command timeout |
| Approvals | Presentation (overlay / notification), whether it expires, timeout seconds (AI tools only) |
| Remote access | SSH service, access token |
| File channel | Web upload / download |
| Global limits (top card) | Read-only, unrestricted folders, delete to trash |

Approvals used to live in Settings → Security & approvals, command and path rules in
Settings → Terminal & commands, and SSH on its own page: adjusting "may the AI run commands" meant
visiting three pages. All three are now inside Permissions, and Settings only keeps items unrelated
to authorisation (port, browser behaviour, appearance…).

## The permission matrix: 9 keys × three states

Every permission key has three states: **allow** / **ask** / **deny**.

| Key | Default | Covers |
|---|---|---|
| `fs.read` | allow | Reading files, listing dirs, viewing images, searching, listing the trash |
| `fs.write` | ask | Writing, editing, creating, copying, moving, restoring from trash |
| `fs.delete` | ask | Deleting files/dirs, emptying the trash |
| `shell.exec` | ask | Commands run by the AI, custom tools (what you type in the terminal yourself does not count) |
| `tools.manage` | ask | Creating / editing / deleting custom tools, managing tool packs |
| `ui.control` | ask | Screen control: screenshot, view dump, tap, swipe, typing (needs Root / Shizuku) |
| `browser.control` | ask | Built-in browser: open pages, click, fill forms, run scripts, read/write cookies (**asks on every action** by default) |
| `system.info` | allow | Device info, storage info, server status, fetching the token |
| `memory` | allow | Memory read/write (turning the master switch off removes memory tools from `tools/list`) |

At the top of the app's **Permissions** page you can switch a **preset session** in one tap
(it stays on the home page; the switches themselves live under *Tool permissions*):

| Preset | Effect |
|---|---|
| Allow all | All 9 keys set to allow — the AI stops being interrupted |
| Deny all | All 9 keys set to deny — the AI can only read server status |
| Ask all | All 9 keys set to ask — every sensitive action pops up (safest) |
| Custom | Unlocked; tune the 9 switches one by one |

- Choosing one of the first three **locks** the switches below (whole rows become untappable), so you never end up with "says allow all but deletes ask".
- Before switching to a fixed preset the app **saves your current custom settings**; switching back to Custom restores them exactly.

## Per-tool policy (four states)

Beyond the global matrix, **each tool** can have its own policy (Settings → Tools):

| Value | Meaning |
|---|---|
| Follow (default) | Use the global matrix above |
| Allow | Everything this tool does is let through without approval |
| Ask | **Ignores the global matrix** — every call asks you |
| Deny | Always denied, whatever the global settings say |

- Tools can also be **disabled entirely** — a disabled tool disappears from `tools/list`.
- Only one tool ships with its own policy: `get_token` defaults to **ask**, because it hands out the access token.
- Choosing "always allow / always deny" in an "ask" dialog remembers **that tool only**; global switches stay untouched.

## Rules: finer than switches

Rules can override the switch verdict (both live under **Permissions**: path rules under *File scope*, command rules under *Commands*):

| Rule | Matching | Example |
|---|---|---|
| **Path rules** | Longest prefix wins | Allow writes under `/data/data/me.rerere.rikkahub/` without opening `fs.write` globally |
| **Command rules** | Prefix / exact / regex, **first match in order** wins | `pm list packages` prefix → allow; `rm -rf` regex → deny |

Whenever you hit "always allow / always deny" in an approval dialog, MCPBox **creates the matching rule automatically**, so the same kind of operation stops bothering you. The entry rows under Permissions show how many rules exist (e.g. "Path rules · 3").

## Approvals: overlay or notification

| Setting | Default | Notes |
|---|---|---|
| Approval mode | **Overlay** | A top-level overlay above every app, with four buttons: allow once / always allow / deny / always deny |
| Approval mode = Notification | — | One notification per request, action buttons right in the notification, plus "N more waiting" in the body |
| Local-only access / web console | — | Approvals can also be handled from the web console |

- Notification mode **never shows the overlay** (even with overlay permission); overlay mode **falls back to notifications** when it lacks the permission, otherwise nobody would see the request.
- A denied request returns a clear error saying the user can change the permission in the app — **do not retry in a loop after a denial**.

## Timeouts (both can be switched off)

| Setting | Default | Range | With "unlimited" |
|---|---|---|---|
| Approval timeout | **120 s** (auto-deny) | 15–600 s slider | The dialog never expires; the AI waits for your answer (value stored as 0) |
| Default command timeout | **60 s** | 10–300 s slider | Commands run until they finish on their own |

- The two "unlimited" switches both live under **Permissions**: approval under *Approvals*, commands under *Commands*.
- The number of seconds used before switching to unlimited is remembered and restored when you switch back.
- `run_shell` and custom tools also accept `timeoutMs = 0` meaning no limit.

## Private app directories

An app cannot read other apps' private dirs, so this feature **forwards through root / Shizuku**:

- Three modes: **off** (default) / **read-only** / **read-write**
- Covers `/data/data`, `/data/user/0`, `/data/user_de/0`, `/data/local/tmp`, `/data/app`, `/data/misc`, `/data/system`, `/data/adb`
- In read-only mode writes and deletes are refused outright; use a path rule to open up a single app instead
- Android 15+ mount-namespace isolation hides app data even from root; MCPBox probes `su -mm` / `su -M` to enter the global namespace

The Permissions page also has **allowed directories** — the white list of sandbox roots. Only paths below them are reachable by tools; you can add common folders (e.g. `/storage/emulated/0/xtt`).
