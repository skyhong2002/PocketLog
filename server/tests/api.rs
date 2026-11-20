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
fn app() -> Router {
    router(AppState::new(rusqlite::Connection::open_in_memory().unwrap(), TOKEN).unwrap())
}
async fn call(
    app: &Router,
    method: &str,
    path: &str,
    body: Value,
    authenticated: bool,
) -> (StatusCode, Value) {
    let mut request = Request::builder()
        .method(method)
        .uri(path)
        .header("content-type", "application/json");
    if authenticated {
        request = request.header("authorization", format!("Bearer {TOKEN}"));
    }
    let response = app
        .clone()
        .oneshot(request.body(Body::from(body.to_string())).unwrap())
        .await
        .unwrap();
    assert_eq!(response.headers()["cache-control"], "no-store");
    let status = response.status();
    let body = response.into_body().collect().await.unwrap().to_bytes();
    (status, serde_json::from_slice(&body).unwrap_or(Value::Null))
}
fn event(id: &str) -> Value {
    let now = chrono::Utc::now().timestamp_millis();
    json!({"id":id,"start_ms":now-600_000,"end_ms":now-300_000,"app":"org.example.reader","category":"learning"})
}
#[tokio::test]
async fn all_journal_routes_require_authentication() {
    let app = app();
    for (method, path) in [
        ("POST", "/v1/activities"),
        ("POST", "/v1/checkins"),
        ("GET", "/v1/timeline"),
        ("DELETE", "/v1/journal"),
    ] {
        assert_eq!(
            call(&app, method, path, json!({}), false).await.0,
            StatusCode::UNAUTHORIZED
        );
    }
    assert_eq!(
        call(&app, "GET", "/healthz", Value::Null, false).await.0,
        StatusCode::OK
    );
    assert!(AppState::new(rusqlite::Connection::open_in_memory().unwrap(), "password").is_err());
}
#[tokio::test]
async fn batch_retry_is_idempotent_and_conflicts_are_atomic() {
    let app = app();
    let a = event("first");
    let batch = json!({"activities":[a.clone()]});
    assert_eq!(
        call(&app, "POST", "/v1/activities", batch.clone(), true)
            .await
            .1["accepted"],
        1
    );
    assert_eq!(
        call(&app, "POST", "/v1/activities", batch, true).await.1["accepted"],
        0
    );
    let mut conflict = a.clone();
    conflict["app"] = json!("another.app");
    let second = event("second");
    assert_eq!(
        call(
            &app,
            "POST",
            "/v1/activities",
            json!({"activities":[second,conflict]}),
            true
        )
        .await
        .0,
        StatusCode::CONFLICT
    );
    let path = format!(
        "/v1/timeline?from_ms={}&to_ms={}",
        a["start_ms"].as_i64().unwrap() + 60_000,
        a["end_ms"].as_i64().unwrap() - 60_000
    );
    let (status, timeline) = call(&app, "GET", &path, Value::Null, true).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(timeline["activities"].as_array().unwrap().len(), 1);
    assert_eq!(
        timeline["activities"][0]["start_ms"],
        a["start_ms"].as_i64().unwrap() + 60_000
    );
    assert_eq!(
        timeline["activities"][0]["end_ms"],
        a["end_ms"].as_i64().unwrap() - 60_000
    );
}
#[tokio::test]
async fn rejects_raw_screen_fields_unbounded_and_expired_data() {
    let app = app();
    let mut raw = event("raw");
    raw["screenshot"] = json!("PRIVATE");
    assert_eq!(
        call(
            &app,
            "POST",
            "/v1/activities",
            json!({"activities":[raw]}),
            true
        )
        .await
        .0,
        StatusCode::UNPROCESSABLE_ENTITY
    );
    for field in ["start_ms", "end_ms"] {
        let mut a = event("invalid");
        a[field] = json!(0);
        assert_eq!(
            call(
                &app,
                "POST",
                "/v1/activities",
                json!({"activities":[a]}),
                true
            )
            .await
            .0,
            StatusCode::BAD_REQUEST
        );
    }
    assert_eq!(
        call(
            &app,
            "POST",
            "/v1/activities",
            json!({"activities":vec![event("many");101]}),
            true
        )
        .await
        .0,
        StatusCode::BAD_REQUEST
    );
    let request = Request::builder()
        .method("POST")
        .uri("/v1/activities")
        .header("authorization", format!("Bearer {TOKEN}"))
        .header("content-type", "application/json");
    let response = app
        .oneshot(request.body(Body::from("x".repeat(65537))).unwrap())
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::PAYLOAD_TOO_LARGE);
}
#[tokio::test]
async fn explicit_feelings_are_private_and_deletable() {
    let app = app();
    let now = chrono::Utc::now().timestamp_millis();
    let c = json!({"id":"mood-1","at_ms":now-1000,"mood":4,"energy":2});
    assert_eq!(
        call(&app, "POST", "/v1/checkins", c.clone(), true).await.0,
        StatusCode::NO_CONTENT
    );
    assert_eq!(
        call(&app, "POST", "/v1/checkins", c, true).await.0,
        StatusCode::NO_CONTENT
    );
    assert_eq!(
        call(
            &app,
            "POST",
            "/v1/checkins",
            json!({"id":"bad","at_ms":now,"mood":0,"energy":6}),
            true
        )
        .await
        .0,
        StatusCode::BAD_REQUEST
    );
    let path = format!("/v1/timeline?from_ms={}&to_ms={}", now - 3_600_000, now + 1);
    assert_eq!(
        call(&app, "GET", &path, Value::Null, true).await.1["checkins"]
            .as_array()
            .unwrap()
            .len(),
        1
    );
    assert_eq!(
        call(&app, "DELETE", "/v1/journal", Value::Null, true)
            .await
            .0,
        StatusCode::NO_CONTENT
    );
    assert_eq!(
        call(&app, "GET", &path, Value::Null, true).await.1["checkins"],
        json!([])
    );
}
#[tokio::test]
async fn journal_is_retained_permanently_and_errors_do_not_echo_secrets() {
    let path = std::env::temp_dir().join(format!(
        "pocketlog-retention-{}-{}.sqlite",
        std::process::id(),
        chrono::Utc::now().timestamp_nanos_opt().unwrap()
    ));
    let state = AppState::new(rusqlite::Connection::open(&path).unwrap(), TOKEN).unwrap();
    let inspect = rusqlite::Connection::open(&path).unwrap();
    inspect
        .execute(
            "INSERT INTO activities VALUES ('expired',0,1,'example.app','\"work\"')",
            [],
        )
        .unwrap();
    inspect
        .execute("INSERT INTO checkins VALUES ('expired',1,3,3)", [])
        .unwrap();
    state.prune().await.unwrap();
    let remaining: i64 = inspect
        .query_row(
            "SELECT (SELECT COUNT(*) FROM activities)+(SELECT COUNT(*) FROM checkins)",
            [],
            |r| r.get(0),
        )
        .unwrap();
    assert_eq!(remaining, 2);
    let app = router(state);
    let (status, old) = call(
        &app,
        "GET",
        "/v1/timeline?from_ms=0&to_ms=2",
        Value::Null,
        true,
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(old["activities"].as_array().unwrap().len(), 1);
    let (status, error) = call(
        &app,
        "GET",
        "/v1/timeline?from_ms=-1&to_ms=1",
        Value::Null,
        true,
    )
    .await;
    assert_eq!(status, StatusCode::BAD_REQUEST);
    assert!(!error.to_string().contains(TOKEN));
    drop(app);
    drop(inspect);
    std::fs::remove_file(path).unwrap();
}

#[tokio::test]
async fn private_context_requires_consent_is_idempotent_and_never_read_back() {
    let app = app();
    let now = chrono::Utc::now().timestamp_millis();
    let observation = json!({"id":"context-1","at_ms":now-1000,"app":"org.example.reader","text":"PRIVATE CONTEXT","analysis_consent":true});
    for _ in 0..2 {
        assert_eq!(
            call(&app, "POST", "/v1/observations", observation.clone(), true)
                .await
                .0,
            StatusCode::NO_CONTENT
        );
    }
    let mut conflict = observation.clone();
    conflict["text"] = json!("other");
    assert_eq!(
        call(&app, "POST", "/v1/observations", conflict, true)
            .await
            .0,
        StatusCode::CONFLICT
    );
    let mut denied = observation.clone();
    denied["analysis_consent"] = json!(false);
    assert_eq!(
        call(&app, "POST", "/v1/observations", denied, true).await.0,
        StatusCode::BAD_REQUEST
    );
    let mut expired = observation.clone();
    // Deferred (charging-time) uploads are accepted for three days, not longer.
    expired["at_ms"] = json!(now - 3 * 86_400_000 - 1);
    assert_eq!(
        call(&app, "POST", "/v1/observations", expired, true)
            .await
            .0,
        StatusCode::BAD_REQUEST
    );
    let mut large = observation;
    large["text"] = json!("x".repeat(2001));
    assert_eq!(
        call(&app, "POST", "/v1/observations", large, true).await.0,
        StatusCode::BAD_REQUEST
    );
    let path = format!("/v1/timeline?from_ms={}&to_ms={}", now - 3600000, now);
    let data = call(&app, "GET", &path, Value::Null, true).await.1;
    assert!(!data.to_string().contains("PRIVATE CONTEXT"));
    assert_eq!(
        call(&app, "GET", "/v1/status", Value::Null, true).await.1["pending_context"],
        1
    );
    call(&app, "DELETE", "/v1/journal", Value::Null, true).await;
    assert_eq!(
        call(&app, "GET", "/v1/status", Value::Null, true).await.1["pending_context"],
        0
    );
}

#[tokio::test]
async fn corrections_reflections_and_export_window_are_private_and_deletable() {
    let app = app();
    let activity = event("edit-me");
    let now = chrono::Utc::now().timestamp_millis();
    call(
        &app,
        "POST",
        "/v1/activities",
        json!({"activities":[activity]}),
        true,
    )
    .await;
    let edit = json!({"id":"edit-me","title":"我的活動","summary":"我自己的修正","category":"social_media"});
    assert_eq!(
        call(&app, "POST", "/v1/cards", edit, true).await.0,
        StatusCode::NO_CONTENT
    );
    let reflection = json!({"id":"reflection-today","at_ms":now-1000,"text":"今天很充實"});
    for _ in 0..2 {
        assert_eq!(
            call(&app, "POST", "/v1/reflections", reflection.clone(), true)
                .await
                .0,
            StatusCode::NO_CONTENT
        );
    }
    let path = format!("/v1/timeline?from_ms={}&to_ms={}", now - 3600000, now);
    let data = call(&app, "GET", &path, Value::Null, true).await.1;
    assert_eq!(data["cards"][0]["source"], "user");
    assert_eq!(data["cards"][0]["category"], "social_media");
    assert_eq!(data["reflections"].as_array().unwrap().len(), 1);
    call(
        &app,
        "POST",
        "/v1/reflections",
        json!({"id":"reflection-today","at_ms":now-1000,"text":""}),
        true,
    )
    .await;
    assert_eq!(
        call(&app, "GET", &path, Value::Null, true).await.1["reflections"],
        json!([])
    );
    call(&app, "DELETE", "/v1/journal", Value::Null, true).await;
    assert_eq!(
        call(&app, "GET", &path, Value::Null, true).await.1["cards"],
        json!([])
    );
}

#[tokio::test]
async fn new_routes_authenticate_before_parsing() {
    let app = app();
    for (method, path) in [
        ("POST", "/v1/observations"),
        ("POST", "/v1/cards"),
        ("POST", "/v1/reflections"),
        ("GET", "/v1/status"),
        ("GET", "/v1/storage"),
        ("PUT", "/v1/storage"),
        ("PUT", "/v1/ai/auto"),
    ] {
        assert_eq!(
            call(
                &app,
                method,
                path,
                json!({"secret":"not valid schema"}),
                false
            )
            .await
            .0,
            StatusCode::UNAUTHORIZED
        );
    }
}

#[tokio::test]
async fn image_input_requires_independent_consent_and_size_bound() {
    let app = app();
    let now = chrono::Utc::now().timestamp_millis();
    let mut image = json!({"id":"image-1","at_ms":now-1000,"app":"org.example.reader","text":"","analysis_consent":true,"jpeg_base64":"AAAA"});
    assert_eq!(
        call(&app, "POST", "/v1/observations", image.clone(), true)
            .await
            .0,
        StatusCode::BAD_REQUEST
    );
    image["image_consent"] = json!(true);
    // The API stores bounded opaque input. JPEG decoding belongs only to the separate worker.
    assert_eq!(
        call(&app, "POST", "/v1/observations", image.clone(), true)
            .await
            .0,
        StatusCode::NO_CONTENT
    );
    // About 300 KiB of JPEG fits the dedicated observation body limit.
    image["id"] = json!("image-2");
    image["jpeg_base64"] = json!("A".repeat(400_000));
    assert_eq!(
        call(&app, "POST", "/v1/observations", image.clone(), true)
            .await
            .0,
        StatusCode::NO_CONTENT
    );
    image["id"] = json!("image-3");
    image["jpeg_base64"] = json!("A".repeat(400_001));
    assert_eq!(
        call(&app, "POST", "/v1/observations", image, true).await.0,
        StatusCode::BAD_REQUEST
    );
}

#[tokio::test]
async fn overview_clips_intervals_and_respects_corrected_categories() {
    let app = app();
    let a = event("overview-me");
    let from = a["start_ms"].as_i64().unwrap() + 60_000;
    let to = a["end_ms"].as_i64().unwrap() - 60_000;
    call(
        &app,
        "POST",
        "/v1/activities",
        json!({"activities":[a]}),
        true,
    )
    .await;
    call(&app,"POST","/v1/cards",json!({"id":"overview-me","title":"閱讀文件","summary":"private note","category":"news_reading"}),true).await;
    let path = format!("/v1/overview?from_ms={from}&to_ms={to}");
    assert_eq!(
        call(&app, "GET", &path, Value::Null, false).await.0,
        StatusCode::UNAUTHORIZED
    );
    let data = call(&app, "GET", &path, Value::Null, true).await.1;
    assert_eq!(data["categories"][0]["category"], "news_reading");
    assert_eq!(data["categories"][0]["duration_ms"], to - from);
    assert_eq!(data["title_keywords"][0][0], "閱讀文件");
    assert!(!data.to_string().contains("private note"));
}

#[tokio::test]
async fn multibyte_notes_cannot_exceed_the_android_response_budget() {
    let path = std::env::temp_dir().join(format!(
        "pocketlog-response-bound-{}-{}.sqlite",
        std::process::id(),
        chrono::Utc::now().timestamp_nanos_opt().unwrap()
    ));
    let state = AppState::new(rusqlite::Connection::open(&path).unwrap(), TOKEN).unwrap();
    let mut inspect = rusqlite::Connection::open(&path).unwrap();
    let now = chrono::Utc::now().timestamp_millis();
    let tx = inspect.transaction().unwrap();
    for i in 0..1000 {
        let id = format!("card-{i}");
        tx.execute(
            "INSERT INTO activities VALUES (?,?,?,'org.example.reader','\"learning\"')",
            rusqlite::params![id, now - 600000, now - 300000],
        )
        .unwrap();
        tx.execute(
            "INSERT INTO notes VALUES (?,?,?,'\"learning\"',1)",
            rusqlite::params![id, "漢".repeat(200), "字".repeat(1000)],
        )
        .unwrap();
    }
    tx.commit().unwrap();
    let app = router(state);
    let url = format!("/v1/timeline?from_ms={}&to_ms={now}", now - 3600000);
    let (status, data) = call(&app, "GET", &url, Value::Null, true).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(data["truncated"], true);
    assert!(serde_json::to_vec(&data).unwrap().len() <= 480 * 1024);
    drop(app);
    drop(inspect);
    std::fs::remove_file(path).unwrap();
}

struct PrivateDirectory(std::path::PathBuf);
impl PrivateDirectory {
    fn new() -> Self {
        Self(std::env::temp_dir().join(format!(
            "pocketlog-ai-{}-{}",
            std::process::id(),
            chrono::Utc::now().timestamp_nanos_opt().unwrap()
        )))
    }
}
impl Drop for PrivateDirectory {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.0);
    }
}
fn configured_app() -> (Router, PrivateDirectory) {
    let directory = PrivateDirectory::new();
    let state = AppState::new(rusqlite::Connection::open_in_memory().unwrap(), TOKEN)
        .unwrap()
        .with_ai_settings(&directory.0)
        .unwrap();
    (router(state), directory)
}
#[tokio::test]
async fn byo_settings_are_owner_only_redacted_persistent_and_can_be_deleted() {
    use std::os::unix::fs::PermissionsExt;
    let (app, directory) = configured_app();
    for method in ["GET", "PUT", "DELETE"] {
        assert_eq!(
            call(&app, method, "/v1/ai", json!({}), false).await.0,
            StatusCode::UNAUTHORIZED
        );
    }
    assert_eq!(
        call(&app, "POST", "/v1/ai/test", json!({}), false).await.0,
        StatusCode::UNAUTHORIZED
    );
    let config = json!({"endpoint":"https://api.example.com/v1/chat/completions","model":"my-model","api_key":"dummy-private-provider-key","image_support":false,"response_format":"json_schema"});
    let (status, response) = call(&app, "PUT", "/v1/ai", config, true).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(response["has_key"], true);
    assert!(!response.to_string().contains("dummy-private-provider-key"));
    let first = response["provider_id"].as_str().unwrap().to_owned();
    let read = call(&app, "GET", "/v1/ai", Value::Null, true).await.1;
    assert_eq!(read["provider_id"], first);
    assert_eq!(read["probe_provider_binding"], true);
    assert!(read.get("key").is_none() && read.get("api_key").is_none());
    assert_eq!(
        std::fs::metadata(directory.0.join("provider.json"))
            .unwrap()
            .permissions()
            .mode()
            & 0o777,
        0o600
    );
    assert_eq!(
        std::fs::metadata(&directory.0)
            .unwrap()
            .permissions()
            .mode()
            & 0o777,
        0o700
    );
    let changed = call(
        &app,
        "PUT",
        "/v1/ai",
        json!({"endpoint":"https://api.example.com/v1/chat/completions","model":"another-model"}),
        true,
    )
    .await
    .1;
    assert_eq!(changed["has_key"], true);
    assert_ne!(changed["provider_id"], first);
    let other = call(
        &app,
        "PUT",
        "/v1/ai",
        json!({"endpoint":"https://different.example.com/v1/chat/completions","model":"my-model"}),
        true,
    )
    .await
    .1;
    assert_eq!(other["has_key"], false);
    assert_eq!(
        call(&app, "DELETE", "/v1/ai", Value::Null, true).await.0,
        StatusCode::NO_CONTENT
    );
    assert!(
        !std::fs::read_to_string(directory.0.join("provider.json"))
            .unwrap()
            .contains("dummy-private-provider-key")
    );
    assert_eq!(
        call(&app, "GET", "/v1/ai", Value::Null, true).await.1["enabled"],
        false
    );
}
#[tokio::test]
async fn automatic_summaries_are_bound_to_the_current_provider() {
    let (app, _directory) = configured_app();
    let config = json!({"endpoint":"https://api.example.com/v1/chat/completions","model":"my-model","api_key":"dummy-private-provider-key"});
    let first = call(&app, "PUT", "/v1/ai", config.clone(), true).await.1;
    assert_eq!(first["auto_summarize"], false);
    let id = first["provider_id"].as_str().unwrap().to_owned();
    let (status, enabled) = call(
        &app,
        "PUT",
        "/v1/ai/auto",
        json!({"provider_id":id,"enabled":true}),
        true,
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(enabled["auto_summarize"], true);
    assert_eq!(
        call(&app, "GET", "/v1/ai", Value::Null, true).await.1["auto_summarize"],
        true
    );
    // Reconfiguring creates a new provider identity that starts without the switch.
    let second = call(&app, "PUT", "/v1/ai", config, true).await.1;
    assert_eq!(second["auto_summarize"], false);
    assert_eq!(
        call(
            &app,
            "PUT",
            "/v1/ai/auto",
            json!({"provider_id":id,"enabled":true}),
            true
        )
        .await
        .0,
        StatusCode::CONFLICT
    );
    assert_eq!(
        call(
            &app,
            "PUT",
            "/v1/ai/auto",
            json!({"provider_id":"not-hex","enabled":true}),
            true
        )
        .await
        .0,
        StatusCode::BAD_REQUEST
    );
}
#[tokio::test]
async fn storage_budget_is_versioned_bounded_and_reported() {
    let app = app();
    let (status, usage) = call(&app, "GET", "/v1/storage", Value::Null, true).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(usage["limit_bytes"], 10_i64 * 1024 * 1024 * 1024);
    assert_eq!(usage["image_count"], 0);
    let revision = usage["revision"].clone();
    for limit in [0_i64, 1024, 2 * 1024 * 1024 * 1024 * 1024] {
        assert_eq!(
            call(
                &app,
                "PUT",
                "/v1/storage",
                json!({"revision":revision,"limit_bytes":limit}),
                true
            )
            .await
            .0,
            StatusCode::BAD_REQUEST
        );
    }
    let (status, saved) = call(
        &app,
        "PUT",
        "/v1/storage",
        json!({"revision":revision,"limit_bytes":20_i64 * 1024 * 1024 * 1024}),
        true,
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(saved["limit_bytes"], 20_i64 * 1024 * 1024 * 1024);
    assert_eq!(
        call(
            &app,
            "PUT",
            "/v1/storage",
            json!({"revision":revision,"limit_bytes":5_i64 * 1024 * 1024 * 1024}),
            true
        )
        .await
        .0,
        StatusCode::CONFLICT
    );
    let now = chrono::Utc::now().timestamp_millis();
    let observation = json!({"id":"stored-image","at_ms":now-1000,"app":"org.example.reader","text":"","analysis_consent":true,"image_consent":true,"jpeg_base64":"AAAA"});
    call(&app, "POST", "/v1/observations", observation, true).await;
    let usage = call(&app, "GET", "/v1/storage", Value::Null, true).await.1;
    assert_eq!(
        (usage["image_count"].clone(), usage["image_bytes"].clone()),
        (json!(1), json!(4))
    );
    let status = call(&app, "GET", "/v1/status", Value::Null, true).await.1;
    assert_eq!(status["journal_retention"], "permanent");
    assert_eq!(status["storage"]["image_count"], 1);
}
#[tokio::test]
async fn byo_settings_refuse_credential_urls_insecure_hosts_and_header_injection() {
    let (app, _directory) = configured_app();
    for endpoint in [
        "http://remote.example/v1/chat/completions",
        "https://key@api.example/v1",
        "https://api.example/v1?key=secret",
        "https://api.example/v1#secret",
    ] {
        let (status, body) = call(
            &app,
            "PUT",
            "/v1/ai",
            json!({"endpoint":endpoint,"model":"test","api_key":"dummy"}),
            true,
        )
        .await;
        assert_eq!(status, StatusCode::BAD_REQUEST);
        assert!(!body.to_string().contains(endpoint));
    }
    assert_eq!(
        call(
            &app,
            "PUT",
            "/v1/ai",
            json!({"endpoint":"https://api.example/v1","model":"test","api_key":"bad\r\nheader"}),
            true
        )
        .await
        .0,
        StatusCode::BAD_REQUEST
    );
}
#[tokio::test]
async fn existing_observation_schema_migrates_and_legacy_receipts_still_replay() {
    use sha2::{Digest, Sha256};
    let directory = PrivateDirectory::new();
    let path = directory.0.join("legacy.sqlite");
    std::fs::create_dir_all(&directory.0).unwrap();
    let db = rusqlite::Connection::open(&path).unwrap();
    db.execute_batch("CREATE TABLE observations(id TEXT PRIMARY KEY,at_ms INTEGER NOT NULL,app TEXT NOT NULL,text TEXT NOT NULL,consent INTEGER NOT NULL,jpeg_base64 TEXT);CREATE TABLE receipts(id TEXT PRIMARY KEY,at_ms INTEGER NOT NULL,hash TEXT NOT NULL);").unwrap();
    let at = chrono::Utc::now().timestamp_millis() - 1000;
    let canonical = format!(
        r#"{{"id":"legacy","at_ms":{at},"app":"app.example","text":"synthetic legacy","analysis_consent":true,"external_ai_consent":false,"jpeg_base64":null,"image_consent":false}}"#
    );
    let hash: String = Sha256::digest(canonical.as_bytes())
        .iter()
        .map(|b| format!("{b:02x}"))
        .collect();
    db.execute(
        "INSERT INTO observations VALUES('legacy',?,'app.example','synthetic legacy',1,NULL)",
        [at],
    )
    .unwrap();
    db.execute(
        "INSERT INTO receipts VALUES('legacy',?,?)",
        rusqlite::params![at, hash],
    )
    .unwrap();
    let app = router(AppState::new(db, TOKEN).unwrap());
    let body: Value = serde_json::from_str(&canonical).unwrap();
    assert_eq!(
        call(&app, "POST", "/v1/observations", body, true).await.0,
        StatusCode::NO_CONTENT
    );
    let check = rusqlite::Connection::open(path).unwrap();
    let (text, provider): (String, Option<String>) = check
        .query_row(
            "SELECT text,provider_id FROM observations WHERE id='legacy'",
            [],
            |r| Ok((r.get(0)?, r.get(1)?)),
        )
        .unwrap();
    assert_eq!(text, "synthetic legacy");
    assert!(provider.is_none());
    assert_eq!(
        call(&app, "GET", "/v1/status", json!({}), true).await.1["pending_context"],
        1
    );
}

#[tokio::test]
async fn shared_setup_is_versioned_validated_and_never_grants_collection_consent() {
    let app = app();
    assert_eq!(
        call(&app, "GET", "/v1/workspace", json!({}), false).await.0,
        StatusCode::UNAUTHORIZED
    );
    let mut settings = call(&app, "GET", "/v1/workspace", json!({}), true).await.1;
    assert_eq!(settings["onboarding_version"], 0);
    assert!(settings.get("capture_consent").is_none());
    settings["onboarding_version"] = json!(1);
    settings["focus_minutes"] = json!(90);
    let saved = call(&app, "PUT", "/v1/workspace", settings.clone(), true).await;
    assert_eq!(saved.0, StatusCode::OK);
    assert_eq!(saved.1["revision"], 1);
    assert_eq!(
        call(&app, "PUT", "/v1/workspace", settings, true).await.0,
        StatusCode::CONFLICT
    );
    let mut bad = saved.1.clone();
    bad["categories"][0]["color"] = json!("#badurl");
    assert_eq!(
        call(&app, "PUT", "/v1/workspace", bad, true).await.0,
        StatusCode::BAD_REQUEST
    );
    let mut bad = saved.1.clone();
    bad["categories"][1]["id"] = bad["categories"][0]["id"].clone();
    assert_eq!(
        call(&app, "PUT", "/v1/workspace", bad, true).await.0,
        StatusCode::BAD_REQUEST
    );
    assert_eq!(
        call(&app, "GET", "/v1/workspace", json!({}), true).await.1,
        saved.1
    );
}
#[tokio::test]
async fn browser_sessions_require_csrf_header_expire_on_logout_and_hide_owner_token() {
    let app = app();
    let request = Request::builder()
        .method("POST")
        .uri("/session")
        .header("content-type", "application/json")
        .body(Body::from(json!({"token":TOKEN}).to_string()))
        .unwrap();
    assert_eq!(
        app.clone().oneshot(request).await.unwrap().status(),
        StatusCode::FORBIDDEN
    );
    let request = Request::builder()
        .method("POST")
        .uri("/session")
        .header("content-type", "application/json")
        .header("x-pocketlog", "1")
        .body(Body::from(json!({"token":TOKEN}).to_string()))
        .unwrap();
    let response = app.clone().oneshot(request).await.unwrap();
    assert_eq!(response.status(), StatusCode::NO_CONTENT);
    let cookie = response.headers()["set-cookie"]
        .to_str()
        .unwrap()
        .to_owned();
    assert!(!cookie.contains(TOKEN));
    for flag in ["Secure", "HttpOnly", "SameSite=Strict", "Path=/"] {
        assert!(cookie.contains(flag));
    }
    let cookie = cookie.split(';').next().unwrap();
    for (method, path, csrf, site, expected) in [
        ("GET", "/v1/workspace", false, "same-origin", StatusCode::OK),
        (
            "DELETE",
            "/v1/journal",
            false,
            "same-origin",
            StatusCode::UNAUTHORIZED,
        ),
        (
            "DELETE",
            "/v1/journal",
            true,
            "cross-site",
            StatusCode::UNAUTHORIZED,
        ),
        (
            "DELETE",
            "/session",
            true,
            "same-origin",
            StatusCode::NO_CONTENT,
        ),
        (
            "GET",
            "/v1/workspace",
            false,
            "same-origin",
            StatusCode::UNAUTHORIZED,
        ),
    ] {
        let mut req = Request::builder()
            .method(method)
            .uri(path)
            .header("cookie", cookie)
            .header("sec-fetch-site", site);
        if csrf {
            req = req.header("x-pocketlog", "1");
        }
        assert_eq!(
            app.clone()
                .oneshot(req.body(Body::empty()).unwrap())
                .await
                .unwrap()
                .status(),
            expected
        );
    }
    let response = app
        .clone()
        .oneshot(Request::builder().uri("/").body(Body::empty()).unwrap())
        .await
        .unwrap();
    assert!(
        response.headers()["content-security-policy"]
            .to_str()
            .unwrap()
            .contains("frame-ancestors 'none'")
    );
    let bytes = response.into_body().collect().await.unwrap().to_bytes();
    assert!(!String::from_utf8_lossy(&bytes).contains(TOKEN));
}
#[tokio::test]
async fn review_records_are_private_bounded_idempotent_and_deleted_with_journal() {
    let app = app();
    let now = chrono::Utc::now().timestamp_millis();
    let body = json!({"id":"flow-one","at_ms":now-60000,"kind":"flow","text":"Synthetic focus topic","end_ms":now,"rating":null,"source":null});
    assert_eq!(
        call(&app, "POST", "/v1/reviews", body.clone(), false)
            .await
            .0,
        StatusCode::UNAUTHORIZED
    );
    for _ in 0..2 {
        assert_eq!(
            call(&app, "POST", "/v1/reviews", body.clone(), true)
                .await
                .0,
            StatusCode::NO_CONTENT
        );
    }
    let path = format!("/v1/reviews?from_ms={}&to_ms={now}", now - 120000);
    assert_eq!(
        call(&app, "GET", &path, json!({}), true).await.1["records"]
            .as_array()
            .unwrap()
            .len(),
        1
    );
    let mut bad = body.clone();
    bad["end_ms"] = json!(now - 120000);
    assert_eq!(
        call(&app, "POST", "/v1/reviews", bad, true).await.0,
        StatusCode::BAD_REQUEST
    );
    let mut bad = body;
    bad["at_ms"] = json!(now - 31 * 86400000_i64);
    assert_eq!(
        call(&app, "POST", "/v1/reviews", bad, true).await.0,
        StatusCode::BAD_REQUEST
    );
    call(&app, "DELETE", "/v1/journal", json!({}), true).await;
    assert!(
        call(&app, "GET", &path, json!({}), true).await.1["records"]
            .as_array()
            .unwrap()
            .is_empty()
    );
}
#[tokio::test]
async fn chat_refuses_missing_consent_and_stale_provider_before_outbound() {
    let (app, _directory) = configured_app();
    let now = chrono::Utc::now().timestamp_millis();
    let config = call(
        &app,
        "PUT",
        "/v1/ai",
        json!({"endpoint":"http://127.0.0.1:1/v1/chat/completions","model":"test"}),
        true,
    )
    .await
    .1;
    let mut q = json!({"question":"Summarize","from_ms":now-600000,"to_ms":now,"provider_id":config["provider_id"],"consent":false});
    assert_eq!(
        call(&app, "POST", "/v1/ask", q.clone(), true).await.0,
        StatusCode::BAD_REQUEST
    );
    q["consent"] = json!(true);
    q["provider_id"] = json!("old-provider");
    assert_eq!(
        call(&app, "POST", "/v1/ask", q, true).await.0,
        StatusCode::CONFLICT
    );
}

#[tokio::test]
async fn explicit_probe_is_synthetic_and_chat_sends_only_bounded_derived_cards() {
    use std::io::{Read, Write};
    let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let address = listener.local_addr().unwrap();
    let capture = std::thread::spawn(move || {
        let mut bodies = Vec::new();
        for index in 0..2 {
            let (mut stream, _) = listener.accept().unwrap();
            stream
                .set_read_timeout(Some(std::time::Duration::from_secs(5)))
                .unwrap();
            let mut bytes = Vec::new();
            let mut b = [0u8; 1];
            while !bytes.ends_with(b"\r\n\r\n") {
                stream.read_exact(&mut b).unwrap();
                bytes.push(b[0]);
                assert!(bytes.len() < 16384);
            }
            let headers = String::from_utf8(bytes).unwrap();
            assert!(
                headers
                    .to_lowercase()
                    .contains("authorization: bearer test-provider-key")
            );
            assert!(!headers.contains(TOKEN));
            let length: usize = headers
                .lines()
                .find_map(|line| {
                    line.to_lowercase()
                        .strip_prefix("content-length: ")
                        .map(str::to_owned)
                })
                .unwrap()
                .parse()
                .unwrap();
            let mut body = vec![0; length];
            stream.read_exact(&mut body).unwrap();
            bodies.push(String::from_utf8(body).unwrap());
            let content = if index == 0 {
                json!({"title":"測試","summary":"固定測試","category":"unsorted"}).to_string()
            } else {
                "依活動卡，你閱讀了參考資料。".into()
            };
            let output =
                json!({"choices":[{"finish_reason":"stop","message":{"content":content}}]})
                    .to_string();
            write!(stream,"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",output.len(),output).unwrap();
        }
        bodies
    });
    let (app, _directory) = configured_app();
    let now = chrono::Utc::now().timestamp_millis();
    call(
        &app,
        "POST",
        "/v1/activities",
        json!({"activities":[event("ask-card")]}),
        true,
    )
    .await;
    call(&app,"POST","/v1/cards",json!({"id":"ask-card","title":"Reading reference","summary":"Derived summary","category":"learning"}),true).await;
    call(
        &app,
        "POST",
        "/v1/reflections",
        json!({"id":"private-reflection","at_ms":now-1000,"text":"NEVER_SEND_REFLECTION"}),
        true,
    )
    .await;
    call(&app,"POST","/v1/observations",json!({"id":"raw","at_ms":now-500000,"app":"org.example.reader","text":"NEVER_SEND_RAW","analysis_consent":true}),true).await;
    let config=call(&app,"PUT","/v1/ai",json!({"endpoint":format!("http://{address}/v1/chat/completions"),"model":"test","api_key":"test-provider-key"}),true).await.1;
    assert_eq!(
        call(
            &app,
            "POST",
            "/v1/ai/test",
            json!({"provider_id":config["provider_id"]}),
            true
        )
        .await
        .0,
        StatusCode::OK
    );
    let result=call(&app,"POST","/v1/ask",json!({"question":"做了什麼？","from_ms":now-3600000,"to_ms":now,"provider_id":config["provider_id"],"consent":true}),true).await;
    assert_eq!(result.0, StatusCode::OK);
    assert_eq!(result.1["stored"], false);
    let bodies = capture.join().unwrap();
    assert!(bodies[0].contains("Synthetic connection test"));
    assert!(!bodies[0].contains("Reading reference"));
    assert!(bodies[1].contains("Reading reference"));
    assert!(bodies[1].contains("Derived summary"));
    for body in bodies {
        for private in [
            "NEVER_SEND_REFLECTION",
            "NEVER_SEND_RAW",
            TOKEN,
            "test-provider-key",
        ] {
            assert!(!body.contains(private));
        }
    }
}

#[tokio::test]
async fn custom_categories_rules_preserve_replays_and_manual_edits() {
    let app = app();
    let activity = event("rule-example");
    call(
        &app,
        "POST",
        "/v1/activities",
        json!({"activities":[activity.clone()]}),
        true,
    )
    .await;
    let mut prefs = call(&app, "GET", "/v1/workspace", json!({}), true).await.1;
    prefs["categories"]
        .as_array_mut()
        .unwrap()
        .push(json!({"id":"custom_reading","name":"深入閱讀","color":"#123abc","role":"focus"}));
    prefs["rules"] = json!([{"id":"reading","category":"custom_reading","app":"org.example.reader","title_contains":null,"enabled":true}]);
    assert_eq!(
        call(&app, "PUT", "/v1/workspace", prefs, true).await.0,
        StatusCode::OK
    );
    let now = chrono::Utc::now().timestamp_millis();
    let path = format!("/v1/timeline?from_ms={}&to_ms={now}", now - 3600000);
    assert_eq!(
        call(&app, "GET", &path, json!({}), true).await.1["cards"][0]["category"],
        "custom_reading"
    );
    let overview = format!("/v1/overview?from_ms={}&to_ms={now}", now - 3600000);
    assert_eq!(
        call(&app, "GET", &overview, json!({}), true).await.1["categories"][0]["category"],
        "custom_reading"
    );
    assert_eq!(
        call(
            &app,
            "POST",
            "/v1/activities",
            json!({"activities":[activity]}),
            true
        )
        .await
        .1["accepted"],
        0
    );
    call(
        &app,
        "POST",
        "/v1/cards",
        json!({"id":"rule-example","title":"User correction","summary":"","category":"rest"}),
        true,
    )
    .await;
    assert_eq!(
        call(&app, "GET", &path, json!({}), true).await.1["cards"][0]["category"],
        "rest"
    );
    assert_eq!(
        call(&app, "GET", &overview, json!({}), true).await.1["categories"][0]["category"],
        "rest"
    );
    call(
        &app,
        "POST",
        "/v1/cards",
        json!({"id":"rule-example","title":"User custom","summary":"","category":"custom_reading"}),
        true,
    )
    .await;
    let mut prefs = call(&app, "GET", "/v1/workspace", json!({}), true).await.1;
    prefs["categories"]
        .as_array_mut()
        .unwrap()
        .retain(|c| c["id"] != "custom_reading");
    prefs["rules"] = json!([]);
    assert_eq!(
        call(&app, "PUT", "/v1/workspace", prefs, true).await.0,
        StatusCode::CONFLICT
    );
    assert_eq!(
        call(
            &app,
            "POST",
            "/v1/cards",
            json!({"id":"rule-example","title":"Invalid","summary":"","category":"custom_missing"}),
            true
        )
        .await
        .0,
        StatusCode::BAD_REQUEST
    );
}

#[tokio::test]
async fn durable_focus_is_shared_conflict_safe_idempotent_and_erased() {
    let app = app();
    let now = chrono::Utc::now().timestamp_millis();
    for (method, path) in [
        ("GET", "/v1/focus"),
        ("POST", "/v1/focus"),
        ("DELETE", "/v1/focus"),
        ("POST", "/v1/focus/finish"),
    ] {
        assert_eq!(
            call(&app, method, path, json!({}), false).await.0,
            StatusCode::UNAUTHORIZED
        );
    }
    let session = json!({"id":"focus-one","at_ms":now-1000,"minutes":25,"text":"Synthetic focus"});
    assert_eq!(
        call(&app, "POST", "/v1/focus", session.clone(), true)
            .await
            .0,
        StatusCode::OK
    );
    assert_eq!(
        call(&app, "POST", "/v1/focus", session.clone(), true)
            .await
            .0,
        StatusCode::OK
    );
    assert_eq!(
        call(&app, "GET", "/v1/focus", json!({}), true).await.1["session"],
        session
    );
    let other = json!({"id":"focus-two","at_ms":now,"minutes":30,"text":"Second"});
    assert_eq!(
        call(&app, "POST", "/v1/focus", other.clone(), true).await.0,
        StatusCode::CONFLICT
    );
    assert_eq!(
        call(
            &app,
            "POST",
            "/v1/focus/finish",
            json!({"id":"focus-two"}),
            true
        )
        .await
        .0,
        StatusCode::CONFLICT
    );
    for _ in 0..2 {
        assert_eq!(
            call(
                &app,
                "POST",
                "/v1/focus/finish",
                json!({"id":"focus-one"}),
                true
            )
            .await
            .0,
            StatusCode::NO_CONTENT
        );
    }
    assert_eq!(
        call(&app, "POST", "/v1/focus", session, true).await.0,
        StatusCode::CONFLICT
    );
    assert_eq!(
        call(&app, "POST", "/v1/focus", other.clone(), true).await.0,
        StatusCode::OK
    );
    // A retry on an old ID cannot finish/discard the new timer.
    assert_eq!(
        call(
            &app,
            "POST",
            "/v1/focus/finish",
            json!({"id":"focus-one"}),
            true
        )
        .await
        .0,
        StatusCode::NO_CONTENT
    );
    assert_eq!(
        call(&app, "DELETE", "/v1/focus", json!({"id":"focus-one"}), true)
            .await
            .0,
        StatusCode::NO_CONTENT
    );
    assert_eq!(
        call(&app, "GET", "/v1/focus", json!({}), true).await.1["session"],
        other
    );
    let path = format!("/v1/reviews?from_ms={}&to_ms={}", now - 60_000, now + 1000);
    let records = call(&app, "GET", &path, json!({}), true).await.1;
    assert_eq!(records["records"].as_array().unwrap().len(), 1);
    assert_eq!(records["records"][0]["kind"], "flow");
    assert_eq!(
        call(&app, "DELETE", "/v1/journal", json!({}), true).await.0,
        StatusCode::NO_CONTENT
    );
    assert!(call(&app, "GET", "/v1/focus", json!({}), true).await.1["session"].is_null());
    assert_eq!(
        call(&app, "GET", &path, json!({}), true).await.1["records"]
            .as_array()
            .unwrap()
            .len(),
        0
    );
}

fn synthetic_model(replies: Vec<Value>) -> (String, std::thread::JoinHandle<Vec<(String, Value)>>) {
    use std::io::{Read, Write};
    let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let endpoint = format!("http://{}", listener.local_addr().unwrap());
    listener.set_nonblocking(true).unwrap();
    let handle = std::thread::spawn(move || {
        let mut received = Vec::new();
        for reply in replies {
            let deadline = std::time::Instant::now() + std::time::Duration::from_secs(12);
            let mut stream = loop {
                match listener.accept() {
                    Ok((s, _)) => break s,
                    Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                        assert!(std::time::Instant::now() < deadline, "expected model call");
                        std::thread::sleep(std::time::Duration::from_millis(5));
                    }
                    Err(e) => panic!("{e}"),
                }
            };
            stream
                .set_read_timeout(Some(std::time::Duration::from_secs(5)))
                .unwrap();
            let mut headers = Vec::new();
            let mut byte = [0];
            while !headers.ends_with(b"\r\n\r\n") {
                stream.read_exact(&mut byte).unwrap();
                headers.push(byte[0]);
                assert!(headers.len() < 16384);
            }
            let headers = String::from_utf8(headers).unwrap().to_lowercase();
            let len: usize = headers
                .lines()
                .find_map(|s| s.strip_prefix("content-length: "))
                .unwrap()
                .parse()
                .unwrap();
            assert!(len < 100000);
            let mut bytes = vec![0; len];
            stream.read_exact(&mut bytes).unwrap();
            received.push((headers, serde_json::from_slice(&bytes).unwrap()));
            let text = reply.to_string();
            write!(stream,"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",text.len(),text).unwrap();
        }
        received
    });
    (endpoint, handle)
}
#[tokio::test]
async fn native_provider_headers_and_real_transport_preserve_secret_boundaries() {
    for protocol in ["anthropic", "gemini"] {
        let summary =
            json!({"title":"Synthetic test","summary":"Connection only","category":"unsorted"})
                .to_string();
        let reply = if protocol == "anthropic" {
            json!({"stop_reason":"end_turn","content":[{"type":"text","text":summary}]})
        } else {
            json!({"candidates":[{"finishReason":"STOP","content":{"parts":[{"text":summary}]}}]})
        };
        let (base, capture) = synthetic_model(vec![reply]);
        let endpoint = if protocol == "anthropic" {
            format!("{base}/v1/messages")
        } else {
            format!("{base}/v1beta/models/synthetic:generateContent")
        };
        let (app, _dir) = configured_app();
        let config=call(&app,"PUT","/v1/ai",json!({"protocol":protocol,"endpoint":endpoint,"model":"synthetic","api_key":"synthetic-provider-key"}),true).await;
        assert_eq!(config.0, StatusCode::OK);
        assert_eq!(config.1["protocol"], protocol);
        assert!(!config.1.to_string().contains("synthetic-provider-key"));
        assert_eq!(
            call(
                &app,
                "POST",
                "/v1/ai/test",
                json!({"provider_id":config.1["provider_id"]}),
                true
            )
            .await
            .0,
            StatusCode::OK
        );
        let rows = capture.join().unwrap();
        let (headers, body) = &rows[0];
        assert!(headers.contains(if protocol == "anthropic" {
            "x-api-key: synthetic-provider-key"
        } else {
            "x-goog-api-key: synthetic-provider-key"
        }));
        assert!(!headers.contains("authorization:"));
        assert!(!headers.lines().next().unwrap().contains("key"));
        assert!(!body.to_string().contains("synthetic-provider-key"));
        assert!(!body.to_string().contains(TOKEN));
        assert!(body.to_string().contains("Synthetic connection test"));
    }
}
#[tokio::test]
async fn conversation_and_brief_retries_persist_without_duplicate_outbound_requests() {
    let reply =
        |text: &str| json!({"choices":[{"finish_reason":"stop","message":{"content":text}}]});
    let (base, capture) = synthetic_model(vec![
        reply("first answer"),
        reply("second answer"),
        reply("saved brief"),
    ]);
    let dir = PrivateDirectory::new();
    std::fs::create_dir_all(&dir.0).unwrap();
    let db = dir.0.join("journal.sqlite");
    let config_dir = dir.0.join("config");
    let app = router(
        AppState::new(rusqlite::Connection::open(&db).unwrap(), TOKEN)
            .unwrap()
            .with_ai_settings(&config_dir)
            .unwrap(),
    );
    let now = chrono::Utc::now().timestamp_millis();
    let config = call(
        &app,
        "PUT",
        "/v1/ai",
        json!({"endpoint":format!("{base}/v1/chat/completions"),"model":"synthetic"}),
        true,
    )
    .await
    .1;
    let first = json!({"question":"first question","from_ms":now-3600000,"to_ms":now,"provider_id":config["provider_id"],"consent":true,"conversation_id":"durable-thread","request_id":"first-turn"});
    assert_eq!(
        call(&app, "POST", "/v1/ask", first.clone(), true).await.1["stored"],
        true
    );
    assert_eq!(
        call(&app, "POST", "/v1/ask", first.clone(), true).await.1["replayed"],
        true
    );
    let mut second = first.clone();
    second["question"] = json!("followup");
    second["request_id"] = json!("second-turn");
    assert_eq!(
        call(&app, "POST", "/v1/ask", second, true).await.0,
        StatusCode::OK
    );
    let brief = json!({"id":"durable-brief","from_ms":now-3600000,"to_ms":now,"kind":"daily","provider_id":config["provider_id"],"consent":true});
    assert_eq!(
        call(&app, "POST", "/v1/briefs", brief.clone(), true)
            .await
            .1["text"],
        "saved brief"
    );
    assert_eq!(
        call(&app, "POST", "/v1/briefs", brief, true).await.1["text"],
        "saved brief"
    );
    let captured = capture.join().unwrap();
    assert_eq!(captured.len(), 3);
    assert!(
        captured[1].1["messages"]
            .as_array()
            .unwrap()
            .iter()
            .any(|m| m["role"] == "assistant" && m["content"] == "first answer")
    );
    drop(app);
    let app = router(
        AppState::new(rusqlite::Connection::open(&db).unwrap(), TOKEN)
            .unwrap()
            .with_ai_settings(&config_dir)
            .unwrap(),
    );
    let thread = call(
        &app,
        "GET",
        "/v1/conversations/durable-thread",
        json!({}),
        true,
    )
    .await;
    assert_eq!(thread.0, StatusCode::OK);
    assert_eq!(thread.1["turns"].as_array().unwrap().len(), 2);
    let path = format!("/v1/briefs?from_ms={}&to_ms={now}", now - 3600000);
    assert_eq!(
        call(&app, "GET", &path, json!({}), true).await.1["briefs"][0]["text"],
        "saved brief"
    );
    assert_eq!(
        call(
            &app,
            "DELETE",
            "/v1/conversations/durable-thread",
            json!({}),
            true
        )
        .await
        .0,
        StatusCode::NO_CONTENT
    );
    assert_eq!(
        call(
            &app,
            "GET",
            "/v1/conversations/durable-thread",
            json!({}),
            true
        )
        .await
        .0,
        StatusCode::NOT_FOUND
    );
    call(&app, "DELETE", "/v1/journal", json!({}), true).await;
    assert!(
        call(&app, "GET", &path, json!({}), true).await.1["briefs"]
            .as_array()
            .unwrap()
            .is_empty()
    );
}
#[tokio::test]
async fn schedule_requires_separate_consent_revision_and_valid_timezone() {
    let (app, _dir) = configured_app();
    for (method, path) in [
        ("GET", "/v1/briefs"),
        ("POST", "/v1/briefs"),
        ("DELETE", "/v1/briefs/example"),
        ("GET", "/v1/briefs/schedule"),
        ("PUT", "/v1/briefs/schedule"),
    ] {
        assert_eq!(
            call(&app, method, path, json!({}), false).await.0,
            StatusCode::UNAUTHORIZED
        );
    }
    let mut schedule = call(&app, "GET", "/v1/briefs/schedule", json!({}), true)
        .await
        .1;
    assert_eq!(schedule["enabled"], false);
    schedule["enabled"] = json!(true);
    assert_eq!(
        call(&app, "PUT", "/v1/briefs/schedule", schedule.clone(), true)
            .await
            .0,
        StatusCode::BAD_REQUEST
    );
    let config = call(
        &app,
        "PUT",
        "/v1/ai",
        json!({"endpoint":"http://127.0.0.1:1/v1/chat/completions","model":"synthetic"}),
        true,
    )
    .await
    .1;
    assert_eq!(
        call(&app, "GET", "/v1/briefs/schedule", json!({}), true)
            .await
            .1["enabled"],
        false
    );
    schedule["provider_id"] = config["provider_id"].clone();
    schedule["timezone"] = json!("invalid/timezone");
    assert_eq!(
        call(&app, "PUT", "/v1/briefs/schedule", schedule.clone(), true)
            .await
            .0,
        StatusCode::BAD_REQUEST
    );
    schedule["timezone"] = json!("Asia/Taipei");
    assert_eq!(
        call(&app, "PUT", "/v1/briefs/schedule", schedule.clone(), true)
            .await
            .0,
        StatusCode::OK
    );
    assert_eq!(
        call(&app, "PUT", "/v1/briefs/schedule", schedule, true)
            .await
            .0,
        StatusCode::CONFLICT
    );
    call(&app, "DELETE", "/v1/journal", json!({}), true).await;
    let schedule = call(&app, "GET", "/v1/briefs/schedule", json!({}), true)
        .await
        .1;
    assert_eq!(schedule["enabled"], false);
    assert_eq!(schedule["revision"], 2);
}

#[tokio::test]
async fn approved_agent_turns_are_private_versioned_bounded_and_deleted() {
    let app = app();
    let now = chrono::Utc::now().timestamp_millis();
    for (method, path) in [
        ("GET", "/v1/agent-sessions"),
        ("PUT", "/v1/agent-sessions"),
        ("GET", "/v1/agent-sessions/demo"),
        ("DELETE", "/v1/agent-sessions/demo"),
    ] {
        assert_eq!(
            call(&app, method, path, json!({}), false).await.0,
            StatusCode::UNAUTHORIZED
        );
    }
    let item = json!({"id":"demo","revision":0,"at_ms":now,"source":"codex","project":"Demo","title":"Approved synthetic session","status":"completed","turns":[{"id":"turn-1","at_ms":now-1000,"role":"user","text":"approved question"},{"id":"turn-2","at_ms":now,"role":"assistant","text":"approved answer"}]});
    let saved = call(&app, "PUT", "/v1/agent-sessions", item.clone(), true).await;
    assert_eq!(saved.0, StatusCode::OK);
    assert_eq!(saved.1["revision"], 1);
    assert_eq!(
        call(&app, "PUT", "/v1/agent-sessions", item.clone(), true)
            .await
            .0,
        StatusCode::CONFLICT
    );
    let list = call(&app, "GET", "/v1/agent-sessions", json!({}), true)
        .await
        .1;
    assert_eq!(list["sessions"][0]["turn_count"], 2);
    assert!(!list.to_string().contains("approved question"));
    assert_eq!(
        call(&app, "GET", "/v1/agent-sessions/demo", json!({}), true)
            .await
            .1["turns"][0]["text"],
        "approved question"
    );
    let mut edit = saved.1;
    edit["status"] = json!("waiting");
    assert_eq!(
        call(&app, "PUT", "/v1/agent-sessions", edit, true).await.0,
        StatusCode::OK
    );
    for field in ["tool", "oversize", "duplicate"] {
        let mut bad = item.clone();
        bad["id"] = json!(format!("bad-{field}"));
        match field {
            "tool" => bad["turns"][0]["role"] = json!("tool"),
            "oversize" => bad["turns"][0]["text"] = json!("x".repeat(1001)),
            _ => bad["turns"][1]["id"] = json!("turn-1"),
        };
        assert_eq!(
            call(&app, "PUT", "/v1/agent-sessions", bad, true).await.0,
            if field == "tool" {
                StatusCode::UNPROCESSABLE_ENTITY
            } else {
                StatusCode::BAD_REQUEST
            }
        );
    }
    call(&app, "DELETE", "/v1/journal", json!({}), true).await;
    assert_eq!(
        call(&app, "GET", "/v1/agent-sessions/demo", json!({}), true)
            .await
            .0,
        StatusCode::NOT_FOUND
    );
    assert!(
        call(&app, "GET", "/v1/agent-sessions", json!({}), true)
            .await
            .1["sessions"]
            .as_array()
            .unwrap()
            .is_empty()
    );
}

#[tokio::test]
async fn remote_pause_rejects_capture_without_blocking_explicit_journaling() {
    let app = app();
    let now = chrono::Utc::now().timestamp_millis();
    for method in ["GET", "PUT"] {
        assert_eq!(
            call(&app, method, "/v1/collection", json!({}), false)
                .await
                .0,
            StatusCode::UNAUTHORIZED
        );
    }
    let initial = call(&app, "GET", "/v1/collection", json!({}), true).await.1;
    assert_eq!(initial["paused"], false);
    let observation = json!({"id":"pre-pause","at_ms":now-1000,"app":"org.example.reader","text":"synthetic context","analysis_consent":true});
    assert_eq!(
        call(&app, "POST", "/v1/observations", observation.clone(), true)
            .await
            .0,
        StatusCode::NO_CONTENT
    );
    let mut pause = initial.clone();
    pause["paused"] = json!(true);
    let paused = call(&app, "PUT", "/v1/collection", pause.clone(), true).await;
    assert_eq!(paused.0, StatusCode::OK);
    assert_eq!(paused.1["revision"], 1);
    assert_eq!(
        call(&app, "PUT", "/v1/collection", pause, true).await.0,
        StatusCode::CONFLICT
    );
    assert_eq!(
        call(&app, "POST", "/v1/observations", observation, true)
            .await
            .0,
        StatusCode::LOCKED
    );
    assert_eq!(
        call(
            &app,
            "POST",
            "/v1/activities",
            json!({"activities":[event("paused")]}),
            true
        )
        .await
        .0,
        StatusCode::LOCKED
    );
    assert_eq!(
        call(&app, "GET", "/v1/status", json!({}), true).await.1["pending_context"],
        0
    );
    assert_eq!(
        call(
            &app,
            "POST",
            "/v1/checkins",
            json!({"id":"feeling-during-pause","at_ms":now,"mood":3,"energy":3}),
            true
        )
        .await
        .0,
        StatusCode::NO_CONTENT
    );
    let mut resume = paused.1;
    resume["paused"] = json!(false);
    assert_eq!(
        call(&app, "PUT", "/v1/collection", resume, true).await.0,
        StatusCode::OK
    );
    assert_eq!(
        call(
            &app,
            "POST",
            "/v1/activities",
            json!({"activities":[event("resumed")]}),
            true
        )
        .await
        .0,
        StatusCode::OK
    );
}
#[tokio::test]
async fn replay_is_separate_opt_in_worker_sanitized_authenticated_and_short_lived() {
    use base64::Engine;
    let dir = PrivateDirectory::new();
    std::fs::create_dir_all(&dir.0).unwrap();
    let path = dir.0.join("journal.sqlite");
    let app = router(AppState::new(rusqlite::Connection::open(&path).unwrap(), TOKEN).unwrap());
    let now = chrono::Utc::now().timestamp_millis();
    let mut jpeg = Vec::new();
    image::codecs::jpeg::JpegEncoder::new_with_quality(&mut jpeg, 30)
        .encode_image(&image::RgbImage::new(16, 16))
        .unwrap();
    let observation = json!({"id":"analysis-only","at_ms":now-1000,"app":"org.example.reader","text":"synthetic","analysis_consent":true,"jpeg_base64":base64::engine::general_purpose::STANDARD.encode(jpeg),"image_consent":true});
    let window = format!("/v1/replay?from_ms={}&to_ms={now}", now - 3600000);
    for (method, path) in [
        ("GET", window.as_str()),
        ("GET", "/v1/replay/image"),
        ("GET", "/v1/replay/image/image"),
        ("DELETE", "/v1/replay/image"),
    ] {
        assert_eq!(
            call(&app, method, path, json!({}), false).await.0,
            StatusCode::UNAUTHORIZED
        );
    }
    assert_eq!(
        call(&app, "POST", "/v1/observations", observation.clone(), true)
            .await
            .0,
        StatusCode::NO_CONTENT
    );
    let mut approved = observation.clone();
    approved["id"] = json!("approved-image");
    approved["replay_consent"] = json!(true);
    let mut invalid = approved.clone();
    invalid["image_consent"] = json!(false);
    assert_eq!(
        call(&app, "POST", "/v1/observations", invalid, true)
            .await
            .0,
        StatusCode::BAD_REQUEST
    );
    assert_eq!(
        call(&app, "POST", "/v1/observations", approved.clone(), true)
            .await
            .0,
        StatusCode::NO_CONTENT
    );
    assert!(
        call(&app, "GET", &window, json!({}), true).await.1["images"]
            .as_array()
            .unwrap()
            .is_empty()
    );
    assert_eq!(
        call(&app, "GET", "/v1/replay/approved-image", json!({}), true)
            .await
            .0,
        StatusCode::NOT_FOUND
    );
    let mut db = rusqlite::Connection::open(&path).unwrap();
    pocketlog_server::process_with_provider(&mut db, None).unwrap();
    let result = call(&app, "GET", &window, json!({}), true).await.1;
    assert_eq!(result["images"].as_array().unwrap().len(), 1);
    assert_eq!(result["images"][0]["id"], "approved-image");
    let result = call(&app, "GET", "/v1/replay/approved-image", json!({}), true)
        .await
        .1;
    let bytes = base64::engine::general_purpose::STANDARD
        .decode(result["jpeg_base64"].as_str().unwrap())
        .unwrap();
    assert!(bytes.len() <= 24576);
    assert!(image::load_from_memory(&bytes).is_ok());
    assert_eq!(
        call(&app, "GET", "/v1/replay/analysis-only", json!({}), true)
            .await
            .0,
        StatusCode::NOT_FOUND
    );
    db.execute("UPDATE image_replay SET at_ms=?", [now - 86_400_001])
        .unwrap();
    assert_eq!(
        call(&app, "GET", "/v1/replay/approved-image", json!({}), true)
            .await
            .0,
        StatusCode::NOT_FOUND
    );
    assert_eq!(
        db.query_row("SELECT COUNT(*) FROM image_replay", [], |r| r
            .get::<_, i64>(0))
            .unwrap(),
        0
    );
    approved["id"] = json!("delete-image");
    call(&app, "POST", "/v1/observations", approved, true).await;
    call(&app, "DELETE", "/v1/journal", json!({}), true).await;
    assert_eq!(
        db.query_row("SELECT COUNT(*) FROM image_replay", [], |r| r
            .get::<_, i64>(0))
            .unwrap(),
        0
    );
}

async fn call_token(
    app: &Router,
    method: &str,
    path: &str,
    body: Value,
    token: &str,
) -> (StatusCode, Value) {
    let response = app
        .clone()
        .oneshot(
            Request::builder()
                .method(method)
                .uri(path)
                .header("content-type", "application/json")
                .header("authorization", format!("Bearer {token}"))
                .body(Body::from(body.to_string()))
                .unwrap(),
        )
        .await
        .unwrap();
    let status = response.status();
    let bytes = response.into_body().collect().await.unwrap().to_bytes();
    (
        status,
        serde_json::from_slice(&bytes).unwrap_or(Value::Null),
    )
}
#[tokio::test]
async fn device_credentials_are_scoped_secret_once_expiring_revocable_and_owner_bound() {
    let dir = PrivateDirectory::new();
    std::fs::create_dir_all(&dir.0).unwrap();
    let path = dir.0.join("journal.sqlite");
    let app = router(AppState::new(rusqlite::Connection::open(&path).unwrap(), TOKEN).unwrap());
    for (method, path) in [
        ("GET", "/v1/devices"),
        ("POST", "/v1/devices"),
        ("DELETE", "/v1/devices/example"),
    ] {
        assert_eq!(
            call(&app, method, path, json!({}), false).await.0,
            StatusCode::UNAUTHORIZED
        );
    }
    let now = chrono::Utc::now().timestamp_millis();
    let timeline = format!("/v1/timeline?from_ms={}&to_ms={now}", now - 3600000);
    let journal = call(
        &app,
        "POST",
        "/v1/devices",
        json!({"label":"Synthetic phone","scope":"journal","days":30}),
        true,
    )
    .await;
    assert_eq!(journal.0, StatusCode::OK);
    let token = journal.1["token"].as_str().unwrap();
    assert_eq!(token.len(), 64);
    let initial = call(&app, "GET", "/v1/devices", json!({}), true).await.1;
    assert!(
        initial["devices"][0]
            .get("last_authenticated_ms")
            .unwrap()
            .is_null()
    );
    assert_eq!(
        call_token(&app, "GET", &timeline, json!({}), "malformed")
            .await
            .0,
        StatusCode::UNAUTHORIZED
    );
    let still_unused = call(&app, "GET", "/v1/devices", json!({}), true).await.1;
    assert!(still_unused["devices"][0]["last_authenticated_ms"].is_null());
    assert_eq!(
        call_token(&app, "GET", &timeline, json!({}), token).await.0,
        StatusCode::OK
    );
    assert_eq!(
        call_token(&app, "GET", "/v1/devices", json!({}), token)
            .await
            .0,
        StatusCode::UNAUTHORIZED
    );
    assert_eq!(
        call_token(
            &app,
            "POST",
            "/v1/devices",
            json!({"label":"escalation","scope":"journal","days":90}),
            token
        )
        .await
        .0,
        StatusCode::UNAUTHORIZED
    );
    let list = call(&app, "GET", "/v1/devices", json!({}), true).await.1;
    assert_eq!(list["devices"].as_array().unwrap().len(), 1);
    let authenticated = list["devices"][0]["last_authenticated_ms"]
        .as_i64()
        .unwrap();
    assert_eq!(authenticated % 60_000, 0);
    assert!(authenticated >= now - 60_000);
    assert!(!list.to_string().contains(token));
    assert!(!list.to_string().contains("hash"));
    let response = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/session")
                .header("content-type", "application/json")
                .header("x-pocketlog", "1")
                .body(Body::from(json!({"token":token}).to_string()))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
    let collector = call(
        &app,
        "POST",
        "/v1/devices",
        json!({"label":"Synthetic collector","scope":"collector","days":1}),
        true,
    )
    .await
    .1;
    let collect_token = collector["token"].as_str().unwrap();
    assert_eq!(
        call_token(
            &app,
            "POST",
            "/v1/activities",
            json!({"activities":[event("device-event")]}),
            collect_token
        )
        .await
        .0,
        StatusCode::OK
    );
    assert_eq!(
        call_token(&app, "GET", &timeline, json!({}), collect_token)
            .await
            .0,
        StatusCode::UNAUTHORIZED
    );
    assert_eq!(
        call_token(&app, "GET", "/v1/collection", json!({}), collect_token)
            .await
            .0,
        StatusCode::OK
    );
    assert_eq!(
        call_token(&app, "DELETE", "/v1/journal", json!({}), collect_token)
            .await
            .0,
        StatusCode::UNAUTHORIZED
    );
    let db = rusqlite::Connection::open(&path).unwrap();
    let bytes: Vec<u8> = db
        .query_row(
            "SELECT hash FROM device_credentials WHERE id=?",
            [journal.1["id"].as_str().unwrap()],
            |r| r.get(0),
        )
        .unwrap();
    assert_eq!(bytes.len(), 32);
    assert_ne!(bytes, token.as_bytes());
    db.execute(
        "UPDATE device_credentials SET expires_ms=? WHERE id=?",
        rusqlite::params![now - 1, collector["id"].as_str().unwrap()],
    )
    .unwrap();
    assert_eq!(
        call_token(&app, "GET", "/v1/collection", json!({}), collect_token)
            .await
            .0,
        StatusCode::UNAUTHORIZED
    );
    let revoke = format!("/v1/devices/{}", journal.1["id"].as_str().unwrap());
    assert_eq!(
        call(&app, "DELETE", &revoke, json!({}), true).await.0,
        StatusCode::NO_CONTENT
    );
    assert_eq!(
        call_token(&app, "GET", &timeline, json!({}), token).await.0,
        StatusCode::UNAUTHORIZED
    );
    let other = call(
        &app,
        "POST",
        "/v1/devices",
        json!({"label":"Before rotation","scope":"journal","days":30}),
        true,
    )
    .await
    .1;
    drop(app);
    let app =
        router(AppState::new(rusqlite::Connection::open(&path).unwrap(), &"a".repeat(64)).unwrap());
    assert_eq!(
        call_token(
            &app,
            "GET",
            &timeline,
            json!({}),
            other["token"].as_str().unwrap()
        )
        .await
        .0,
        StatusCode::UNAUTHORIZED
    );
    assert_eq!(
        db.query_row("SELECT COUNT(*) FROM device_credentials", [], |r| r
            .get::<_, i64>(0))
            .unwrap(),
        0
    );
}

#[tokio::test]
async fn conversation_cursor_preserves_all_tied_timestamps() {
    let dir = PrivateDirectory::new();
    std::fs::create_dir_all(&dir.0).unwrap();
    let path = dir.0.join("journal.sqlite");
    let app = router(AppState::new(rusqlite::Connection::open(&path).unwrap(), TOKEN).unwrap());
    let now = chrono::Utc::now().timestamp_millis();
    let db = rusqlite::Connection::open(&path).unwrap();
    db.execute(
        "INSERT INTO chat_threads VALUES('tied-thread',?,'Synthetic','synthetic-provider')",
        [now],
    )
    .unwrap();
    for i in 0..60 {
        db.execute(
            "INSERT INTO chat_turns VALUES(?,'tied-thread',?,'question','answer',?,?)",
            rusqlite::params![format!("turn-{i:03}"), now, now - 1000, now],
        )
        .unwrap();
    }
    let first = call(
        &app,
        "GET",
        "/v1/conversations/tied-thread",
        json!({}),
        true,
    )
    .await
    .1;
    assert_eq!(first["turns"].as_array().unwrap().len(), 50);
    assert_eq!(first["truncated"], true);
    let cursor = &first["next_cursor"];
    let second = call(
        &app,
        "GET",
        &format!(
            "/v1/conversations/tied-thread?before_ms={}&before_id={}",
            cursor["before_ms"],
            cursor["before_id"].as_str().unwrap()
        ),
        json!({}),
        true,
    )
    .await
    .1;
    assert_eq!(second["turns"].as_array().unwrap().len(), 10);
    assert_eq!(second["truncated"], false);
    let ids: std::collections::BTreeSet<&str> = first["turns"]
        .as_array()
        .unwrap()
        .iter()
        .chain(second["turns"].as_array().unwrap())
        .map(|t| t["id"].as_str().unwrap())
        .collect();
    assert_eq!(ids.len(), 60);
}

#[tokio::test]
async fn updating_agent_status_never_extends_old_turn_retention() {
    let dir = PrivateDirectory::new();
    std::fs::create_dir_all(&dir.0).unwrap();
    let path = dir.0.join("journal.sqlite");
    let app = router(AppState::new(rusqlite::Connection::open(&path).unwrap(), TOKEN).unwrap());
    let now = chrono::Utc::now().timestamp_millis();
    let item = json!({"id":"retained-session","revision":0,"at_ms":now,"source":"claude","project":"Synthetic","title":"Retention","status":"active","turns":[{"id":"older","at_ms":now-1000,"role":"user","text":"older turn"},{"id":"newer","at_ms":now,"role":"assistant","text":"current turn"}]});
    assert_eq!(
        call(&app, "PUT", "/v1/agent-sessions", item, true).await.0,
        StatusCode::OK
    );
    let db = rusqlite::Connection::open(path).unwrap();
    db.execute(
        "UPDATE agent_sessions SET json=json_set(json,'$.turns[0].at_ms',?)",
        [now - 30 * 86_400_000 - 1000],
    )
    .unwrap();
    let data = call(
        &app,
        "GET",
        "/v1/agent-sessions/retained-session",
        json!({}),
        true,
    )
    .await
    .1;
    assert_eq!(data["turns"].as_array().unwrap().len(), 1);
    assert_eq!(data["turns"][0]["id"], "newer");
    assert_eq!(data["revision"], 2);
    let stored: String = db
        .query_row("SELECT json FROM agent_sessions", [], |r| r.get(0))
        .unwrap();
    assert!(!stored.contains("older turn"));
}

#[tokio::test]
async fn scheduled_briefs_require_explicit_current_provider_and_skip_empty_periods() {
    use chrono::Timelike;
    let reply = json!({"choices":[{"finish_reason":"stop","message":{"content":"Synthetic scheduled brief"}}]});
    let (base, capture) = synthetic_model(vec![reply]);
    let dir = PrivateDirectory::new();
    std::fs::create_dir_all(&dir.0).unwrap();
    let path = dir.0.join("journal.sqlite");
    let config_dir = dir.0.join("config");
    let app = router(
        AppState::new(rusqlite::Connection::open(&path).unwrap(), TOKEN)
            .unwrap()
            .with_ai_settings(&config_dir)
            .unwrap(),
    );
    let config = call(
        &app,
        "PUT",
        "/v1/ai",
        json!({"endpoint":format!("{base}/v1/chat/completions"),"model":"synthetic"}),
        true,
    )
    .await
    .1;
    let run = |path: std::path::PathBuf, config_dir: std::path::PathBuf| {
        tokio::task::spawn_blocking(move || {
            let mut db = rusqlite::Connection::open(path).unwrap();
            let provider = pocketlog_server::Provider::from_config_directory(&config_dir).unwrap();
            pocketlog_server::process_briefs(&mut db, provider.as_ref()).unwrap()
        })
    };
    assert_eq!(run(path.clone(), config_dir.clone()).await.unwrap(), 0);
    let tz = if chrono::Utc::now().hour() < 4 {
        "Asia/Taipei"
    } else {
        "UTC"
    };
    let mut schedule = call(&app, "GET", "/v1/briefs/schedule", json!({}), true)
        .await
        .1;
    schedule["enabled"] = json!(true);
    schedule["provider_id"] = config["provider_id"].clone();
    schedule["minute_of_day"] = json!(240);
    schedule["timezone"] = json!(tz);
    schedule["weekly"] = json!(false);
    assert_eq!(
        call(&app, "PUT", "/v1/briefs/schedule", schedule, true)
            .await
            .0,
        StatusCode::OK
    );
    assert_eq!(
        run(path.clone(), config_dir.clone()).await.unwrap(),
        0,
        "empty periods never incur model calls"
    );
    let now = chrono::Utc::now().timestamp_millis();
    let mut activity = event("scheduled-card");
    activity["start_ms"] = json!(now - 24 * 3_600_000);
    activity["end_ms"] = json!(now - 23 * 3_600_000);
    assert_eq!(
        call(
            &app,
            "POST",
            "/v1/activities",
            json!({"activities":[activity]}),
            true
        )
        .await
        .0,
        StatusCode::OK
    );
    assert_eq!(run(path.clone(), config_dir.clone()).await.unwrap(), 1);
    assert_eq!(
        run(path.clone(), config_dir.clone()).await.unwrap(),
        0,
        "completed period never repeats a paid call"
    );
    assert_eq!(capture.join().unwrap().len(), 1);
    let rotated = call(
        &app,
        "PUT",
        "/v1/ai",
        json!({"endpoint":"http://127.0.0.1:1/v1/chat/completions","model":"new-synthetic"}),
        true,
    )
    .await
    .1;
    assert_ne!(rotated["provider_id"], config["provider_id"]);
    assert_eq!(
        run(path.clone(), config_dir).await.unwrap(),
        0,
        "old scheduled consent does not authorize a new provider"
    );
    let db = rusqlite::Connection::open(path).unwrap();
    assert_eq!(
        db.query_row("SELECT COUNT(*) FROM briefs", [], |r| r.get::<_, i64>(0))
            .unwrap(),
        1
    );
}

#[tokio::test]
async fn probe_requires_the_confirmed_current_provider_before_any_network_call() {
    use std::io::{Read, Write};
    let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    listener.set_nonblocking(true).unwrap();
    let endpoint = format!(
        "http://{}/v1/chat/completions",
        listener.local_addr().unwrap()
    );
    let (app, _directory) = configured_app();
    assert_eq!(
        call(&app, "GET", "/v1/ai", Value::Null, true).await.1["probe_provider_binding"],
        true
    );
    for raw in ["", "{", "[]", "null"] {
        for authenticated in [false, true] {
            let mut request = Request::builder()
                .method("POST")
                .uri("/v1/ai/test")
                .header("content-type", "application/json");
            if authenticated {
                request = request.header("authorization", format!("Bearer {TOKEN}"));
            }
            let response = app
                .clone()
                .oneshot(request.body(Body::from(raw)).unwrap())
                .await
                .unwrap();
            assert_eq!(
                response.status(),
                if authenticated {
                    StatusCode::BAD_REQUEST
                } else {
                    StatusCode::UNAUTHORIZED
                }
            );
        }
    }
    let absent = call(
        &app,
        "POST",
        "/v1/ai/test",
        json!({"provider_id":"a".repeat(64)}),
        true,
    )
    .await;
    assert_eq!(absent.0, StatusCode::CONFLICT);
    let a = call(
        &app,
        "PUT",
        "/v1/ai",
        json!({"endpoint":endpoint,"model":"synthetic-a","api_key":"synthetic-key-a"}),
        true,
    )
    .await;
    assert_eq!(a.0, StatusCode::OK);
    let b = call(
        &app,
        "PUT",
        "/v1/ai",
        json!({"endpoint":endpoint,"model":"synthetic-b","api_key":"synthetic-key-b"}),
        true,
    )
    .await;
    assert_eq!(b.0, StatusCode::OK);
    assert_ne!(a.1["provider_id"], b.1["provider_id"]);
    for invalid in [
        json!({}),
        json!({"provider_id":null}),
        json!({"provider_id":7}),
        json!({"provider_id":""}),
        json!({"provider_id":"a".repeat(63)}),
        json!({"provider_id":"g".repeat(64)}),
        json!({"provider_id":b.1["provider_id"],"consent":true}),
    ] {
        assert_eq!(
            call(&app, "POST", "/v1/ai/test", invalid.clone(), false)
                .await
                .0,
            StatusCode::UNAUTHORIZED
        );
        assert_eq!(
            call(&app, "POST", "/v1/ai/test", invalid, true).await.0,
            StatusCode::BAD_REQUEST
        );
    }
    let stale = call(
        &app,
        "POST",
        "/v1/ai/test",
        json!({"provider_id":a.1["provider_id"]}),
        true,
    )
    .await;
    assert_eq!(stale.0, StatusCode::CONFLICT);
    assert_eq!(stale.1["error"], "provider changed");
    assert_eq!(
        listener.accept().unwrap_err().kind(),
        std::io::ErrorKind::WouldBlock
    );
    call(
        &app,
        "POST",
        "/v1/activities",
        json!({"activities":[event("private-probe-card")]}),
        true,
    )
    .await;
    call(&app, "POST", "/v1/cards", json!({"id":"private-probe-card","title":"NEVER_SEND_PROBE_CARD","summary":"Private derived content","category":"learning"}), true).await;
    let receiver = listener.try_clone().unwrap();
    let capture = std::thread::spawn(move || {
        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(12);
        let mut stream = loop {
            match receiver.accept() {
                Ok((stream, _)) => break stream,
                Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                    assert!(
                        std::time::Instant::now() < deadline,
                        "expected synthetic probe"
                    );
                    std::thread::sleep(std::time::Duration::from_millis(5));
                }
                Err(e) => panic!("{e}"),
            }
        };
        stream
            .set_read_timeout(Some(std::time::Duration::from_secs(5)))
            .unwrap();
        let mut headers = Vec::new();
        let mut byte = [0];
        while !headers.ends_with(b"\r\n\r\n") {
            stream.read_exact(&mut byte).unwrap();
            headers.push(byte[0]);
            assert!(headers.len() < 16384);
        }
        let headers = String::from_utf8(headers).unwrap();
        let length: usize = headers
            .lines()
            .find_map(|line| {
                line.to_lowercase()
                    .strip_prefix("content-length: ")
                    .map(str::to_owned)
            })
            .unwrap()
            .parse()
            .unwrap();
        assert!(length < 16384);
        let mut body = vec![0; length];
        stream.read_exact(&mut body).unwrap();
        let body = String::from_utf8(body).unwrap();
        let content =
            json!({"title":"Synthetic test","summary":"Connection only","category":"unsorted"})
                .to_string();
        let reply =
            json!({"choices":[{"finish_reason":"stop","message":{"content":content}}]}).to_string();
        write!(stream, "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}", reply.len(), reply).unwrap();
        (headers, body)
    });
    let confirmed = call(
        &app,
        "POST",
        "/v1/ai/test",
        json!({"provider_id":b.1["provider_id"]}),
        true,
    )
    .await;
    assert_eq!(confirmed.0, StatusCode::OK);
    assert_eq!(confirmed.1, json!({"ok":true,"journal_data_sent":false}));
    let (headers, body) = capture.join().unwrap();
    assert!(
        headers
            .to_lowercase()
            .contains("authorization: bearer synthetic-key-b")
    );
    assert!(body.contains("Synthetic connection test"));
    assert!(body.contains("synthetic-b"));
    for private in ["synthetic-key-a", TOKEN, "NEVER_SEND_PROBE_CARD"] {
        assert!(!headers.contains(private));
        assert!(!body.contains(private));
    }
    assert!(!body.contains("synthetic-key-b"));
    assert_eq!(
        call(&app, "DELETE", "/v1/ai", Value::Null, true).await.0,
        StatusCode::NO_CONTENT
    );
    let disabled = call(
        &app,
        "POST",
        "/v1/ai/test",
        json!({"provider_id":b.1["provider_id"]}),
        true,
    )
    .await;
    assert_eq!(disabled.0, StatusCode::CONFLICT);
    assert_eq!(disabled.1["error"], "provider changed");
    assert_eq!(
        listener.accept().unwrap_err().kind(),
        std::io::ErrorKind::WouldBlock
    );
}

#[tokio::test]
async fn lossless_replay_preserves_pixels_and_uses_the_authenticated_image_route() {
    use base64::{Engine, engine::general_purpose::STANDARD};
    use image::{ExtendedColorType, Rgb, RgbImage, codecs::webp::WebPEncoder};

    let dir = PrivateDirectory::new();
    std::fs::create_dir_all(&dir.0).unwrap();
    let path = dir.0.join("journal.sqlite");
    let app = router(AppState::new(rusqlite::Connection::open(&path).unwrap(), TOKEN).unwrap());
    let source = RgbImage::from_fn(1080, 2340, |x, y| {
        if y % 36 < 3 && x % 120 < 80 {
            Rgb([16, 40, 153])
        } else {
            Rgb([244, 247, 252])
        }
    });
    let mut webp = Vec::new();
    WebPEncoder::new_lossless(&mut webp)
        .encode(
            &source,
            source.width(),
            source.height(),
            ExtendedColorType::Rgb8,
        )
        .unwrap();
    let observation = json!({
        "id": "lossless",
        "at_ms": chrono::Utc::now().timestamp_millis() - 500,
        "app": "org.example.reader",
        "text": "synthetic",
        "analysis_consent": true,
        "image_consent": true,
        "replay_consent": true,
        "screenshot": {"format":"webp", "base64": STANDARD.encode(webp)},
    });
    let mut ambiguous = observation.clone();
    ambiguous["jpeg_base64"] = json!("AAAA");
    assert_eq!(
        call(&app, "POST", "/v1/observations", ambiguous, true)
            .await
            .0,
        StatusCode::BAD_REQUEST
    );
    for _ in 0..2 {
        assert_eq!(
            call(&app, "POST", "/v1/observations", observation.clone(), true)
                .await
                .0,
            StatusCode::NO_CONTENT
        );
    }

    let mut db = rusqlite::Connection::open(path).unwrap();
    pocketlog_server::process_with_provider(&mut db, None).unwrap();
    let (status, json) = call(&app, "GET", "/v1/replay/lossless", json!({}), true).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(json["screenshot"]["format"], "webp");
    let request = Request::get("/v1/replay/lossless/image")
        .header("authorization", format!("Bearer {TOKEN}"))
        .body(Body::empty())
        .unwrap();
    let response = app.oneshot(request).await.unwrap();
    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(response.headers()["cache-control"], "no-store");
    assert_eq!(response.headers()["content-type"], "image/webp");
    let bytes = response.into_body().collect().await.unwrap().to_bytes();
    assert!(bytes.len() <= 300_000);
    assert_eq!(
        bytes.as_ref(),
        STANDARD
            .decode(json["screenshot"]["base64"].as_str().unwrap())
            .unwrap()
    );
    assert_eq!(image::load_from_memory(&bytes).unwrap().into_rgb8(), source);
}
