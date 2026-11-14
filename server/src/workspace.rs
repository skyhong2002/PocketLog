//! Shared product setup and explicit private review records, independent of collection permissions.
use super::*;
use rusqlite::OptionalExtension;

pub(super) fn migrate(db: &Connection) -> rusqlite::Result<()> {
    db.execute_batch("CREATE TABLE IF NOT EXISTS workspace(id INTEGER PRIMARY KEY CHECK(id=1), revision INTEGER NOT NULL, json TEXT NOT NULL);
    CREATE TABLE IF NOT EXISTS review_records(id TEXT PRIMARY KEY, at_ms INTEGER NOT NULL, kind TEXT NOT NULL, json TEXT NOT NULL);
    CREATE INDEX IF NOT EXISTS review_records_at ON review_records(at_ms);")
}
#[derive(Clone, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub(super) struct Preferences {
    revision: i64,
    onboarding_version: u8,
    focus_minutes: u16,
    distraction_minutes: u16,
    categories: Vec<CategoryStyle>,
}
#[derive(Clone, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
struct CategoryStyle {
    id: Category,
    name: String,
    color: String,
    role: Role,
}
#[derive(Clone, Deserialize, Serialize)]
#[serde(rename_all = "snake_case")]
enum Role {
    Focus,
    Neutral,
    Distraction,
}
impl Default for Preferences {
    fn default() -> Self {
        let names = [
            (Category::Work, "工作", "#b9a4ed", Role::Focus),
            (Category::Learning, "學習", "#87bcb4", Role::Focus),
            (Category::Communication, "溝通", "#e4be7c", Role::Neutral),
            (Category::SocialMedia, "社群", "#e69c9c", Role::Distraction),
            (
                Category::Entertainment,
                "娛樂",
                "#d4a9c8",
                Role::Distraction,
            ),
            (Category::NewsReading, "閱讀", "#9bbce1", Role::Neutral),
            (Category::LifeAdmin, "生活", "#b4c493", Role::Neutral),
            (Category::Rest, "休息", "#80bda1", Role::Neutral),
            (Category::Idle, "閒置", "#92949e", Role::Neutral),
            (Category::Unsorted, "未分類", "#b1a797", Role::Neutral),
        ];
        Self {
            revision: 0,
            onboarding_version: 0,
            focus_minutes: 270,
            distraction_minutes: 60,
            categories: names
                .into_iter()
                .map(|(id, name, color, role)| CategoryStyle {
                    id,
                    name: name.into(),
                    color: color.into(),
                    role,
                })
                .collect(),
        }
    }
}
fn load(db: &Connection) -> Result<Preferences> {
    let json: Option<String> = db
        .query_row("SELECT json FROM workspace WHERE id=1", [], |r| r.get(0))
        .optional()
        .map_err(storage_error)?;
    json.map(|s| serde_json::from_str(&s).map_err(storage_error))
        .unwrap_or_else(|| Ok(Preferences::default()))
}
pub(super) fn routes() -> Router<AppState> {
    Router::new()
        .route("/v1/workspace", get(read).put(save))
        .route("/v1/reviews", get(records).post(record))
        .route("/v1/ask", post(ask))
}
async fn read(State(state): State<AppState>) -> Result<Json<Preferences>> {
    state.database(|db| Ok(Json(load(db)?))).await
}
async fn save(
    State(state): State<AppState>,
    Json(mut prefs): Json<Preferences>,
) -> Result<Json<Preferences>> {
    if prefs.onboarding_version > 1
        || prefs.focus_minutes > 1440
        || prefs.distraction_minutes > 1440
        || prefs.categories.len() != 10
    {
        return Err(invalid());
    }
    let mut ids = std::collections::BTreeSet::new();
    for c in &prefs.categories {
        if c.name.trim().is_empty()
            || c.name.chars().count() > 40
            || c.name.chars().any(char::is_control)
            || c.color.len() != 7
            || !c.color.starts_with('#')
            || !c.color[1..].bytes().all(|b| b.is_ascii_hexdigit())
            || !ids.insert(serde_json::to_string(&c.id).map_err(storage_error)?)
        {
            return Err(invalid());
        }
    }
    state.database(move |db| {
        let tx=db.transaction().map_err(storage_error)?;
        if load(&tx)?.revision != prefs.revision { return Err(ApiError(StatusCode::CONFLICT,"settings changed; reload before saving")); }
        prefs.revision=prefs.revision.checked_add(1).ok_or_else(invalid)?;
        let json=serde_json::to_string(&prefs).map_err(storage_error)?;
        tx.execute("INSERT INTO workspace VALUES(1,?,?) ON CONFLICT(id) DO UPDATE SET revision=excluded.revision,json=excluded.json",params![prefs.revision,json]).map_err(storage_error)?;
        tx.commit().map_err(storage_error)?;
        Ok(Json(prefs))
    }).await
}
#[derive(Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
struct ReviewRecord {
    id: String,
    at_ms: i64,
    kind: Kind,
    text: String,
    end_ms: Option<i64>,
    rating: Option<Rating>,
    source: Option<AgentSource>,
}
#[derive(Deserialize, Serialize)]
#[serde(rename_all = "snake_case")]
enum Kind {
    Review,
    Flow,
    Agent,
}
#[derive(Deserialize, Serialize)]
#[serde(rename_all = "snake_case")]
enum Rating {
    Focused,
    Neutral,
    Distracted,
}
#[derive(Deserialize, Serialize)]
#[serde(rename_all = "snake_case")]
enum AgentSource {
    Codex,
    Claude,
}
async fn record(
    State(state): State<AppState>,
    Json(item): Json<ReviewRecord>,
) -> Result<StatusCode> {
    let now = now_ms();
    if !valid_id(&item.id)
        || item.at_ms <= now - RETENTION
        || item.at_ms > now + 60_000
        || item.text.chars().count() > 4000
        || item.text.contains('\0')
        || item
            .end_ms
            .is_some_and(|end| end < item.at_ms || end > now + 60_000 || end - item.at_ms > DAY)
        || matches!(item.kind, Kind::Flow) && item.end_ms.is_none()
        || matches!(item.kind, Kind::Agent) && item.source.is_none()
        || matches!(item.kind, Kind::Review) && item.rating.is_none()
    {
        return Err(invalid());
    }
    state.database(move|db|{
        let tx=db.transaction().map_err(storage_error)?; super::prune(&tx,now)?;
        let count:i64=tx.query_row("SELECT COUNT(*) FROM review_records WHERE id != ?",[&item.id],|r|r.get(0)).map_err(storage_error)?;
        if count>=500 {return Err(ApiError(StatusCode::INSUFFICIENT_STORAGE,"review capacity reached"));}
        tx.execute("INSERT INTO review_records VALUES(?,?,?,?) ON CONFLICT(id) DO UPDATE SET at_ms=excluded.at_ms,kind=excluded.kind,json=excluded.json",params![item.id,item.at_ms,serde_json::to_string(&item.kind).map_err(storage_error)?,serde_json::to_string(&item).map_err(storage_error)?]).map_err(storage_error)?;
        tx.commit().map_err(storage_error)?; Ok(())
    }).await?;
    Ok(StatusCode::NO_CONTENT)
}
async fn records(
    State(state): State<AppState>,
    Query(w): Query<Window>,
) -> Result<Json<serde_json::Value>> {
    let now = now_ms();
    if w.from_ms < now - RETENTION
        || w.to_ms > now + 60_000
        || w.to_ms <= w.from_ms
        || w.to_ms - w.from_ms > RETENTION
    {
        return Err(invalid());
    }
    state.database(move|db|{super::prune(db,now)?;
        let rows:Vec<String>=db.prepare("SELECT json FROM review_records WHERE at_ms>=? AND at_ms<? ORDER BY at_ms DESC LIMIT 101").map_err(storage_error)?.query_map([w.from_ms,w.to_ms],|r|r.get(0)).map_err(storage_error)?.collect::<std::result::Result<_,_>>().map_err(storage_error)?;
        let mut truncated=rows.len()>100;
        let mut records:Vec<serde_json::Value>=rows.into_iter().take(100).map(|s|serde_json::from_str(&s).map_err(storage_error)).collect::<Result<_>>()?;
        while serde_json::to_vec(&records).map_err(storage_error)?.len() > 470 * 1024 { records.pop(); truncated=true; }
        Ok(Json(serde_json::json!({"records":records,"truncated":truncated})))
    }).await
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Ask {
    question: String,
    from_ms: i64,
    to_ms: i64,
    provider_id: String,
    consent: bool,
}
async fn ask(State(state): State<AppState>, Json(q): Json<Ask>) -> Result<Json<serde_json::Value>> {
    let now = now_ms();
    if !q.consent
        || q.question.trim().is_empty()
        || q.question.chars().count() > 1000
        || q.from_ms < now - RETENTION
        || q.to_ms > now + 60_000
        || q.to_ms <= q.from_ms
        || q.to_ms - q.from_ms > 7 * DAY
    {
        return Err(invalid());
    }
    let store = state.ai_settings.clone().ok_or_else(invalid)?;
    if !store.matches(&q.provider_id) {
        return Err(ApiError(
            StatusCode::CONFLICT,
            "provider changed; renew consent",
        ));
    }
    let permit = state
        .ai_probe_slot
        .clone()
        .try_acquire_owned()
        .map_err(|_| ApiError(StatusCode::TOO_MANY_REQUESTS, "model busy"))?;
    let (evidence,epoch)=state.database(move|db|{
        super::prune(db,now)?;
        let mut stmt=db.prepare("SELECT a.app,MAX(a.start_ms,?1),MIN(a.end_ms,?2),COALESCE(n.title,''),COALESCE(n.summary,'') FROM activities a LEFT JOIN notes n ON n.id=a.id WHERE a.end_ms>?1 AND a.start_ms<?2 ORDER BY a.start_ms LIMIT 101").map_err(storage_error)?;
        let rows:Vec<serde_json::Value>=stmt.query_map([q.from_ms,q.to_ms],|r|Ok(serde_json::json!({"app":r.get::<_,String>(0)?,"start_ms":r.get::<_,i64>(1)?,"end_ms":r.get::<_,i64>(2)?,"title":r.get::<_,String>(3)?,"summary":r.get::<_,String>(4)?}))).map_err(storage_error)?.collect::<std::result::Result<_,_>>().map_err(storage_error)?;
        let mut rows=rows; rows.truncate(100);
        let mut text=serde_json::to_string(&rows).map_err(storage_error)?;
        // No raw screenshots, check-ins, reflections or hidden context. Entire entries only.
        while text.len()>24000 {rows.pop();text=serde_json::to_string(&rows).map_err(storage_error)?;}
        let epoch:i64=db.query_row("SELECT epoch FROM journal_meta",[],|r|r.get(0)).map_err(storage_error)?;
        Ok((text,epoch))
    }).await?;
    let answer = tokio::task::spawn_blocking(move || {
        let _permit = permit;
        let provider = analysis::Provider::from_store(&store)
            .map_err(storage_error)?
            .ok_or_else(invalid)?;
        provider
            .answer(&q.provider_id, &q.question, &evidence)
            .map_err(|_| ApiError(StatusCode::BAD_GATEWAY, "model request failed"))
    })
    .await
    .map_err(storage_error)??;
    state.database(move|db|{
        let current:i64=db.query_row("SELECT epoch FROM journal_meta",[],|r|r.get(0)).map_err(storage_error)?;
        if current!=epoch {return Err(ApiError(StatusCode::CONFLICT,"journal or provider changed"));}
        Ok(Json(serde_json::json!({"answer":answer,"scope":"up to 100 derived cards; no raw context","stored":false})))
    }).await
}
