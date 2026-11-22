# 12 · Plugin system (design draft)

> Status: **draft, not implemented**. This page defines the plugin package format, runtime,
> permissions and UI. If the implementation has to deviate, update this page.

A plugin is a directory with a manifest and some scripts. Once installed it can do two things:

- **Tool plugins** give the model MCP tools; every call goes through approval.
- **Service plugins** expose HTTP routes on the MCPBox port so other programs can connect directly.

---

## 1 Goals and non-goals

### 1.1 Two scenarios that must work first

**A free-model gateway on the phone.** Port the anonymous free channels of the dsh plugin
`dsh-our-free-model` (OpenCode Zen, the Kilo public gateway) to the phone and expose them as an
OpenAI-compatible endpoint from a MCPBox plugin. Once enabled, RikkaHub or plain curl can point at
the local address with a plugin-generated key. Phase one covers anonymous channels only; channels
that need credentials or GitHub verification come later.

**A file bridge between the desktop and the phone.** The phone-side plugin exposes a file API; a
separate dsh plugin on the desktop (Node, its own repository) connects to it and renders a sidebar
panel: browse phone folders, quote files into the conversation, edit and save back, and upload files
from the desktop to the phone. Traffic goes over the LAN or Tailscale, authenticated with the
plugin's own key.

These two decide that plugins cannot stay "tools for the model" only; they must be able to serve
outside programs (section 3).

### 1.2 Capabilities

- One plugin can ship several tools, each with its own parameters
- Plugins own their settings; tools read them at run time
- Plugins can register HTTP routes with their own key and streaming responses
- Plugins can have pages (declarative or HTML)
- Plugins can inject rows and switches into designated settings pages
- Plugins can declare the MCPBox version and plugin API version they require
- Plugins can run in the background: timers, startup hooks, file-change watchers
- One tool pack per plugin, off by default, so it costs no tokens until activated
- Compatible with the existing custom tools; `custom_tools.json` migrates cleanly

### 1.3 Non-goals

- Plugins cannot change the app's own UI or navigation
- No remote code execution without installation
- Tool calls never bypass approval; no plugin bypasses `PathSandbox`
- P1 keeps plugins in the app process; isolation is exception capture plus timeouts
- No WebSocket upgrade; push goes over SSE

---

## 2 Why not just extend custom tools

Custom tools already look like single-tool plugins: name, title, description, parameter list,
permission key, command template, import/export, `my.tools` pack. What is missing:

| Gap | How plugins fill it |
|---|---|
| One command per tool | `plugin.json` declares many tools, one script file each |
| No own configuration | `settings` declaration + `config.json` + `ctx.settings` |
| No lifecycle | A directory is the unit: install, disable, upgrade, uninstall |
| Shell only | `kind` becomes `shell` / `js` / `python` |
| No UI | Declarative blocks + optional HTML page |
| Cannot run in background | Scheduler hosted by the foreground service |
| Cannot serve outside programs | `http.routes` plugged into the existing `HttpServer` |

So custom tools are not replaced. They become `kind: "shell"` tools inside a built-in plugin
`my.tools`; `create_custom_tool` and friends keep working.

---

## 3 Two kinds of plugins

| | Tool plugin | Service plugin |
|---|---|---|
| Consumer | An MCP client calling tools | An outside program over HTTP |
| Granularity | One tool call | One endpoint |
| Authorization | `ApprovalCenter` every time | **Install-time grant + plugin key** |
| Frequency | Low, prompts are fine | High (once per token), prompts infeasible |
| Examples | Weather lookup, file cleanup | Model gateway, file bridge |

Service plugins skip per-request approval because the requirement makes it impossible to do
otherwise: a gateway sees one request per token, and a prompt per request means it does not work.
This is not a relaxation of security; the gate moves — capabilities are shown at install time, and
a separate key guards the routes afterwards. The built-in SSH service already works this way: the
only gate is the "allow command execution" switch, not a prompt per command.

A plugin can be both: a file bridge offers AI-callable tools and an HTTP API for the desktop.

---

## 4 Layout

Installed under the app-private `files/plugins/<id>/`:

```
files/plugins/<id>/
  plugin.json          required, manifest
  tools/               optional, tool scripts
    <tool>.js
  api/                 optional, HTTP route handlers
    <handler>.js
  web/                 optional, plugin pages and assets
    index.html
  data/                writable at run time
  logs/                ring buffer, 500 entries max
```

- A plugin id never changes; changing it means a different plugin.
- Anything outside the plugin directory is reachable only through `ctx.fs`, still bound by
  `PathSandbox` and the permission matrix.
- `data/` is the only directory a plugin may write by default.

---

## 5 plugin.json

### 5.1 Top-level fields

| Field | Type | Required | Notes |
|---|---|---|---|
| `id` | string | yes | `[a-z][a-z0-9_]{1,31}`, globally unique |
| `name` | string | yes | Display name, up to 40 chars |
| `version` | string | yes | `1.0.0`-style, used for upgrades |
| `author` | string | no | |
| `description` | string | yes | One line for the list, up to 200 chars |
| `homepage` | string | no | |
| `permissions` | string[] | no | Declared capabilities, see section 12 |
| `settings` | object[] | no | See 5.2 |
| `tools` | object[] | no | See 5.3 |
| `http` | object | no | Routes and key, see 5.4 |
| `ui` | object | no | See section 10 |
| `background` | object | no | See section 11 |
| `requires` | object | no | Version requirements, see 5.5 |

### 5.2 settings

```json
{ "key": "api_base", "type": "string", "title": "API base",
  "description": "No trailing slash", "default": "https://api.example.com" }
```

| type | Control | Stored as |
|---|---|---|
| `string` | text field | string |
| `secret` | masked field with reveal | string, excluded from export |
| `integer` | number field | integer |
| `number` | number field | decimal |
| `boolean` | switch | boolean |
| `enum` | dropdown | string, must be one of `options` |

`key` matches `[a-z][a-z0-9_]*`. The app renders these as native controls; plugin authors write no
UI. Values live in `config.json` and are writable from the plugin page or `ctx.settings.set`.

### 5.3 tools

```json
{
  "name": "forecast",
  "title": "Weather forecast",
  "description": "Look up the forecast for a city. Tell the model when to use it.",
  "file": "tools/forecast.js",
  "timeoutMs": 20000,
  "params": [
    { "name": "city", "type": "string", "required": true, "description": "City name" },
    { "name": "days", "type": "integer", "default": "3", "description": "1-7" }
  ]
}
```

- `name` is unique within the plugin, `[a-z][a-z0-9_]{0,40}`.
- The registered name is `<pluginId>__<name>`, max 64 chars; a collision fails the install.
- Param types: `string` / `integer` / `number` / `boolean` / `enum` (with `options`).
- `timeoutMs` defaults to 30000, capped at 300000.
- Permissions are not fixed per tool; they follow the host API the script calls. The per-tool
  ALLOW / ASK / DENY override still applies.

### 5.4 http

```json
"http": {
  "enabledByDefault": false,
  "bind": "loopback",
  "routes": [
    { "path": "/v1/models",           "handler": "api.models", "methods": ["GET"],  "auth": "key" },
    { "path": "/v1/chat/completions", "handler": "api.chat",   "methods": ["POST"], "auth": "key", "stream": true }
  ],
  "key": { "label": "API Key", "prefix": "sk-" }
}
```

| Field | Meaning |
|---|---|
| `bind` | `loopback` (127.0.0.1 only, default) / `lan` (0.0.0.0, warned about at install) |
| `routes[].path` | Must start with `/`; prefix matched, longest wins |
| `routes[].auth` | `key` (plugin key) / `none` (only allowed on loopback) |
| `routes[].stream` | Whether the route may upgrade to a chunked stream |
| `key.prefix` | Prefix for generated keys, so users can recognise them (`sk-`) |

**Reserved prefixes.** Plugins may not occupy these; the install fails on a conflict:

```
/mcp  /sse  /messages  /api/*  /web  /upload  /download  /console
```

Any other root-level prefix is fine, including `/v1/*` — MCPBox does not use it, so clients get the
standard base URL `http://127.0.0.1:8720/v1`.

### 5.5 requires

```json
"requires": { "mcpbox": ">=1.7.0", "api": 1 }
```

| Field | Meaning |
|---|---|
| `mcpbox` | Required MCPBox version, compared semantically against versionName |
| `api` | Plugin API version the plugin is written against (integer) |

`mcpbox` accepts `>=1.7.0`, `^1.7.0` (same major), `~1.7.0` (same minor) and an exact `1.7.0`.
Omitting `requires` means no constraint, for compatibility with early plugins.

| When | If not satisfied |
|---|---|
| Install | Refused, stating "requires MCPBox 1.7.0 or newer, current 1.5.0" |
| After an app upgrade | The plugin becomes **incompatible**: disabled, routes closed, marked amber in the list with the requirement in the subtitle; **data is kept** |
| After an app downgrade | Same |
| Plugin `api` outside what the app supports | Same, subtitle says the plugin API version is incompatible |

Compare versionName (`1.5.0`), never versionCode — the latter is a monotonic build counter that is
not comparable across branches. The app side parses the release part out of `ServerMeta.fullVersion`.

### 5.6 Full example (tool plugin)

```json
{
  "id": "hello_weather",
  "name": "Weather helper",
  "version": "1.0.0",
  "author": "example",
  "description": "Look up weather by city and keep recent lookups in its data directory.",
  "permissions": ["network", "fs.read", "fs.write"],
  "settings": [
    { "key": "api_base", "type": "string", "title": "API base", "default": "https://api.example.com" },
    { "key": "api_key", "type": "secret", "title": "API key" },
    { "key": "units", "type": "enum", "title": "Units", "options": ["metric", "imperial"], "default": "metric" }
  ],
  "tools": [
    {
      "name": "forecast",
      "title": "Weather forecast",
      "description": "Look up the forecast for a city.",
      "file": "tools/forecast.js",
      "params": [
        { "name": "city", "type": "string", "required": true, "description": "City name" },
        { "name": "days", "type": "integer", "default": "3" }
      ]
    }
  ],
  "ui": {
    "pages": [
      { "id": "main", "title": "Weather", "blocks": [
        { "type": "section", "title": "Setup", "text": "Fill in the API base and key above." }
      ] }
    ]
  },
  "background": { "interval": "30m", "events": ["startup"] }
}
```

### 5.7 Full example (service plugin)

```json
{
  "id": "free_gateway",
  "name": "Free model gateway",
  "version": "0.1.0",
  "description": "Turns the anonymous free model channels into a local OpenAI-compatible endpoint.",
  "permissions": ["network", "http.serve"],
  "settings": [
    { "key": "default_model", "type": "string", "title": "Default model", "default": "deepseek-v4-flash" },
    { "key": "probe_minutes", "type": "integer", "title": "Channel probe interval (minutes)", "default": "30" }
  ],
  "http": {
    "enabledByDefault": false,
    "bind": "loopback",
    "routes": [
      { "path": "/v1/models",           "handler": "api.models", "methods": ["GET"],  "auth": "key" },
      { "path": "/v1/chat/completions", "handler": "api.chat",   "methods": ["POST"], "auth": "key", "stream": true }
    ],
    "key": { "label": "API Key", "prefix": "sk-" }
  }
}
```

---

## 6 Runtime

### 6.1 Layers

```
mcpcore
  PluginManifest.kt   parse and validate plugin.json
  PluginStore.kt      scan / install / disable / uninstall / migrate
  PluginRuntime.kt    interface: eval(code, ctx) -> JSON
  PluginHost.kt       ctx implementation over tools / files / shell
  PluginRouter.kt     route table and dispatch, ahead of McpServer.dispatch
app
  JsPluginRuntime     quickjs-kt
  PyPluginRuntime     Chaquopy (later)
harness
  FakePluginRuntime   records calls, returns canned results
```

`mcpcore` keeps no Android dependency; engines are injected by the app. The harness drives
install → register → call → approve → uninstall with the fake runtime and tests manifest validation
and migration separately.

### 6.2 Script contract

Tool scripts default-export an object:

```js
export default {
  async run(ctx) {
    const base = ctx.settings.get("api_base")
    const res = await ctx.http.get(`${base}/v1/forecast?city=${encodeURIComponent(ctx.args.city)}`)
    if (res.status !== 200) return { error: `HTTP ${res.status}` }
    return (await res.json()).days.map(d => `${d.date}  ${d.low}~${d.high}`).join("\n")
  }
}
```

Route scripts export a handler:

```js
// api/chat.js
export default async function (ctx, req) {
  const body = await req.json()
  const upstream = await ctx.http.post("https://upstream.example/v1/chat/completions", {
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body)
  })
  if (body.stream) {
    for await (const chunk of upstream.sse()) ctx.sse.write(chunk)
    return ctx.sse.close()
  }
  return req.respond(200, "application/json", await upstream.text())
}
```

- Return a string for the tool result, `{ error }` for failure; routes use `req.respond` or `ctx.sse`.
- Thrown exceptions become error responses; the server survives.
- No cross-file `import` at first; `lib/` relative imports come later.

### 6.3 host API

| API | Notes | Permission |
|---|---|---|
| `ctx.args` | Parameters with defaults applied | — |
| `ctx.settings.get/set` | Plugin settings; writes are not approved | — |
| `ctx.fs.read/write/list/remove` | Paths go through `PathSandbox` | `fs.read` / `fs.write` / `fs.delete` |
| `ctx.shell.run(command, { cwd, timeoutMs })` | Reuses the three backends | `shell.exec` |
| `ctx.http.get/post/fetch` | http/https; returns `{ status, headers, text(), json(), sse() }` | `network` |
| `ctx.sse.write/comment/close` | Route scripts only; wraps `Exchange.openSse` | `http.serve` |
| `ctx.tools.call(name, args)` | Calls any existing tool through its own permissions | Target tool decides |
| `ctx.log(level, message)` | Plugin log | — |
| `ctx.plugin` | `{ id, version, dataDir }` | — |

`req` in route scripts:

| Member | Notes |
|---|---|
| `req.method` / `req.path` / `req.query` / `req.headers` | Request info |
| `req.bodyText()` / `req.json()` | Body, read fully via Content-Length or chunked |
| `req.remote` | Remote address, used for the loopback check |
| `req.respond(status, contentType, body)` | One-shot response |
| `ctx.sse` | Streaming, see 8.3 |

### 6.4 Limits

| Item | Value |
|---|---|
| Tool call timeout | Tool-declared, 30s default, 5min cap |
| Route request timeout | 120s (streams measured by idle time) |
| Concurrency | One tool task per plugin; route requests on a thread pool, queue of 8 |
| Memory | Engine default; quickjs-kt memory cap to be verified |
| Logs | 500-entry ring buffer per plugin |
| Outbound | http/https only; LAN addresses blocked by default |

---

## 7 Tool registration and packs

- Plugin tools are appended after `McpServer.builtinTools`, in the same place as custom tools
  (regenerated per request, so changes take effect immediately).
- Each plugin gets a pack:

```kotlin
ToolPack(
    id = "plugin.<id>",
    title = "Plugin · <name>",
    description = "<plugin description>",
    tools = allToolNamesOfPlugin,
    builtin = true,
    defaultActive = false
)
```

- Off by default, so it adds nothing to the default token budget.
- Disabling a plugin removes its tools from the registry, its pack from the list, and closes its routes.
- `tools/call` matches built-in behaviour: no pack filtering, the permission matrix is the gate.
- Plugins with routes but no tools get no pack; they only appear in the plugin list.

---

## 8 Service plugins: routes, keys and streaming

### 8.1 Dispatch order

`McpServer.dispatch` runs "local only → web password → built-in routes → plugin routes → 404".
Plugin routes come after every built-in route has declined:

```
1. Built-in routes (/mcp, /sse, /api/*, /web, /upload, /download, /console, /health)
2. Plugin routes (declared paths, longest match wins)
3. 404
```

- Requests that land on a plugin path **skip** `checkAuth` (the MCPBox token) and the web login
  redirect; they authenticate with the plugin's own key. Clients hold `sk-...`, not an access
  token, so the two credential types never mix.
- The global "local only" switch still applies — plugin routes do not bypass it.
- Path matching is either exact or a **trailing `*` prefix wildcard** (`/v1/*` covers everything
  under `/v1/`, but not `/v1` itself). Among several hits the longest `path` wins.

### 8.2 Plugin key

- Each service plugin gets its own key, created at install time, with the `http.key.prefix`
  (usually `sk-`).
- Stored as `__key` in `config.json`. The plugin page masks it until you tap "show"; "copy"
  always copies the plaintext.
- Accepted as `Authorization: Bearer <key>` or `X-API-Key: <key>`.
- Comparison is constant-time; a mismatch returns 401 with a one-line JSON error.
- Rotating the key invalidates the old one immediately and closes every stream that plugin had open.

### 8.3 Two switches for serving

| Field | Meaning |
|---|---|
| `__httpEnabled` | The "serve" toggle on the plugin page |
| `__httpGranted` | The user has **confirmed** this plugin may serve to others |

`InstalledPlugin.serving` is true only when the plugin is active, the toggle is on and the grant
exists. If any of the three is missing, `routes()` returns nothing and the path falls back to 404 —
a freshly installed plugin never opens a port by surprise.

`bind` decides whether non-local requests are accepted:

| Value | Binds | Notes |
|---|---|---|
| `loopback` | Only 127.0.0.1 | Default; only programs on the phone |
| `lan` | 0.0.0.0 | Reachable on the LAN; enabling warns that anyone on the network with the key can use it |

The check looks at the source address only, independent of the global listen address: with the
server bound to `0.0.0.0`, a `loopback` plugin still rejects anything that is not local.

### 8.4 Route script contract

Scripts under `api/` export `handle(ctx)` and write the result through `ctx.res` or `ctx.sse`:

```js
// api/models.js
export default {
  async handle(ctx) {
    // ctx.req: { method, path, query, headers, body, remote, json() }
    ctx.res.json({ object: "list", data: [{ id: "demo-model" }] })
    // also: ctx.res.text("hi", 200) / ctx.res.respond(body, { status, headers, contentType })
  }
}
```

- `ctx.res.respond(body, opts)`: a string goes out as-is, anything else is `JSON.stringify`d.
  Status defaults to 200; use `ctx.res.status = 404` or `opts.status`.
- A script that never responds yields 500 `plugin returned no response` instead of hanging the socket.
- Response bodies are capped at 4 MB. Script errors → 500, denied approval → 403, timeout → 504.
- A method outside `methods` returns 405 with an `Allow` header.

### 8.5 Streaming (SSE)

When the route declares `"stream": true`, `ctx.sse` exists (it is `null` otherwise, and using it
raises an error):

```js
export default {
  async handle(ctx) {
    const up = ctx.http.stream("https://upstream/v1/chat/completions", {
      method: "POST",
      headers: { Authorization: "Bearer ..." },
      body: JSON.stringify(payload)
    })
    if (up.status !== 200) {
      ctx.res.text(up.text(), up.status)
      return
    }
    ctx.sse.open()
    let line
    while ((line = up.next()) !== null) {
      if (line.startsWith("data: ")) {
        if (!ctx.sse.write(line.slice(6))) break   // false = client is gone
      }
    }
    up.close()
    ctx.sse.close()
  }
}
```

- `ctx.http.stream(...)` returns `{ status, headers, next(), text(), close() }`; `next()` blocks
  until a line arrives, strips the newline, and returns `null` at the end.
- The first `ctx.sse.write` opens the stream implicitly; `false` means the client disconnected.
- `ctx.sse.comment("ping")` writes a keepalive comment frame.
- At most 16 concurrent streams per plugin; further requests get 429 before the script runs.
- Disabling the plugin, uninstalling it, turning serving off or rotating the key closes all streams.
- **No WebSocket**: two-way work uses SSE (server to client) plus plain POST (client to server).

### 8.6 Timeout

A route script gets 30 seconds to prepare, enforced by the JS engine's interrupt handler. Time spent
blocked in `up.next()` during streaming is Kotlin time and does **not** count; a stuck upstream is
caught by the HTTP read timeout instead.

---

## 9 Settings and storage

```
files/plugins/<id>/config.json   settings + __key
files/plugins/<id>/data/         plugin data, relative root for ctx.fs
files/plugins/<id>/logs/plugin.log
```

- `config.json` is written atomically (temp file + rename).
- Setting changes apply immediately; the next `ctx.settings.get` sees them, no reload.
- Exporting a plugin can include `config.json`; `secret` fields and `__key` are stripped by default.
- Backups gain a Plugins part: manifest + config + `data/`, no scripts or logs.

---

## 10 Plugin pages

### 10.1 Declarative (first)

`ui.pages[].blocks` is rendered with native Compose, so it follows theme and font size:

| block | Purpose |
|---|---|
| `section` | Title plus text |
| `text` | A rounded text box, multi-line, optionally monospace |
| `fields` | Form that calls an action on submit |
| `button` | Button, with confirmation for destructive actions |
| `list` | List fed by an action |
| `key` | Show the plugin key: copy, rotate, reveal |
| `status` | Rows of status, e.g. per-channel availability |
| `progress` | Progress for long operations |
| `log` | Tail of the plugin log |

Actions are exported by the script:

```js
export const actions = {
  async refresh(ctx) {
    return { blocks: [{ type: "status", items: [{ label: "OpenCode Zen", value: "available" }] }] }
  }
}
```

The returned blocks replace the page in place. No WebView involved.

### 10.2 HTML (later)

With `ui.web = "web/index.html"`, the Page button opens a dedicated WebView:

- Only assets inside the plugin directory load; external links go to the system browser.
- A narrow bridge is injected:

```js
const r = await window.mcpbox.invoke("refresh")
await window.mcpbox.tool("free_gateway__status", {})
```

- The bridge exposes only `invoke` (plugin actions) and `tool` (tool calls, still approved).
- This is separate from the built-in AI browser: no shared cookies or pages.

### 10.3 Slots (injecting into settings pages)

A plugin can inject a group of rows into a designated place in the settings tree. Content is
declarative and rendered with the existing `CardGroup` / `RowSpec`, so theme, dynamic colour, font
size, ripples and backdrop transparency all come out right automatically.

```json
"ui": {
  "slots": [
    {
      "anchor": "settings/network#console",
      "place": "after",
      "rows": [
        { "type": "row", "title": "Gateway status", "subtitle": "8 models available", "action": "status", "page": "dashboard" },
        { "type": "switch", "setting": "enabled", "title": "Serve to other apps" },
        { "type": "text", "text": "Copy the key on the Plugins page before first use." }
      ]
    }
  ]
}
```

**Anchors.** Page level is `settings/<page>`, section level is `settings/<page>#<section>`:

| Anchor | Location |
|---|---|
| `settings/appearance` | Appearance & language |
| `settings/appearance#theme` / `#language` / `#interaction` | Theme / language / interaction |
| `settings/appearance/wallpaper` | Backdrop |
| `settings/appearance/bars` | Top and bottom bars |
| `settings/appearance/ball` / `#icon` / `#langpack` | Floating ball / icon / language packs |
| `settings/network` | Network & access |
| `settings/network#network` / `#console` | Network / web console |
| `settings/browser` | Browser |
| `settings/browser#security` / `#page` / `#search` / `#identity` | Security / pages / search / identity |
| `settings/background` / `backup` / `stats` / `about` | Same-named pages |

Section ids are registered in code as a constant table; adding a settings page or section means
registering one there, otherwise plugins cannot reach it.

**Anchors plugins may not use.**

- `settings/tools` (tools) and `settings/memory` (memory store) — the whole page is excluded
- The onboarding flow (not part of the settings tree)
- The easter-egg / developer-mode section of About is technically open, but injecting there breaks
  the point of hiding it; the install preview warns about that

**Slot-only blocks.**

| block | Meaning |
|---|---|
| `row` | One row: title, subtitle, action |
| `switch` | A switch bound to one of the plugin's own boolean settings via `setting` |
| `text` | A block of text |

`row` actions:

| Action | Effect |
|---|---|
| `page` | Open one of the plugin's own pages |
| `action` | Run one of the plugin's actions, with progress |
| `copy` | Copy a piece of text (gateway URL, key, ...) |

**Grouping.**

- At most one group per plugin per anchor.
- **Own group by default**: a new `CardGroup` right after the section, labelled "Plugin · <name>",
  so disabling the plugin removes the group cleanly.
- An `OutlineTag` marking "Plugin" in the group label is allowed, so the source is obvious.

**Switches must show their origin.** A plugin cannot inject a switch that changes app behaviour —
every switch on a settings page currently maps to a real config key, and mixing in plugin switches
makes it impossible to tell which one is real. `switch` may only bind the plugin's own boolean
setting; the subtitle is prefixed with "Plugin setting · " and the group label carries the name.

**Refresh.** Slot content is dynamic (action results change), so `revision` alone is not enough:
`PluginHost` keeps a `pluginRev` per plugin, incremented after each action, and plugin rows use it
as their `remember` key.

**Three engineering traps every settings page with slots must handle.**

1. **Predictive-back preview layer**: `behind` renders a second instance of the previous page. If that
   page has plugin rows they must be rendered there too, otherwise the drag shows a page without them
   and they pop in on release. Scroll state has the same problem and must be hoisted to the host
   (there is already precedent in `SettingsScreen`).
2. **Scroll position**: row height varies with content; sharing one `ScrollState` between the real
   page and the preview needs care.
3. **Backdrop**: rows must use `cardColor(...)`, otherwise they are opaque patches over a custom
   backdrop.

**Ordering.** Rows are collected per anchor at composition time: first by `place` (before / after),
then by plugin id, so the order is stable across recompositions.

---

## 11 Background tasks

`McpService` hosts a `PluginScheduler`:

| Source | Declaration | Trigger |
|---|---|---|
| Timer | `"interval": "30m"` (`s`/`m`/`h`, min 1m, max 24h) | While the service runs |
| Startup | `"events": ["startup"]` | Once when the app starts with the plugin enabled |
| File change | `"events": ["file.changed"]` + `"watch": ["/sdcard/Download"]` | FileObserver; paths must be allowed |
| After a tool call | `"events": ["tool.called"]` + `"filter": ["write_file"]` | After the named tool succeeds |

```js
export function onEvent(ctx) {
  ctx.log("info", `event: ${ctx.event.type}`)
}
```

- Same timeout as tools; a timeout counts as a failure.
- Backoff 1m / 5m / 15m; three consecutive failures disable the plugin and mark it red until the
  user re-enables it.
- Timers do not resurrect a killed app process. "Background" means the foreground service runs.
- File observers exist only while the service runs and are managed by the scheduler.

---

## 12 Permissions

### 12.1 Keys

| Key | New | Default | Covers |
|---|---|---|---|
| `fs.read` | no | allow | `ctx.fs.read/list` |
| `fs.write` | no | ask | `ctx.fs.write`, writes to `data/` |
| `fs.delete` | no | ask | `ctx.fs.remove` |
| `shell.exec` | no | ask | `ctx.shell.run` |
| `network` | **yes** | ask | `ctx.http.*` |
| `http.serve` | **yes** | granted at install | `http.routes` |
| `system.info` | no | allow | Read-only device info |
| `browser.control` | no | ask | Reserved |
| `ui.control` | no | ask | Reserved |

### 12.2 Tool plugins: per-call approval

Declared `permissions` are an install-time disclosure, not a grant. Every host API call goes through
`PermissionStore.decide` + `ApprovalCenter`. The prompt names the plugin:

```
Tool    hello_weather_forecast
Plugin  Weather helper 1.0.0
Action  Network access to api.example.com
```

### 12.3 `network` remembered per host

A prompt per request would make the gateway unusable, so "always allow" on `network` becomes a
**domain rule** — the same mechanism as command prefix rules (`Rule(type = DOMAIN, match = "host")`):

- Matches the hostname, not the full URL; subdomains are not implied.
- Wildcards only in prefix form: `*.example.com`.
- Rules live next to command rules in the permissions page and can be disabled or deleted.
- Shared by tool calls and route requests, so the gateway asks once and stops.

### 12.4 Service plugins: install grant + key

- `http.serve` has two states, not three: granted when the user enables the plugin, otherwise no routes.
- Switching to `lan` needs one more confirmation, stating that devices on the same network can use
  the key.
- Disabling, rotating the key, or stopping the service closes every open SSE connection.

### 12.5 What plugins cannot do

- No `Context`, `SharedPreferences` or Java classes; only `ctx`.
- No reflection; Python disables the `java` module.
- The plugin directory cannot be modified at run time, only through installation.
- Routes can only answer their declared paths; they cannot forward into built-in routes.
- A plugin cannot inject a switch that changes app behaviour; `switch` binds only plugin settings.
- `settings/tools` and `settings/memory` are closed to plugins.

---

## 13 Install, upgrade, uninstall

### 13.1 Install

- Entry: the `+` in the plugin list, picking a `.zip`. After extraction the archive must contain
  `plugin.json` directly; archives that wrap everything in one folder (`zip -r x.zip myplugin/`)
  are accepted and the extra level is stripped.
- **Extraction is the security boundary**: every entry name is normalised and rejected if it is an
  absolute path, contains `..`, or carries a drive letter — one bad entry rejects the whole package
  and nothing is written. 16MB per file, 64MB extracted in total, 2000 entries.
- Then `plugin.json` is found and validated: manifest → version requirement → reserved path
  conflicts → route conflicts → referenced scripts exist.
- Any `config.json` shipped in the package is discarded (it holds someone else's settings and key).
  On upgrade the local `config.json` is kept, so settings and key survive.
- Extraction goes to a temporary directory and the old directory is replaced only after everything
  passes; a failure never leaves half a plugin behind.
- **New plugins start disabled**; enabling them registers tools and routes. Upgrading an installed
  plugin keeps its previous on/off state.
- Limits: 40 tools and 40 routes per plugin.

### 13.2 Upgrade

- Version must be higher; "force replace" needs a second confirmation.
- `config.json` and `data/` survive; scripts are replaced. The key is kept so existing clients keep
  working.
- The plugin is disabled after upgrade, so a new version does not silently inherit old grants.

### 13.3 Uninstall

- Confirm, listing the directory to be removed.
- Optional "keep data": `data/` moves to `files/plugin-data/<id>/` and reattaches on reinstall.
- Routes and SSE connections close before the directory is deleted.
- The built-in `my.tools` plugin cannot be uninstalled.

---

## 14 Relationship with custom tools

- On first start, a non-empty `custom_tools.json` becomes the built-in `my.tools` plugin:

```json
{
  "id": "my.tools",
  "name": "My tools",
  "version": "1.0.0",
  "builtin": true,
  "tools": [ { "name": "...", "kind": "shell", "command": "...", "params": [] } ]
}
```

- After migration, `create_custom_tool` / `update_custom_tool` keep working by editing that array.
- `custom_tools.json` stays as a read-only snapshot; the manifest is the source of truth.
- The custom tools page merges into the plugin page: `my.tools` is a built-in entry whose detail
  page is the old tool list.

---

## 15 UI

Follows the existing rules: top-level entry plus dedicated subpages, no card piles, no emoji.

```
Settings
 └─ Plugins
     ├─ plugin list (name; version · tools · routes · author; enable switch on the right)
     ├─ Import (the `+` top right, pick a .zip)
     └─ plugin detail (page key: plugins/<id>)
         ├─ status: enabled, external service, API key
         ├─ overview: description, author, homepage, install source and time
         ├─ tools: name + description
         ├─ routes: method + path + script
         ├─ settings: generated from the manifest
         ├─ logs: tap to see the last 60 lines
         └─ uninstall (with confirmation)
```

- Tapping a row opens the detail page; the switch only toggles "enabled" (broken plugins have none).
- Incompatible plugins state the requirement in the subtitle and their switch is locked
  (`LockedSwitch`); the detail page still shows the overview and uninstall.
- "External service" and "API key" appear only when the plugin declares `http`.
- Settings render in three shapes: boolean = switch, enum = pill menu, text / secret / number =
  a pill showing the current value that opens a native dialog (input plus buttons only, no prose).
- Long operations (install) show a progress dialog with the file name.
- Rows injected into settings pages form their own group labelled with the plugin name; see 10.3.

---

## 16 Design of the two target scenarios

### 16.1 Free model gateway on the phone

**Scope.** Phase one covers anonymous free channels only: the OpenCode Zen gateway and the Kilo AI
public gateway. Neither needs an account or key. EAC channels (GitHub verification) and account
channels (provider logins) come later.

**Surface.**

| Route | Method | Notes |
|---|---|---|
| `/v1/models` | GET | Currently available models, OpenAI format |
| `/v1/chat/completions` | POST | Streaming and non-streaming |

Anthropic's `/v1/messages` waits until a client actually needs it.

**Client setup.**

```
Base URL   http://127.0.0.1:8720/v1
API Key    sk-<generated by the plugin>
Model      pick from /v1/models
```

With `lan` binding, replace `127.0.0.1` with the phone's LAN address.

**Channel probing.** Once at startup, then every `probe_minutes`; results show in `/v1/models` and
in the plugin page status block. A failed probe does not drop cached models, it only marks that
channel unavailable.

**Known risks, stated in the plugin description.**

- Anonymous channel availability is controlled upstream and can be rate-limited or withdrawn.
- Requests pass through a third-party gateway; not suitable for sensitive data.
- The model set per channel changes; clients must be able to refresh a cached list.

**Porting notes.** The upstream channel adapters are plain JS (build a request, parse SSE) and can
be moved; what is missing is Node's `fetch` / `http`, which `ctx.http` provides. Account channels
need credential storage and login flows, hence the separate phase.

### 16.2 File bridge between desktop and phone

Split: the phone side is a MCPBox plugin (provides the API), the desktop side is a separate dsh
plugin (Node / TypeScript, its own repository) that renders a sidebar panel in dsh. The two talk
plain HTTP + SSE rather than MCP, so any other tool can use the same API.

**Phone-side routes.**

| Route | Method | Notes |
|---|---|---|
| `/x/files/api/list?path=` | GET | Directory listing: name, size, mtime, isDir |
| `/x/files/api/stat?path=` | GET | Info for one path |
| `/x/files/api/raw?path=` | GET | Read a file, `Range` supported, images returned as-is |
| `/x/files/api/raw?path=` | PUT | Write a file (desktop to phone upload) |
| `/x/files/api/events` | GET | SSE, file change events (create / modify / delete) |

- Path semantics match the MCP tools and still go through `PathSandbox`: only allowed directories.
- Auth uses the plugin key. With `loopback` the desktop cannot connect, so this plugin defaults to
  `lan` and requires the "devices on the same network can use the key" confirmation first.
- SSE events are throttled (the same path merges within 500ms) so large writes do not flood.

**What the desktop panel does.**

- File tree plus preview (text and images).
- Quote: attach file content to the current conversation.
- Edit: save back via PUT; on conflict, warn using the phone file's mtime.
- Upload: drag files from the desktop and PUT them to the phone.
- Live updates: subscribe to SSE and refresh affected directories.

**Transport.** Direct on the LAN; Tailscale when not on the same network. Public exposure requires
HTTPS or at least a one-off key — stated in the plugin description, not enabled by default.

**Boundaries against existing features.** A desktop dsh can already connect to
`http://phone-ip:8720/mcp` as an MCP client and read/write phone files, but that is the model calling
tools on demand; this panel is about files appearing in the UI. The built-in SSH/SFTP also lets a
desktop mount the phone directory with no plugin at all; the plugin's extra value is the in-dsh panel
and live sync.

---

## 17 Phases

| Phase | Content | Acceptance |
|---|---|---|
| P1 | Manifest + JS runtime + tool registration + packs + settings | **Done**: a JS plugin installed on a real device; its tools are callable, arguments validate, approvals fire |
| P2 | HTTP routes + plugin key + streaming + host rules | **Done**: curl reaches `/v1/models`, `/v1/echo` and the streaming `/v1/stream`; plugin networking goes through approval |
| P3 | Plugin page + import + version requirements | **Done**: install from a zip, enable / disable / uninstall, edit settings, rotate the key; plugins needing a newer app refuse to install |
| P4 | Scenario one: free model gateway (anonymous channels) | RikkaHub points at the local address, lists models, chats, streams |
| P5 | Scenario two: file bridge API + desktop dsh plugin | dsh browses, quotes, edits and uploads phone files |
| P6 | Declarative pages + HTML pages + timers and events | Plugin has UI and runs on a schedule |
| P7 | Slots: settings injection + anchor table + preview-layer support | Plugins place rows and switches in settings pages |
| P8 | Python runtime + install from URL | Two languages, shareable |

P2 is the critical infrastructure and comes before the management page: once routes and streaming
work, scenario one is mostly channel adaptation.

### 17.1 Where P1 landed

Notes on how the implementation differs from the plan above, so later phases know the current state:

- **Engine**: `quickjs-kt` 1.0.15 (`io.github.dokar3:quickjs-kt`). Not the latest: from 1.0.16 the AAR
  requires `compileSdk 37`, and the project is on 36; 1.0.15 requires 36, which fits.
  It ships built with Kotlin 2.4 while the project compiler is 2.1, so the app module enables
  `-Xskip-metadata-version-check`.
- **A fresh QuickJs instance per tool call**, closed right after: no global state leaks between plugins,
  and one plugin wrecking its runtime cannot affect the next. Tool calls are infrequent enough.
- **Return values**: strings pass through; `null` / `undefined` become empty; anything else is JSON text.
  It is also lenient: a plain `export default async function (ctx) {...}` is accepted rather than rejected.
- **`ctx.http.*` reports "this build does not support network access yet"** in P1 instead of shipping an
  implementation without permission checks. P2 filled it in: `network` permission, host rules and
  approval, shared by tool scripts and route scripts.
- **Relative paths resolve inside the plugin's own `data/`** (no approval); absolute paths go through
  `PathSandbox` and the permission matrix.
- **Service plugins get their key at install time**, but route dispatch only arrives in P2.

### 17.2 Where P2 landed

- **The HTTP client is `HttpURLConnection`**, not OkHttp: mcpcore is plain JVM and must run on both
  Android and the harness. The cost is that `PATCH` needs a reflective field write, which JDK 17's
  module system blocks — that case reports "this system does not support PATCH, use POST" instead
  of quietly switching methods.
- **4 MB response cap**, truncating with a `truncated` flag; at most 3 redirects, each re-checked
  against host rules.
- **Nothing but `window` builtins plus `ctx`**: route scripts get `ctx.req.body` as a string and call
  `ctx.req.json()` themselves; object bodies passed to `ctx.res.respond` are stringified in JS.
- **No `checkAuth`**: plugin paths skip the web login and token checks in `dispatch` and use their own
  key, while the global "local only" switch still guards the front door.
- **`http.serve` is not a three-state `PermKey`**: it is a per-plugin grant (`__httpGranted`), because
  "allow this plugin to open a port" cannot be a global switch. The permissions page only gains
  `network`.
- **Host rules only grow from the approval dialog**: the rules page can flip allow/deny, disable or
  delete them, but offers no manual "add".
- **Streaming uses a synchronous `next()` instead of `for await`**: `while ((line = up.next()) !== null)`
  is far steadier than wiring an async iterator into the JS engine, and easier to debug.
- **The route timeout does not cover streaming**: `runRoute` adds no coroutine timeout and relies on the
  engine's interrupt handler for script compute time; a stuck upstream is caught by the HTTP read
  timeout. Tool calls keep their outer timeout (`evaluationTimeout + 5s`).
- **The plugin page was a minimal version in P2**: installed list, serving toggle, view/copy/rotate key.
  P3 added import, the detail page, settings and logs.

### 17.3 Where P3 landed

- **Import accepts `.zip` only** (the `.mcpbox-plugin` name from earlier drafts was dropped; the
  extension is not checked, the content is).
- **No install preview dialog**: extraction and validation happen in a staging directory and the
  package is thrown away on failure; the new plugin lands in the list disabled, and its tools,
  routes and permissions are visible by opening it.
- **Limits differ from the earlier draft's 20MB / 500 files**: 16MB per file, 64MB total, 2000 entries.
- **Upgrade does not compare versions**: the same id is simply replaced, keeping `config.json`
  (settings and key) and the on/off state. Roll back by importing the old package again.
- **Custom tools were not migrated into a built-in `my.tools` plugin**: they still live in
  Settings → AI → Tools as their own store; the plugin page lists real plugins only.
- **Logs show the last 60 lines**, with no clear / export / live tail.
- **The detail page has no "pages" section**: declarative pages are P6.

---

## 18 Open questions

1. **Can the AI install plugins itself?** Custom tools can be created by the model; a plugin is a
   whole package of code that can also take HTTP routes, an order of magnitude riskier.
2. **Is Python worth it?** Chaquopy adds a Gradle plugin and roughly 10+ MB. If both target
   scenarios fit in JS, P7 can be dropped.
3. **Distribution**: local import only at first. A GitHub index for install-by-URL and updates later?
4. **Anthropic format in the gateway**: is `/v1/messages` needed, depending on the client actually used?
5. **File bridge conflicts**: on mtime conflict, overwrite, save-as, or refuse — decided in the
   desktop plugin.
