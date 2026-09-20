# MCP 文件盒 · MCPBox

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![构建与签名](https://github.com/xtt-xt/MCPBox/actions/workflows/build.yml/badge.svg)](https://github.com/xtt-xt/MCPBox/actions/workflows/build.yml)

把 Android 手机变成一台 **MCP 文件服务器**：AI 客户端（RikkaHub / Claude Desktop / Cursor / Cline…）通过 MCP 协议读写手机文件、执行命令，而**每一次敏感操作都要经过手机上的悬浮窗审批**。

- 单 APK，无外部依赖：HTTP/MCP 服务器、自带浏览器控制台、常驻终端全部在这一个进程里
- 三档 shell 身份：**应用沙箱** / **Root** / **Shizuku（ADB shell）**
- 权限不是"一次性全允许"，而是 **8 个开关 × 三态 + 路径/命令级规则**，审批弹窗浮在所有 App 之上

```
AI 客户端 ──HTTP(MCP)──▶ 手机上的 MCPBox ──▶ 文件系统 / shell
                              │
                              └─▶ 悬浮窗审批（允许一次 / 始终允许 / 拒绝 / 始终拒绝）
```

## 特性

| | |
|---|---|
| **52 个 MCP 工具** | 文件读写删、搜索、图片预览、回收站、设备信息、执行命令、自定义工具、令牌、记忆库、工具包、UI 自动化… |
| **工具包（省 token）** | 工具分 7 个包，默认只带 22 个（≈4100 token，比全量省 52%）；包由你在 App 里配置，AI 无感 |
| **会话隔离** | 客户端地址填 `/mcp/p/<名字>` 就是独立会话，各有各的激活状态，可持久化 / 重置 |
| **逐次审批** | 顶层悬浮窗 + 通知栏兜底，支持「允许一次 / 始终允许 / 拒绝 / 始终拒绝」 |
| **权限矩阵** | 8 个权限键三态（允许 / 询问 / 拒绝）+ 路径规则、命令规则（前缀 / 完全 / 正则） |
| **工具级权限四态** | 单个工具可单独设「跟随 / 允许 / 询问 / 拒绝」，其中「询问」无视全局矩阵、每次都弹窗 |
| **记忆库** | 给 AI 的长期记忆：实体 + 观察 + 关系（知识图谱），能在 App 里浏览和编辑 |
| **文件网关** | `POST /upload`、`GET /download`，外加手机浏览器直接可用的上传网页 |
| **应用私有目录** | 可读可写 `/data/data/<包名>`，三档（禁止 / 只读 / 可读写），经 root 转发 |
| **UI 自动化** | 截屏 + 读界面结构（控件树/坐标）+ 点击 / 滑动 / 输入（含中文）/ 按键 / 启应用 / 等元素，需要 Shizuku 或 Root |
| **内置终端** | 常驻 shell，`cd`/`export` 状态保留、命令历史、Ctrl-C 中断、清屏 |
| **自定义工具** | 用命令模板给自己造新 MCP 工具，支持参数占位与 JSON 导入导出 |
| **网页控制台** | 浏览器里试工具、看日志、处理审批；支持**仅本机访问**与**密码保护** |
| **两种传输** | Streamable HTTP（`/mcp`）与旧版 HTTP+SSE（`/sse` + `/messages`） |

## 快速开始

1. 安装 APK，打开 App → 首页点开服务（默认端口 `8720`）
2. 给文件访问权限、悬浮窗权限、通知权限（首页「环境检查」会逐项提示）
3. 在 AI 客户端里添加 MCP 服务器：

```
类型：Streamable HTTP（推荐）或 SSE
地址：http://<手机局域网IP>:8720/mcp
令牌：App 首页「连接地址」里复制的那个（含 token 的完整 URL）
```

4. 需要 shell 权限时，去「终端」页申请 Shizuku 授权（可选：Root 会自动识别）

## MCP 工具

**文件（20）**：`list_dir` `directory_tree` `file_info` `read_file` `read_image` `search_files` `file_hash` `write_file` `edit_file` `make_dir` `copy_path` `move_path` `delete_path` `list_trash` `restore_trash` `empty_trash` `server_info` `get_device_info` `storage_info` `notify_user`

**Shell / 扩展（8）**：`run_shell` `shell_info` `create_custom_tool` `update_custom_tool` `delete_custom_tool` `list_custom_tools` `export_custom_tools` `import_custom_tools`

**UI 自动化（8）**：`ui_screenshot` `ui_dump` `ui_tap` `ui_swipe` `ui_input` `ui_key` `ui_launch` `ui_wait`
（需要 Root 或 Shizuku：截屏、读控件树、注入点击都是系统权限，应用自身 UID 做不到。
`ui_input` 输中文时自动走「写剪贴板 → 模拟粘贴」）

**令牌（1）**：`get_token`（调用它自己不需要令牌，用于解开「AI 要先拿到 token 才能传文件」的死循环）

**工具包（5）**：`list_packs` `activate_pack` `deactivate_pack` `reset_packs`（免审批，只影响可见性）、`manage_pack`（建/改/删自定义包，走「自定义工具」权限）

**记忆库（10）**：`create_entities` `create_relations` `add_observations` `delete_entities` `delete_relations` `delete_observations` `read_graph` `search_nodes` `open_nodes` `memory_stats`

## 权限模型

| 权限键 | 默认 | 说明 |
|---|---|---|
| `fs.read` | 允许 | 读文件、列目录、看图 |
| `fs.write` | 询问 | 写入、修改、新建、复制、移动 |
| `fs.delete` | 询问 | 删除（默认还会先进回收站） |
| `shell.exec` | 询问 | 执行命令（用户自己在终端敲的不算） |
| `tools.manage` | 询问 | 新建/修改/删除自定义工具 |
| `ui.control` | 询问 | 控制屏幕：截屏、读界面结构、点击、滑动、输入（需要 Root / Shizuku） |
| `system.info` | 允许 | 设备信息、存储信息、获取令牌 |
| `memory` | 允许 | 记忆库的读写（关掉总开关后记忆工具直接从 `tools/list` 消失） |

**规则（Rule）**可以覆盖开关，粒度更细：

- 路径规则：`/data/data/me.rerere.rikkahub/` → 允许写（最长前缀优先）
- 命令规则：`pm list packages` 前缀 → 允许；`rm -rf` 正则 → 拒绝（按顺序首条命中）

在审批弹窗上点「始终允许 / 始终拒绝」会自动生成对应规则，之后同类操作不再打扰。

单个工具还能在「设置 → AI 工具 → 工具管理」里单独设权限，四态含义：

| 取值 | 含义 |
|---|---|
| 跟随（默认） | 按上面的全局权限矩阵走 |
| 允许 | 这个工具的所有操作直接放行，不弹审批 |
| 询问 | **无视全局矩阵**，这个工具每次调用都弹窗问你一次 |
| 拒绝 | 无论全局怎么设，一律拒绝 |

在「询问」触发的弹窗里选「始终允许 / 始终拒绝」，记住的是**这个工具本身**，不会改动全局权限开关。

## 记忆库

给 AI 的长期记忆，结构是经典的知识图谱三元组：

| 概念 | 说明 |
|---|---|
| **实体** | 一个节点：项目、工具、事件、人物、用户偏好… 名字唯一 |
| **观察** | 挂在实体身上的一条条事实短句（增删时自动去重） |
| **关系** | 两个实体之间的有向边：`from ──PART_OF──▸ to` |

实体类型、分区（folder）、关系谓词都是**自由文本**，不强制枚举 —— AI 自己会自然长出
「项目事实 / 用户偏好 / 事件」这类结构，关系谓词一般用大写下划线（`PART_OF`、`HAPPENS_AT`、
`INVOLVES`、`CORRECTS`、`UPDATES`…）。

- 界面：**设置 → AI 工具 → 记忆库**。支持搜索、按分区过滤，点进实体可以改名 / 改类型 /
  改分区、增删观察、增删关系；点关系能跳到对方实体。
- 存储：`filesDir/memory/graph.json`，原子写入（先写 `.tmp` 再改名），断电最多丢最后一次写入。
- 搜索 `search_nodes` 会连带返回命中实体的**直接邻居**，方便顺着线索往下读。
- 重名实体只跳过不覆盖；重命名会自动同步关系两端；删实体会连带删掉它的所有关系。

## 工具包（省 token）

工具定义要在**每一轮请求**里重复带给模型。全部 52 个工具大约 8600 token，
聊 20 轮就是 15 万——而且大部分轮次根本用不到那么多工具。

所以把工具分包，`tools/list` 只返回**基础包 + 已激活包**里的工具：

| 包 | 工具数 | 出厂 |
|---|---|---|
| `core` 基础 | 4 | **常驻** |
| `file.read` 文件读取 | 8 | 开 |
| `memory` 记忆库 | 10 | 开 |
| `file.write` 文件写入 | 9 | 关 |
| `shell` 命令与自定义工具 | 8 | 关 |
| `ui` UI 自动化 | 8 | 关 |
| `my.tools` 我的工具 | 动态 | 关 |

默认 22 个工具 ≈ 4100 token，**比全带上省 52%**。

包是**你自己的长期设置**：在「权限 → 工具包」里勾好，AI 看到什么就用什么，
完全不知道有包这回事 —— 零摩擦，也不浪费对话轮次。也可以自己建包
（App 里「＋ 新建工具包」，或让 AI 用 `manage_pack`）：把常用工具组合起来，起个名字、写清用途。

> **⚠️ 改完要重连才生效**
> 主流 MCP 客户端**只在连接时拉一次工具列表**，之后不会再拉（服务端也没法通知它刷新）。
> 所以改完工具包后，需要重新连接 MCP 服务（或重启 App），AI 才会看到变化。

### 可选：让 AI 自己开关包（默认关）

「权限 → 工具包」里有这个开关。打开后 AI 能看到并调用 5 个包管理工具
（`list_packs` / `activate_pack` / `deactivate_pack` / `reset_packs` / `manage_pack`），
`initialize` 的说明里也会列出还没激活的包。

**为什么不默认开**：实测主流客户端只在连接时拉一次工具列表，而客户端还会按自己缓存的
工具表**拦截调用** —— 所以 AI 激活了一个包，这一轮、下一轮都调不到里面的工具，
白白浪费好几轮对话。默认关掉，既省下那 5 个工具的 token，也不给 AI 挖坑。

### 会话隔离：`/mcp/p/<名字>`

MCP 协议层面**无法感知「AI 开了新对话」**——客户端只在启动时 `initialize` 一次，
之后所有对话共用同一条连接。所以用请求路径来区分：

```
/mcp            → 默认会话
/mcp/p/编码     → 编码场景（可以自己激活 file.write + shell）
/mcp/p/写作     → 写作场景（只要 memory）
```

每份状态存在 `filesDir/profiles/<名字>.json`，重启 App 也保留；
在权限页可以选会话、重置、删除。

**TTL 兜底**（可关，默认 30 分钟）：超过这段时间没请求就回到默认包集，
这样「隔一阵开新对话」拿到的也是干净状态。设置 → AI 工具 →「会话状态自动重置」。

## 文件进出通道

不依赖 root，也不需要数据线：

```bash
# 推文件进手机（原始 body 即文件内容）
curl -X POST --data-binary @app.apk \
  "http://<手机IP>:8720/upload?path=/storage/emulated/0/xtt/app/mcp/app.apk&token=<token>"

# 取文件出手机
curl -o back.apk \
  "http://<手机IP>:8720/download?path=/storage/emulated/0/xtt/app/mcp/app.apk&token=<token>"
```

上传走「写文件」权限、下载走「读文件」权限，照旧弹审批。手机浏览器打开 `http://127.0.0.1:8720/upload` 还有一个上传网页（选文件 + 填路径即可）。

## 应用私有目录

应用自身读不到别家私有目录，所以开启后会**经 root / Shizuku 转发**：

- 三档：**禁止**（默认）/ **只读** / **可读写**
- 覆盖范围：`/data/data`、`/data/user/0`、`/data/user_de/0`、`/data/local/tmp`、`/data/app`、`/data/misc`、`/data/system`、`/data/adb`
- 想只放开某一个应用，用路径规则单独设置；只读模式下写/删会被直接拒绝
- Android 15+ 的 mount namespace 隔离会让 root 也"看不全"应用数据，本项目会自动探测 `su -mm` / `su -M` 进入 global namespace

## 网页控制台安全

| 开关 | 作用 |
|---|---|
| 仅本机访问 | 只响应 `127.0.0.1` / `::1`，局域网设备一律 403 |
| 密码保护 | 浏览器需登录（cookie 会话 12 小时）；程序用 token 调用不受影响 |
| 访问密码 | 单独设置，留空则用访问令牌（token）当密码 |

## 架构

```
mcpcore/   纯 Kotlin/JVM 零 Android 依赖：HTTP 服务器、MCP 协议、工具实现、
           权限矩阵、文件桥、文件网关（可单独跑、可测）
harness/   端到端测试：真起一个 HTTP 服务器，用真请求打所有工具与安全策略
app/       Android 层：Compose UI、前台服务、悬浮窗审批、Shizuku 接入、终端
```

## 构建

```bash
# 端到端测试（138 项断言）
./gradlew :harness:e2e

# 构建
./gradlew :app:assembleDebug        # 调试包
./gradlew :app:assembleRelease      # 正式包（无密钥时产出未签名 APK）
```

要求：JDK 17、Android SDK（`compileSdk 35`）。Gradle 用仓库自带的 wrapper（8.13）。

## 签名与发布

签名信息**不入库**（`keystore/` 已在 `.gitignore` 里）。构建时按环境变量读取：

| 环境变量 | 说明 |
|---|---|
| `KEYSTORE_PATH` | 密钥路径，默认 `keystore/release.keystore` |
| `KEYSTORE_PASSWORD` | keystore 密码 |
| `KEY_ALIAS` | 别名 |
| `KEY_PASSWORD` | 别名密码 |

**没有密钥也能构建** —— 只是 release 包不会被签名。

### GitHub Actions

仓库自带 [`.github/workflows/build.yml`](.github/workflows/build.yml)：推送到 `main`、发 PR 或打 `v*` 标签时自动跑测试 + 构建，产物在 Actions 的 Artifacts 里；打标签时还会自动创建 Release 并附上 APK。

想让 CI 产出**已签名**的 APK，在仓库 **Settings → Secrets and variables → Actions** 里加四个 Secret：

| Secret | 怎么填 |
|---|---|
| `KEYSTORE_BASE64` | `base64 -w0 keystore/release.keystore`（本机执行后整段粘贴） |
| `KEYSTORE_PASSWORD` | keystore 密码 |
| `KEY_ALIAS` | 别名 |
| `KEY_PASSWORD` | 别名密码 |

发布一个版本：

```bash
git tag v1.6.4 && git push origin v1.6.4
```

## 目录结构

```
app/                          Android 应用（Compose UI + 服务 + 悬浮窗）
  src/main/java/com/xtt/mcpbox/
    ui/                       界面：首页/终端/权限/日志/设置/工具管理/记忆库
    server/                   前台服务、审批广播、开机自启
    AndroidHost.kt            设备信息、通知等系统能力
    ShizukuShell.kt           Shizuku 进程启动器
mcpcore/src/main/kotlin/com/xtt/mcpbox/core/
  HttpServer.kt               迷你 HTTP/1.1（keep-alive、chunked、SSE）
  McpServer.kt                路由、鉴权、MCP 协议、网页控制台安全
  McpTools.kt / ToolsRead.kt / ToolsWrite.kt / ToolsShell.kt
  Approval.kt                 权限矩阵与审批中心
  PathSandbox.kt / TrashManager.kt / FileBridge.kt / FileGateway.kt
  Shell.kt                    shell 后端（应用 / root / Shizuku）
  WebConsole.kt / Config.kt / CustomTools.kt / EventLog.kt
  Memory.kt                   记忆库：实体 + 观察 + 关系，原子写入 graph.json
  ToolPack.kt                 工具包：内置包定义 + 自定义包存储
  ToolsUi.kt                  UI 自动化：截屏 / 控件树 / 点击 / 滑动 / 输入
  ProfileStore.kt             会话（URL profile）：激活状态 + TTL，原子写盘
  ToolsPacks.kt / ToolsMemory.kt / ToolsToken.kt / ToolMeta.kt / ToolPolicy.kt / LocalNet.kt
harness/                      端到端测试
```

## 许可

Copyright (C) 2026 xtt

本项目以 **GNU General Public License v3.0**（GPL-3.0）发布，完整条款见 [LICENSE](LICENSE)。


> 本项目依赖均为 Apache-2.0 / MIT 等宽松许可（AndroidX、Jetpack Compose、Kotlin、kotlinx-coroutines、Shizuku），与 GPL-3.0 兼容。
