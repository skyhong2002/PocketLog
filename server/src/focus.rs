//! One owner-started timer, durable across clients. It never grants capture/model consent.
use super::*;
use rusqlite::OptionalExtension;

pub(super) fn migrate(db: &Connection) -> rusqlite::Result<()> {
    db.execute_batch("CREATE TABLE IF NOT EXISTS focus_session(slot INTEGER PRIMARY KEY CHECK(slot=1),id TEXT UNIQUE NOT NULL,at_ms INTEGER NOT NULL,minutes INTEGER NOT NULL,text TEXT NOT NULL);")
}
#[derive(Deserialize, Serialize, PartialEq)]
#[serde(deny_unknown_fields)]
struct Session {
    id: String,
    at_ms: i64,
    minutes: u16,
    text: String,
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Identity {
    id: String,
}
fn load(db: &Connection) -> Result<Option<Session>> {
    db.query_row(
        "SELECT id,at_ms,minutes,text FROM focus_session WHERE slot=1",
        [],
        |r| {
            Ok(Session {
                id: r.get(0)?,
                at_ms: r.get(1)?,
                minutes: r.get(2)?,
                text: r.get(3)?,
            })
        },
    )
    .optional()
    .map_err(storage_error)
}
pub(super) fn routes() -> Router<AppState> {
    Router::new()
        .route("/v1/focus", get(read).post(start).delete(discard))
        .route("/v1/focus/finish", post(finish))
}
async fn read(State(state): State<AppState>) -> Result<Json<serde_json::Value>> {
    state
        .database(|db| {
            super::prune(db, now_ms())?;
            Ok(Json(serde_json::json!({"session":load(db)?})))
        })
        .await
}
async fn start(
    State(state): State<AppState>,
    Json(session): Json<Session>,
) -> Result<Json<serde_json::Value>> {
    let now = now_ms();
    if !valid_id(&session.id)
        || session.at_ms < now - 60_000
        || session.at_ms > now + 60_000
        || !(1..=180).contains(&session.minutes)
        || session.text.trim().is_empty()
        || session.text.chars().count() > 300
        || session.text.chars().any(char::is_control)
    {
        return Err(invalid());
    }
    state
        .database(move |db| {
            let tx = db.transaction().map_err(storage_error)?;
            super::prune(&tx, now)?;
            if let Some(existing) = load(&tx)? {
                if existing == session {
                    return Ok(Json(serde_json::json!({"session":existing})));
                }
                return Err(ApiError(
                    StatusCode::CONFLICT,
                    "another focus session is active",
                ));
            }
            let completed: bool = tx
                .query_row(
                    "SELECT EXISTS(SELECT 1 FROM review_records WHERE id=?)",
                    [&session.id],
                    |r| r.get(0),
                )
                .map_err(storage_error)?;
            if completed {
                return Err(ApiError(StatusCode::CONFLICT, "focus id already completed"));
            }
            tx.execute(
                "INSERT INTO focus_session VALUES(1,?,?,?,?)",
                params![session.id, session.at_ms, session.minutes, session.text],
            )
            .map_err(storage_error)?;
            tx.commit().map_err(storage_error)?;
            Ok(Json(serde_json::json!({"session":session})))
        })
        .await
}
async fn finish(
    State(state): State<AppState>,
    Json(identity): Json<Identity>,
) -> Result<StatusCode> {
    if !valid_id(&identity.id) {
        return Err(invalid());
    }
    state.database(move|db|{
        let tx=db.transaction().map_err(storage_error)?;let now=now_ms();super::prune(&tx,now)?;
        let existing=load(&tx)?;
        // Retrying a completed session must not finish a newer timer or duplicate a record.
        let prior:Option<String>=tx.query_row("SELECT kind FROM review_records WHERE id=?",[&identity.id],|r|r.get(0)).optional().map_err(storage_error)?;
        if prior.as_deref()==Some("\"flow\"") {return Ok(());}
        let session=existing.filter(|s|s.id==identity.id).ok_or(ApiError(StatusCode::CONFLICT,"focus session changed"))?;
        let count:i64=tx.query_row("SELECT COUNT(*) FROM review_records",[],|r|r.get(0)).map_err(storage_error)?;
        if count>=500 {return Err(ApiError(StatusCode::INSUFFICIENT_STORAGE,"review capacity reached"));}
        let end=now.min(session.at_ms+i64::from(session.minutes)*60_000).max(session.at_ms);
        let record=serde_json::json!({"id":session.id,"at_ms":session.at_ms,"kind":"flow","text":session.text,"end_ms":end,"rating":null,"source":null});
        tx.execute("INSERT INTO review_records VALUES(?,?,?,?)",params![session.id,session.at_ms,"\"flow\"",record.to_string()]).map_err(storage_error)?;
        tx.execute("DELETE FROM focus_session WHERE id=?",[session.id]).map_err(storage_error)?;
        tx.commit().map_err(storage_error)?;Ok(())
    }).await?;
    Ok(StatusCode::NO_CONTENT)
}
async fn discard(
    State(state): State<AppState>,
    Json(identity): Json<Identity>,
) -> Result<StatusCode> {
    if !valid_id(&identity.id) {
        return Err(invalid());
    }
    state
        .database(move |db| {
            db.execute("DELETE FROM focus_session WHERE id=?", [identity.id])
                .map_err(storage_error)?;
            Ok(())
        })
        .await?;
    Ok(StatusCode::NO_CONTENT)
}
