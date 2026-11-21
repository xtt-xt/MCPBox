# 04 · Memory store

[← Back to README](../../README.en.md) ｜ [中文](../04-记忆库.md)

Long-term memory for the AI. **Each memory is a Markdown file**, and the AI reads and writes whole notes.

```
files/memory/
  projects/MCPBox.md
  people/Xing Tian.md
  unsorted/Scratch.md
  _backup/graph-20261121-161500.json    ← backup of the legacy database (for migration)
```

- **Name** = file name, globally unique.
- **Folder** = parent directory; a note in the memory root shows up as "Unsorted".
- Renaming moves the file and updates links that point to the old name; deleting a note removes links to it elsewhere.

## File format

```markdown
---
title: MCPBox
description: MCP file server on a phone
type: project
created: 1763700000000
updated: 1763700000000
links:
  - to: Xing Tian
    type: belongs to
---

Body text, Markdown as usual.
```

| Field | Meaning |
|---|---|
| `title` | Note name, equal to the file name |
| `description` | One-line summary shown in lists, search results and links |
| `type` | Free text (project / person / preference…) |
| `links` | Outgoing links, each `to` + `type`; incoming links are not written here — they are derived by scanning |
| Body | Everything after the closing `---` |

Unknown keys in the front matter are ignored. Hand-editing is fine; the app rescans the directory.

## Tools (7)

| Tool | What it does |
|---|---|
| `memory_list` | List notes (folder + name + summary), optionally one folder |
| `memory_read` | Read one note: summary, type, body, plus links in both directions |
| `memory_write` | Write a whole note; `oldName` renames |
| `memory_append` | Append a paragraph to the body |
| `memory_search` | Keyword search; hits include direct neighbours |
| `memory_delete` | Delete notes |
| `memory_stats` | Note / link counts and folder / type breakdown |

The master switch lives in "Settings → AI & tools → Memory"; turning it off removes these 7 tools from `tools/list`.

## Editing in the app

Location: **Settings → AI & tools → Memory**.

- List page: search (name / summary / body), folder pills, tap a card to open it;
- Detail page: name, folder, summary, type, body and links are all editable; "Save" writes back to the file;
- Links can be added (pick a target and a type) and removed (only outgoing ones — incoming links belong to the other note);
- The "⋯" menu offers export, import and "export the last legacy database".

## Import / export

Both are manual, in the app — they do not use up tool slots.

| Action | Notes |
|---|---|
| Export all | One zip: `memory/memory.json` is a manifest, `memory/files/**` are the notes |
| Import from file | Accepts the zip above, and a legacy `graph.json` |
| Import mode | Merge (keep existing) / Replace (wipe first) |

## Migrating from the old format

The old store was `files/memory/graph.json` (entities + observations + relations).

- While `graph.json` still sits in the root, the memory page shows only an "Update to the new memory database" banner and every memory tool returns an error;
- Tapping the banner offers **Migrate** / **Save legacy memories** (to a location you pick) / **Later** (keeps memory disabled);
- When legacy data is detected a backup is written to `files/memory/_backup/graph-<time>.json` and the path is shown;
- Migration turns each entity into `<folder>/<name>.md`, joins observations line by line into the body, moves relations into `links` of the source note, and removes `graph.json` (the backup stays);
- The backup can be exported later from "⋯ → Export last legacy database".

## Included in backups

The memory part of "Backup & restore" is the whole directory: `memory/files/` holds the notes and
`memory/memory.json` is just the manifest. A legacy `graph.json` inside an old backup is converted to
Markdown on restore. See [07 Stats, backup, onboarding](07-stats-backup-onboarding.md).
