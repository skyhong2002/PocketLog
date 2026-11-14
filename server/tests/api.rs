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
