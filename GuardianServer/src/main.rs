//! 「使用守护」管理后台 · Rust 版
//!
//! 设计目标：
//!   1. **单个静态二进制**：Linux x86_64 musl 静态链接，服务器上什么都不用装
//!      （SQLite 也一起编进去了，不需要在服务器上装 sqlite3）；
//!   2. **零外部文件**：前端页面（html/css/js）编进二进制，不依赖当前目录；
//!   3. **可被反复启动**：由 run.sh 负责 stop→start，服务本身收到 SIGTERM 会保存数据后退出。
//!
//! 环境变量（都可省略）：
//!   GUARDIAN_ADMIN_PASSWORD  后台登录口令（不设则用 guardian2026，启动会警告）
//!   GUARDIAN_ADDR            监听地址，默认 0.0.0.0:8000
//!   GUARDIAN_PORT            只给端口也行（配合 GUARDIAN_HOST，默认 0.0.0.0）
//!   GUARDIAN_DATA            SQLite 文件路径，默认 ./data/guardian.db
//!   GUARDIAN_TZ_OFFSET       统计"今天"用的时区偏移小时数，默认 8（北京时间）
//!   GUARDIAN_SESSION_DAYS    登录有效期天数，默认 7
//!
//! 子命令：
//!   guardian-server migrate [--data <路径>]   只做一次 JSON → SQLite 迁移然后退出

mod db;
mod handlers;
mod quiz;
mod store;
mod util;

use std::collections::HashMap;
use std::net::SocketAddr;
use std::path::PathBuf;
use std::sync::{Arc, Mutex, MutexGuard};

use axum::routing::{get, post};
use axum::Router;

use store::Store;

pub struct Config {
    pub admin_password: String,
    pub using_default_password: bool,
    pub tz_offset_hours: f64,
    pub session_days: i64,
    pub addr: String,
}

pub struct AppState {
    /// 小数据常驻内存；事件/时间线在 SQLite 里按需查询（见 `db.rs`）
    pub store: Mutex<Store>,
    /// SQLite 文件路径（诊断、部署脚本用）
    pub data_path: PathBuf,
    pub cfg: Config,
    /// 登录失败计数（进程内存，重启清零无所谓）
    pub login_fails: Mutex<HashMap<String, Vec<f64>>>,
}

impl AppState {
    /// 拿数据锁；即使别处 panic 过也照样能用，避免一个请求出错把服务锁死
    pub fn lock(&self) -> MutexGuard<'_, Store> {
        self.store.lock().unwrap_or_else(|e| e.into_inner())
    }
}

fn env_or(name: &str, default: &str) -> String {
    std::env::var(name)
        .ok()
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty())
        .unwrap_or_else(|| default.to_string())
}

impl Config {
    fn from_env() -> Config {
        // 口令：环境变量 > config.env（由 run.sh 注入）> 默认值
        let given = std::env::var("GUARDIAN_ADMIN_PASSWORD")
            .ok()
            .map(|s| s.trim().to_string())
            .filter(|s| !s.is_empty());
        let using_default_password = match &given {
            Some(p) => p == "guardian2026",
            None => true,
        };
        let admin_password = given.unwrap_or_else(|| "guardian2026".to_string());

        let addr = std::env::var("GUARDIAN_ADDR")
            .ok()
            .map(|s| s.trim().to_string())
            .filter(|s| !s.is_empty())
            .unwrap_or_else(|| {
                let host = env_or("GUARDIAN_HOST", "0.0.0.0");
                let port = env_or("GUARDIAN_PORT", "8000");
                format!("{host}:{port}")
            });

        let tz_offset_hours = std::env::var("GUARDIAN_TZ_OFFSET")
            .ok()
            .and_then(|s| s.trim().parse::<f64>().ok())
            .unwrap_or(8.0);
        let session_days = std::env::var("GUARDIAN_SESSION_DAYS")
            .ok()
            .and_then(|s| s.trim().parse::<i64>().ok())
            .unwrap_or(7)
            .clamp(1, 365);

        Config {
            admin_password,
            using_default_password,
            tz_offset_hours,
            session_days,
            addr,
        }
    }
}

fn banner(cfg: &Config, data_path: &std::path::Path) {
    let line = "=".repeat(64);
    println!("{line}");
    println!("  使用守护 · 管理后台（Rust） v{}", env!("CARGO_PKG_VERSION"));
    println!("  监听地址：http://{}", cfg.addr);
    println!("  数据库：  {}", data_path.display());
    let tz = if cfg.tz_offset_hours.fract() == 0.0 {
        format!("{}", cfg.tz_offset_hours as i64)
    } else {
        format!("{}", cfg.tz_offset_hours)
    };
    println!(
        "  统计时区：UTC{}{}（用于算“今天”）",
        if cfg.tz_offset_hours >= 0.0 { "+" } else { "" },
        tz
    );
    if cfg.using_default_password {
        println!("  ⚠ 正在使用默认口令 guardian2026，公网部署前务必改掉！");
        println!("    （编辑同目录的 config.env，或在设备/后台里改）");
    }
    println!("{line}");
    println!("  按 Ctrl+C 退出；由 run.sh 启动时请用 run.sh stop 停止");
    println!();
}

async fn shutdown_signal(state: Arc<AppState>) {
    let ctrl_c = async {
        let _ = tokio::signal::ctrl_c().await;
    };

    #[cfg(unix)]
    let term = async {
        use tokio::signal::unix::{signal, SignalKind};
        match signal(SignalKind::terminate()) {
            Ok(mut s) => {
                s.recv().await;
            }
            Err(_) => std::future::pending::<()>().await,
        }
    };
    #[cfg(not(unix))]
    let term = std::future::pending::<()>();

    tokio::select! {
        _ = ctrl_c => {},
        _ = term => {},
    }
    println!("\n收到退出信号，正在保存数据…");
    let mut store = state.lock();
    store.save();
    println!("已保存，再见。");
}

/// 解析数据文件路径。
///
/// 兼容老配置：如果 `GUARDIAN_DATA` 还指向 `.json`（v2.1.x 及以前的写法），
/// 自动换成同目录的 `.db` —— 旧文件会被当成迁移源读一次，不会被覆盖。
fn resolve_data_path() -> PathBuf {
    let raw = env_or("GUARDIAN_DATA", "./data/guardian.db");
    let p = PathBuf::from(&raw);
    if p.extension().map(|e| e.eq_ignore_ascii_case("json")).unwrap_or(false) {
        let db = p.with_extension("db");
        println!(
            "[配置] GUARDIAN_DATA 指向的是 JSON（{}），已自动改用 {}",
            p.display(),
            db.display()
        );
        return db;
    }
    p
}

#[tokio::main]
async fn main() {
    let cfg = Config::from_env();
    let data_path = resolve_data_path();

    // ---- 子命令：只做迁移，不启动服务 ----
    let args: Vec<String> = std::env::args().skip(1).collect();
    if args.first().map(|s| s.as_str()) == Some("migrate") {
        println!("[迁移] 目标数据库：{}", data_path.display());
        let store = Store::open(&data_path);
        match &store.migrated_from {
            Some(p) => println!("[迁移] 已从 {} 导入完成。", p.display()),
            None => println!("[迁移] 没有发现需要迁移的 state.json，数据库已是最新。"),
        }
        let (evs, uses, pres) = store.db.stats();
        println!(
            "[迁移] 当前库：{} 台设备 · {} 个账号 · {} 条事件 · {} 条片段 · {} 段在线",
            store.devices.len(),
            store.users.len(),
            evs,
            uses,
            pres
        );
        return;
    }

    let mut store = Store::open(&data_path);
    // 首次启动（或从 v1.x 升级）时补上内置管理员账号。
    // 注意：口令只在**创建那一次**生效；之后改密码请在后台页面里改，环境变量不再覆盖。
    let created_admin = store.ensure_admin(util::hash_password(&cfg.admin_password));
    store.save();
    if created_admin {
        println!(
            "[账号] 已创建内置管理员 admin（初始口令：{}）",
            cfg.admin_password
        );
        println!("       登录后请立刻在右上角「账号」里改掉这个口令。");
    }

    // 导入 / 补齐内置题库（语文拼音 / 数学口算 / 牛津树词汇）。
    // 幂等：按题库名逐题面比对，只补题库里还没有的题，已有题库 id 不变。
    if let Some(n) = quiz::ensure_seed(&store.db) {
        let (b, q) = quiz::stats(&store.db);
        println!("[题库] 内置题库补齐 {n} 道题（当前共 {b} 套、{q} 道）");
    }
    // 去重：旧版随机抽题会撞题面，补题只能加不能删，所以单独清一遍。
    // 幂等，只在真删掉东西时打日志。
    let dup = quiz::dedupe_seed(&store.db);
    if dup > 0 {
        let (_, q) = quiz::stats(&store.db);
        println!("[题库] 清理重复题面 {dup} 条（当前共 {q} 道）");
    }

    let state = Arc::new(AppState {
        store: Mutex::new(store),
        data_path: data_path.clone(),
        cfg,
        login_fails: Mutex::new(HashMap::new()),
    });

    let app = Router::new()
        .route("/", get(handlers::index))
        .route("/index.html", get(handlers::index))
        .route("/healthz", get(handlers::healthz))
        .route("/static/app.css", get(handlers::static_css))
        .route("/static/app.js", get(handlers::static_js))
        .route("/favicon.ico", get(handlers::favicon))
        .route("/api/login", post(handlers::login))
        .route("/api/logout", post(handlers::logout))
        .route("/api/me", get(handlers::me))
        .route("/api/me/password", post(handlers::change_my_password))
        .route("/api/overview", get(handlers::overview))
        .route("/api/devices", get(handlers::list_devices))
        .route("/api/devices/{device_id}", get(handlers::device_detail))
        .route("/api/devices/{device_id}/timeline", get(handlers::device_timeline))
        .route("/api/devices/{device_id}/events", get(handlers::device_events))
        .route("/api/devices/{device_id}/quiet", get(handlers::device_quiet))
        .route("/api/devices/{device_id}/command", post(handlers::push_command))
        .route("/api/devices/{device_id}/delete", post(handlers::delete_device))
        .route("/api/events", get(handlers::list_events))
        // ---- 答题解锁：题库（v2.6.0）----
        .route("/api/banks", get(handlers::list_banks).post(handlers::create_bank))
        .route("/api/banks/seed", post(handlers::restore_seed))
        .route("/api/banks/{id}", post(handlers::update_bank))
        .route("/api/banks/{id}/delete", post(handlers::delete_bank))
        .route(
            "/api/banks/{id}/questions",
            get(handlers::list_questions).post(handlers::add_question),
        )
        .route("/api/banks/{id}/questions/{qid}", post(handlers::update_question))
        .route(
            "/api/banks/{id}/questions/{qid}/delete",
            post(handlers::delete_question),
        )
        .route("/api/banks/{id}/import", post(handlers::import_questions))
        .route("/api/pair-codes", post(handlers::create_pair_code))
        .route("/api/users", get(handlers::list_users))
        .route("/api/users", post(handlers::create_user))
        .route("/api/users/{user_id}", post(handlers::update_user))
        .route("/api/users/{user_id}/delete", post(handlers::delete_user))
        .route("/api/device/pair", post(handlers::device_pair))
        .route("/api/device/heartbeat", post(handlers::device_heartbeat))
        .route("/api/device/usage", post(handlers::device_usage))
        .route("/api/device/result", post(handlers::device_result))
        .route("/api/device/quiz", post(handlers::device_quiz))
        .with_state(state.clone());

    let listener = match tokio::net::TcpListener::bind(&state.cfg.addr).await {
        Ok(l) => l,
        Err(e) => {
            eprintln!("✘ 监听 {} 失败：{e}", state.cfg.addr);
            eprintln!("  常见原因：端口被占用（先跑一次 run.sh stop），或没有权限绑定该端口");
            std::process::exit(1);
        }
    };

    banner(&state.cfg, &state.data_path);

    // 后台维护：每小时清一次过期流水。
    // 事件保留 365 天、时间线保留 90 天，见 db.rs 顶部的常量。
    // 放在后台线程而不是每次心跳里做，是为了不让删除操作挡在心跳路径上。
    {
        let bg = state.clone();
        tokio::spawn(async move {
            let mut tick = tokio::time::interval(std::time::Duration::from_secs(3600));
            tick.tick().await; // interval 第一次是立即返回的，吃掉
            loop {
                tick.tick().await;
                let store = bg.lock();
                let (e, p, u) = store.db.prune(util::now_ts());
                if e + p + u > 0 {
                    println!("[维护] 清理过期数据：事件 {e} 条 · 在线区间 {p} 段 · 应用片段 {u} 条");
                }
            }
        });
    }

    let serve = axum::serve(
        listener,
        app.into_make_service_with_connect_info::<SocketAddr>(),
    )
    .with_graceful_shutdown(shutdown_signal(state.clone()));

    if let Err(e) = serve.await {
        eprintln!("✘ 服务异常退出：{e}");
        std::process::exit(1);
    }
}
