//! 全部 HTTP 接口。
//!
//! 分两类：
//!   * 管理端（`/api/...`）：家长浏览器用，带 Bearer token；
//!   * 设备端（`/api/device/...`）：平板用，带设备 token（配对后拿到）。
//!
//! 前端页面（index.html / app.css / app.js）用 `include_str!` 编进二进制，
//! 所以服务器上只需要一个可执行文件，不用带目录结构。

use std::net::SocketAddr;
use std::sync::Arc;

use axum::body::Body;
use axum::extract::{ConnectInfo, Path as UrlPath, Query, State};
use axum::http::{header, HeaderMap, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::Json;
use serde::Deserialize;
use serde_json::{json, Map, Value};

use crate::store::{Device, PairCode, PresenceSeg, Session, Store, UsageSeg, User};
use crate::store::{QuietRule, DAYS_ALL};
use crate::util::{
    clamp, hash_password, json_bool, local_day, now_ts, rand_digits, rand_hex, verify_password,
};
use crate::AppState;

/// 最近一次心跳在这个秒数内就算在线
pub const ONLINE_WINDOW: f64 = 90.0;
/// 指令发出去这么久还没回执，重新排队
pub const COMMAND_RESEND_AFTER: f64 = 120.0;
/// 配对码有效期（秒）
pub const PAIR_CODE_TTL: f64 = 600.0;
/// 心跳间隔，下发给设备
const HEARTBEAT_SECONDS: i64 = 20;
const MAX_MS: i64 = 24 * 3600 * 1000;
/// 一台设备最多能配几条停用时段。
///
/// 定 8 条而不是"不限"：家长的名义需求是两三条（早上上学前、晚上睡觉、午休），
/// 给到 8 条足够覆盖；再多设备端一屏也放不下，而且重叠时"以哪条为准"会变得难解释。
const MAX_QUIET_RULES: usize = 8;

/// 星期位掩码 → 中文。0 表示每天（前端"一天都没勾"就等于全选）。
fn days_label(days: i64) -> String {
    if days & DAYS_ALL == 0 || days & DAYS_ALL == DAYS_ALL {
        return "每天".into();
    }
    // 第 0 位是周一
    const NAMES: [&str; 7] = ["一", "二", "三", "四", "五", "六", "日"];
    // 连续段合成"周一至周五"，比"周一、周二、周三"好读
    let picked: Vec<bool> = (0..7).map(|i| days & (1 << i) != 0).collect();
    let mut out: Vec<String> = Vec::new();
    let mut i = 0;
    while i < 7 {
        if !picked[i] {
            i += 1;
            continue;
        }
        let mut j = i;
        while j + 1 < 7 && picked[j + 1] {
            j += 1;
        }
        out.push(if j - i >= 2 {
            format!("周{}至周{}", NAMES[i], NAMES[j])
        } else {
            (i..=j).map(|k| format!("周{}", NAMES[k])).collect::<Vec<_>>().join("、")
        });
        i = j + 1;
    }
    out.join("、")
}

const INDEX_HTML: &str = include_str!("../static/index.html");
const APP_CSS: &str = include_str!("../static/app.css");
const APP_JS: &str = include_str!("../static/app.js");

/// 允许远程下发的指令；值是给事件流看的中文说明
pub const ALLOWED_COMMANDS: &[(&str, &str)] = &[
    ("lock", "远程锁定（立即进入强制休息）"),
    ("unlock", "解除锁定（跳过本次休息）"),
    ("pause", "暂停守护（不再限制时间）"),
    ("enable", "开启守护"),
    ("config", "修改配置参数"),
    ("sync", "让设备立刻同步一次"),
    // 惩罚是「拆防护次数」越多越长的，误判累积上去之后孩子一次要歇很久。
    // 家长需要一个能远程把计数归零的出口，否则只能拿着设备手动点。
    ("reset_tamper", "清零拆防护计数（惩罚时长回到第 1 档）"),
    // 停用时段（宵禁）：到点自动锁屏、到点自动解锁，不需要家长在跟前。
    // 规则列表放在设备配置里下发（指令只带 id，设备回执里会带明细）。
    ("quiet", "更新停用时段（每天几点到几点不能玩）"),
    // 每日总时长（v2.5.0）：和"连续使用"是两个维度，用完当天不再自动解除。
    ("day_limit", "设置每日总时长上限"),
    ("day_unlock", "今日解除（今天不再按额度锁）"),
    ("day_relock", "恢复今日限制"),
];

/// 版本号比较用（"2.3.1" → (2,3,1)）。解析不出来的段当 0，不 panic。
fn ver_tuple(v: &str) -> (u64, u64, u64) {
    let mut it = v.trim().split('.').map(|s| s.parse::<u64>().unwrap_or(0));
    (
        it.next().unwrap_or(0),
        it.next().unwrap_or(0),
        it.next().unwrap_or(0),
    )
}

fn command_label(kind: &str) -> &str {
    ALLOWED_COMMANDS
        .iter()
        .find(|(k, _)| *k == kind)
        .map(|(_, v)| *v)
        .unwrap_or(kind)
}

// ======================================================================
// 统一错误
// ======================================================================

pub struct ApiError {
    status: StatusCode,
    msg: String,
}

impl ApiError {
    fn new(status: StatusCode, msg: impl Into<String>) -> Self {
        Self {
            status,
            msg: msg.into(),
        }
    }
    fn bad(msg: impl Into<String>) -> Self {
        Self::new(StatusCode::BAD_REQUEST, msg)
    }
    fn unauthorized(msg: impl Into<String>) -> Self {
        Self::new(StatusCode::UNAUTHORIZED, msg)
    }
    fn not_found(msg: impl Into<String>) -> Self {
        Self::new(StatusCode::NOT_FOUND, msg)
    }
}

impl IntoResponse for ApiError {
    fn into_response(self) -> Response {
        // 前端错误提示读的是 data.detail，保持这个字段名
        (self.status, Json(json!({ "detail": self.msg }))).into_response()
    }
}

type ApiResult<T> = Result<T, ApiError>;

/// 已登录用户的最小信息（鉴权结果）
pub struct Auth {
    pub id: String,
    pub username: String,
    pub role: String,
}

impl Auth {
    pub fn is_admin(&self) -> bool {
        self.role == "admin"
    }
}

// ======================================================================
// 工具
// ======================================================================

fn asset(content_type: &'static str, body: &'static str) -> Response {
    Response::builder()
        .status(StatusCode::OK)
        .header(header::CONTENT_TYPE, content_type)
        .header(header::CACHE_CONTROL, "no-cache")
        .body(Body::from(body))
        .unwrap_or_else(|_| StatusCode::INTERNAL_SERVER_ERROR.into_response())
}

fn bearer(headers: &HeaderMap) -> Option<String> {
    let raw = headers.get(header::AUTHORIZATION)?.to_str().ok()?;
    match raw.get(..7) {
        Some(p) if p.eq_ignore_ascii_case("bearer ") => {
            raw.get(7..).map(|s| s.trim().to_string()).filter(|s| !s.is_empty())
        }
        _ => None,
    }
}

fn client_ip(headers: &HeaderMap, ci: &SocketAddr) -> String {
    if let Some(fwd) = headers.get("x-forwarded-for").and_then(|v| v.to_str().ok()) {
        if let Some(first) = fwd.split(',').next() {
            let s = first.trim();
            if !s.is_empty() {
                return s.to_string();
            }
        }
    }
    if let Some(real) = headers.get("x-real-ip").and_then(|v| v.to_str().ok()) {
        let s = real.trim();
        if !s.is_empty() {
            return s.to_string();
        }
    }
    ci.ip().to_string()
}

/// 校验管理端 token。顺带做滑动续期（只改内存，不每次都落盘）
///
/// 返回值从"token"升级成 [Auth]：权限判断必须知道**是谁**在看，
/// 只有 admin 能跨账号看设备。
fn require_user(st: &AppState, store: &mut Store, headers: &HeaderMap) -> ApiResult<Auth> {
    let token = bearer(headers).ok_or_else(|| ApiError::unauthorized("未登录"))?;
    let now = now_ts();
    let days = st.cfg.session_days as f64;

    let uid = {
        let sess = store
            .sessions
            .iter_mut()
            .find(|s| s.token == token)
            .ok_or_else(|| ApiError::unauthorized("未登录"))?;
        if sess.expires_at < now {
            return Err(ApiError::unauthorized("登录已过期，请重新登录"));
        }
        sess.expires_at = now + days * 86400.0;
        sess.user_id.clone()
    };

    let idx = store
        .user_index(&uid)
        .ok_or_else(|| ApiError::unauthorized("账号不存在，请重新登录"))?;
    let u = &store.users[idx];
    if u.disabled {
        return Err(ApiError::new(
            StatusCode::FORBIDDEN,
            "账号已被停用，请联系管理员",
        ));
    }
    Ok(Auth {
        id: u.id.clone(),
        username: u.username.clone(),
        role: u.role.clone(),
    })
}

fn require_admin(st: &AppState, store: &mut Store, headers: &HeaderMap) -> ApiResult<Auth> {
    let auth = require_user(st, store, headers)?;
    if !auth.is_admin() {
        return Err(ApiError::new(
            StatusCode::FORBIDDEN,
            "只有管理员可以做这个操作",
        ));
    }
    Ok(auth)
}

/// 这个账号能不能看 / 操作这台设备
fn can_see(auth: &Auth, d: &Device) -> bool {
    auth.is_admin() || (!d.owner_id.is_empty() && d.owner_id == auth.id)
}

/// 取设备下标，同时校验归属；看不到的设备一律按"不存在"处理，
/// 不返回 403 —— 免得普通账号靠状态码探测出别的账号有哪些设备 id。
fn device_for(
    store: &Store,
    auth: &Auth,
    device_id: &str,
) -> ApiResult<usize> {
    let idx = store
        .device_index(device_id)
        .ok_or_else(|| ApiError::not_found("设备不存在"))?;
    if !can_see(auth, &store.devices[idx]) {
        return Err(ApiError::not_found("设备不存在"));
    }
    Ok(idx)
}

/// 校验设备 token，返回设备下标
fn require_device(store: &Store, headers: &HeaderMap) -> ApiResult<usize> {
    let token = bearer(headers).ok_or_else(|| ApiError::unauthorized("缺少设备令牌"))?;
    store
        .device_index_by_token(&token)
        .ok_or_else(|| ApiError::unauthorized("设备令牌无效，请重新配对"))
}

/// 设备对象转 JSON：去掉 token 与内部 owner_id，补上 online / offline_seconds / owner
///
/// `owner` 传的是账号名（管理员视角下需要一眼看出设备属于谁）。
fn device_json(d: &Device, now: f64, owner: &str) -> Value {
    let mut v = serde_json::to_value(d).unwrap_or(Value::Null);
    if let Some(obj) = v.as_object_mut() {
        obj.remove("token");
        obj.remove("owner_id");
        obj.insert("owner".into(), json!(owner));
        let seen = d.last_seen;
        let online = seen > 0.0 && (now - seen) <= ONLINE_WINDOW;
        obj.insert("online".into(), Value::Bool(online));
        obj.insert(
            "offline_seconds".into(),
            if seen > 0.0 {
                json!((now - seen) as i64)
            } else {
                Value::Null
            },
        );
    }
    v
}

fn owner_name(store: &Store, user_id: &str) -> String {
    store
        .users
        .iter()
        .find(|u| u.id == user_id)
        .map(|u| u.username.clone())
        .unwrap_or_else(|| "（已删除的账号）".into())
}

/// 当前账号能看到的设备
fn visible_devices<'a>(store: &'a Store, auth: &Auth) -> Vec<&'a Device> {
    store.devices.iter().filter(|d| can_see(auth, d)).collect()
}

/// 当前账号能看到的设备 id；管理员返回 `None`（表示不过滤）。
///
/// 普通账号返回空 Vec 时，SQL 侧会退化成恒假条件 —— 也就是"一条也看不到"，
/// 这是正确的：刚建的账号还没配对任何设备。
fn visible_ids(store: &Store, auth: &Auth) -> Option<Vec<String>> {
    if auth.is_admin() {
        None
    } else {
        Some(
            store
                .devices
                .iter()
                .filter(|d| can_see(auth, d))
                .map(|d| d.id.clone())
                .collect(),
        )
    }
}

/// 事件流按权限过滤，取 `[from, to)` 区间内最新的 `limit` 条。
///
/// 以前是全量读进内存再 sort+truncate；现在交给 SQLite 走 `ts` 索引。
fn events_desc(store: &Store, auth: &Auth, from: f64, to: f64, limit: usize) -> Vec<Value> {
    let visible = visible_ids(store, auth);
    let (items, _) = store
        .db
        .events_page(None, visible.as_deref(), from, to, limit as i64, 0);
    items.into_iter().map(|e| json!(e)).collect()
}

// ======================================================================
// 请求体
// ======================================================================

#[derive(Deserialize)]
pub struct LoginBody {
    /// 老版本前端只发密码，这里给个默认值，向后兼容
    #[serde(default)]
    pub username: String,
    pub password: String,
}

#[derive(Deserialize)]
pub struct NewUserBody {
    pub username: String,
    pub password: String,
    #[serde(default)]
    pub role: String,
    #[serde(default)]
    pub note: String,
}

#[derive(Deserialize)]
pub struct UpdateUserBody {
    #[serde(default)]
    pub password: Option<String>,
    #[serde(default)]
    pub role: Option<String>,
    #[serde(default)]
    pub disabled: Option<bool>,
    #[serde(default)]
    pub note: Option<String>,
}

#[derive(Deserialize)]
pub struct PasswordBody {
    #[serde(default)]
    pub old_password: String,
    pub new_password: String,
}

#[derive(Deserialize)]
pub struct CommandBody {
    #[serde(rename = "type")]
    pub kind: String,
    #[serde(default)]
    pub params: Option<Value>,
}

#[derive(Deserialize)]
pub struct PairBody {
    pub code: String,
    #[serde(default)]
    pub name: String,
    #[serde(default)]
    pub model: Option<String>,
    #[serde(default)]
    pub app_version: Option<String>,
}

#[derive(Deserialize)]
pub struct ResultBody {
    pub command_id: u64,
    pub ok: bool,
    #[serde(default)]
    pub message: Option<String>,
}

// ======================================================================
// 账号相关常量与校验
// ======================================================================

/// 首次启动时内置管理员的默认密码
pub const DEFAULT_PASSWORD: &str = "guardian2026";

/// 用来"空跑一次哈希"的假记录。
///
/// 用户不存在时也要走一遍 PBKDF2，否则"用户不存在"会立刻返回、
/// "密码错误"要算几十毫秒，攻击者靠响应时间就能枚举出账号名。
const DUMMY_HASH: &str = "pbkdf2$60000$00000000000000000000000000000000$0000000000000000000000000000000000000000000000000000000000000000";

fn check_password_strength(pw: &str) -> ApiResult<()> {
    let n = pw.chars().count();
    if n < 6 {
        return Err(ApiError::bad("密码至少 6 位"));
    }
    if n > 128 {
        return Err(ApiError::bad("密码过长（最多 128 位）"));
    }
    Ok(())
}

fn valid_username(name: &str) -> ApiResult<String> {
    let n = name.trim();
    let len = n.chars().count();
    if len < 2 || len > 24 {
        return Err(ApiError::bad("用户名长度需在 2~24 个字符之间"));
    }
    if !n
        .chars()
        .all(|c| c.is_alphanumeric() || c == '_' || c == '-' || c == '.')
    {
        return Err(ApiError::bad("用户名只能包含中英文、数字、下划线、点和短横线"));
    }
    Ok(n.to_string())
}

fn user_json(u: &User, device_count: usize) -> Value {
    json!({
        "id": u.id,
        "username": u.username,
        "role": u.role,
        "is_admin": u.is_admin(),
        "disabled": u.disabled,
        "note": u.note,
        "created_at": u.created_at,
        "last_login": u.last_login,
        "device_count": device_count,
    })
}

// ======================================================================
// 页面与静态资源
// ======================================================================

pub async fn index() -> Response {
    asset("text/html; charset=utf-8", INDEX_HTML)
}

pub async fn static_css() -> Response {
    asset("text/css; charset=utf-8", APP_CSS)
}

pub async fn static_js() -> Response {
    asset("application/javascript; charset=utf-8", APP_JS)
}

pub async fn favicon() -> Response {
    StatusCode::NO_CONTENT.into_response()
}

pub async fn healthz() -> Json<Value> {
    Json(json!({ "ok": true, "time": now_ts() }))
}

// ======================================================================
// 管理端：登录
// ======================================================================

pub async fn login(
    State(st): State<Arc<AppState>>,
    ConnectInfo(ci): ConnectInfo<SocketAddr>,
    headers: HeaderMap,
    Json(body): Json<LoginBody>,
) -> ApiResult<Json<Value>> {
    let ip = client_ip(&headers, &ci);
    let now = now_ts();
    let username = {
        let u = body.username.trim();
        if u.is_empty() { "admin".to_string() } else { u.to_string() }
    };

    // 限流：同一 IP 5 分钟内失败 8 次就锁一会儿。
    // 这里按 IP 而不是按用户名限流，是为了顺手挡住"拿一堆用户名撞库"。
    {
        let mut fails = st.login_fails.lock().unwrap_or_else(|e| e.into_inner());
        let hist = fails.entry(ip.clone()).or_default();
        hist.retain(|t| now - *t < 300.0);
        if hist.len() >= 8 {
            return Err(ApiError::new(
                StatusCode::TOO_MANY_REQUESTS,
                "尝试次数过多，请 5 分钟后再试",
            ));
        }
    }

    let mut store = st.lock();

    let fail = |st: &AppState, ip: String, msg: &str| -> ApiError {
        let mut fails = st.login_fails.lock().unwrap_or_else(|e| e.into_inner());
        fails.entry(ip).or_default().push(now);
        ApiError::unauthorized(msg.to_string())
    };

    let idx = match store.user_index_by_name(&username) {
        Some(i) => i,
        // 用户不存在也走一遍哈希校验，避免靠响应快慢判断账号是否存在
        None => {
            let _ = verify_password(&body.password, DUMMY_HASH);
            return Err(fail(&st, ip, "用户名或密码不正确"));
        }
    };

    let (uid, pwd_hash, role, disabled) = {
        let u = &store.users[idx];
        (u.id.clone(), u.password.clone(), u.role.clone(), u.disabled)
    };

    if !verify_password(&body.password, &pwd_hash) {
        return Err(fail(&st, ip, "用户名或密码不正确"));
    }
    if disabled {
        return Err(ApiError::new(
            StatusCode::FORBIDDEN,
            "这个账号已被停用，请联系管理员",
        ));
    }

    st.login_fails
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .remove(&ip);

    let using_default = store.users[idx].username == "admin"
        && verify_password(DEFAULT_PASSWORD, &pwd_hash);

    let name = store.users[idx].username.clone();
    store.users[idx].last_login = now;

    let token = rand_hex(32);
    let exp = now + st.cfg.session_days as f64 * 86400.0;
    store.sessions.push(Session {
        token: token.clone(),
        created_at: now,
        expires_at: exp,
        ip: ip.clone(),
        user_id: uid.clone(),
    });
    store.add_event(
        None,
        "admin_login",
        format!(
            "账号 {} 登录后台（{}）",
            name,
            if ip.is_empty() { "未知 IP" } else { ip.as_str() }
        ),
    );
    store.save();

    Ok(Json(json!({
        "token": token,
        "expires_at": exp,
        "user": {
            "id": uid,
            "username": name,
            "role": role,
            "is_admin": role == "admin",
        },
        "using_default_password": using_default,
    })))
}

pub async fn me(State(st): State<Arc<AppState>>, headers: HeaderMap) -> ApiResult<Json<Value>> {
    let mut store = st.lock();
    let auth = require_user(&st, &mut store, &headers)?;
    let devices = visible_devices(&store, &auth).len();
    Ok(Json(json!({
        "user": {
            "id": auth.id,
            "username": auth.username,
            "role": auth.role,
            "is_admin": auth.is_admin(),
        },
        "device_count": devices,
    })))
}

/// 改自己的密码：必须验证旧密码
pub async fn change_my_password(
    State(st): State<Arc<AppState>>,
    headers: HeaderMap,
    Json(body): Json<PasswordBody>,
) -> ApiResult<Json<Value>> {
    let mut store = st.lock();
    let auth = require_user(&st, &mut store, &headers)?;

    let idx = store
        .user_index(&auth.id)
        .ok_or_else(|| ApiError::not_found("账号不存在"))?;
    if !verify_password(&body.old_password, &store.users[idx].password) {
        return Err(ApiError::bad("原密码不正确"));
    }
    check_password_strength(&body.new_password)?;

    store.users[idx].password = hash_password(&body.new_password);
    // 改完密码把该账号其它会话踢掉，只有当前这枚 token 保留
    let token = bearer(&headers).unwrap_or_default();
    store
        .sessions
        .retain(|s| s.user_id != auth.id || s.token == token);
    store.add_event(
        None,
        "user",
        format!("账号 {} 修改了自己的密码", auth.username),
    );
    store.save();
    Ok(Json(json!({ "ok": true })))
}

pub async fn logout(State(st): State<Arc<AppState>>, headers: HeaderMap) -> ApiResult<Json<Value>> {
    let mut store = st.lock();
    let auth = require_user(&st, &mut store, &headers)?;
    let token = bearer(&headers).unwrap_or_default();
    store
        .sessions
        .retain(|s| s.token != token || s.user_id != auth.id);
    store.save();
    Ok(Json(json!({ "ok": true })))
}

// ======================================================================
// 管理端：统计与设备
// ======================================================================

pub async fn overview(State(st): State<Arc<AppState>>, headers: HeaderMap) -> ApiResult<Json<Value>> {
    let mut store = st.lock();
    let auth = require_user(&st, &mut store, &headers)?;

    let now = now_ts();
    let tz = st.cfg.tz_offset_hours;
    let today = local_day(now, tz);

    // ★ 权限边界就在这一句：普通账号拿到的 devices 只包含自己的设备，
    //   统计、趋势、事件全部基于这个子集算，不会漏出别人的数据。
    let mine = visible_devices(&store, &auth);
    let mine_ids: Vec<String> = mine.iter().map(|d| d.id.clone()).collect();

    let devices: Vec<Value> = mine
        .iter()
        .map(|d| {
            let mut v = device_json(d, now, &owner_name(&store, &d.owner_id));
            // 今日解锁 / 开关机 / 亮屏时长：设备卡上直接给数字，
            // 免得家长为了看一眼"今天开了几次机"还要点进设备页
            let (unlocks, boots, shutdowns, screen_ms, sys_ms) =
                store.db.daystat_get(&d.id, &today);
            if let Some(obj) = v.as_object_mut() {
                obj.insert("today_unlocks".into(), json!(unlocks));
                obj.insert("today_boots".into(), json!(boots));
                obj.insert("today_shutdowns".into(), json!(shutdowns));
                obj.insert("today_screen_ms".into(), json!(screen_ms));
                obj.insert("today_sys_ms".into(), json!(sys_ms));
            }
            v
        })
        .collect();
    let online = mine
        .iter()
        .filter(|d| d.last_seen > 0.0 && (now - d.last_seen) <= ONLINE_WINDOW)
        .count();
    let resting = mine
        .iter()
        .filter(|d| d.last_seen > 0.0 && (now - d.last_seen) <= ONLINE_WINDOW && d.resting)
        .count();
    let off = mine.iter().filter(|d| !d.enabled).count();
    let total_today: i64 = mine.iter().map(|d| d.today_used_ms).sum();

    // 权限边界：普通账号只统计自己名下设备；管理员为 None（不过滤）
    let scope: Option<Vec<String>> = if auth.is_admin() {
        None
    } else {
        Some(mine_ids.clone())
    };

    let today_from = crate::util::day_start_ts(&today, tz).unwrap_or(now);
    let rest_today = store
        .db
        .count_kind("rest_start", today_from, today_from + 86400.0, scope.as_deref());

    // 近 7 天趋势（只统计自己名下的设备）
    let days: Vec<String> = (0..7)
        .rev()
        .map(|i| local_day(now - i as f64 * 86400.0, tz))
        .collect();
    let used_rows = store.db.daily_between(&days, scope.as_deref());
    let mut trend = Vec::with_capacity(7);
    for day in &days {
        let used: i64 = used_rows
            .iter()
            .filter(|r| &r.day == day)
            .map(|r| r.used_ms)
            .sum();
        let d_from = crate::util::day_start_ts(day, tz).unwrap_or(now);
        let rest = store
            .db
            .count_kind("rest_start", d_from, d_from + 86400.0, scope.as_deref());
        trend.push(json!({ "day": day, "used_ms": used, "rest_count": rest }));
    }

    // 管理员登录时顺手校验"内置管理员是否还在用默认密码"
    let mut using_default = false;
    if auth.is_admin() {
        if let Some(i) = store.user_index(&auth.id) {
            using_default = store.users[i].username == "admin"
                && verify_password(DEFAULT_PASSWORD, &store.users[i].password);
        }
    }

    Ok(Json(json!({
        "now": now,
        "today": today,
        "user": {
            "id": auth.id,
            "username": auth.username,
            "role": auth.role,
            "is_admin": auth.is_admin(),
        },
        "stats": {
            "devices_total": mine.len(),
            "devices_online": online,
            "devices_resting": resting,
            "devices_off": off,
            "today_used_ms": total_today,
            "today_rest_count": rest_today,
        },
        "trend": trend,
        "devices": devices,
        // 首页只给最近 48 小时 —— 事件是长期累积的，全量塞进首屏既慢又没人看
        "events": events_desc(&store, &auth, now - 48.0 * 3600.0, now + 60.0, 60),
        "events_window_hours": 48,
        "using_default_password": using_default,
    })))
}

pub async fn list_devices(
    State(st): State<Arc<AppState>>,
    headers: HeaderMap,
) -> ApiResult<Json<Value>> {
    let mut store = st.lock();
    let auth = require_user(&st, &mut store, &headers)?;
    let now = now_ts();
    let devices: Vec<Value> = visible_devices(&store, &auth)
        .iter()
        .map(|d| device_json(d, now, &owner_name(&store, &d.owner_id)))
        .collect();
    Ok(Json(json!({ "devices": devices })))
}

pub async fn device_detail(
    State(st): State<Arc<AppState>>,
    UrlPath(device_id): UrlPath<String>,
    headers: HeaderMap,
) -> ApiResult<Json<Value>> {
    let mut store = st.lock();
    let auth = require_user(&st, &mut store, &headers)?;

    let idx = device_for(&store, &auth, &device_id)?;
    let now = now_ts();
    let device = device_json(
        &store.devices[idx],
        now,
        &owner_name(&store, &store.devices[idx].owner_id),
    );

    // 近 14 天每日汇总（SQLite 已按 day DESC 排好）
    let daily: Vec<Value> = store
        .db
        .daily_of(&device_id, 14)
        .into_iter()
        .map(|r| {
            json!({
                "device_id": r.device_id,
                "day": r.day,
                "used_ms": r.used_ms,
                "rest_count": r.rest_count
            })
        })
        .collect();

    let mut commands: Vec<Value> = store
        .commands
        .iter()
        .filter(|c| c.device_id == device_id)
        .map(|c| json!(c))
        .collect();
    commands.sort_by(|a, b| {
        let ia = a.get("id").and_then(|v| v.as_u64()).unwrap_or(0);
        let ib = b.get("id").and_then(|v| v.as_u64()).unwrap_or(0);
        ib.cmp(&ia)
    });
    commands.truncate(20);

    // 详情页默认给最近 30 条事件；按天查询走 /api/devices/{id}/events
    let (evs, _) = store
        .db
        .events_page(Some(&device_id), None, 0.0, now + 60.0, 30, 0);
    let events: Vec<Value> = evs.into_iter().map(|e| json!(e)).collect();

    // ---- 今日概览：设备主页首屏直接用，省掉一次额外请求 ----
    let tz = st.cfg.tz_offset_hours;
    let today = local_day(now, tz);
    let t_from = crate::util::day_start_ts(&today, tz).unwrap_or(now);
    let t_to = t_from + 86400.0;
    let today_usage = store.usage_of(&device_id, t_from, t_to);
    let merged_today = crate::store::merge_adjacent(&today_usage, t_from, t_to);
    let today_apps = usage_apps(&merged_today, t_from, t_to);
    let today_online_ms: f64 = store
        .presence_of(&device_id, t_from, t_to)
        .iter()
        .filter_map(|p| clip_seg(p.start, p.end, t_from, t_to))
        .map(|(s, e)| e - s)
        .sum();
    let usage_tracked = store.has_usage(&device_id);
    let screen_tracked = store.db.has_screen(&device_id);
    let (unlocks, boots, shutdowns, ds_screen_ms, sys_ms) = store.db.daystat_get(&device_id, &today);

    Ok(Json(json!({
        "device": device,
        "daily": daily,
        "commands": commands,
        "events": events,
        "today": {
            "day": today,
            "apps": today_apps,
            "online_ms": (today_online_ms * 1000.0).round() as i64,
            "usage_tracked": usage_tracked,
            "screen_tracked": screen_tracked,
            "unlocks": unlocks,
            "boots": boots,
            "shutdowns": shutdowns,
            "screen_ms": ds_screen_ms,
            "sys_ms": sys_ms,
        },
    })))
}

// ======================================================================
// 时间线：在线区间 + 前台应用
// ======================================================================

/// 把一个区间夹到 `[from, to]` 里；完全在外面的返回 None
fn clip_seg(start: f64, end: f64, from: f64, to: f64) -> Option<(f64, f64)> {
    let s = start.max(from);
    let e = end.min(to);
    if e > s {
        Some((s, e))
    } else {
        None
    }
}

/// 应用片段聚合：同一 App 的时长加起来，按总时长倒序。
///
/// 先 [`merge_adjacent`] 合并成"真正的一次使用"，再聚合 —— 否则一个用 10 分钟
/// 的应用会因为每 20 秒心跳各报一段而被算成 30 次、每次 20 秒。
///
/// `label` 取最长的那一段的名字 —— 设备可能先报包名、后补上可读名，
/// 用最长片段的名字更靠谱。
///
/// **输出的 `ms` 是毫秒**（内部片段是 Unix 秒，这里统一换算给前端）。
fn usage_apps(usage: &[UsageSeg], from: f64, to: f64) -> Vec<Value> {
    use std::collections::HashMap;
    let merged = crate::store::merge_adjacent(usage, from, to);

    // pkg -> (label, 秒数, 最长单段时长, 次数)
    let mut map: HashMap<&str, (String, f64, f64, i64)> = HashMap::new();
    for u in &merged {
        let d = u.end - u.start;
        let entry = map
            .entry(u.pkg.as_str())
            .or_insert_with(|| (u.label.clone(), 0.0, 0.0, 0));
        entry.1 += d;
        entry.3 += 1;
        if d > entry.2 {
            entry.2 = d;
            if !u.label.is_empty() {
                entry.0 = u.label.clone();
            }
        }
    }
    let mut out: Vec<Value> = map
        .into_iter()
        .map(|(pkg, (label, secs, _longest, count))| {
            json!({
                "pkg": pkg,
                "label": if label.is_empty() { pkg.to_string() } else { label },
                "ms": (secs * 1000.0).round() as i64,
                "count": count,
            })
        })
        .collect();
    out.sort_by(|a, b| {
        let ma = a.get("ms").and_then(|v| v.as_i64()).unwrap_or(0);
        let mb = b.get("ms").and_then(|v| v.as_i64()).unwrap_or(0);
        mb.cmp(&ma)
    });
    out
}

#[derive(Deserialize)]
pub struct UsageBody {
    #[serde(default)]
    pub segments: Vec<UsageIn>,
    /// 亮屏区间（秒）。老版本 App 不传，服务端照旧工作。
    #[serde(default)]
    pub screen: Vec<UsageIn>,
    /// 设备本地的"今天"（yyyy-MM-dd）。设备时区可能和服务器不同，
    /// 计数必须按设备自己的自然日归集，否则跨时区会错位一天。
    #[serde(default)]
    pub day: String,
    /// 当天累计解锁次数（KEYGUARD_HIDDEN 事件数）。只有全天重扫时才带。
    #[serde(default)]
    pub unlocks: Option<i64>,
    /// 当天开机次数（DEVICE_STARTUP / boot_id 变化）
    #[serde(default)]
    pub boots: Option<i64>,
    /// 当天关机次数（DEVICE_SHUTDOWN）
    #[serde(default)]
    pub shutdowns: Option<i64>,
    /// 当天亮屏总时长（毫秒），由设备按自己扫到的区间累加
    #[serde(default)]
    pub screen_ms: Option<i64>,
    /// 当天"系统口径"的前台应用总时长（毫秒）。
    /// 取自 UsageStatsManager.queryAndAggregateUsageStats，和手机自带的
    /// 数字健康同一个 API —— 用来和采集口径对照，一眼看出准不准。
    #[serde(default)]
    pub sys_ms: Option<i64>,
}

#[derive(Deserialize)]
pub struct UsageIn {
    pub start: f64,
    pub end: f64,
    #[serde(default)]
    pub pkg: String,
    #[serde(default)]
    pub label: String,
}

/// `POST /api/device/usage` —— 设备上报前台应用片段 + 亮屏区间 + 当日计数。
///
/// 幂等：App 会反复上报"当前正在用的那一段"（每次把 end 往后推），
/// 服务端按 `(start, pkg)` 覆盖，不会越攒越多。
pub async fn device_usage(
    State(st): State<Arc<AppState>>,
    headers: HeaderMap,
    Json(body): Json<UsageBody>,
) -> ApiResult<Json<Value>> {
    let now = now_ts();
    let mut store = st.lock();
    let idx = require_device(&store, &headers)?;
    let device_id = store.devices[idx].id.clone();

    // 单次最多收 500 条：设备端有 bug 时也不会把内存和磁盘撑爆
    let incoming: Vec<UsageSeg> = body
        .segments
        .into_iter()
        .take(500)
        .map(|s| UsageSeg {
            start: s.start,
            end: s.end,
            pkg: s.pkg.trim().to_string(),
            label: s.label.trim().chars().take(40).collect(),
        })
        .collect();
    let accepted = incoming.len();
    // 设备上报这些片段，本身就说明它此刻是活的 —— 顺带补一次在线区间，
    // 免得"只上报应用、不发心跳"的实现漏掉在线时间。
    // 过期数据由后台定时任务清理（每小时一次），不放在心跳路径上。
    store.touch_presence(idx, now);
    store.merge_usage(idx, incoming, now);

    // ---- 亮屏区间 ----
    let screen: Vec<PresenceSeg> = body
        .screen
        .into_iter()
        .take(500)
        .filter(|s| s.end > s.start && s.start > 0.0 && s.start < now + 60.0)
        .map(|s| PresenceSeg {
            start: s.start,
            end: s.end.min(now + 60.0),
        })
        .collect();
    let screen_n = screen.len();
    if !screen.is_empty() {
        store.db.upsert_screen(&device_id, &screen);
    }

    // ---- 当日计数 ----
    //
    // 只在设备做了"全天重扫"时才带这些字段；平时只补最近几分钟的片段，
    // 不带计数（否则会把一天的总数当成刚发生的增量写进去）。
    let mut daystat_saved = false;
    if !body.day.is_empty() && crate::util::day_start_ts(&body.day, st.cfg.tz_offset_hours).is_some() {
        if let (Some(unlocks), Some(boots)) = (body.unlocks, body.boots) {
            store.db.upsert_daystat(
                &device_id,
                &body.day,
                unlocks.clamp(0, 100_000),
                boots.clamp(0, 1000),
                body.shutdowns.unwrap_or(0).clamp(0, 1000),
                body.screen_ms.unwrap_or(0).clamp(0, 86_400_000),
                body.sys_ms.unwrap_or(0).clamp(0, 86_400_000),
            );
            daystat_saved = true;
        }
    }

    store.save();
    Ok(Json(json!({
        "ok": true,
        "accepted": accepted,
        "screen": screen_n,
        "daystat": daystat_saved,
    })))
}

#[derive(Deserialize)]
pub struct TimelineQuery {
    /// `yyyy-MM-dd`，缺省是服务器时区的"今天"
    pub day: Option<String>,
}

/// `GET /api/devices/{id}/timeline?day=yyyy-MM-dd`
///
/// 返回某一天的：在线区间、前台应用片段（已夹到当天）、应用聚合、各项总时长。
/// 时间戳都是 Unix 秒，前端按 `tz` 换算成本地小时来画。
pub async fn device_timeline(
    State(st): State<Arc<AppState>>,
    UrlPath(device_id): UrlPath<String>,
    Query(q): Query<TimelineQuery>,
    headers: HeaderMap,
) -> ApiResult<Json<Value>> {
    let mut store = st.lock();
    let auth = require_user(&st, &mut store, &headers)?;
    // 越权访问别人的设备 → 404（这里只要校验，不使用返回的下标）
    device_for(&store, &auth, &device_id)?;

    let now = now_ts();
    let tz = st.cfg.tz_offset_hours;
    let today = local_day(now, tz);
    let day = q
        .day
        .as_deref()
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty())
        .unwrap_or_else(|| today.clone());

    let from = crate::util::day_start_ts(&day, tz)
        .ok_or_else(|| ApiError::bad("日期格式应为 yyyy-MM-dd"))?;
    let to = from + 86400.0;

    // ---- 在线区间（夹到当天）----
    let presence: Vec<Value> = store
        .presence_of(&device_id, from, to)
        .iter()
        .filter_map(|p| clip_seg(p.start, p.end, from, to))
        .map(|(s, e)| json!({ "start": s, "end": e }))
        .collect();
    let online_ms: f64 = presence
        .iter()
        .map(|v| {
            v.get("end").and_then(|x| x.as_f64()).unwrap_or(0.0)
                - v.get("start").and_then(|x| x.as_f64()).unwrap_or(0.0)
        })
        .sum();

    // ---- 应用片段（合并成真正的一次使用，并夹到当天）----
    //
    // 直接用原始片段画会是一堆挤在一起的小色块：设备每 20 秒心跳就把
    // "当前段"重报一次，同一段会被切成几十截。合并后再画才是"连续用了多久"。
    let raw_usage = store.usage_of(&device_id, from, to);
    let merged = crate::store::merge_adjacent(&raw_usage, from, to);
    let usage: Vec<Value> = merged
        .iter()
        .map(|u| json!({ "start": u.start, "end": u.end, "pkg": u.pkg, "label": u.label }))
        .collect();
    // 秒 → 毫秒，和前端字段名保持一致
    let foreground_ms: f64 = merged.iter().map(|u| (u.end - u.start) * 1000.0).sum();

    let apps = usage_apps(&merged, from, to);

    // ---- 亮屏区间（夹到当天）----
    //
    // 时间轴上单独一行画：有绿线就是亮着，空白就是熄屏。
    // 老版本 App 没上报过这张表，has_screen 为 false，前端就不画这行。
    let screen: Vec<Value> = store
        .db
        .screen_between(&device_id, from, to)
        .iter()
        .filter_map(|p| clip_seg(p.start, p.end, from, to))
        .map(|(s, e)| json!({ "start": s, "end": e }))
        .collect();
    let screen_ms: f64 = screen
        .iter()
        .map(|v| {
            v.get("end").and_then(|x| x.as_f64()).unwrap_or(0.0)
                - v.get("start").and_then(|x| x.as_f64()).unwrap_or(0.0)
        })
        .sum();
    let screen_tracked = store.db.has_screen(&device_id);

    // ---- 当天的解锁 / 开关机 ----
    let (unlocks, boots, shutdowns, ds_screen_ms, sys_ms) = store.db.daystat_get(&device_id, &day);

    Ok(Json(json!({
        "day": day,
        "today": today,
        "tz": tz,
        "from": from,
        "to": to,
        "presence": presence,
        "usage": usage,
        "screen": screen,
        "apps": apps,
        "totals": {
            "online_ms": (online_ms * 1000.0).round() as i64,
            "foreground_ms": foreground_ms.round() as i64,
            "usage_tracked": store.has_usage(&device_id),
            "screen_ms": if screen_tracked { (screen_ms * 1000.0).round() as i64 } else { ds_screen_ms },
            "screen_tracked": screen_tracked,
            "unlocks": unlocks,
            "boots": boots,
            "shutdowns": shutdowns,
            "sys_ms": sys_ms,
        },
    })))
}

// ======================================================================
// 管理端：下发指令
// ======================================================================

pub async fn push_command(
    State(st): State<Arc<AppState>>,
    UrlPath(device_id): UrlPath<String>,
    ConnectInfo(ci): ConnectInfo<SocketAddr>,
    headers: HeaderMap,
    Json(body): Json<CommandBody>,
) -> ApiResult<Json<Value>> {
    let ip = client_ip(&headers, &ci);
    let mut store = st.lock();
    let auth = require_user(&st, &mut store, &headers)?;

    if !ALLOWED_COMMANDS.iter().any(|(k, _)| *k == body.kind) {
        return Err(ApiError::bad(format!("不支持的指令：{}", body.kind)));
    }
    // 归属校验放在这里：别人的设备连"下发指令"都走不到
    let dev_idx = device_for(&store, &auth, &device_id)?;

    // reset_tamper 是 v2.3.1 才加的指令。更早的 App 收到只会回"不支持的指令"，
    // 家长点了之后只看到一条失败记录，根本不知道是版本没升。
    if body.kind == "reset_tamper" {
        let ver = store.devices[dev_idx].app_version.clone();
        if !ver.is_empty() && ver_tuple(&ver) < (2, 3, 1) {
            return Err(ApiError::bad(format!(
                "这台设备的 App 是 {ver}，不支持「清零违规计数」，请先升级到 2.3.1 及以上"
            )));
        }
    }
    // 停用时段是 v2.4 才有的能力。旧 App 收到 `quiet` 只会回"不支持的指令"，
    // 更要紧的是家长会以为已经设好了，到点孩子照样能玩 —— 必须在服务端就拦住。
    if body.kind == "quiet" {
        let ver = store.devices[dev_idx].app_version.clone();
        if !ver.is_empty() && ver_tuple(&ver) < (2, 4, 0) {
            return Err(ApiError::bad(format!(
                "这台设备的 App 是 {ver}，不支持「停用时段」，请先升级到 2.4.0 及以上"
            )));
        }
    }
    // 每日总时长同理：老 App 收到 day_limit 会回"不支持的指令"，
    // 但家长会以为设好了，孩子照样玩满一整天。
    if matches!(body.kind.as_str(), "day_limit" | "day_unlock" | "day_relock") {
        let ver = store.devices[dev_idx].app_version.clone();
        if !ver.is_empty() && ver_tuple(&ver) < (2, 5, 0) {
            return Err(ApiError::bad(format!(
                "这台设备的 App 是 {ver}，不支持「每日总时长」，请先升级到 2.5.0 及以上"
            )));
        }
    }

    let raw: Map<String, Value> = match body.params {
        Some(Value::Object(m)) => m,
        _ => Map::new(),
    };
    let params = match body.kind.as_str() {
        "lock" => {
            // ★ 默认值取这台设备自己配置的休息时长，不能写死 5。
            //
            // v2.3.0 之前这里是 `clamp(..., 5)`：前端按钮上写着「锁定 1 分钟」
            // （那是设备的 rest_minutes），但请求里没带 minutes，于是服务端补了
            // 默认 5 —— 家长点 1 分钟，孩子实际被锁 5 分钟。默认值必须跟着设备走。
            let own = store.devices[dev_idx].rest_minutes.clamp(1, 240);
            let minutes = clamp(raw.get("minutes"), 1, 240, own);
            json!({ "minutes": minutes })
        }
        "config" => {
            let mut clean = Map::new();
            if raw.contains_key("use_minutes") {
                clean.insert(
                    "use_minutes".into(),
                    json!(clamp(raw.get("use_minutes"), 1, 600, 30)),
                );
            }
            if raw.contains_key("rest_minutes") {
                clean.insert(
                    "rest_minutes".into(),
                    json!(clamp(raw.get("rest_minutes"), 1, 240, 5)),
                );
            }
            if raw.contains_key("reset_gap_seconds") {
                clean.insert(
                    "reset_gap_seconds".into(),
                    json!(clamp(raw.get("reset_gap_seconds"), 30, 3600, 300)),
                );
            }
            if raw.contains_key("tamper_policy") {
                clean.insert(
                    "tamper_policy".into(),
                    Value::Bool(json_bool(raw.get("tamper_policy"), false)),
                );
            }
            for k in ["mute_on_rest", "pause_media_on_rest"] {
                if let Some(v) = raw.get(k) {
                    clean.insert(k.into(), Value::Bool(json_bool(Some(v), false)));
                }
            }
            // 每日上限也走 config：家长在设置区一次性保存，不用分两次点。
            // 顺手落库 —— 设备离线时这个值也不能丢。
            if raw.contains_key("day_limit_minutes") {
                let m = clamp(raw.get("day_limit_minutes"), 0, 1440, 0);
                store.devices[dev_idx].day_limit_minutes = m;
                clean.insert("day_limit_minutes".into(), json!(m));
            }
            if clean.is_empty() {
                return Err(ApiError::bad("没有可修改的参数"));
            }
            Value::Object(clean)
        }
        // ---- 每日总时长：0 表示不限 ----
        // 和停用时段一样先落库：设备离线时家长设的值不能丢，
        // 否则"今天设了 90 分钟"要到孩子开机后才第一次生效。
        "day_limit" => {
            let minutes = clamp(raw.get("minutes"), 0, 1440, 0);
            store.devices[dev_idx].day_limit_minutes = minutes;
            json!({ "minutes": minutes })
        }
        "day_unlock" | "day_relock" => Value::Object(Map::new()),
        // ---- 停用时段：整体替换规则列表 ----
        //
        // 设计成"整份替换"而不是"逐条增删改"：规则最多四五条，前端本来就是
        // 一张表一次性保存；整份下发让设备端不需要做 diff，也避免了"删了一半"
        // 这种中间状态。设备回执里带上清洗后的规则数，家长能核对。
        "quiet" => {
            // 两种入参：
            //   ① rules = [...]        整份替换（前端表单走这条）
            //   ② enabled = true/false 对**现有**规则整体开关
            // ② 以前返回的是 `{enabled:bool}`，设备端根本不认（它只解析 rules），
            // 于是"整体开关"这个动作在设备上静默失败。现在统一展开成完整规则再下发，
            // 设备端只需要一种解析路径。
            let rules: Vec<QuietRule> = match raw.get("rules") {
                Some(Value::Array(a)) => {
                    let arr = a.clone();
                    if arr.len() > MAX_QUIET_RULES {
                        return Err(ApiError::bad(format!(
                            "停用时段最多 {MAX_QUIET_RULES} 条（再多设备端也显示不下）"
                        )));
                    }
                    let mut list: Vec<QuietRule> = Vec::new();
                    for (i, item) in arr.iter().enumerate() {
                        let obj = item.as_object().ok_or_else(|| {
                            ApiError::bad(format!("第 {} 条规则格式不对", i + 1))
                        })?;
                        let from = clamp(obj.get("from"), 0, 1440, -1);
                        let to = clamp(obj.get("to"), 0, 1440, -1);
                        if from < 0 || to < 0 {
                            return Err(ApiError::bad(format!(
                                "第 {} 条规则缺少开始/结束时间（from/to，单位是 0 点起算的分钟数）",
                                i + 1
                            )));
                        }
                        if from == to {
                            return Err(ApiError::bad(format!(
                                "第 {} 条规则的开始和结束时间相同（{}），请改成具体的时段",
                                i + 1,
                                crate::store::fmt_hhmm(from)
                            )));
                        }
                        let days = clamp(obj.get("days"), 0, DAYS_ALL, 0) & DAYS_ALL;
                        let id = obj
                            .get("id")
                            .and_then(|v| v.as_str())
                            .map(str::trim)
                            .filter(|s| !s.is_empty())
                            .map(|s| s.to_string())
                            .unwrap_or_else(|| format!("qr_{}", crate::util::rand_hex(4)));
                        list.push(QuietRule {
                            id,
                            enabled: json_bool(obj.get("enabled"), true),
                            label: obj
                                .get("label")
                                .and_then(|v| v.as_str())
                                .unwrap_or("")
                                .chars()
                                .take(20)
                                .collect(),
                            from,
                            to,
                            days,
                            created_at: now_ts(),
                        });
                    }
                    list
                }
                Some(_) => return Err(ApiError::bad("rules 必须是数组")),
                None => {
                    if raw.contains_key("enabled") {
                        let on = json_bool(raw.get("enabled"), true);
                        let mut list = store.devices[dev_idx].quiet_hours.clone();
                        for r in list.iter_mut() {
                            r.enabled = on;
                        }
                        list
                    } else {
                        return Err(ApiError::bad("缺少 rules（停用时段列表）"));
                    }
                }
            };

            // ★ 服务端先存一份：设备离线时家长配的规则不会丢，下次心跳原样带过去。
            // 以前 config 类指令是纯"转发"，设备不在线就白点；停用时段是长期规则，
            // 必须落库，否则家长晚上在网页上设好了，孩子早上开机照样能玩。
            store.devices[dev_idx].quiet_hours = rules.clone();
            json!({ "rules": rules })
        }
        _ => Value::Object(Map::new()),
    };

    // 锁定多少分钟必须写进事件里：家长事后看到"锁了 5 分钟"才发现对不上，
    // 光写"下发了远程锁定"根本查不出问题。
    let extra = match body.kind.as_str() {
        "lock" => params
            .get("minutes")
            .and_then(|v| v.as_i64())
            .map(|m| format!("（{m} 分钟）"))
            .unwrap_or_default(),
        // 每日额度一定要把分钟数写进事件：家长事后看到"今天 15:40 被锁"，
        // 得能一眼看出当时设的是多少分钟，而不是只能凭记忆
        "day_limit" => params
            .get("minutes")
            .and_then(|v| v.as_i64())
            .map(|m| {
                if m <= 0 {
                    "（改为不限）".to_string()
                } else {
                    format!("（每天 {m} 分钟）")
                }
            })
            .unwrap_or_default(),
        "day_unlock" => "（今天不再按额度锁）".to_string(),
        "day_relock" => "（恢复每日额度限制）".to_string(),
        // 停用时段一定要把规则原样写进事件流：家长事后想知道"到底什么时候设的、
        // 设成了几点到几点"，光写"更新了停用时段"根本查不出来。
        "quiet" => params
            .get("rules")
            .and_then(|v| v.as_array())
            .map(|a| {
                if a.is_empty() {
                    "（清空，全部放开）".to_string()
                } else {
                    let list: Vec<String> = a
                        .iter()
                        .map(|r| {
                            let f = r.get("from").and_then(|v| v.as_i64()).unwrap_or(0);
                            let t = r.get("to").and_then(|v| v.as_i64()).unwrap_or(0);
                            let d = r.get("days").and_then(|v| v.as_i64()).unwrap_or(0);
                            let on = r
                                .get("enabled")
                                .and_then(|v| v.as_bool())
                                .unwrap_or(true);
                            format!(
                                "{}{}–{}{}",
                                days_label(d),
                                crate::store::fmt_hhmm(f),
                                crate::store::fmt_hhmm(t),
                                if on { "" } else { "（未启用）" }
                            )
                        })
                        .collect();
                    format!("（{}）", list.join("、"))
                }
            })
            .unwrap_or_default(),
        _ => String::new(),
    };

    let cid = store.push_command(&device_id, &body.kind, params);
    store.add_event(
        Some(&device_id),
        "command",
        format!(
            "{} 下发指令：{}{}",
            auth.username,
            command_label(&body.kind),
            extra
        ),
    );
    let _ = ip;
    store.save();

    Ok(Json(json!({
        "ok": true,
        "command_id": cid,
        "queued": true,
        "hint": "指令已排队，设备下次心跳时执行（通常 20 秒内）",
    })))
}

/// 事件查询参数。
///
/// 三种用法：
/// * `?day=2026-10-24` —— 查某一天的全部事件（设备详情页用）
/// * `?hours=24`       —— 查最近 N 小时（首页默认 48）
/// * `?page=2&size=50` —— 分页，`size` 上限 200
#[derive(Deserialize, Default)]
pub struct EventQuery {
    /// `yyyy-MM-dd`；给了它就忽略 `hours`
    pub day: Option<String>,
    /// 最近多少小时
    pub hours: Option<f64>,
    /// 只看某台设备
    pub device: Option<String>,
    pub page: Option<i64>,
    pub size: Option<i64>,
}

/// 事件分页的公共实现，`list_events` 与 `device_events` 共用。
///
/// `forced` 是"设备详情页"传进来的设备 id（已经过归属校验），
/// 它会覆盖查询串里的 `?device=`。
fn page_events(
    store: &Store,
    auth: &Auth,
    tz: f64,
    q: &EventQuery,
    forced: Option<&str>,
) -> Result<Value, ApiError> {
    let now = now_ts();
    let today = local_day(now, tz);

    // 时间窗口：给了 day 就按天切，否则按最近 N 小时
    let (from, to, day) = match q
        .day
        .as_deref()
        .map(str::trim)
        .filter(|s| !s.is_empty())
    {
        Some(d) => {
            let f = crate::util::day_start_ts(d, tz)
                .ok_or_else(|| ApiError::bad("日期格式应为 yyyy-MM-dd"))?;
            (f, f + 86400.0, d.to_string())
        }
        None => {
            let h = q.hours.unwrap_or(48.0).clamp(1.0, 24.0 * 366.0);
            (now - h * 3600.0, now + 60.0, today.clone())
        }
    };

    let size = q.size.unwrap_or(50).clamp(1, 200);
    let page = q.page.unwrap_or(1).max(1);
    let offset = (page - 1) * size;

    // 设备维度：设备详情页传进来的优先，其次是查询串里的 ?device=
    let device = forced
        .map(|s| s.to_string())
        .or_else(|| q.device.clone().filter(|s| !s.trim().is_empty()));

    // 已经指定了具体设备（且校验过归属）就不必再按可见列表过滤；
    // 否则普通账号必须在自己的设备范围内。
    let visible = if device.is_some() {
        None
    } else {
        visible_ids(store, auth)
    };

    let (items, total) =
        store
            .db
            .events_page(device.as_deref(), visible.as_deref(), from, to, size, offset);
    let pages = (total + size - 1) / size;

    Ok(json!({
        "events": items.into_iter().map(|e| json!(e)).collect::<Vec<_>>(),
        "total": total,
        "page": page,
        "size": size,
        "pages": pages,
        "from": from,
        "to": to,
        "day": day,
        "today": today,
    }))
}

/// `GET /api/events?day=&hours=&device=&page=&size=`
pub async fn list_events(
    State(st): State<Arc<AppState>>,
    Query(q): Query<EventQuery>,
    headers: HeaderMap,
) -> ApiResult<Json<Value>> {
    let mut store = st.lock();
    let auth = require_user(&st, &mut store, &headers)?;

    // 查别人设备的事件 → 404（和详情页同一个规矩，不能用状态码探测设备 id）
    if let Some(did) = q.device.as_deref().map(str::trim).filter(|s| !s.is_empty()) {
        device_for(&store, &auth, did)?;
    }

    let out = page_events(&store, &auth, st.cfg.tz_offset_hours, &q, None)?;
    Ok(Json(out))
}

/// `GET /api/devices/{id}/quiet` —— 读取这台设备的停用时段规则。
///
/// 设备主页拿它来渲染规则表（详情接口里其实已经带了，这里是给脚本和
/// 单独刷新用的：家长改完规则点保存后不必整页重载）。
pub async fn device_quiet(
    State(st): State<Arc<AppState>>,
    UrlPath(device_id): UrlPath<String>,
    headers: HeaderMap,
) -> ApiResult<Json<Value>> {
    let mut store = st.lock();
    let auth = require_user(&st, &mut store, &headers)?;
    let idx = device_for(&store, &auth, &device_id)?;
    let d = &store.devices[idx];
    Ok(Json(json!({
        "rules": d.quiet_hours,
        "active": d.quiet_hours.iter().any(|r| r.enabled),
        "in_quiet": d.in_quiet,
        "quiet_left_ms": d.quiet_left_ms,
        "quiet_label": d.quiet_label,
    })))
}

/// `GET /api/devices/{id}/events?day=&page=&size=`
///
/// 设备主页用这个：按天翻事件，和上面的时间轴共用同一个 `day` 参数。
pub async fn device_events(
    State(st): State<Arc<AppState>>,
    UrlPath(device_id): UrlPath<String>,
    Query(q): Query<EventQuery>,
    headers: HeaderMap,
) -> ApiResult<Json<Value>> {
    let mut store = st.lock();
    let auth = require_user(&st, &mut store, &headers)?;
    device_for(&store, &auth, &device_id)?;

    let out = page_events(&store, &auth, st.cfg.tz_offset_hours, &q, Some(&device_id))?;
    Ok(Json(out))
}

/// 移除一台设备：令牌立即失效，平板下次心跳会收到 401 并提示重新配对。
///
/// 指令和每日统计一起清掉；事件保留（审计用，家长能看出"什么时候删了哪台"）。
pub async fn delete_device(
    State(st): State<Arc<AppState>>,
    UrlPath(device_id): UrlPath<String>,
    headers: HeaderMap,
) -> ApiResult<Json<Value>> {
    let mut store = st.lock();
    let auth = require_user(&st, &mut store, &headers)?;
    let idx = device_for(&store, &auth, &device_id)?;

    let name = store.devices[idx].name.clone();
    let owner = store.devices[idx].owner_id.clone();
    let owner_label = owner_name(&store, &owner);
    store.devices.remove(idx);
    store.commands.retain(|c| c.device_id != device_id);
    // 设备被移除后，它名下的在线区间 / 应用片段 / 每日汇总一起清掉。
    // 事件保留 —— 那是审计记录，家长能看出"什么时候删了哪台"。
    store.purge_device(&device_id);
    store.add_event(
        None,
        "device_removed",
        format!(
            "{} 移除了设备 {}（归属 {}）",
            auth.username, name, owner_label
        ),
    );
    store.save();
    Ok(Json(json!({ "ok": true })))
}

// ======================================================================
// 管理端：配对码
// ======================================================================

/// 生成配对码。
///
/// 归属规则：
///   * 普通账号 —— 配对出来的设备一定归自己（`owner` 参数被忽略）；
///   * 管理员   —— 可用 `?owner=<账号 id>` 替别的账号生成配对码，不传就归自己。
pub async fn create_pair_code(
    State(st): State<Arc<AppState>>,
    headers: HeaderMap,
    axum::extract::Query(q): axum::extract::Query<std::collections::HashMap<String, String>>,
) -> ApiResult<Json<Value>> {
    let mut store = st.lock();
    let auth = require_user(&st, &mut store, &headers)?;

    let owner_id = match q.get("owner").map(|s| s.trim()).filter(|s| !s.is_empty()) {
        Some(want) if auth.is_admin() => {
            let idx = store
                .user_index(want)
                .ok_or_else(|| ApiError::bad("指定的账号不存在"))?;
            if store.users[idx].disabled {
                return Err(ApiError::bad("这个账号已被停用，不能配新设备"));
            }
            store.users[idx].id.clone()
        }
        _ => auth.id.clone(),
    };

    let now = now_ts();
    store.pair_codes.retain(|p| p.expires_at >= now);
    let mut code = rand_digits(6);
    while store.pair_codes.iter().any(|p| p.code == code) {
        code = rand_digits(6);
    }
    let exp = now + PAIR_CODE_TTL;
    store.pair_codes.push(PairCode {
        code: code.clone(),
        created_at: now,
        expires_at: exp,
        used_by: None,
        owner_id: owner_id.clone(),
    });
    let owner_label = owner_name(&store, &owner_id);
    store.add_event(
        None,
        "pair_code",
        format!(
            "{} 生成配对码 {code}（归属 {}，10 分钟内有效）",
            auth.username, owner_label
        ),
    );
    store.save();

    Ok(Json(json!({
        "code": code,
        "expires_at": exp,
        "ttl": PAIR_CODE_TTL,
        "owner": owner_label,
    })))
}

// ======================================================================
// 管理端：账号管理（仅管理员）
// ======================================================================

pub async fn list_users(State(st): State<Arc<AppState>>, headers: HeaderMap) -> ApiResult<Json<Value>> {
    let mut store = st.lock();
    let auth = require_admin(&st, &mut store, &headers)?;
    let users: Vec<Value> = store
        .users
        .iter()
        .map(|u| {
            let n = store.devices.iter().filter(|d| d.owner_id == u.id).count();
            user_json(u, n)
        })
        .collect();
    Ok(Json(json!({
        "users": users,
        "me": auth.id,
        "can_admin": true,
    })))
}

pub async fn create_user(
    State(st): State<Arc<AppState>>,
    headers: HeaderMap,
    Json(body): Json<NewUserBody>,
) -> ApiResult<Json<Value>> {
    let mut store = st.lock();
    let auth = require_admin(&st, &mut store, &headers)?;

    let username = valid_username(&body.username)?;
    check_password_strength(&body.password)?;
    if store.user_index_by_name(&username).is_some() {
        return Err(ApiError::bad("这个用户名已经被占用了"));
    }
    let role = match body.role.trim() {
        "" | "user" => "user",
        "admin" => "admin",
        other => return Err(ApiError::bad(format!("未知角色：{other}"))),
    };

    let u = User {
        id: format!("u_{}", rand_hex(6)),
        username: username.clone(),
        password: hash_password(&body.password),
        role: role.into(),
        created_at: now_ts(),
        last_login: 0.0,
        disabled: false,
        note: body.note.trim().chars().take(80).collect(),
    };
    let out = user_json(&u, 0);
    let uid = u.id.clone();
    store.users.push(u);
    store.add_event(
        None,
        "user",
        format!("{} 新建了账号 {}（{role}）", auth.username, username),
    );
    store.save();
    Ok(Json(json!({ "ok": true, "user": out, "id": uid })))
}

pub async fn update_user(
    State(st): State<Arc<AppState>>,
    UrlPath(user_id): UrlPath<String>,
    headers: HeaderMap,
    Json(body): Json<UpdateUserBody>,
) -> ApiResult<Json<Value>> {
    let mut store = st.lock();
    let auth = require_admin(&st, &mut store, &headers)?;

    let idx = store
        .user_index(&user_id)
        .ok_or_else(|| ApiError::not_found("账号不存在"))?;
    let target_name = store.users[idx].username.clone();
    let was_admin = store.users[idx].is_admin() && !store.users[idx].disabled;
    let target_disabled = store.users[idx].disabled;

    // ---- 先把要改成什么算清楚，再统一做合法性检查 ----
    let new_role = match body.role.as_deref().map(str::trim) {
        None | Some("") => None,
        Some("admin") => Some("admin".to_string()),
        Some("user") => Some("user".to_string()),
        Some(other) => return Err(ApiError::bad(format!("未知角色：{other}"))),
    };
    if let Some(pw) = body.password.as_deref().filter(|s| !s.is_empty()) {
        check_password_strength(pw)?;
    }

    let will_be_admin = new_role.as_deref().map(|r| r == "admin").unwrap_or(was_admin);
    let will_be_disabled = body.disabled.unwrap_or(target_disabled);

    // 保护：不能把自己停用 / 降级（否则会把自己锁在门外）
    if user_id == auth.id {
        if will_be_disabled {
            return Err(ApiError::bad("不能停用自己的账号"));
        }
        if was_admin && !will_be_admin {
            return Err(ApiError::bad("不能取消自己的管理员身份"));
        }
    }
    // 保护：系统里至少要留一个可用的管理员
    if was_admin && (!will_be_admin || will_be_disabled) && store.admin_count() <= 1 {
        return Err(ApiError::bad("至少要保留一个可用的管理员账号"));
    }

    let u = &mut store.users[idx];
    if let Some(pw) = body.password.as_deref().filter(|s| !s.is_empty()) {
        u.password = hash_password(pw);
    }
    if let Some(r) = new_role.clone() {
        u.role = r;
    }
    if let Some(d) = body.disabled {
        u.disabled = d;
    }
    if let Some(n) = body.note.as_deref() {
        u.note = n.trim().chars().take(80).collect();
    }

    // 被停用 / 改了密码 → 该账号的在线会话全部失效（管理员例外：允许保留自己的）
    if (will_be_disabled || body.password.is_some()) && user_id != auth.id {
        store.sessions.retain(|s| s.user_id != user_id);
    }

    let mut changes = Vec::new();
    if body.password.as_deref().filter(|s| !s.is_empty()).is_some() {
        changes.push("重设密码");
    }
    if new_role.is_some() {
        changes.push("改角色");
    }
    if body.disabled.is_some() {
        changes.push(if will_be_disabled { "停用" } else { "启用" });
    }
    let n = store.devices.iter().filter(|d| d.owner_id == user_id).count();
    let out = user_json(&store.users[idx], n);
    let desc = if changes.is_empty() { "更新备注".to_string() } else { changes.join("、") };
    store.add_event(
        None,
        "user",
        format!("{} 修改了账号 {}（{desc}）", auth.username, target_name),
    );
    store.save();
    Ok(Json(json!({ "ok": true, "user": out })))
}

/// 删除账号。
///
/// 名下还有设备时**不允许直接删**：必须用 `?reassign=<账号 id>` 指定转移给谁，
/// 否则一旦误删，那台平板会变成"谁都看不见、谁都不能操作"的孤儿设备，
/// 只能重新配对才能恢复。
pub async fn delete_user(
    State(st): State<Arc<AppState>>,
    UrlPath(user_id): UrlPath<String>,
    headers: HeaderMap,
    axum::extract::Query(q): axum::extract::Query<std::collections::HashMap<String, String>>,
) -> ApiResult<Json<Value>> {
    let mut store = st.lock();
    let auth = require_admin(&st, &mut store, &headers)?;

    let idx = store
        .user_index(&user_id)
        .ok_or_else(|| ApiError::not_found("账号不存在"))?;
    if user_id == auth.id {
        return Err(ApiError::bad("不能删除自己的账号"));
    }
    let name = store.users[idx].username.clone();
    let was_admin = store.users[idx].is_admin() && !store.users[idx].disabled;
    if was_admin && store.admin_count() <= 1 {
        return Err(ApiError::bad("至少要保留一个可用的管理员账号"));
    }

    let devices: Vec<String> = store
        .devices
        .iter()
        .filter(|d| d.owner_id == user_id)
        .map(|d| d.id.clone())
        .collect();

    let mut moved = 0usize;
    if !devices.is_empty() {
        let target = q
            .get("reassign")
            .map(|s| s.trim().to_string())
            .filter(|s| !s.is_empty())
            .ok_or_else(|| {
                ApiError::bad(format!(
                    "这个账号名下还有 {} 台设备，请先指定要把它们转给哪个账号",
                    devices.len()
                ))
            })?;
        let tidx = store
            .user_index(&target)
            .ok_or_else(|| ApiError::bad("要转入的账号不存在"))?;
        if target == user_id {
            return Err(ApiError::bad("不能把设备转给正在删除的这个账号"));
        }
        if store.users[tidx].disabled {
            return Err(ApiError::bad("要转入的账号已被停用"));
        }
        for d in store.devices.iter_mut() {
            if d.owner_id == user_id {
                d.owner_id = target.clone();
                moved += 1;
            }
        }
    }

    store.users.remove(idx);
    store.sessions.retain(|s| s.user_id != user_id);
    // 这个账号还没用的配对码也一起作废，否则配出来的设备会归到一个不存在的账号名下
    store.pair_codes.retain(|p| p.owner_id != user_id);
    store.add_event(
        None,
        "user",
        format!(
            "{} 删除了账号 {}{}",
            auth.username,
            name,
            if moved > 0 {
                format!("，名下 {moved} 台设备已转移")
            } else {
                String::new()
            }
        ),
    );
    store.save();
    Ok(Json(json!({ "ok": true, "moved_devices": moved })))
}

// ======================================================================
// 设备端
// ======================================================================

pub async fn device_pair(
    State(st): State<Arc<AppState>>,
    ConnectInfo(ci): ConnectInfo<SocketAddr>,
    headers: HeaderMap,
    Json(body): Json<PairBody>,
) -> ApiResult<Json<Value>> {
    let ip = client_ip(&headers, &ci);
    let code = body.code.trim().to_string();
    let name = {
        let n = body.name.trim();
        if n.is_empty() { "未命名设备".to_string() } else { n.to_string() }
    };
    let now = now_ts();
    let mut store = st.lock();

    let idx = store
        .pair_codes
        .iter()
        .position(|p| p.code == code)
        .ok_or_else(|| ApiError::bad("配对码不存在"))?;
    let pc = &store.pair_codes[idx];
    if pc.used_by.is_some() {
        return Err(ApiError::bad("配对码已被使用，请在后台重新生成"));
    }
    if pc.expires_at < now {
        return Err(ApiError::bad("配对码已过期，请在后台重新生成"));
    }
    // 配对码是谁生成的，设备就归谁 —— 这是多账号隔离的入口
    let owner_id = pc.owner_id.clone();
    if owner_id.is_empty() {
        return Err(ApiError::bad("这个配对码没有归属账号，请在后台重新生成"));
    }
    // 归属账号已被停用 / 删掉时不要把设备挂过去，否则它立刻变成谁都看不到的孤儿
    match store.user_index(&owner_id) {
        Some(i) if !store.users[i].disabled => {}
        Some(_) => return Err(ApiError::bad("配对码的归属账号已被停用，请在后台重新生成")),
        None => return Err(ApiError::bad("配对码的归属账号已不存在，请在后台重新生成")),
    }

    let device_id = format!("dev_{}", rand_hex(6));
    let token = rand_hex(32);
    store.devices.push(Device {
        id: device_id.clone(),
        owner_id: owner_id.clone(),
        name: name.clone(),
        model: body.model.clone().unwrap_or_default(),
        token: token.clone(),
        created_at: now,
        last_seen: now,
        last_ip: ip,
        app_version: body.app_version.clone().unwrap_or_default(),
        enabled: false,
        ..Device::default()
    });
    if let Some(pc) = store.pair_codes.get_mut(idx) {
        pc.used_by = Some(device_id.clone());
    }
    store.add_event(
        Some(&device_id),
        "pair",
        format!(
            "新设备配对成功：{}（{}）",
            name,
            body.model.as_deref().unwrap_or("未知型号")
        ),
    );
    store.save();

    Ok(Json(json!({
        "device_id": device_id,
        "token": token,
        "name": name,
        "heartbeat_seconds": HEARTBEAT_SECONDS,
    })))
}

pub async fn device_heartbeat(
    State(st): State<Arc<AppState>>,
    ConnectInfo(ci): ConnectInfo<SocketAddr>,
    headers: HeaderMap,
    Json(payload): Json<Value>,
) -> ApiResult<Json<Value>> {
    let ip = client_ip(&headers, &ci);
    let now = now_ts();
    let mut store = st.lock();
    let idx = require_device(&store, &headers)?;
    let did = store.devices[idx].id.clone();
    let dev_name = store.devices[idx].name.clone();
    let prev_resting = store.devices[idx].resting;
    let prev_enabled = store.devices[idx].enabled;
    let prev_in_quiet = store.devices[idx].in_quiet;
    let prev_day_locked = store.devices[idx].day_locked;

    // ---- 更新状态快照 ----
    {
        let d = &mut store.devices[idx];
        d.last_seen = now;
        d.last_ip = ip;
        if let Some(v) = payload.get("app_version").and_then(|v| v.as_str()) {
            if !v.is_empty() {
                d.app_version = v.to_string();
            }
        }
        d.enabled = json_bool(payload.get("enabled"), d.enabled);
        d.resting = json_bool(payload.get("resting"), d.resting);
        d.rest_left_ms = clamp(payload.get("rest_left_ms"), 0, MAX_MS, d.rest_left_ms);
        d.used_ms = clamp(payload.get("used_ms"), 0, MAX_MS, d.used_ms);
        // 缺字段时保留旧值而不是归零：老版本 App 不带这个字段，
        // 归零会让"今天使用"在升级/降级时突然掉到 0（看着像统计坏了）
        d.today_used_ms = clamp(payload.get("today_used_ms"), 0, MAX_MS, d.today_used_ms);
        d.rest_count = clamp(payload.get("rest_count"), 0, 999, 0);
        d.use_minutes = clamp(payload.get("use_minutes"), 1, 600, d.use_minutes);
        d.rest_minutes = clamp(payload.get("rest_minutes"), 1, 240, d.rest_minutes);
        // 每日总时长：0 = 不限。缺字段时保留旧值，避免老版本 App 把家长设的上限抹成 0。
        d.day_limit_minutes = clamp(payload.get("day_limit_minutes"), 0, 1440, d.day_limit_minutes);
        d.day_locked = json_bool(payload.get("day_locked"), d.day_locked);
        d.day_unlock_left_ms =
            clamp(payload.get("day_unlock_left_ms"), 0, MAX_MS, d.day_unlock_left_ms);
        d.reset_gap_seconds = clamp(payload.get("reset_gap_seconds"), 30, 3600, d.reset_gap_seconds);
        d.tamper_policy = json_bool(payload.get("tamper_policy"), d.tamper_policy);
        d.tamper_strikes = clamp(payload.get("tamper_strikes"), 0, 999, d.tamper_strikes);
        d.pin_set = json_bool(payload.get("pin_set"), d.pin_set);
        d.screen_on = json_bool(payload.get("screen_on"), d.screen_on);
        d.audio_suppressed = json_bool(payload.get("audio_suppressed"), d.audio_suppressed);
        d.pause_media_on_rest = json_bool(payload.get("pause_media_on_rest"), d.pause_media_on_rest);
        d.mute_on_rest = json_bool(payload.get("mute_on_rest"), d.mute_on_rest);
        // ---- 停用时段 ----
        //
        // 规则由家长在后台设定、服务端保存、随指令下发，所以这里**只收状态、不收规则**：
        // 设备上报的规则一律不采信，否则孩子改一下 App 的配置就能绕过。
        // 设备只负责回答"我现在在不在停用中、还有多久结束"。
        d.in_quiet = json_bool(payload.get("in_quiet"), d.in_quiet);
        d.quiet_left_ms = clamp(payload.get("quiet_left_ms"), 0, MAX_MS, d.quiet_left_ms);
        if let Some(v) = payload.get("quiet_label").and_then(|v| v.as_str()) {
            d.quiet_label = v.chars().take(40).collect();
        }
    }

    let resting = store.devices[idx].resting;
    let enabled = store.devices[idx].enabled;
    let in_quiet = store.devices[idx].in_quiet;
    let quiet_label = store.devices[idx].quiet_label.clone();
    let day_locked = store.devices[idx].day_locked;
    let prev_quiet = prev_in_quiet;

    // ---- 时间线：这一刻并进"在线区间" ----
    // 设备侧没有"我要下线了"这种信号（被强杀、被冻结都不会发告别请求），
    // 所以"在线/离线"只能由"心跳断没断"推导出来。
    store.touch_presence(idx, now);

    // ---- 状态变化记一笔：家长能在事件流里看到孩子什么时候开始 / 结束休息 ----
    if prev_resting && !resting {
        store.add_event(Some(&did), "rest_end", format!("{dev_name} 休息结束"));
    } else if !prev_resting && resting {
        store.add_event(Some(&did), "rest_start", format!("{dev_name} 进入强制休息"));
    }
    if prev_enabled && !enabled {
        store.add_event(Some(&did), "guard_off", format!("{dev_name} 的守护被关闭"));
    } else if !prev_enabled && enabled {
        store.add_event(Some(&did), "guard_on", format!("{dev_name} 的守护已开启"));
    }
    // 停用时段进出各记一笔：家长翻事件流就能看出"昨晚 22:00 到底锁没锁上"，
    // 而不用靠孩子口述。
    if !prev_quiet && in_quiet {
        let why = if quiet_label.is_empty() {
            "进入停用时段".to_string()
        } else {
            format!("进入停用时段（{quiet_label}）")
        };
        store.add_event(Some(&did), "quiet_start", format!("{dev_name} {why}"));
    } else if prev_quiet && !in_quiet {
        store.add_event(Some(&did), "quiet_end", format!("{dev_name} 停用时段结束"));
    }
    // 每日额度用完 / 解除各记一笔：家长翻事件流就能确认"今天到底几点被锁的"，
    // 这条也是核对"孩子说没玩多久"和"实际用了多久"最直接的证据。
    if !prev_day_locked && day_locked {
        store.add_event(
            Some(&did),
            "day_lock",
            format!("{dev_name} 今日额度已用完，已锁定至明天 0 点"),
        );
    } else if prev_day_locked && !day_locked {
        store.add_event(Some(&did), "day_unlock", format!("{dev_name} 今日额度限制已解除"));
    }

    // ---- 每日统计 ----
    let day = local_day(now, st.cfg.tz_offset_hours);
    let today_used = store.devices[idx].today_used_ms;
    let rest_count = store.devices[idx].rest_count;
    store.upsert_daily(&did, &day, today_used, rest_count);

    // ---- 取待执行指令 ----
    // 先处理"发出去太久没回执"的，重新排队
    for c in store.commands.iter_mut() {
        if c.device_id == did && c.status == "sent" && c.sent_at < now - COMMAND_RESEND_AFTER {
            c.status = "pending".into();
        }
    }

    // ---- 每次都把完整规则带下去 ----
    //
    // 这是"家长设了规则、设备离线"和"设备刷机重装"两种情况的自愈通道：
    // 设备端每次心跳都会拿到权威的规则列表，与本地不一致就覆盖。
    // 代价只是几十字节，换来的是"网页上看到的就是设备上生效的"。
    let quiet_rules = store.devices[idx].quiet_hours.clone();
    // 规则里有任何一条处于启用状态，就算"停用功能可用"，设备据此决定要不要装闹钟
    let quiet_active = quiet_rules.iter().any(|r| r.enabled);

    let mut out: Vec<Value> = Vec::new();
    for c in store.commands.iter_mut() {
        if out.len() >= 20 {
            break;
        }
        if c.device_id == did && c.status == "pending" {
            c.status = "sent".into();
            c.sent_at = now;
            out.push(json!({ "id": c.id, "type": c.kind, "params": c.params }));
        }
    }

    store.save();

    Ok(Json(json!({
        "ok": true,
        "server_time": now,
        "device_name": dev_name,
        "commands": out,
        "quiet_hours": quiet_rules,
        "quiet_active": quiet_active,
        "heartbeat_seconds": HEARTBEAT_SECONDS,
    })))
}

pub async fn device_result(
    State(st): State<Arc<AppState>>,
    headers: HeaderMap,
    Json(body): Json<ResultBody>,
) -> ApiResult<Json<Value>> {
    let mut store = st.lock();
    let idx = require_device(&store, &headers)?;
    let did = store.devices[idx].id.clone();

    let pos = store
        .commands
        .iter()
        .position(|c| c.id == body.command_id && c.device_id == did)
        .ok_or_else(|| ApiError::not_found("指令不存在"))?;

    let kind = store.commands[pos].kind.clone();
    {
        let c = &mut store.commands[pos];
        c.status = if body.ok { "done".into() } else { "failed".into() };
        c.done_at = now_ts();
        c.result = body
            .message
            .as_deref()
            .unwrap_or("")
            .chars()
            .take(500)
            .collect();
    }
    let mut msg = format!("{} → {}", command_label(&kind), if body.ok { "成功" } else { "失败" });
    if let Some(m) = body.message.as_deref().map(str::trim).filter(|m| !m.is_empty()) {
        msg.push_str(&format!("（{m}）"));
    }
    store.add_event(Some(&did), "command_result", msg);
    store.save();

    Ok(Json(json!({ "ok": true })))
}
