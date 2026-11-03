//! SQLite 持久层。
//!
//! # 为什么从 JSON 换成 SQLite
//!
//! 原来整库是一个 JSON 文件，每次改动都要**重写整个文件**。事件是只增不减的流水，
//! 用久了会出两个问题：
//!
//! 1. 文件线性变大 —— 一次心跳要序列化几十 MB、写几十 MB，
//!    延迟从毫秒级涨到几百毫秒，手机上看到的"后台点了没反应"就是这么来的；
//! 2. 只能在内存里 `filter` —— 分页、按天查询都得先把全部事件读进来，
//!    数据一多就卡，而且进程一挂就全丢。
//!
//! SQLite 正好补上：单文件、无服务进程、有索引、事务写入。
//!
//! # 为什么仍然是"单文件静态二进制"
//!
//! `rusqlite` 开 `bundled`，把 `sqlite3.c` 一起编进二进制。交叉编译 C 代码这一步
//! 由 zig 提供（见 `build.sh`），所以**目标机不需要装 sqlite3、不需要任何 .so**。
//!
//! # 表设计上的折中
//!
//! * 会无限增长的四张表（`events` / `presence` / `usage` / `daily`）用真正的列 + 索引，
//!   因为它们需要按时间范围查询和分页；
//! * 数量很少、字段又多的五类对象（设备 / 账号 / 会话 / 配对码 / 指令）整行 JSON
//!   存进 `kv` 表 —— 省掉几十个字段的映射代码，查询只按主键，热数据本来就在内存里。

use std::path::Path;
use std::sync::Mutex;

use rusqlite::types::Value as SqlValue;
use rusqlite::{params, params_from_iter, Connection, OptionalExtension};

use crate::store::{Daily, Event, PresenceSeg, UsageSeg};

/// 事件保留天数。一年足够回看，再久意义不大。
pub const EVENT_KEEP_DAYS: f64 = 365.0;
/// 时间线（在线区间 + 前台应用片段）保留天数。
///
/// 片段是 20 秒一条粒度，一天几千条，一年能到百万级。90 天是"体积"和
/// "能回看一个季度"之间的折中，超出自动清理。
pub const TIMELINE_KEEP_DAYS: f64 = 90.0;

/// 两次心跳间隔超过这个值就断成两个"在线区间"。
///
/// 心跳是 20 秒一次，这里给到 90 秒：允许中间丢两三次心跳，
/// 网络抖一下不会把一整段在线时间切成碎片。
pub const PRESENCE_GAP: f64 = 90.0;

const SCHEMA_VERSION: i64 = 1;

const SCHEMA_SQL: &str = r#"
CREATE TABLE IF NOT EXISTS kv (
    k TEXT PRIMARY KEY,
    v TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS events (
    id        INTEGER PRIMARY KEY AUTOINCREMENT,
    ts        REAL NOT NULL,
    kind      TEXT NOT NULL,
    device_id TEXT,
    message   TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_events_ts     ON events(ts DESC);
CREATE INDEX IF NOT EXISTS ix_events_dev_ts ON events(device_id, ts DESC);

CREATE TABLE IF NOT EXISTS presence (
    id        INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id TEXT NOT NULL,
    start     REAL NOT NULL,
    end       REAL NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_presence_dev ON presence(device_id, end DESC);

CREATE TABLE IF NOT EXISTS usage (
    device_id TEXT NOT NULL,
    start     REAL NOT NULL,
    end       REAL NOT NULL,
    pkg       TEXT NOT NULL,
    label     TEXT NOT NULL DEFAULT '',
    PRIMARY KEY (device_id, start)
);
CREATE INDEX IF NOT EXISTS ix_usage_dev_end ON usage(device_id, end);

CREATE TABLE IF NOT EXISTS daily (
    device_id  TEXT NOT NULL,
    day        TEXT NOT NULL,
    used_ms    INTEGER NOT NULL DEFAULT 0,
    rest_count INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (device_id, day)
);

-- 亮屏区间。只有"开着"的区间，关屏就是没有覆盖到的时间。
--
-- 为什么要单独存：前台应用片段只说明"哪个应用在最前面"，不会因为熄屏而结束 ——
-- 熄屏后系统并不会把应用踢到后台，最后那个应用会一直挂着。没有这张表，
-- 整夜的熄屏时间都会被算成"在用 B 站"。
CREATE TABLE IF NOT EXISTS screen (
    device_id TEXT NOT NULL,
    start     REAL NOT NULL,
    end       REAL NOT NULL,
    PRIMARY KEY (device_id, start)
);
CREATE INDEX IF NOT EXISTS ix_screen_dev ON screen(device_id, end);

-- 每日设备状态计数：解锁 / 开机 / 关机 / 亮屏时长 / 系统口径的应用总时长。
--
-- 独立成表而不是往 daily 上加列：老库升级时不用 ALTER TABLE，
-- 建表语句是 IF NOT EXISTS，旧版本直接多出两张表就能用。
CREATE TABLE IF NOT EXISTS daystat (
    device_id  TEXT NOT NULL,
    day        TEXT NOT NULL,
    unlock_cnt INTEGER NOT NULL DEFAULT 0,
    boot_cnt   INTEGER NOT NULL DEFAULT 0,
    off_cnt    INTEGER NOT NULL DEFAULT 0,
    screen_ms  INTEGER NOT NULL DEFAULT 0,
    sys_ms     INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (device_id, day)
);
"#;

pub struct Db {
    conn: Mutex<Connection>,
}

impl Db {
    pub fn open(path: &Path) -> rusqlite::Result<Db> {
        if let Some(dir) = path.parent() {
            if !dir.as_os_str().is_empty() {
                let _ = std::fs::create_dir_all(dir);
            }
        }
        let conn = Connection::open(path)?;
        // WAL：读不阻塞写，后台刷新页面时心跳不会被卡住。
        // synchronous=NORMAL 在 WAL 下已经足够安全（断电最多丢最后一个事务）。
        conn.execute_batch(
            "PRAGMA journal_mode=WAL;\
             PRAGMA synchronous=NORMAL;\
             PRAGMA busy_timeout=5000;\
             PRAGMA temp_store=MEMORY;",
        )?;
        let db = Db {
            conn: Mutex::new(conn),
        };
        db.init_schema()?;
        Ok(db)
    }

    /// 进程内的连接锁。被 panic 毒化也要能继续用 —— 数据库本身没有坏。
    fn c(&self) -> std::sync::MutexGuard<'_, Connection> {
        self.conn.lock().unwrap_or_else(|e| e.into_inner())
    }

    fn init_schema(&self) -> rusqlite::Result<()> {
        let c = self.c();
        c.execute_batch(SCHEMA_SQL)?;
        c.execute(
            "INSERT INTO kv(k, v) VALUES('meta:schema', ?1)
             ON CONFLICT(k) DO UPDATE SET v = excluded.v",
            params![SCHEMA_VERSION.to_string()],
        )?;
        Ok(())
    }

    /// 库文件有多大（诊断用）
    pub fn file_size(&self, path: &Path) -> u64 {
        std::fs::metadata(path).map(|m| m.len()).unwrap_or(0)
    }

    // ==================================================================
    // 小对象：整行 JSON 存 kv
    // ==================================================================

    pub fn kv_all(&self, prefix: &str) -> Vec<String> {
        let c = self.c();
        let mut st = match c.prepare("SELECT v FROM kv WHERE k LIKE ?1") {
            Ok(s) => s,
            Err(_) => return Vec::new(),
        };
        let rows = st.query_map(params![format!("{prefix}:%")], |r| r.get::<_, String>(0));
        match rows {
            Ok(it) => it.filter_map(|r| r.ok()).collect(),
            Err(_) => Vec::new(),
        }
    }

    /// 整族替换：先删掉这个前缀下所有旧行，再写入新的。
    ///
    /// 这几类对象总共也就几十行，全量重写比算 diff 简单得多，也不会漏删。
    pub fn kv_replace(&self, prefix: &str, rows: &[(String, String)]) {
        let mut c = self.c();
        let tx = match c.transaction() {
            Ok(t) => t,
            Err(e) => {
                eprintln!("[存储] 开启事务失败：{e}");
                return;
            }
        };
        if let Err(e) = tx.execute("DELETE FROM kv WHERE k LIKE ?1", params![format!("{prefix}:%")]) {
            eprintln!("[存储] 清理 {prefix} 失败：{e}");
            return;
        }
        for (k, v) in rows {
            if let Err(e) = tx.execute(
                "INSERT OR REPLACE INTO kv(k, v) VALUES(?1, ?2)",
                params![k, v],
            ) {
                eprintln!("[存储] 写入 {k} 失败：{e}");
            }
        }
        if let Err(e) = tx.commit() {
            eprintln!("[存储] 提交 {prefix} 失败：{e}");
        }
    }

    // ==================================================================
    // 事件
    // ==================================================================

    pub fn add_event(&self, device_id: Option<&str>, kind: &str, message: &str) -> i64 {
        let c = self.c();
        match c.execute(
            "INSERT INTO events(ts, kind, device_id, message) VALUES(?1, ?2, ?3, ?4)",
            params![crate::util::now_ts(), kind, device_id, message],
        ) {
            Ok(_) => c.last_insert_rowid(),
            Err(e) => {
                eprintln!("[存储] 写事件失败：{e}");
                0
            }
        }
    }

    /// 构造事件查询的 WHERE 子句。
    ///
    /// * `device = Some(id)` —— 只看这一台（设备详情页）
    /// * `visible = Some(ids)` —— 普通账号只能看自己名下设备的事件；
    ///   传空数组表示"一台都看不到"，直接给一个恒假条件，避免拼出 `IN ()` 语法错误。
    /// * `visible = None` —— 管理员，不过滤。
    fn event_where(
        device: Option<&str>,
        visible: Option<&[String]>,
        from: f64,
        to: f64,
    ) -> (String, Vec<SqlValue>) {
        let mut w = String::from("WHERE ts >= ? AND ts < ?");
        let mut p: Vec<SqlValue> = vec![SqlValue::Real(from), SqlValue::Real(to)];
        if let Some(d) = device {
            w.push_str(" AND device_id = ?");
            p.push(SqlValue::Text(d.to_string()));
        } else if let Some(ids) = visible {
            if ids.is_empty() {
                w.push_str(" AND 0");
            } else {
                let ph = vec!["?"; ids.len()].join(",");
                w.push_str(&format!(" AND device_id IN ({ph})"));
                for id in ids {
                    p.push(SqlValue::Text(id.clone()));
                }
            }
        }
        (w, p)
    }

    pub fn events_page(
        &self,
        device: Option<&str>,
        visible: Option<&[String]>,
        from: f64,
        to: f64,
        limit: i64,
        offset: i64,
    ) -> (Vec<Event>, i64) {
        let (w, p) = Self::event_where(device, visible, from, to);
        let c = self.c();

        let total: i64 = c
            .query_row(
                &format!("SELECT COUNT(*) FROM events {w}"),
                params_from_iter(p.iter()),
                |r| r.get(0),
            )
            .unwrap_or(0);

        let sql = format!(
            "SELECT id, device_id, kind, message, ts FROM events {w} \
             ORDER BY ts DESC, id DESC LIMIT ? OFFSET ?"
        );
        let mut p2 = p;
        p2.push(SqlValue::Integer(limit.max(0)));
        p2.push(SqlValue::Integer(offset.max(0)));
        let mut st = match c.prepare(&sql) {
            Ok(s) => s,
            Err(e) => {
                eprintln!("[存储] 查询事件失败：{e}");
                return (Vec::new(), total);
            }
        };
        let rows = st.query_map(params_from_iter(p2.iter()), |r| {
            Ok(Event {
                id: r.get::<_, i64>(0)? as u64,
                device_id: r.get(1)?,
                kind: r.get(2)?,
                message: r.get(3)?,
                created_at: r.get(4)?,
            })
        });
        let items = match rows {
            Ok(it) => it.filter_map(|r| r.ok()).collect(),
            Err(_) => Vec::new(),
        };
        (items, total)
    }

    /// 按 kind 统计数量（首页"今日休息次数"、趋势图用）
    pub fn count_kind(
        &self,
        kind: &str,
        from: f64,
        to: f64,
        visible: Option<&[String]>,
    ) -> i64 {
        let (mut w, mut p) = Self::event_where(None, visible, from, to);
        w.push_str(" AND kind = ?");
        p.push(SqlValue::Text(kind.to_string()));
        self.c()
            .query_row(
                &format!("SELECT COUNT(*) FROM events {w}"),
                params_from_iter(p.iter()),
                |r| r.get(0),
            )
            .unwrap_or(0)
    }

    // ==================================================================
    // 每日汇总
    // ==================================================================

    /// 按"取最大值"合并：心跳抖动或设备时钟回拨都不会让数字变小
    pub fn upsert_daily(&self, device_id: &str, day: &str, used_ms: i64, rest_count: i64) {
        let _ = self.c().execute(
            "INSERT INTO daily(device_id, day, used_ms, rest_count) VALUES(?1, ?2, ?3, ?4)
             ON CONFLICT(device_id, day) DO UPDATE SET
                used_ms    = MAX(daily.used_ms, excluded.used_ms),
                rest_count = MAX(daily.rest_count, excluded.rest_count)",
            params![device_id, day, used_ms, rest_count],
        );
    }

    pub fn daily_of(&self, device_id: &str, limit: i64) -> Vec<Daily> {
        let c = self.c();
        let mut st = match c.prepare(
            "SELECT device_id, day, used_ms, rest_count FROM daily
             WHERE device_id = ?1 ORDER BY day DESC LIMIT ?2",
        ) {
            Ok(s) => s,
            Err(_) => return Vec::new(),
        };
        let rows = st.query_map(params![device_id, limit], |r| {
            Ok(Daily {
                device_id: r.get(0)?,
                day: r.get(1)?,
                used_ms: r.get(2)?,
                rest_count: r.get(3)?,
            })
        });
        match rows {
            Ok(it) => it.filter_map(|r| r.ok()).collect(),
            Err(_) => Vec::new(),
        }
    }

    /// 一段日期区间内、指定设备集合的每日使用时长（趋势图）
    pub fn daily_between(&self, days: &[String], visible: Option<&[String]>) -> Vec<Daily> {
        if days.is_empty() {
            return Vec::new();
        }
        let mut sql = String::from(
            "SELECT device_id, day, used_ms, rest_count FROM daily WHERE day IN (",
        );
        sql.push_str(&vec!["?"; days.len()].join(","));
        sql.push(')');
        let mut p: Vec<SqlValue> = days
            .iter()
            .map(|d| SqlValue::Text(d.clone()))
            .collect();
        if let Some(ids) = visible {
            if ids.is_empty() {
                return Vec::new();
            }
            sql.push_str(&format!(
                " AND device_id IN ({})",
                vec!["?"; ids.len()].join(",")
            ));
            for id in ids {
                p.push(SqlValue::Text(id.clone()));
            }
        }
        let c = self.c();
        let mut st = match c.prepare(&sql) {
            Ok(s) => s,
            Err(_) => return Vec::new(),
        };
        let rows = st.query_map(params_from_iter(p.iter()), |r| {
            Ok(Daily {
                device_id: r.get(0)?,
                day: r.get(1)?,
                used_ms: r.get(2)?,
                rest_count: r.get(3)?,
            })
        });
        match rows {
            Ok(it) => it.filter_map(|r| r.ok()).collect(),
            Err(_) => Vec::new(),
        }
    }

    // ==================================================================
    // 时间线
    // ==================================================================

    /// 记录一次心跳：把这一刻并进"在线区间"。
    ///
    /// 与最后一段的尾巴相距不超过 [`PRESENCE_GAP`] 就延长它，否则开一段新的。
    /// 这样一天下来通常只有几段（上课一段、晚上一段），不会一个心跳一行。
    pub fn touch_presence(&self, device_id: &str, now: f64) {
        let c = self.c();
        let last: Option<(i64, f64)> = c
            .query_row(
                "SELECT id, end FROM presence WHERE device_id = ?1 ORDER BY end DESC LIMIT 1",
                params![device_id],
                |r| Ok((r.get(0)?, r.get(1)?)),
            )
            .optional()
            .unwrap_or(None);

        match last {
            Some((id, end)) if now >= end && now - end <= PRESENCE_GAP => {
                let _ = c.execute("UPDATE presence SET end = ?1 WHERE id = ?2", params![now, id]);
            }
            // 时钟回拨（设备改时间）：不要在原地改出一个 end < start 的区间
            Some((_, end)) if now < end => {}
            _ => {
                let _ = c.execute(
                    "INSERT INTO presence(device_id, start, end) VALUES(?1, ?2, ?3)",
                    params![device_id, now, now],
                );
            }
        }
    }

    /// 批量写入前台应用片段。
    ///
    /// 幂等：主键是 `(device_id, start)`，设备反复上报"当前这一段"时
    /// 只会把 `end` 往后推，不会攒出重复记录。`end` 只增不减
    /// （乱序上报时不至于把片段越改越短）。
    pub fn upsert_usage(&self, device_id: &str, segs: &[UsageSeg]) -> usize {
        if segs.is_empty() {
            return 0;
        }
        let mut c = self.c();
        let tx = match c.transaction() {
            Ok(t) => t,
            Err(e) => {
                eprintln!("[存储] 开启事务失败：{e}");
                return 0;
            }
        };
        let mut n = 0usize;
        {
            let mut st = match tx.prepare(
                "INSERT INTO usage(device_id, start, end, pkg, label) VALUES(?1, ?2, ?3, ?4, ?5)
                 ON CONFLICT(device_id, start) DO UPDATE SET
                    end   = MAX(usage.end, excluded.end),
                    label = CASE WHEN excluded.label <> '' THEN excluded.label ELSE usage.label END",
            ) {
                Ok(s) => s,
                Err(_) => return 0,
            };
            for s in segs {
                if st
                    .execute(params![device_id, s.start, s.end, s.pkg, s.label])
                    .is_ok()
                {
                    n += 1;
                }
            }
        }
        if let Err(e) = tx.commit() {
            eprintln!("[存储] 提交应用片段失败：{e}");
            return 0;
        }
        n
    }

    pub fn presence_between(&self, device_id: &str, from: f64, to: f64) -> Vec<PresenceSeg> {
        let c = self.c();
        let mut st = match c.prepare(
            "SELECT start, end FROM presence
             WHERE device_id = ?1 AND end >= ?2 AND start < ?3
             ORDER BY start",
        ) {
            Ok(s) => s,
            Err(_) => return Vec::new(),
        };
        let rows = st.query_map(params![device_id, from, to], |r| {
            Ok(PresenceSeg {
                start: r.get(0)?,
                end: r.get(1)?,
            })
        });
        match rows {
            Ok(it) => it.filter_map(|r| r.ok()).collect(),
            Err(_) => Vec::new(),
        }
    }

    pub fn usage_between(&self, device_id: &str, from: f64, to: f64) -> Vec<UsageSeg> {
        let c = self.c();
        let mut st = match c.prepare(
            "SELECT start, end, pkg, label FROM usage
             WHERE device_id = ?1 AND end >= ?2 AND start < ?3
             ORDER BY start",
        ) {
            Ok(s) => s,
            Err(_) => return Vec::new(),
        };
        let rows = st.query_map(params![device_id, from, to], |r| {
            Ok(UsageSeg {
                start: r.get(0)?,
                end: r.get(1)?,
                pkg: r.get(2)?,
                label: r.get(3)?,
            })
        });
        match rows {
            Ok(it) => it.filter_map(|r| r.ok()).collect(),
            Err(_) => Vec::new(),
        }
    }

    /// 这台设备有没有上报过应用片段（决定前端要不要提示"未授权使用记录"）
    pub fn has_usage(&self, device_id: &str) -> bool {
        self.c()
            .query_row(
                "SELECT 1 FROM usage WHERE device_id = ?1 LIMIT 1",
                params![device_id],
                |_| Ok(()),
            )
            .optional()
            .unwrap_or(None)
            .is_some()
    }

    // ==================================================================
    // 亮屏区间
    // ==================================================================

    /// 写入亮屏区间。和 [`Self::upsert_usage`] 一样按 `(device_id, start)` 幂等，
    /// `end` 只增不减 —— 设备反复上报"当前这一段还开着"时只会把尾巴往后推。
    pub fn upsert_screen(&self, device_id: &str, segs: &[PresenceSeg]) -> usize {
        if segs.is_empty() {
            return 0;
        }
        let mut c = self.c();
        let tx = match c.transaction() {
            Ok(t) => t,
            Err(e) => {
                eprintln!("[存储] 开启事务失败：{e}");
                return 0;
            }
        };
        let mut n = 0usize;
        {
            let mut st = match tx.prepare(
                "INSERT INTO screen(device_id, start, end) VALUES(?1, ?2, ?3)
                 ON CONFLICT(device_id, start) DO UPDATE SET end = MAX(screen.end, excluded.end)",
            ) {
                Ok(s) => s,
                Err(_) => return 0,
            };
            for s in segs {
                if st.execute(params![device_id, s.start, s.end]).is_ok() {
                    n += 1;
                }
            }
        }
        if let Err(e) = tx.commit() {
            eprintln!("[存储] 提交亮屏区间失败：{e}");
            return 0;
        }
        n
    }

    /// 取某个时间范围内的亮屏区间（已按 start 排序，未裁剪到边界）
    pub fn screen_between(&self, device_id: &str, from: f64, to: f64) -> Vec<PresenceSeg> {
        let c = self.c();
        let mut st = match c.prepare(
            "SELECT start, end FROM screen
             WHERE device_id = ?1 AND end >= ?2 AND start < ?3
             ORDER BY start",
        ) {
            Ok(s) => s,
            Err(_) => return Vec::new(),
        };
        let rows = st.query_map(params![device_id, from, to], |r| {
            Ok(PresenceSeg {
                start: r.get(0)?,
                end: r.get(1)?,
            })
        });
        match rows {
            Ok(it) => it.filter_map(|r| r.ok()).collect(),
            Err(_) => Vec::new(),
        }
    }

    /// 这台设备有没有上报过亮屏区间（老版本 App 没有，前端据此决定画不画那条线）
    pub fn has_screen(&self, device_id: &str) -> bool {
        self.c()
            .query_row(
                "SELECT 1 FROM screen WHERE device_id = ?1 LIMIT 1",
                params![device_id],
                |_| Ok(()),
            )
            .optional()
            .unwrap_or(None)
            .is_some()
    }

    // ==================================================================
    // 每日设备状态（解锁 / 开关机 / 亮屏时长 / 系统口径）
    // ==================================================================

    /// 写入当天的设备状态计数。
    ///
    /// 全部按"取最大值"合并：这些计数在一天内只会增加，设备重复上报同一天
    /// （每次全量重扫都会重报一遍）不会把它们翻倍。
    pub fn upsert_daystat(
        &self,
        device_id: &str,
        day: &str,
        unlock_cnt: i64,
        boot_cnt: i64,
        off_cnt: i64,
        screen_ms: i64,
        sys_ms: i64,
    ) {
        let _ = self.c().execute(
            "INSERT INTO daystat(device_id, day, unlock_cnt, boot_cnt, off_cnt, screen_ms, sys_ms)
             VALUES(?1, ?2, ?3, ?4, ?5, ?6, ?7)
             ON CONFLICT(device_id, day) DO UPDATE SET
                unlock_cnt = MAX(daystat.unlock_cnt, excluded.unlock_cnt),
                boot_cnt   = MAX(daystat.boot_cnt,   excluded.boot_cnt),
                off_cnt    = MAX(daystat.off_cnt,    excluded.off_cnt),
                screen_ms  = MAX(daystat.screen_ms,  excluded.screen_ms),
                sys_ms     = MAX(daystat.sys_ms,     excluded.sys_ms)",
            params![device_id, day, unlock_cnt, boot_cnt, off_cnt, screen_ms, sys_ms],
        );
    }

    /// 取某一天的设备状态；没有记录时返回全 0（老版本 App 就是这种情况）
    pub fn daystat_get(&self, device_id: &str, day: &str) -> (i64, i64, i64, i64, i64) {
        self.c()
            .query_row(
                "SELECT unlock_cnt, boot_cnt, off_cnt, screen_ms, sys_ms
                 FROM daystat WHERE device_id = ?1 AND day = ?2",
                params![device_id, day],
                |r| {
                    Ok((
                        r.get(0)?,
                        r.get(1)?,
                        r.get(2)?,
                        r.get(3)?,
                        r.get(4)?,
                    ))
                },
            )
            .optional()
            .unwrap_or(None)
            .unwrap_or((0, 0, 0, 0, 0))
    }

    /// 近 N 天的设备状态（趋势/汇总用）
    pub fn daystat_of(&self, device_id: &str, limit: i64) -> Vec<(String, i64, i64, i64, i64, i64)> {
        let c = self.c();
        let mut st = match c.prepare(
            "SELECT day, unlock_cnt, boot_cnt, off_cnt, screen_ms, sys_ms FROM daystat
             WHERE device_id = ?1 ORDER BY day DESC LIMIT ?2",
        ) {
            Ok(s) => s,
            Err(_) => return Vec::new(),
        };
        let rows = st.query_map(params![device_id, limit], |r| {
            Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?, r.get(4)?, r.get(5)?))
        });
        match rows {
            Ok(it) => it.filter_map(|r| r.ok()).collect(),
            Err(_) => Vec::new(),
        }
    }

    // ==================================================================
    // 维护
    // ==================================================================

    /// 删掉过期的流水数据。调用频率不用高 —— 每小时一次足够。
    pub fn prune(&self, now: f64) -> (usize, usize, usize) {
        let c = self.c();
        let ev = now - EVENT_KEEP_DAYS * 86400.0;
        let tl = now - TIMELINE_KEEP_DAYS * 86400.0;
        let a = c
            .execute("DELETE FROM events WHERE ts < ?1", params![ev])
            .unwrap_or(0);
        let b = c
            .execute("DELETE FROM presence WHERE end < ?1", params![tl])
            .unwrap_or(0);
        let d = c
            .execute("DELETE FROM usage WHERE end < ?1", params![tl])
            .unwrap_or(0);
        // 亮屏区间和上面同寿命（都是时间线数据），一起清
        let _ = c.execute("DELETE FROM screen WHERE end < ?1", params![tl]);
        (a, b, d)
    }

    /// 设备被移除时，把它名下的流水一起删干净
    pub fn delete_device_data(&self, device_id: &str) {
        let c = self.c();
        let _ = c.execute("DELETE FROM presence WHERE device_id = ?1", params![device_id]);
        let _ = c.execute("DELETE FROM usage WHERE device_id = ?1", params![device_id]);
        let _ = c.execute("DELETE FROM daily WHERE device_id = ?1", params![device_id]);
        let _ = c.execute("DELETE FROM screen WHERE device_id = ?1", params![device_id]);
        let _ = c.execute("DELETE FROM daystat WHERE device_id = ?1", params![device_id]);
    }

    /// 统计信息（登录后右下角/诊断用）
    pub fn stats(&self) -> (i64, i64, i64) {
        let c = self.c();
        let q = |sql: &str| -> i64 { c.query_row(sql, [], |r| r.get(0)).unwrap_or(0) };
        (
            q("SELECT COUNT(*) FROM events"),
            q("SELECT COUNT(*) FROM usage"),
            q("SELECT COUNT(*) FROM presence"),
        )
    }

    /// 显式开启事务执行一批写操作（迁移用）
    pub fn with_tx<F: FnOnce(&rusqlite::Transaction) -> rusqlite::Result<()>>(&self, f: F) -> bool {
        let mut c = self.c();
        let ok = match c.transaction() {
            Ok(tx) => match f(&tx) {
                Ok(()) => tx.commit().is_ok(),
                Err(e) => {
                    eprintln!("[存储] 事务失败：{e}");
                    false
                }
            },
            Err(e) => {
                eprintln!("[存储] 开启事务失败：{e}");
                false
            }
        };
        ok
    }
}
