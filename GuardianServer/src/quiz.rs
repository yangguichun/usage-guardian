//! 答题解锁（v2.6.0）的题库：存储、导入解析、给设备下发。
//!
//! # 为什么单独一个模块
//!
//! 题库是**配置数据**，不是流水：要能逐行改、要能整批导入、要按键值查。
//! 塞进 `store.rs` 的 `kv` JSON 里会让"改一道题"变成"重写整个题库"，
//! 所以用 `db.rs` 里的两张真表；读写逻辑留在这里，`handlers.rs` 只管鉴权和 JSON。
//!
//! # 题目格式
//!
//! 题型只有两种，都用一个 `answer` 字段收口，设备端不需要理解学科：
//!
//! * `choice` —— 选择题。`options` 是候选，`answer` 必须**等于其中一个选项的文本**
//!   （不是下标）。存文本的好处是导入时人眼可读，改选项顺序也不会把答案改错。
//! * `spell`  —— 补全单词。`tpl` 是带空位的模板（`ap__le`），`answer` 是完整单词，
//!   空位数量与 `answer` 中对应位置的字母逐个比对。
//!
//! 学科（subject）只影响设备端显示的标签和题库分类，不参与判题逻辑 ——
//! 以后要加"科学""古诗"都不用改客户端。

use rusqlite::{params, params_from_iter, OptionalExtension};
use serde_json::{json, Value};

use crate::db::Db;
use crate::util::now_ts;

/// 一套题库最多多少题。家长批量导入时手一抖可能贴进来几万行，
/// 这里兜一下，避免一道 SQL 写太久把心跳卡住。
pub const MAX_QUESTIONS_PER_BANK: usize = 3000;
/// 一次导入最多处理多少行
pub const MAX_IMPORT_LINES: usize = 3000;
/// 设备一次最多拉多少题。太小会让孩子很快刷完见重复，太大浪费流量。
pub const DEVICE_LIMIT_MAX: i64 = 500;

pub const SUBJECTS: &[(&str, &str)] = &[
    ("chinese", "语文"),
    ("math", "数学"),
    ("english", "英语"),
    ("mixed", "综合"),
];

pub fn subject_label(s: &str) -> &'static str {
    SUBJECTS
        .iter()
        .find(|(k, _)| *k == s)
        .map(|(_, v)| *v)
        .unwrap_or("综合")
}

pub fn valid_subject(s: &str) -> bool {
    SUBJECTS.iter().any(|(k, _)| *k == s)
}

#[derive(Clone)]
pub struct Bank {
    pub id: i64,
    pub owner_id: String,
    pub name: String,
    pub subject: String,
    pub note: String,
    pub enabled: bool,
    pub is_seed: bool,
    pub created_at: f64,
    pub updated_at: f64,
    pub count: i64,
}

impl Bank {
    pub fn json(&self) -> Value {
        json!({
            "id": self.id,
            "name": self.name,
            "subject": self.subject,
            "subject_label": subject_label(&self.subject),
            "note": self.note,
            "enabled": self.enabled,
            "is_seed": self.is_seed,
            "count": self.count,
            "builtin": self.owner_id.is_empty(),
            "created_at": self.created_at,
            "updated_at": self.updated_at,
        })
    }
}

#[derive(Clone, Default)]
pub struct Question {
    pub id: i64,
    pub bank_id: i64,
    pub subject: String,
    /// choice / spell
    pub qtype: String,
    pub stem: String,
    pub options: Vec<String>,
    pub answer: String,
    pub tpl: String,
    pub hint: String,
    pub level: i64,
    pub enabled: bool,
}

impl Question {
    /// 后台列表用（字段名好读）
    pub fn json(&self) -> Value {
        json!({
            "id": self.id,
            "bank_id": self.bank_id,
            "subject": self.subject,
            "type": self.qtype,
            "stem": self.stem,
            "options": self.options,
            "answer": self.answer,
            "tpl": self.tpl,
            "hint": self.hint,
            "level": self.level,
            "enabled": self.enabled,
        })
    }

    /// 下发设备用（字段名短，一条几十字节；几百题也不大）
    pub fn device_json(&self) -> Value {
        json!({
            "id": self.id,
            "s": self.subject,
            "t": self.qtype,
            "q": self.stem,
            "o": self.options,
            "a": self.answer,
            "w": self.tpl,
            "h": self.hint,
            "lv": self.level,
        })
    }

    /// 结构是否自洽。导入和 API 编辑共用这一套校验，
    /// 免得"网页上能存进去、设备上答不出来"。
    pub fn validate(&self) -> Result<(), String> {
        if self.stem.trim().is_empty() {
            return Err("题干不能为空".into());
        }
        if self.stem.chars().count() > 300 {
            return Err("题干太长（最多 300 字）".into());
        }
        match self.qtype.as_str() {
            "choice" => {
                if self.options.len() < 2 {
                    return Err("选择题至少要有 2 个选项".into());
                }
                if self.options.len() > 6 {
                    return Err("选项最多 6 个".into());
                }
                if self.answer.trim().is_empty() {
                    return Err("缺少正确答案".into());
                }
                if !self.options.iter().any(|o| o.trim() == self.answer.trim()) {
                    return Err(format!(
                        "正确答案「{}」不在选项里（选项：{}）",
                        self.answer,
                        self.options.join(" / ")
                    ));
                }
                let uniq: std::collections::BTreeSet<&str> =
                    self.options.iter().map(|s| s.trim()).collect();
                if uniq.len() != self.options.len() {
                    return Err("选项里有重复项".into());
                }
            }
            "spell" => {
                if !self.tpl.contains('_') {
                    return Err("补全单词的模板里要用下划线 _ 标出空缺字母".into());
                }
                if self.tpl.chars().count() != self.answer.chars().count() {
                    return Err("模板长度要和答案一致（一个 _ 对应一个字母）".into());
                }
                if self.answer.trim().is_empty() || self.answer.contains('_') {
                    return Err("答案不能为空，也不能包含下划线".into());
                }
            }
            other => return Err(format!("不支持的题型：{other}（只能是 choice / spell）")),
        }
        Ok(())
    }
}

fn row_to_bank(r: &rusqlite::Row) -> rusqlite::Result<Bank> {
    Ok(Bank {
        id: r.get(0)?,
        owner_id: r.get(1)?,
        name: r.get(2)?,
        subject: r.get(3)?,
        note: r.get(4)?,
        enabled: r.get::<_, i64>(5)? != 0,
        is_seed: r.get::<_, i64>(6)? != 0,
        created_at: r.get(7)?,
        updated_at: r.get(8)?,
        count: r.get(9)?,
    })
}

const BANK_COLS: &str = "b.id, b.owner_id, b.name, b.subject, b.note, b.enabled, b.is_seed, \
                         b.created_at, b.updated_at, (SELECT COUNT(*) FROM quiz_question q \
                         WHERE q.bank_id = b.id AND q.enabled = 1)";

/// 列出题库。[owner] 为 None 表示不过滤（管理员）；
/// 普通账号看到的是「自己的」+「内置的」。
pub fn banks(db: &Db, owner: Option<&str>) -> Vec<Bank> {
    let c = db.c();
    let sql = format!(
        "SELECT {BANK_COLS} FROM quiz_bank b {} ORDER BY b.is_seed DESC, b.id ASC",
        if owner.is_some() { "WHERE b.owner_id = ?1 OR b.owner_id = ''" } else { "" }
    );
    let map = |r: &rusqlite::Row| row_to_bank(r);
    let out: Vec<Bank> = match owner {
        Some(o) => {
            let mut st = match c.prepare(&sql) {
                Ok(s) => s,
                Err(_) => return Vec::new(),
            };
            // 先落到局部变量：直接当块尾表达式时，Result 临时值会活到块结束，
            // 比 st / c 更晚析构，借用检查会报 does not live long enough。
            let mut rows: Vec<Bank> = Vec::new();
            if let Ok(it) = st.query_map(params![o], map) {
                rows = it.filter_map(|x| x.ok()).collect();
            }
            rows
        }
        None => {
            let mut st = match c.prepare(&sql) {
                Ok(s) => s,
                Err(_) => return Vec::new(),
            };
            let mut rows: Vec<Bank> = Vec::new();
            if let Ok(it) = st.query_map([], map) {
                rows = it.filter_map(|x| x.ok()).collect();
            }
            rows
        }
    };
    out
}

pub fn bank_get(db: &Db, id: i64) -> Option<Bank> {
    let c = db.c();
    let sql = format!("SELECT {BANK_COLS} FROM quiz_bank b WHERE b.id = ?1");
    c.query_row(&sql, params![id], |r| row_to_bank(r))
        .optional()
        .ok()
        .flatten()
}

/// 建题库，返回新 id。`owner_id` 空串 = 内置题库。
pub fn bank_create(
    db: &Db,
    owner_id: &str,
    name: &str,
    subject: &str,
    note: &str,
    is_seed: bool,
) -> Option<i64> {
    let c = db.c();
    let now = now_ts();
    c.execute(
        "INSERT INTO quiz_bank(owner_id, name, subject, note, enabled, is_seed, created_at, updated_at)
         VALUES(?1, ?2, ?3, ?4, 1, ?5, ?6, ?6)",
        params![owner_id, name, subject, note, is_seed as i64, now],
    )
    .ok()?;
    Some(c.last_insert_rowid())
}

pub fn bank_update(
    db: &Db,
    id: i64,
    name: &str,
    subject: &str,
    note: &str,
    enabled: bool,
) -> bool {
    let c = db.c();
    c.execute(
        "UPDATE quiz_bank SET name = ?2, subject = ?3, note = ?4, enabled = ?5, updated_at = ?6
         WHERE id = ?1",
        params![id, name, subject, note, enabled as i64, now_ts()],
    )
    .map(|n| n > 0)
    .unwrap_or(false)
}

pub fn bank_touch(db: &Db, id: i64) {
    let c = db.c();
    let _ = c.execute(
        "UPDATE quiz_bank SET updated_at = ?2 WHERE id = ?1",
        params![id, now_ts()],
    );
}

pub fn bank_delete(db: &Db, id: i64) -> bool {
    let c = db.c();
    let _ = c.execute("DELETE FROM quiz_question WHERE bank_id = ?1", params![id]);
    c.execute("DELETE FROM quiz_bank WHERE id = ?1", params![id])
        .map(|n| n > 0)
        .unwrap_or(false)
}

fn row_to_q(r: &rusqlite::Row) -> rusqlite::Result<Question> {
    let opts_raw: String = r.get(5)?;
    Ok(Question {
        id: r.get(0)?,
        bank_id: r.get(1)?,
        subject: r.get(2)?,
        qtype: r.get(3)?,
        stem: r.get(4)?,
        options: serde_json::from_str(&opts_raw).unwrap_or_default(),
        answer: r.get(6)?,
        tpl: r.get(7)?,
        hint: r.get(8)?,
        level: r.get(9)?,
        enabled: r.get::<_, i64>(10)? != 0,
    })
}

const Q_COLS: &str =
    "id, bank_id, subject, qtype, stem, options, answer, tpl, hint, level, enabled";

/// 题库里的题（后台编辑用）。[kw] 非空时按题干模糊匹配。
pub fn questions(db: &Db, bank_id: i64, kw: &str, limit: i64, offset: i64) -> Vec<Question> {
    let c = db.c();
    let kw = kw.trim();
    let (sql, args): (String, Vec<rusqlite::types::Value>) = if kw.is_empty() {
        (
            format!("SELECT {Q_COLS} FROM quiz_question WHERE bank_id = ?1 ORDER BY id DESC LIMIT ?2 OFFSET ?3"),
            vec![bank_id.into(), limit.into(), offset.into()],
        )
    } else {
        (
            format!(
                "SELECT {Q_COLS} FROM quiz_question WHERE bank_id = ?1 AND stem LIKE ?2 \
                 ORDER BY id DESC LIMIT ?3 OFFSET ?4"
            ),
            vec![bank_id.into(), format!("%{kw}%").into(), limit.into(), offset.into()],
        )
    };
    let mut st = match c.prepare(&sql) {
        Ok(s) => s,
        Err(_) => return Vec::new(),
    };
    let mut rows: Vec<Question> = Vec::new();
    if let Ok(it) = st.query_map(params_from_iter(args), |r| row_to_q(r)) {
        rows = it.filter_map(|x| x.ok()).collect();
    }
    rows
}

pub fn question_count(db: &Db, bank_id: i64) -> i64 {
    let c = db.c();
    c.query_row(
        "SELECT COUNT(*) FROM quiz_question WHERE bank_id = ?1",
        params![bank_id],
        |r| r.get(0),
    )
    .unwrap_or(0)
}

pub fn question_get(db: &Db, id: i64) -> Option<Question> {
    let c = db.c();
    let sql = format!("SELECT {Q_COLS} FROM quiz_question WHERE id = ?1");
    c.query_row(&sql, params![id], |r| row_to_q(r))
        .optional()
        .ok()
        .flatten()
}

pub fn question_add(db: &Db, bank_id: i64, q: &Question) -> Option<i64> {
    let c = db.c();
    let now = now_ts();
    c.execute(
        "INSERT INTO quiz_question(bank_id, subject, qtype, stem, options, answer, tpl, hint,
                                   level, enabled, created_at, updated_at)
         VALUES(?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?11)",
        params![
            bank_id,
            q.subject,
            q.qtype,
            q.stem,
            serde_json::to_string(&q.options).unwrap_or_else(|_| "[]".into()),
            q.answer,
            q.tpl,
            q.hint,
            q.level,
            q.enabled as i64,
            now
        ],
    )
    .ok()?;
    Some(c.last_insert_rowid())
}

pub fn question_update(db: &Db, id: i64, q: &Question) -> bool {
    let c = db.c();
    c.execute(
        "UPDATE quiz_question SET subject = ?2, qtype = ?3, stem = ?4, options = ?5,
                answer = ?6, tpl = ?7, hint = ?8, level = ?9, enabled = ?10, updated_at = ?11
         WHERE id = ?1",
        params![
            id,
            q.subject,
            q.qtype,
            q.stem,
            serde_json::to_string(&q.options).unwrap_or_else(|_| "[]".into()),
            q.answer,
            q.tpl,
            q.hint,
            q.level,
            q.enabled as i64,
            now_ts()
        ],
    )
    .map(|n| n > 0)
    .unwrap_or(false)
}

pub fn question_delete(db: &Db, id: i64) -> bool {
    let c = db.c();
    c.execute("DELETE FROM quiz_question WHERE id = ?1", params![id])
        .map(|n| n > 0)
        .unwrap_or(false)
}

/// 批量写入（导入用）。整批一个事务，失败一条不影响其它。
pub fn bulk_add(db: &Db, bank_id: i64, items: &[Question]) -> usize {
    if items.is_empty() {
        return 0;
    }
    let mut c = db.c();
    let now = now_ts();
    let tx = match c.transaction() {
        Ok(t) => t,
        Err(_) => return 0,
    };
    let mut n = 0usize;
    for q in items {
        let r = tx.execute(
            "INSERT INTO quiz_question(bank_id, subject, qtype, stem, options, answer, tpl, hint,
                                       level, enabled, created_at, updated_at)
             VALUES(?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?11)",
            params![
                bank_id,
                q.subject,
                q.qtype,
                q.stem,
                serde_json::to_string(&q.options).unwrap_or_else(|_| "[]".into()),
                q.answer,
                q.tpl,
                q.hint,
                q.level,
                q.enabled as i64,
                now
            ],
        );
        if r.is_ok() {
            n += 1;
        }
    }
    let _ = tx.commit();
    n
}

// ==================================================================
// 给设备下发
// ==================================================================

/// 按题库 id 集合取题（只取启用的），随机打散后取前 [limit] 条。
///
/// 为什么在服务端打散而不是设备端：设备缓存的是一份"题池"，
/// 每次心跳都会重下（版本变了才下），服务端换个顺序就等于给了一批新题，
/// 设备不用存"用过哪些"的长列表。
pub fn pick_for_banks(db: &Db, bank_ids: &[i64], limit: i64) -> Vec<Question> {
    if bank_ids.is_empty() {
        return Vec::new();
    }
    let c = db.c();
    let holders = (1..=bank_ids.len())
        .map(|i| format!("?{i}"))
        .collect::<Vec<_>>()
        .join(",");
    let sql = format!(
        "SELECT {Q_COLS} FROM quiz_question
         WHERE bank_id IN ({holders}) AND enabled = 1
         ORDER BY (id * 2654435761 + {}) % 100003 LIMIT {}",
        now_ts() as i64,
        limit.clamp(1, DEVICE_LIMIT_MAX)
    );
    let mut st = match c.prepare(&sql) {
        Ok(s) => s,
        Err(_) => return Vec::new(),
    };
    let args: Vec<rusqlite::types::Value> = bank_ids.iter().map(|i| (*i).into()).collect();
    let mut rows: Vec<Question> = Vec::new();
    if let Ok(it) = st.query_map(params_from_iter(args), |r| row_to_q(r)) {
        rows = it.filter_map(|x| x.ok()).collect();
    }
    rows
}

/// 题库版本号：内容一变（增删题、改题、开关题库）就要变，设备据此决定重不重下。
///
/// 用"题数 + 最大 updated_at + 最大 id"而不是哈希整个内容：
/// 后者要读全部行，几百题也还好，但这个够用且是 O(1)。
pub fn version(db: &Db, bank_ids: &[i64]) -> String {
    if bank_ids.is_empty() {
        return "0".into();
    }
    let c = db.c();
    let holders = (1..=bank_ids.len())
        .map(|i| format!("?{i}"))
        .collect::<Vec<_>>()
        .join(",");
    let sql = format!(
        "SELECT COUNT(*), COALESCE(MAX(updated_at), 0), COALESCE(MAX(id), 0)
         FROM quiz_question WHERE bank_id IN ({holders}) AND enabled = 1"
    );
    let args: Vec<rusqlite::types::Value> = bank_ids.iter().map(|i| (*i).into()).collect();
    let row: rusqlite::Result<(i64, f64, i64)> = c.query_row(
        &sql,
        params_from_iter(args),
        |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
    );
    match row {
        Ok((n, ts, max_id)) => {
            let ids = bank_ids
                .iter()
                .map(|i| i.to_string())
                .collect::<Vec<_>>()
                .join("-");
            format!("{n}.{}.{max_id}.{ids}", ts as i64)
        }
        Err(_) => "0".into(),
    }
}

// ==================================================================
// 导入解析
// ==================================================================

fn split_fields(line: &str) -> Vec<String> {
    // 全角竖线和制表符都当分隔符：家长从 Excel 复制过来常带这两种。
    // ★ 必须先绑定成变量再 split —— 直接链式 split 会借用一个临时 String，
    //   编译器会报 "temporary value dropped while borrowed"。
    let norm = line.replace('｜', "|").replace('\t', "|");
    norm.split('|').map(|s| s.trim().to_string()).collect()
}

/// 解析文本导入。
///
/// 支持三种写法（`#` 开头的行忽略，空行忽略）：
///
/// ```text
/// 题干|选项A|选项B|选项C|选项D|正确答案      → 选择题
/// S|题干|模板（_ 表示空缺）|完整答案|提示      → 补全单词
/// [{"stem":"...","options":[...],"answer":"..."}]  → JSON 数组（整段）
/// ```
pub fn parse_import(
    text: &str,
    bank_subject: &str,
    default_level: i64,
) -> (Vec<Question>, Vec<String>) {
    let mut out = Vec::new();
    let mut errs = Vec::new();

    let trimmed = text.trim();
    // 整段 JSON
    if trimmed.starts_with('[') {
        match serde_json::from_str::<Vec<Value>>(trimmed) {
            Ok(arr) => {
                for (i, v) in arr.iter().enumerate() {
                    if out.len() >= MAX_QUESTIONS_PER_BANK {
                        errs.push(format!("超过单题库上限 {MAX_QUESTIONS_PER_BANK} 题，后面的忽略了"));
                        break;
                    }
                    match q_from_json(v, bank_subject, default_level) {
                        Ok(q) => out.push(q),
                        Err(e) => errs.push(format!("第 {} 条：{e}", i + 1)),
                    }
                }
                return (out, errs);
            }
            Err(e) => {
                return (Vec::new(), vec![format!("JSON 解析失败：{e}")]);
            }
        }
    }

    for (i, raw) in text.lines().enumerate() {
        let line = raw.trim();
        if line.is_empty() || line.starts_with('#') || line.starts_with("//") {
            continue;
        }
        if out.len() >= MAX_QUESTIONS_PER_BANK || out.len() >= MAX_IMPORT_LINES {
            errs.push(format!("超过单题库上限，第 {} 行之后的内容忽略了", i + 1));
            break;
        }
        let f = split_fields(line);
        let subject = if bank_subject == "mixed" {
            "mixed".to_string()
        } else {
            bank_subject.to_string()
        };

        // S|题干|模板|答案|提示
        if f.len() >= 4 && f[0].eq_ignore_ascii_case("s") {
            let q = Question {
                subject,
                qtype: "spell".into(),
                stem: f[1].clone(),
                tpl: f[2].clone(),
                answer: f[3].clone(),
                hint: f.get(4).cloned().unwrap_or_default(),
                options: Vec::new(),
                level: default_level,
                enabled: true,
                ..Default::default()
            };
            match q.validate() {
                Ok(()) => out.push(q),
                Err(e) => errs.push(format!("第 {} 行：{e}", i + 1)),
            }
            continue;
        }

        // 题干|A|B|C|D|答案
        if f.len() >= 4 {
            let answer = f[f.len() - 1].clone();
            let stem = f[0].clone();
            let options: Vec<String> = f[1..f.len() - 1]
                .iter()
                .filter(|s| !s.is_empty())
                .cloned()
                .collect();
            let q = Question {
                subject,
                qtype: "choice".into(),
                stem,
                options,
                answer,
                level: default_level,
                enabled: true,
                ..Default::default()
            };
            match q.validate() {
                Ok(()) => out.push(q),
                Err(e) => errs.push(format!("第 {} 行：{e}", i + 1)),
            }
            continue;
        }
        errs.push(format!(
            "第 {} 行：字段太少（选择题至少要「题干|选项|选项|答案」）",
            i + 1
        ));
    }
    (out, errs)
}

fn q_from_json(v: &Value, bank_subject: &str, default_level: i64) -> Result<Question, String> {
    let stem = v
        .get("stem")
        .and_then(|x| x.as_str())
        .ok_or("缺少 stem（题干）")?
        .trim()
        .to_string();
    let qtype = v
        .get("type")
        .and_then(|x| x.as_str())
        .filter(|s| !s.is_empty())
        .unwrap_or("choice")
        .to_string();
    let subject = v
        .get("subject")
        .and_then(|x| x.as_str())
        .filter(|s| valid_subject(s))
        .unwrap_or(bank_subject)
        .to_string();
    let options: Vec<String> = v
        .get("options")
        .and_then(|x| x.as_array())
        .map(|a| {
            a.iter()
                .filter_map(|x| x.as_str())
                .map(|s| s.trim().to_string())
                .collect()
        })
        .unwrap_or_default();
    let q = Question {
        subject,
        qtype,
        stem,
        options,
        answer: v
            .get("answer")
            .and_then(|x| x.as_str())
            .unwrap_or("")
            .trim()
            .to_string(),
        tpl: v
            .get("tpl")
            .and_then(|x| x.as_str())
            .unwrap_or("")
            .trim()
            .to_string(),
        hint: v
            .get("hint")
            .and_then(|x| x.as_str())
            .unwrap_or("")
            .chars()
            .take(200)
            .collect(),
        level: v
            .get("level")
            .and_then(|x| x.as_i64())
            .unwrap_or(default_level)
            .clamp(1, 9),
        enabled: true,
        ..Default::default()
    };
    q.validate()?;
    Ok(q)
}

// ==================================================================
// 内置题库
// ==================================================================

const SEED_JSON: &str = include_str!("../static/quiz_seed.json");

/// 首次启动时导入内置题库（只在一条内置题库都没有时执行）。
///
/// 为什么不放进部署脚本：家长可能是自己下载二进制在树莓派上跑的，
/// 多一步手动操作就多一个"装了但没题"的坑。这个函数本身是幂等的。
pub fn ensure_seed(db: &Db) -> Option<usize> {
    {
        let c = db.c();
        let n: i64 = c
            .query_row(
                "SELECT COUNT(*) FROM quiz_bank WHERE is_seed = 1",
                [],
                |r| r.get(0),
            )
            .unwrap_or(0);
        if n > 0 {
            return None;
        }
    }
    let parsed: Value = serde_json::from_str(SEED_JSON).ok()?;
    let arr = parsed.get("banks")?.as_array()?;
    let mut total = 0usize;
    for b in arr {
        let name = b.get("name").and_then(|x| x.as_str()).unwrap_or("内置题库");
        let subject = b
            .get("subject")
            .and_then(|x| x.as_str())
            .filter(|s| valid_subject(s))
            .unwrap_or("mixed");
        let note = b.get("description").and_then(|x| x.as_str()).unwrap_or("");
        let id = bank_create(db, "", name, subject, note, true)?;
        let mut items = Vec::new();
        if let Some(qs) = b.get("questions").and_then(|x| x.as_array()) {
            for v in qs {
                if let Ok(q) = q_from_json(v, subject, 1) {
                    items.push(q);
                }
            }
        }
        total += bulk_add(db, id, &items);
    }
    Some(total)
}

/// 给诊断/统计用：内置题库有几套、几道题
pub fn stats(db: &Db) -> (i64, i64) {
    let c = db.c();
    let banks: i64 = c
        .query_row("SELECT COUNT(*) FROM quiz_bank", [], |r| r.get(0))
        .unwrap_or(0);
    let qs: i64 = c
        .query_row("SELECT COUNT(*) FROM quiz_question", [], |r| r.get(0))
        .unwrap_or(0);
    (banks, qs)
}
