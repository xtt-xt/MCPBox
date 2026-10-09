# 03 · Packs & sessions

[← Back to README](../../README.en.md) ｜ [中文](../03-工具包与会话.md)

## Why tool packs

Tool definitions are re-sent to the model on **every request**. With all 72 tools enabled, a `tools/list` body is roughly **34k characters** (at ~4 characters per token, about 8500 tokens); twenty turns means 170k characters — and most turns need only a handful of tools.

So tools are split into packs, and `tools/list` returns only the **core pack plus the active packs**:

| Pack | Tools | Default |
|---|---|---|
| `core` Basics | 4 (+5 pack-management tools, hidden by default) | **always on** |
| `file.read` Reading files | 8 | on |
| `memory` Memory store | 10 | on |
| `file.write` Writing files | 9 | off |
| `shell` Commands & custom tools | 8 | off |
| `ui` UI automation | 8 | off |
| `browser` Built-in browser | 18 | off |
| `my.tools` My tools | dynamic (follows custom tools) | off |

**Out of the box the AI sees 22 tools** (measured: that configuration produces a ~9.6k character `tools/list` body) — about 30% of the full set, i.e. **roughly 70% fewer tokens** than sending everything.

Packs are **your long-term setting**: tick them under **Permissions → Tool packs** and the AI simply sees whatever is ticked, unaware that packs exist. Packs only affect the **visibility** of `tools/list`, not `tools/call` — calling any existing tool by name is still allowed (it goes through the permission matrix as usual). That way, even if a client ignores `notifications/tools/list_changed`, you never hit the "activated but uncallable" deadlock.

> **⚠️ Reconnect after changing packs**
> Mainstream MCP clients **fetch the tool list once, when connecting**, and never again (the server cannot push a refresh). After editing packs, reconnect the MCP server (or restart the app) so the AI sees the change.

## Custom packs

- In the app: **Permissions → Tool packs → ＋ New pack** opens a full-page editor (search box + tick list + save at the bottom).
- By the AI: with "let the AI manage packs" enabled, the AI can create / edit / delete custom packs with `manage_pack`.
- Pack names are shown to the AI, so the description should say **when to activate this pack**.
- Built-in packs cannot be renamed or deleted; `core` is always on and cannot be disabled.

> Known gap: existing packs currently have **no edit entry** — tapping a pack row toggles it on / off, and the only other control is delete. To change a pack's contents today you must delete and recreate it (or let the AI use `manage_pack`).

## Optional: let the AI manage packs (off by default)

There is a switch under **Permissions → Tool packs**. When on, the AI can see and call five pack-management tools (`list_packs` / `activate_pack` / `deactivate_pack` / `reset_packs` / `manage_pack`), and the `initialize` instructions list the inactive packs with their purposes.

**Why it is off by default**: mainstream clients fetch the tool list once and then **block calls according to their cached table** — so if the AI activates a pack, it still cannot call the new tools this turn or the next, wasting several turns. Leaving it off saves those five tools' tokens and keeps the AI out of a trap.

## Session isolation: `/mcp/p/<name>`

The MCP protocol has **no notion of "the AI started a new conversation"** — a client calls `initialize` once at startup and every conversation shares that one connection. So requests are separated by path:

```
/mcp           → default session
/mcp/p/coding  → coding (may activate file.write + shell for itself)
/mcp/p/writing → writing (memory only)
```

- Each session remembers its own active packs, independently of the others.
- State lives in `filesDir/profiles/<name>.json` and survives app restarts.
- The Permissions page lets you pick a session, reset it, or delete it (trash icon on each row; `default` is the fallback session and cannot be deleted).

**TTL fallback** (on by default, 30 minutes): a session with no requests for that long falls back to the default pack set, so "start a new chat after a while" also gets a clean state. Location: Settings → AI & tools → "Auto-reset session state".
