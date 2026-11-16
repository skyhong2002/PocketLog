//! Shared stop switch. Resuming never grants any phone permission or collection consent.
//! Pausing discards unanalyzed context; already analyzed journal history is retained.
use super::*;
pub(super) fn migrate(db: &Connection) -> rusqlite::Result<()> {
    db.execute_batch("CREATE TABLE IF NOT EXISTS collection_control(slot INTEGER PRIMARY KEY CHECK(slot=1),revision INTEGER NOT NULL,paused INTEGER NOT NULL,changed_ms INTEGER NOT NULL);INSERT OR IGNORE INTO collection_control VALUES(1,0,0,0);")
}
#[derive(Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
struct Control {
    revision: i64,
    paused: bool,
    changed_ms: i64,
}
fn load(db: &Connection) -> Result<Control> {
    db.query_row(
        "SELECT revision,paused,changed_ms FROM collection_control WHERE slot=1",
        [],
        |r| {
            Ok(Control {
                revision: r.get(0)?,
                paused: r.get(1)?,
                changed_ms: r.get(2)?,
            })
        },
    )
    .map_err(storage_error)
}
pub(super) fn ensure_running(db: &Connection) -> Result<()> {
    if load(db)?.paused {
        Err(ApiError(StatusCode::LOCKED, "collection is paused"))
    } else {
        Ok(())
    }
}
pub(super) fn routes() -> Router<AppState> {
    Router::new().route("/v1/collection", get(read).put(save))
}
async fn read(State(state): State<AppState>) -> Result<Json<Control>> {
    state.database(|db| Ok(Json(load(db)?))).await
}
async fn save(State(state): State<AppState>, Json(next): Json<Control>) -> Result<Json<Control>> {
    state.database(move|db|{let tx=db.transaction().map_err(storage_error)?;let old=load(&tx)?;
        if old.revision!=next.revision {return Err(ApiError(StatusCode::CONFLICT,"collection state changed; reload"));}
        let next=Control{revision:old.revision.checked_add(1).ok_or_else(invalid)?,paused:next.paused,changed_ms:now_ms()};
        tx.execute("UPDATE collection_control SET revision=?,paused=?,changed_ms=? WHERE slot=1",params![next.revision,next.paused,next.changed_ms]).map_err(storage_error)?;
        if next.paused {tx.execute_batch("UPDATE journal_meta SET epoch=epoch+1;DELETE FROM observations WHERE processed=0;DELETE FROM analysis_jobs;DELETE FROM image_replay WHERE validated=0;").map_err(storage_error)?;}
        tx.commit().map_err(storage_error)?;Ok(Json(next))
    }).await
}
