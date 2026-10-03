# 更新日志

## 版本号规则

格式：**`v<发布版本>-<核心版本>`**，例如 `v1.0.0-22`

| 部分 | 例子 | 什么时候变 |
|---|---|---|
| **发布版本** | `1.0.0` | **只在正式发布发行版时**递增（1.0.0 → 1.1.0 → 2.0.0），按需求决定 |
| **核心版本** | `22` | **每次构建都 +1**，等于 Android 的 `versionCode` |

好处：
- `versionCode` 恒定递增 → 升级安装永远不会冲突
- `versionName` 只在发版时动 → 用户看到的版本有意义，不被日常构建刷屏

发版：

```bash
tools/release.sh              # 日常构建，核心版本 +1
tools/release.sh 1.1.0        # 发行版：发布版本改成 1.1.0 并打 tag
```

---

## v1.2.1 · 2026-10-03 —— 未发布 / Unreleased

<details open>
<summary><b>中文</b></summary>

### 新增能力

| 能力 | 说明 |
|---|---|
| **进入 App 自动补齐权限** | 有 Root / Shizuku 时，打开 App（包括刚从系统设置页回来）自动把缺的「文件访问 / 悬浮窗 / 忽略电池优化」静默开掉 —— 走 `appops` 与电池白名单，**不会重启进程**。首页「环境检查」多一行「一键补齐」，一次把缺的都开掉并报出结果；设置 → 后台与运行里可以关掉自动（默认开） |
| **审批方式可选** | 设置 → 安全与审批 → 审批方式：「悬浮窗」（默认，浮在所有应用之上，没有悬浮窗权限时自动退回通知栏）或「通知栏」（只发带「允许一次 / 始终允许 / 拒绝」按钮的通知，不弹悬浮窗）。改完立刻生效，不用重启服务器；同时来多条审批时各占一条通知，互不顶掉 |
| **整目录一次传** | 文件网关支持压缩包模式：`POST /upload?path=目录&extract=1`（body 是 zip → 解压到该目录）、`GET /download?path=目录&zip=1`（目录打成 zip 一次取走）。**一个请求 = 一次审批**，所以传一整个文件夹不再弹 N 次窗；解压前先校验（条目数 / 解压后总大小 / 拒绝 `../` 这类危险条目），有问题时一个文件都不写；浏览器上传页也支持多选与「这是压缩包」勾选框 |
| **内置浏览器（工具包 `browser`）** | App 自带一个 WebView，AI 可以：打开 / 跳转 / 前进后退刷新网页、读正文（text / markdown / 原始 HTML / meta）、读可交互元素（带序号和坐标）、按序号 / 文字 / 选择器 / 坐标点击、填表单（input / textarea / 下拉 / 勾选，自动派发 input + change）、滚动、等元素出现、执行 JS、网页截图、多页面管理、用各平台搜索（必应 / 百度 / Google / DuckDuckGo / 知乎 / 微博 / B站 / GitHub / 维基 + 自定义）、把网页存成 md / txt / html、管 cookie 与 User-Agent、按链接下载文件。**不需要 Root / Shizuku**。AI 一打开页面，手机上就出现一个悬浮球：点开是可拖动、可缩放的悬浮窗，能在里面看当前页、切页面、手动接管，还有「暂停 AI / 继续」「关闭本页」「全部关闭」 |

### 关于浏览器

- **权限**：新增独立权限键「浏览器控制」，默认**询问** —— 每个浏览器动作都会弹一次审批。嫌烦可以把它设成允许，或在弹窗上点「始终允许」
- **安全**：默认禁止访问 localhost / 127.0.0.1 / 10.x / 192.168.x / `*.local` 这类内网地址 —— 这台手机上跑着 MCP 服务器（`/mcp` 端点不校验 token），网页里的脚本能碰本机就等于绕过审批。要放开：设置 → 浏览器 → 允许访问内网地址
- **百度这类「结果包在自己域名下」的引擎也能拿到结果了**：`browser_search` 在站外链接不够时，改为优先给「跳转型」链接（`/link?url=`、`/url?`、`redirect` 等）—— 百度把结果都包在 `www.baidu.com/link?url=…` 里，之前会被同域名过滤掉，最后只剩导航
- **`browser_wait` 等文字时会在整页正文里找**（原来只在「可交互元素」里找，等 `<h2>` 这种标题永远等不到）；元素列表的坐标改成**页面绝对坐标**（滚到底之后不再是负数），`by=point` 点击会自动把滚动量减回去；`target=_blank` 的链接在点击时改成当前页打开（合成点击触发的 `window.open` 会被弹窗拦截）
- **搜索等结果真的渲染出来**：搜索结果页是流式渲染的，打开一两秒后 DOM 里常常只有顶栏（首页 / 图片 / 视频 / 翻页…）。`browser_search` 现在会**一直轮询到真的拿到结果**（站外链接够多就停；一条都没有时绝不提前收工，上限 25 秒）—— 必应带 AI 摘要的页面会先静止几秒再一次性把结果挂上去，实测打开 4 秒时 DOM 里只有 10 个顶栏链接、结果要十几秒才出现；实在拿不到时会把「页面当时有多少链接 / 元素」一并报出来
- **搜索引擎的「同站」判断**改成按后两段域名比：引擎会换域名（`www.bing.com` 跳到 `cn.bing.com`），原来用 `endsWith` 判错，导航 / 翻页 / 广告链接冒充成了结果链接；同时结果链接从「只抓 30 条」改成按需抓（最多 400 条）再筛，避免真结果被顶栏挤掉
- **`browser_eval` 兼容严格 CSP 的站点**：必应这类站点禁止页面里 `eval`，原来的包装方式会直接报错；现在检测到 CSP 拒绝 eval 时自动换成「把代码直接当脚本执行」（`try { … }` 的完成值语义一样，只是错误以 `[js error] …` 字符串回来）
- **又一个真机才暴露的坑（这次是搜索为什么只给导航链接）**：搜索引擎的 URL 模板里有 `%s`，而 `java.net.URI("https://www.bing.com/search?q=%s")` 会抛 `Malformed escape pair` —— 被 `runCatching` 吞掉之后引擎域名变成空字符串，「去掉搜索引擎自己域名的链接」那一步就整个失效（空域名一律判不同站），于是 `browser_search` 把顶栏导航当成搜索结果返回。现在统一走 `SearchEngine.host` / `hostOf()`（先把占位符换掉、URI 失败再退回 `URL`），并加了回归断言
- **修一个真机才暴露的坑**：注入的 `links` 脚本里写着 `filter.toLowerCase()`，而 `filter` 默认是 `null` —— 整段脚本抛异常，于是 `browser_content(mode=links)` 与 `browser_search` 的结果链接**永远是空的**（正文照样有，所以很容易看漏）。现在脚本对 null 做了保护，并加了回归断言（JS 在 harness 里跑不了，这类写法只能靠断言守）
- **搜索 / 读内容**：`browser_search` 现在会**还原跳转壳**（必应 `bing.com/ck/a?u=a1…`、Google `/url?q=`、DuckDuckGo `/l/?uddg=`、知乎 `link.zhihu.com`、搜狗 `/link`），所以在必应这类页面上能真的拿到结果链接了（之前会被「去掉搜索引擎自己的域名」整批滤掉）；`browser_content` / `browser_search` / `browser_save` 默认**先自动滚到底**（滚完停在底部、不回顶 —— 回顶会把客户端流式渲染出来的内容抖掉），把「滚了才加载」的长列表、图片、评论区喂出来（`auto_scroll=false` 关掉，`scroll_rounds` 控制最多滚几屏）
- **后台照样能用**：悬浮窗收起时窗口缩成 1×1 像素，但 WebView 仍按整屏尺寸排版（懒加载、媒体查询、元素坐标都不会错位），截图走 `View.draw`，不需要窗口真的显示在屏幕上
- **登录态**：cookie 存在 App 自己的数据里，和系统浏览器完全隔离；用户可以点悬浮球在自己眼前登录，之后 AI 能复用这份登录态（`browser_storage` 读得到 —— 注意隐私），也可以随时清空
- **页面数上限**默认 5（设置 → 浏览器里可调 1~12），每个页面一块 WebView，很吃内存
- 「暂停 AI」期间，AI 的浏览器动作会被直接拒绝并提示等待，等用户点「继续」再继续
- 设置 → 浏览器：允许访问内网、最多页面数、默认搜索引擎、自定义搜索引擎增删、User-Agent（手机 / 桌面 / 默认）、清 cookie / 清缓存
- 悬浮球可以拖动，松手后**完整吸附在屏幕边缘**（不做半收）；球体是主色渐变 + 投影 + 深色地球图标，AI 操作时球上有一盏小灯和一条状态胶囊；面板改成带描边的卡片、按钮与标签都是胶囊，顶栏下加了一条分隔线

### 说明

- **通知权限不自动补**：它是运行时权限，`pm grant` 会让系统把正在运行的进程杀掉重启 —— 启动阶段做这件事，界面刚画出来就没了；仍旧由用户在系统弹窗里点一下
- 只在**已经有可用特权后端**时才动手（Shizuku 已授权 / root 可用），不支持的项不碰，也不会自己跳系统页面
- 自动补齐的结果写进日志；同一项失败后不再反复试（免得每次切回前台都弹一次 root 授权框），手动点「一键补齐」会重新试一遍
- 首页「环境检查」自己每秒校一次状态：从系统设置页回来、或刚被补上的项，卡片立刻跟着变（不再等服务器状态轮询）
- 审批方式改完**立刻生效**（每次请求现读设置），不用重启服务器；同时来多条审批时各占一条通知、按钮也按请求分开，不会互相顶掉
- 传一整个文件夹（比如把工作区的一个目录推到手机）：

```bash
zip -qr /tmp/x.zip 文件夹/
curl -X POST --data-binary @/tmp/x.zip \
  "http://127.0.0.1:8720/upload?path=/storage/emulated/0/目标目录&extract=1&token=<token>"
# 反向：把手机上的目录打包取回
curl -H "Authorization: Bearer <token>" \
  "http://127.0.0.1:8720/download?path=/storage/emulated/0/目标目录&zip=1" -o x.zip
```

### 基线

- 端到端测试 **566 项全绿**（新增 99 项：浏览器工具包 71、跳转壳还原 6、注入 JS 回归 4、同站判断与 CSP 退路 7、引擎域名与 %s 坑 5、内置包清单 1；更早的 33 项见下）

</details>

<details>
<summary><b>English</b></summary>

### New capabilities

| Feature | Notes |
|---|---|
| **Auto-fill permissions on launch** | With Root / Shizuku, opening the app (including coming back from a system settings page) silently grants the missing *files / overlay / ignore-battery-optimizations* permissions via `appops` and the battery whitelist — **no process restart**. The home screen's environment check gained a "fill all at once" row, and Settings → Background & runtime can turn the automatic pass off (on by default) |
| **Selectable approval style** | Settings → Security & approval → Approval style: *overlay* (default — floats above every app, falls back to a notification without the overlay permission) or *notification* (a notification with Allow once / Always allow / Deny buttons, no overlay). Takes effect immediately, no server restart; concurrent requests each get their own notification |
| **Whole folders in one request** | The file gateway gained an archive mode: `POST /upload?path=<dir>&extract=1` (body is a zip → extracted into that folder) and `GET /download?path=<dir>&zip=1` (folder packed into a zip and streamed back). **One request = one approval**, so moving a folder no longer asks N times; the archive is validated first (entry count, extracted size, `../` entries rejected) and nothing is written when it fails. The browser upload page supports multi-select and an "this is an archive" checkbox |
| **Built-in browser (tool pack `browser`)** | The app ships its own WebView, so the AI can open / navigate / go back-forward-reload pages, read text (plain / Markdown / raw HTML / meta), read interactive elements (with indices and coordinates), click by index / text / selector / coordinates, fill forms (input / textarea / select / checkbox, firing input + change), scroll, wait for elements, run JS, screenshot a page, manage several tabs, search across platforms (Bing / Baidu / Google / DuckDuckGo / Zhihu / Weibo / Bilibili / GitHub / Wikipedia plus your own), save pages as md / txt / html, manage cookies and User-Agent and download a URL. **No Root or Shizuku needed.** As soon as the AI opens a page a floating ball appears; tap it for a draggable, resizable window where you can watch the page, switch tabs, take over by hand, or hit *Pause AI* / *Resume*, *Close tab*, *Close all* |

### About the browser

- **Permission**: a new key, *browser control*, defaults to **ask** — every browser action prompts once. Set it to allow, or hit *Always allow* in the popup, if that is too chatty
- **Security**: intranet addresses (localhost / 127.0.0.1 / 10.x / 192.168.x / `*.local`) are blocked by default — this phone runs the MCP server and `/mcp` does not check the token, so a page script reaching the device would bypass approvals. To allow it: Settings → Browser → Allow intranet addresses
- **Engines that wrap results in their own domain (Baidu) now yield results**: when there are too few off-site links, `browser_search` prefers "redirect-style" links (`/link?url=`, `/url?`, `redirect`, ...). Baidu wraps every result in `www.baidu.com/link?url=…`, which the same-site filter used to drop, leaving only the navigation
- **`browser_wait` now searches the whole page text** (it used to scan only interactive elements, so waiting for an `<h2>` heading never succeeded); element coordinates became **absolute page coordinates** (no more negative numbers after auto-scrolling) and `by=point` clicks subtract the scroll offset; links with `target=_blank` are switched to the current tab when clicked (a synthetic click's `window.open` is popup-blocked)
- **Search waits for results to actually render**: result pages stream in, so a second or two after opening the DOM often only holds the header (home / images / videos / paging). `browser_search` now **polls until it really has results** (stops as soon as there are enough off-site links; never gives up early when there are none; 25 s cap) - Bing's AI-summary pages sit still for a few seconds and then attach everything at once: at 4 s the DOM held only 10 header links and the results took over ten seconds. When nothing is found it reports how many links / elements the page had at the time
- **"Same site" detection for search engines** now compares the last two domain labels: engines switch domains (`www.bing.com` redirects to `cn.bing.com`) and the old `endsWith` check misfired, letting nav / paging / ad links impersonate results; result links are also scraped on demand (up to 400) instead of a fixed 30, so real results are no longer crowded out by the header
- **`browser_eval` now works on strict-CSP sites**: Bing and friends forbid page `eval`, which made the old wrapper fail outright; when a CSP eval rejection is detected the code is injected directly as a script instead (same completion-value semantics, errors come back as an `[js error] …` string)
- **Second device-only bug (why search returned nothing but header links)**: engine URL templates contain `%s`, and `java.net.URI("https://www.bing.com/search?q=%s")` throws `Malformed escape pair`. `runCatching` swallowed it, leaving the engine host empty, which silenced the "drop the engine's own domain" filter (an empty host never matches a site) - so `browser_search` handed back the header nav as results. Everything now goes through `SearchEngine.host` / `hostOf()` (replace the placeholder first, fall back to `URL` parsing) with regression assertions
- **Fixed a device-only bug**: the injected `links` script called `filter.toLowerCase()` while `filter` defaults to `null`, so the whole script threw and the result links of `browser_content(mode=links)` and `browser_search` were **always empty** (the body text still came back, which made it easy to miss). The script now guards null and a regression assertion was added (JS cannot run in the harness, so such patterns are guarded by assertions only)
- **Search / reading**: `browser_search` now **unwraps redirect shells** (Bing `bing.com/ck/a?u=a1…`, Google `/url?q=`, DuckDuckGo `/l/?uddg=`, Zhihu `link.zhihu.com`, Sogou `/link`), so result links actually come back on pages like Bing (they used to be filtered out wholesale as "the engine's own domain"); `browser_content` / `browser_search` / `browser_save` now **auto-scroll to the bottom** by default so lazy lists, images and comments get loaded - and stop there, because scrolling back up makes some client-rendered pages drop what they had just rendered (`auto_scroll=false` to disable, `scroll_rounds` to cap the passes)
- **It keeps working in the background**: when the panel is collapsed the window shrinks to 1×1 px while the WebView still lays out at full screen size (lazy loading, media queries and element coordinates stay correct); screenshots go through `View.draw`, so the window never has to be visible
- **Login state**: cookies live in the app's own data, fully separate from your system browser. The user can sign in from the floating window and the AI reuses that session (`browser_storage` can read it — mind the privacy), or wipe it at any time
- **Tab limit** defaults to 5 (1–12 in Settings → Browser); each tab is a WebView and costs memory
- While *Pause AI* is on, browser actions are rejected with a note to wait until the user hits *Resume*
- Settings → Browser: intranet access, max tabs, default search engine, add/remove custom engines, User-Agent (mobile / desktop / default), clear cookies / cache
- The ball can be dragged and snaps **fully inside the screen edge** (no half-tuck); it is a primary-gradient sphere with a soft shadow and a dark globe glyph, plus a status chip and a small activity dot while the AI works. The panel is now a bordered card with pill buttons and pill tabs, separated from the header by a hairline

### Notes

- **Notifications are never auto-filled**: it is a runtime permission, and `pm grant` makes the system kill and restart the running process right after the UI appears — the user still taps through the system dialog
- It only runs when a privileged backend is already usable (Shizuku authorised / root available); unsupported items are left alone and no system page is opened for you
- Results go to the log; a failed item is not retried within the same run (so a denied root prompt doesn't pop up on every foreground), while tapping "fill all at once" retries it
- The environment check re-checks itself every second, so items granted from a system page or by the auto-fill show up immediately
- The approval style takes effect **immediately** (read per request, no server restart); concurrent requests each get their own notification and action buttons, so they no longer overwrite each other
- Moving a whole folder (e.g. pushing a workspace directory to the phone):

```bash
zip -qr /tmp/x.zip folder/
curl -X POST --data-binary @/tmp/x.zip \
  "http://127.0.0.1:8720/upload?path=/storage/emulated/0/target&extract=1&token=<token>"
# the other way round: pack a phone folder and pull it back
curl -H "Authorization: Bearer <token>" \
  "http://127.0.0.1:8720/download?path=/storage/emulated/0/target&zip=1" -o x.zip
```

### Baseline

- End-to-end tests: **566 green** (+99: 71 for the browser toolkit, 6 for redirect unwrapping, 4 for injected-JS regressions, 7 for same-site detection and the CSP fallback, 5 for engine hosts and the %s trap, 1 for the built-in pack list; the earlier 33 are listed above)

</details>

---

## v1.2.0 · 2026-10-03 —— 第二个正式发行版 / Second stable release

<details open>
<summary><b>中文</b></summary>

上一个 Release 是 `v1.1.0`（2026-09-28）。从那时到现在（核心版本 `-48` → `-80`）的主要变化：

### 新增能力

| 能力 | 说明 |
|---|---|
| **备份与恢复** | 记忆库 / 设置 / 自定义工具 / 统计四部分。可以打成一个 zip，也可以每部分单独导一个 json；恢复时逐项选「合并 / 覆盖」，设置还能单独决定要不要连访问令牌一起恢复（默认不带，免得把客户端全踢下线） |
| **使用统计** | 设置 → 统计：每日请求热力图（一年 53 列，可横向滑；不满一年自动铺满）+ 累计运行时长 / 打开应用次数 / 服务器启动次数 / 有请求的天数。只增不减，重置设置也不清，随备份走 |
| **初始引导** | 全新安装第一次打开走五步（欢迎 / 语言 / 权限 / 恢复备份 / 开始使用）；升级不打扰，开发者模式里可以强制重走 |
| **开发者模式** | 关于页连点图标 7 次解锁：语言菜单、更新弹窗预览（拉 GitHub 最新 Release 的真实内容）、强制检查更新、强制重走引导 |
| **全项目 i18n 收尾** | 六批补完：App 界面、工具标题与说明、审批弹窗链路、44 个工具返回给 AI 的正文、日志与错误消息、网页控制台与上传页 —— 全项目可见文案清零 |

### 界面重做

- **设置页拆成「入口 + 子页」**：一级只留入口行（外观与语言 / 网络与访问 / 安全与审批 / 终端与命令 / 工具管理 / 记忆库 / 后台与运行 / 备份与恢复 / 统计 / 关于），具体开关都在子页；子页进出场带 1/3 屏宽滑动动画，返回键回设置首页，滚动位置按页保留
- **底部导航栏重做**：B 站式图标胶囊；全 App 自己处理 `WindowInsets`（适配小白条）；单击不回顶、**双击当前 tab 才回顶**；涟漪只画在图标胶囊里；备份 / 恢复的底栏挪到页面外层，进出只滑一次
- **设置页收纳**：工具管理、记忆库提到一级（删掉中间的「AI 与工具」子页）；预设会话挪到权限页顶部；超时开关与滑块连成一组；`RowSpec.visible` 支持展开 / 收起动画（收起最后一行时上一行的底部圆角跟着变圆）
- **文案精简**：去掉各页副标题与一批冗余说明（这是刻意的，不是缺文案）

### 修的问题

- **恢复备份直接闪退**：JSON 分不出 `int` / `long` —— `approval_timeout` 导出再读回来变成 Int，写回后再用 `getLong` 读就 `ClassCastException`。现在导出时额外记一份 `types` 映射、恢复严格照类型写回；老备份跟着当前键的类型走；`Prefs` 读取也加了容错
- **设置子页的底栏不隐藏**；「后台与运行 / 外观与语言 / 开发者模式」里的开关点了不刷新（缺 `revision` → 调用点 lambda 被 Compose 判定成相等，整页跳过重组）
- **权限页**：胶囊点不动；点「授权」会自己跳步
- **初始引导**：把「走到第几步」存进了 saved instance state —— 进程被系统回收再回来会从中间续上，看着像自己跳页；底栏挂着自家审批浮层时点得穿，会把「跳过 / 下一步」误点掉
- **自动授权会把 App 自己搞重启**：`pm grant` 授运行时权限会让系统杀掉正在运行的进程 → 引导权限页改成只显示状态 + 提供入口，全部由用户自己点；权限入口则先试 `appops` / 电池白名单这类不会重启进程的静默授权
- **更新弹窗**：正文限高可滚动 + 轻量渲染 Markdown，不再被截断
- **终端**：支持输入法、支持回到底部

### 基线

- 端到端测试 **434 项全绿**（v1.1.0 时是 336；新增备份与恢复 51 项、类型保真 19 项、使用统计 21 项等）
- 工具 **52 → 54**（多了记忆库导入导出 `memory_export` / `memory_import`）
- release APK **2.6 MB**（v1.1.0 时 2.3 MB），仍是三模块（mcpcore + harness + app）
- 文档：中文 [README.md](README.md) / [README.en.md](README.en.md)（新增「统计」小节）

</details>

<details>
<summary><b>English</b></summary>

The previous release was `v1.1.0` (2026-09-28). What changed since then (core versions `-48` … `-80`):

### New capabilities

| Feature | Notes |
|---|---|
| **Backup & restore** | Memory / settings / custom tools / stats. Export everything as one zip or one json per part; on restore you choose merge or replace per part, and settings can optionally carry the access token (off by default, so your clients don't get kicked out) |
| **Usage stats** | Settings → Statistics: a daily request heatmap (53 weeks, horizontally scrollable; a shorter history fills the width) plus total run time, app launches, server starts and days with requests. Only ever grows, survives a settings reset, travels with backups |
| **First-run guide** | Five steps on a fresh install (welcome / language / permissions / restore a backup / start); upgrades are never interrupted, and developer mode can replay it on demand |
| **Developer mode** | Tap the icon on the About page 7 times to unlock: the language menu, update-dialog preview (pulls the real latest GitHub release), forced update checks, replaying the guide |
| **Full i18n sweep** | Six batches: app UI, tool titles and descriptions, the approval flow, the body text 44 tools return to the AI, logs and error messages, the web console and upload page — no visible Chinese-only strings left |

### UI rework

- **Settings split into entries + subpages**: the top level only lists entries (Appearance / Network / Security / Shell / Tools / Memory / Background / Backup / Statistics / About); every switch lives in its own subpage with a 1/3-screen slide transition, back-key returns to the settings home, and scroll position is remembered per page
- **Bottom navigation rebuilt**: Bilibili-style icon pills; the whole app handles its own `WindowInsets` (gesture bar friendly); a single tap no longer scrolls to top — **double-tap the current tab does**; ripples are clipped to the icon pill; the backup/restore bottom bar moved outside the pages so it slides in only once
- **Settings tidied up**: Tools and Memory are top-level entries (the intermediate "AI & tools" page is gone); presets moved to the top of the Permissions page; timeout switches sit above their sliders; `RowSpec.visible` gives rows an expand/collapse animation (corner radii follow the visible rows)
- **Copy cleanup**: page subtitles and a batch of redundant explanations were removed on purpose

### Fixes

- **Restoring a backup crashed** — JSON can't tell `int` from `long`: `approval_timeout` came back as an Int, was written back as one, and `getLong` then threw `ClassCastException`. Exports now carry a `types` map, restores write values back in their original type, older backups follow the current key type, and `Prefs` reads coerce mismatched types instead of crashing
- **Subpage bottom bar stayed visible**; switches in Background / Appearance / Developer mode did nothing (missing `revision` meant Compose skipped the whole page)
- **Permissions page**: the pill didn't respond; tapping "grant" advanced the steps by itself
- **First-run guide**: the current step was kept in saved instance state, so a recycled process resumed mid-guide and looked like it jumped pages; taps could punch through the app's own approval overlay and hit "Skip / Next"
- **Silent granting restarted the app**: `pm grant` for runtime permissions makes the system kill the running process → the guide now only shows status and entry points and lets the user tap; the permissions entry tries `appops` / battery whitelist first, which don't restart the process
- **Update dialog**: height-capped and scrollable with a lightweight Markdown renderer, no more truncated text
- **Terminal**: IME support, and it scrolls back to the bottom

### Baseline

- End-to-end tests: **434 green** (336 at v1.1.0; +51 backup/restore, +19 type fidelity, +21 usage stats)
- Tools **52 → 54** (`memory_export` / `memory_import` added)
- Release APK **2.6 MB** (2.3 MB at v1.1.0), still three modules (mcpcore + harness + app)
- Docs: [README.md](README.md) (Chinese) / [README.en.md](README.en.md) — new "Statistics" section

</details>

---

## v1.1.0 · 2026-09-28 —— 第一个正式发行版 / First stable release

<details open>
<summary><b>中文</b></summary>

上一个 Release 停在 `v1.0.0-22`（2026-09-12）。从那时到现在的主要变化：

### 新增能力

| 能力 | 说明 |
|---|---|
| **UI 自动化工具包** | 8 个工具（截屏 / 读控件树 / 点击 / 滑动 / 输入 / 按键 / 启应用 / 等元素），挂在独立的「控制屏幕」权限下，默认不激活。`ui_input` 输中文自动走剪贴板 |
| **悬浮窗审批 + 通知栏兜底** | 审批弹窗浮在所有 App 之上，没有悬浮窗权限时自动改用通知栏按钮 |
| **权限页「预设会话」** | 一键切换 全部允许 / 全部拒绝 / 全部询问 / 自定义；前三个会统一并锁定开关，切走时记住你的自定义设置，切回来原样恢复 |
| **超时可「不限时」** | 审批弹窗不会自动消失、命令一直跑到自己结束（值存 0）；`run_shell` 的 `timeoutMs=0` 同理 |
| **工具级权限四态** | 单个工具可单独设 跟随 / 允许 / 询问 / 拒绝 |
| **多语言界面** | 中文 / English 内置，可导出模板翻译后导入 |
| **记忆库** | 实体 + 观察 + 关系（知识图谱），App 内可浏览编辑 |
| **工具包 / 会话隔离** | 52 个工具分 7 包按需激活（默认 22 个，省 52% token）；`/mcp/p/<名字>` 独立会话，可重置 / 删除 |

### 体验修复（v1.0.0-39 ~ -46）

- 读界面结构时在可用后端间自动回退（root 优先），`uiautomator` 静默失败不再无解
- 「复制地址」直接给带令牌的完整 URL，粘进客户端即可用
- 记忆库分批加载改为 20 条一批，进页面不再卡顿
- 弹窗 / 下拉菜单一律用原生实现；点整行 = 展开右侧下拉，不再弹居中的大窗
- 预设会话锁定态、超时开关与滑块连成一组、若干卡片间距修正
- i18n 补漏 60 条（根因：一批界面文案定义在 `mcpcore` 里，词条表一直没覆盖到）
- `server_info` / `get_device_info` 报完整版本号（`v1.1.0-47`）

### 基线

- 端到端测试 **336 项全绿**
- release APK 2.3 MB，三模块（mcpcore + harness + app）
- 文档：中文 [README.md](README.md) / [README.en.md](README.en.md)

### 已知待办

- 通知栏 / 终端回显 / Shizuku 提示 / 更新检查失败原因这一批文案还没 i18n（约 50 条，需要改字符串拼接）

</details>

<details>
<summary><b>English</b></summary>

The previous release was `v1.0.0-22` (2026-09-12). What changed since then:

### New capabilities

| Feature | Notes |
|---|---|
| **UI automation pack** | 8 tools (screenshot / read view tree / tap / swipe / type / key press / launch app / wait for element) behind its own "Control screen" permission, off by default. `ui_input` types CJK via the clipboard |
| **Floating approvals with a notification fallback** | The approval popup floats above every app; without overlay permission it falls back to notification buttons |
| **Presets** | One tap in the Permissions tab: all-allow / all-deny / all-ask / custom. The first three unify and lock the switches; your custom set is remembered when you switch away and restored exactly when you return |
| **Optional no-timeout mode** | The approval popup never auto-dismisses and commands run to completion (stored as 0); `run_shell`'s `timeoutMs=0` behaves the same |
| **Per-tool permissions** | Individual tools can be set to follow / allow / ask / deny |
| **Multilingual UI** | Chinese and English built in; export a template, translate it and import any language |
| **Memory** | Entities + observations + relations (a knowledge graph), browsable and editable in the app |
| **Tool packs / session isolation** | 52 tools in 7 packs, activated on demand (22 by default — 52% fewer tokens); `/mcp/p/<name>` gives an independent session you can reset or delete |

### Fixes and polish (v1.0.0-39 … -46)

- Reading the view tree now falls back across available backends (root first), so a silent `uiautomator` failure is no longer a dead end
- "Copy address" now hands you a full URL including the token — paste it into your client and go
- Memory loads in batches of 20, so opening the tab no longer stutters
- Dialogs and dropdowns use the native implementations throughout; tapping a row expands the dropdown on its right instead of opening a centred modal
- Preset lock state, timeout switch grouped with its slider, and several card spacing fixes
- 60 missing i18n entries added (root cause: a batch of UI strings is defined inside `mcpcore`, which the entry table never covered)
- `server_info` / `get_device_info` now report the full version (`v1.1.0-47`)

### Baseline

- End-to-end tests: **336 assertions, all green**
- Release APK 2.3 MB, three modules (mcpcore + harness + app)
- Docs: [README.md](README.md) (Chinese) / [README.en.md](README.en.md)

### Known issues

- The notification, terminal echo, Shizuku hint and update-check messages are not translated yet (~50 strings; needs string-concatenation work)

</details>

---

## v1.1.0-78 · 2026-10-02

**新增：使用统计（设置 → 应用 → 统计）· 统计进入备份**

- **统计页**（设置 → 统计）：一张 **请求热力图** + 几张数字卡，都是只增不减的长期记录
  - **热力图**：一列 = 一周（周一起），一年 53 列，横向可滑（打开就在今天那端）；
    记录不满一年时格子会自动放大铺满整宽。分档跟着一年里最忙的那天走，
    颜色从 `primary` 派生的四档，空格子用 `onSurface` 9% 灰
  - **总请求次数**：`POST /mcp` 里**每条 JSON-RPC 消息**算一次（`initialize` / `ping` /
    `tools/call` / 通知都算），按**设备本地自然日**分组。计数点放在 `collectResponses`，
    鉴权失败的请求不计数
  - **累计运行时长**：服务在跑就累加（`start` 时开始计时、`stop` 时结算），页面每秒刷新看着它涨
  - **打开应用次数**：一个进程只记一次（转屏 / 从最近任务切回都不会重复记）
  - **服务器启动次数** / **有请求的天数**
- **存储**：`filesDir/stats/stats.json`（原子写入：先写 `.tmp` 再改名）。请求很密集，
  所以打 `dirty` 标记 + 一个 15 秒一轮的守护线程统一落盘；「打开应用 / 启动 / 停止服务器」
  这种低频又重要的动作直接写。进程被杀最多丢最后一次落盘前的十几秒
- **永远保留**：统计不在 `SharedPreferences` 里，所以「重置全部设置」不会把它清掉；
  也**没有清空入口**（免得手滑把攒了半年的记录清掉）
- **进备份**：备份多了第四个部分「统计」（`stats.json`）。单独导出 / zip / 引导里的恢复都支持；
  恢复时**合并 = 按天取较大值、累计量取较大值**（同一份恢复两次不会翻倍，装新机也能带过去），
  覆盖 = 直接用备份里的。备份页「当前内容」也会显示统计规模
- **i18n**：新增词条 30 余条（中 / 英）

### 基线

- 端到端测试 **434 项全绿**（新增第 49 段 21 项：请求计数、按天分组、落盘重读、
  一个进程只记一次打开、嗅探 / zip 往返 / 合并不翻倍、热力图网格与分档）

---

## v1.1.0-65 · 2026-09-29

**修：恢复备份闪退（ClassCastException）· 权限胶囊点不动 · 备份页不打勾不再显示灰标签**

- **恢复备份直接闪退** —— 根因是 JSON 分不出 `int` / `long`：
  `approval_timeout = 300000` 导出再读回来变成 Int，我用 `putInt` 写了回去，
  而 `Config.reload()` 用 `getLong` 读 → `ClassCastException: Integer cannot be cast to Long` → 崩。
  三处一起修：
  - 导出设置时**额外记一份 `types` 映射**（每个键的原始类型），恢复时严格照类型写回
  - 没有 `types` 的老备份：跟着**当前这个键的类型**走（当前是 Long 就写 Long）
  - `Prefs` 的 `getInt` / `getLong` / `getBoolean` 加了容错：读到不匹配的类型就自己转，
    并顺手写回正确类型（防旧版本留下的坏数据，也防以后再踩）
- **权限页的胶囊点不动** —— `onExpandedChange` 只处理了「关闭」：
  胶囊自己的 `.clickable { setOpen(true) }` 走进回调后 `open == true` 什么都不做。
  现在开关都接，点胶囊和点整行都能展开
- **备份页**：没勾选的内容不再显示「不备份」灰标签，不打勾就是不打勾

### 基线

- 端到端测试 **413 项全绿**（第 48 段 19 项：备份的类型保真）

---

## v1.1.0-64 · 2026-09-29

**备份与恢复（设置 → 应用），外加几个修修补补**

### 新增：备份与恢复

设置一级新增入口（在「后台与运行」和「关于」之间），进去是两张卡：

- **备份** → 二级子页：勾选要备份的**记忆库 / 设置 / 自定义工具**，
  - 「设置」里还有个「包含访问令牌」开关（默认带；备份文件可能被分享出去，不想带就关掉）
  - 「导出 zip」把勾上的都打进一个包；下面「单独导出」可以只导其中一个 json
- **恢复备份** → 弹窗选「选 zip 备份」还是「选单独文件」（文件可多选），然后走两步：
  1. **要恢复哪些部分？** —— zip / 多文件走这步，逐个勾选；单文件直接跳第 2 步
  2. **每一部分怎么恢复？** —— 每行一个「合并 / 覆盖」胶囊，各选各的；
     设置部分如果带着令牌，还会多一个「连访问令牌一起恢复」开关（默认关，免得把客户端全踢下线）

细节：

- 每种文件都带 `_type` 标记（`mcpbox.settings` / `mcpbox.custom-tools`），
  恢复时自动识别类型；老格式的记忆文件（裸 `entities/relations`）也认
- zip 里是 `manifest.json` + 各部分的 json，manifest 记着有哪些部分、含不含令牌
- 「合并」= 只动备份里提到的；「覆盖」= 先清空这一部分再写入，不能撤销
- 恢复设置后主题 / 语言立即生效（整棵树重建），并提示端口 / 目录变了要重启服务器
- 恢复结果直接留在页面上，不用 toast 一闪而过

### 改：自定义工具右上角

「导出到文件 / 从文件导入」从正文挪到**右上角「⋯」菜单**（跟记忆页一个套路），
正文那排按钮删掉，换成一句指引。

### 修：开发者模式

- **语言菜单开关点了不刷新** —— `DevModePage` 没接 `revision`，跟「后台与运行」一个毛病，补上
- 新增**「关闭开发者模式」**卡片：关掉后关于页入口消失、语言菜单一起关；
  正用着猫娘语会自动退回跟随系统（再想开就连点图标 7 次）

### 基线

- 端到端测试 **394 项全绿**（第 47 段 51 项：备份与恢复）

---

## v1.1.0-63 · 2026-09-29

**修：设置子页底栏不隐藏 · 「后台与运行」开关点了不刷新 · 重置弹窗说清楚会丢什么**

三个真 bug：

- **设置子页的底栏没隐藏**：工具管理 / 记忆库 / 关于是独立的 Scaffold（无底栏）所以全屏，
  而设置子页共用外层 Scaffold 的 bottomBar → 底栏一直亮着。
  现在进子页时底栏带动画滑出（220/180ms），返回时滑回来
- **「后台与运行」里的开关点了不刷新**：这页是唯一没接 `revision` 的子页，
  加上调用点的 lambda 被 Compose memoize 成「相等」，整个子页被判定为参数没变直接跳过重组
  → 开关看着没动。现在补上 `revision`，并把四个开关值 `remember(revision)` 出来。
  （「外观与语言」同样补了 `revision`）
- **「重置全部设置」弹窗**改成说清楚影响面：会重置**除记忆库以外**的所有设置
  （权限与规则 / 工具覆盖 / 端口 / 令牌 / 目录 / 外观与语言 / 后台选项），
  服务器会停、用旧令牌的客户端要重填；记忆库不受影响。确认按钮写「确认重置」

---

## v1.1.0-62 · 2026-09-29

**继续收纳：工具包那张卡把会话开关吃进去；不限时开关挪到滑块上面**

- 权限页：「会话状态自动重置」不再另起分组，**直接接在「让 AI 自己开关工具包」下面，同一张卡**
  （首尾圆角、中间直角），后面才是「重置间隔」滑块
- 设置 → 安全与审批 / 终端与命令：**「不限时」开关挪到滑块行上面**；
  打开后下面那行（标题 + 秒数 + 滑块）**带动画收起**，不再靠「变灰 + 一句『上面的秒数失效』」凑合
- 顺带把两处「（上面的秒数失效）」的说法删掉 —— 行都没了，不用再解释

---

## v1.1.0-61 · 2026-09-29

**设置页收纳 + 卡片展开动画**

接着上一版继续收拾：

- **「会话」分组挪到「让 AI 自己开关工具包」下面**，紧挨着「重置这个会话 / 删除会话」按钮 ——
  原来它俩中间隔着一整段说明文字，看着不像一组
- 删掉「会话状态不会自动重置，完全手动控制」那句小字（跟上面的开关卡片说的是同一件事）
- `CardGroup` 支持 **`RowSpec.visible`**：行不再靠 `if (x) RowSpec(...) else null` 硬删硬加，
  而是走 **展开 / 收起动画**（展开 220ms、收起 180ms，配 150/120ms 淡入淡出）；
  首尾圆角按「可见行」重算，收起最后一行时上一行的底部圆角会跟着变回圆的
- 用上动画的地方：权限页的**重置间隔**、外观页的**种子颜色**（动态取色一开就收起来）

---

## v1.1.0-60 · 2026-09-29

**设置页扁平化：工具管理 / 记忆库 提到一级；会话自动重置挪到权限页的工具包下面**

「AI 与工具」这个中间子页里的两件事都需要常点，每次却要多过一层。现在：

- 设置一级新增「AI」分组，**工具管理**与**记忆库**直接作为入口行（副标题照旧报数字：工具个数 / 实体数 · 关系数）
- 「AI 与工具」子页**整个删掉**，少一层跳转
- **会话状态自动重置**（开关 + 重置间隔滑块）搬到 **权限页 → 工具包下面**新开的「会话」分组 —— 它管的就是工具包激活状态能活多久，跟工具包放在一起才对得上
- 工具包底部说明的指引同步改成「可在下面关掉」

---

## v1.1.0-59 · 2026-09-28

**修：更新弹窗正文被砍到 1200 字（滚到底只有一个「已」字）**

上一版把正文做成可滚动之后，截断就成了纯损失 —— `UpdateChecker` 原来写死的 `notes.take(1200)` 会把 Release 正文砍掉一大半，
表现就是「滚动到底只剩半个字」。现在：

- 正文**不再截断**，只留一个 20000 字的兜底上限（正常 Release 远小于这个数）
- 万一真超了，末尾会附一句「说明太长，这里只显示前面一部分，完整内容见 Releases 页面」，不会无声无息地断在半个字上

---

## v1.1.0-58 · 2026-09-28

**更新弹窗：正文可滚动 + 轻量渲染 Markdown**

之前那个弹窗有几个明显的问题，这次一起修掉：

| 问题 | 现在 |
|---|---|
| Release 说明是 Markdown（标题、表格、加粗、`<details>` 标签），原样塞进 `Text` 会把井号、竖线、尖括号全露出来 | 轻量渲染：标题层级、列表、引用、表格、行内样式、HTML 标签剥除 |
| 说明太长会把「去下载 / 稍后」两个按钮顶出屏幕，正文也没法滚动 | 正文区**限高（屏高 58%）+ 可滚动**，按钮固定在下面 |
| 正文硬截断到 600 字 | 不再截断（上限仍是 Release 正文取 1200 字），滚动着看 |

### 轻量 Markdown 渲染

弹窗拆成了独立的 `UpdateDialog.kt`：

- 标题按 `# / ## / ###` 给字号与字重，列表加「·」，引用行加左侧竖线
- 表格逐行拆开渲染成「**小标题** + 正文」两列（分隔行 `|---|` 直接丢掉）
- 行内 `**加粗**` → 半粗、`` `代码` `` → 等宽 + 主题色、`[文字](url)` 只显示文字
- `<details>` / `<summary>` 这类 HTML 标签整段剥掉（Release 中文案常带折叠块）

顺带把「发现新版本」弹窗从 `MainActivity` 里搬出来 —— 现在真检查更新和开发者模式预览用的是同一个组件，样式只维护一份。

---

## v1.1.0-57 · 2026-09-28

**开发者模式：更新弹窗预览改用 GitHub 最新 Release 的真实内容**

「预览更新弹窗」之前还会先比版本号，而 `versionName` 只在发版时才变（日常构建只涨 `versionCode`），
所以永远比不过最新 tag，永远退回示例内容。现在 `UpdateChecker.check(ignoreVersion = true)` 会**跳过版本比较**，
直接把 GitHub 上最新一条 Release 当成预览对象 —— 标题、正文都是真实的。

「下次启动强制检查更新」同理：清掉「今天已检查」记录后，下次启动会**忽略版本比较**地检查一次并弹窗，
这样模拟「用户第一次进入 App」才真的能看到弹窗。

连不上 GitHub 时才退回本机生成的示例说明，并且文案明确写了是示例。

---

## v1.1.0-56 · 2026-09-28

**关于页连点图标 → 解锁「开发者模式」（不再直接解锁猫娘语）**

以前连点关于页图标 7 次，直接把「猫娘语」塞进语言下拉，中间没有缓冲。现在改成两级：

```
关于页 ── 连点图标 7 次 ──▶ 解锁「开发者模式」入口卡片
                              └─▶ 开发者模式 ── 打开「语言菜单」──▶ 语言列表里出现「猫娘语」
```

### 开发者模式（关于页 → 开发者）

| 项目 | 干什么 |
|---|---|
| **语言菜单**（开关） | 打开后，彩蛋语言「猫娘语」才会出现在 设置 → 外观与语言 → 语言 的下拉里；关掉时如果正用着它，会自动退回「跟随系统」，免得下拉找不到当前项 |
| **预览更新弹窗** | 不管当前是什么版本，直接弹一次更新提示，显示 **GitHub 上最新的 Release**（标题、说明正文都用真实内容）—— 版本名只在发版时变，所以这里忽略版本比较；连不上 GitHub 才退回本机生成的示例，并明确说明 |
| **下次启动强制检查更新** | 清掉「今天已经检查过」的记录，下次打开 App 必定检查一次；查到最新 Release 就弹窗 —— 用来模拟用户第一次进入 App 的检查流程（即使「每天自动检查」是关的也会查） |

原来那张「喵喵喵」卡片换成「开发者模式」入口卡。

### 兼容

老版本解锁过猫娘语的人，升级后**不会丢**：开发者模式入口与语言菜单的默认值都回落到旧的那个解锁标记，
所以连点 7 次会提示「开发者模式早就在那里了」，语言下拉里猫娘语照旧在。

harness **358 项全绿**（本次只动 App 层，未触碰 mcpcore）。

---

## v1.1.0-55 · 2026-09-28

**设置页改成「顶层入口 + 独立子页」· 记忆库可以导入导出了（AI 也能用）**

### 设置页：8 个分组的长列表 → 6 个入口

原来一页滚到底（外观 / 网络 / 网页控制台 / 安全 / 终端与命令 / AI 工具 / 后台运行 / 关于），想改一个开关得先翻半天，加一项就再长一截。现在顶层只有入口行，开关都在各自的子页里：

| 分组 | 入口 | 里面有什么 |
|---|---|---|
| 通用 | 外观与语言 | 动态取色、种子颜色、调色板样式、颜色模式、语言与语言包 |
| | 网络与访问 | 监听端口、局域网访问、响应格式、网页控制台（仅本机 / 密码保护 / 访问密码） |
| | 安全与审批 | 启用访问令牌、令牌查看与重置、审批超时 / 不限时 |
| | 终端与命令 | 命令后端优先级、命令规则、命令默认超时 / 不限时 |
| 应用 | AI 与工具 | 工具管理、记忆库、会话状态自动重置与间隔 |
| | 后台与运行 | CPU 唤醒、开机自启、审批亮屏、记录日志、重启服务器、重置全部设置 |
| | 关于 | 直接进「关于」页 |

多层级设置页的四项必修一次做齐：

1. **进出场动画**：进入滑入 1/3 屏宽（300ms，旧页退 1/6），返回反向 —— 方向由目标状态决定，不写死
2. **系统返回键**：子页接 `BackHandler`（回顶层），顶层不接（顶层再按就该退出应用）
3. **标题属于各自的页面**：顶层是「设置」，子页是各自的标题，不会出现两个标题叠在一起
4. **滚动位置保留**：每页用 `SaveableStateHolder` 按 key 保存滚动位置，返回时不跳回顶部

还有一个坑单独处理了：**「当前停在哪个子页」提升到了 Activity 层**。切语言 / 切主题会重建整棵界面树，状态放在设置页内部的话，用户在子页里一改语言就被甩回设置首页。

### 记忆库：导入导出

记忆页右上角多了一个「⋯」菜单：

- **导出到文件** —— 整库导出成 JSON（实体 + 关系 + 观察），走系统文件选择器自己挑位置，默认文件名带时间戳
- **从文件导入** —— 两种模式：
  - **合并**（推荐、默认）：同名实体只补空着的类型 / 分区，观察去重后追加，**绝不覆盖已有观察**，重复关系跳过
  - **覆盖**：清空整库后整份替换 —— 有二次确认，写明「这一步不能撤销」

合并时「未分类」视同没填：导入文件里带了分区就会补上，用户显式选过的分区一律不动。

AI 侧同步多了两个工具：

| 工具 | 说明 |
|---|---|
| `memory_export` | 导出整库到 JSON，path 省略时写到 `根目录/xtt/memory/memory-<时间>.json` |
| `memory_import` | 从 JSON 导入，`mode=merge`（默认）/ `mode=replace`；文件不是合法记忆文件会明确报错 |

harness **358 项全绿**（新增第 46 段「记忆库导入导出」21 项：往返、合并去重、空字段补全、默认合并、非法文件报错）。

---

## v1.0.0-46 · 2026-09-28

**i18n 补漏（界面中文在英文下不再漏出来）· 去掉重复的「预设配色」**

### i18n：中文文案的源头不在 app 模块

之前一直以为翻译覆盖率还行，实际漏了一大片。根因是**一批界面文案定义在 `mcpcore` 里**（`PermKey` 的 title/desc、`BuiltinPacks` 的包名与说明、`ServerMeta` 的时间单位），而词条表只从 app 模块收集 —— 于是英文界面下这些地方直接露出中文：

| 之前（英文界面） | 现在 |
|---|---|
| `读取文件 / 写入文件 / 删除文件 / 执行命令 / 控制屏幕 / 系统信息` | `Read files / Write files / …` |
| `基础 / 文件读取 / 记忆库 / 文件写入 / 命令与自定义工具 / UI 自动化` | `Core / File reading / Memory / …` |
| `色调点 / 鲜活 / 表现力 / 保真 / 中性 / 单色` | `Tonal spot / Vibrant / Expressive / …` |
| `跟随系统 / 浅色 / 深色` | `Follow system / Light / Dark` |
| `本机 / 局域网 / 有线/热点 / 热点` | `This device / LAN / …` |
| `已运行 2 分 17 秒` | `Up 2 min 17 s`（新增可翻译的 `uptimeLabel()`，不再用 core 里写死中文单位的 `uptimeText()`） |

顺带修掉 5 处代码里**忘了包 `L()`** 的文案：工具包名与说明、「全部权限」、日志空态、导出失败、语言包信息块。共补 60 条词条。

> 仍未覆盖的是**通知栏 / 终端回显 / Shizuku 提示 / 更新检查失败原因**这一批（约 50 条）。它们不是纯 UI 文案，改起来要动字符串拼接，留到下一版单独做。

### 去掉「预设配色」

设置 → 外观里的「预设配色」整行删掉 —— 配色编辑（种子色对话框）里已经有 12 色板 + 色相/饱和度/亮度滑块，功能重复。随之成为死代码的 `ThemeChoice` 枚举和它的 7 条词条一并清掉。

harness **336 项全绿**。

---

## v1.0.0-45 · 2026-09-28

**「自定义」模式会被记住 · 超时开关跟滑块连成一组**

| 改动 | 说明 |
|---|---|
| 自定义设置不再丢 | 切到固定预设（允许 / 拒绝 / 询问）之前，先把当前那 8 个开关**存进快照**；之后切回「自定义」原样恢复。以前切一次「全部允许」，你调了半天的设置就被永久抹掉了 |
| 快照只在必要时更新 | 在固定预设之间切换（允许 → 拒绝）不碰快照；停在「自定义」里继续改，改动会同步进快照 |
| 连成一组 | 「审批超时」的滑块和「审批不限时」开关、以及「命令默认超时」和「命令不限时」，现在各自在**同一个连体卡片组**里（首尾圆角、中间直角）—— 上一版错误地拆成了两张独立卡片 |
| `RowSpec` 支持附加内容 | 新增可选参数 `content`（跟在标题下面，同属一张卡片）和 `subtitleColor`，滑块行才能进 `CardGroup`。规范里「多行连成一体」必须用 `CardGroup`，不能拿独立卡片堆 |
| i18n | 新文案补齐英文和猫娘语词条 |

harness 新增 9 项（都在 `[43]` 段：快照存 / 恢复 / 不被污染 / 持久化 / 老配置无快照），**336 项全绿**。

---

## v1.0.0-44 · 2026-09-28

**超时可「不限时」· 权限页点整行展开右侧下拉 · 记忆库进页更轻**

| 改动 | 说明 |
|---|---|
| 审批 / 命令超时支持不限制 | 设置页里各多了一张**独立的开关卡片**：「审批不限时」「命令不限时」。打开后滑块变灰、值显示「不限制」，超时按 **0** 存 → 审批弹窗不会自动消失、命令一直跑到自己结束 |
| 关掉开关会还原 | 打开前的秒数记在 Prefs 里，关掉开关回到原来的数字，不会被重置成默认值 |
| 点整行 = 点右边那个下拉 | 权限页「预设会话」和 8 个权限开关现在**点整行就展开右侧胶囊下拉**；原来那个居中的单选大窗（点整行弹出来的）删掉了 —— 全页只剩一种选择交互 |
| 记忆库分批加载 | 列表和观察列表从「首批 60 条」改成 **20 条一批**，离底半屏再补 20（预加载距离也可配了），进页面不再卡一下 |
| 控件间距 | 「重启服务器」那张卡片补上缺失的间距，不再和「记录日志」贴在一起 |
| `timeoutMs` 的 0 | `run_shell` / 自定义工具的 `timeoutMs` 现在 **0 = 不限制**；以前会被夹成 1000ms，等于一传就超时 |
| i18n | 新增文案补齐英文和猫娘语词条 |

harness 新增 10 项（`[45]` 超时无限制），**327 项全绿**。

---

## v1.0.0-43 · 2026-09-28

**预设会话改成下拉（四个选项）· 权限开关分组分开 · 工具能报完整版本号**

| 改动 | 说明 |
|---|---|
| 预设会话改成下拉 | 从三行卡片改成**一行 + 右侧下拉**，四个选项：**全部允许 / 全部拒绝 / 全部询问 / 自定义** |
| 前三个会锁住开关 | 选中前三个会把下面所有权限**统一成同一个值并锁住**（整行不可点、下拉只剩一个静态标签）；想单独调必须先切到「自定义」 |
| 权限开关独立分组 | 新增「权限开关」分组标题，跟上面的预设彻底分开（之前两组卡片贴在一起了） |
| 版本号 | `server_info` 和 `get_device_info` 现在报**完整版本号** `v1.0.0-43`；原来只有 `1.0.0`，看不出是第几版 |
| 兼容 | 老配置里没有 preset 字段 → 回落到「自定义」，升级上来的用户不会被莫名锁住 |
| i18n | 预设的四个选项 + 新文案都补齐了英文和猫娘语词条 |

harness 新增 18 项（`[43]` 预设会话 / `[44]` 版本号），**317 项全绿**。

---

## v1.0.0-42 · 2026-09-28

**权限页调整 + 会话可删除 + 地址直接带令牌**

| 改动 | 说明 |
|---|---|
| 「快捷操作」→「预设会话」 | 分组改名并挪到权限页**最上面**，顺序调成 全部允许 / 全部拒绝 / 全部询问 |
| 会话可删除 | 「当前会话」弹窗每行右边多一个垃圾桶，点了弹确认框；`default` 是兜底会话，不给删 |
| 复制地址带令牌 | 「复制地址」现在复制的是 `http://ip:port/mcp/p/名字?token=…`，粘进客户端就能用，不用再单独配请求头；原来那个「复制请求头」按钮去掉了 |
| 「工具包（省 token）」→「工具包」 | 标题和正文里的「省 token」「省三分之一 token」说法都去掉了（省不省 token 是工具包自己的事，不用挂嘴上） |
| 文案进 i18n | 预设会话 / 删除会话 / 复制地址那几句都补了英文和猫娘语词条 |

**顺带修好 ui_dump 读不到界面结构**

`uiautomator dump` 在 Shizuku 后端下会**静默失败**（`app_process` 在那个上下文里起不来，返回码 0 但既没输出也没文件）。现在：

- 挑后端时 **root 优先**（显式传 `backend` 参数时仍按用户说的来）
- dump 失败会在**所有可用的特权后端**上依次重试，换后端成功时在日志里留一条「UI 后端回退」
- 报 idle state 就直接放弃重试 —— 界面停不下来时重试没有意义，不如把诊断信息给出来

---

## v1.0.0-40 ~ 41 · 2026-09-20（补记）

- **v40**：修 `ui_dump` 在部分 ROM 上失败 —— `uiautomator` 加重试 + 输出落到 `/data/local/tmp`
- **v41**：`ui_dump` 报错诊断 —— 把 idle state 的含义和出路写进错误信息里

---

## v1.0.0-39 · 2026-09-20

**新增「UI 自动化」工具包：把手机屏幕当接口用**

8 个工具，默认不激活（省 token），在「权限 → 工具包」里打开或让 AI 用 `activate_pack ui`：

| 工具 | 干什么 |
|---|---|
| `ui_screenshot` | 截屏并把图片给模型看，同时报出屏幕坐标空间（截图被缩放过时会提示换算） |
| `ui_dump` | 读当前界面的控件树：哪些元素能点、文字是什么、中心坐标在哪（text / json 两种格式） |
| `ui_tap` | 点坐标，或按 text / desc / id 自动定位（匹配到多个会列出候选让你选 index） |
| `ui_swipe` | 滑动 / 滚动：给方向（up / down / left / right）或起止坐标，可重复 |
| `ui_input` | 输入文字，**中文自动走剪贴板 + 模拟粘贴**（安卓的 `input text` 只认 ASCII） |
| `ui_key` | 按键：返回 / 主页 / 回车 / 最近任务 / 音量 / 唤醒…也可以直接给数字键值 |
| `ui_launch` | 启动应用、回桌面、看当前前台应用、列已安装应用（找包名） |
| `ui_wait` | 等某个文字出现 / 消失再继续，比固定 sleep 稳 |

- **新增独立权限开关 `ui.control`「控制屏幕」**（默认询问）：可以单独关掉，
  不影响「执行命令」。关掉后这些工具一个都调不动。
- **需要 Root 或 Shizuku**：截屏、读控件树、注入点击都是系统权限，应用自身 UID 做不到。
  拿不到特权身份时这几个工具会**明确说明原因和解决办法**，而不是静默失败。
- 临时文件放在 `<主根目录>/.MCPBox/ui/` 下（截屏 screen.png、控件树 window.xml），
  在文件页里能直接看到。
- `HostInfo` 增加 `setClipboard`，Android 端用系统的剪贴板管理器实现（写剪贴板不受
  后台读取限制，粘贴由前台应用执行）。
- 端到端测试 285 → **299 项**（补了 UI 工具注册、独立权限、无特权后端时的报错文案等）。

---

## v1.0.0-38 · 2026-09-20

**弹窗与下拉菜单恢复原生实现**

v1.0.0-37 里把下拉菜单改成了自绘 `Popup`、弹窗统一成自绘 `AppDialog`（自己排 300/150 的
进出场动画）。实机看下来观感不自然 —— **规矩是「以原生为准」**，所以全部撤回：

- 删掉自绘的 `AppDialog` / `AppAlertDialog` / `M3DropdownMenu` / `MenuRow` / `MenuPositionProvider`
- 下拉菜单回到 M3 的 `DropdownMenu` / `DropdownMenuItem`
- 记忆库的 3 个删除确认回到系统 `AlertDialog`；输入弹窗回到原来的整体限高滚动
- 保留本轮的时长对齐与分批渲染（与观感无关的那部分）

---

## v1.0.0-37 · 2026-09-20

**动画对齐 M3 Expressive 规范 + 日志 / 记忆页分批渲染**

动画（时长全部收进规范阶梯 100 / 150 / 160 / 190 / 200 / 210 / 230 / 260 / 300）：

- 子页面切换 `280 / 240ms` → **`300 / 260ms`**，淡入淡出 `200 / 160` → **`220 / 180`**；
  「工具」和「记忆库」两个二级页面之前比主页慢半拍，现在四个页面一套参数
- 关于页彩蛋的缩放 `130ms` → **`150ms`**（阶梯里没有 130 这一档）

可点击反馈（规范硬规则：必须有反馈，且 ripple 要裁在形状里）：

- 记忆卡、关系行改用 `PressCardBox`：按下底色 **160ms** 渐变 + primary ripple，两者都裁在圆角内
- 补 ripple 的地方：胶囊下拉、胶囊按钮、圆形图标按钮、权限页的常用目录、颜色圆点、非卡片行（`CardRow(card = false)`）

性能（「缓加载」）：

- 日志页、记忆库列表、记忆详情的观察列表改成**分批渲染**：首屏只渲染 60 条，
  滚到离底部约 1.2 屏时再补下一批（用 `withFrameNanos` 让出一帧，不和滚动抢帧），
  列表底部给一行「已显示 x / y 条 · 继续下滑自动加载」
- 记忆库列表的关系匹配从「每张卡扫一遍全表」改成按实体名预索引
- 换筛选 / 换搜索词会回到首批（不会停在「已经展开到最后一批」的状态）

---

## v1.0.0-36 · 2026-09-19

**修：「AI 自己激活工具包」在真机上根本用不了**

实测发现（时间戳日志）：

```
19:27:16  客户端已连接 → 拉一次 tools/list
19:31:15  activate_pack(shell)     ← 之后再也没有 tools/list 请求
          于是 run_shell 这一轮、下一轮都调不到
```

- **根因**：主流 MCP 客户端（RikkaHub 等）**只在连接时拉一次工具列表**，之后再也不拉；
  而服务端也无法主动通知它刷新（没实现 `notifications/tools/list_changed`，
  而且很多客户端也不处理它）。
- 更要命的是：客户端会**按自己缓存的工具表拦调用**。所以"包不影响 `tools/call`"这句
  只在服务端成立 —— 客户端直接报 `Tool not found`，AI 白试一轮。
- 结论：**AI 激活这条路在当前客户端上走不通**。真实的 AI 流程要 5 轮才能写一个文件。

**改法：包回归「用户侧的长期设置」**

- **默认不再让 AI 知道有包这回事**：`initialize` 的说明里去掉工具包段落，
  5 个包管理工具也不再出现在 `tools/list` 里（顺带省约 520 token）。
  AI 看到什么就用什么，零摩擦、不浪费轮次。
- **省 token 的效果一点没少** —— 因为 AI 能看见什么本来就由你的配置决定，
  在权限页勾一次就永久生效，跟 AI 有没有激活无关。
- **新增开关「让 AI 自己开关工具包」（默认关）**：想折腾的人可以打开，
  打开后行为和之前一样（说明 + 5 个包管理工具都回来）。
- **修掉误导性提示**：原来 `activate_pack` 返回里写着「直接按名字调用也是可以的；
  调用不受激活状态限制」—— 这是错的，客户端会拦，AI 会照着反复白试。
  现在改成如实说明「需要客户端重新连接才能生效」，并告诉 AI 被拦了要叫你重连，别重试。
- **关掉开关时，AI 拿着旧缓存来调包工具会收到明确指引**（而不是默默执行），
  告诉它去请用户打开「让 AI 自己开关工具包」。

**界面**

- 「权限 → 工具包」新增开关行，并写清这条硬限制：
  **改完工具包需要重新连接（或重启 App）才会对 AI 生效**。
- 顺手去掉那句同样不准确的说明（"不影响调用"）。

**测试**：端到端 269 → **285 项**全绿。新增一段专门验证默认行为：
关掉时包管理工具全部消失、已激活包的工具照常可见、未激活包的工具不可见、
HTTP 结果与内核计算一致、旧缓存调用被明确挡下、`instructions` 不再提工具包；
打开时这些都反过来。

---

## v1.0.0-35 · 2026-09-19

**修：切走再回来，权限页的会话跳回 default**
- 权限页「工具包」里选的会话之前只存在内存里（`remember`），切到别的 Tab 再回来就被重置成 default
- 现在存进设置，切走再回来、甚至重启 App 都还在原处
- 注意：这只是「我在看哪个会话」的界面状态；AI 实际用哪个会话，由它请求的地址 `/mcp/p/<名字>` 决定

**新：直接告诉你该填什么地址**
- 「工具包」里新增一张卡片，按当前会话算好地址，一键复制：
  ```
  http://192.168.1.7:8720/mcp/p/编码
  ```
- 同时给出推荐的请求头写法（令牌放头里，地址更干净），也能一键复制：
  ```
  Authorization: Bearer <token>
  ```

**新：会话列表显示最近活动时间**
- 选会话时能看到每个会话「刚刚 / 3 分钟前 / 2 小时前 / 还没用过」
- 一眼看出 AI 实际在用哪个会话

**顺手修的**
- harness 段号重复（两个 `[35]`，末尾还有个 `[28]` 倒着排）→ 现在 1~40 连续
- CI 里的步骤名写死了「138 项断言」，实际早就不止 → 去掉数字，免得再过期

---

## v1.0.0-34 · 2026-09-19

**工具包：按需加载工具，省 token**

每个工具的定义都要在**每一轮请求**里重复带给模型。39 个工具 ≈ 7200 token，聊 20 轮就是 14 万。
现在把工具分包，默认只加载「基础 + 文件读取 + 记忆库」，约 4600 token，**省三分之一**。

- 权限页新增「工具包」分组（和权限键并列）：每个包一行，右侧开关控制激活
- 出厂分 5 个包：`core` 基础（常驻，含包管理本身）、`file.read` 文件读取、`memory` 记忆库、
  `file.write` 文件写入、`shell` 命令与自定义工具；用户的工具自动归入 `my.tools` 我的工具
- 可以自己建包：权限页「＋ 新建工具包」，填包名、说明（给 AI 看）、工具名列表
- **包只影响 `tools/list`，不影响 `tools/call`** —— 调任何存在的工具都允许（照常走权限矩阵）。
  这样即使客户端不处理 `notifications/tools/list_changed`，也不会出现「激活了却调不到」的死锁

**AI 可以自己开关包**

新增 5 个工具，前 4 个免审批（它们只改变「能看到什么」，不扩大能力边界）：

| 工具 | 权限 | 作用 |
|---|---|---|
| `list_packs` | 免审批 | 列出所有包、说明、包含哪些工具、是否激活 |
| `activate_pack` | 免审批 | 激活 |
| `deactivate_pack` | 免审批 | 停用 |
| `reset_packs` | 免审批 | 重置当前会话回默认 |
| `manage_pack` | 走「自定义工具」权限 | 新建 / 修改 / 删除自定义包 |

`initialize` 返回的 instructions 里会写明「你看到的工具是分包的」，并列出还没激活的包和它们的用途。

**会话隔离：URL profile + TTL**

MCP 协议层面**无法感知「AI 开了新对话」**（客户端只在启动时 `initialize` 一次，之后所有对话共用同一条连接），
所以用请求路径来区分：

```
/mcp            → 默认会话
/mcp/p/编码     → 编码场景，自己的激活状态
/mcp/p/写作     → 写作场景，自己的激活状态
```

- 状态持久化在 `filesDir/profiles/<名字>.json`，重启 App 也还在
- 权限页顶部能选会话、重置、删除
- **TTL 兜底**（可关）：超过 N 分钟没请求就回到默认，这样「隔一阵开新对话」也是干净的。
  设置 → AI 工具 → 「会话状态自动重置」开关 + 「重置间隔」滑块（5~240 分钟，默认 30）
- 会话名做了防路径穿越处理（`/`、`\`、`..` 一律拒绝）

**测试**：端到端 208 → **269 项**全绿（新增工具包模型、会话状态、TTL、tools/list 过滤、
未激活仍可调用、建包删包等 61 项）

---

## v1.0.0-33 · 2026-09-19

**修了一个记忆库的 bug**
- `add_observations` 在同一批里传了重复的观察时不会去重：它只跟实体上**已有的**观察比对，漏了这批内容**内部**的去重，于是会写进一模一样的重复条目
- 现在先去空、本批去重，再排掉已有的
- 顺手补了回归测试（同一批传 3 条相同内容，只能记 1 条）
- 端到端测试 **208 项**全绿

---

## v1.0.0-32 · 2026-09-18

**工具权限四态：跟随 / 允许 / 询问 / 拒绝**
- 每个工具都能单独设权限，工具详情页点「权限」一行就能选
- 「询问」= 无视全局权限矩阵，这个工具每次调用都弹窗问一次
- 在这种弹窗里选「始终允许 / 始终拒绝」，记住的是**这个工具本身**，不会再顺手改掉全局权限开关
- 「跟随」是默认值；四态都会明确写进配置，所以「改回跟随」不会被出厂默认顶回去

**内置工具的说明可以改**
- 工具详情页新增「说明」组：显示名称 + 给 AI 看的说明，都能自己写，也能一键恢复默认
- 改完立刻生效，AI 在 `tools/list` 里看到的就是新文案（标题、描述、annotations 一起变）
- 只改文案，不影响工具的行为、参数和权限

**新增 `get_token` 工具**
- AI 可以自己领访问令牌，顺带拿到端口、局域网地址和 curl 示例
- **调用它本身不需要令牌**，解开了「要先有 token 才能传文件、但 AI 又拿不到 token」的死循环
- 能拿到令牌属于敏感操作，这个工具**出厂默认「询问」**（可以改成允许/拒绝/跟随）

**记忆库：给 AI 的长期记忆（知识图谱）**
- 新的「记忆库」页面：设置 → AI 工具 → 记忆库
- 结构是「实体 + 观察 + 关系」：实体是一张卡（项目、工具、事件、偏好…），观察是卡上一条条事实，关系把两张卡连起来
- 界面支持搜索、按分区过滤、增删改实体 / 观察 / 关系，点关系可以跳到对方实体
- 配套 10 个 AI 工具：`create_entities`、`create_relations`、`add_observations`、`delete_entities`、`delete_relations`、`delete_observations`、`read_graph`、`search_nodes`、`open_nodes`、`memory_stats`
- 实体类型、分区、关系谓词（`PART_OF` / `HAPPENS_AT` …）都是自由文本，不强制枚举
- 数据存在 `filesDir/memory/graph.json`，原子写入（先写 .tmp 再改名），断电最多丢最后一次写入
- 新增权限分类「记忆库」（默认允许）；设置 → 权限里能整体收走，关掉后这些工具直接从 `tools/list` 消失

**其它**
- 英文语言包补齐 53 条新词条，猫娘语加了几个词
- 端到端测试从 157 项增加到 **197 项**，全绿

---

## v1.0.0-22 · 2026-09-12

第一个发行版 · 把手机变成一台 MCP 文件服务器

**多语言**
- 中文 / English 切换，立即生效；语言包可导出模板、填好后导入
- 彩蛋：关于页连点图标 7 次解锁「猫娘语」

**工具管理**
- 每个工具可单独启用/禁用、单独设权限（允许 / 询问 / 禁止）
- 自定义工具可新建、编辑、导入导出、删除（右上角 `+`）
- 禁用的工具不会出现在 AI 的 `tools/list` 里，也调不动

**权限与审批**
- 6 类权限三态控制 + 路径规则 / 命令规则
- 审批悬浮窗（大圆角主题卡片），支持「记住此命令」自动生成规则
- 根 / Shizuku / 应用沙箱三套执行后端

**外观**
- 种子色驱动整套配色；动态取色用系统壁纸原版 Material You
- 调色板样式（色调点 / 鲜活 / 表现力 / 保真 / 中性 / 单色）、深色 / 浅色 / 跟随系统

**其他**
- 网页控制台（本机限定 + 密码 + 上传/下载）
- 内置终端、日志页、关于页（检查更新 / 开源链接 / 鸣谢）
- 许可：GPL-3.0
