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
async fn retention_removes_old_records_and_errors_do_not_echo_secrets() {
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
    assert_eq!(remaining, 0);
    let app = router(state);
    let (status, error) = call(
        &app,
        "GET",
        "/v1/timeline?from_ms=0&to_ms=1",
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
    expired["at_ms"] = json!(now - 86_400_001);
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
    image["jpeg_base64"] = json!("A".repeat(32769));
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
    let hash = format!("{:x}", Sha256::digest(canonical.as_bytes()));
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
        call(&app, "POST", "/v1/ai/test", json!({}), true).await.0,
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
