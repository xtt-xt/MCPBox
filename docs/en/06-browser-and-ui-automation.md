# 06 · Built-in browser & UI automation

[← Back to README](../../README.en.md) ｜ [中文](../06-浏览器与UI自动化.md)

## Built-in browser (`browser` pack, 18 tools, **no Root / Shizuku needed**)

The app ships its own WebView, so the AI can browse: open pages, read text and interactive elements, click / fill forms / scroll / wait for elements, run JS, screenshot pages, manage multiple tabs, search the web, save a page to a file, or download a link. See [01 Tools](01-tools.md#browser--built-in-browser-18-off-by-default-no-root-needed) for all 18 tools.

### Security and defaults

| Item | Default | Notes |
|---|---|---|
| LAN / localhost | **blocked** | Stops page scripts from reaching the local MCP port; can be allowed in Settings → Browser |
| Max pages | **5** | Close some before opening more |
| Default search engine | **Bing** | Also Baidu / Google / DuckDuckGo / Zhihu / Weibo / Bilibili / GitHub / Wikipedia… |
| Global identity (UA) | **mobile** | Can be overridden per page to desktop |
| Login state | **isolated** from your system browser | Cookies and cache belong to MCPBox only |
| Approvals | **every action asks** | Permission key `browser.control` defaults to "ask" (changeable on the Permissions page) |

### Cookies and identity, per page

Every page can have its own User-Agent and cookies:

- `browser_open` / `browser_navigate` accept `ua` (`mobile` / `android` = phone, `desktop` / `windows` = desktop) and `cookies` (written before loading, so **the very first request is already logged in**).
- `browser_storage` takes a `page` argument: omit `url` and the target is that page's URL. Actions: `get_cookies` / `set_cookie` (multi-line, several per line) / `clear_site_cookies` (one site only) / `clear_cookies` (all) / `clear_cache` / `get_ua` / `set_ua`.
- Changing the global identity does **not** affect pages that already have a per-page override.

### Floating ball and panel

Approvals and the browser **share one floating ball** (merged 2026-11-21; they used to have one each):

| What is there now | What the ball shows | Tap | Long-press |
|---|---|---|---|
| Pending approvals only | Pending count | Opens the approval card | Just vibrates and bounces |
| Open pages only | Globe icon | Opens the browser panel | Just vibrates and bounces |
| Both | Pending count (approvals win) | Opens the approval card | Vibrates and pops a ring menu |

- The ring menu has two sectors and picks its side from the **ball centre's distance to each of the four screen edges**, so it never runs off-screen:
  top-left corner → bottom-right, top-right → bottom-left, bottom-left → top-right, bottom-right → top-left (one quadrant each);
  only hugging the left edge → opens right, right edge → opens left, top edge → opens down, bottom edge → opens up (half a circle each);
  far from every edge (e.g. snapping set to None with the ball parked mid-screen) → one item on each side.
  Slide onto a sector and release to open it; release near the centre to cancel.
- Ball position and snapping (none / left / right / auto) live under **Settings → Appearance → Floating ball**, shared by both features.
- With no pages and no pending approvals the ball hides itself.

The panel itself: a stroked window with five buttons — **pause AI / reload page / close this page / close all / collapse**.

- "Pause AI" only blocks the AI's actions; a manual "reload page" is never blocked by it (taking over should not be blocked by your own pause).
- The panel can be dragged, and lets you watch the page, switch pages and take over manually.

### Sleeping inactive pages

Android's WebView has no per-page freeze API (`pauseTimers` is global and would stop the current page too), so background pages get a hard sleep instead: **once the timeout passes the WebView is destroyed and only its address is kept**; opening it again reloads it.

- Timeout under **Settings → Browser → Sleep inactive pages**: never / 5 / 15 / 30 / 60 minutes (default 15).
- "Last used" counts any AI action on that page (read content / click / screenshot / eval…), switching to it, operating the panel, and touching or scrolling on the page.
- The current page is **not** frozen while the panel is open (the user is looking at it); once collapsed, it sleeps by the same timeout.
- Sleeping pages show "asleep" on their tab and in `browser_pages`; any action on them wakes them up, at the cost of one extra load for the AI.
- Waking restores the scroll position; **form input, in-page routing and transient state are lost**.
- The page limit still counts tabs, not live WebViews.

### Known limits

| Limit | Reason |
|---|---|
| `clear_site_cookies` cannot delete **HttpOnly** cookies | Chromium never exposes them to the Java layer |
| A per-page UA override does not change **client hints** | Measured with `httpbin`: `User-Agent` echoes the custom string, but `Sec-CH-UA` is still Android WebView |

### Reading convention

`browser_content` / `browser_search` / `browser_save` **auto-scroll to the bottom first** by default (`auto_scroll=false` disables it; `scroll_rounds` sets how many screens) — many sites only load content once scrolled. It stops at the bottom rather than returning to the top.

## UI automation (`ui` pack, 8 tools, **needs Root or Shizuku**)

Treats the phone screen as an interface: screenshot, dump the view tree (with absolute coordinates), simulate taps / swipes / typing / key presses, launch apps, wait for elements. Tool list in [01 Tools](01-tools.md#ui--ui-automation-8-off-by-default-needs-root--shizuku).

- **Why privileges**: screenshots, view dumps and injected taps are system privileges the app's own UID does not have.
- **Backend fallback**: dumping the UI falls back across available privileged backends (**root first**) and logs "UI backend fallback" on success.
- **Chinese input**: `ui_input` automatically goes through "write clipboard → simulate paste".
- **Do not retry blindly**: an `idle state` error means give up (retrying gives the same result).
- **Permission key**: `ui.control` (default "ask").

> UI automation on a real device is slow and depends on which app is in the foreground — start with `ui_screenshot` / `ui_dump` to see the current screen and coordinates before deciding where to tap.
