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
