# 04 · Memory store

[← Back to README](../../README.en.md) ｜ [中文](../04-记忆库.md)

Long-term memory for the AI, stored as classic knowledge-graph triples:

| Concept | Meaning |
|---|---|
| **Entity** | A node: project, tool, event, person, user preference… **names are unique** |
| **Observation** | One fact per line, attached to an entity (duplicates are skipped) |
| **Relation** | A directed edge between two entities: `from ──PART_OF──▸ to` |

Entity types, folders and relation predicates are all **free text** — no enumeration. The AI naturally grows structures like "project fact / user preference / event", and predicates are usually UPPER_SNAKE_CASE (`PART_OF`, `HAPPENS_AT`, `INVOLVES`, `CORRECTS`, `UPDATES`…).

- **Behaviour**: entities with the same name are skipped, never overwritten; renaming syncs both ends of relations; deleting an entity deletes its relations too.
- **Search**: `search_nodes` results include the **direct neighbours** of each hit, so the AI can follow a thread.
- **Storage**: `filesDir/memory/graph.json`, written atomically (write `.tmp`, then rename) — a power loss costs at most the last write.
- **Tools**: 12 `memory_*` / entity / relation tools, see [01 Tools](01-tools.md#memory--memory-store-10-on-by-default).
- **Master switch**: turning off "Settings → AI & tools → Memory" removes the memory tools from `tools/list` entirely.

## Browsing and editing in the app

Location: **Settings → AI & tools → Memory**.

- Search and filter by folder;
- Open an entity to change its **name / type / folder** and add or remove observations and relations;
- Tapping a relation jumps to the entity on the other end;
- The "⋯" menu in the top right has **Export / Import**.

## Export / import

### In the app

| Action | Notes |
|---|---|
| Export | Uses the system file picker (CreateDocument) and writes one JSON file |
| Import · merge (default) | For entities with the same name only **blank** types/folders are filled in; observations are de-duplicated and appended, existing ones are never overwritten; duplicate relations are skipped |
| Import · replace | Wipes the whole store first, then writes the file's contents (with a second confirmation) |

On a merge import, "**uncategorized**" counts as blank: if the file carries a folder it is filled in, while a folder you chose explicitly stays untouched.

### Letting the AI do it

Two tools do the same thing:

- `memory_export`: write the whole store to a given JSON file.
- `memory_import`: import from JSON with `mode=merge` (default, safe) or `mode=replace` (wipe, then write).

> ⚠️ These two tools are **not part of any tool pack yet**, so they do not appear in `tools/list` — packs only expose the tools listed in them. For now call them via `tools/call`, or use the app. Once they are added to the `memory` pack they will show up normally.

## Included in backups

The memory store is the first part of "Backup & restore" and can be packed into a single zip together with settings, custom tools and stats — see [07 Stats, backup, onboarding](07-stats-backup-onboarding.md).
