#![forbid(unsafe_code)]

mod agents;
mod ai_settings;
mod analysis;
mod briefs;
mod collection;
mod conversations;
mod devices;
mod focus;
mod journal;
mod pages;
mod replay;
mod screen_cards;
mod screenshots;
mod storage;
mod topics;
mod web;
mod workspace;

use axum::{
    Json, Router,
    extract::{DefaultBodyLimit, Query, State},
    http::{StatusCode, header},
    middleware::{self, Next},
    response::{IntoResponse, Response},
    routing::{delete, get, post},
};
use rusqlite::{Connection, params};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::sync::{Arc, Mutex};
use subtle::ConstantTimeEq;

const DAY: i64 = 86_400_000;
/// Additive feature flags for `/v1` clients; see docs/API.md "Compatibility".
/// Absent on older servers. Flags are only ever added within `/v1`.
static API_CAPABILITIES: std::sync::LazyLock<serde_json::Value> = std::sync::LazyLock::new(|| {
    serde_json::json!({
        "version": 1,
        "capabilities": ["activity_pages", "activity_search", "json_errors", "retry_after"]
    })
});
const RETENTION: i64 = 30 * DAY;
// Journal records are retained permanently; this bounds only a runaway client.
const MAX_EVENTS: i64 = 2_000_000;

#[derive(Clone)]
pub struct AppState {
    db: Arc<Mutex<Connection>>,
    token_hash: [u8; 32],
    db_slots: Arc<tokio::sync::Semaphore>,
    ai_settings: Option<ai_settings::AiSettingsStore>,
    ai_probe_slot: Arc<tokio::sync::Semaphore>,
    sessions: Arc<Mutex<Vec<web::Session>>>,
}

#[derive(Debug)]
struct ApiError(StatusCode, &'static str);
impl IntoResponse for ApiError {
    fn into_response(self) -> Response {
        (self.0, Json(serde_json::json!({"error":self.1}))).into_response()
    }
}
type Result<T> = std::result::Result<T, ApiError>;
fn storage_error(_: impl std::fmt::Debug) -> ApiError {
    ApiError(StatusCode::SERVICE_UNAVAILABLE, "storage unavailable")
}
fn invalid() -> ApiError {
    ApiError(StatusCode::BAD_REQUEST, "invalid request")
}
/// Lowercase hex SHA-256, byte-for-byte identical to the earlier `{:x}` digest
/// formatting so persisted receipt, topic and brief identifiers stay stable.
fn hex_digest(data: impl AsRef<[u8]>) -> String {
    use std::fmt::Write;
    Sha256::digest(data)
        .iter()
        .fold(String::with_capacity(64), |mut out, b| {
            let _ = write!(out, "{b:02x}");
            out
        })
}
fn now_ms() -> i64 {
    chrono::Utc::now().timestamp_millis()
}

impl AppState {
    pub fn new(db: Connection, token: &str) -> std::result::Result<Self, &'static str> {
        // Generated 32-byte random hex secret; never use a memorable password.
        if token.len() != 64 || !token.bytes().all(|b| b.is_ascii_hexdigit()) {
            return Err("owner token must be 64 hex characters");
        }
        db.busy_timeout(std::time::Duration::from_secs(2))
            .map_err(|_| "database setup failed")?;
        db.execute_batch("PRAGMA journal_mode=WAL; PRAGMA secure_delete=ON;
          CREATE TABLE IF NOT EXISTS activities(id TEXT PRIMARY KEY, start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL, app TEXT NOT NULL, category TEXT NOT NULL);
          CREATE INDEX IF NOT EXISTS activities_end ON activities(end_ms);
          CREATE INDEX IF NOT EXISTS activities_app ON activities(app,start_ms,end_ms);
          CREATE INDEX IF NOT EXISTS activities_start ON activities(start_ms,id);
          CREATE TABLE IF NOT EXISTS checkins(id TEXT PRIMARY KEY, at_ms INTEGER NOT NULL, mood INTEGER NOT NULL, energy INTEGER NOT NULL);
          CREATE INDEX IF NOT EXISTS checkins_at ON checkins(at_ms);
          CREATE TABLE IF NOT EXISTS notes(id TEXT PRIMARY KEY, title TEXT NOT NULL, summary TEXT NOT NULL, category TEXT NOT NULL, edited INTEGER NOT NULL DEFAULT 0);
          CREATE TABLE IF NOT EXISTS reflections(id TEXT PRIMARY KEY, at_ms INTEGER NOT NULL, text TEXT NOT NULL);
          CREATE INDEX IF NOT EXISTS reflections_at ON reflections(at_ms);
          CREATE TABLE IF NOT EXISTS observations(id TEXT PRIMARY KEY, at_ms INTEGER NOT NULL, app TEXT NOT NULL, text TEXT NOT NULL, consent INTEGER NOT NULL, jpeg_base64 TEXT);
          CREATE INDEX IF NOT EXISTS observations_at ON observations(at_ms);
          CREATE INDEX IF NOT EXISTS observations_app ON observations(app,at_ms);
          CREATE TABLE IF NOT EXISTS journal_meta(epoch INTEGER NOT NULL);
          INSERT INTO journal_meta SELECT 0 WHERE NOT EXISTS(SELECT 1 FROM journal_meta);
          CREATE TABLE IF NOT EXISTS analysis_jobs(id TEXT PRIMARY KEY, attempts INTEGER NOT NULL, next_ms INTEGER NOT NULL);
          CREATE TABLE IF NOT EXISTS receipts(id TEXT PRIMARY KEY, at_ms INTEGER NOT NULL, hash TEXT NOT NULL);").map_err(|_| "database setup failed")?;
        devices::migrate(&db, &Sha256::digest(token.as_bytes()).into())
            .map_err(|_| "database setup failed")?;
        replay::migrate(&db).map_err(|_| "database setup failed")?;
        collection::migrate(&db).map_err(|_| "database setup failed")?;
        agents::migrate(&db).map_err(|_| "database setup failed")?;
        briefs::migrate(&db).map_err(|_| "database setup failed")?;
        focus::migrate(&db).map_err(|_| "database setup failed")?;
        conversations::migrate(&db).map_err(|_| "database setup failed")?;
        workspace::migrate(&db).map_err(|_| "database setup failed")?;
        let has_provider_id: bool = db
            .prepare("PRAGMA table_info(observations)")
            .map_err(|_| "database setup failed")?
            .query_map([], |r| r.get::<_, String>(1))
            .map_err(|_| "database setup failed")?
            .collect::<std::result::Result<Vec<_>, _>>()
            .map_err(|_| "database setup failed")?
            .iter()
            .any(|name| name == "provider_id");
        if !has_provider_id {
            db.execute("ALTER TABLE observations ADD COLUMN provider_id TEXT", [])
                .map_err(|_| "database setup failed")?;
        }
        storage::migrate(&db).map_err(|_| "database setup failed")?;
        screen_cards::migrate(&db).map_err(|_| "database setup failed")?;
        topics::migrate(&db).map_err(|_| "database setup failed")?;
        Ok(Self {
            db: Arc::new(Mutex::new(db)),
            token_hash: Sha256::digest(token.as_bytes()).into(),
            db_slots: Arc::new(tokio::sync::Semaphore::new(8)),
            ai_settings: None,
            ai_probe_slot: Arc::new(tokio::sync::Semaphore::new(1)),
            sessions: Arc::new(Mutex::new(Vec::new())),
        })
    }
    pub fn with_ai_settings(
        mut self,
        directory: &std::path::Path,
    ) -> std::result::Result<Self, &'static str> {
        self.ai_settings = Some(ai_settings::AiSettingsStore::new(directory)?);
        Ok(self)
    }
    async fn database<T: Send + 'static>(
        &self,
        f: impl FnOnce(&mut Connection) -> Result<T> + Send + 'static,
    ) -> Result<T> {
        let permit = self
            .db_slots
            .clone()
            .try_acquire_owned()
            .map_err(|_| ApiError(StatusCode::SERVICE_UNAVAILABLE, "server busy"))?;
        let db = self.db.clone();
        tokio::task::spawn_blocking(move || {
            let _permit = permit;
            let mut db = db.lock().map_err(storage_error)?;
            f(&mut db)
        })
        .await
        .map_err(storage_error)?
    }
    pub async fn prune(&self) -> std::result::Result<(), &'static str> {
        self.database(|db| {
            prune(db, now_ms())?;
            Ok(())
        })
        .await
        .map_err(|_| "retention cleanup failed")
    }
}
// Activities, checkins, notes, reflections and raw observation text are kept
// permanently; screenshots are bounded by the owner's storage budget instead.
fn prune(db: &Connection, now: i64) -> Result<()> {
    journal::prune(db, now)?;
    Ok(())
}

async fn authenticate(
    State(state): State<AppState>,
    req: axum::extract::Request,
    next: Next,
) -> Response {
    let token = req
        .headers()
        .get(header::AUTHORIZATION)
        .and_then(|v| v.to_str().ok())
        .and_then(|s| s.strip_prefix("Bearer "))
        .unwrap_or("");
    let hash: [u8; 32] = Sha256::digest(token.as_bytes()).into();
    if !bool::from(state.token_hash.ct_eq(&hash)) && !web::authorized(&state, &req) {
        let valid_token = token.len() == 64 && token.bytes().all(|b| b.is_ascii_hexdigit());
        let allowed = if valid_token {
            devices::authorized(
                &state,
                hash,
                req.method().as_str().to_owned(),
                req.uri().path().to_owned(),
            )
            .await
        } else {
            Ok(false)
        };
        match allowed {
            Ok(true) => {}
            Ok(false) => return ApiError(StatusCode::UNAUTHORIZED, "unauthorized").into_response(),
            Err(error) => return error.into_response(),
        }
    }
    match tokio::time::timeout(std::time::Duration::from_secs(10), next.run(req)).await {
        Ok(response) => response,
        Err(_) => ApiError(StatusCode::REQUEST_TIMEOUT, "request timed out").into_response(),
    }
}
async fn private_headers(req: axum::extract::Request, next: Next) -> Response {
    let mut response = next.run(req).await;
    let is_page = response
        .headers()
        .get(header::CONTENT_TYPE)
        .is_some_and(|v| v.to_str().unwrap_or("").starts_with("text/html"));
    for (key, value) in [
        ("cache-control", "no-store"),
        ("x-content-type-options", "nosniff"),
        ("x-robots-tag", "noindex, nofollow"),
        (
            "content-security-policy",
            if is_page {
                "default-src 'none'; script-src 'self'; style-src 'self'; font-src 'self'; img-src 'self'; connect-src 'self'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'"
            } else {
                "default-src 'none'; frame-ancestors 'none'"
            },
        ),
        ("referrer-policy", "no-referrer"),
    ] {
        response.headers_mut().insert(
            axum::http::HeaderName::from_static(key),
            axum::http::HeaderValue::from_static(value),
        );
    }
    response.headers_mut().insert(
        header::VARY,
        axum::http::HeaderValue::from_static("Authorization, Cookie"),
    );
    response
}
/// Seconds suggested to clients for transient refusals that carry no own hint.
const RETRY_AFTER_SECONDS: &str = "5";
/// Gives every `/v1` error the same `{"error":…}` JSON shape. Framework rejections
/// (malformed JSON, unknown fields, wrong method, oversized body) otherwise answer
/// with plain text. Status codes are kept unchanged for existing clients.
async fn json_errors(req: axum::extract::Request, next: Next) -> Response {
    let api = req.uri().path().starts_with("/v1/");
    let mut response = next.run(req).await;
    let status = response.status();
    if !api || !(status.is_client_error() || status.is_server_error()) {
        return response;
    }
    if matches!(
        status,
        StatusCode::SERVICE_UNAVAILABLE | StatusCode::TOO_MANY_REQUESTS
    ) && !response.headers().contains_key(header::RETRY_AFTER)
    {
        response.headers_mut().insert(
            header::RETRY_AFTER,
            axum::http::HeaderValue::from_static(RETRY_AFTER_SECONDS),
        );
    }
    let is_json = response
        .headers()
        .get(header::CONTENT_TYPE)
        .is_some_and(|v| v.as_bytes().starts_with(b"application/json"));
    if is_json {
        return response;
    }
    let message = match status {
        StatusCode::NOT_FOUND => "not found",
        StatusCode::METHOD_NOT_ALLOWED => "method not allowed",
        StatusCode::PAYLOAD_TOO_LARGE => "request too large",
        StatusCode::UNSUPPORTED_MEDIA_TYPE => "unsupported media type",
        s if s.is_server_error() => "server error",
        _ => "invalid request",
    };
    let headers = response.headers().clone();
    let mut replaced = ApiError(status, message).into_response();
    for name in [header::RETRY_AFTER, header::ALLOW] {
        if let Some(value) = headers.get(&name) {
            replaced.headers_mut().insert(name, value.clone());
        }
    }
    replaced
}
pub fn router(state: AppState) -> Router {
    let private = journal::routes()
        .merge(ai_settings::routes())
        .merge(workspace::routes())
        .merge(conversations::routes())
        .merge(focus::routes())
        .merge(briefs::routes())
        .merge(agents::routes())
        .merge(collection::routes())
        .merge(replay::routes())
        .merge(storage::routes())
        .merge(devices::routes())
        .route("/v1/activities", get(pages::activities).post(ingest))
        .route("/v1/checkins", post(checkin))
        .route("/v1/timeline", get(timeline))
        .route("/v1/journal", delete(erase))
        .route_layer(middleware::from_fn_with_state(state.clone(), authenticate));
    Router::new()
        .route(
            "/healthz",
            get(|| async { Json(serde_json::json!({"status":"ok"})) }),
        )
        .merge(private)
        .merge(web::routes())
        .layer(DefaultBodyLimit::max(64 * 1024))
        .layer(middleware::from_fn(json_errors))
        .layer(middleware::from_fn(private_headers))
        .with_state(state)
}

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq)]
#[serde(deny_unknown_fields)]
pub struct Activity {
    pub id: String,
    pub start_ms: i64,
    pub end_ms: i64,
    pub app: String,
    pub category: Category,
}
#[derive(Clone, Debug, Deserialize, Serialize, PartialEq)]
#[serde(try_from = "String", into = "String")]
pub enum Category {
    Unsorted,
    Work,
    Learning,
    Communication,
    Entertainment,
    LifeAdmin,
    Rest,
    SocialMedia,
    NewsReading,
    Idle,
    Custom(String),
}
impl TryFrom<String> for Category {
    type Error = &'static str;
    fn try_from(id: String) -> std::result::Result<Self, Self::Error> {
        Ok(match id.as_str() {
            "unsorted" => Self::Unsorted,
            "work" => Self::Work,
            "learning" => Self::Learning,
            "communication" => Self::Communication,
            "entertainment" => Self::Entertainment,
            "life_admin" => Self::LifeAdmin,
            "rest" => Self::Rest,
            "social_media" => Self::SocialMedia,
            "news_reading" => Self::NewsReading,
            "idle" => Self::Idle,
            _ if id.starts_with("custom_")
                && (8..=64).contains(&id.len())
                && id
                    .bytes()
                    .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'_') =>
            {
                Self::Custom(id)
            }
            _ => return Err("invalid category"),
        })
    }
}
impl From<Category> for String {
    fn from(category: Category) -> Self {
        match category {
            Category::Unsorted => "unsorted",
            Category::Work => "work",
            Category::Learning => "learning",
            Category::Communication => "communication",
            Category::Entertainment => "entertainment",
            Category::LifeAdmin => "life_admin",
            Category::Rest => "rest",
            Category::SocialMedia => "social_media",
            Category::NewsReading => "news_reading",
            Category::Idle => "idle",
            Category::Custom(id) => return id,
        }
        .into()
    }
}
impl Activity {
    fn valid(&self, now: i64) -> bool {
        valid_id(&self.id)
            && self.start_ms > now - RETENTION
            && self.end_ms <= now + 60_000
            && self.end_ms > self.start_ms
            && self.end_ms - self.start_ms <= DAY
            && !self.app.is_empty()
            && self.app.len() <= 200
            && self
                .app
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || b"._-".contains(&b))
    }
}
fn valid_id(id: &str) -> bool {
    !id.is_empty()
        && id.len() <= 80
        && id
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b"-_".contains(&b))
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Batch {
    activities: Vec<Activity>,
}
async fn ingest(
    State(state): State<AppState>,
    Json(batch): Json<Batch>,
) -> Result<Json<serde_json::Value>> {
    let now = now_ms();
    if batch.activities.is_empty()
        || batch.activities.len() > 100
        || batch.activities.iter().any(|a| !a.valid(now))
    {
        return Err(invalid());
    }
    let accepted = state.database(move |db| {
        let tx = db.transaction().map_err(storage_error)?;
        prune(&tx, now)?;
        collection::ensure_running(&tx)?;
        let mut accepted = 0;
        for a in batch.activities {
            let derived_id: bool = tx.query_row(
                "SELECT EXISTS(SELECT 1 FROM screen_cards WHERE id=?)",
                [&a.id], |r| r.get(0),
            ).map_err(storage_error)?;
            if derived_id { return Err(ApiError(StatusCode::CONFLICT, "event id already used")); }
            let category = serde_json::to_string(&a.category).map_err(storage_error)?;
            let changed = tx.execute("INSERT OR IGNORE INTO activities VALUES (?, ?, ?, ?, ?)", params![a.id,a.start_ms,a.end_ms,a.app,category]).map_err(storage_error)?;
            if changed == 0 {
                let same: bool = tx.query_row("SELECT start_ms = ? AND end_ms = ? AND app = ? AND category = ? FROM activities WHERE id = ?", params![a.start_ms,a.end_ms,a.app,category,a.id], |r| r.get(0)).map_err(storage_error)?;
                if !same { return Err(ApiError(StatusCode::CONFLICT, "event id already used")); }
            }
            if changed > 0 { workspace::ensure_category(&tx, &a.category)?; }
            accepted += changed;
        }
        let count: i64 = tx.query_row("SELECT COUNT(*) FROM activities", [], |r| r.get(0)).map_err(storage_error)?;
        if count > MAX_EVENTS { return Err(ApiError(StatusCode::INSUFFICIENT_STORAGE, "journal capacity reached")); }
        tx.commit().map_err(storage_error)?;
        Ok(accepted)
    }).await?;
    Ok(Json(serde_json::json!({"accepted":accepted})))
}
#[derive(Clone, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
struct Checkin {
    id: String,
    at_ms: i64,
    mood: u8,
    energy: u8,
}
async fn checkin(State(state): State<AppState>, Json(c): Json<Checkin>) -> Result<StatusCode> {
    let now = now_ms();
    if !valid_id(&c.id)
        || c.at_ms <= now - RETENTION
        || c.at_ms > now + 60_000
        || !(1..=5).contains(&c.mood)
        || !(1..=5).contains(&c.energy)
    {
        return Err(invalid());
    }
    state.database(move |db| {
        let tx = db.transaction().map_err(storage_error)?;
        prune(&tx, now)?;
        let count: i64 = tx.query_row("SELECT COUNT(*) FROM checkins", [], |r| r.get(0)).map_err(storage_error)?;
        // Replacement makes network retry idempotent, including at capacity.
        let exists: bool = tx.query_row("SELECT EXISTS(SELECT 1 FROM checkins WHERE id = ?)", [&c.id], |r| r.get(0)).map_err(storage_error)?;
        if count >= 10_000 && !exists { return Err(ApiError(StatusCode::INSUFFICIENT_STORAGE, "journal capacity reached")); }
        tx.execute("INSERT INTO checkins VALUES (?, ?, ?, ?) ON CONFLICT(id) DO UPDATE SET at_ms=excluded.at_ms, mood=excluded.mood, energy=excluded.energy", params![c.id,c.at_ms,c.mood,c.energy]).map_err(storage_error)?;
        tx.commit().map_err(storage_error)?;
        Ok(())
    }).await?;
    Ok(StatusCode::NO_CONTENT)
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Window {
    from_ms: i64,
    to_ms: i64,
}
#[derive(Serialize)]
struct Timeline {
    activities: Vec<Activity>,
    checkins: Vec<Checkin>,
    truncated: bool,
    cards: Vec<journal::Card>,
    reflections: Vec<journal::Reflection>,
}
async fn timeline(
    State(state): State<AppState>,
    Query(w): Query<Window>,
) -> Result<Json<Timeline>> {
    let now = now_ms();
    if w.from_ms < 0
        || w.to_ms > now + 60_000
        || w.to_ms <= w.from_ms
        || w.to_ms - w.from_ms > 26 * 3_600_000
    {
        return Err(invalid());
    }
    state.database(move |db| {
        prune(db, now)?;
        let mut statement = db.prepare("SELECT id, MAX(start_ms, ?1), MIN(end_ms, ?2), app, category FROM activities WHERE end_ms > ?1 AND start_ms < ?2 ORDER BY start_ms, id LIMIT 1001").map_err(storage_error)?;
        let mut activities = statement.query_map([w.from_ms,w.to_ms], |r| {
            let category: String = r.get(4)?;
            Ok(Activity { id:r.get(0)?, start_ms:r.get(1)?, end_ms:r.get(2)?, app:r.get(3)?, category:serde_json::from_str(&category).map_err(|e| rusqlite::Error::FromSqlConversionFailure(4, rusqlite::types::Type::Text, Box::new(e)))? })
        }).map_err(storage_error)?.collect::<std::result::Result<Vec<_>,_>>().map_err(storage_error)?;
        let mut statement = db.prepare("SELECT id, at_ms, mood, energy FROM checkins WHERE at_ms >= ? AND at_ms < ? ORDER BY at_ms, id LIMIT 1001").map_err(storage_error)?;
        let mut checkins = statement.query_map([w.from_ms,w.to_ms], |r| Ok(Checkin {id:r.get(0)?,at_ms:r.get(1)?,mood:r.get(2)?,energy:r.get(3)?})).map_err(storage_error)?.collect::<std::result::Result<Vec<_>,_>>().map_err(storage_error)?;
        let mut truncated = activities.len() > 1000 || checkins.len() > 1000;
        activities.truncate(1000); checkins.truncate(1000);
        let mut cards = journal::cards(db, &activities)?;
        let screen = screen_cards::timeline(db, w.from_ms, w.to_ms)?;
        // Keep the separate raw activities response for usage statistics and legacy clients.
        cards.retain(|card| !card.is_app_only() || !screen.iter().any(|item|
            item.start_ms <= card.start_ms && item.end_ms >= card.end_ms));
        cards.extend(journal::cards(db, &screen)?);
        cards.sort_by(|a, b| (a.start_ms, &a.id).cmp(&(b.start_ms, &b.id)));
        truncated |= cards.len() > 1000 || screen.len() > 1000;
        cards.truncate(1000);
        let reflections = journal::reflections(db, w.from_ms, w.to_ms)?;
        let mut result = Timeline { activities, checkins, truncated, cards, reflections };
        // Bound serialized responses to the client limit even for multibyte user notes.
        while serde_json::to_vec(&result).map_err(storage_error)?.len() > 480 * 1024 {
            result.truncated = true;
            if !result.cards.is_empty() { result.cards.truncate(result.cards.len() / 2); }
            else if !result.reflections.is_empty() { result.reflections.truncate(result.reflections.len() / 2); }
            else if !result.activities.is_empty() { result.activities.truncate(result.activities.len() / 2); }
            else { result.checkins.truncate(result.checkins.len() / 2); }
        }
        Ok(Json(result))
    }).await
}
async fn erase(State(state): State<AppState>) -> Result<StatusCode> {
    state
        .database(|db| {
            let tx = db.transaction().map_err(storage_error)?;
            journal::erase(&tx)?;
            tx.execute("DELETE FROM activities", [])
                .map_err(storage_error)?;
            tx.execute("DELETE FROM checkins", [])
                .map_err(storage_error)?;
            tx.commit().map_err(storage_error)?;
            db.execute_batch("PRAGMA wal_checkpoint(TRUNCATE);")
                .map_err(storage_error)?;
            Ok(())
        })
        .await?;
    Ok(StatusCode::NO_CONTENT)
}

pub fn process_pending(db: &mut Connection) -> std::result::Result<usize, &'static str> {
    analysis::process_with_provider(db, None)
}

pub use analysis::{Provider, process_with_provider};

/// Labels card-title keyword topics while the owner's automatic switch is on.
pub fn process_topics(
    db: &mut Connection,
    provider: Option<&Provider>,
) -> std::result::Result<usize, &'static str> {
    topics::process(db, provider).map_err(|_| "topic labeling failed")
}

/// Runs explicitly opted-in derived-card brief schedules; no raw capture input.
pub fn process_briefs(
    db: &mut Connection,
    provider: Option<&Provider>,
) -> std::result::Result<usize, &'static str> {
    briefs::process(db, provider)
}

#[cfg(test)]
mod digest_tests {
    #[test]
    fn hex_digest_matches_known_sha256_vector() {
        // Pinned so the sha2 upgrade cannot silently change stored identifiers.
        assert_eq!(
            super::hex_digest("abc"),
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        );
    }
}
