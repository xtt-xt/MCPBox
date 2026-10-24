# 11 · SSH service (built-in SSH / SFTP)

A built-in SSH / SFTP server: an AI client's "SSH workspace" can connect straight to the phone
and read/write phone files over SFTP. No Termux, and no multi-GB rootfs workspace download.

It is a **separate feature from the MCP (HTTP) service**: each has its own switch, port and bind
address, and turning one off does not affect the other.

---

## 1. Turning it on

Settings → **SSH service**:

| Item | Meaning |
|---|---|
| Enable SSH service | Independent switch. Runs alongside the foreground service; works even when the MCP service is off |
| Port | Default `8722` (`8723` for the test variant), editable |
| Allow LAN access | Off by default, which binds `127.0.0.1` only — enough for clients on this phone. Turn on to bind `0.0.0.0` |
| Address / host fingerprint | Tap to copy. The fingerprint is `SHA256:…`; compare it on first connect |
| User name | Fixed to `mcpbox` |
| Password login | User name + password generated in the app; can be regenerated |
| Public key login | The app generates a key pair — copy the **private** key into the client, or paste your own public keys under "Extra public keys" |
| Allow running commands | Off by default. When off the client can only use SFTP, no exec / shell |
| Run as | Auto / app sandbox / Root / Shizuku (falls back to the app sandbox without privileges) |

When both switches are off (including MCP) the foreground service shuts itself down; with one of
them on it stays alive just for that one.

## 2. Filling in the client

In RikkaHub's "New SSH workspace":

- Host `127.0.0.1` (or the phone's LAN IP for other devices)
- Port `8722`
- User `mcpbox`
- Auth: **private key** (paste the whole key shown in the app) or password
- Remote directory `/storage/emulated/0` (or anything inside the allowed roots)

Command-line check:

```bash
sftp -i key -P 8722 mcpbox@127.0.0.1
ssh  -i key -p 8722 mcpbox@127.0.0.1 'ls /storage/emulated/0'
```

## 3. Security model

- **Paths go through the same allow-list**: every SFTP path resolution passes `PathSandbox`, so
  anything outside (e.g. `/data/local/tmp`) returns `permission denied` and is logged as
  "SSH blocked a path outside the allowed roots". The allowed roots, unlimited-directory mode and
  the private-directory level all apply exactly as they do for the AI tools.
- **Read-only mode** (Settings → Security → "read only") also applies here: writes, attribute
  changes, mkdir, rename and delete are refused; reads are unaffected.
- **Command execution is a separate switch**, off by default — when off, no command factory is
  installed at all and the client's exec is rejected. When on, commands use the same backends as
  the AI's `run_shell` (app sandbox / Root / Shizuku), picked by "Run as".
- MCPBox approval prompts are **not** involved: the SSH session authenticates once at connect
  time. So keep the SSH port off the public internet and do not port-forward it.

## 4. Where the code lives

| File | Role |
|---|---|
| `mcpcore/.../core/SshServer.kt` | The server itself: MINA wiring, auth, SFTP guard, command execution |
| `mcpcore/.../core/Config.kt` | The `ssh_*` keys and `regenerateSshKey()` |
| `app/.../AppCore.kt` | Wires `PhoneSshServer`, `syncSsh()` |
| `app/.../server/McpService.kt` | `setMcpEnabled` / `setSshEnabled` / `sync`; the service aligns both switches |
| `app/.../ui/SettingsPages.kt` | `SshSettingsPage` |
| `harness/.../Main.kt` | Section `[55]`: starts a real SSH server and drives it with JSch — auth, SFTP, escape attempt, read-only, exec |

## 5. Real-device pitfalls (read before touching this)

1. **`user.home` does not exist** on Android, yet MINA computes the `~/.ssh` path inside the static
   initializer of `SshServer.setUpDefaultServer()` → `IllegalArgumentException("No user home")`.
   The whole `ServerBuilder` class fails to initialise and later retries are rejected by the JVM
   (`Rejecting re-init`). `PhoneSshServer.bootstrap(homeDir, androidMode = true)` sets it before
   anything touches SSHD.
2. **`javax.management` does not exist**: `ExceptionUtils.peelException` does
   `instanceof javax.management.ReflectionException` → `NoClassDefFoundError`, which breaks the
   whole "get KeyAgreement" path. Setting only `org.apache.sshd.androidMode` is **not enough**
   (MINA validates the value with the same matcher, `"true"` doesn't match); call
   `OsUtils.setAndroid(true)` directly.
3. **`realpath .` is rejected by our own allow-list**: clients ask for the current directory first,
   and MINA's default directory is `/`. `GuardedSftpSubsystem.resolveFile` maps empty / `.` / `~`
   to the primary root.
4. **Commands must not run on the IO thread**: with a null executor, `AbstractCommandSupport`
   calls `run()` on the caller thread — a Nio2 IO thread — and one long `waitFor()` blocks the
   whole server until it no longer even sends a banner.
5. **Command executors must not be shared**: MINA calls `close()` / `shutdownNow()` on the
   executor when the channel ends, so a shared pool ends up "first command works, everything
   after is rejected". Each command now gets its own single-thread executor.
6. **Don't decode the access / flags bits yourself** for read-only checks: the two ints MINA passes
   are not the client's raw pflags (a single put shows up as access=1, flags=258). Use
   `FileHandle.getOpenOptions(access, flags)` and inspect the standard open options.
7. **Idle timeout**: sessions drop after 10 minutes by default. Pass **seconds** (a Long) for
   `IDLE_TIMEOUT`; a `Duration` throws `NumberFormatException` inside the timer thread.
8. **R8**: `app/proguard-rules.pro` keeps all of `org.apache.sshd.**`, `net.i2p.crypto.eddsa.**`
   and `org.slf4j.**`, otherwise release builds "cannot connect, with no visible reason".
