# MCP 文件盒 · MCPBox

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![构建与签名](https://github.com/xtt-xt/MCPBox/actions/workflows/build.yml/badge.svg)](https://github.com/xtt-xt/MCPBox/actions/workflows/build.yml)

把 Android 手机变成一台 **MCP 文件服务器**：AI 客户端（RikkaHub / Claude Desktop / Cursor / Cline…）通过 MCP 协议读写手机文件、执行命令，而**每一次敏感操作都要经过手机上的悬浮窗审批**。

- 单 APK，无外部依赖：HTTP/MCP 服务器、自带浏览器控制台、常驻终端全部在这一个进程里
- 三档 shell 身份：**应用沙箱** / **Root** / **Shizuku（ADB shell）**
- 权限不是"一次性全允许"，而是 **6 个开关 × 三态 + 路径/命令级规则**，审批弹窗浮在所有 App 之上

```
AI 客户端 ──HTTP(MCP)──▶ 手机上的 MCPBox ──▶ 文件系统 / shell
                              │
                              └─▶ 悬浮窗审批（允许一次 / 始终允许 / 拒绝 / 始终拒绝）
```

## 特性

| | |
|---|---|
| **28 个 MCP 工具** | 文件读写删、搜索、图片预览、回收站、设备信息、执行命令、自定义工具… |
| **逐次审批** | 顶层悬浮窗 + 通知栏兜底，支持「允许一次 / 始终允许 / 拒绝 / 始终拒绝」 |
| **权限矩阵** | 6 个权限键三态（允许 / 询问 / 拒绝）+ 路径规则、命令规则（前缀 / 完全 / 正则） |
| **文件网关** | `POST /upload`、`GET /download`，外加手机浏览器直接可用的上传网页 |
| **应用私有目录** | 可读可写 `/data/data/<包名>`，三档（禁止 / 只读 / 可读写），经 root 转发 |
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

## 权限模型

| 权限键 | 默认 | 说明 |
|---|---|---|
| `fs.read` | 允许 | 读文件、列目录、看图 |
| `fs.write` | 询问 | 写入、修改、新建、复制、移动 |
| `fs.delete` | 询问 | 删除（默认还会先进回收站） |
| `shell.exec` | 询问 | 执行命令（用户自己在终端敲的不算） |
| `tools.manage` | 询问 | 新建/修改/删除自定义工具 |
| `system.info` | 允许 | 设备信息、存储信息 |

**规则（Rule）**可以覆盖开关，粒度更细：

- 路径规则：`/data/data/me.rerere.rikkahub/` → 允许写（最长前缀优先）
- 命令规则：`pm list packages` 前缀 → 允许；`rm -rf` 正则 → 拒绝（按顺序首条命中）

在审批弹窗上点「始终允许 / 始终拒绝」会自动生成对应规则，之后同类操作不再打扰。

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
    ui/                       界面：首页/终端/权限/日志/设置/自定义工具
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
harness/                      端到端测试
```

## 许可

Copyright (C) 2026 xtt

本项目以 **GNU General Public License v3.0**（GPL-3.0）发布，完整条款见 [LICENSE](LICENSE)。


> 本项目依赖均为 Apache-2.0 / MIT 等宽松许可（AndroidX、Jetpack Compose、Kotlin、kotlinx-coroutines、Shizuku），与 GPL-3.0 兼容。
