/* 前端预览桩服务（仅开发用，不进发布包）
 *
 * 目的：Linux 侧的真实后端没法在 Windows 上跑，但界面改动（日期选择器、
 * 事件分页、按天联动）必须能"看见"才能验收。这里用 Node 原生 http
 * 把 static/ 三个文件发出去，并按 handlers.rs 里**真实的响应结构**造数据。
 *
 * 用法：
 *   node preview/mock-server.js            # 默认 8711
 *   node preview/mock-server.js 9000       # 换端口
 *
 * 注意：造的数据是确定性的（同一个设备 + 同一天永远一样），不是随机乱跳，
 * 这样翻页、切日期时能看出"数据真的跟着换了"而不是"每次刷新都变"。
 */

const http = require('http');
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const STATIC = path.join(ROOT, 'static');
const PORT = Number(process.argv[2] || process.env.PORT || 8711);
const TZ = 8; // UTC+8，和后端 cfg.tz_offset_hours 默认值一致
const HOUR = 3600;

/* ---------------- 时间 ---------------- */

const now = () => Date.now() / 1000;

/** 本地日期字符串 yyyy-MM-dd（不能用 toISOString，那是 UTC） */
function localDay(ts) {
  const d = new Date((ts + TZ * HOUR) * 1000);
  return `${d.getUTCFullYear()}-${String(d.getUTCMonth() + 1).padStart(2, '0')}-${String(d.getUTCDate()).padStart(2, '0')}`;
}

/** 某天 00:00 的 Unix 秒（本地时区） */
function dayStart(day) {
  return Date.parse(`${day}T00:00:00Z`) / 1000 - TZ * HOUR;
}

/** 这一天是几号 —— 用来按中间几天变化数据走势 */
function shiftDay(day, n) {
  const t = dayStart(day) + n * 86400;
  return localDay(t);
}

/* ---------------- 确定性伪随机 ---------------- */

function hash(str) {
  let h = 2166136261;
  for (let i = 0; i < str.length; i++) {
    h ^= str.charCodeAt(i);
    h = Math.imul(h, 16777619);
  }
  return h >>> 0;
}

function rng(seedStr) {
  let a = hash(seedStr);
  return function () {
    a |= 0;
    a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const pick = (r, arr) => arr[Math.floor(r() * arr.length) % arr.length];
const int = (r, lo, hi) => lo + Math.floor(r() * (hi - lo + 1));

/* ---------------- 设备 ---------------- */

const DEVICES = [
  {
    id: 'dev-a1b2c3',
    name: '小明的平板',
    model: 'OPPO Pad Air',
    online: true,
    enabled: true,
    resting: true,
    pin_set: true,
    app_version: '2.4.2',
    use_minutes: 30,
    rest_minutes: 5,
    reset_gap_seconds: 300,
    tamper_policy: true,
    tamper_strikes: 3,   // ×3 惩罚：5 分钟会变成 15 分钟
    pause_media_on_rest: true,
    mute_on_rest: false,
    screen_on: true,
    note: '周一到周五晚上 9 点后不许用',
    ip: '192.168.1.23',
    // 停用时段：22:00 → 次日 06:00（每天）正在生效，另有一条周末白天规则未启用
    in_quiet: true,
    quiet_next: '每天 22:00–06:00',
    quiet_hours: [
      { id: 'q_night', enabled: true, label: '睡觉', from: 22 * 60, to: 6 * 60, days: 0 },
      { id: 'q_noon', enabled: false, label: '午休', from: 12 * 60 + 30, to: 14 * 60, days: 96 },
    ],
  },
  {
    id: 'dev-d4e5f6',
    name: '客厅平板',
    model: 'Redmi Pad SE',
    online: false,
    enabled: true,
    resting: false,
    pin_set: true,
    app_version: '2.4.1',
    use_minutes: 20,
    rest_minutes: 10,
    reset_gap_seconds: 600,
    tamper_policy: false,
    tamper_strikes: 0,
    pause_media_on_rest: true,
    mute_on_rest: true,
    screen_on: false,
    note: '',
    ip: '192.168.1.31',
    in_quiet: false,
    quiet_hours: [],
  },
];

function deviceJson(d, t) {
  return {
    id: d.id,
    owner: 'admin',
    name: d.name,
    model: d.model,
    created_at: t - 26 * 86400,
    last_seen: d.online ? t - 6 : t - 5400,
    last_ip: d.ip,
    app_version: d.app_version,
    enabled: d.enabled,
    resting: d.resting,
    rest_left_ms: d.resting ? 3 * 60 * 1000 + 12000 : 0,
    used_ms: d.resting ? 30 * 60 * 1000 : 18 * 60 * 1000 + 40000,
    today_used_ms: 72 * 60 * 1000,
    rest_count: 3,
    use_minutes: d.use_minutes,
    rest_minutes: d.rest_minutes,
    reset_gap_seconds: d.reset_gap_seconds,
    tamper_policy: d.tamper_policy,
    tamper_strikes: d.tamper_strikes || 0,
    pin_set: d.pin_set,
    screen_on: d.screen_on,
    audio_suppressed: d.resting && d.mute_on_rest,
    pause_media_on_rest: d.pause_media_on_rest,
    mute_on_rest: d.mute_on_rest,
    note: d.note,
    online: d.online,
    offline_seconds: d.online ? 6 : 5400,
    // ---- 停用时段 ----
    quiet_hours: d.quiet_hours || [],
    in_quiet: !!d.in_quiet,
    quiet_left_ms: d.in_quiet ? 92 * 60 * 1000 : 0,
    quiet_label: d.quiet_next || '',
  };
}

/* ---------------- 每天的时间线 / 事件 ---------------- */

const APP_POOL = [
  { pkg: 'com.ss.android.ugc.aweme', label: '抖音' },
  { pkg: 'com.tencent.tmgp.sgame', label: '王者荣耀' },
  { pkg: 'tv.danmaku.bili', label: '哔哩哔哩' },
  { pkg: 'com.tencent.mm', label: '微信' },
  { pkg: 'com.duoduo.baby', label: '学习通' },
  { pkg: 'com.android.chrome', label: 'Chrome' },
  { pkg: 'com.miui.video', label: '小米视频' },
];

const EVENTS_POOL = [
  { kind: 'guard_on', msg: '守护已启动' },
  { kind: 'rest_start', msg: '连续使用 30 分钟，进入强制休息 5 分钟' },
  { kind: 'rest_end', msg: '休息结束，恢复计时' },
  { kind: 'guard_off', msg: '守护被关闭（密码验证通过）' },
  { kind: 'tamper', msg: '检测到疑似强行停止，已记录并追责' },
  { kind: 'command', msg: '收到指令：立即进入休息' },
  { kind: 'command_result', msg: '指令执行成功：立即进入休息' },
  { kind: 'admin_login', msg: '账号 admin 登录后台（192.168.1.8）' },
  { kind: 'pair', msg: '设备配对成功' },
];

/** 某设备某天的原始片段（都是 Unix 秒，和数据库存法一致） */
function dayData(dev, day) {
  const r = rng(`${dev.id}|${day}`);
  const base = dayStart(day);
  const isToday = day === localDay(now());
  const cur = now();

  // 3~5 段"开机使用"时段。
  // 关键：窗口右边界今天是"此刻"，历史天是 22:30 —— 既不能造出未来的使用
  // 记录，也不至于早上打开预览时整条时间轴空着。
  const morning = base + 6 * HOUR;
  const evening = isToday ? Math.max(cur, morning + 2 * HOUR) : base + 22.5 * HOUR;
  const blocks = [];
  const n = int(r, 3, 5);
  const slot = Math.max((evening - morning) / n, 20 * 60);
  for (let i = 0; i < n; i++) {
    const s = morning + i * slot + int(r, 0, Math.max(1, Math.floor(slot * 0.15)));
    const len = Math.floor(slot * (0.45 + r() * 0.3));
    const e = Math.min(s + len, evening);
    if (e > s + 120) blocks.push([s, e]);
  }

  const presence = blocks.map(([s, e]) => ({ start: s, end: e }));

  // 亮屏区间：比"在线时段"稍宽一点（先亮屏、再操作；退出后还要看一眼桌面）。
  // 中间留出的空白就是熄屏 —— 时间轴上那段应该是空白的。
  const screen = blocks.map(([s, e]) => ({
    start: Math.max(base, s - int(r, 30, 90)),
    end: Math.min(base + 24 * HOUR, e + int(r, 60, 240)),
  }));

  const screenMs = screen.reduce((a, s) => a + (s.end - s.start), 0) * 1000;
  const unlocks = screen.length + int(r, 0, 4);
  const boots = isToday && r() > 0.7 ? 1 : int(r, 0, 1);
  const shutdowns = boots;

  // 每个时段内切 2~4 个应用，中间留一点"看桌面"的空隙
  const usage = [];
  for (const [bs, be] of blocks) {
    let t = bs + 30;
    const k = int(r, 2, 4);
    for (let i = 0; i < k && t < be - 60; i++) {
      const app = pick(r, APP_POOL);
      const dur = int(r, 3, Math.max(4, Math.floor((be - t) / 60 / k))) * 60;
      const e = Math.min(t + dur, be);
      usage.push({ start: t, end: e, pkg: app.pkg, label: app.label });
      t = e + int(r, 1, 6) * 60;
    }
  }

  // 事件：跟着时段走，至少几条
  const events = [];
  let id = hash(day + dev.id) % 100000;
  const addEv = (ts, kind, msg) => {
    events.push({ id: id++, device_id: dev.id, kind, message: msg, created_at: Math.round(ts) });
  };
  addEv(blocks.length ? blocks[0][0] - 120 : base + 8 * HOUR, 'guard_on', '守护已启动（开机自启）');
  for (const [bs, be] of blocks) {
    const span = be - bs;
    const rounds = Math.max(1, Math.round(span / (dev.use_minutes * 60 + dev.rest_minutes * 60)));
    for (let i = 0; i < rounds; i++) {
      const s = bs + (i + 1) * dev.use_minutes * 60;
      if (s > be) break;
      addEv(s, 'rest_start', `连续使用 ${dev.use_minutes} 分钟，进入强制休息 ${dev.rest_minutes} 分钟`);
      addEv(s + dev.rest_minutes * 60, 'rest_end', '休息结束，恢复计时');
    }
  }
  const extra = int(r, 1, 3);
  for (let i = 0; i < extra; i++) {
    const ev = pick(r, EVENTS_POOL.slice(4));
    const anchor = blocks.length ? pick(r, blocks) : [base + 9 * HOUR, base + 12 * HOUR];
    addEv(anchor[0] + int(r, 1, Math.max(2, Math.floor((anchor[1] - anchor[0]) / 60))) * 60, ev.kind, ev.msg);
  }
  events.sort((a, b) => b.created_at - a.created_at);

  const onlineMs = presence.reduce((s, p) => s + (p.end - p.start), 0) * 1000;
  const fgMs = usage.reduce((s, u) => s + (u.end - u.start), 0) * 1000;

  // 同 App 聚合（真实后端是先 merge_adjacent 再聚合，这里片段本就不重叠，等价）
  const agg = new Map();
  for (const u of usage) {
    const cur0 = agg.get(u.pkg) || { pkg: u.pkg, label: u.label, ms: 0, count: 0, longest: 0 };
    const d = (u.end - u.start) * 1000;
    cur0.ms += d;
    cur0.count += 1;
    if (d > cur0.longest) {
      cur0.longest = d;
      cur0.label = u.label;
    }
    agg.set(u.pkg, cur0);
  }
  const apps = [...agg.values()]
    .map((a) => ({ pkg: a.pkg, label: a.label, ms: a.ms, count: a.count }))
    .sort((a, b) => b.ms - a.ms);

  return {
    day,
    presence,
    screen,
    usage,
    apps,
    totals: {
      online_ms: Math.round(onlineMs),
      foreground_ms: Math.round(fgMs),
      usage_tracked: true,
      screen_ms: Math.round(screenMs),
      screen_tracked: true,
      unlocks,
      boots,
      shutdowns,
      // 系统口径（设备的 queryAndAggregateUsageStats）：故意留一点点差，
      // 让"一致/偏差"两种显示都能被看到
      sys_ms: Math.round(fgMs * 1.02),
    },
    events,
    used_ms: Math.round(fgMs),
    rest_count: events.filter((e) => e.kind === 'rest_start').length,
  };
}

/** 最近 7 天趋势（升序，和 overview 的 trend 一致） */
function trend(t) {
  const out = [];
  for (let i = 6; i >= 0; i--) {
    const day = localDay(t - i * 86400);
    let used = 0;
    let rest = 0;
    for (const d of DEVICES) {
      const dd = dayData(d, day);
      used += dd.used_ms;
      rest += dd.rest_count;
    }
    out.push({ day, used_ms: used, rest_count: rest });
  }
  return out;
}

/** 近 14 天每日汇总（降序） */
function daily(dev, t) {
  const out = [];
  for (let i = 0; i < 14; i++) {
    const day = localDay(t - i * 86400);
    const dd = dayData(dev, day);
    out.push({ device_id: dev.id, day, used_ms: dd.used_ms, rest_count: dd.rest_count });
  }
  return out;
}

/** 跨设备事件流：按时间倒序 */
function allEvents(t, from, to) {
  const out = [];
  for (const d of DEVICES) {
    // 只扫涉及到的日子，避免造 365 天数据
    for (let i = 0; i < 40; i++) {
      const day = localDay(t - i * 86400);
      const s = dayStart(day);
      if (s > to) continue;
      if (s + 86400 < from) break;
      for (const e of dayData(d, day).events) {
        if (e.created_at >= from && e.created_at < to) out.push(e);
      }
    }
  }
  out.sort((a, b) => b.created_at - a.created_at);
  return out;
}

function paged(list, page, size) {
  const total = list.length;
  const pages = Math.max(1, Math.ceil(total / size));
  const p = Math.min(page, pages);
  return { items: list.slice((p - 1) * size, (p - 1) * size + size), total, page: p, size, pages };
}

/* ---------------- 路由 ---------------- */

function send(res, code, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
  res.end(body);
}

function serveFile(res, file, type) {
  fs.readFile(file, (err, buf) => {
    if (err) {
      res.writeHead(404);
      res.end('not found');
      return;
    }
    res.writeHead(200, { 'Content-Type': type, 'Cache-Control': 'no-store' });
    res.end(buf);
  });
}

/**
 * 预览用的首页。
 *
 * 比直接 serve 静态文件多做一件事：注入一个 token，打开就是已登录。
 * 桩服务的 /api/me 本来也不鉴权，每次预览都要先找口令纯属折腾；
 * 真实后台的登录流程完全不受影响（这个函数只在本文件里用）。
 */
function serveIndex(res) {
  fs.readFile(path.join(STATIC, 'index.html'), 'utf8', (err, html) => {
    if (err) {
      res.writeHead(500);
      res.end('index.html 读取失败');
      return;
    }
    const boot = `<script>try{if(!localStorage.getItem('guardian_admin_token')){`
      + `localStorage.setItem('guardian_admin_token','preview-token');`
      + `localStorage.setItem('guardian_admin_user',JSON.stringify({id:'u-admin',username:'admin',role:'admin',is_admin:true}));`
      + `}}catch(e){}`
      // 带 ?measure 打开时把布局数字写进 title，方便用 --dump-dom 抓出来核对
      // （jsdom 不做布局计算，这类"有没有横向溢出"只能靠真浏览器量）
      + `if(location.search.includes('measure')){setTimeout(function(){`
      + `var de=document.documentElement,out=['W:'+de.scrollWidth+'/'+window.innerWidth];`
      + `document.querySelectorAll('#devicePage .panel, #devicePage .stats, #devicePage .layout, #quietPanel').forEach(function(el,i){`
      + `var r=el.getBoundingClientRect();`
      + `var hd=el.querySelector('.panel-head');`
      + `var tag=(hd?hd.innerText.trim().slice(0,10):(el.id||el.className.split(' ')[0])).replace(/\\s+/g,'_');`
      + `out.push(i+':'+tag+' L'+Math.round(r.left)+' T'+Math.round(r.top)+' W'+Math.round(r.width)+' H'+Math.round(r.height));`
      + `});document.title=out.join(' | ');},2500);}</script>`;
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' });
    res.end(html.replace('</head>', boot + '</head>'));
  });
}

const server = http.createServer((req, res) => {
  const u = new URL(req.url, `http://${req.headers.host}`);
  const p = u.pathname;
  const q = u.searchParams;

  // ---- 静态 ----
  if (p === '/' || p === '/index.html') return serveIndex(res);
  if (p === '/static/app.css') return serveFile(res, path.join(STATIC, 'app.css'), 'text/css; charset=utf-8');
  if (p === '/static/app.js') return serveFile(res, path.join(STATIC, 'app.js'), 'application/javascript; charset=utf-8');
  if (p === '/favicon.ico') {
    res.writeHead(204);
    return res.end();
  }

  const t = now();
  const today = localDay(t);

  // ---- 接口 ----
  if (p === '/healthz') return send(res, 200, { ok: true, time: t });

  if (p === '/api/login') {
    return send(res, 200, {
      token: 'preview-token',
      expires_at: t + 30 * 86400,
      user: { id: 'u-admin', username: 'admin', role: 'admin', is_admin: true },
      using_default_password: false,
    });
  }

  if (p === '/api/me') {
    return send(res, 200, { id: 'u-admin', username: 'admin', role: 'admin', is_admin: true });
  }

  if (p === '/api/overview') {
    const tr = trend(t);
    const todayRow = tr[tr.length - 1];
    return send(res, 200, {
      now: t,
      today,
      user: { id: 'u-admin', username: 'admin', role: 'admin', is_admin: true },
      stats: {
        devices_total: DEVICES.length,
        devices_online: DEVICES.filter((d) => d.online).length,
        devices_resting: DEVICES.filter((d) => d.resting).length,
        devices_off: DEVICES.filter((d) => !d.online).length,
        today_used_ms: todayRow.used_ms,
        today_rest_count: todayRow.rest_count,
      },
      trend: tr,
      devices: DEVICES.map((d) => {
        const dd = dayData(d, today);
        return {
          ...deviceJson(d, t),
          today_used_ms: dd.used_ms,
          today_unlocks: dd.totals.unlocks,
          today_boots: dd.totals.boots,
          today_shutdowns: dd.totals.shutdowns,
          today_screen_ms: dd.totals.screen_ms,
          today_sys_ms: dd.totals.sys_ms,
        };
      }),
      events: allEvents(t, t - 48 * HOUR, t + 60).slice(0, 60),
      events_window_hours: 48,
      using_default_password: false,
    });
  }

  if (p === '/api/devices') {
    return send(res, 200, {
      devices: DEVICES.map((d) => {
        const dd = dayData(d, today);
        return {
          ...deviceJson(d, t),
          today_used_ms: dd.used_ms,
          today_unlocks: dd.totals.unlocks,
          today_boots: dd.totals.boots,
          today_shutdowns: dd.totals.shutdowns,
          today_screen_ms: dd.totals.screen_ms,
          today_sys_ms: dd.totals.sys_ms,
        };
      }),
    });
  }

  if (p === '/api/users') {
    return send(res, 200, {
      users: [
        { id: 'u-admin', username: 'admin', role: 'admin', is_admin: true, disabled: false, note: '内置管理员', created_at: t - 30 * 86400, last_login: t - 600, device_count: 2 },
        { id: 'u-mom', username: 'mama', role: 'user', is_admin: false, disabled: false, note: '', created_at: t - 20 * 86400, last_login: t - 3600, device_count: 0 },
      ],
    });
  }

  // /api/events —— 全部事件（按天或按小时）
  if (p === '/api/events') {
    const size = Math.min(200, Math.max(1, Number(q.get('size') || 50)));
    const page = Math.max(1, Number(q.get('page') || 1));
    const day = (q.get('day') || '').trim();
    const hours = Math.min(8784, Math.max(1, Number(q.get('hours') || 48)));
    let from;
    let to;
    if (day) {
      if (!/^\d{4}-\d{2}-\d{2}$/.test(day)) return send(res, 400, { detail: '日期格式应为 yyyy-MM-dd' });
      from = dayStart(day);
      to = from + 86400;
    } else {
      to = t + 60;
      from = t - hours * HOUR;
    }
    let list = allEvents(t, from, to);
    if (q.get('device')) list = list.filter((e) => e.device_id === q.get('device'));
    const r = paged(list, page, size);
    return send(res, 200, {
      events: r.items,
      total: r.total,
      page: r.page,
      size: r.size,
      pages: r.pages,
      from,
      to,
      day: day || null,
      today,
    });
  }

  // /api/devices/{id}/timeline
  let m = p.match(/^\/api\/devices\/([^/]+)\/timeline$/);
  if (m) {
    const dev = DEVICES.find((d) => d.id === m[1]);
    if (!dev) return send(res, 404, { detail: '设备不存在' });
    const day = (q.get('day') || today).trim();
    if (!/^\d{4}-\d{2}-\d{2}$/.test(day)) return send(res, 400, { detail: '日期格式应为 yyyy-MM-dd' });
    const dd = dayData(dev, day);
    const from = dayStart(day);
    return send(res, 200, {
      day,
      today,
      tz: TZ,
      from,
      to: from + 86400,
      presence: dd.presence,
      usage: dd.usage,
      screen: dd.screen,
      apps: dd.apps,
      totals: dd.totals,
    });
  }

  // /api/devices/{id}/events
  m = p.match(/^\/api\/devices\/([^/]+)\/events$/);
  if (m) {
    const dev = DEVICES.find((d) => d.id === m[1]);
    if (!dev) return send(res, 404, { detail: '设备不存在' });
    const size = Math.min(200, Math.max(1, Number(q.get('size') || 20)));
    const page = Math.max(1, Number(q.get('page') || 1));
    const day = (q.get('day') || today).trim();
    if (!/^\d{4}-\d{2}-\d{2}$/.test(day)) return send(res, 400, { detail: '日期格式应为 yyyy-MM-dd' });
    const dd = dayData(dev, day);
    const r = paged(dd.events, page, size);
    const from = dayStart(day);
    return send(res, 200, {
      events: r.items,
      total: r.total,
      page: r.page,
      size: r.size,
      pages: r.pages,
      from,
      to: from + 86400,
      day,
      today,
    });
  }

  // /api/devices/{id}
  m = p.match(/^\/api\/devices\/([^/]+)$/);
  if (m) {
    const dev = DEVICES.find((d) => d.id === m[1]);
    if (!dev) return send(res, 404, { detail: '设备不存在' });
    const dd = dayData(dev, today);
    return send(res, 200, {
      device: deviceJson(dev, t),
      daily: daily(dev, t),
      commands: [
        { id: 12, device_id: dev.id, kind: 'rest_now', params: {}, state: 'done', created_at: t - 900 },
        { id: 11, device_id: dev.id, kind: 'config', params: { use_minutes: 30 }, state: 'done', created_at: t - 7200 },
      ],
      events: dd.events.slice(0, 30),
      today: {
        day: today,
        apps: dd.apps,
        online_ms: dd.totals.online_ms,
        usage_tracked: true,
        screen_tracked: true,
        unlocks: dd.totals.unlocks,
        boots: dd.totals.boots,
        shutdowns: dd.totals.shutdowns,
        screen_ms: dd.totals.screen_ms,
        sys_ms: dd.totals.sys_ms,
      },
    });
  }

  if (p.startsWith('/api/devices/') && p.endsWith('/command')) {
    return send(res, 200, { ok: true, accepted: 1 });
  }

  if (p.startsWith('/api/')) {
    // 其余接口一律给个"成功"，免得前端弹错干扰看界面
    return send(res, 200, { ok: true });
  }

  res.writeHead(404);
  res.end('not found');
});

server.listen(PORT, '127.0.0.1', () => {
  console.log(`预览桩服务：http://127.0.0.1:${PORT}/`);
  console.log('账号 admin / 密码随便填（桩服务不校验）');
  console.log('设备：dev-a1b2c3（在线·休息中） / dev-d4e5f6（离线）');
});
