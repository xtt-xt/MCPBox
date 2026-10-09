# 05 · File transfer & web

[← Back to README](../../README.en.md) ｜ [中文](../05-文件通道与静态站点.md)

## Upload / download (no root, no cable)

```bash
# Push a file to the phone (the raw body is the file content)
curl -X POST --data-binary @app.apk \
  "http://<phone-ip>:8720/upload?path=/storage/emulated/0/xtt/app/mcp/app.apk&token=<token>"

# Pull a file off the phone
curl -o back.apk \
  "http://<phone-ip>:8720/download?path=/storage/emulated/0/xtt/app/mcp/app.apk&token=<token>"
```

- Three ways to pass the token: `?token=`, `Authorization: Bearer`, `X-MCP-Token`.
- **Upload uses the "write files" permission, download uses "read files"** — both ask for approval as usual.
- Size limit per request = the `maxUploadMb` setting (default **512 MB**).

### Upload page in a browser

Open `http://127.0.0.1:8720/upload` on the phone: pick **multiple files**, type a destination path, and optionally tick "this is an archive" (equivalent to `extract=1`).

### zip: a whole directory in one request

One request = **one approval** (do not loop over N files — that pops N approvals):

```
POST /upload?path=<dir>&extract=1     body is a zip → extract into that dir
GET  /download?path=<dir>&zip=1       dir → packed into a zip and returned
```

An uploaded archive is validated first, then approved, and only then written:

- at most **20,000** entries
- extracted total ≤ `maxUploadMb × 4`
- unsafe entry names (`../`, absolute paths, drive letters) are rejected

## Static site hosting

Drop web artifacts on the phone and open them in a browser — no cable, no extra server:

```
http://127.0.0.1:8720/web/?token=<token>                        site list
http://127.0.0.1:8720/web/xtt/web/flat-ui/index.html?token=<token>
```

- **Paths are relative to the "main root"**: `/web/a/b.html` means `<main root>/a/b.html`.
- A directory renders an index page, marking its `index.html` as "this directory's entry page".
- **A token is required, but only once**: put `?token=…` in the first URL and the server plants a `Path=/web` HttpOnly session cookie (**12 hours**, invalidated by a server restart). Sub-resources (CSS / JS / fonts) are then fetched by the browser with that cookie automatically — which is exactly why `?token=` alone is not enough (the browser does not repeat the query string when it requests sub-resources). To avoid typing a token: open the link from the **web console**'s "Static sites" card, which already includes it.
- **Read-only**: no PUT / DELETE; writing still goes through `/upload` (which asks for approval).
- `<main root>/.MCPBox` (the app's own directory, including the trash) is hidden, and hidden entries are not listed in the index.
- Single-file limit **32 MB** (a preview is read fully into memory); use `/download` for larger files.
- Permission rule: only **"deny"** on `fs.read` blocks it ("ask" does not pop up — a single page requests dozens of sub-resources, one by one approval would be unusable).
- Turning the token feature off entirely (`tokenEnabled=false`) opens this route up, matching `/download`.

## Private app directories

See [02 Permissions → Private app directories](02-permissions.md#private-app-directories): three modes (off / read-only / read-write), forwarded through root / Shizuku, covering `/data/data`, `/data/local/tmp` and more.

## Web console security

| Switch | Effect |
|---|---|
| Local-only access | Only answers `127.0.0.1` / `::1`; LAN devices get 403 (off by default) |
| Password protection | The browser must log in (12-hour cookie session); programmatic token calls are unaffected (off by default) |
| Access password | Set separately; when empty the access token doubles as the password |

The console itself lives at `http://127.0.0.1:8720/`: try tools, read logs, handle approvals and open static sites from a browser.

## HTTP routes at a glance

| Route | Purpose | Auth |
|---|---|---|
| `/mcp`, `/mcp/p/<name>` | MCP (Streamable HTTP) | token |
| `/sse` + `/messages` | MCP (legacy HTTP+SSE) | token |
| `/upload` | Upload (supports `extract=1`) | token + approval |
| `/download` | Download (supports `zip=1`) | token + approval |
| `/web/<path>` | Static sites (read-only) | token (once, then cookie) |
| `/` | Web console | optional local-only / password |
| `/api/status`, `/api/tools`, `/api/log`, `/api/call`, `/api/pending`, `/api/approve` | JSON endpoints used by the console (pending approvals, approve / deny) | token |
