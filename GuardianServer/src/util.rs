//! 小工具：时间、日期、随机串、常量时间比较。
//!
//! 刻意不引 chrono / time 之类的日期库：只需要"某时区的今天是哪一天"，
//! 自己算比多拉一个依赖更稳（交叉编译和体积都吃亏）。

use rand::Rng;
use serde_json::Value;
use std::time::{SystemTime, UNIX_EPOCH};

/// 当前 Unix 时间戳（秒，带小数）
pub fn now_ts() -> f64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs_f64())
        .unwrap_or(0.0)
}

/// 把时间戳换算成"指定时区的那一天"，格式 yyyy-MM-dd。
/// 用固定偏移而不是 tzdata：服务器上不需要装任何时区数据库。
pub fn local_day(ts: f64, tz_offset_hours: f64) -> String {
    let shifted = ts + tz_offset_hours * 3600.0;
    let days = (shifted / 86400.0).floor() as i64;
    let (y, m, d) = civil_from_days(days);
    format!("{:04}-{:02}-{:02}", y, m, d)
}

/// [`local_day`] 的反函数：把 `yyyy-MM-dd` 解析成"当天本地 00:00"的时间戳。
///
/// 时间线要按天切片，必须知道"这一天从哪一秒开始"。格式不对直接返回 None，
/// 让调用方退回"今天"，而不是拿一个荒唐的时间去查。
pub fn day_start_ts(day: &str, tz_offset_hours: f64) -> Option<f64> {
    let mut it = day.split('-');
    let y: i64 = it.next()?.trim().parse().ok()?;
    let m: i64 = it.next()?.trim().parse().ok()?;
    let d: i64 = it.next()?.trim().parse().ok()?;
    if !(1970..=2999).contains(&y) || !(1..=12).contains(&m) || !(1..=31).contains(&d) {
        return None;
    }
    Some(days_from_civil(y, m, d) as f64 * 86400.0 - tz_offset_hours * 3600.0)
}

/// [`civil_from_days`] 的逆运算
fn days_from_civil(y: i64, m: i64, d: i64) -> i64 {
    let y = if m <= 2 { y - 1 } else { y };
    let era = if y >= 0 { y } else { y - 399 } / 400;
    let yoe = y - era * 400;
    let mp = (m + 9) % 12;
    let doy = (153 * mp + 2) / 5 + d - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    era * 146_097 + doe - 719_468
}

/// Howard Hinnant 的 civil_from_days：把"1970-01-01 起的天数"还原成年月日。
fn civil_from_days(z: i64) -> (i64, i64, i64) {    let z = z + 719_468;
    let era = if z >= 0 { z } else { z - 146_096 } / 146_097;
    let doe = z - era * 146_097; // [0, 146096]
    let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146_096) / 365; // [0, 399]
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100); // [0, 365]
    let mp = (5 * doy + 2) / 153; // [0, 11]
    let d = doy - (153 * mp + 2) / 5 + 1; // [1, 31]
    let m = if mp < 10 { mp + 3 } else { mp - 9 }; // [1, 12]
    (if m <= 2 { y + 1 } else { y }, m, d)
}

/// 从 JSON 里取整数（兼容数字、布尔、数字字符串三种写法）
pub fn json_i64(v: Option<&Value>) -> Option<i64> {
    match v? {
        Value::Number(n) => n
            .as_i64()
            .or_else(|| n.as_f64().map(|f| f.round() as i64)),
        Value::Bool(b) => Some(if *b { 1 } else { 0 }),
        Value::String(s) => s.trim().parse::<i64>().ok(),
        _ => None,
    }
}

/// 取整数并夹到 [lo, hi]；取不到就用默认值
pub fn clamp(v: Option<&Value>, lo: i64, hi: i64, default: i64) -> i64 {
    match json_i64(v) {
        Some(n) => n.clamp(lo, hi),
        None => default,
    }
}

/// 取布尔；缺省用 default
pub fn json_bool(v: Option<&Value>, default: bool) -> bool {
    match v {
        Some(Value::Bool(b)) => *b,
        Some(Value::Number(n)) => n.as_f64().map(|f| f != 0.0).unwrap_or(default),
        Some(Value::String(s)) => matches!(s.trim(), "1" | "true" | "TRUE" | "yes"),
        Some(Value::Null) | None => default,
        _ => default,
    }
}

fn hex_of(bytes: &[u8]) -> String {
    const HEX: &[u8; 16] = b"0123456789abcdef";
    let mut s = String::with_capacity(bytes.len() * 2);
    for b in bytes {
        s.push(HEX[(b >> 4) as usize] as char);
        s.push(HEX[(b & 0x0f) as usize] as char);
    }
    s
}

/// n 字节随机数的十六进制串（2n 个字符）
pub fn rand_hex(n: usize) -> String {
    let mut rng = rand::thread_rng();
    let mut buf = vec![0u8; n];
    rng.fill(&mut buf[..]);
    hex_of(&buf)
}

/// n 位纯数字随机串（配对码）
pub fn rand_digits(n: usize) -> String {
    let mut rng = rand::thread_rng();
    (0..n).map(|_| char::from(b'0' + rng.gen_range(0..10u8))).collect()
}

/// 常量时间字符串比较：不因为"前几位对上了"而提前返回，
/// 避免攻击者靠响应时间一位一位试出口令。
pub fn ct_eq(a: &str, b: &str) -> bool {
    let (a, b) = (a.as_bytes(), b.as_bytes());
    let mut diff = a.len() ^ b.len();
    let max = a.len().max(b.len());
    for i in 0..max {
        let x = *a.get(i).unwrap_or(&0);
        let y = *b.get(i).unwrap_or(&0);
        diff |= (x ^ y) as usize;
    }
    diff == 0
}

// ======================================================================
// 密码哈希（SHA-256 / HMAC / PBKDF2 全部自己实现，零依赖）
//
// 为什么不用 sha2 / argon2 / bcrypt 这些库：
//   * argon2、bcrypt 是 C 代码，会毁掉"Windows 上一条命令出 Linux 静态包"的前提；
//   * sha2 虽然是纯 Rust，但当前网络拉 crate 极慢，为几十行代码不值得。
// 自己实现的正确性靠 NIST / RFC 的公开测试向量保证（见文件末尾 tests）。
// ======================================================================

/// SHA-256（FIPS 180-4）
pub fn sha256(data: &[u8]) -> [u8; 32] {
    const K: [u32; 64] = [
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4,
        0xab1c5ed5, 0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe,
        0x9bdc06a7, 0xc19bf174, 0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f,
        0x4a7484aa, 0x5cb0a9dc, 0x76f988da, 0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7,
        0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967, 0x27b70a85, 0x2e1b2138, 0x4d2c6dfc,
        0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85, 0xa2bfe8a1, 0xa81a664b,
        0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070, 0x19a4c116,
        0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7,
        0xc67178f2,
    ];

    let mut h: [u32; 8] = [
        0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab,
        0x5be0cd19,
    ];

    let mut msg = data.to_vec();
    let bit_len = (data.len() as u64).wrapping_mul(8);
    msg.push(0x80);
    while msg.len() % 64 != 56 {
        msg.push(0);
    }
    msg.extend_from_slice(&bit_len.to_be_bytes());

    for chunk in msg.chunks_exact(64) {
        let mut w = [0u32; 64];
        for i in 0..16 {
            w[i] = u32::from_be_bytes([
                chunk[4 * i],
                chunk[4 * i + 1],
                chunk[4 * i + 2],
                chunk[4 * i + 3],
            ]);
        }
        for i in 16..64 {
            let s0 = w[i - 15].rotate_right(7) ^ w[i - 15].rotate_right(18) ^ (w[i - 15] >> 3);
            let s1 = w[i - 2].rotate_right(17) ^ w[i - 2].rotate_right(19) ^ (w[i - 2] >> 10);
            w[i] = w[i - 16]
                .wrapping_add(s0)
                .wrapping_add(w[i - 7])
                .wrapping_add(s1);
        }

        let mut a = h[0];
        let mut b = h[1];
        let mut c = h[2];
        let mut d = h[3];
        let mut e = h[4];
        let mut f = h[5];
        let mut g = h[6];
        let mut hh = h[7];

        for i in 0..64 {
            let s1 = e.rotate_right(6) ^ e.rotate_right(11) ^ e.rotate_right(25);
            let ch = (e & f) ^ ((!e) & g);
            let t1 = hh
                .wrapping_add(s1)
                .wrapping_add(ch)
                .wrapping_add(K[i])
                .wrapping_add(w[i]);
            let s0 = a.rotate_right(2) ^ a.rotate_right(13) ^ a.rotate_right(22);
            let maj = (a & b) ^ (a & c) ^ (b & c);
            let t2 = s0.wrapping_add(maj);

            hh = g;
            g = f;
            f = e;
            e = d.wrapping_add(t1);
            d = c;
            c = b;
            b = a;
            a = t1.wrapping_add(t2);
        }

        h[0] = h[0].wrapping_add(a);
        h[1] = h[1].wrapping_add(b);
        h[2] = h[2].wrapping_add(c);
        h[3] = h[3].wrapping_add(d);
        h[4] = h[4].wrapping_add(e);
        h[5] = h[5].wrapping_add(f);
        h[6] = h[6].wrapping_add(g);
        h[7] = h[7].wrapping_add(hh);
    }

    let mut out = [0u8; 32];
    for i in 0..8 {
        out[4 * i..4 * i + 4].copy_from_slice(&h[i].to_be_bytes());
    }
    out
}

/// HMAC-SHA256（RFC 2104）
pub fn hmac_sha256(key: &[u8], msg: &[u8]) -> [u8; 32] {
    const BLOCK: usize = 64;
    let mut k = [0u8; BLOCK];
    if key.len() > BLOCK {
        k[..32].copy_from_slice(&sha256(key));
    } else {
        k[..key.len()].copy_from_slice(key);
    }
    let mut ipad = [0x36u8; BLOCK];
    let mut opad = [0x5cu8; BLOCK];
    for i in 0..BLOCK {
        ipad[i] ^= k[i];
        opad[i] ^= k[i];
    }

    let mut inner = ipad.to_vec();
    inner.extend_from_slice(msg);
    let ih = sha256(&inner);

    let mut outer = opad.to_vec();
    outer.extend_from_slice(&ih);
    sha256(&outer)
}

/// PBKDF2-HMAC-SHA256，输出 32 字节（正好一个块，实现可以很短）
pub fn pbkdf2_sha256(password: &[u8], salt: &[u8], iters: u32) -> [u8; 32] {
    let iters = iters.max(1);
    let mut first = salt.to_vec();
    first.extend_from_slice(&1u32.to_be_bytes());

    let mut u = hmac_sha256(password, &first);
    let mut out = u;
    for _ in 1..iters {
        u = hmac_sha256(password, &u);
        for i in 0..32 {
            out[i] ^= u[i];
        }
    }
    out
}

/// 密码存储格式：`pbkdf2$<迭代次数>$<盐 hex>$<哈希 hex>`
const PBKDF2_ITERS: u32 = 60_000;

pub fn rand_bytes(n: usize) -> Vec<u8> {
    use rand::RngCore;
    let mut rng = rand::thread_rng();
    let mut buf = vec![0u8; n];
    rng.fill_bytes(&mut buf);
    buf
}

/// 生成密码哈希（每个账号独立随机盐）
pub fn hash_password(password: &str) -> String {
    let salt = rand_bytes(16);
    let dk = pbkdf2_sha256(password.as_bytes(), &salt, PBKDF2_ITERS);
    format!(
        "pbkdf2${}${}${}",
        PBKDF2_ITERS,
        hex_of(&salt),
        hex_of(&dk)
    )
}

/// 校验密码。格式不对一律当失败，绝不 panic（数据文件可能被手改过）
pub fn verify_password(password: &str, stored: &str) -> bool {
    let mut parts = stored.split('$');
    if parts.next() != Some("pbkdf2") {
        return false;
    }
    let iters: u32 = match parts.next().and_then(|s| s.parse().ok()) {
        Some(v) => v,
        None => return false,
    };
    let (salt_hex, hash_hex) = match (parts.next(), parts.next()) {
        (Some(a), Some(b)) => (a, b),
        _ => return false,
    };
    let salt = match unhex(salt_hex) {
        Some(s) => s,
        None => return false,
    };
    let expect = match unhex(hash_hex) {
        Some(s) => s,
        None => return false,
    };
    let dk = pbkdf2_sha256(password.as_bytes(), &salt, iters);
    ct_eq(&hex_of(&dk), &hex_of(&expect))
}

fn unhex(s: &str) -> Option<Vec<u8>> {
    if s.len() % 2 != 0 {
        return None;
    }
    let b = s.as_bytes();
    let mut out = Vec::with_capacity(s.len() / 2);
    let val = |c: u8| -> Option<u8> {
        match c {
            b'0'..=b'9' => Some(c - b'0'),
            b'a'..=b'f' => Some(c - b'a' + 10),
            b'A'..=b'F' => Some(c - b'A' + 10),
            _ => None,
        }
    };
    for pair in b.chunks_exact(2) {
        out.push((val(pair[0])? << 4) | val(pair[1])?);
    }
    Some(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sha256_known_vectors() {
        // NIST FIPS 180-4 附录 B
        assert_eq!(
            hex_of(&sha256(b"")),
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        );
        assert_eq!(
            hex_of(&sha256(b"abc")),
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        );
        assert_eq!(
            hex_of(&sha256(b"abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq")),
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1"
        );
    }

    #[test]
    fn hmac_rfc4231() {
        // RFC 4231 Test Case 2：key = "Jefe"，data = "what do ya want for nothing?"
        assert_eq!(
            hex_of(&hmac_sha256(b"Jefe", b"what do ya want for nothing?")),
            "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843"
        );
    }

    #[test]
    fn pbkdf2_rfc6070_style() {
        // 用 Python3 hashlib.pbkdf2_hmac('sha256', b'password', b'salt', 1) 独立算出的期望值
        assert_eq!(
            hex_of(&pbkdf2_sha256(b"password", b"salt", 1)),
            "120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b"
        );
        // 迭代 2 次
        assert_eq!(
            hex_of(&pbkdf2_sha256(b"password", b"salt", 2)),
            "ae4d0c95af6b46d32d0adff928f06dd02a303f8ef3c251dfd6e2d85a95474c43"
        );
    }

    #[test]
    fn password_roundtrip() {
        let h = hash_password("guardian2026");
        assert!(h.starts_with("pbkdf2$"));
        assert!(verify_password("guardian2026", &h));
        assert!(!verify_password("guardian2027", &h));
        assert!(!verify_password("", &h));
        // 格式损坏时只返回 false，不 panic
        assert!(!verify_password("x", "garbage"));
        assert!(!verify_password("x", "pbkdf2$abc$zz$$"));
        // 同一个密码两次哈希结果不同（盐不同），但都能验证通过
        let h2 = hash_password("guardian2026");
        assert_ne!(h, h2);
        assert!(verify_password("guardian2026", &h2));
    }

    #[test]
    fn day_conversion() {
        // 期望值由 Python 的 datetime 独立算过一遍，逐条核对
        assert_eq!(local_day(0.0, 0.0), "1970-01-01");
        assert_eq!(local_day(0.0, 8.0), "1970-01-01");
        assert_eq!(local_day(0.0, -5.0), "1969-12-31");
        assert_eq!(local_day(86_399.0, 0.0), "1970-01-01");
        assert_eq!(local_day(86_399.0, 8.0), "1970-01-02");
        assert_eq!(local_day(86_400.0, 0.0), "1970-01-02");
        assert_eq!(local_day(1_792_000_000.0, 0.0), "2026-10-14");
        assert_eq!(local_day(1_792_000_000.0, 8.0), "2026-10-15");
        assert_eq!(local_day(1_760_000_000.0, 8.0), "2025-10-09");
        assert_eq!(local_day(4_102_444_800.0, 8.0), "2100-01-01");
    }

    #[test]
    fn ct_eq_works() {
        assert!(ct_eq("abc", "abc"));
        assert!(!ct_eq("abc", "abd"));
        assert!(!ct_eq("abc", "ab"));
        assert!(!ct_eq("", "a"));
    }
}
