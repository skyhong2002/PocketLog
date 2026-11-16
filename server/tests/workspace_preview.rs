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
const PATH: &str = "/v1/workspace/preview";

fn app() -> Router {
    router(AppState::new(rusqlite::Connection::open_in_memory().unwrap(), TOKEN).unwrap())
}

async fn raw(
    app: &Router,
    path: &str,
    method: &str,
    token: Option<&str>,
    body: String,
) -> (StatusCode, Value) {
    let mut request = Request::builder()
        .method(method)
        .uri(path)
        .header("content-type", "application/json");
    if let Some(token) = token {
        request = request.header("authorization", format!("Bearer {token}"));
    }
    let response = app
        .clone()
        .oneshot(request.body(Body::from(body)).unwrap())
        .await
        .unwrap();
    assert_eq!(response.headers()["cache-control"], "no-store");
    let status = response.status();
    let body = response.into_body().collect().await.unwrap().to_bytes();
    (status, serde_json::from_slice(&body).unwrap_or(Value::Null))
}

async fn call(app: &Router, path: &str, method: &str, body: Value) -> (StatusCode, Value) {
    raw(app, path, method, Some(TOKEN), body.to_string()).await
}

async fn draft(app: &Router) -> Value {
    let (status, prefs) = call(app, "/v1/workspace", "GET", Value::Null).await;
    assert_eq!(status, StatusCode::OK);
    prefs
}

fn sample() -> Value {
    json!({"app":"org.example.reader","title":"A Rust guide","category":"unsorted"})
}

#[tokio::test]
async fn preview_uses_ordered_enabled_and_rules_and_preserves_manual_choices() {
    let app = app();
    let mut workspace = draft(&app).await;
    workspace["rules"] = json!([
        {"id":"disabled","category":"rest","app":"org.example.reader","enabled":false},
        {"id":"both","category":"learning","app":"org.example.reader","title_contains":"RUST"},
        {"id":"fallback","category":"work","app":"org.example.reader"},
        {"id":"title-only","category":"news_reading","title_contains":"新聞"}
    ]);
    for (sample, category, matched, manual) in [
        (sample(), "learning", Some("both"), false),
        (
            json!({"app":"org.example.reader","title":"Other title","category":"unsorted"}),
            "work",
            Some("fallback"),
            false,
        ),
        (
            json!({"app":"ORG.EXAMPLE.READER","title":"A Rust guide","category":"rest"}),
            "rest",
            None,
            false,
        ),
        (
            json!({"app":"another.app","title":"rust","category":"communication"}),
            "communication",
            None,
            false,
        ),
        (
            json!({"app":"another.app","title":"今日新聞","category":"rest"}),
            "news_reading",
            Some("title-only"),
            false,
        ),
        (
            json!({"app":"org.example.reader","title":"A Rust guide","category":"rest","edited":true}),
            "rest",
            None,
            true,
        ),
        (
            json!({"app":"another.app","title":"","category":"rest"}),
            "rest",
            None,
            false,
        ),
    ] {
        let (status, result) = call(
            &app,
            PATH,
            "POST",
            json!({"workspace":workspace,"sample":sample}),
        )
        .await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(
            result,
            json!({"category":category,"matched_rule_id":matched,"manual_override":manual})
        );
    }
    // Changing array order changes precedence, without saving the draft.
    workspace["rules"].as_array_mut().unwrap().swap(1, 2);
    let (_, result) = call(
        &app,
        PATH,
        "POST",
        json!({"workspace":workspace,"sample":sample()}),
    )
    .await;
    assert_eq!(result["matched_rule_id"], "fallback");
}

#[tokio::test]
async fn preview_and_production_share_the_same_classifier() {
    let fixture = DatabaseFixture::new();
    let path = fixture.0.join("journal.sqlite");
    let app = router(AppState::new(rusqlite::Connection::open(&path).unwrap(), TOKEN).unwrap());
    let db = rusqlite::Connection::open(&path).unwrap();
    let mut workspace = draft(&app).await;
    workspace["rules"] = json!([
        {"id":"disabled","category":"rest","app":"org.example.reader","enabled":false},
        {"id":"both","category":"learning","app":"org.example.reader","title_contains":"RUST"},
        {"id":"fallback","category":"work","app":"org.example.reader"}
    ]);
    assert_eq!(
        call(&app, "/v1/workspace", "PUT", workspace.clone())
            .await
            .0,
        StatusCode::OK
    );
    let now = chrono::Utc::now().timestamp_millis();
    for (id, app_name, edited, title) in [
        ("both", "org.example.reader", false, "A Rust guide"),
        ("fallback", "org.example.reader", false, "Different title"),
        ("upper", "ORG.EXAMPLE.READER", false, "A Rust guide"),
        ("manual", "org.example.reader", true, "A Rust guide"),
    ] {
        assert_eq!(call(&app, "/v1/activities", "POST", json!({"activities":[{"id":id,"start_ms":now-600_000,"end_ms":now-300_000,"app":app_name,"category":"rest"}]})).await.0, StatusCode::OK);
        if edited {
            assert_eq!(
                call(
                    &app,
                    "/v1/cards",
                    "POST",
                    json!({"id":id,"title":"A Rust guide","summary":"","category":"rest"})
                )
                .await
                .0,
                StatusCode::NO_CONTENT
            );
        } else {
            // Simulate the persisted derived note that production classifies.
            db.execute(
                "INSERT INTO notes VALUES(?,?,'','\"rest\"',0)",
                rusqlite::params![id, title],
            )
            .unwrap();
        }
        let (_, preview) = call(&app, PATH, "POST", json!({"workspace":workspace,"sample":{"app":app_name,"title":title,"category":"rest","edited":edited}})).await;
        let (_, timeline) = call(
            &app,
            &format!("/v1/timeline?from_ms={}&to_ms={now}", now - 3_600_000),
            "GET",
            Value::Null,
        )
        .await;
        let card = timeline["cards"]
            .as_array()
            .unwrap()
            .iter()
            .find(|card| card["id"] == id)
            .unwrap();
        assert_eq!(preview["category"], card["category"]);
    }
}

#[tokio::test]
async fn preview_validates_drafts_samples_and_strict_schemas() {
    let app = app();
    let workspace = draft(&app).await;
    let valid = json!({"workspace":workspace,"sample":sample()});
    let mut invalid_samples = Vec::new();
    for app in [
        "".to_owned(),
        "x".repeat(201),
        "contains space".to_owned(),
        "org.example/reader".to_owned(),
        "應用".to_owned(),
    ] {
        let mut s = sample();
        s["app"] = json!(app);
        invalid_samples.push(s);
    }
    for title in [
        "x".repeat(201),
        "line\nbreak".into(),
        "tab\t".into(),
        "null\0".into(),
        "control\u{7f}".into(),
    ] {
        let mut s = sample();
        s["title"] = json!(title);
        invalid_samples.push(s);
    }
    let mut missing_category = sample();
    missing_category["category"] = json!("custom_missing");
    invalid_samples.push(missing_category);
    for sample in invalid_samples {
        assert_eq!(
            call(
                &app,
                PATH,
                "POST",
                json!({"workspace":workspace,"sample":sample})
            )
            .await
            .0,
            StatusCode::BAD_REQUEST
        );
    }
    // Character bounds permit a 200-character Unicode title and the maximum app length.
    let mut boundary = sample();
    boundary["title"] = json!("學".repeat(200));
    boundary["app"] = json!("x".repeat(200));
    assert_eq!(
        call(
            &app,
            PATH,
            "POST",
            json!({"workspace":workspace,"sample":boundary})
        )
        .await
        .0,
        StatusCode::OK
    );
    let mut custom = workspace.clone();
    custom["categories"]
        .as_array_mut()
        .unwrap()
        .push(json!({"id":"custom_reading","name":"Reading","color":"#abcdef","role":"focus"}));
    let mut custom_sample = sample();
    custom_sample["category"] = json!("custom_reading");
    assert_eq!(
        call(
            &app,
            PATH,
            "POST",
            json!({"workspace":custom,"sample":custom_sample})
        )
        .await
        .0,
        StatusCode::OK
    );
    for field in [
        "onboarding_version",
        "focus_minutes",
        "distraction_minutes",
        "categories",
        "rules",
    ] {
        let mut invalid = workspace.clone();
        invalid[field] = match field {
            "onboarding_version" => json!(2),
            "focus_minutes" | "distraction_minutes" => json!(1441),
            "categories" => json!([]),
            _ => json!([{"id":"invalid rule id","category":"work","app":"org.example.reader"}]),
        };
        let preview = call(
            &app,
            PATH,
            "POST",
            json!({"workspace":invalid,"sample":sample()}),
        )
        .await
        .0;
        let save = call(&app, "/v1/workspace", "PUT", invalid).await.0;
        assert_eq!(preview, StatusCode::BAD_REQUEST);
        assert_eq!(preview, save);
    }
    for (section, field) in [
        ("", "unexpected"),
        ("sample", "text"),
        ("workspace", "unexpected"),
    ] {
        let mut unknown = valid.clone();
        if section.is_empty() {
            unknown[field] = json!(true);
        } else {
            unknown[section][field] = json!(true);
        }
        assert_eq!(
            call(&app, PATH, "POST", unknown).await.0,
            StatusCode::UNPROCESSABLE_ENTITY
        );
    }
    let mut wrong_type = valid;
    wrong_type["sample"]["edited"] = json!("true");
    assert_eq!(
        call(&app, PATH, "POST", wrong_type).await.0,
        StatusCode::UNPROCESSABLE_ENTITY
    );
}

#[tokio::test]
async fn preview_authenticates_before_parsing_and_retains_body_and_device_scopes() {
    let app = app();
    let workspace = draft(&app).await;
    let body = json!({"workspace":workspace,"sample":sample()}).to_string();
    let mut keys = Vec::new();
    for scope in ["journal", "collector"] {
        let (status, credential) = call(
            &app,
            "/v1/devices",
            "POST",
            json!({"label":"Synthetic preview test","scope":scope,"days":1}),
        )
        .await;
        assert_eq!(status, StatusCode::OK);
        keys.push(credential["token"].as_str().unwrap().to_owned());
    }
    for token in [None, Some("invalid"), Some(keys[1].as_str())] {
        for body in [body.clone(), "{invalid json".into(), "x".repeat(65_537)] {
            assert_eq!(
                raw(&app, PATH, "POST", token, body).await.0,
                StatusCode::UNAUTHORIZED
            );
        }
    }
    for token in [TOKEN, keys[0].as_str()] {
        assert_eq!(
            raw(&app, PATH, "POST", Some(token), body.clone()).await.0,
            StatusCode::OK
        );
        assert_eq!(
            raw(&app, PATH, "POST", Some(token), "{invalid json".into())
                .await
                .0,
            StatusCode::BAD_REQUEST
        );
        assert_eq!(
            raw(&app, PATH, "POST", Some(token), "x".repeat(65_537))
                .await
                .0,
            StatusCode::PAYLOAD_TOO_LARGE
        );
    }
}

struct DatabaseFixture(std::path::PathBuf);
impl DatabaseFixture {
    fn new() -> Self {
        static NEXT: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
        let path = std::env::temp_dir().join(format!(
            "pocketlog-preview-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, std::sync::atomic::Ordering::Relaxed)
        ));
        std::fs::create_dir(&path).unwrap();
        Self(path)
    }
}
impl Drop for DatabaseFixture {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.0);
    }
}

fn snapshot(db: &rusqlite::Connection) -> Vec<(String, Vec<Vec<rusqlite::types::Value>>)> {
    let tables: Vec<String> = db
        .prepare("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")
        .unwrap()
        .query_map([], |row| row.get(0))
        .unwrap()
        .collect::<Result<_, _>>()
        .unwrap();
    tables
        .into_iter()
        .map(|table| {
            let mut statement = db
                .prepare(&format!(
                    "SELECT * FROM \"{}\" ORDER BY rowid",
                    table.replace('"', "\"\"")
                ))
                .unwrap();
            let columns = statement.column_count();
            let rows = statement
                .query_map([], |row| {
                    (0..columns)
                        .map(|i| row.get(i))
                        .collect::<Result<Vec<rusqlite::types::Value>, _>>()
                })
                .unwrap()
                .collect::<Result<_, _>>()
                .unwrap();
            (table, rows)
        })
        .collect()
}

#[tokio::test]
async fn preview_neither_changes_persisted_data_nor_prunes_expired_journal_records() {
    let fixture = DatabaseFixture::new();
    let path = fixture.0.join("journal.sqlite");
    let app = router(AppState::new(rusqlite::Connection::open(&path).unwrap(), TOKEN).unwrap());
    let mut workspace = draft(&app).await;
    workspace["categories"]
        .as_array_mut()
        .unwrap()
        .push(json!({"id":"custom_reading","name":"Reading","color":"#abcdef","role":"focus"}));
    let (status, saved) = call(&app, "/v1/workspace", "PUT", workspace).await;
    assert_eq!(status, StatusCode::OK);
    let db = rusqlite::Connection::open(&path).unwrap();
    // Expired rows would be removed by any call to the shared prune operation.
    // A saved category in use would prevent saving the preview's draft removal.
    db.execute_batch("INSERT INTO activities VALUES('synthetic-expired',1,2,'org.example.reader','\"custom_reading\"');
        INSERT INTO notes VALUES('synthetic-expired','Synthetic title','Synthetic summary','\"custom_reading\"',1);
        INSERT INTO reflections VALUES('synthetic-reflection',1,'Synthetic reflection');
        INSERT INTO observations(id,at_ms,app,text,consent) VALUES('synthetic-observation',1,'org.example.reader','Synthetic context',0);
        INSERT INTO receipts VALUES('synthetic-receipt',1,'synthetic-hash');").unwrap();
    let before = snapshot(&db);
    let mut unsaved = saved.clone();
    unsaved["revision"] = json!(0); // A stale revision is allowed for a pure preview.
    unsaved["focus_minutes"] = json!(1);
    unsaved["categories"]
        .as_array_mut()
        .unwrap()
        .retain(|c| c["id"] != "custom_reading");
    unsaved["rules"] =
        json!([{"id":"draft-only","app":"org.example.reader","category":"learning"}]);
    let request = json!({"workspace":unsaved,"sample":sample()});
    for _ in 0..3 {
        let (status, result) = call(&app, PATH, "POST", request.clone()).await;
        assert_eq!(status, StatusCode::OK);
        assert_eq!(result["matched_rule_id"], "draft-only");
    }
    assert_eq!(before, snapshot(&db));
    assert_eq!(
        call(&app, "/v1/workspace", "GET", Value::Null).await.1,
        saved
    );
    assert_eq!(
        call(&app, "/v1/workspace", "PUT", unsaved.clone()).await.0,
        StatusCode::CONFLICT
    );
    unsaved["revision"] = saved["revision"].clone();
    // The expired category is pruned during save, so use a current activity to
    // verify that save still enforces its category-in-use conflict separately.
    let now = chrono::Utc::now().timestamp_millis();
    db.execute(
        "UPDATE activities SET start_ms=?,end_ms=? WHERE id='synthetic-expired'",
        [now - 2000, now - 1000],
    )
    .unwrap();
    assert_eq!(
        call(&app, "/v1/workspace", "PUT", unsaved).await.0,
        StatusCode::CONFLICT
    );
}

#[tokio::test]
async fn owner_preview_does_not_require_any_database_table() {
    let fixture = DatabaseFixture::new();
    let path = fixture.0.join("journal.sqlite");
    let app = router(AppState::new(rusqlite::Connection::open(&path).unwrap(), TOKEN).unwrap());
    let workspace = draft(&app).await;
    let db = rusqlite::Connection::open(&path).unwrap();
    for (table, _) in snapshot(&db) {
        db.execute(
            &format!("DROP TABLE \"{}\"", table.replace('"', "\"\"")),
            [],
        )
        .unwrap();
    }
    assert_eq!(
        call(
            &app,
            PATH,
            "POST",
            json!({"workspace":workspace,"sample":sample()})
        )
        .await
        .0,
        StatusCode::OK
    );
}
