/* 使用守护 · 管理后台前端（原生 JS，无依赖）
 *
 * 和后端的约定：
 *   - 管理端所有接口都要带 Authorization: Bearer <token>
 *   - 设备状态由设备心跳上报，页面每 5 秒拉一次 /api/overview
 *   - 远程操作 = 往队列里放一条指令，设备下次心跳（20 秒内）取走执行
 *   - 权限：admin 能看全部设备和管理账号；user 只能看归属自己的设备，
 *     越权访问会得到 404（不是 403，避免用它探测别人有哪些设备）
 */

const TOKEN_KEY = 'guardian_admin_token';
const USER_KEY = 'guardian_admin_user';

let token = localStorage.getItem(TOKEN_KEY) || '';
let me = (() => {
  try { return JSON.parse(localStorage.getItem(USER_KEY) || 'null'); } catch (e) { return null; }
})();

let overview = null;
let allUsers = [];
let pollTimer = null;

/* ---------------- 工具 ---------------- */

const $ = (id) => document.getElementById(id);

function esc(s) {
  return String(s == null ? '' : s).replace(/[&<>"']/g, (c) => (
    { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]
  ));
}

function fmtDur(ms) {
  const v = ms || 0;
  // 不到 1 分钟就显示秒 —— 否则"用了 40 秒"会显示成 0 分钟，看起来像没统计到
  if (v < 60000) {
    const s = Math.round(v / 1000);
    return s > 0 ? s + ' 秒' : '0 分钟';
  }
  const m = Math.round(v / 60000);
  if (m < 60) return m + ' 分钟';
  const h = Math.floor(m / 60);
  const r = m % 60;
  return r ? `${h} 小时 ${String(r).padStart(2, '0')} 分` : `${h} 小时`;
}

/**
 * 「拆防护」惩罚的倍数，必须和 App 端 Prefs.penaltyMsFor 一致：
 * 第 1 次原长，第 2 次 ×2，第 3 次起 ×3。
 *
 * 家长设了 5 分钟却休息 15 分钟，就是这里被 ×3 了 —— 所以界面上要能看见倍数。
 */
function penaltyFactor(strikes) {
  const n = Number(strikes) || 0;
  if (n <= 1) return 1;
  return n === 2 ? 2 : 3;
}

function fmtShort(ms) {
  const v = ms || 0;
  if (v < 60000) return Math.round(v / 1000) + 's';
  const m = Math.round(v / 60000);
  if (m < 60) return m + 'm';
  return `${Math.floor(m / 60)}h${String(m % 60).padStart(2, '0')}`;
}

function fmtClock(ts) {
  if (!ts) return '--:--';
  const d = new Date(ts * 1000);
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
}

function fmtDate(ts) {
  if (!ts) return '从未';
  const d = new Date(ts * 1000);
  return `${d.getMonth() + 1}/${d.getDate()} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
}

function fmtAgo(sec) {
  if (sec == null) return '从未连接';
  if (sec < 10) return '刚刚';
  if (sec < 60) return sec + ' 秒前';
  if (sec < 3600) return Math.floor(sec / 60) + ' 分钟前';
  if (sec < 86400) return Math.floor(sec / 3600) + ' 小时前';
  return Math.floor(sec / 86400) + ' 天前';
}

/* ---------------- 日期工具 ---------------- */

/** 本地日期 → yyyy-MM-dd（不能用 toISOString，那会按 UTC 切，容易差一天） */
function dayStr(d) {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

function todayStr() {
  return dayStr(new Date());
}

function shiftDay(day, delta) {
  const [y, m, d] = day.split('-').map(Number);
  const t = Date.UTC(y, m - 1, d) + delta * 86400000;
  const dt = new Date(t);
  return `${dt.getUTCFullYear()}-${String(dt.getUTCMonth() + 1).padStart(2, '0')}-${String(dt.getUTCDate()).padStart(2, '0')}`;
}

/**
 * 事件时间戳带日期：今天显示「今天 14:22」，今年内显示「10-23 14:22」，
 * 跨年显示「2025-10-23 14:22」。
 *
 * 事件是长期累积的，光有 HH:MM 看不出是哪天 —— 一天的事件里混进前几天的会很误导。
 */
function fmtStamp(ts, todayIso) {
  if (!ts) return '--';
  const d = new Date(ts * 1000);
  const day = dayStr(d);
  const today = todayIso || todayStr();
  const hm = `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
  if (day === today) return '今天 ' + hm;
  if (day === shiftDay(today, -1)) return '昨天 ' + hm;
  const md = day.slice(5).replace('-', '-');
  if (day.slice(0, 4) === today.slice(0, 4)) return `${md} ${hm}`;
  return `${day} ${hm}`;
}

/** 「今天 / 昨天 / 10-21」这种给人看的短标签 */
function dayLabel(day, todayIso) {
  const today = todayIso || todayStr();
  if (day === today) return '今天';
  if (day === shiftDay(today, -1)) return '昨天';
  if (day === shiftDay(today, -2)) return '前天';
  return day.slice(5);
}

function toast(msg) {
  const el = $('toast');
  el.textContent = msg;
  el.classList.remove('hidden');
  clearTimeout(el._t);
  el._t = setTimeout(() => el.classList.add('hidden'), 2800);
}

/* ---------------- 请求 ---------------- */

async function api(path, opts = {}) {
  const headers = Object.assign({ 'Content-Type': 'application/json' }, opts.headers || {});
  if (token) headers['Authorization'] = 'Bearer ' + token;

  const res = await fetch(path, Object.assign({}, opts, { headers }));
  if (res.status === 401) {
    logoutLocal();
    throw new Error('登录已过期');
  }
  let data = null;
  try { data = await res.json(); } catch (e) { /* 无 body */ }
  if (!res.ok) {
    throw new Error((data && (data.detail || data.message)) || ('请求失败 ' + res.status));
  }
  return data;
}

/* ---------------- 登录 ---------------- */

function showLogin() {
  $('app').classList.add('hidden');
  $('login').classList.remove('hidden');
  setTimeout(() => {
    const u = $('username');
    (u.value ? $('pwd') : u).focus();
  }, 60);
}

function logoutLocal() {
  token = '';
  me = null;
  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(USER_KEY);
  clearInterval(pollTimer);
  clearInterval(deviceTimer);
  document.querySelectorAll('.modal').forEach((m) => m.classList.add('hidden'));
  $('devicePage').classList.add('hidden');
  showLogin();
}

$('loginForm').addEventListener('submit', async (e) => {
  e.preventDefault();
  $('loginErr').textContent = '';
  try {
    const data = await api('/api/login', {
      method: 'POST',
      body: JSON.stringify({
        username: $('username').value.trim(),
        password: $('pwd').value
      })
    });
    token = data.token;
    me = data.user;
    localStorage.setItem(TOKEN_KEY, token);
    localStorage.setItem(USER_KEY, JSON.stringify(me));
    $('pwd').value = '';
    enterApp();
  } catch (err) {
    $('loginErr').textContent = err.message;
  }
});

$('btnLogout').addEventListener('click', async () => {
  try { await api('/api/logout', { method: 'POST' }); } catch (e) { /* ignore */ }
  logoutLocal();
});

function enterApp() {
  $('login').classList.add('hidden');
  $('app').classList.remove('hidden');
  $('devicePage').classList.add('hidden');
  applyIdentity();
  load();
  clearInterval(pollTimer);
  pollTimer = setInterval(() => {
    if (!document.hidden) load();
  }, 5000);
  // 直接打开 `#/device/xxx`（刷新页面、收藏夹、别人发来的链接）时，
  // 恢复到那台设备，而不是停在列表页再让家长自己找一遍。
  const m = location.hash.match(HASH_DEVICE);
  if (m) {
    openDevice(decodeURIComponent(m[1]), { push: false });
  } else if (location.hash) {
    history.replaceState({}, '', location.pathname + location.search);
  }
}

/** 顶栏显示当前账号；非管理员隐藏「账号管理」 */
function applyIdentity() {
  if (!me) return;
  $('userName').textContent = me.username;
  $('userRole').textContent = me.is_admin ? '管理员' : '普通';
  $('userPill').classList.toggle('admin', !!me.is_admin);
  $('btnUsers').classList.toggle('hidden', !me.is_admin);
  $('devicesTitle').textContent = me.is_admin ? '全部设备' : '我的设备';
}

/* ---------------- 主数据 ---------------- */

async function load() {
  try {
    overview = await api('/api/overview');
    if (overview.user && (!me || me.role !== overview.user.role || me.username !== overview.user.username)) {
      me = overview.user;
      localStorage.setItem(USER_KEY, JSON.stringify(me));
      applyIdentity();
    }
    render();
  } catch (err) {
    if (err.message !== '登录已过期') {
      $('bootTime').textContent = '连接失败：' + err.message;
    }
  }
}

function render() {
  const o = overview;
  if (!o) return;

  $('bootTime').textContent = '最后刷新 ' + new Date().toLocaleTimeString('zh-CN', { hour12: false });
  $('pwdWarn').classList.toggle('hidden', !o.using_default_password);
  if (o.today) serverToday = o.today;
  if (o.events_window_hours) {
    $('evWindow').textContent = `最近 ${o.events_window_hours} 小时`;
  }

  const s = o.stats;
  const pill = $('onlinePill');
  pill.querySelector('span').textContent = `在线 ${s.devices_online} / ${s.devices_total}`;
  pill.querySelector('.dot').classList.toggle('off', s.devices_online === 0);

  renderStats(s);
  renderDevices(o.devices);
  renderTrend(o.trend);
  renderEvents(o.events, o.today);
}

function renderStats(s) {
  const cards = [
    { label: '今日使用总时长', value: fmtDur(s.today_used_ms), foot: `共 ${s.devices_total} 台设备` },
    { label: '今日强制休息', value: s.today_rest_count, unit: '次', foot: '可见设备合计' },
    { label: '在线设备', value: s.devices_online, unit: `/ ${s.devices_total}`, foot: `${s.devices_resting} 台正在休息` },
    { label: '守护关闭', value: s.devices_off, unit: '台', foot: s.devices_off ? '这些设备现在不受限制' : '全部设备都在守护中' },
  ];
  $('stats').innerHTML = cards.map((c) => `
    <div class="stat">
      <div class="label">${c.label}</div>
      <div class="value">${c.value}${c.unit ? `<small>${c.unit}</small>` : ''}</div>
      <div class="foot">${c.foot}</div>
    </div>`).join('');
}

function statusOf(d) {
  if (!d.online) return { cls: 'away', text: '离线' };
  // 停用时段的优先级最高：它盖过"守护中"，家长一眼要知道"现在为什么不能玩"
  if (d.in_quiet) return { cls: 'quiet', text: '停用时段' };
  // 每日额度用完是一个"要锁到明天"的状态，比"休息中"更值得被看见
  if (d.day_locked) return { cls: 'rest', text: '今日额度用完' };
  if (d.resting) return { cls: 'rest', text: '休息中' };
  if (!d.enabled) return { cls: 'off', text: '守护已关闭' };
  return { cls: 'use', text: '守护中' };
}

/** 规则的一行文字描述，如「每天 22:00–06:00（次日）」「周一至周五 06:00–09:00」 */
function quietRuleText(r) {
  const a = hhmmNum(r.from);
  const b = hhmmNum(r.to);
  const cross = (r.from || 0) > (r.to || 0);
  return `${daysText(r.days)} ${a}–${b}${cross ? '（次日）' : ''}`;
}

/** 把 0 点起算的分钟数格式化成 HH:MM */
function hhmmNum(min) {
  const m = Math.max(0, Math.min(1440, Math.round(Number(min) || 0)));
  return String(Math.floor(m / 60)).padStart(2, '0') + ':' + String(m % 60).padStart(2, '0');
}

/** "HH:MM" → 0 点起算的分钟数；非法返回 null */
function toMin(v) {
  const m = /^(\d{1,2}):(\d{2})$/.exec(String(v || '').trim());
  if (!m) return null;
  const h = Number(m[1]);
  const mi = Number(m[2]);
  if (h > 24 || mi > 59) return null;
  if (h === 24 && mi > 0) return null;
  return h * 60 + mi;
}

/** 星期位掩码 → 文字。0 或全选都显示"每天"（和服务端 days_label 同口径） */
const DAY_NAMES = ['一', '二', '三', '四', '五', '六', '日'];
function daysText(days) {
  const v = Number(days) || 0;
  if (v === 0 || v === 0b1111111) return '每天';
  const on = DAY_NAMES.filter((_, i) => v & (1 << i));
  if (!on.length) return '每天';
  // 连续的合成"周一至周五"
  const idx = DAY_NAMES.map((_, i) => (v & (1 << i) ? i : -1)).filter((i) => i >= 0);
  if (idx.length >= 3 && idx[idx.length - 1] - idx[0] === idx.length - 1) {
    return `周${DAY_NAMES[idx[0]]}至周${DAY_NAMES[idx[idx.length - 1]]}`;
  }
  return on.map((n) => '周' + n).join('、');
}

/** 明天 / 今天还要不要停用：给设备卡一句"下一段什么时候开始" */
function quietNextHint(rules) {
  const list = (rules || []).filter((r) => r.enabled);
  if (!list.length) return '';
  const now = new Date();
  const cur = now.getHours() * 60 + now.getMinutes();
  // 今天是否还要进入：只算"今天还没开始的"
  const cand = [];
  for (const r of list) {
    // 星期位对应的"今天"（JS 0=周日，掩码第 0 位=周一）
    const jsDay = now.getDay();
    const bit = 1 << ((jsDay + 6) % 7);
    const every = !r.days || r.days === 0b1111111;
    if (r.from > cur && (every || r.days & bit)) cand.push(r);
  }
  if (!cand.length) return '';
  cand.sort((a, b) => a.from - b.from);
  return `今天 ${hhmmNum(cand[0].from)} 起停用`;
}

function renderDevices(list) {
  $('emptyState').classList.toggle('hidden', list.length > 0);
  $('devices').innerHTML = list.map((d) => {
    const st = statusOf(d);
    const limitMs = Math.max(60000, (d.use_minutes || 30) * 60000);
    const pct = Math.min(100, Math.round(((d.used_ms || 0) / limitMs) * 100));
    const over = (d.used_ms || 0) > limitMs;
    return `
    <div class="device clickable" data-id="${esc(d.id)}">
      <div class="device-top">
        <div>
          <div class="device-name">${esc(d.name)}
            ${me && me.is_admin ? `<span class="owner-tag">${esc(d.owner || '未分配')}</span>` : ''}
          </div>
          <div class="device-sub">${esc(d.model || '未知型号')} · 最近心跳 ${fmtAgo(d.offline_seconds)}</div>
        </div>
        <span class="badge ${st.cls}"><i></i>${st.text}</span>
      </div>

      <div class="bar ${over ? 'over' : ''}"><i style="width:${pct}%"></i></div>

      <div class="device-metrics">
        <span>今日 <b>${fmtDur(d.today_used_ms)}</b></span>
        <span>本轮 <b>${fmtDur(d.used_ms)}</b> / ${d.use_minutes} 分钟</span>
        <span>已休息 <b>${d.rest_count}</b> 次</span>
        ${d.today_screen_ms ? `<span>亮屏 <b>${fmtDur(d.today_screen_ms)}</b></span>` : ''}
        ${d.today_unlocks ? `<span>解锁 <b>${d.today_unlocks}</b> 次</span>` : ''}
        ${d.today_boots ? `<span>开机 <b>${d.today_boots}</b> 次</span>` : ''}
        ${!d.in_quiet && quietNextHint(d.quiet_hours) ? `<span>${esc(quietNextHint(d.quiet_hours))}</span>` : ''}
        ${d.resting && d.rest_left_ms ? `<span>剩余 <b>${fmtDur(d.rest_left_ms)}</b></span>` : ''}
        ${d.resting && d.rest_reason ? `<span>原因 <b>${esc(d.rest_reason)}</b></span>` : ''}
        ${d.in_quiet && d.quiet_left_ms ? `<span>停用到 <b>${fmtDur(d.quiet_left_ms)}</b> 后</span>` : ''}
        ${d.pin_set ? '' : '<span style="color:var(--danger)">未设密码</span>'}
        ${penaltyFactor(d.tamper_strikes) > 1
          ? `<span style="color:var(--warn)">违规 ${d.tamper_strikes} 次 · 休息 ×${penaltyFactor(d.tamper_strikes)}</span>`
          : ''}
      </div>

      <div class="device-actions">
        <button class="btn primary" data-act="lock" data-id="${esc(d.id)}" data-min="${d.rest_minutes}">锁定 ${d.rest_minutes} 分钟</button>
        <button class="btn ok" data-act="unlock" data-id="${esc(d.id)}" ${d.resting ? '' : 'disabled'}>解除锁定</button>
        ${d.enabled
          ? `<button class="btn warn" data-act="pause" data-id="${esc(d.id)}">暂停守护</button>`
          : `<button class="btn ok" data-act="enable" data-id="${esc(d.id)}">开启守护</button>`}
        <button class="btn ghost" data-act="detail" data-id="${esc(d.id)}">查看详情 →</button>
      </div>
    </div>`;
  }).join('');
}

function renderTrend(rows) {
  const max = Math.max.apply(null, rows.map((r) => r.used_ms || 0).concat([1]));
  $('trend').innerHTML = rows.map((r) => {
    const w = Math.round(((r.used_ms || 0) / max) * 100);
    const label = r.day.slice(5).replace('-', '/');
    return `
      <div class="trend-row">
        <span class="trend-day">${label}</span>
        <span class="trend-bar"><i style="width:${w}%"></i></span>
        <span class="trend-val">${fmtShort(r.used_ms)}</span>
      </div>`;
  }).join('');
}

const KIND_ICON = {
  rest_start: '🟠', rest_end: '🟢', guard_off: '⏸', guard_on: '▶️',
  command: '📤', command_result: '✅', pair: '📱', pair_code: '🔑',
  admin_login: '👤', user: '🧾',
  // 停用时段进出：🌙 进场、☀️ 放开
  quiet_start: '🌙', quiet_end: '☀️',
};

function renderEvents(list, todayIso) {
  if (!list.length) {
    $('events').innerHTML = '<div class="empty muted small">最近 48 小时没有事件</div>';
    return;
  }
  $('events').innerHTML = list.map((e) => `
    <div class="event">
      <span class="time" title="${esc(fmtDate(e.created_at))}">${esc(fmtStamp(e.created_at, todayIso))}</span>
      <span class="txt">${KIND_ICON[e.kind] || '·'} ${esc(e.message)}</span>
    </div>`).join('');
}

/* ---------------- 设备操作 ---------------- */

document.addEventListener('click', async (e) => {
  const btn = e.target.closest('button[data-act]');
  if (!btn) return;
  const id = btn.dataset.id;
  const act = btn.dataset.act;

  if (act === 'detail') { openDevice(id); return; }

  // 锁定分钟数直接取按钮上的值（= 设备自己配置的休息时长），显式下发。
  // 以前这里传空 params，服务端补默认 5 分钟 —— 于是"锁定 1 分钟"实际锁 5 分钟。
  const lockMin = Number(btn.dataset.min) || 0;

  if (act === 'lock') {
    if (!confirm(`立即让这台设备进入强制休息 ${lockMin || ''} 分钟？屏幕会被全屏遮罩挡住。`)) return;
  }
  if (act === 'reset_tamper') {
    if (!confirm('把「拆防护」违规计数归零？之后惩罚休息会回到第 1 档（不再翻倍）。')) return;
  }
  if (act === 'pause') {
    if (!confirm('暂停守护后，这台设备将不再限制使用时间，直到你在后台或设备上重新开启。确定吗？')) return;
  }
  if (act === 'day_unlock') {
    if (!confirm('今天就解除每日总时长限制？到明天 0 点前都不会再按额度锁。适合孩子有作业要做的情况。')) return;
  }
  if (act === 'day_relock') {
    if (!confirm('恢复每日限制？如果今天额度已经用完，设备会立刻锁定。')) return;
  }

  btn.disabled = true;
  try {
    const params = act === 'lock' && lockMin > 0 ? { minutes: lockMin } : {};
    await api(`/api/devices/${id}/command`, {
      method: 'POST',
      body: JSON.stringify({ type: act, params })
    });
    toast(act === 'sync' ? '已通知设备立刻同步'
      : act === 'reset_tamper' ? '已下发清零，设备 20 秒内执行'
      : act === 'day_unlock' ? '已解除今日限制，设备 20 秒内生效'
      : act === 'day_relock' ? '已恢复每日限制'
      : '指令已排队，设备 20 秒内执行');
    setTimeout(() => (view === 'device' ? renderDevicePage(true) : load()), 1200);
  } catch (err) {
    toast(err.message);
  } finally {
    btn.disabled = false;
  }
});

/** 点设备卡片的空白处也能进设备主页（按钮有各自的处理逻辑，不会误触） */
document.addEventListener('click', (e) => {
  if (e.target.closest('button')) return;
  const card = e.target.closest('#devices .device[data-id]');
  if (!card) return;
  openDevice(card.dataset.id);
});

/* ---------------- 设备主页 ---------------- */

let view = 'home';          // home | device
let currentDeviceId = null;
let deviceTimer = null;
let tlDay = '';             // '' = 还没定，等服务器返回 today 再填
let tlFullDay = false;      // false = 只显示有活动的时段
let tlData = null;
let dpData = null;
let serverToday = '';       // 服务器时区的"今天"（家长浏览器可能不在同一时区）
let dpEvPage = 1;
/**
 * 时间线正在"只看某个应用"时的包名，空串 = 显示全部。
 *
 * 家长常见的提问是"她几点到几点在玩抖音"，整条轨道上十几个应用挤在一起，
 * 根本看不出来。点应用排行里的一行就把时间线过滤成那一个应用。
 */
let tlAppPkg = '';
/** 最近一次喂给应用排行的当日数据，切换过滤时用它重画高亮 */
let dpAppsData = null;

/**
 * 设备主页的地址，形如 `#/device/dev_xxx`。
 *
 * 为什么用 hash 不用 path：后台可能被放在反向代理的子目录下，
 * `pushState('/device/xxx')` 会把子目录一起抹掉，刷新直接 404。
 */
const HASH_DEVICE = /^#\/device\/([^/?#]+)$/;

/**
 * 打开设备主页。
 *
 * [opts.push] 为 false 时不写历史记录（由 popstate / 首屏恢复 hash 时调用），
 * 否则每条设备页都会在浏览器历史里留一步 —— 这样按手机的「后退」
 * 是回到设备列表，而不是把整个后台关掉。
 */
async function openDevice(id, opts) {
  if (!id) return;
  const push = !opts || opts.push !== false;
  view = 'device';
  currentDeviceId = id;
  if (push) {
    const want = '#/device/' + encodeURIComponent(id);
    if (location.hash !== want) history.pushState({ dev: id }, '', want);
  }
  tlDay = '';
  tlFullDay = false;
  tlAppPkg = '';
  dpEvPage = 1;
  // 换设备要重建停用时段草稿，否则会把上一台的规则带过来
  quietDraftFor = null;
  quietDraftSig = null;
  quietDraft = null;
  $('btnTlRange').textContent = '全天';
  $('app').classList.add('hidden');
  $('devicePage').classList.remove('hidden');
  window.scrollTo(0, 0);
  clearInterval(pollTimer); pollTimer = null;
  clearInterval(deviceTimer);
  $('dpStats').innerHTML = '';
  $('tlCanvas').innerHTML = '<div class="muted small">加载中…</div>';
  await renderDevicePage();
  deviceTimer = setInterval(() => {
    if (document.hidden || view !== 'device') return;
    renderDevicePage(true);
  }, 10000);
}

/**
 * 顶栏「← 返回」。
 *
 * 如果我们先前 pushState 过设备页记录，就走 `history.back()` —— 这样
 * 浏览器历史和地址栏都会一起退回首页，和点系统的后退键行为一致；
 * 直接打开 `#/device/xxx`（收藏、刷新）时没有上一条，才手动切回去。
 */
function backHome() {
  if (history.state && history.state.dev) {
    history.back();
    return;
  }
  switchToHome(false);
}

/** 真正切回设备列表（不改历史时用 replace，避免回退键在首页上原地打转） */
function switchToHome(push) {
  view = 'home';
  currentDeviceId = null;
  const clean = location.pathname + location.search;
  if (push) history.pushState({}, '', clean);
  else history.replaceState({}, '', clean);
  clearInterval(deviceTimer); deviceTimer = null;
  $('devicePage').classList.add('hidden');
  $('app').classList.remove('hidden');
  window.scrollTo(0, 0);
  load();
  if (!pollTimer) {
    pollTimer = setInterval(() => {
      if (!document.hidden && view === 'home') load();
    }, 5000);
  }
}

// 浏览器/手机的后退键：在设备页就退回列表，在列表上则什么都不做
// （history 里已经没有更早的记录了，让浏览器自己处理）。
window.addEventListener('popstate', () => {
  const m = location.hash.match(HASH_DEVICE);
  if (m) {
    openDevice(decodeURIComponent(m[1]), { push: false });
    return;
  }
  if (view === 'device') switchToHome(false);
});

async function renderDevicePage(quiet) {
  const id = currentDeviceId;
  if (!id) return;
  let data;
  try {
    data = await api('/api/devices/' + id);
  } catch (err) {
    if (!quiet) toast(err.message);
    return;
  }
  if (id !== currentDeviceId) return;   // 期间用户已经切走
  dpData = data;
  serverToday = (data.today && data.today.day) || todayStr();
  // 首次进来（或换了设备）才建草稿；同设备的 10 秒刷新不动它，
  // 否则家长正在改的那一行的输入会被服务端返回的旧值覆盖掉。
  if (quietDraftFor !== id) {
    quietDraftFor = id;
    resetQuietDraft(data.device);
  }
  // 规则本身被后台改了（另一台电脑保存的），以服务端为准刷新草稿
  if (quietDraftSig !== JSON.stringify(data.device.quiet_hours || [])) {
    quietDraftSig = JSON.stringify(data.device.quiet_hours || []);
    resetQuietDraft(data.device);
  }

  // 第一次进来用服务器的「今天」初始化，之后保留用户选的日期
  if (!tlDay) tlDay = serverToday;

  const d = data.device;
  const st = statusOf(d);
  $('dpName').textContent = d.name;
  $('dpMeta').textContent = [
    d.model || '未知型号',
    me && me.is_admin ? '归属 ' + (d.owner || '未分配') : '',
    '最近心跳 ' + fmtAgo(d.offline_seconds),
  ].filter(Boolean).join(' · ');

  const badge = $('dpBadge');
  badge.className = 'badge ' + st.cls;
  badge.querySelector('span').textContent = st.text;

  renderDpKv(d);
  renderDpDaily(data.daily);
  renderDpActions(d);
  renderDpConfig(d);
  renderDpCommands(data.commands);
  // 停用时段：草稿独立于 dpData，自动刷新不会冲掉正在编辑的内容
  renderQuiet(d);
  // 答题解锁：题库列表要单独拉一次（缓存 15 秒），所以放在最后异步画
  renderQuizPanel(d);

  // 统计卡 / 应用排行 / 时间轴 / 事件 都由"选中日期"统一驱动
  await loadDayData(quiet);
}

/**
 * 加载选中那一天的整套数据：时间轴 → 统计卡 + 应用排行，事件单独翻页加载。
 *
 * `quiet` 是 10 秒自动刷新用的：这时要**保持当前事件页码**，
 * 否则家长正翻到第 3 页，十秒后就被弹回第 1 页了。
 */
async function loadDayData(quiet) {
  await Promise.all([
    loadTimeline(quiet),
    loadDeviceEvents(quiet ? dpEvPage : 1),
  ]);
  if (!dpData) return;

  // 时间轴挂了也别让统计卡空着：退回详情接口里的"今日"数据
  const t = tlData
    ? statsFromTimeline(tlData)
    : {
        day: tlDay,
        apps: (dpData.today && dpData.today.apps) || [],
        usage_tracked: !!(dpData.today && dpData.today.usage_tracked),
        online_ms: (dpData.today && dpData.today.online_ms) || 0,
        foreground_ms: 0,
        screen_ms: (dpData.today && dpData.today.screen_ms) || 0,
        screen_tracked: !!(dpData.today && dpData.today.screen_tracked),
        unlocks: (dpData.today && dpData.today.unlocks) || 0,
        boots: (dpData.today && dpData.today.boots) || 0,
        shutdowns: (dpData.today && dpData.today.shutdowns) || 0,
        sys_ms: (dpData.today && dpData.today.sys_ms) || 0,
      };
  renderDpStats(dpData.device, t);
  renderDpApps(t);
}

/** 把时间线接口的返回整理成统计卡需要的形状 */
function statsFromTimeline(t) {
  const tot = t.totals || {};
  return {
    day: t.day,
    apps: t.apps || [],
    usage_tracked: !!tot.usage_tracked,
    online_ms: tot.online_ms || 0,
    foreground_ms: tot.foreground_ms || 0,
    // v2.3：亮屏 / 解锁 / 开关机
    screen_ms: tot.screen_ms || 0,
    screen_tracked: !!tot.screen_tracked,
    unlocks: tot.unlocks || 0,
    boots: tot.boots || 0,
    shutdowns: tot.shutdowns || 0,
    sys_ms: tot.sys_ms || 0,
  };
}

function renderDpStats(d, tl) {
  const day = (tl && tl.day) || serverToday || todayStr();
  const isToday = day === serverToday;
  const label = dayLabel(day, serverToday);

  // 当天的手机会话累计从「近 14 天」里取；没有那一行才退回设备上报的今日数据
  const row = ((dpData && dpData.daily) || []).find((r) => r.day === day);
  const usedMs = row ? row.used_ms : isToday ? d.today_used_ms : null;
  const restCount = row ? row.rest_count : isToday ? d.rest_count : null;

  const tracked = !!(tl && tl.usage_tracked);
  const fg = tracked ? (tl.apps || []).reduce((a, x) => a + (x.ms || 0), 0) : null;
  const limit = Math.max(60000, (d.use_minutes || 30) * 60000);
  const pct = isToday
    ? Math.min(100, Math.round(((d.used_ms || 0) / limit) * 100))
    : null;

  // ---- 采集口径 vs 系统口径 ----
  //
  // sys_ms 来自设备端 queryAndAggregateUsageStats（和手机自带"数字健康"同一个
  // API）。两个数不一致时直接标出差值，免得家长拿着两个对不上的数字猜。
  const sysMs = (tl && tl.sys_ms) || 0;
  let fgFoot = fg === null ? '未授权「使用情况访问」' : '各应用时长合计';
  if (fg !== null) {
    if (sysMs > 0 && fg > 0) {
      const diff = (fg - sysMs) / sysMs;
      if (Math.abs(diff) >= 0.15) {
        fgFoot = `系统口径 ${fmtDur(sysMs)}，差 ${diff > 0 ? '+' : ''}${Math.round(diff * 100)}%`;
      } else {
        fgFoot = `与系统口径一致（${fmtDur(sysMs)}）`;
      }
    }
  }

  const cards = [
    {
      // ★ 这张卡整体是"本轮"口径：进度条 `pct` 和脚注里的"本轮 X / 上限 Y"
      // 都是本轮已用，唯独大数字一直取当天累计 —— 于是会出现"进度条已经满了，
      // 数字却不是本轮那个"的自相矛盾（家长的第一反应就是"这名字不对"）。
      // 现在标题、数字、进度条统一成"本轮"，当天累计挪到脚注里，信息一点没少。
      // 看历史日期时没有"本轮"这个概念（那天已经结束），仍用当天累计。
      label: isToday ? '本轮使用' : `${label}使用`,
      value: isToday
        ? fmtDur(d.used_ms || 0)
        : (usedMs == null ? '—' : fmtDur(usedMs)),
      foot: isToday
        ? `今天累计 ${fmtDur(usedMs)}`
          + (d.day_limit_minutes > 0 ? ` · 每日上限 ${fmtShort(d.day_limit_minutes * 60000)}` : '')
          + ` · 本轮上限 ${d.use_minutes} 分钟`
        : '设备上报的当日累计',
      bar: pct,
      warn: isToday && !!d.day_locked,
    },
    {
      label: `${label}前台`,
      value: fg === null ? '—' : fmtDur(fg),
      foot: fgFoot,
      warn: fg !== null && sysMs > 0 && Math.abs((fg - sysMs) / sysMs) >= 0.15,
    },
    {
      label: `${label}在线`,
      value: fmtDur((tl && tl.online_ms) || 0),
      foot: '收到心跳的时段合计',
    },
    {
      label: `${label}亮屏`,
      value: tl && tl.screen_tracked ? fmtDur(tl.screen_ms) : '—',
      foot: tl && tl.screen_tracked ? '屏幕亮着的累计时长' : 'App 版本较旧，未采集',
    },
    {
      label: `${label}解锁`,
      value: tl && tl.screen_tracked ? tl.unlocks : '—',
      unit: tl && tl.screen_tracked ? ' 次' : '',
      foot: '亮屏后解锁的次数',
    },
    {
      label: `${label}开关机`,
      value: tl && tl.screen_tracked ? tl.boots : '—',
      unit: tl && tl.screen_tracked ? ' 次开机' : '',
      foot: tl && tl.screen_tracked ? `关机 ${tl.shutdowns} 次` : 'App 版本较旧，未采集',
    },
    {
      label: `${label}休息`,
      value: restCount == null ? '—' : restCount,
      unit: ' 次',
      foot: isToday && d.resting
        ? `正在休息，剩余 ${fmtDur(d.rest_left_ms)}`
        : '已完成的强制休息',
    },
  ];

  $('dpStats').innerHTML = cards.map((c) => `
    <div class="stat">
      <div class="label">${c.label}</div>
      <div class="value">${c.value}${c.unit ? `<small>${c.unit}</small>` : ''}</div>
      ${c.bar != null ? `<div class="bar ${c.bar >= 100 ? 'over' : ''}"><i style="width:${c.bar}%"></i></div>` : ''}
      <div class="foot${c.warn ? ' warn' : ''}">${esc(c.foot)}</div>
    </div>`).join('');
}

/** 当日应用排行：条形图 + 时长 + 切换次数；点一行 = 时间线只看这个应用 */
function renderDpApps(today) {
  dpAppsData = today || null;
  const apps = (today && today.apps) || [];
  const hint = $('dpAppsHint');

  // 换了日期后发现"正在过滤的那个应用"当天没数据 → 自动回到全部，
  // 否则时间线会是一片空白，家长会以为坏了。
  let dropped = false;
  if (tlAppPkg && !apps.some((a) => (a.pkg || a.label) === tlAppPkg)) {
    tlAppPkg = '';
    dropped = true;
  }

  if (!apps.length) {
    $('dpApps').innerHTML = '<div class="muted small">'
      + (today && today.usage_tracked === false
        ? '还没有应用数据 —— 需要在平板 App 里授权「使用情况访问」。没授权时只能看到在线时段。'
        : '这一天没有应用数据。')
      + '</div>';
    hint.textContent = '';
    return;
  }

  const max = apps[0].ms || 1;
  $('dpApps').innerHTML = apps.slice(0, 12).map((a, i) => {
    const name = a.label || a.pkg;
    const pkg = a.pkg || name;
    const on = !!tlAppPkg && tlAppPkg === pkg;
    return `
      <div class="app-row${on ? ' on' : ''}" data-pkg="${esc(pkg)}" role="button" tabindex="0"
           title="${on ? '点一下恢复显示全部应用' : '只看这个应用的时间线'}">
        <span class="idx">${i + 1}</span>
        <div>
          <div class="nm">${esc(name)}<small>${a.count} 次</small></div>
          <div class="b"><i style="width:${Math.max(2, Math.round((a.ms / max) * 100))}%;background:${colorOf(name)}"></i></div>
        </div>
        <span class="v">${fmtShort(a.ms)}</span>
      </div>`;
  }).join('');

  const total = apps.reduce((s, x) => s + (x.ms || 0), 0);
  if (tlAppPkg) {
    const cur = apps.find((a) => (a.pkg || a.label) === tlAppPkg);
    const curName = cur ? (cur.label || cur.pkg) : tlAppPkg;
    hint.innerHTML = `只看 <b>${esc(curName)}</b> · 点空白处显示全部`;
  } else {
    hint.textContent = `共 ${apps.length} 个应用 · ${fmtDur(total)} · 点一行只看它`;
  }

  // 上面刚把过滤清掉了，但时间线是用**旧**的过滤条件画的（loadDayData 里
  // 先画时间线再填这张表），所以这里要补一次重绘，否则页面停在
  // "这个应用这一天没有记录"的空态上，直到下一次刷新才对。
  if (dropped && tlData) renderTimeline(tlData);
}

/**
 * 切换「只看某个应用」。
 *
 * 传空串 = 恢复全部。点同一个应用第二次也恢复全部（再点一下取消，
 * 比强迫家长去找"显示全部"按钮更顺手）。
 */
function applyAppFilter(pkg) {
  tlAppPkg = (!pkg || tlAppPkg === pkg) ? '' : pkg;
  if (dpAppsData) renderDpApps(dpAppsData);
  if (tlData) renderTimeline(tlData);
}

function renderDpKv(d) {  const rows = [
    ['守护状态', d.enabled ? '已开启' : '<span style="color:var(--danger)">已关闭（不受时间限制）</span>'],
    ['连续使用上限', `${d.use_minutes} 分钟`],
    ['每日总时长', d.day_limit_minutes > 0
      ? `${d.day_limit_minutes} 分钟${d.day_locked ? '（<span style="color:var(--danger)">今天已用完</span>）' : ''}`
      : '不限'],
    ['每次休息', `${d.rest_minutes} 分钟`],
    ['重新计时阈值', `熄屏 ${d.reset_gap_seconds} 秒后归零`],
    ['家长密码', d.pin_set ? '已设置' : '<span style="color:var(--danger)">未设置（孩子可自行关闭守护）</span>'],
    ['强停惩罚', d.tamper_policy ? '已开启' : '未开启'],
    ['应用版本', esc(d.app_version || '未知')],
    ['最近地址', esc(d.last_ip || '未知')],
  ];
  $('dpKv').innerHTML = rows.map(([k, v]) => `<div>${k}</div><div>${v}</div>`).join('');
}

function renderDpDaily(list) {
  if (!list || !list.length) {
    $('dpDaily').innerHTML = '<div class="muted small">暂无数据</div>';
    return;
  }
  const max = Math.max.apply(null, list.map((x) => x.used_ms || 0).concat([1]));
  $('dpDaily').innerHTML = list.map((x) => `
    <div class="daily-row">
      <span class="d">${x.day.slice(5)}</span>
      <span class="b"><i style="width:${Math.round(((x.used_ms || 0) / max) * 100)}%"></i></span>
      <span class="muted">${fmtShort(x.used_ms)}</span>
    </div>`).join('');
}

function renderDpActions(d) {
  // 「今日解除」只在设了每日上限时才出现：没开这个维度时点它没有意义，
  // 反而会让家长误以为"每天有个额度"。
  const dayBtn = !d.day_limit_minutes ? '' : d.day_locked
    ? `<button class="btn ok" data-act="day_unlock" data-id="${esc(d.id)}">今日解除</button>`
    : `<button class="btn ghost" data-act="day_relock" data-id="${esc(d.id)}">恢复今日限制</button>`;
  $('dpActions').innerHTML = `
    <button class="btn primary" data-act="lock" data-id="${esc(d.id)}" data-min="${d.rest_minutes}">锁定 ${d.rest_minutes} 分钟</button>
    <button class="btn ok" data-act="unlock" data-id="${esc(d.id)}" ${d.resting ? '' : 'disabled'}>解除锁定</button>
    ${dayBtn}
    ${d.enabled
      ? `<button class="btn warn" data-act="pause" data-id="${esc(d.id)}">暂停守护</button>`
      : `<button class="btn ok" data-act="enable" data-id="${esc(d.id)}">开启守护</button>`}
    <button class="btn ghost" data-act="sync" data-id="${esc(d.id)}">立即同步</button>
    <button class="btn ghost" data-act="reset_tamper" data-id="${esc(d.id)}">清零违规计数</button>`;
}

function renderDpConfig(d) {
  $('dpConfig').innerHTML = `
    <div class="field">
      <div><label>连续使用时长</label><div class="hint">用满这么多分钟就强制休息</div></div>
      <input type="number" id="cfgUse" min="1" max="600" value="${d.use_minutes}">
    </div>
    <div class="field">
      <div><label>每次休息时长</label><div class="hint">强制休息多少分钟</div></div>
      <input type="number" id="cfgRest" min="1" max="240" value="${d.rest_minutes}">
    </div>
    <div class="field">
      <div><label>每日总时长</label><div class="hint">一天累计用满就锁到次日 0 点，不自动解除；填 0 表示不限</div></div>
      <input type="number" id="cfgDay" min="0" max="1440" step="15" value="${d.day_limit_minutes || 0}">
    </div>
    <div class="field">
      <div><label>重新计时阈值</label><div class="hint">熄屏超过这么多秒，连续时长归零</div></div>
      <input type="number" id="cfgGap" min="30" max="3600" step="30" value="${d.reset_gap_seconds}">
    </div>
    <div class="field">
      <div><label>被强行结束后惩罚</label><div class="hint">开启后，违规次数会放大下次休息时长：第 1 次原长、第 2 次 ×2、第 3 次起 ×3${
        d.tamper_strikes
          ? `。当前已记 <b>${d.tamper_strikes}</b> 次 → 休息 <b>${d.rest_minutes}×${penaltyFactor(d.tamper_strikes)} = ${d.rest_minutes * penaltyFactor(d.tamper_strikes)} 分钟</b>`
          : ''
      }</div></div>
      <span class="switch"><input type="checkbox" id="cfgTamper" ${d.tamper_policy ? 'checked' : ''}><span></span></span>
    </div>
    <div class="field">
      <div><label>休息时暂停后台播放</label><div class="hint">申请音频焦点，让视频自动暂停</div></div>
      <span class="switch"><input type="checkbox" id="cfgPause" ${d.pause_media_on_rest === 0 ? '' : 'checked'}><span></span></span>
    </div>
    <div class="field">
      <div><label>兜底：静音媒体音量</label><div class="hint">仅在上面那项压不住时才开</div></div>
      <span class="switch"><input type="checkbox" id="cfgMute" ${d.mute_on_rest ? 'checked' : ''}><span></span></span>
    </div>
    <button class="btn primary block" style="margin-top:14px" id="btnSaveCfg">下发配置</button>`;

  $('btnSaveCfg').addEventListener('click', async () => {
    const body = {
      type: 'config',
      params: {
        use_minutes: Number($('cfgUse').value),
        rest_minutes: Number($('cfgRest').value),
        day_limit_minutes: Number($('cfgDay').value),
        reset_gap_seconds: Number($('cfgGap').value),
        tamper_policy: $('cfgTamper').checked,
        pause_media_on_rest: $('cfgPause').checked,
        mute_on_rest: $('cfgMute').checked,
      },
    };
    try {
      await api(`/api/devices/${d.id}/command`, { method: 'POST', body: JSON.stringify(body) });
      toast('配置已下发，设备 20 秒内应用');
      renderDevicePage(true);
    } catch (err) {
      toast(err.message);
    }
  });
}

function renderDpCommands(list) {
  $('dpCommands').innerHTML = list && list.length
    ? list.map((c) => `
      <div class="cmd">
        <span>${esc(commandLabel(c.type))}</span>
        <span class="muted">${fmtClock(c.created_at)}</span>
        <span class="st ${c.status}">${statusLabel(c.status)}</span>
      </div>`).join('')
    : '<div class="muted small">还没有下发过指令</div>';
}

function renderDpEvents(list) {
  $('dpEvents').innerHTML = list && list.length
    ? list.map((ev) => `
      <div class="cmd">
        <span class="muted" title="${esc(fmtDate(ev.created_at))}">${esc(fmtStamp(ev.created_at, serverToday))}</span>
        <span>${KIND_ICON[ev.kind] || '·'} ${esc(ev.message)}</span>
      </div>`).join('')
    : '<div class="muted small">这一天没有事件</div>';
}

/** 按「选中日期」拉事件（分页）。日期切换时由 loadDayData 调进来。 */
async function loadDeviceEvents(page) {
  const id = currentDeviceId;
  if (!id) return;
  const p = page || 1;
  try {
    const q = new URLSearchParams({ day: tlDay, page: p, size: 20 });
    const data = await api(`/api/devices/${id}/events?${q}`);
    if (id !== currentDeviceId) return;
    dpEvPage = data.page || 1;
    $('dpEvHint').textContent = `${tlDay} · 共 ${data.total} 条`;
    renderDpEvents(data.events);
    renderPager('dpEvPager', data, (n) => loadDeviceEvents(n));
  } catch (err) {
    $('dpEvents').innerHTML = `<div class="muted small">事件加载失败：${esc(err.message)}</div>`;
  }
}

/** 通用分页控件：上一页 / 第 n/N 页 / 下一页 */
function renderPager(elId, data, onGo) {
  const el = $(elId);
  if (!el) return;
  if (!data || !data.pages || data.pages <= 1) {
    el.innerHTML = data && data.total ? `<span class="muted small">共 ${data.total} 条</span>` : '';
    el.onclick = null;
    return;
  }
  el.innerHTML = `
    <button class="btn ghost small" data-p="${data.page - 1}" ${data.page <= 1 ? 'disabled' : ''}>‹ 上一页</button>
    <span class="muted small">第 ${data.page} / ${data.pages} 页 · 共 ${data.total} 条</span>
    <button class="btn ghost small" data-p="${data.page + 1}" ${data.page >= data.pages ? 'disabled' : ''}>下一页 ›</button>`;
  el.onclick = (e) => {
    const b = e.target.closest('button[data-p]');
    if (!b || b.disabled) return;
    onGo(Number(b.dataset.p));
  };
}

/* ---------------- 停用时段（宵禁） ---------------- */

/**
 * 页面上正在编辑的规则草稿。
 *
 * 为什么要独立一份而不是直接改 dpData.device.quiet_hours：
 * 10 秒一次的自动刷新会重拉详情并覆盖 dpData，编辑到一半的输入会被冲掉。
 * 只有「换了设备」或「服务端规则真的变了」才重置草稿。
 */
let quietDraft = null;
/** 草稿属于哪台设备 */
let quietDraftFor = null;
/** 上一次从服务端看到的规则指纹，用来判断"规则被别人改了" */
let quietDraftSig = null;

/** 从服务端数据建立草稿（只在进入设备页 / 规则变化 / 保存成功后调用） */
function resetQuietDraft(d) {
  quietDraft = JSON.parse(JSON.stringify(d.quiet_hours || []));
  // 没有 id 的（新建的）补一个，保证行的 key 稳定、删除时不会错行
  quietDraft.forEach((r) => {
    if (!r.id) r.id = 'qr_' + Math.random().toString(36).slice(2, 8);
  });
  quietDraftSig = JSON.stringify(d.quiet_hours || []);
}

function renderQuiet(d) {
  if (quietDraft === null) resetQuietDraft(d);

  // ---- 当前状态条 ----
  const nowEl = $('quietNow');
  if (d.in_quiet) {
    nowEl.className = 'quiet-now on';
    nowEl.innerHTML = `<b>正在停用中</b> · ${esc(d.quiet_label || '停用时段')}
      ｜还会锁 <b>${fmtDur(d.quiet_left_ms || 0)}</b>`;
  } else if (d.quiet_hours && d.quiet_hours.some((r) => r.enabled)) {
    const hint = quietNextHint(d.quiet_hours);
    nowEl.className = 'quiet-now';
    nowEl.innerHTML = `<b>当前未停用</b>${hint ? ' · ' + esc(hint) : ''}`;
  } else {
    nowEl.className = 'quiet-now hidden';
    nowEl.innerHTML = '';
  }

  // ---- 规则列表 ----
  const list = $('quietList');
  if (!quietDraft.length) {
    list.innerHTML = '<div class="muted small" style="padding:10px 0">'
      + '还没有停用时段。点右上角「添加时段」，例如「每天 22:00 到次日 06:00」。</div>';
  } else {
    list.innerHTML = quietDraft.map((r, i) => `
      <div class="quiet-row ${r.enabled ? '' : 'off'}" data-i="${i}">
        <span class="switch sm"><input type="checkbox" data-q="enabled" ${r.enabled ? 'checked' : ''}><span></span></span>
        <input class="qi-label" data-q="label" maxlength="20" placeholder="备注（可空）"
               value="${esc(r.label || '')}">
        <input class="qi-time" data-q="from" type="time" value="${hhmmNum(r.from)}">
        <span class="qi-arrow">→</span>
        <input class="qi-time" data-q="to" type="time" value="${hhmmNum(r.to)}">
        <div class="qi-days" data-q="days">
          ${DAY_NAMES.map((n, k) => `
            <button type="button" class="qi-day ${quietDayOn(r.days, k) ? 'on' : ''}"
                    data-day="${k}" title="周${n}">${n}</button>`).join('')}
        </div>
        <button type="button" class="icon-btn qi-del" data-q="del" title="删除这条">✕</button>
      </div>`).join('');
  }

  // ---- 汇总提示 ----
  const on = quietDraft.filter((r) => r.enabled);
  // 设备本机另外设的规则（内容不在服务端，只报条数）。
  // 不提示的话，家长看到"规则列表是空的"会以为手机上的设置没生效。
  const localNote = (d.quiet_local_count || 0) > 0
    ? `　设备本机另有 ${d.quiet_local_count} 条（在 App 设置页里改，这里看不到具体内容）`
    : '';
  $('quietHint').textContent = (on.length
    ? `${on.length} 条生效中：` + on.map(quietRuleText).join('；')
    : (quietDraft.length ? '全部未启用 —— 保存后设备端不会锁屏' : '未设置停用时段')) + localNote;
}

/** 星期位掩码第 k 位（0=周一）是否为开。days=0（没勾）表示每天，全部点亮 */
function quietDayOn(days, k) {
  const v = Number(days) || 0;
  if (v === 0) return true;
  return !!(v & (1 << k));
}

/** 绑定停用时段面板的交互（只调一次） */
function initQuietPanel() {
  $('btnQuietAdd').addEventListener('click', () => {
    if (quietDraft === null) quietDraft = [];
    if (quietDraft.length >= 8) {
      toast('最多 8 条停用时段');
      return;
    }
    quietDraft.push({
      id: 'qr_' + Math.random().toString(36).slice(2, 8),
      enabled: true,
      label: '',
      from: 22 * 60,
      to: 6 * 60,
      days: 0,
    });
    renderQuiet((dpData && dpData.device) || { in_quiet: false, quiet_hours: quietDraft });
  });

  $('quietList').addEventListener('click', (e) => {
    const row = e.target.closest('.quiet-row');
    if (!row || quietDraft === null) return;
    const i = Number(row.dataset.i);
    const r = quietDraft[i];
    if (!r) return;

    // 星期按钮：切换那一位。"没勾任何一天"= 每天，所以从 0（每天）起步时
    // 点一天要变成"只勾这一天"，其余全灭，否则家长的直觉对不上。
    const dayBtn = e.target.closest('.qi-day');
    if (dayBtn) {
      const k = Number(dayBtn.dataset.day);
      let v = Number(r.days) || 0;
      if (v === 0) v = 0b1111111;
      v ^= 1 << k;
      r.days = v & 0b1111111;
      dayBtn.classList.toggle('on', !!(r.days & (1 << k)));
      updateQuietHint();
      return;
    }
    if (e.target.closest('.qi-del')) {
      quietDraft.splice(i, 1);
      renderQuiet((dpData && dpData.device) || { in_quiet: false, quiet_hours: quietDraft });
    }
  });

  // 输入变化：只更新草稿，不重绘（重绘会把焦点丢到别的行）
  $('quietList').addEventListener('input', (e) => {
    const row = e.target.closest('.quiet-row');
    if (!row || quietDraft === null) return;
    const r = quietDraft[Number(row.dataset.i)];
    if (!r) return;
    const key = e.target.dataset.q;
    if (key === 'enabled') {
      r.enabled = e.target.checked;
      row.classList.toggle('off', !r.enabled);
    } else if (key === 'label') {
      r.label = e.target.value.slice(0, 20);
    } else if (key === 'from' || key === 'to') {
      const m = toMin(e.target.value);
      if (m !== null) r[key] = m;
    }
    updateQuietHint();
  });

  $('btnQuietSave').addEventListener('click', saveQuiet);
}

function updateQuietHint() {
  const on = (quietDraft || []).filter((r) => r.enabled);
  $('quietHint').textContent = on.length
    ? `${on.length} 条生效中：` + on.map(quietRuleText).join('；')
    : ((quietDraft || []).length ? '全部未启用 —— 保存后设备端不会锁屏' : '未设置停用时段');
}

/** 保存：把整份规则下发（服务端也会存一份，设备离线不丢） */
async function saveQuiet() {
  const d = dpData && dpData.device;
  if (!d || !quietDraft) return;

  // 前端先校验一遍：起止相同、时间没填，都要在点下去之前就拦住
  for (let i = 0; i < quietDraft.length; i++) {
    const r = quietDraft[i];
    if (r.from === r.to) {
      toast(`第 ${i + 1} 条的起止时间相同（${hhmmNum(r.from)}），请改成具体的时段`);
      return;
    }
    if (r.from == null || r.to == null) {
      toast(`第 ${i + 1} 条的时间没填完整`);
      return;
    }
  }

  try {
    await api(`/api/devices/${d.id}/command`, {
      method: 'POST',
      body: JSON.stringify({
        type: 'quiet',
        params: {
          rules: quietDraft.map((r) => ({
            id: r.id,
            enabled: !!r.enabled,
            label: r.label || '',
            from: r.from,
            to: r.to,
            days: r.days || 0,
          })),
        },
      }),
    });
    toast('停用时段已保存，设备 20 秒内生效');
    await renderDevicePage(true);
    const cur = (dpData && dpData.device) || d;
    resetQuietDraft(cur);
    renderQuiet(cur);
  } catch (err) {
    toast(err.message);
  }
}

/* ---------------- 时间线 ---------------- */

async function loadTimeline(quiet) {
  const id = currentDeviceId;
  if (!id) return;
  const q = tlDay ? '?day=' + encodeURIComponent(tlDay) : '';
  let data;
  try {
    data = await api(`/api/devices/${id}/timeline${q}`);
  } catch (err) {
    $('tlCanvas').innerHTML = `<div class="muted small">时间线加载失败：${esc(err.message)}</div>`;
    return;
  }
  if (id !== currentDeviceId) return;
  tlData = data;
  if (data.today) serverToday = data.today;
  syncDayPicker(data);
  renderTimeline(data);
  if (!quiet && !data.apps.length && !data.presence.length) {
    // 静默即可，页面上已经有说明
  }
}

/** 把选中日期同步到日期控件（默认今天，不允许翻到未来）
 *
 * 注意：这里**不要**去写 #tlSub —— 那是时间轴的副标题，由 renderTimeline
 * 按"实际数据落在几点到几点"来写，紧跟着就会被覆盖，写了也是白写。
 * 当前看的是哪天，由输入框本身显示。
 */
function syncDayPicker(data) {
  const day = data.day;
  const today = data.today || serverToday || day;
  const input = $('dayInput');
  input.value = day;
  input.max = today;
  $('btnNextDay').disabled = day >= today;
  $('btnPrevDay').disabled = false;
  $('btnToday').disabled = day === today;
}

const PALETTE = [
  '#4f8cff', '#f2994a', '#27ae60', '#eb5757', '#9b51e0',
  '#00b8a9', '#f2c94c', '#56ccf2', '#bb6bd9', '#e07a5f',
];

/** 同一应用每次都用同一个颜色：按名字做稳定哈希 */
function colorOf(key) {
  const s = String(key || '');
  let h = 0;
  for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) >>> 0;
  return PALETTE[h % PALETTE.length];
}

function hhmm(sec) {
  const s = Math.max(0, Math.round(sec));
  const h = Math.floor(s / 3600) % 24;
  const m = Math.floor((s % 3600) / 60);
  return String(h).padStart(2, '0') + ':' + String(m).padStart(2, '0');
}

function pickStep(span) {
  const isMobile = window.matchMedia('(max-width: 720px)').matches;
  const want = isMobile ? 4 : 8;
  const raw = span / want / 3600;
  const steps = [1, 2, 3, 4, 6, 12, 24];
  const s = steps.find((x) => x >= raw) || 24;
  return s * 3600;
}

function renderTimeline(data) {
  const from = data.from;
  const pres = data.presence || [];
  const usage = data.usage || [];
  const scr = data.screen || [];
  const tot = data.totals || {};
  const canvas = $('tlCanvas');
  // 「只看某个应用」时，后面的视野计算、轨道绘制全部基于这一份。
  // 视野跟着过滤后的片段走，家长点"抖音"就能直接看到它集中在哪几个小时，
  // 而不是在整天的空白里找那几块颜色。
  const shown = tlAppPkg
    ? usage.filter((u) => (u.pkg || u.label) === tlAppPkg)
    : usage;
  const empty = !pres.length && !usage.length && !scr.length;

  if (empty) {
    canvas.style.width = '100%';
    canvas.innerHTML = `
      <div class="tl-empty">
        <div>这一天还没有记录</div>
        <div class="muted small">设备在线后每 20 秒上报一次心跳；应用名需要在平板 App 里授权「使用情况访问」。</div>
      </div>`;
    $('tlLegend').innerHTML = '';
    $('tlSub').textContent = '在线时段 + 每个时刻在用的应用';
    $('tlTip').textContent = '点按色块查看具体时段';
    return;
  }

  // ---- 显示范围（当天内的秒偏移）----
  let lo = 0;
  let hi = 86400;
  if (!tlFullDay) {
    let minS = 86400;
    let maxS = 0;
    // 过滤到单个应用时只用它的片段定视野。掺进在线/亮屏区间的话视野还是整天，
    // 等于没过滤 —— 家长想看的恰恰是"这个应用集中在哪几个小时"。
    if (tlAppPkg) {
      for (const u of shown) {
        minS = Math.min(minS, u.start - from);
        maxS = Math.max(maxS, u.end - from);
      }
    } else {
      for (const p of pres) {
        minS = Math.min(minS, p.start - from);
        maxS = Math.max(maxS, p.end - from);
      }
      for (const u of shown) {
        minS = Math.min(minS, u.start - from);
        maxS = Math.max(maxS, u.end - from);
      }
      for (const s of scr) {
        minS = Math.min(minS, s.start - from);
        maxS = Math.max(maxS, s.end - from);
      }
    }
    if (maxS <= 0) { minS = 8 * 3600; maxS = 9 * 3600; }
    // 前后留白随活动跨度走：跨得久就多留一点，只活跃了十几分钟就少留一点
    const rawSpan = Math.max(0, maxS - minS);
    const pad = Math.min(15 * 60, Math.max(120, rawSpan * 0.15));
    lo = Math.max(0, minS - pad);
    hi = Math.min(86400, maxS + pad);
    // 太窄了刻度会挤成一团；但也不能像以前那样一律拉到 6 小时 ——
    // 孩子只活跃了 20 分钟的时候，6 小时视野会把细节压扁 18 倍，
    // 每个几秒的色块都不到 1 像素，全被最小宽度撑开、互相叠住。
    const MIN_SPAN = 3600;
    if (hi - lo < MIN_SPAN) {
      const mid = (lo + hi) / 2;
      lo = mid - MIN_SPAN / 2;
      hi = lo + MIN_SPAN;
      if (lo < 0) { lo = 0; hi = MIN_SPAN; }
      if (hi > 86400) { hi = 86400; lo = 86400 - MIN_SPAN; }
    }
  }

  const span = hi - lo;
  const isMobile = window.matchMedia('(max-width: 720px)').matches;
  const width = Math.max(520, Math.round((span / 3600) * (isMobile ? 46 : 62)));
  canvas.style.width = width + 'px';

  const k = width / span;
  const x = (t) => Math.max(0, Math.min(width, (t - from - lo) * k));

  /**
   * 把一段裁剪到 [lo, hi] 视野内，完全在视野外返回 null。
   *
   * 不加裁剪的话：过滤到某个应用后视野可能只覆盖 14:00–15:00，而一条
   * 00:00–24:00 的「在线」区间会被 x() 的左右 clamp 画成横贯整行的色块 ——
   * 凭空造出一条"全程在线"的假数据，比不画还糟。
   */
  const clipToView = (a, b) => {
    const a2 = Math.max(a, from + lo);
    const b2 = Math.min(b, from + hi);
    return b2 > a2 ? [a2, b2] : null;
  };

  // ---- 刻度 ----
  const step = pickStep(span);
  let ticks = '';
  for (let s = Math.ceil(lo / step) * step; s <= hi; s += step) {
    const left = x(from + s);
    ticks += `<div class="tl-tick" style="left:${left}px"><span>${hhmm(s)}</span></div>`;
  }

  // ---- 在线轨道 ----
  const online = pres
    .map((p) => clipToView(p.start, p.end))
    .filter(Boolean)
    .map(([a, b]) => {
      const l = x(a);
      const r = x(b);
      return `<div class="tl-on" style="left:${l}px;width:${Math.max(1, r - l)}px"></div>`;
    }).join('');

  // ---- 屏幕轨道 ----
  //
  // 亮着就画一条绿线，熄屏就是空白 —— 一眼能看出"几点到几点手机是亮的"。
  // 老版本 App 没上报过亮屏区间（totals.screen_tracked = false），
  // 这时整行显示"未采集"，而不是显示成"全天没亮过"，免得误解。
  const screenLane = scr
    .map((s) => clipToView(s.start, s.end))
    .filter(Boolean)
    .map(([a, b]) => {
      const l = x(a);
      const w = Math.max(1, x(b) - l);
      const tip = `${hhmm(a - from)}–${hhmm(b - from)} · 亮屏 ${fmtShort((b - a) * 1000)}`;
      return `<div class="tl-scr" data-tip="${esc(tip)}" style="left:${l}px;width:${w}px"></div>`;
    }).join('');
  const screenRow = tot.screen_tracked === false
    ? `<div class="tl-row">
         <div class="tl-name">屏幕</div>
         <div class="tl-lane" style="width:${width}px"><div class="tl-none">未采集（App 版本较旧）</div></div>
       </div>`
    : `<div class="tl-row">
         <div class="tl-name">屏幕</div>
         <div class="tl-lane" style="width:${width}px">${screenLane}</div>
       </div>`;

  // ---- 应用轨道 ----
  //
  // 服务器已经把"每 20 秒一段"的碎片合并成连续的一整段，所以这里正常情况下
  // 一条时间只对应一个色块。宽度用 min 1px 而不是 2~3px：视野里色块多的时候，
  // 最小宽度会互相压住，看起来就是"很多应用叠在一起"。
  const appSegs = shown
    .map((u) => {
      const c = clipToView(u.start, u.end);
      return c ? { u, a: c[0], b: c[1] } : null;
    })
    .filter(Boolean)
    .map(({ u, a, b }) => {
      const l = x(a);
      const w = Math.max(1, x(b) - l);
      const name = u.label || u.pkg;
      const text = w > 56 ? `<span>${esc(name)}</span>` : '';
      const tip = `${hhmm(a - from)}–${hhmm(b - from)} · ${name} · ${fmtShort((b - a) * 1000)}`;
      const radius = w < 5 ? 1 : 4;
      return `<div class="tl-app" data-tip="${esc(tip)}" style="left:${l}px;width:${w}px;border-radius:${radius}px;background:${colorOf(name)}">${text}</div>`;
    }).join('');

  canvas.innerHTML = `
    <div class="tl-row tl-axis">
      <div class="tl-name"></div>
      <div class="tl-lane" style="width:${width}px">${ticks}</div>
    </div>
    <div class="tl-row">
      <div class="tl-name">在线</div>
      <div class="tl-lane" style="width:${width}px">${online}</div>
    </div>
    ${screenRow}
    <div class="tl-row lane-tall">
      <div class="tl-name">应用</div>
      <div class="tl-lane" style="width:${width}px">
        ${appSegs || `<div class="tl-none">${
          tlAppPkg ? '这个应用这一天没有记录' : '这一天没有应用数据'
        }</div>`}
      </div>
    </div>`;

  // ---- 图例（当日 TOP 8）----
  // 正在过滤时最前面挂一个"只看 X ✕"，点它就恢复。
  // 只靠"点空白处恢复"不够显眼 —— 家长不知道还能点哪儿。
  const top = (data.apps || []).slice(0, 8);
  const curApp = tlAppPkg
    ? (data.apps || []).find((a) => (a.pkg || a.label) === tlAppPkg)
    : null;
  const curName = curApp ? (curApp.label || curApp.pkg) : tlAppPkg;
  const pickChip = tlAppPkg
    ? `<button class="lg lg-pick" data-clear-filter="1" title="恢复显示全部应用">
         <i style="background:${colorOf(curName)}"></i>只看 ${esc(curName)} ✕
       </button>`
    : '';
  $('tlLegend').innerHTML = pickChip + (top.length
    ? top.map((a) => {
      const name = a.label || a.pkg;
      return `<span class="lg"><i style="background:${colorOf(name)}"></i>${esc(name)}<b>${fmtShort(a.ms)}</b></span>`;
    }).join('')
    : '<span class="muted small">还没有应用数据 —— 需要在平板 App 里授权「使用情况访问」</span>');

  $('tlSub').textContent = tlAppPkg
    ? `只看 ${curName} · ${hhmm(lo)} – ${hhmm(hi)}`
    : `${hhmm(lo)} – ${hhmm(hi)} · 绿条=在线/亮屏，下排色块=前台应用`;
  $('tlTip').textContent = tlAppPkg
    ? '点空白处、或再点一次这个应用，恢复显示全部应用'
    : '点按色块查看具体时段';
}

/* ---------------- 指令文案（原样保留） ---------------- */

function commandLabel(t) {
  return {
    lock: '远程锁定', unlock: '解除锁定', pause: '暂停守护',
    enable: '开启守护', config: '修改配置', sync: '立即同步',
    reset_tamper: '清零违规计数', quiet: '更新停用时段',
  }[t] || t;
}

function statusLabel(s) {
  return { pending: '排队中', sent: '已发送', done: '已执行', failed: '失败' }[s] || s;
}

/* ---------------- 设备页交互（只绑一次） ---------------- */

function initDevicePage() {
  $('btnBack').addEventListener('click', backHome);
  initQuietPanel();

  // ---- 日期切换：时间轴 / 统计 / 应用排行 / 事件 一起跟着走 ----
  const gotoDay = async (day) => {
    if (!day) return;
    // 不允许翻到未来（那边一定没有数据，翻过去只会让人以为坏了）
    if (serverToday && day > serverToday) day = serverToday;
    tlDay = day;
    dpEvPage = 1;          // 换日期要回到事件第一页
    $('dayInput').value = day;
    await loadDayData(true);
  };

  $('btnPrevDay').addEventListener('click', () => gotoDay(shiftDay(tlDay, -1)));
  $('btnNextDay').addEventListener('click', () => gotoDay(shiftDay(tlDay, 1)));
  $('btnToday').addEventListener('click', () => gotoDay(serverToday || todayStr()));
  $('dayInput').addEventListener('change', () => gotoDay($('dayInput').value));

  $('btnTlRange').addEventListener('click', () => {
    tlFullDay = !tlFullDay;
    $('btnTlRange').textContent = tlFullDay ? '只看活动' : '全天';
    if (tlData) renderTimeline(tlData);
  });

  // ---- 应用 ↔ 时间线联动：点一行只看它，点空白恢复全部 ----
  $('dpApps').addEventListener('click', (e) => {
    const row = e.target.closest('.app-row');
    applyAppFilter(row ? row.dataset.pkg : '');
  });
  // 键盘也能选（桌面端 tab 到某一行按回车）
  $('dpApps').addEventListener('keydown', (e) => {
    if (e.key !== 'Enter' && e.key !== ' ') return;
    const row = e.target.closest('.app-row');
    if (!row) return;
    e.preventDefault();
    applyAppFilter(row.dataset.pkg);
  });

  $('tlCanvas').addEventListener('click', (e) => {
    const seg = e.target.closest('.tl-app');
    if (!seg) {
      // 点到轨道空白处 = 取消过滤（家长最容易想到的"退出"手势）
      if (tlAppPkg) applyAppFilter('');
      return;
    }
    $('tlTip').innerHTML = `<b>${esc(seg.dataset.tip || '')}</b>`;
    $('tlCanvas').querySelectorAll('.tl-app.sel').forEach((x) => x.classList.remove('sel'));
    seg.classList.add('sel');
  });

  // 图例最前面那个"只看 X ✕"
  $('tlLegend').addEventListener('click', (e) => {
    if (e.target.closest('[data-clear-filter]')) applyAppFilter('');
  });

  $('btnDelDev').addEventListener('click', async () => {
    const d = dpData && dpData.device;
    if (!d) return;
    if (!confirm(`移除设备「${d.name}」？它的令牌会立刻失效。`)) return;
    try {
      await api(`/api/devices/${d.id}/delete`, { method: 'POST' });
      toast('设备已移除');
      backHome();
    } catch (err) {
      toast(err.message);
    }
  });
}

/* ---------------- 添加设备 ---------------- */

$('btnPair').addEventListener('click', async () => {
  $('pairModal').classList.remove('hidden');
  await preparePairOwner();
  await newPairCode();
});

$('btnNewCode').addEventListener('click', newPairCode);
$('pairOwner').addEventListener('change', newPairCode);

/** 管理员可以替别的账号配设备；普通账号没有这个选项 */
async function preparePairOwner() {
  const row = $('pairOwnerRow');
  if (!me || !me.is_admin) { row.classList.add('hidden'); return; }
  try {
    const d = await api('/api/users');
    const sel = $('pairOwner');
    sel.innerHTML = d.users
      .filter((u) => !u.disabled)
      .map((u) => `<option value="${esc(u.id)}">${esc(u.username)}${u.is_admin ? '（管理员）' : ''}</option>`)
      .join('');
    sel.value = me.id;
    row.classList.remove('hidden');
  } catch (e) {
    row.classList.add('hidden');
  }
}

async function newPairCode() {
  $('pairCode').textContent = '------';
  $('pairHint').textContent = '正在生成…';
  const owner = (!me || !me.is_admin) ? '' : ($('pairOwner').value || '');
  const qs = owner ? `?owner=${encodeURIComponent(owner)}` : '';
  try {
    const d = await api('/api/pair-codes' + qs, { method: 'POST' });
    $('pairCode').textContent = d.code;
    $('pairHint').textContent = `10 分钟内有效，只能用一次${d.owner ? ' · 归属 ' + d.owner : ''}`;
  } catch (err) {
    $('pairHint').textContent = err.message;
  }
}

/* ---------------- 账号管理 ---------------- */

$('btnUsers').addEventListener('click', openUsers);

async function openUsers() {
  $('usersModal').classList.remove('hidden');
  $('userList').innerHTML = '<div class="muted small">加载中…</div>';
  await loadUsers();
}

async function loadUsers() {
  try {
    const d = await api('/api/users');
    allUsers = d.users;
    renderUsers(d.users);
  } catch (err) {
    $('userList').innerHTML = `<div class="err">${esc(err.message)}</div>`;
  }
}

function renderUsers(list) {
  $('userList').innerHTML = list.map((u) => {
    const isMe = me && u.id === me.id;
    return `
    <div class="user-row">
      <div class="user-main">
        <div class="user-name">
          ${esc(u.username)}
          ${u.is_admin ? '<span class="badge use">管理员</span>' : ''}
          ${u.disabled ? '<span class="badge away">已停用</span>' : ''}
          ${isMe ? '<span class="badge off">我</span>' : ''}
        </div>
        <div class="user-sub muted small">
          ${u.device_count} 台设备 · 最近登录 ${fmtDate(u.last_login)}${u.note ? ' · ' + esc(u.note) : ''}
        </div>
      </div>
      <div class="user-ops">
        <button class="btn ghost" data-uact="pwd" data-id="${esc(u.id)}" data-name="${esc(u.username)}">重设密码</button>
        ${isMe ? '' : `<button class="btn ghost" data-uact="toggle" data-id="${esc(u.id)}" data-disabled="${u.disabled}">${u.disabled ? '启用' : '停用'}</button>`}
        ${isMe ? '' : `<button class="btn danger" data-uact="del" data-id="${esc(u.id)}" data-name="${esc(u.username)}">删除</button>`}
      </div>
    </div>`;
  }).join('');
}

document.addEventListener('click', async (e) => {
  const btn = e.target.closest('button[data-uact]');
  if (!btn) return;
  const id = btn.dataset.id;
  const name = btn.dataset.name;
  const act = btn.dataset.uact;

  if (act === 'pwd') {
    const pw = prompt(`给账号「${name}」设置新密码（至少 6 位）：`);
    if (!pw) return;
    try {
      await api(`/api/users/${id}`, { method: 'POST', body: JSON.stringify({ password: pw }) });
      toast('密码已重设，该账号需重新登录');
    } catch (err) { toast(err.message); }
    return;
  }

  if (act === 'toggle') {
    const disable = btn.dataset.disabled !== 'true';
    if (disable && !confirm(`停用「${name}」后，这个账号将无法登录。确定吗？`)) return;
    try {
      await api(`/api/users/${id}`, { method: 'POST', body: JSON.stringify({ disabled: disable }) });
      toast(disable ? '账号已停用' : '账号已启用');
      loadUsers();
    } catch (err) { toast(err.message); }
    return;
  }

  if (act === 'del') {
    if (!confirm(`删除账号「${name}」？这个操作不可撤销。`)) return;
    try {
      await api(`/api/users/${id}/delete`, { method: 'POST' });
      toast('账号已删除');
      loadUsers();
    } catch (err) {
      // 名下还有设备时，后端要求指定转移目标
      if (/转给/.test(err.message)) {
        const t = prompt(err.message + '\n\n请输入要把设备转给哪个账号（用户名）：');
        if (!t) return;
        const target = allUsers.find((u) => u.username.toLowerCase() === t.trim().toLowerCase());
        if (!target) { toast('没有这个账号'); return; }
        try {
          await api(`/api/users/${id}/delete?reassign=${encodeURIComponent(target.id)}`, { method: 'POST' });
          toast('账号已删除，设备已转移给 ' + target.username);
          loadUsers();
        } catch (err2) { toast(err2.message); }
      } else {
        toast(err.message);
      }
    }
  }
});

$('btnCreateUser').addEventListener('click', async () => {
  const body = {
    username: $('nuName').value.trim(),
    password: $('nuPwd').value,
    role: $('nuRole').value,
    note: $('nuNote').value.trim(),
  };
  if (!body.username || !body.password) { toast('用户名和密码都要填'); return; }
  try {
    await api('/api/users', { method: 'POST', body: JSON.stringify(body) });
    $('nuName').value = $('nuPwd').value = $('nuNote').value = '';
    toast('账号已创建');
    loadUsers();
  } catch (err) {
    toast(err.message);
  }
});

/* ---------------- 我的账号 ---------------- */

$('btnAccount').addEventListener('click', () => {
  $('accountModal').classList.remove('hidden');
  $('accMeta').textContent = me
    ? `${me.username} · ${me.is_admin ? '管理员' : '普通账号'} · 只能看到归属自己的设备`
    : '';
  $('oldPwd').value = $('newPwd').value = $('newPwd2').value = '';
});

$('btnChangePwd').addEventListener('click', async () => {
  const oldP = $('oldPwd').value;
  const p1 = $('newPwd').value;
  const p2 = $('newPwd2').value;
  if (!oldP || !p1) { toast('请填写当前密码和新密码'); return; }
  if (p1 !== p2) { toast('两次输入的新密码不一致'); return; }
  if (p1.length < 6) { toast('新密码至少 6 位'); return; }
  try {
    await api('/api/me/password', {
      method: 'POST',
      body: JSON.stringify({ old_password: oldP, new_password: p1 })
    });
    $('accountModal').classList.add('hidden');
    toast('密码已修改');
  } catch (err) {
    toast(err.message);
  }
});

/* ---------------- 全部事件（按天 + 分页） ---------------- */

// null = 还没选过（第一次打开默认今天）；'' = 用户明确选了"全部时间"
// 注意不能用 '' 同时表示这两种状态，否则"全部时间"每次打开都会被重置回今天
let evDay = null;
let evDevice = '';

function fillEvDevices() {
  const sel = $('evDevice');
  const list = (overview && overview.devices) || [];
  const cur = sel.value;
  sel.innerHTML = '<option value="">全部设备</option>'
    + list.map((d) => `<option value="${esc(d.id)}">${esc(d.name)}</option>`).join('');
  sel.value = cur && list.some((d) => d.id === cur) ? cur : '';
}

async function loadAllEvents(page) {
  const p = page || 1;
  const q = new URLSearchParams({ page: p, size: 30 });
  // 给了 day 就按天；"全部时间"退化成往前一年（事件本身保留 365 天）
  if (evDay) q.set('day', evDay);
  else q.set('hours', 24 * 366);
  if (evDevice) q.set('device', evDevice);

  try {
    const data = await api('/api/events?' + q);
    $('evModalSub').textContent = evDay
      ? `${dayLabel(evDay, data.today)}（${evDay}）· 共 ${data.total} 条`
      : `全部时间 · 共 ${data.total} 条`;
    renderEventList($('evList'), data.events, data.today);
    renderPager('evPager', data, (n) => loadAllEvents(n));
  } catch (err) {
    $('evList').innerHTML = `<div class="muted small">加载失败：${esc(err.message)}</div>`;
  }
}

/** 弹窗里的事件列表：比首页那份多一个设备标签，方便跨设备看 */
function renderEventList(el, list, todayIso) {
  if (!list || !list.length) {
    el.innerHTML = '<div class="empty muted small">没有事件</div>';
    return;
  }
  const names = {};
  ((overview && overview.devices) || []).forEach((d) => (names[d.id] = d.name));
  el.innerHTML = list.map((e) => `
    <div class="event">
      <span class="time">${esc(fmtStamp(e.created_at, todayIso))}</span>
      <span class="txt">${KIND_ICON[e.kind] || '·'} ${esc(e.message)}</span>
      ${e.device_id && names[e.device_id] ? `<span class="dev-tag">${esc(names[e.device_id])}</span>` : ''}
    </div>`).join('');
}

function openEventsModal() {
  $('eventsModal').classList.remove('hidden');
  fillEvDevices();
  if (evDay === null) evDay = serverToday || todayStr();
  syncEvDay();
  loadAllEvents(1);
}

/** 把「全部事件」的日期状态刷回控件（null/''/具体日期 三种状态统一处理） */
function syncEvDay() {
  const today = serverToday || todayStr();
  const all = !evDay;
  $('evDay').value = all ? '' : evDay;
  $('evDay').max = today;
  $('btnEvAllTime').classList.toggle('on', all);
  $('btnEvToday').disabled = !all && evDay === today;
  $('btnEvNext').disabled = !all && evDay >= today;
}

function initEventsModal() {
  $('btnAllEvents').addEventListener('click', openEventsModal);

  $('evDay').addEventListener('change', () => {
    evDay = $('evDay').value || '';
    syncEvDay();
    loadAllEvents(1);
  });

  $('btnEvPrev').addEventListener('click', () => {
    evDay = shiftDay(evDay || serverToday || todayStr(), -1);
    syncEvDay();
    loadAllEvents(1);
  });

  $('btnEvNext').addEventListener('click', () => {
    let d = shiftDay(evDay || serverToday || todayStr(), 1);
    if (serverToday && d > serverToday) d = serverToday;
    evDay = d;
    syncEvDay();
    loadAllEvents(1);
  });

  $('btnEvToday').addEventListener('click', () => {
    evDay = serverToday || todayStr();
    syncEvDay();
    loadAllEvents(1);
  });

  $('btnEvAllTime').addEventListener('click', () => {
    evDay = '';
    syncEvDay();
    loadAllEvents(1);
  });

  $('evDevice').addEventListener('change', () => {
    evDevice = $('evDevice').value;
    loadAllEvents(1);
  });
}

/* ---------------- 答题解锁：题库管理 + 设备设置（v2.6.0） ---------------- */

let bankCache = [];        // 题库列表（含科目、题目数）
let bankSubjects = [];     // 科目字典，由服务端给（中文名不要在前端再抄一份）
let bankCacheAt = 0;
let bankView = 0;          // 0 = 题库列表；>0 = 正在看某套题库的题目
let bankEdit = null;       // 正在编辑的题（null = 新增）
let quizPainted = false;   // 设备页的答题设置是否已经画过（用于"别打断正在输入"）

/** 题库列表缓存 15 秒：设备页每 10 秒刷一次，不缓存就每次都多一个请求 */
async function loadBanks(force) {
  if (!force && bankCache.length && Date.now() - bankCacheAt < 15000) return bankCache;
  const d = await api('/api/banks');
  bankCache = d.banks || [];
  bankSubjects = d.subjects || [];
  bankCacheAt = Date.now();
  return bankCache;
}

/** 家长正在答题设置里输入时不要重画：10 秒一次自动刷新会把输入框冲掉 */
function quizEditing() {
  const box = $('quizCfg');
  const a = document.activeElement;
  return !!(box && a && box.contains(a) && a.tagName === 'INPUT');
}

function renderQuizPanel(d) {
  const box = $('quizCfg');
  if (!box) return;
  if (quizPainted && quizEditing()) return;
  box.innerHTML = '<div class="muted small">加载题库…</div>';
  loadBanks().then(() => paintQuizPanel(d)).catch(() => paintQuizPanel(d));
}

function paintQuizPanel(d) {
  const box = $('quizCfg');
  if (!box) return;
  quizPainted = true;
  const picked = new Set(d.quiz_banks || []);
  const rows = bankCache.length
    ? bankCache.map((b) => `
      <label class="bank-pick">
        <input type="checkbox" class="qBank" value="${b.id}" ${picked.has(b.id) ? 'checked' : ''}>
        <span class="bank-name">${esc(b.name)}</span>
        <em class="muted small">${esc(b.subject_label)} · ${b.count} 题</em>
      </label>`).join('')
    : '<div class="muted small">还没有题库，点右上角「管理题库」建一套</div>';

  box.innerHTML = `
    <div class="field">
      <div><label>启用答题解锁</label>
        <div class="hint">用满下面的分钟数就弹一道题，答对才能继续</div></div>
      <span class="switch"><input type="checkbox" id="qEnabled" ${d.quiz_enabled ? 'checked' : ''}><span></span></span>
    </div>
    <div class="cfg-row">
      <div class="field">
        <div><label>每隔多少分钟出一道</label></div>
        <input type="number" id="qInterval" min="1" max="180" value="${d.quiz_interval_minutes || 10}">
      </div>
      <div class="field">
        <div><label>两题都没答对，休息多久</label>
          <div class="hint">分钟。倒计时结束自动解锁，不用家长在旁边</div></div>
        <input type="number" id="qPenalty" min="1" max="60" value="${d.quiz_penalty_minutes || 2}">
      </div>
    </div>
    <div class="sect-title">用哪些题库</div>
    <div class="bank-pick-list">${rows}</div>
    <div class="quiz-foot">
      <span class="muted small">保存会下发 2 条指令（配置 + 题库名单），20 秒内生效</span>
      <button class="btn primary" id="btnSaveQuiz">保存并下发</button>
    </div>`;
  $('btnSaveQuiz').addEventListener('click', saveQuiz);
}

async function saveQuiz() {
  const d = dpData && dpData.device;
  if (!d) return;
  const ids = Array.from(document.querySelectorAll('#quizCfg .qBank:checked'))
    .map((el) => Number(el.value));
  const cfg = {
    type: 'config',
    params: {
      quiz_enabled: $('qEnabled').checked,
      quiz_interval_minutes: Number($('qInterval').value),
      quiz_penalty_minutes: Number($('qPenalty').value),
    },
  };
  try {
    await api(`/api/devices/${d.id}/command`, { method: 'POST', body: JSON.stringify(cfg) });
    await api(`/api/devices/${d.id}/command`, {
      method: 'POST',
      body: JSON.stringify({ type: 'quiz_banks', params: { ids } }),
    });
    toast('已下发，设备 20 秒内应用');
    quizPainted = false;
    renderDevicePage(true);
  } catch (err) {
    toast(err.message);
  }
}

/* ---- 题库管理弹窗 ---- */

async function openBanks(bankId) {
  bankView = bankId || 0;
  bankEdit = null;
  $('banksModal').classList.remove('hidden');
  $('banksTitle').textContent = '题库';
  $('banksSub').textContent = '语文 / 数学 / 英语，题目可以慢慢补';
  $('banksBody').innerHTML = '<div class="muted small">加载中…</div>';
  try {
    await loadBanks(true);
    await paintBanks();
  } catch (err) {
    $('banksBody').innerHTML = `<div class="err">${esc(err.message)}</div>`;
  }
}

async function paintBanks() {
  if (!bankView) return paintBankList();
  return paintBankDetail();
}

function paintBankList() {
  const list = bankCache.map((b) => `
    <div class="bank-row">
      <div class="bank-main">
        <div class="bank-title">${esc(b.name)}
          <span class="tag">${esc(b.subject_label)}</span>
          ${b.builtin ? '<span class="tag builtin">内置</span>' : ''}
        </div>
        <div class="muted small">${b.count} 题${b.note ? ' · ' + esc(b.note) : ''}</div>
      </div>
      <div class="bank-ops">
        <button class="btn ghost small" data-open="${b.id}">查看题目</button>
      </div>
    </div>`).join('') || '<div class="muted small">还没有题库</div>';

  const subjOpts = bankSubjects
    .map((s) => `<option value="${esc(s.key)}">${esc(s.label)}</option>`).join('');

  $('banksBody').innerHTML = `
    <div class="bank-list">${list}</div>
    <div class="sect-title">新建题库</div>
    <div class="form-grid">
      <input id="nbName" type="text" placeholder="名称，例如「一年级下册字词」">
      <select id="nbSubject" class="select">${subjOpts}</select>
      <input id="nbNote" type="text" placeholder="备注（可空）">
    </div>
    <div class="modal-foot">
      ${me && me.is_admin ? '<button class="btn ghost" id="btnRestoreSeed">恢复内置题库</button>' : ''}
      <span class="spacer"></span>
      <button class="btn primary" id="btnCreateBank">创建题库</button>
    </div>`;

  document.querySelectorAll('[data-open]').forEach((el) =>
    el.addEventListener('click', () => openBanks(Number(el.dataset.open))));

  $('btnCreateBank').addEventListener('click', async () => {
    const name = $('nbName').value.trim();
    if (!name) { toast('请填题库名称'); return; }
    try {
      const r = await api('/api/banks', {
        method: 'POST',
        body: JSON.stringify({
          name,
          subject: $('nbSubject').value,
          note: $('nbNote').value.trim(),
        }),
      });
      await loadBanks(true);
      bankView = r.id;
      bankEdit = null;
      await paintBanks();
      toast('已创建');
    } catch (err) { toast(err.message); }
  });

  const seedBtn = $('btnRestoreSeed');
  if (seedBtn) seedBtn.addEventListener('click', async () => {
    try {
      const r = await api('/api/banks/seed', { method: 'POST' });
      await loadBanks(true);
      await paintBanks();
      toast(`内置题库已恢复：${r.banks} 套 / ${r.questions} 题`);
    } catch (err) { toast(err.message); }
  });
}

async function paintBankDetail() {
  const bank = bankCache.find((b) => b.id === bankView);
  if (!bank) { bankView = 0; return paintBankList(); }
  $('banksTitle').textContent = bank.name;
  $('banksSub').textContent = `${bank.subject_label} · ${bank.count} 题${bank.builtin ? ' · 内置题库（所有人都能看到）' : ''}`;

  const data = await api(`/api/banks/${bankView}/questions?limit=100`);
  const qs = data.questions || [];
  const rows = qs.map((q) => `
    <div class="q-row">
      <div class="q-main">
        <div class="q-stem">${esc(q.stem)}</div>
        <div class="muted small">
          ${q.type === 'spell' ? '补全单词' : '选择题'} · 答案 <b>${esc(q.answer)}</b>
          ${q.tpl ? ' · 模板 ' + esc(q.tpl) : ''}
          ${q.options && q.options.length ? ' · 选项 ' + q.options.map(esc).join(' / ') : ''}
        </div>
      </div>
      <div class="q-ops">
        <button class="btn ghost small" data-edit="${q.id}">编辑</button>
        <button class="btn ghost small danger-text" data-del="${q.id}">删除</button>
      </div>
    </div>`).join('') || '<div class="muted small">这套题库还没有题</div>';

  const subj = bank.subject;
  $('banksBody').innerHTML = `
    <div class="modal-back">
      <button class="btn ghost small" id="btnBankBack">← 返回题库列表</button>
      ${bank.count > qs.length ? `<span class="muted small">只显示前 ${qs.length} 题，共 ${bank.count} 题</span>` : ''}
    </div>
    <div class="q-list">${rows}</div>

    <div class="sect-title">${bankEdit ? '修改这道题' : '新增一道题'}</div>
    <div class="q-form">
      <select id="qfType" class="select">
        <option value="choice">选择题（四选一）</option>
        <option value="spell">补全单词（填字母）</option>
      </select>
      <input id="qfStem" type="text" placeholder="题干，例如「3 + 5 = ?」或「补全单词：小猫」">
      <input id="qfOptions" type="text" placeholder="选项，用逗号分隔：8,7,9,6（选择题必填）">
      <input id="qfAnswer" type="text" placeholder="正确答案，例如 8 或 cat">
      <input id="qfTpl" type="text" placeholder="模板（补全单词用）：c_t，下划线是要填的空">
      <input id="qfHint" type="text" placeholder="提示（可空）">
      <select id="qfLevel" class="select">
        <option value="1">难度 1</option><option value="2">难度 2</option><option value="3">难度 3</option>
      </select>
    </div>
    <div class="modal-foot">
      ${bankEdit ? '<button class="btn ghost" id="btnQfCancel">取消修改</button>' : ''}
      <span class="spacer"></span>
      <button class="btn primary" id="btnQfSave">${bankEdit ? '保存修改' : '添加'}</button>
    </div>

    <details class="cfg-box" style="margin-top:14px">
      <summary>批量导入（从 Excel 复制也行）</summary>
      <div class="import-help muted small">
        每行一道题，竖线分隔（全角｜和 Tab 也认）：<br>
        选择题：<code>题干|选项A|选项B|选项C|选项D|正确答案</code><br>
        补全单词：<code>S|题干|模板(c_t)|完整答案|提示</code><br>
        也可以整段粘 JSON 数组。<code>#</code> 开头的行会跳过。
      </div>
      <textarea id="qfImport" rows="6" placeholder="3 + 5 = ?|8|7|9|6|8"></textarea>
      <div class="modal-foot">
        <span class="muted small" id="qfImportMsg"></span>
        <span class="spacer"></span>
        <button class="btn primary small" id="btnImport">导入</button>
      </div>
    </details>`;

  document.querySelectorAll('[data-open]').forEach((el) =>
    el.addEventListener('click', () => openBanks(Number(el.dataset.open))));
  $('btnBankBack').addEventListener('click', () => { bankView = 0; bankEdit = null; paintBanks(); });
  document.querySelectorAll('[data-edit]').forEach((el) =>
    el.addEventListener('click', () => {
      const q = qs.find((x) => x.id === Number(el.dataset.edit));
      if (q) { bankEdit = q; paintBankDetail(); }
    }));
  document.querySelectorAll('[data-del]').forEach((el) =>
    el.addEventListener('click', async () => {
      if (!confirm('删除这道题？')) return;
      try {
        await api(`/api/banks/${bankView}/questions/${el.dataset.del}/delete`, { method: 'POST' });
        await loadBanks(true);
        await paintBankDetail();
        toast('已删除');
      } catch (err) { toast(err.message); }
    }));

  // 编辑态回填
  if (bankEdit) {
    $('qfType').value = bankEdit.type || 'choice';
    $('qfStem').value = bankEdit.stem || '';
    $('qfOptions').value = (bankEdit.options || []).join(',');
    $('qfAnswer').value = bankEdit.answer || '';
    $('qfTpl').value = bankEdit.tpl || '';
    $('qfHint').value = bankEdit.hint || '';
    $('qfLevel').value = String(bankEdit.level || 1);
    $('btnQfCancel').addEventListener('click', () => { bankEdit = null; paintBankDetail(); });
  }

  $('btnQfSave').addEventListener('click', async () => {
    const type = $('qfType').value;
    const body = {
      type,
      stem: $('qfStem').value.trim(),
      options: $('qfOptions').value.split(',').map((s) => s.trim()).filter(Boolean),
      answer: $('qfAnswer').value.trim(),
      tpl: type === 'spell' ? $('qfTpl').value.trim() : '',
      hint: $('qfHint').value.trim(),
      level: Number($('qfLevel').value),
    };
    if (!body.stem) { toast('题干不能为空'); return; }
    if (!body.answer) { toast('答案不能为空'); return; }
    try {
      if (bankEdit) {
        await api(`/api/banks/${bankView}/questions/${bankEdit.id}`, {
          method: 'POST', body: JSON.stringify(body),
        });
        bankEdit = null;
      } else {
        await api(`/api/banks/${bankView}/questions`, {
          method: 'POST', body: JSON.stringify(body),
        });
      }
      await loadBanks(true);
      await paintBankDetail();
      toast('已保存');
    } catch (err) { toast(err.message); }
  });

  $('btnImport').addEventListener('click', async () => {
    const text = $('qfImport').value;
    if (!text.trim()) { toast('先粘贴内容'); return; }
    try {
      const r = await api(`/api/banks/${bankView}/import`, {
        method: 'POST',
        body: JSON.stringify({ text, level: Number($('qfLevel').value) }),
      });
      const errs = (r.errors || []).slice(0, 5);
      $('qfImportMsg').innerHTML = r.ok
        ? `导入 ${r.imported} 题${r.skipped ? `，${r.skipped} 题超出上限未导入` : ''}${errs.length ? '；' + errs.length + ' 行有问题' : ''}`
        : esc(r.message || '导入失败');
      await loadBanks(true);
      await paintBankDetail();
      if (r.imported) toast(`已导入 ${r.imported} 题`);
    } catch (err) { toast(err.message); }
  });
}

/* ---------------- 弹窗关闭 ---------------- */

document.addEventListener('click', (e) => {
  const closer = e.target.closest('[data-close]');
  if (closer) {
    $(closer.dataset.close).classList.add('hidden');
  }
  if (e.target.classList.contains('modal')) {
    e.target.classList.add('hidden');
  }
});

document.addEventListener('keydown', (e) => {
  if (e.key !== 'Escape') return;
  // 设备页用「返回」退出，只有弹窗才吃 Esc
  ['pairModal', 'usersModal', 'accountModal', 'eventsModal', 'banksModal'].forEach((id) =>
    $(id).classList.add('hidden'));
});

/* ---------------- 启动 ---------------- */

// 设备页里那些固定元素（返回、日期、操作按钮）只绑一次，
// 页面内容是每次渲染重建的，不会跟着重复绑定。
initDevicePage();
initEventsModal();

// 题库入口：顶栏（全局）和设备页（就地跳到题库）各一个
$('btnBanks').addEventListener('click', () => openBanks());
$('btnQuizBanks').addEventListener('click', () => openBanks());

(async function boot() {
  if (!token) { showLogin(); return; }
  try {
    const d = await api('/api/me');
    me = d.user;
    localStorage.setItem(USER_KEY, JSON.stringify(me));
    enterApp();
  } catch (e) {
    showLogin();
  }
})();
