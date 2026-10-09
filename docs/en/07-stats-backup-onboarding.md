# 07 · Stats, backup, onboarding

[← Back to README](../../README.en.md) ｜ [中文](../07-统计备份与引导.md)

## Usage stats

Location: **Settings → App → Stats**. Everything here only ever grows:

| Metric | What counts |
|---|---|
| **Request heatmap** | Every JSON-RPC message on `POST /mcp` (including `initialize` / `ping` / notifications), grouped by the device's local calendar day |
| **Total uptime** | Accumulates while the server runs; the page refreshes it every second |
| **App launches** | One per app launch (one per process — rotating the screen or returning to the task does not count) |
| **Server starts** | One per server start, restarts included |
| **Days with requests** | How many days in the year saw at least one request |

- Storage: `filesDir/stats/stats.json`, written atomically; requests are flushed every **15 seconds**, so a process kill loses at most a few seconds.
- **Kept forever**: it does not live in `SharedPreferences`, and "reset all settings" **does not clear it** (there is no clear entry either).
- Stats are the fourth backup part; on a merge restore it takes the **larger value per day** (restoring twice does not double anything).

## Backup & restore

Location: **Settings → App → Backup & restore**. Four parts:

| Part | Contents |
|---|---|
| Memory | Entities / observations / relations |
| Settings | All setting keys (optionally **including the access token**) |
| Custom tools | Tool definitions |
| Stats | Requests / uptime / launches |

- **Export**: either one **zip** (`manifest.json` + `memory.json` + `settings.json` + `custom-tools.json` + `stats.json`), or per-part JSON. The backup page uses **switches** to pick parts, and the token is a sub-row of "Settings" (it collapses when settings are off). You pick a destination folder; file names look like `memory-20261001-0030.json`.
- **Restore takes two steps**:
  1. Pick the parts (zips / multi-file selections go through this step; a single file jumps to step 2);
  2. Choose **merge / replace** per part (one pill toggles it).
- **Settings are always replaced wholesale** (no mode question); memory and custom tools only ask when the current data is non-empty.
- **Token policy**: a backup may or may not embed the token; when it does, restore shows a "restore the access token too" switch, **off by default** — otherwise restoring a backup would kick every MCP client offline (their URLs carry the old token).
- **Sniffing rules**: single files are identified by their `_type` field (`mcpbox.settings` / `mcpbox.custom-tools` / stats); memory files have no `_type` and are recognized by their `entities` / `relations` keys — so **backups from older versions (1.1.0-55) still work**.

## First-run onboarding

A fresh install walks through five steps: **welcome → language → permissions → restore a backup → get started**.

- Upgrades are **not** interrupted (it only appears on a fresh install).
- To see it again: tap the icon on the About page **7 times** to unlock **developer mode**, which can force it to run again.

## Developer mode

Unlocked by tapping the About page icon 7 times. It lets you:

- force the onboarding wizard to run again;
- preview the "new version found" dialog (skips the version comparison, so local dev builds show it too);
- force an update check on next launch;
- toggle the "language menu", which reveals the hidden language in the language dropdown (off by default).

> "Check for updates" on the About page runs when you tap the row; a new version opens the "new version found" dialog, while "already latest" or a failure only shows a toast.
> Note: dev builds keep the release `versionName` (it only changes on release), so a manual check on a local build reports "already latest" — use developer mode's "preview update dialog" to see the dialog.

## Localization

- **Chinese / English** are built in; switching changes the whole UI plus the titles, descriptions and parameter docs of **every tool**.
- To translate into another language: export the language template, edit the entries, import it back.
- The language picker lives in **Settings → Appearance & language**.
