//! 数据模型与持久化。
//!
//! # 存储分层
//!
//! | 数据 | 位置 | 原因 |
//! | --- | --- | --- |
//! | 设备 / 账号 / 会话 / 配对码 / 指令 | 内存 + `kv` 表（整行 JSON） | 数量极少（个位数到几十条），热数据必须常驻内存 |
//! | 事件 / 在线区间 / 应用片段 / 每日汇总 | SQLite 独立表 | 只增不减，需要按时间范围查询与分页 |
//!
//! 这样既保住了接口层"直接读 `store.devices`"的简单写法，又让真正会增长的数据
//! 落在有索引的表里 —— 事件攒到几十万条时，查某一天依然是一次索引扫描。
//!
//! 早期版本（v2.1.x 及以前）是**单个 JSON 文件**，升级时会自动把旧的
//! `state.json` 导入 SQLite 并把原文件改名为 `state.json.migrated`，见 [`Store::open`]。

use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};
use serde_json::Value;

use crate::db::Db;
use crate::util::now_ts;

/// 指令在内存里保留多少条（超出丢最老的）。
///
/// 事件不再受这个限制 —— 它们在 SQLite 里按天数清理，见 [`crate::db::EVENT_KEEP_DAYS`]。
pub const MAX_COMMANDS_KEEP: usize = 3000;

/// 设备在线区间。
///
/// 只能靠心跳推导 —— 设备侧没有任何"我要下线了"的信号（进程被强杀、
/// 被系统冻结都不会发告别请求），所以"离线"始终是"心跳断了多久"。
#[derive(Serialize, Deserialize, Clone, Default)]
#[serde(default)]
pub struct PresenceSeg {
    pub start: f64,
    pub end: f64,
}

/// 前台应用使用片段。
///
/// 由 App 侧用 `UsageStatsManager` 采集后上报：每次前台应用切换产生一段，
/// `end` 是"下一段开始"的时刻。最后一段是开放的（`end` 随每次心跳推进），
/// 服务端按 `(device_id, start)` 覆盖同一条记录，所以重复上报不会产生重复片段。
#[derive(Serialize, Deserialize, Clone, Default)]
#[serde(default)]
pub struct UsageSeg {
    pub start: f64,
    pub end: f64,
    /// 包名，用于去重与图标分组
    pub pkg: String,
    /// 应用显示名，由设备上报（服务端没有 APK 可查）
    pub label: String,
}

/// 一条「停用时段」（宵禁）规则。
///
/// 语义：**每天** `from` 分到 `to` 分之间不允许使用，到点自动锁屏，
/// 时段结束后自动解锁。这是和「用一段时间就休息」并列的另一类限制：
/// 前者管"用多久"，这个管"什么时候不能用"。
///
/// 时间一律用**设备本地时区的分钟数**（0..1440，即 0:00 起算）：
///
/// * 只到分钟精度 —— 家长要的是"晚上 10 点开始不许玩"，不是 21:58:37；
/// * `from == to` 视为无效（既不是零长度也不是全天，按全天处理会误伤）；
/// * `from > to` 表示**跨午夜**（22:00 → 06:00）—— 现实里最常见的写法，
///   不能用 `from < to` 这种校验把它挡掉。
///
/// 星期用 7 位位掩码表示，**第 0 位是周一**（`days & 1`），第 6 位是周日。
/// 和前端 `<input type=checkbox>` 的顺序一致，省掉一层换算：
/// `1 + 2 + 4 + 8 + 16 = 31` 正好是"周一到周五"。
///
/// ★ 星期判断以**时段开始那天**为准。跨夜规则「周五 22:00–06:00」勾了周五，
/// 那周六凌晨 0–6 点也在停用范围内 —— 否则家长勾了周五，孩子周六早上还能玩，
/// 会被当成 bug。
#[derive(Serialize, Deserialize, Clone)]
#[serde(default)]
pub struct QuietRule {
    /// 规则 id（`qr_xxxxxx`）。停用本身没有时长，只按 id 定位，供设备回执对账。
    pub id: String,
    /// 是否启用。停用而不是删除，家长临时放开一天不用重建规则。
    pub enabled: bool,
    /// 备注，如「上学日早上」「晚上睡觉」
    pub label: String,
    /// 开始时间，从 0:00 起算的分钟数 0..1440
    pub from: i64,
    /// 结束时间，从 0:00 起算的分钟数 0..1440
    pub to: i64,
    /// 生效星期位掩码（第 0 位=周一）。0 表示**每天**（兼容"没选就是全选"）。
    pub days: i64,
    pub created_at: f64,
}

impl Default for QuietRule {
    fn default() -> Self {
        QuietRule {
            id: String::new(),
            enabled: true,
            label: String::new(),
            // 默认给一个最常见的：晚上 22:00 到次日 06:00，每天
            from: 22 * 60,
            to: 6 * 60,
            days: 0,
            created_at: 0.0,
        }
    }
}

/// 星期位掩码：所有 7 天
pub const DAYS_ALL: i64 = 0b111_1111;

/// 把分钟数格式化成 `HH:MM`（用于事件文案）
pub fn fmt_hhmm(minutes: i64) -> String {
    let m = minutes.clamp(0, 24 * 60);
    format!("{:02}:{:02}", m / 60, m % 60)
}

#[derive(Serialize, Deserialize, Clone)]
#[serde(default)]
pub struct Device {
    pub id: String,
    /// 归属账号 id。管理员能看全部设备，普通账号只能看自己的。
    /// 老数据里没有这个字段（空串），载入时会归给内置管理员。
    pub owner_id: String,
    pub name: String,
    pub model: String,
    pub token: String,
    pub created_at: f64,
    pub last_seen: f64,
    pub last_ip: String,
    pub app_version: String,
    /// 守护是否开启
    pub enabled: bool,
    /// 是否正在强制休息
    pub resting: bool,
    pub rest_left_ms: i64,
    /// 本轮已使用时长
    pub used_ms: i64,
    /// 今日累计（设备本地计算的）
    pub today_used_ms: i64,
    pub rest_count: i64,
    pub use_minutes: i64,
    pub rest_minutes: i64,
    pub reset_gap_seconds: i64,
    pub tamper_policy: bool,
    /// 「拆防护」累计次数（设备上报）。惩罚休息的时长按这个次数翻倍：
    /// 1 次原长、2 次 ×2、3 次起 ×3。家长看到"设 5 分钟却休息 15 分钟"
    /// 就是被这个倍数放大的，后台必须能看见它。
    pub tamper_strikes: i64,
    pub pin_set: bool,
    pub screen_on: bool,
    pub audio_suppressed: bool,
    pub pause_media_on_rest: bool,
    pub mute_on_rest: bool,
    /// 「停用时段」（宵禁）规则列表。
    ///
    /// 和设备其它配置一起存进 `kv` 表的整行 JSON 里，老库载入时字段缺失就取空数组，
    /// 不需要任何迁移动作。
    pub quiet_hours: Vec<QuietRule>,
    /// 设备此刻是不是正处在某个停用时段里（由设备按自己的时钟判断后上报）
    pub in_quiet: bool,
    /// 当前这个停用时段还有多久结束（毫秒）；不在停用中为 0
    pub quiet_left_ms: i64,
    /// 当前生效的那条规则的名字，给后台显示「因「晚上睡觉」停用中」
    pub quiet_label: String,
    pub note: String,
}

impl Default for Device {
    fn default() -> Self {
        Device {
            id: String::new(),
            owner_id: String::new(),
            name: "未命名设备".into(),
            model: String::new(),
            token: String::new(),
            created_at: 0.0,
            last_seen: 0.0,
            last_ip: String::new(),
            app_version: String::new(),
            enabled: false,
            resting: false,
            rest_left_ms: 0,
            used_ms: 0,
            today_used_ms: 0,
            rest_count: 0,
            use_minutes: 30,
            rest_minutes: 5,
            reset_gap_seconds: 300,
            tamper_policy: false,
            tamper_strikes: 0,
            pin_set: false,
            screen_on: false,
            audio_suppressed: false,
            pause_media_on_rest: true,
            mute_on_rest: false,
            quiet_hours: Vec::new(),
            in_quiet: false,
            quiet_left_ms: 0,
            quiet_label: String::new(),
            note: String::new(),
        }
    }
}

/// 把"一条条心跳大小"的原始片段并成真正的一次使用。
///
/// 设备每 20 秒心跳一次、每次都会把"当前正在用的这一段"重报一遍，所以同一个应用
/// 连用 10 分钟，库里就是 30 条首尾相接的记录。直接拿原始片段画时间轴，结果是
/// 一堆挤在一起的小色块（每块不到 1 像素，全被最小宽度撑开、互相叠住）；
/// 统计也会把"打开 1 次"算成"30 次"。
///
/// 合并规则：
/// * 同包 + 间隔 ≤ 60 秒 → 认为是一次连续使用，接成一段
/// * 不同包但时间重叠（历史脏数据/时钟漂移）→ 以更晚开始的为准，把前一段截断，
///   保证"同一时刻只有一个前台应用"
///
/// 时间参数与返回值都是 Unix **秒**，且已夹到 `[from, to]` 这个区间内。
pub fn merge_adjacent(usage: &[UsageSeg], from: f64, to: f64) -> Vec<UsageSeg> {
    const GAP: f64 = 60.0;

    let mut segs: Vec<UsageSeg> = usage
        .iter()
        .filter_map(|u| {
            let s = u.start.max(from);
            let e = u.end.min(to);
            if e - s < 1.0 {
                return None;
            }
            Some(UsageSeg {
                start: s,
                end: e,
                pkg: u.pkg.clone(),
                label: u.label.clone(),
            })
        })
        .collect();

    segs.sort_by(|a, b| {
        a.start
            .partial_cmp(&b.start)
            .unwrap_or(std::cmp::Ordering::Equal)
    });

    let mut out: Vec<UsageSeg> = Vec::with_capacity(segs.len());
    for s in segs {
        let mut drop_last = false;
        if let Some(last) = out.last_mut() {
            if last.pkg == s.pkg && s.start <= last.end + GAP {
                if s.end > last.end {
                    last.end = s.end;
                }
                // 名字后补上报的常见：短的那条可能是包名，长的那条才是可读名
                if last.label.is_empty() || last.label == last.pkg {
                    if !s.label.is_empty() {
                        last.label = s.label.clone();
                    }
                }
                continue;
            }
            if s.start < last.end {
                last.end = s.start;
                drop_last = last.end - last.start < 1.0;
            }
        }
        if drop_last {
            out.pop();
        }
        out.push(s);
    }
    out
}

#[derive(Serialize, Deserialize, Clone)]
#[serde(default)]
pub struct Command {
    pub id: u64,
    pub device_id: String,
    /// 注意：对外 JSON 字段名是 "type"（前端按这个名字读），Rust 里不能叫 type
    #[serde(rename = "type")]
    pub kind: String,
    pub params: Value,
    pub status: String, // pending / sent / done / failed
    pub created_at: f64,
    pub sent_at: f64,
    pub done_at: f64,
    pub result: String,
}

impl Default for Command {
    fn default() -> Self {
        Command {
            id: 0,
            device_id: String::new(),
            kind: String::new(),
            params: Value::Object(Default::default()),
            status: "pending".into(),
            created_at: 0.0,
            sent_at: 0.0,
            done_at: 0.0,
            result: String::new(),
        }
    }
}

#[derive(Serialize, Deserialize, Clone, Default)]
#[serde(default)]
pub struct Event {
    pub id: u64,
    pub device_id: Option<String>,
    pub kind: String,
    pub message: String,
    pub created_at: f64,
}

#[derive(Serialize, Deserialize, Clone, Default)]
#[serde(default)]
pub struct PairCode {
    pub code: String,
    pub created_at: f64,
    pub expires_at: f64,
    pub used_by: Option<String>,
    /// 这个配对码生成时属于哪个账号 —— 配对成功的设备就归他
    pub owner_id: String,
}

#[derive(Serialize, Deserialize, Clone, Default)]
#[serde(default)]
pub struct Session {
    pub token: String,
    pub created_at: f64,
    pub expires_at: f64,
    pub ip: String,
    /// 这个会话属于哪个账号
    pub user_id: String,
}

/// 后台账号。
///
/// 角色只有两种：
///   * `admin` —— 看得到所有设备、能管理账号、能替别的账号生成配对码；
///   * `user`  —— 只看得到归属自己的设备。
#[derive(Serialize, Deserialize, Clone)]
#[serde(default)]
pub struct User {
    pub id: String,
    pub username: String,
    /// `pbkdf2$迭代次数$盐$哈希`，明文密码一律不落盘
    pub password: String,
    /// "admin" / "user"
    pub role: String,
    pub created_at: f64,
    pub last_login: f64,
    pub disabled: bool,
    pub note: String,
}

impl Default for User {
    fn default() -> Self {
        User {
            id: String::new(),
            username: String::new(),
            password: String::new(),
            role: "user".into(),
            created_at: 0.0,
            last_login: 0.0,
            disabled: false,
            note: String::new(),
        }
    }
}

impl User {
    pub fn is_admin(&self) -> bool {
        self.role == "admin"
    }
}

#[derive(Serialize, Deserialize, Clone, Default)]
#[serde(default)]
pub struct Daily {
    pub device_id: String,
    pub day: String,
    pub used_ms: i64,
    pub rest_count: i64,
}

/// 内存中的"小数据" + 一个 SQLite 连接。
pub struct Store {
    pub db: Db,
    pub users: Vec<User>,
    pub devices: Vec<Device>,
    pub commands: Vec<Command>,
    pub pair_codes: Vec<PairCode>,
    pub sessions: Vec<Session>,
    pub next_command_id: u64,
    /// 数据库文件路径（诊断用）
    pub path: PathBuf,
    /// 这次启动是否刚从旧 JSON 迁移过来
    pub migrated_from: Option<PathBuf>,
}

/// v2.1.x 及以前的整库 JSON 结构，只在升级时读一次。
#[derive(Deserialize, Default)]
#[serde(default)]
struct LegacyStore {
    users: Vec<User>,
    devices: Vec<LegacyDevice>,
    commands: Vec<Command>,
    events: Vec<Event>,
    pair_codes: Vec<PairCode>,
    sessions: Vec<Session>,
    daily: Vec<Daily>,
}

#[derive(Deserialize, Default)]
#[serde(default)]
struct LegacyDevice {
    #[serde(flatten)]
    base: Device,
    presence: Vec<PresenceSeg>,
    usage: Vec<UsageSeg>,
}

impl Store {
    /// 打开（必要时创建）数据库。
    ///
    /// `path` 是 SQLite 文件。如果同目录下存在上一版留下的 `state.json`，
    /// 会自动导入一次并把原文件改名 —— 家长升级时**不需要手动执行任何命令**。
    pub fn open(path: &Path) -> Store {
        let db = match Db::open(path) {
            Ok(d) => d,
            Err(e) => {
                eprintln!("[存储] 打不开数据库 {}：{e}", path.display());
                eprintln!("       检查目录是否存在、是否有写权限。");
                std::process::exit(1);
            }
        };

        let mut s = Store {
            db,
            users: Vec::new(),
            devices: Vec::new(),
            commands: Vec::new(),
            pair_codes: Vec::new(),
            sessions: Vec::new(),
            next_command_id: 0,
            path: path.to_path_buf(),
            migrated_from: None,
        };
        s.load_small();

        // 旧版 JSON 自动迁移
        let legacy = path.with_file_name("state.json");
        if legacy.exists() {
            s.migrate_legacy(&legacy);
        }

        s.sanitize();

        let (evs, uses, pres) = s.db.stats();
        println!(
            "[存储] SQLite 已就绪 · {} 台设备 · {} 个账号 · {} 条事件 · {} 条应用片段 · {} 段在线",
            s.devices.len(),
            s.users.len(),
            evs,
            uses,
            pres
        );
        println!("[存储] 数据库文件：{}", path.display());
        s
    }

    // ==================================================================
    // 小数据的读写
    // ==================================================================

    fn load_small(&mut self) {
        for raw in self.db.kv_all("dev") {
            if let Ok(d) = serde_json::from_str::<Device>(&raw) {
                self.devices.push(d);
            }
        }
        for raw in self.db.kv_all("usr") {
            if let Ok(u) = serde_json::from_str::<User>(&raw) {
                self.users.push(u);
            }
        }
        for raw in self.db.kv_all("ses") {
            if let Ok(x) = serde_json::from_str::<Session>(&raw) {
                self.sessions.push(x);
            }
        }
        for raw in self.db.kv_all("pair") {
            if let Ok(x) = serde_json::from_str::<PairCode>(&raw) {
                self.pair_codes.push(x);
            }
        }
        for raw in self.db.kv_all("cmd") {
            if let Ok(x) = serde_json::from_str::<Command>(&raw) {
                self.commands.push(x);
            }
        }
        self.commands.sort_by_key(|c| c.id);
    }

    /// 写库：把内存里的小数据整体同步到 `kv` 表。
    ///
    /// 这些对象总共几十行，全量替换比算 diff 简单且不会漏删。
    /// 事件/时间线不在这里 —— 它们是各写各的，见 [`Store::add_event`] 等。
    pub fn save(&mut self) {
        self.trim();

        let rows = |items: Vec<(String, String)>| items;
        let mut dev = Vec::with_capacity(self.devices.len());
        for d in &self.devices {
            if let Ok(v) = serde_json::to_string(d) {
                dev.push((format!("dev:{}", d.id), v));
            }
        }
        self.db.kv_replace("dev", &rows(dev));

        let mut usr = Vec::with_capacity(self.users.len());
        for u in &self.users {
            if let Ok(v) = serde_json::to_string(u) {
                usr.push((format!("usr:{}", u.id), v));
            }
        }
        self.db.kv_replace("usr", &rows(usr));

        let mut ses = Vec::with_capacity(self.sessions.len());
        for x in &self.sessions {
            if let Ok(v) = serde_json::to_string(x) {
                ses.push((format!("ses:{}", x.token), v));
            }
        }
        self.db.kv_replace("ses", &rows(ses));

        let mut pair = Vec::with_capacity(self.pair_codes.len());
        for x in &self.pair_codes {
            if let Ok(v) = serde_json::to_string(x) {
                pair.push((format!("pair:{}", x.code), v));
            }
        }
        self.db.kv_replace("pair", &rows(pair));

        let mut cmd = Vec::with_capacity(self.commands.len());
        for x in &self.commands {
            if let Ok(v) = serde_json::to_string(x) {
                cmd.push((format!("cmd:{}", x.id), v));
            }
        }
        self.db.kv_replace("cmd", &rows(cmd));
    }

    fn trim(&mut self) {
        if self.commands.len() > MAX_COMMANDS_KEEP {
            let cut = self.commands.len() - MAX_COMMANDS_KEEP;
            self.commands.drain(0..cut);
        }
        // 会话过期就清掉，避免库里堆积没人用的 token
        let now = now_ts();
        self.sessions.retain(|s| s.expires_at > now);
    }

    fn sanitize(&mut self) {
        let now = now_ts();
        self.sessions.retain(|s| s.expires_at > now);
        self.pair_codes.retain(|p| p.expires_at + 3600.0 > now);
        // 账号被删掉之后，它的会话立刻失效
        if !self.users.is_empty() {
            let ids: Vec<String> = self.users.iter().map(|u| u.id.clone()).collect();
            self.sessions
                .retain(|s| ids.iter().any(|id| *id == s.user_id));
        }
        if self.next_command_id == 0 {
            self.next_command_id = self.commands.iter().map(|c| c.id).max().unwrap_or(0) + 1;
        }
        // 停用时段规则逐台设备清洗一遍：手改过数据库、或者客户端传来脏数据时，
        // 越界的时间/位掩码会让设备端的判断逻辑算出负数时长。宁可在这里夹住。
        for d in self.devices.iter_mut() {
            let mut seen: Vec<String> = Vec::new();
            let mut clean: Vec<QuietRule> = Vec::new();
            for r in d.quiet_hours.drain(..) {
                let mut r = r;
                r.from = r.from.clamp(0, 1440);
                r.to = r.to.clamp(0, 1440);
                r.days = r.days & DAYS_ALL;
                if r.from == r.to {
                    // 零长度（或 24 小时全天）一律丢掉：两种解释都会让人意外
                    continue;
                }
                if r.id.is_empty() {
                    r.id = crate::util::rand_hex(4);
                }
                // id 冲突（手工复制粘贴出来的）就换一个，否则设备对账会错
                if seen.contains(&r.id) {
                    r.id = crate::util::rand_hex(4);
                }
                seen.push(r.id.clone());
                clean.push(r);
            }
            d.quiet_hours = clean;
        }
    }

    // ==================================================================
    // 旧版 JSON 迁移
    // ==================================================================

    /// 把 v2.1.x 的 `state.json` 一次性导入 SQLite。
    ///
    /// 失败不致命：留着原文件，下次启动还会重试。
    fn migrate_legacy(&mut self, json_path: &Path) {
        let bytes = match std::fs::read(json_path) {
            Ok(b) => b,
            Err(e) => {
                eprintln!("[迁移] 读不了 {}：{e}", json_path.display());
                return;
            }
        };
        if bytes.is_empty() {
            let _ = std::fs::rename(json_path, json_path.with_extension("json.empty"));
            return;
        }
        let old: LegacyStore = match serde_json::from_slice(&bytes) {
            Ok(v) => v,
            Err(e) => {
                eprintln!(
                    "[迁移] {} 解析失败（{e}），跳过迁移；原文件保留不动",
                    json_path.display()
                );
                return;
            }
        };

        println!(
            "[迁移] 发现旧版数据 {}，开始导入 SQLite：{} 台设备 / {} 个账号 / {} 条事件 / {} 条片段",
            json_path.display(),
            old.devices.len(),
            old.users.len(),
            old.events.len(),
            old.devices.iter().map(|d| d.usage.len()).sum::<usize>()
        );

        let now = now_ts();

        // 事件：按时间升序插入，id 交给 SQLite 重新分配
        let events: Vec<(String, String, String, f64)> = old
            .events
            .iter()
            .map(|e| {
                (
                    e.device_id.clone().unwrap_or_default(),
                    e.kind.clone(),
                    e.message.clone(),
                    e.created_at,
                )
            })
            .collect();

        // 时间线：按设备拆开
        let mut presence: Vec<(String, f64, f64)> = Vec::new();
        let mut usage: Vec<(String, f64, f64, String, String)> = Vec::new();
        for d in &old.devices {
            for p in &d.presence {
                presence.push((d.base.id.clone(), p.start, p.end));
            }
            for u in &d.usage {
                usage.push((
                    d.base.id.clone(),
                    u.start,
                    u.end,
                    u.pkg.clone(),
                    u.label.clone(),
                ));
            }
        }

        let daily: Vec<Daily> = old.daily.clone();

        let ok = self.db.with_tx(|tx| {
            let mut st = tx.prepare(
                "INSERT INTO events(ts, kind, device_id, message) VALUES(?1, ?2, ?3, ?4)",
            )?;
            for (dev, kind, msg, ts) in &events {
                let dev = if dev.is_empty() { None } else { Some(dev.as_str()) };
                st.execute(rusqlite::params![ts, kind, dev, msg])?;
            }

            let mut st = tx.prepare(
                "INSERT INTO presence(device_id, start, end) VALUES(?1, ?2, ?3)",
            )?;
            for (dev, s, e) in &presence {
                st.execute(rusqlite::params![dev, s, e])?;
            }

            let mut st = tx.prepare(
                "INSERT OR REPLACE INTO usage(device_id, start, end, pkg, label)
                 VALUES(?1, ?2, ?3, ?4, ?5)",
            )?;
            for (dev, s, e, pkg, label) in &usage {
                st.execute(rusqlite::params![dev, s, e, pkg, label])?;
            }

            let mut st = tx.prepare(
                "INSERT OR REPLACE INTO daily(device_id, day, used_ms, rest_count)
                 VALUES(?1, ?2, ?3, ?4)",
            )?;
            for r in &daily {
                st.execute(rusqlite::params![r.device_id, r.day, r.used_ms, r.rest_count])?;
            }
            Ok(())
        });

        if !ok {
            eprintln!("[迁移] 导入失败，原 JSON 保留，下次启动会重试");
            return;
        }

        // 小数据装进内存再落库
        self.devices = old.devices.into_iter().map(|d| d.base).collect();
        self.users = old.users;
        self.commands = old.commands;
        self.pair_codes = old.pair_codes;
        self.sessions = old.sessions;
        self.next_command_id = self.commands.iter().map(|c| c.id).max().unwrap_or(0) + 1;
        self.save();

        let bak = json_path.with_extension(format!("json.migrated-{}", now as i64));
        match std::fs::rename(json_path, &bak) {
            Ok(_) => {
                println!("[迁移] 完成。原文件已改名为 {}", bak.display());
                println!("[迁移] 确认一切正常后可以删掉它，它不再被读取。");
                self.migrated_from = Some(bak);
            }
            Err(e) => {
                eprintln!("[迁移] 导入成功，但原文件改名失败（{e}）；");
                eprintln!("       为避免重复导入，下次启动会跳过它（数据已在库里）。");
                self.migrated_from = Some(json_path.to_path_buf());
            }
        }
    }

    // ==================================================================
    // 账号
    // ==================================================================

    /// 首次启动（或老版本数据升级）时补上内置管理员账号，并把没有归属的设备交给它。
    ///
    /// 返回 true 表示这次新建了管理员（调用方会打印一次提示）。
    pub fn ensure_admin(&mut self, password_hash: String) -> bool {
        let mut created = false;
        if self.users.is_empty() {
            self.users.push(User {
                id: "u_admin".into(),
                username: "admin".into(),
                password: password_hash,
                role: "admin".into(),
                created_at: now_ts(),
                last_login: 0.0,
                disabled: false,
                note: "内置管理员".into(),
            });
            created = true;
        }

        // 老版本（v1.x）的会话没有 user_id，升级后一律作废，让家长重新登录一次
        self.sessions.retain(|s| !s.user_id.is_empty());

        // 老版本数据里设备没有 owner_id，统一归给第一个管理员，
        // 否则升级后家长会看到"一台设备都没有"。
        let admin_id = self
            .users
            .iter()
            .find(|u| u.is_admin())
            .map(|u| u.id.clone())
            .unwrap_or_default();
        for d in self.devices.iter_mut() {
            if d.owner_id.is_empty() {
                d.owner_id = admin_id.clone();
            }
        }
        for p in self.pair_codes.iter_mut() {
            if p.owner_id.is_empty() {
                p.owner_id = admin_id.clone();
            }
        }
        created
    }

    pub fn user_index(&self, id: &str) -> Option<usize> {
        self.users.iter().position(|u| u.id == id)
    }

    /// 用户名不区分大小写，避免"Admin"和"admin"两个账号互相看不见对方
    pub fn user_index_by_name(&self, name: &str) -> Option<usize> {
        let n = name.trim().to_lowercase();
        self.users
            .iter()
            .position(|u| u.username.to_lowercase() == n)
    }

    pub fn admin_count(&self) -> usize {
        self.users.iter().filter(|u| u.is_admin() && !u.disabled).count()
    }

    // ==================================================================
    // 事件
    // ==================================================================

    pub fn add_event(&mut self, device_id: Option<&str>, kind: &str, message: impl Into<String>) {
        let msg = message.into();
        self.db.add_event(device_id, kind, &msg);
    }

    // ==================================================================
    // 时间线
    // ==================================================================

    pub fn touch_presence(&mut self, idx: usize, now: f64) {
        if let Some(d) = self.devices.get(idx) {
            let id = d.id.clone();
            self.db.touch_presence(&id, now);
        }
    }

    /// 合并一批前台应用片段。
    ///
    /// 幂等：同 `(device, start)` 的片段直接覆盖（设备会反复上报"当前这一段"，
    /// 每次把 `end` 往后推）。这里先做三件清理再交给数据库：
    /// 过短的片段丢掉（< 2 秒的多半是切换动画）、时钟超前的夹住、包名为空的跳过。
    pub fn merge_usage(&mut self, idx: usize, incoming: Vec<UsageSeg>, now: f64) {
        let Some(d) = self.devices.get(idx) else {
            return;
        };
        let id = d.id.clone();

        let mut clean: Vec<UsageSeg> = Vec::with_capacity(incoming.len());
        for seg in incoming {
            if seg.pkg.is_empty() || seg.start <= 0.0 {
                continue;
            }
            // 设备时钟可能超前，夹一下，免得时间线出现"未来的应用"
            let start = seg.start.min(now + 5.0);
            let mut end = seg.end.max(start);
            if end - start < 2.0 {
                continue;
            }
            end = end.min(now + 5.0);
            if end - start < 2.0 {
                continue;
            }
            clean.push(UsageSeg {
                start,
                end,
                pkg: seg.pkg,
                label: seg.label,
            });
        }
        self.db.upsert_usage(&id, &clean);
    }

    pub fn presence_of(&self, device_id: &str, from: f64, to: f64) -> Vec<PresenceSeg> {
        self.db.presence_between(device_id, from, to)
    }

    pub fn usage_of(&self, device_id: &str, from: f64, to: f64) -> Vec<UsageSeg> {
        self.db.usage_between(device_id, from, to)
    }

    pub fn has_usage(&self, device_id: &str) -> bool {
        self.db.has_usage(device_id)
    }

    // ==================================================================
    // 指令 / 设备 / 每日
    // ==================================================================

    pub fn push_command(&mut self, device_id: &str, kind: &str, params: Value) -> u64 {
        let id = self.next_command_id;
        self.next_command_id += 1;
        self.commands.push(Command {
            id,
            device_id: device_id.to_string(),
            kind: kind.to_string(),
            params,
            status: "pending".into(),
            created_at: now_ts(),
            sent_at: 0.0,
            done_at: 0.0,
            result: String::new(),
        });
        id
    }

    pub fn device_index(&self, id: &str) -> Option<usize> {
        self.devices.iter().position(|d| d.id == id)
    }

    pub fn device_index_by_token(&self, token: &str) -> Option<usize> {
        self.devices.iter().position(|d| d.token == token)
    }

    /// 每日统计按"取最大值"合并：心跳抖动或设备时钟回拨都不会让数字变小
    pub fn upsert_daily(&mut self, device_id: &str, day: &str, used_ms: i64, rest_count: i64) {
        self.db.upsert_daily(device_id, day, used_ms, rest_count);
    }

    /// 设备被移除时清掉它名下的流水
    pub fn purge_device(&mut self, device_id: &str) {
        self.db.delete_device_data(device_id);
    }
}
