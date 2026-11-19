//! `/v1` integration contract: complete paging, search bounds, error shape and the
//! credential scope matrix. Synthetic data only.
use axum::{
    Router,
    body::Body,
    http::{Request, StatusCode},
};
use http_body_util::BodyExt;
use pocketlog_server::{AppState, router};
use serde_json::{Value, json};
use tower::ServiceExt;

const TOKEN: &str = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

struct Journal {
    app: Router,
    path: std::path::PathBuf,
}
impl Journal {
    fn new() -> Self {
        let dir = std::env::temp_dir().join(format!(
            "pocketlog-contract-{}-{}",
            std::process::id(),
            chrono::Utc::now().timestamp_nanos_opt().unwrap()
        ));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("journal.sqlite");
        let app = router(AppState::new(rusqlite::Connection::open(&path).unwrap(), TOKEN).unwrap());
        Self { app, path }
    }
    /// A second connection for bulk synthetic rows; the service runs in WAL mode.
    fn db(&self) -> rusqlite::Connection {
        let db = rusqlite::Connection::open(&self.path).unwrap();
        db.busy_timeout(std::time::Duration::from_secs(2)).unwrap();
        db
    }
    async fn call(
        &self,
        method: &str,
        path: &str,
        body: Option<String>,
        token: Option<&str>,
    ) -> Reply {
        let mut request = Request::builder().method(method).uri(path);
        if body.is_some() {
            request = request.header("content-type", "application/json");
        }
        if let Some(token) = token {
            request = request.header("authorization", format!("Bearer {token}"));
        }
        let response = self
            .app
            .clone()
            .oneshot(request.body(Body::from(body.unwrap_or_default())).unwrap())
            .await
            .unwrap();
        let status = response.status();
        let content_type = response
            .headers()
            .get("content-type")
            .map(|v| v.to_str().unwrap().to_owned())
            .unwrap_or_default();
        let retry_after = response
            .headers()
            .get("retry-after")
            .map(|v| v.to_str().unwrap().to_owned());
        let bytes = response.into_body().collect().await.unwrap().to_bytes();
        Reply {
            status,
            content_type,
            retry_after,
            body: serde_json::from_slice(&bytes).unwrap_or(Value::Null),
        }
    }
    async fn owner(&self, method: &str, path: &str, body: Value) -> Reply {
        self.call(method, path, Some(body.to_string()), Some(TOKEN))
            .await
    }
}
impl Drop for Journal {
    fn drop(&mut self) {
        if let Some(dir) = self.path.parent() {
            let _ = std::fs::remove_dir_all(dir);
        }
    }
}
struct Reply {
    status: StatusCode,
    content_type: String,
    retry_after: Option<String>,
    body: Value,
}

fn now() -> i64 {
    chrono::Utc::now().timestamp_millis()
}
fn insert(db: &rusqlite::Connection, id: &str, start: i64, end: i64, app: &str) {
    db.execute(
        "INSERT INTO activities VALUES (?,?,?,?,'\"work\"')",
        rusqlite::params![id, start, end, app],
    )
    .unwrap();
}

/// Reads every page and returns card IDs in order, asserting the contract per page.
async fn read_all(j: &Journal, query: &str) -> (Vec<String>, usize) {
    let mut ids = Vec::new();
    let mut cursor = String::new();
    let mut pages = 0;
    loop {
        let reply = j
            .owner(
                "GET",
                &format!("/v1/activities?{query}{cursor}"),
                Value::Null,
            )
            .await;
        assert_eq!(reply.status, StatusCode::OK, "{}", reply.body);
        pages += 1;
        let page = reply.body;
        assert_eq!(
            page["cards"].as_array().unwrap().len(),
            page["activities"].as_array().unwrap().len()
        );
        assert!(serde_json::to_vec(&page).unwrap().len() <= 480 * 1024);
        for card in page["cards"].as_array().unwrap() {
            ids.push(card["id"].as_str().unwrap().to_owned());
        }
        match &page["next_cursor"] {
            Value::Null => return (ids, pages),
            next => {
                cursor = format!(
                    "&after_start_ms={}&after_id={}&epoch={}",
                    next["after_start_ms"],
                    next["after_id"].as_str().unwrap(),
                    page["journal_epoch"]
                );
            }
        }
        assert!(pages < 1_000, "paging must terminate");
    }
}

#[tokio::test]
async fn pages_return_every_activity_once_including_tied_timestamps() {
    let j = Journal::new();
    let t = now() - 3_600_000;
    {
        let db = j.db();
        // Seven activities share one start; one starts before the window and is clipped.
        for i in 0..7 {
            insert(
                &db,
                &format!("tie-{i}"),
                t,
                t + 60_000,
                "org.example.editor",
            );
        }
        for i in 0..20 {
            insert(
                &db,
                &format!("seq-{i:02}"),
                t + 120_000 + i * 1_000,
                t + 121_000 + i * 1_000,
                "org.example.reader",
            );
        }
        insert(&db, "before", t - 10_000, t + 5_000, "org.example.clock");
        insert(&db, "outside", t - 90_000, t - 80_000, "org.example.clock");
    }
    let window = format!("from_ms={t}&to_ms={}", t + 3_000_000);
    let (ids, pages) = read_all(&j, &format!("{window}&limit=3")).await;
    let mut unique = ids.clone();
    unique.sort();
    unique.dedup();
    assert_eq!(unique.len(), ids.len(), "no duplicates across pages");
    assert_eq!(ids.len(), 28);
    assert!(!ids.contains(&"outside".to_owned()));
    assert_eq!(
        ids[0], "before",
        "stored start orders the clipped activity first"
    );
    assert!(pages >= 10);
    let first = j
        .owner(
            "GET",
            &format!("/v1/activities?{window}&limit=1"),
            Value::Null,
        )
        .await
        .body;
    assert_eq!(
        first["activities"][0]["start_ms"], t,
        "intervals are clipped like /v1/timeline"
    );
    assert_eq!(
        first["next_cursor"]["after_start_ms"],
        t - 10_000,
        "cursor keeps the stored start"
    );
    // Default page and limit bound.
    let (all, single) = read_all(&j, &window).await;
    assert_eq!((all, single), (ids, 1));
    for bad in [
        "limit=0",
        "limit=501",
        "after_id=x&epoch=0",
        "after_start_ms=1&epoch=0",
        "after_start_ms=1&after_id=x",
    ] {
        assert_eq!(
            j.owner(
                "GET",
                &format!("/v1/activities?{window}&{bad}"),
                Value::Null
            )
            .await
            .status,
            StatusCode::BAD_REQUEST,
            "{bad}"
        );
    }
    let empty = j
        .owner(
            "GET",
            &format!(
                "/v1/activities?from_ms={}&to_ms={}",
                t + 3_000_000,
                t + 3_100_000
            ),
            Value::Null,
        )
        .await
        .body;
    assert_eq!(empty["cards"], json!([]));
    assert!(empty["next_cursor"].is_null());
    assert_eq!(empty["scanned"], 0);
}

#[tokio::test]
async fn new_rows_after_the_cursor_appear_and_deletion_expires_cursors() {
    let j = Journal::new();
    let t = now() - 3_600_000;
    {
        let db = j.db();
        for i in 0..4 {
            insert(
                &db,
                &format!("a{i}"),
                t + i * 1_000,
                t + i * 1_000 + 500,
                "org.example.reader",
            );
        }
    }
    let window = format!("from_ms={}&to_ms={}", t - 1, t + 3_000_000);
    let page = j
        .owner(
            "GET",
            &format!("/v1/activities?{window}&limit=2"),
            Value::Null,
        )
        .await
        .body;
    let next = &page["next_cursor"];
    let cursor = format!(
        "&after_start_ms={}&after_id={}&epoch={}",
        next["after_start_ms"],
        next["after_id"].as_str().unwrap(),
        page["journal_epoch"]
    );
    // An activity uploaded later sorts after the cursor and is still delivered;
    // one sorting before the cursor is documented as missed until a re-read.
    insert(
        &j.db(),
        "late-after",
        t + 10_000,
        t + 10_500,
        "org.example.reader",
    );
    insert(&j.db(), "late-before", t, t + 100, "org.example.aaa");
    let rest = j
        .owner(
            "GET",
            &format!("/v1/activities?{window}&limit=10{cursor}"),
            Value::Null,
        )
        .await
        .body;
    let rest: Vec<&str> = rest["cards"]
        .as_array()
        .unwrap()
        .iter()
        .map(|c| c["id"].as_str().unwrap())
        .collect();
    assert_eq!(rest, ["a2", "a3", "late-after"]);
    assert_eq!(
        j.owner("DELETE", "/v1/journal", Value::Null).await.status,
        StatusCode::NO_CONTENT
    );
    let expired = j
        .owner(
            "GET",
            &format!("/v1/activities?{window}{cursor}"),
            Value::Null,
        )
        .await;
    assert_eq!(expired.status, StatusCode::CONFLICT);
    assert_eq!(expired.body, json!({"error":"cursor expired"}));
    let restarted = j
        .owner("GET", &format!("/v1/activities?{window}"), Value::Null)
        .await
        .body;
    assert_ne!(restarted["journal_epoch"], page["journal_epoch"]);
    assert_eq!(restarted["cards"], json!([]));
}

#[tokio::test]
async fn oversized_pages_shorten_instead_of_dropping_cards() {
    let j = Journal::new();
    let t = now() - 3_600_000;
    {
        let db = j.db();
        for i in 0..200 {
            let id = format!("big-{i:03}");
            insert(
                &db,
                &id,
                t + i * 1_000,
                t + i * 1_000 + 900,
                "org.example.notes",
            );
            // 1,000 four-byte characters: about 4 KB per card.
            db.execute(
                "INSERT INTO notes VALUES (?,?,?,'\"work\"',1)",
                rusqlite::params![id, "合成標題", "𝄞".repeat(1_000)],
            )
            .unwrap();
        }
    }
    let window = format!("from_ms={t}&to_ms={}", t + 3_000_000);
    let first = j
        .owner(
            "GET",
            &format!("/v1/activities?{window}&limit=500"),
            Value::Null,
        )
        .await
        .body;
    let count = first["cards"].as_array().unwrap().len();
    assert!(count < 200 && count > 1, "page shortened to {count}");
    assert_eq!(
        first["next_cursor"]["after_id"],
        first["cards"][count - 1]["id"],
        "cursor resumes right after the last returned card"
    );
    let (ids, _) = read_all(&j, &format!("{window}&limit=500")).await;
    assert_eq!(ids.len(), 200);
    assert_eq!(
        ids,
        (0..200).map(|i| format!("big-{i:03}")).collect::<Vec<_>>()
    );
}

#[tokio::test]
async fn search_matches_cards_case_insensitively_within_a_bounded_scan() {
    let j = Journal::new();
    let t = now() - 20 * 86_400_000;
    {
        let db = j.db();
        let tx = db.unchecked_transaction().unwrap();
        // 2,600 rows with only three matches: one request inspects at most 2,000.
        for i in 0..2_600 {
            insert(
                &tx,
                &format!("row-{i:04}"),
                t + i * 60_000,
                t + i * 60_000 + 30_000,
                "org.example.reader",
            );
        }
        tx.execute(
            "INSERT INTO notes VALUES ('row-0005','Quarterly REPORT draft','','\"work\"',1)",
            [],
        )
        .unwrap();
        tx.execute(
            "INSERT INTO notes VALUES ('row-2500','季度報告','整理 report 圖表','\"learning\"',1)",
            [],
        )
        .unwrap();
        insert(
            &tx,
            "app-hit",
            t + 2_601 * 60_000,
            t + 2_601 * 60_000 + 1,
            "com.example.Reporter",
        );
        tx.commit().unwrap();
    }
    let window = format!("from_ms={t}&to_ms={}", now());
    let first = j
        .owner(
            "GET",
            &format!("/v1/activities?{window}&q=%20Report%20"),
            Value::Null,
        )
        .await
        .body;
    assert_eq!(first["scanned"], 2_000);
    assert_eq!(first["cards"].as_array().unwrap().len(), 1);
    assert_eq!(first["cards"][0]["id"], "row-0005");
    assert_eq!(
        first["next_cursor"]["after_id"], "row-1999",
        "sparse search still advances"
    );
    let (ids, pages) = read_all(&j, &format!("{window}&q=report")).await;
    assert_eq!(ids, ["row-0005", "row-2500", "app-hit"]);
    assert_eq!(pages, 2);
    let (learning, _) = read_all(&j, &format!("{window}&q=report&category=learning")).await;
    assert_eq!(learning, ["row-2500"]);
    let (none, _) = read_all(&j, &format!("{window}&q=%E4%B8%8D%E5%AD%98%E5%9C%A8")).await;
    assert!(none.is_empty());
    let long = "x".repeat(101);
    for bad in [
        "q=",
        "q=%20%20",
        &format!("q={long}"),
        "category=feelings",
        "q=%00",
    ] {
        let reply = j
            .owner(
                "GET",
                &format!("/v1/activities?{window}&{bad}"),
                Value::Null,
            )
            .await;
        assert_eq!(reply.status, StatusCode::BAD_REQUEST, "{bad}");
    }
    // Range bound: at most 30 days per read.
    let wide = format!(
        "from_ms={}&to_ms={}",
        t - 15 * 86_400_000,
        t + 16 * 86_400_000
    );
    assert_eq!(
        j.owner("GET", &format!("/v1/activities?{wide}"), Value::Null)
            .await
            .status,
        StatusCode::BAD_REQUEST
    );
}

#[tokio::test]
async fn every_v1_error_is_json_and_keeps_its_status() {
    let j = Journal::new();
    let cases: Vec<(&str, &str, Option<String>, StatusCode, &str)> = vec![
        (
            "POST",
            "/v1/activities",
            Some("{not json".into()),
            StatusCode::BAD_REQUEST,
            "invalid request",
        ),
        (
            "POST",
            "/v1/activities",
            Some(r#"{"activities":[],"extra":1}"#.into()),
            StatusCode::UNPROCESSABLE_ENTITY,
            "invalid request",
        ),
        (
            "GET",
            "/v1/timeline?from_ms=1",
            None,
            StatusCode::BAD_REQUEST,
            "invalid request",
        ),
        (
            "GET",
            "/v1/timeline?from_ms=1&to_ms=2&unknown=1",
            None,
            StatusCode::BAD_REQUEST,
            "invalid request",
        ),
        (
            "PATCH",
            "/v1/timeline",
            None,
            StatusCode::METHOD_NOT_ALLOWED,
            "method not allowed",
        ),
        (
            "POST",
            "/v1/cards",
            Some(format!(
                r#"{{"id":"x","title":"{}","summary":"","category":"work"}}"#,
                "a".repeat(70_000)
            )),
            StatusCode::PAYLOAD_TOO_LARGE,
            "request too large",
        ),
        (
            "GET",
            "/v1/no-such-route",
            None,
            StatusCode::NOT_FOUND,
            "not found",
        ),
        (
            "GET",
            "/v1/timeline",
            None,
            StatusCode::UNAUTHORIZED,
            "unauthorized",
        ),
    ];
    for (method, path, body, status, error) in cases {
        let token = (status != StatusCode::UNAUTHORIZED).then_some(TOKEN);
        let reply = j.call(method, path, body, token).await;
        assert_eq!(reply.status, status, "{method} {path}");
        assert!(
            reply.content_type.starts_with("application/json"),
            "{method} {path}: {}",
            reply.content_type
        );
        assert_eq!(reply.body, json!({"error": error}), "{method} {path}");
    }
    // A request without a JSON content type is rejected in the same shape.
    let reply = j.call("POST", "/v1/checkins", None, Some(TOKEN)).await;
    assert_eq!(reply.status, StatusCode::UNSUPPORTED_MEDIA_TYPE);
    assert_eq!(reply.body, json!({"error":"unsupported media type"}));
    // Busy refusals carry a retry hint; capability flags are advertised.
    let status = j.owner("GET", "/v1/status", Value::Null).await.body;
    assert_eq!(status["api"]["version"], 1);
    for flag in [
        "activity_pages",
        "activity_search",
        "json_errors",
        "retry_after",
    ] {
        assert!(
            status["api"]["capabilities"]
                .as_array()
                .unwrap()
                .contains(&json!(flag))
        );
    }
    let probe = j
        .owner("POST", "/v1/ai/test", json!({"provider_id":"0".repeat(64)}))
        .await;
    assert_eq!(
        probe.status,
        StatusCode::SERVICE_UNAVAILABLE,
        "no AI settings directory configured"
    );
    assert_eq!(probe.retry_after.as_deref(), Some("5"));
}

/// Every `/v1` route and method with the credential kinds that may use it.
const ROUTES: &[(&str, &str)] = &[
    ("GET", "/v1/activities?from_ms=0&to_ms=1"),
    ("POST", "/v1/activities"),
    ("POST", "/v1/observations"),
    ("POST", "/v1/checkins"),
    ("GET", "/v1/timeline?from_ms=0&to_ms=1"),
    ("GET", "/v1/overview?from_ms=0&to_ms=1"),
    ("GET", "/v1/status"),
    ("POST", "/v1/cards"),
    ("POST", "/v1/reflections"),
    ("GET", "/v1/storage"),
    ("PUT", "/v1/storage"),
    ("GET", "/v1/collection"),
    ("PUT", "/v1/collection"),
    ("GET", "/v1/ai"),
    ("PUT", "/v1/ai"),
    ("DELETE", "/v1/ai"),
    ("POST", "/v1/ai/test"),
    ("PUT", "/v1/ai/auto"),
    ("GET", "/v1/workspace"),
    ("PUT", "/v1/workspace"),
    ("POST", "/v1/workspace/preview"),
    ("GET", "/v1/reviews?from_ms=0&to_ms=1"),
    ("POST", "/v1/reviews"),
    ("POST", "/v1/ask"),
    ("GET", "/v1/conversations"),
    ("GET", "/v1/conversations/synthetic"),
    ("DELETE", "/v1/conversations/synthetic"),
    ("GET", "/v1/focus"),
    ("POST", "/v1/focus"),
    ("DELETE", "/v1/focus"),
    ("POST", "/v1/focus/finish"),
    ("GET", "/v1/briefs?from_ms=0&to_ms=1"),
    ("POST", "/v1/briefs"),
    ("GET", "/v1/briefs/schedule"),
    ("PUT", "/v1/briefs/schedule"),
    ("DELETE", "/v1/briefs/synthetic"),
    ("GET", "/v1/agent-sessions"),
    ("PUT", "/v1/agent-sessions"),
    ("GET", "/v1/agent-sessions/synthetic"),
    ("DELETE", "/v1/agent-sessions/synthetic"),
    ("GET", "/v1/replay?from_ms=0&to_ms=1"),
    ("GET", "/v1/replay/synthetic"),
    ("GET", "/v1/replay/synthetic/image"),
    ("DELETE", "/v1/replay/synthetic"),
    ("GET", "/v1/devices"),
    ("POST", "/v1/devices"),
    ("DELETE", "/v1/devices/synthetic"),
    // Destructive last so earlier probes run against the same journal.
    ("DELETE", "/v1/journal"),
];
const COLLECTOR: &[(&str, &str)] = &[
    ("POST", "/v1/activities"),
    ("POST", "/v1/observations"),
    ("GET", "/v1/collection"),
    ("GET", "/v1/ai"),
];

#[tokio::test]
async fn credential_scope_matrix_covers_every_route() {
    let j = Journal::new();
    let mut keys = std::collections::BTreeMap::new();
    for (name, scope) in [
        ("journal", "journal"),
        ("collector", "collector"),
        ("revoked", "journal"),
        ("expired", "journal"),
    ] {
        let reply = j
            .owner(
                "POST",
                "/v1/devices",
                json!({"label":format!("Synthetic {name}"),"scope":scope,"days":1}),
            )
            .await;
        assert_eq!(reply.status, StatusCode::OK);
        keys.insert(
            name,
            (
                reply.body["id"].as_str().unwrap().to_owned(),
                reply.body["token"].as_str().unwrap().to_owned(),
            ),
        );
    }
    let revoked = &keys["revoked"].0;
    assert_eq!(
        j.owner("DELETE", &format!("/v1/devices/{revoked}"), Value::Null)
            .await
            .status,
        StatusCode::NO_CONTENT
    );
    j.db()
        .execute(
            "UPDATE device_credentials SET expires_ms=? WHERE id=?",
            rusqlite::params![now() - 1, keys["expired"].0],
        )
        .unwrap();
    let unknown = "f".repeat(64);
    for &(method, path) in ROUTES {
        let body = (method != "GET").then(|| "{}".to_owned());
        let collector_allowed = COLLECTOR.iter().any(|&(m, p)| m == method && p == path);
        let device_route = path.starts_with("/v1/devices");
        for (who, token, allowed) in [
            ("journal", keys["journal"].1.as_str(), !device_route),
            ("collector", keys["collector"].1.as_str(), collector_allowed),
            ("revoked", keys["revoked"].1.as_str(), false),
            ("expired", keys["expired"].1.as_str(), false),
            ("unknown", unknown.as_str(), false),
            ("malformed", "not-a-key", false),
        ] {
            if path == "/v1/journal" && allowed {
                continue; // Checked once below so it cannot erase the next probe's setup.
            }
            let reply = j.call(method, path, body.clone(), Some(token)).await;
            assert_eq!(
                reply.status == StatusCode::UNAUTHORIZED,
                !allowed,
                "{who} {method} {path}: {} {}",
                reply.status,
                reply.body
            );
            if !allowed {
                assert_eq!(
                    reply.body,
                    json!({"error":"unauthorized"}),
                    "no detail about why"
                );
            }
        }
        let missing = j.call(method, path, body.clone(), None).await;
        assert_eq!(
            missing.status,
            StatusCode::UNAUTHORIZED,
            "anonymous {method} {path}"
        );
        let owner = j.call(method, path, body, Some(TOKEN)).await;
        assert_ne!(
            owner.status,
            StatusCode::UNAUTHORIZED,
            "owner {method} {path}"
        );
    }
    let erase = j
        .call("DELETE", "/v1/journal", None, Some(&keys["journal"].1))
        .await;
    assert_eq!(erase.status, StatusCode::NO_CONTENT);
}

#[tokio::test]
async fn protected_screens_are_text_only_and_flagged_on_their_card() {
    let j = Journal::new();
    let t = now() - 3_600_000;
    let activity = json!({"id":"video","start_ms":t,"end_ms":t + 600_000,"app":"com.example.video","category":"unsorted"});
    assert_eq!(
        j.owner("POST", "/v1/activities", json!({"activities":[activity]}))
            .await
            .status,
        StatusCode::OK
    );
    let text_only = json!({"id":"drm-1","at_ms":t + 60_000,"app":"com.example.video","text":"Synthetic show title","analysis_consent":true,"protected_screen":true});
    assert_eq!(
        j.owner("POST", "/v1/observations", text_only.clone())
            .await
            .status,
        StatusCode::NO_CONTENT
    );
    // Same-ID replay stays idempotent; a protected capture can never carry pixels.
    assert_eq!(
        j.owner("POST", "/v1/observations", text_only).await.status,
        StatusCode::NO_CONTENT
    );
    let with_pixels = json!({"id":"drm-2","at_ms":t + 70_000,"app":"com.example.video","text":"x","analysis_consent":true,"protected_screen":true,"jpeg_base64":"AAAA","image_consent":true});
    assert_eq!(
        j.owner("POST", "/v1/observations", with_pixels)
            .await
            .status,
        StatusCode::BAD_REQUEST
    );
    let window = format!("from_ms={}&to_ms={}", t - 1, t + 3_000_000);
    let card = &j
        .owner("GET", &format!("/v1/timeline?{window}"), Value::Null)
        .await
        .body["cards"][0];
    assert_eq!(card["protected_screen"], true);
    assert_eq!(card["pending_context"], true);
    assert!(
        !card.to_string().contains("Synthetic show title"),
        "raw text is never read back"
    );
    // Earlier payloads without the field keep working and are not flagged.
    let plain = json!({"id":"plain","at_ms":t + 80_000,"app":"com.example.other","text":"x","analysis_consent":true});
    assert_eq!(
        j.owner("POST", "/v1/observations", plain).await.status,
        StatusCode::NO_CONTENT
    );
}
