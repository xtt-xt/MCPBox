# 11 · SSH 服务（内置 SSH / SFTP）

内置 SSH / SFTP 服务端：让 AI 客户端的「SSH 工作区」直接连到手机，用 SFTP 读写手机文件。
不需要装 Termux，也不需要给 AI 下载几个 GB 的 rootfs 工作区。

和 MCP（HTTP）服务是**两个独立功能**：各有各的开关、端口与监听地址，关掉一个不影响另一个。

---

## 1. 怎么开

设置 → **SSH 服务**：

| 项 | 说明 |
|---|---|
| 启用 SSH 服务 | 独立开关。打开后服务随前台服务一起跑；MCP 服务关着也不影响它 |
| 监听端口 | 默认 `8722`（测试变体 `8723`），可改 |
| 允许局域网访问 | 关（默认）时只监听 `127.0.0.1`，手机上的客户端够用；打开才绑 `0.0.0.0` |
| 连接地址 / 主机指纹 | 点一下复制；指纹是 `SHA256:…`，首次连接时和客户端显示的对一下 |
| 登录用户名 | 固定 `mcpbox` |
| 密码登录 | 用户名 + 密码，密码在 App 里生成，可重新生成 |
| 公钥登录 | App 生成一对密钥，**私钥**点开复制填进客户端；也可以贴自己机器上的公钥到「额外公钥」 |
| 允许执行命令 | 默认**关**。关着时客户端只能 SFTP，不能 exec / 开 shell |
| 执行身份 | 自动 / 应用沙箱 / Root / Shizuku（没有对应权限时只会落到应用沙箱） |

两个都关掉（MCP 也关）时前台服务会自己收掉；只开其中一个时它就只为那一个活着。

## 2. 客户端里怎么填

在 RikkaHub 的「新建 SSH 工作区」里：

- 主机 `127.0.0.1`（局域网设备用手机 IP）
- 端口 `8722`
- 用户名 `mcpbox`
- 认证：**私钥**（把 App 里那把私钥整段贴进去）或密码
- 远程目录留空即可（客户端会落在主根目录）；要填就填「允许访问的目录」里的一个绝对路径

> 客户端看到的目录树是**以主根目录为根**的一棵子树（类似 chroot）：它列 `/` 拿到的是主根目录，
> 往下走 `/Download`、`/xtt` 这些都是相对这棵子树的。命中白名单的绝对路径照样能用，
> 所以 `sftp` 命令行直接敲 `/storage/emulated/0/...` 也没问题。不在白名单里的一律拒绝。

命令行验证：

```bash
sftp -i 私钥 -P 8722 mcpbox@127.0.0.1
ssh  -i 私钥 -p 8722 mcpbox@127.0.0.1 'ls /storage/emulated/0'
```

## 3. 安全口径

- **路径仍走同一套白名单**：每次 SFTP 的路径解析都过 `PathSandbox`，越界的（比如 `/data/local/tmp`）
  直接回 `permission denied`，日志里记一条「SSH 拒绝越界路径」。
  口径和 AI 工具完全一致：允许访问的目录、不限制目录、应用私有目录档位都照旧生效。
- **只读模式**（设置 → 安全与审批 → 「只读」）在 SSH 侧同样生效：写、改属性、建目录、重命名、删除全拒，读不受影响。
- **命令执行是独立开关**，默认关；关着时连 command factory 都不装，客户端 exec 直接被拒。
  打开后命令走和 AI `run_shell` 同一个后端体系（应用沙箱 / Root / Shizuku），身份由「执行身份」决定。
- 不接 MCPBox 的审批弹窗：SSH 会话在连接时认证一次，之后按客户端自己的规矩来。
  所以**别把 SSH 端口暴露到公网**，也不要在路由器上做端口转发。

## 4. 实现位置

| 文件 | 干什么 |
|---|---|
| `mcpcore/.../core/SshServer.kt` | 服务端本体：装配 MINA、认证、SFTP 守门、命令执行 |
| `mcpcore/.../core/Config.kt` | `ssh_*` 一组键与 `regenerateSshKey()` |
| `app/.../AppCore.kt` | 装配 `PhoneSshServer`、`syncSsh()` |
| `app/.../server/McpService.kt` | `setMcpEnabled` / `setSshEnabled` / `sync` 三个入口，服务按两个开关对齐 |
| `app/.../ui/SettingsPages.kt` | `SshSettingsPage` 界面 |
| `harness/.../Main.kt` | `[55]` 段：真起一个 SSH 服务端，用 JSch 连上去测认证、SFTP、越界、只读、exec |

## 5. 真机踩过的坑（改之前必读）

1. **`user.home` 不存在**：Android 没有这个系统属性，而 MINA 在 `SshServer.setUpDefaultServer()`
   的静态初始化里就要算 `~/.ssh` 的路径 → `IllegalArgumentException("No user home")`，
   整个 `ServerBuilder` 类初始化失败，之后连重试都会被 JVM 拒绝（`Rejecting re-init`）。
   `PhoneSshServer.bootstrap(homeDir, androidMode = true)` 会在第一次碰 SSHD 之前把它补上。
2. **`javax.management` 不存在**：`ExceptionUtils.peelException` 里有
   `instanceof javax.management.ReflectionException`，安卓没有这个包 → `NoClassDefFoundError`，
   把「取 KeyAgreement」整条路打断。**只设 `org.apache.sshd.androidMode` 属性不管用**
   （MINA 用同一个 value matcher 校验属性值，`"true"` 匹配不上），必须直接调
   `OsUtils.setAndroid(true)`。
3. **客户端把「工作目录」当自己的根**：RikkaHub 打开文件页第一件事是列 `/`，之后每一级都是
   `/Download` 这种「相对它自己那个根」的写法。`GuardedSftpSubsystem.resolveFile` 按 chroot 处理：
   空串 / `.` / `~` / `/` 映射到主根目录，命中白名单的绝对路径原样使用，其余都挂到主根目录下面。
   早先只在 `.` 上做了映射，结果文件页停在 `/` 上一直 `SSH_FX_PERMISSION_DENIED`。
4. **命令不能在 IO 线程上跑**：`AbstractCommandSupport` 的 executor 传 null 时直接在调用线程
   `run()`，那是 Nio2 IO 线程 —— 一条 `waitFor()` 的长命令会让整个服务器之后连 banner 都收不到。
5. **命令的 executor 不能共用**：MINA 在通道结束时会 `close()` / `shutdownNow()` 掉它，
   共用池就会「第一条能跑、之后全拒」。现在每条命令一个单线程执行器，随命令一起收。
6. **只读判定别自己拆 access / flags 的位**：MINA 传进来的两个 int 不是客户端的原始 pflags
   （同一个 put 实测会变成 access=1、flags=258）。用 `FileHandle.getOpenOptions(access, flags)`
   拿标准 open 选项再判断。
7. **会话空闲超时**：默认 10 分钟就断。要传**秒数**（`IDLE_TIMEOUT` 给 Long），塞 `Duration`
   会在计时线程里抛 `NumberFormatException`。
8. **R8**：`app/proguard-rules.pro` 里整包保留 `org.apache.sshd.**`、`net.i2p.crypto.eddsa.**`、
   `org.slf4j.**`，否则 release 包会「连不上但看不出原因」。
9. **MINA 会自己挂死**：真机上遇到过「端口还在 LISTEN、TCP 也能连上，但再也不回 banner」，
   之后所有连接都卡在 accept 队列里。这种状态没法从外面判断，所以服务端自带一个看门狗线程：
   每 30 秒连一次自己的端口、读一行 banner，连续两次读不到就原地重启服务。
   看门狗自己那条连接不写进活动日志。
