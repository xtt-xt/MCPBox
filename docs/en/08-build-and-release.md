# 08 · Build & release

[← Back to README](../../README.en.md) ｜ [中文](../08-构建与发布.md)

## Architecture

```
mcpcore/   Pure Kotlin/JVM, zero Android dependencies: HTTP server, MCP protocol, tool
           implementations, permission matrix, file bridge, file gateway, static hosting
           (runs and tests standalone)
harness/   End-to-end tests: a real HTTP server, real requests against every tool and policy
app/       Android layer: Compose UI, foreground service, approval overlay / notifications,
           Shizuku integration, terminal, built-in browser
```

Why the split: **all enforcement lives in `mcpcore`**, so it is covered by end-to-end tests and does not depend on the Android runtime.

- `mcpcore` knows nothing about Android; host capabilities (notifications, clipboard, root / Shizuku, WebView) are injected through interfaces.
- `harness` runs a **real server with real HTTP requests**, not mocks: interception order, auth, the permission matrix, approvals, static hosting and the browser (with a fake WebView bridge) are all verified there.

## Repository layout

```
app/                                   Android app (Compose UI + service + overlay)
  src/main/java/com/xtt/mcpbox/
    ui/                                Home / Terminal / Permissions / Logs / Settings / Tools /
                                       Memory / Backup & restore / Stats / About / pack editor / rules
      Common.kt                        Shared components (paging, press cards, bottom bar, transitions)
      SettingsScreen.kt / SettingsPages.kt   Settings: top-level entries + 6 sub-pages
    browser/BrowserController.kt       Built-in browser: WebView + floating ball + draggable panel
    i18n/                              Lang.kt + LangEn.kt (English table) + LangCat.kt (hidden language)
    server/                            Foreground service, approval action receiver, boot receiver
    AppCore.kt / Prefs.kt              In-process singletons and preferences
    AndroidHost.kt                     Device info, notifications and other system capabilities
    PermGrants.kt                      Auto-granting system permissions (detect / pick backend / run)
    ShizukuShell.kt                    Shizuku process launcher
    UpdateChecker.kt / UpdateDialog.kt Update checks and the "new version" dialog
mcpcore/src/main/kotlin/com/xtt/mcpbox/core/
  HttpServer.kt                        Tiny HTTP/1.1 (keep-alive, chunked, SSE)
  McpServer.kt                         Routing, auth, MCP protocol, console security
  ToolsRead.kt / ToolsWrite.kt / ToolsShell.kt / ToolsUi.kt / ToolsBrowser.kt
  ToolsMemory.kt / ToolsPacks.kt / ToolsToken.kt     Tool group implementations
  Approval.kt / Permission.kt / ToolPolicy.kt        Permission matrix, approval center, per-tool policy
  PathSandbox.kt / Trash.kt / FileBridge.kt / FileGateway.kt
  Shell.kt / ShellMirror.kt            Shell backends (app / root / Shizuku) and AI command mirroring
  WebConsole.kt / WebSites.kt / Config.kt / CustomTools.kt / EventLog.kt
  Memory.kt                            Memory store: entities + observations + relations, atomic graph.json
  Backup.kt                            Backup & restore: four parts, two modes, sniffing
  Stats.kt                             Usage stats (heatmap data, uptime, launch counts)
  ToolPack.kt                          Tool packs: built-in definitions + custom pack storage
  ProfileStore.kt                      Sessions (URL profiles): active packs + TTL, atomic writes
  AutoGrant.kt                         Command planning for auto-granting permissions (pure, testable)
  Browser.kt                           Browser core: URL rules / LAN blocking / engines / injected JS
  I18n.kt / Json.kt / Schema.kt / Tool.kt / ToolMeta.kt / LocalNet.kt / ServerMeta.kt
harness/                               End-to-end tests (633 assertions)
.github/workflows/build.yml            CI: test → build → artifacts → Release on tags
tools/release.sh                       One-shot release script
```

## Building

```bash
# End-to-end tests (633 assertions, real server)
./gradlew :harness:e2e

# Build
./gradlew :app:assembleDebug        # debug
./gradlew :app:assembleRelease      # release (unsigned if no keystore)
```

| Item | Value |
|---|---|
| JDK | 17 |
| compileSdk / targetSdk | 35 |
| minSdk | 26 |
| Gradle | bundled wrapper (8.13) |
| AGP / Kotlin | 8.7.3 / 2.1.0 |
| Modules | `mcpcore` + `harness` + `app` |
| release APK size | ~2.7 MB |

## Signing

Signing material is **never committed** (`keystore/` is in `.gitignore`); it is read from environment variables at build time:

| Variable | Meaning |
|---|---|
| `KEYSTORE_PATH` | keystore path, defaults to `keystore/release.keystore` |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | key alias |
| `KEY_PASSWORD` | key password |

**You can build without a keystore** — the release APK simply stays unsigned.

## Versioning & releasing

Version format: **`v<release>-<core>`** (e.g. `v1.2.2-126`):

| Part | Example | When it changes |
|---|---|---|
| Release | `1.2.2` | Only when you publish a release |
| Core | `126` | Every build (+1), equals `versionCode` |

That keeps `versionCode` strictly increasing (upgrades never conflict) while `versionName` only moves on real releases.

One-shot release (`tools/release.sh` builds, uploads the APK to the phone, commits and pushes):

```bash
tools/release.sh                # daily build: core +1
tools/release.sh 1.2.2 "notes"  # release: versionName 1.2.2, core +1, tag, push --follow-tags
```

Before releasing, write the `## v1.2.2 · date` section in CHANGELOG.md (release sections are **bilingual**; daily sections are Chinese only) — its heading must start with `## v1.2.2`, because CI extracts the release notes from it.

## GitHub Actions

[`.github/workflows/build.yml`](../../.github/workflows/build.yml) runs on pushes to `main`, pull requests and `v*` tags:

1. Restore the signing keystore (`KEYSTORE_BASE64`; skipped if absent → unsigned APK)
2. Run the end-to-end tests (`:harness:e2e`)
3. Build `assembleRelease` + `assembleDebug` and verify the signature
4. Collect artifacts: `MCPBox-v<version>-<core>-release.apk` / `-debug.apk` / `-source.zip`
   (the source zip comes from `git archive` — GPL-3.0 requires shipping sources with binaries)
5. Upload artifacts (30-day retention)
6. **On `v*` tags**: extract the matching CHANGELOG section as release notes, create the Release and attach all three files

To have CI produce a **signed** APK, add four secrets under **Settings → Secrets and variables → Actions**:
`KEYSTORE_BASE64` (run `base64 -w0 keystore/release.keystore` locally and paste the whole string),
`KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.

## License

Copyright (C) 2026 xtt · **GNU General Public License v3.0** (see [LICENSE](../../LICENSE)).
All dependencies use permissive licenses (Apache-2.0 / MIT) and are compatible with GPL-3.0.
