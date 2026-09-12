package com.xtt.mcpbox.core

/** Built-in browser console, handy for testing tools without an AI client. */
object WebConsole {

    fun html(config: Config, server: McpServer): String {
        val token = if (config.tokenEnabled) config.token else ""
        val roots = config.roots.joinToString(" / ")
        return TEMPLATE
            .replace("__TOKEN__", token)
            .replace("__ROOTS__", roots)
            .replace("__VERSION__", ServerMeta.version)
            .replace("__PORT__", config.port.toString())
    }

    /** 登录页：跟 App 里的深色卡片风格保持一致。 */
    fun loginPage(error: Boolean = false, passwordIsToken: Boolean = false): String {
        val errBox = if (error) """<div class="err">密码不对，再试一次</div>""" else ""
        val hint = if (passwordIsToken) {
            "提示：还没单独设置网页密码，这里填 App 里显示的访问令牌（token）就能进。"
        } else {
            "密码可以在 App 的「设置 → 网页控制台 → 访问密码」里改。"
        }
        return """
<!DOCTYPE html>
<html lang="zh-CN"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>MCP 文件盒 · 登录</title>
<style>
  :root { color-scheme: dark; }
  * { box-sizing: border-box; }
  body { margin:0; min-height:100vh; background:#0b0b0d; color:#e8e6ea; display:flex;
         align-items:center; justify-content:center; padding:22px;
         font-family:-apple-system,"PingFang SC","Noto Sans CJK SC",sans-serif; }
  .card { width:100%; max-width:360px; background:#1c1c20; border-radius:26px; padding:24px 22px; }
  h1 { font-size:22px; margin:0 0 6px; }
  p.sub { color:#9e9ea7; font-size:13px; margin:0 0 20px; line-height:1.5; }
  input { width:100%; background:#141416; color:#e8e6ea; border:0; border-radius:16px;
          padding:14px; font-size:15px; margin-bottom:14px; }
  button { width:100%; background:#a8c7fa; color:#0b0b0d; border:0; border-radius:50px;
           padding:14px; font-size:15px; font-weight:600; }
  .err { background:rgba(255,180,171,.14); color:#ffb4ab; font-size:13px;
         border-radius:14px; padding:10px 12px; margin-bottom:14px; }
  .hint { color:#9e9ea7; font-size:12px; margin-top:14px; line-height:1.5; }
</style></head><body>
<form class="card" method="post" action="/login">
  <h1>MCP 文件盒</h1>
  <p class="sub">网页控制台开了密码保护，输入密码才能进。</p>
  $errBox
  <input type="password" name="password" placeholder="访问密码" autofocus autocomplete="current-password">
  <button type="submit">进入控制台</button>
  <p class="hint">$hint</p>
</form>
</body></html>
""".trimIndent()
    }

    private val TEMPLATE = """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>MCP 文件盒 · 控制台</title>
<style>
  :root { color-scheme: dark; }
  * { box-sizing: border-box; }
  body { margin:0; background:#0b0b0d; color:#e8e6ea;
         font-family:-apple-system,"PingFang SC","Noto Sans CJK SC",system-ui,sans-serif; font-size:14px; }
  header { padding:14px 16px; border-bottom:none; position:sticky; top:0; background:#0d1117; z-index:5; }
  h1 { font-size:16px; margin:0 0 6px; font-weight:600; }
  .muted { color:#8b949e; font-size:12px; }
  main { padding:16px; max-width:900px; margin:0 auto; }
  .card { background:#1c1c20; border:none; border-radius:26px; padding:20px; margin-bottom:10px; }
  .card h2 { font-size:14px; margin:0 0 10px; font-weight:600; color:#e6edf3; }
  .row { display:flex; gap:8px; align-items:center; flex-wrap:wrap; }
  .k { color:#8b949e; }
  button { background:#7cd98f; color:#06210d; border:0; border-radius:50px; padding:10px 18px; font-size:13px; font-weight:500; cursor:pointer; }
  button.sec { background:#31313a; color:#e8e6ea; border:none; }
  button.warn { background:transparent; color:#ffb4ab; border:1px solid rgba(255,180,171,.6); }
  button:active { opacity:.8; }
  select, input, textarea { width:100%; background:#0d1117; color:#e6edf3; border:1px solid #30363d;
         border-radius:16px; padding:10px; font-size:13px; font-family:ui-monospace,Menlo,Consolas,monospace; }
  textarea { min-height:110px; resize:vertical; }
  pre { background:#141416; border:none; border-radius:18px; padding:10px; overflow:auto;
        max-height:340px; font-size:12px; white-space:pre-wrap; word-break:break-all; }
  .grid2 { display:grid; grid-template-columns:1fr 1fr; gap:12px; }
  @media (max-width:640px){ .grid2 { grid-template-columns:1fr; } }
  .tag { display:inline-block; padding:1px 7px; border-radius:99px; font-size:11px; background:#21262d; color:#8b949e; }
  .tag.ok { background:#1a7f37; color:#fff; }
  .tag.bad { background:#8b1a17; color:#fff; }
  .pend { border:none; background:#31313a; border-radius:20px; padding:14px; margin-bottom:8px; }
  .logline { font-size:12px; padding:6px 0; border-bottom:none; }
  .dot { width:8px; height:8px; border-radius:50%; display:inline-block; background:#1a7f37; }
</style>
</head>
<body>
<header>
  <h1>MCP 文件盒 · 控制台 <span class="muted">v__VERSION__</span></h1>
  <div class="muted"><span class="dot" id="dot"></span> <span id="stat">连接中…</span></div>
</header>
<main>
  <div class="card">
    <h2>连接信息</h2>
    <div class="muted">MCP 地址（HTTP）：<code id="ep"></code></div>
    <div class="muted">允许目录：__ROOTS__</div>
    <div class="muted">访问令牌：<code id="tk"></code>（客户端需带 <code>Authorization: Bearer</code> 或 <code>?token=</code>）</div>
  </div>

  <div class="card">
    <h2>待审批请求 <span class="tag" id="pendCount">0</span></h2>
    <div id="pendList" class="muted">暂无</div>
  </div>

  <div class="card">
    <h2>工具测试</h2>
    <div class="row" style="margin-bottom:10px">
      <select id="tool" style="flex:2"></select>
      <button class="sec" onclick="fillExample()">填充参数</button>
      <button onclick="run()">执行</button>
    </div>
    <textarea id="args" spellcheck="false">{}</textarea>
    <pre id="result" style="margin-top:10px">（结果会显示在这里）</pre>
  </div>

  <div class="card">
    <h2>最近日志</h2>
    <div id="log" class="muted">加载中…</div>
  </div>
</main>
<script>
const TOKEN = "__TOKEN__";
const H = { 'Content-Type': 'application/json' };
if (TOKEN) H['X-MCP-Token'] = TOKEN;
let TOOLS = [];

function api(path, opts) {
  return fetch(path, Object.assign({ headers: H }, opts || {})).then(r => r.json());
}
function setStat(t){ document.getElementById('stat').textContent = t; }
function tag(ok){ return '<span class="tag ' + (ok ? 'ok' : 'bad') + '">' + (ok ? 'ok' : 'fail') + '</span>'; }

async function refreshStatus(){
  try {
    const s = await api('/api/status');
    document.getElementById('dot').style.background = s.running ? '#1a7f37' : '#8b1a17';
    setStat('运行中 · 端口 ' + s.port + ' · 已运行 ' + s.uptime + ' · 工具 ' + s.tools +
            ' · 会话 ' + s.sessions + ' · 请求 ' + s.stats.total +
            '（允许 ' + s.stats.ok + ' / 失败 ' + s.stats.failed + ' / 审批 ' + s.stats.approvals + '）');
    document.getElementById('ep').textContent = location.origin + '/mcp';
    document.getElementById('tk').textContent = s.token ? s.token : '（未启用）';
  } catch(e) { setStat('无法连接服务器'); }
}

async function refreshTools(){
  try {
    const t = await api('/api/tools');
    TOOLS = t.tools || [];
    const sel = document.getElementById('tool');
    if (sel.options.length !== TOOLS.length) {
      sel.innerHTML = TOOLS.map(x => '<option value="' + x.name + '">' + x.name + ' — ' + x.title + '</option>').join('');
    }
  } catch(e) {}
}

function fillExample(){
  const name = document.getElementById('tool').value;
  const t = TOOLS.find(x => x.name === name);
  if (!t) return;
  const props = (t.inputSchema && t.inputSchema.properties) || {};
  const out = {};
  Object.keys(props).forEach(k => {
    const p = props[k];
    if (props[k].default !== undefined) out[k] = p.default;
    else if (p.type === 'integer') out[k] = 0;
    else if (p.type === 'boolean') out[k] = false;
    else if (p.enum) out[k] = p.enum[0];
    else out[k] = '';
  });
  document.getElementById('args').value = JSON.stringify(out, null, 2);
}

async function run(){
  const name = document.getElementById('tool').value;
  let args = {};
  try { args = JSON.parse(document.getElementById('args').value || '{}'); }
  catch(e) { document.getElementById('result').textContent = '参数不是合法 JSON：' + e.message; return; }
  document.getElementById('result').textContent = '执行中…（若权限为“询问”，手机上会弹窗，请在那里确认）';
  try {
    const r = await api('/api/call', { method:'POST', body: JSON.stringify({ tool: name, arguments: args }) });
    const text = (r.content || []).filter(c => c.type === 'text').map(c => c.text).join('\n');
    const imgs = (r.content || []).filter(c => c.type === 'image').length;
    document.getElementById('result').textContent =
      (r.isError ? '【失败】\n' : '【成功】\n') + text + (imgs ? '\n（附带 ' + imgs + ' 张图片内容）' : '');
  } catch(e) {
    document.getElementById('result').textContent = '请求失败：' + e.message;
  }
  refreshStatus(); refreshLog();
}

async function refreshPending(){
  try {
    const p = await api('/api/pending');
    const list = p.pending || [];
    document.getElementById('pendCount').textContent = list.length;
    const box = document.getElementById('pendList');
    if (!list.length) { box.innerHTML = '<span class="muted">暂无</span>'; return; }
    box.innerHTML = list.map(x =>
      '<div class="pend"><div><b>' + x.summary + '</b></div>' +
      '<div class="muted">工具 ' + x.tool + ' · 权限 ' + x.perm + ' · 来自 ' + (x.client||'?') + '</div>' +
      '<div class="row" style="margin-top:8px">' +
      '<button onclick="approve(\'' + x.id + '\',\'allow_once\')">允许一次</button>' +
      '<button class="sec" onclick="approve(\'' + x.id + '\',\'allow_always\')">始终允许</button>' +
      '<button class="warn" onclick="approve(\'' + x.id + '\',\'deny_once\')">拒绝</button>' +
      '</div></div>').join('');
  } catch(e) {}
}

async function approve(id, decision){
  await api('/api/approve', { method:'POST', body: JSON.stringify({ id: id, decision: decision }) });
  refreshPending();
}

async function refreshLog(){
  try {
    const l = await api('/api/log?limit=40');
    const items = (l.entries || []);
    document.getElementById('log').innerHTML = items.length ? items.map(e => {
      const t = new Date(e.time).toLocaleTimeString('zh-CN');
      return '<div class="logline">' + t + ' ' + tag(e.ok) + ' <span class="muted">[' + e.kind + ']</span> ' +
             (e.tool ? '<b>' + e.tool + '</b> ' : '') + (e.path ? '<span class="muted">' + e.path + '</span> ' : '') +
             e.message + '</div>';
    }).join('') : '<span class="muted">暂无日志</span>';
  } catch(e) {}
}

refreshStatus(); refreshTools(); refreshPending(); refreshLog();
setInterval(refreshStatus, 3000);
setInterval(refreshPending, 2000);
setInterval(refreshLog, 5000);
document.getElementById('tool').addEventListener('change', fillExample);
</script>
</body>
</html>
"""
}
