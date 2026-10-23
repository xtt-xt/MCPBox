# MCP File Box Privacy Policy
Version v2 | Effective date: October 24, 2026

## 1. General
1.1 This policy explains what data MCP File Box ("the App") touches, where it is stored, and when it leaves your device. It forms part of the Terms of Use.
1.2 The App is a service you run on your own phone. There is no account system, no developer-operated server, and no sign-up, login or cloud sync.

## 2. What Is Not Collected
2.1 Device identifiers (IMEI, Android ID, serial number, advertising ID) are not collected.
2.2 Your file contents, directory structure and file name lists are not collected, and are not uploaded anywhere.
2.3 Location, contacts, SMS, call logs, photo library contents and the list of installed apps are not collected.
2.4 The App contains no telemetry, crash reporting, advertising, promotion or behavioral tracking components. There is no third-party analytics SDK.

## 3. Where Data Lives
Everything the App produces is written to its **private app directory** on this device (`/data/data/<package>/`), which other apps cannot read directly:

3.1 Settings, access token, port, permission rules, tool pack state — stored in local SharedPreferences.
3.2 Sessions (which tool packs are active) — JSON files under `profiles/`.
3.3 Memory store (entities, observations, relations written by the AI) — `memory/graph.json`.
3.4 Usage statistics (daily request counts, uptime, launch count) — `stats/stats.json`.
3.5 Custom backdrop — a copy of the image or video you picked, inside the private directory.
3.6 Custom tools and custom icons — likewise inside the private directory.

Unless you export or back up explicitly, none of this is sent off the device by the App.

## 4. Why Each Permission Is Used
4.1 **File access** (all-files access / storage permission): to read and write files in device storage as you authorize, and for the MCP file channel. You can restrict it to the app-private directory or specific folders on the permissions page.
4.2 **Display over other apps**: for approval prompts, and for the built-in browser's floating ball and page panel. Without it, approvals fall back to notifications.
4.3 **Notifications**: for approval alerts and foreground service status. Notification text contains only an operation summary (tool name, path), never file contents.
4.4 **Ignore battery optimization / start on boot / wake lock**: to keep the service running in the background and resume listening after a reboot.
4.5 **Root / Shizuku**: used for running commands and UI automation only after you grant it. Whether and how much you grant is up to you, and you can revoke it at any time.
4.6 All of the above must be granted by you; the App does not bypass system dialogs to obtain them.

## 5. Network Access
The App uses the network only in these cases:

5.1 **Listening on a local port** (8720 by default). The service only accepts local (127.0.0.1) connections by default; once LAN access is enabled, devices on the same network can connect.
5.2 **When you check for updates**, the App queries GitHub's public API for the latest version number. The request carries version information only, no device identifiers or usage data.
5.3 **Built-in browser**: pages that you or the AI open are requested directly from the corresponding site. The site sees your network's egress IP, just as with ordinary browsing.
5.4 **External addresses called by the AI client**: the targets and contents of network requests the AI makes through the App are decided by that AI.
5.5 The App does not send data to any developer-controlled server — no such server exists.

## 6. Access Token
6.1 The access token verifies the caller's identity on the HTTP channel (upload, download, web console, static sites).
6.2 The token is stored locally and leaves the device only when you copy the address or enter it into a client.
6.3 A leaked token is equivalent to handing over file read/write permission. You can reset it in settings; clients configured with the old token must then be updated.
6.4 Disabling token verification lets anyone on the same network reach the HTTP channel; evaluate that risk yourself.

## 7. Logs and Statistics
7.1 Runtime logs (tool calls, approvals, connections, errors) are kept **in memory only**, up to 800 entries, and disappear when the app process ends.
7.2 Logs record operation summaries: time, kind, tool name, path and a short result. **File contents are never logged.**
7.3 Usage statistics record only daily request counts and uptime, used to draw the heatmap inside the App. Statistics are generated locally and never sent to a server.
7.4 Both logs and statistics can be cleared or reset inside the App.

## 8. Backup Files
8.1 Backups are started by you, and may contain the memory store, settings, custom tools, statistics and (optionally) the access token.
8.2 Backup files are written to the location you choose, or to third-party storage you configure (such as WebDAV or S3).
8.3 You are responsible for keeping backup files safe. They may contain your token and memory contents; do not place them where others can access them.
8.4 The App keeps no copy of backup files and does not upload them.

## 9. AI Clients
9.1 The App is only a channel. What an AI client reads, writes and sends is decided by that client and by your authorizations.
9.2 The privacy practices of the AI client itself are the responsibility of its provider and fall outside this policy.
9.3 You can use the permissions page and tool packs to limit which tools and directories the AI can see.

## 10. Third-Party Components
The App uses open-source components such as AndroidX, Jetpack Compose, Kotlin, Shizuku and haze. They run on the device and contain no data-reporting logic.

## 11. Minors
The App is a developer tool and is not designed for minors. If you are under the legal age of consent, use it under the guidance of a guardian.

## 12. Changes to This Policy
12.1 This policy may change with new versions. Material changes will be announced in the App and will require you to confirm again.
12.2 A changed policy takes effect on the date of publication. Continued use means you accept it.

## 13. Contact
Questions about data handling: open an issue in the GitHub repository at https://github.com/xtt-xt/MCPBox/issues

— End of document —

---

This page matches the Privacy Policy shown in the app under About → Privacy Policy.

Back to: [README](../../README.en.md) · [Terms of Use](09-terms.md)
