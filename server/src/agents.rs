//! Explicitly approved agent turns. No filesystem discovery, execution or implicit model access.
use super::*;
use rusqlite::OptionalExtension;

pub(super) fn migrate(db: &Connection) -> rusqlite::Result<()> {
    db.execute_batch("CREATE TABLE IF NOT EXISTS agent_sessions(id TEXT PRIMARY KEY,at_ms INTEGER NOT NULL,revision INTEGER NOT NULL,json TEXT NOT NULL);")
}
#[derive(Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
struct Session {
    id: String,
    revision: i64,
    at_ms: i64,
    source: Source,
    project: String,
    title: String,
    status: Status,
    turns: Vec<Turn>,
}
#[derive(Deserialize, Serialize)]
#[serde(rename_all = "snake_case")]
enum Source {
    Codex,
    Claude,
}
#[derive(Deserialize, Serialize)]
#[serde(rename_all = "snake_case")]
enum Status {
    Active,
    Waiting,
    Completed,
}
#[derive(Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
struct Turn {
    id: String,
    at_ms: i64,
    role: Role,
    text: String,
}
#[derive(Deserialize, Serialize)]
#[serde(rename_all = "snake_case")]
enum Role {
    User,
    Assistant,
}
fn read_session(db: &Connection, id: &str) -> Result<Session> {
    let json: Option<String> = db
        .query_row("SELECT json FROM agent_sessions WHERE id=?", [id], |r| {
            r.get(0)
        })
        .optional()
        .map_err(storage_error)?;
    serde_json::from_str(&json.ok_or(ApiError(StatusCode::NOT_FOUND, "agent session not found"))?)
        .map_err(storage_error)
}
pub(super) fn routes() -> Router<AppState> {
    Router::new()
        .route("/v1/agent-sessions", get(list).put(save))
        .route("/v1/agent-sessions/{id}", get(read).delete(remove))
}
fn label_ok(value: &str, max: usize) -> bool {
    !value.trim().is_empty() && value.chars().count() <= max && !value.chars().any(char::is_control)
}
async fn save(
    State(state): State<AppState>,
    Json(mut session): Json<Session>,
) -> Result<Json<Session>> {
    let now = now_ms();
    let mut ids = std::collections::BTreeSet::new();
    let mut previous = now - RETENTION;
    if !valid_id(&session.id)
        || !label_ok(&session.project, 80)
        || !label_ok(&session.title, 200)
        || session.at_ms <= now - RETENTION
        || session.at_ms > now + 60_000
        || session.turns.is_empty()
        || session.turns.len() > 40
    {
        return Err(invalid());
    }
    for turn in &session.turns {
        if !valid_id(&turn.id)
            || !ids.insert(&turn.id)
            || turn.at_ms < previous
            || turn.at_ms > session.at_ms
            || turn.text.trim().is_empty()
            || turn.text.chars().count() > 1000
            || turn
                .text
                .chars()
                .any(|c| c.is_control() && c != '\n' && c != '\t')
        {
            return Err(invalid());
        }
        previous = turn.at_ms;
    }
    if previous != session.at_ms {
        return Err(invalid());
    }
    state.database(move|db|{let tx=db.transaction().map_err(storage_error)?;super::prune(&tx,now)?;
        let prior:Option<i64>=tx.query_row("SELECT revision FROM agent_sessions WHERE id=?",[&session.id],|r|r.get(0)).optional().map_err(storage_error)?;
        if prior.unwrap_or(0)!=session.revision {return Err(ApiError(StatusCode::CONFLICT,"agent session changed; reload"));}
        let count:i64=tx.query_row("SELECT COUNT(*) FROM agent_sessions WHERE id!=?",[&session.id],|r|r.get(0)).map_err(storage_error)?;
        if count>=100 {return Err(ApiError(StatusCode::INSUFFICIENT_STORAGE,"agent session capacity reached"));}
        session.revision=session.revision.checked_add(1).ok_or_else(invalid)?;
        tx.execute("INSERT INTO agent_sessions VALUES(?,?,?,?) ON CONFLICT(id) DO UPDATE SET at_ms=excluded.at_ms,revision=excluded.revision,json=excluded.json",params![session.id,session.at_ms,session.revision,serde_json::to_string(&session).map_err(storage_error)?]).map_err(storage_error)?;
        tx.commit().map_err(storage_error)?;Ok(Json(session))
    }).await
}
async fn list(State(state): State<AppState>) -> Result<Json<serde_json::Value>> {
    state.database(|db|{super::prune(db,now_ms())?;
        let ids:Vec<String>=db.prepare("SELECT id FROM agent_sessions ORDER BY at_ms DESC,id LIMIT 100").map_err(storage_error)?.query_map([],|r|r.get(0)).map_err(storage_error)?.collect::<std::result::Result<_,_>>().map_err(storage_error)?;
        let sessions=ids.iter().map(|id|{let s=read_session(db,id)?;Ok(serde_json::json!({"id":s.id,"at_ms":s.at_ms,"revision":s.revision,"source":s.source,"project":s.project,"title":s.title,"status":s.status,"turn_count":s.turns.len()}))}).collect::<Result<Vec<_>>>()?;
        Ok(Json(serde_json::json!({"sessions":sessions})))
    }).await
}
async fn read(
    State(state): State<AppState>,
    axum::extract::Path(id): axum::extract::Path<String>,
) -> Result<Json<Session>> {
    if !valid_id(&id) {
        return Err(invalid());
    }
    state
        .database(move |db| {
            super::prune(db, now_ms())?;
            Ok(Json(read_session(db, &id)?))
        })
        .await
}
async fn remove(
    State(state): State<AppState>,
    axum::extract::Path(id): axum::extract::Path<String>,
) -> Result<StatusCode> {
    if !valid_id(&id) {
        return Err(invalid());
    }
    state
        .database(move |db| {
            db.execute("DELETE FROM agent_sessions WHERE id=?", [id])
                .map_err(storage_error)?;
            Ok(())
        })
        .await?;
    Ok(StatusCode::NO_CONTENT)
}
