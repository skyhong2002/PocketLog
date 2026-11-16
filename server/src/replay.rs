//! Separate opt-in, 24-hour screenshot archive. Analysis-only observations remain unreadable.
use super::*;
use base64::Engine;
use rusqlite::OptionalExtension;
pub(super) fn migrate(db: &Connection) -> rusqlite::Result<()> {
    db.execute_batch("CREATE TABLE IF NOT EXISTS image_replay(id TEXT PRIMARY KEY,at_ms INTEGER NOT NULL,app TEXT NOT NULL,jpeg TEXT NOT NULL,validated INTEGER NOT NULL DEFAULT 0);")
}
pub(super) fn insert(db: &Connection, id: &str, at: i64, app: &str, jpeg: &str) -> Result<()> {
    let count: i64 = db
        .query_row("SELECT COUNT(*) FROM image_replay", [], |r| r.get(0))
        .map_err(storage_error)?;
    if count >= 256 {
        return Err(ApiError(
            StatusCode::INSUFFICIENT_STORAGE,
            "replay capacity reached",
        ));
    }
    db.execute(
        "INSERT INTO image_replay(id,at_ms,app,jpeg) VALUES(?,?,?,?)",
        params![id, at, app, jpeg],
    )
    .map_err(storage_error)?;
    Ok(())
}
pub(super) fn routes() -> Router<AppState> {
    Router::new()
        .route("/v1/replay", get(list))
        .route("/v1/replay/{id}", get(read).delete(remove))
        .route("/v1/replay/{id}/image", get(image))
}
async fn list(
    State(state): State<AppState>,
    Query(w): Query<Window>,
) -> Result<Json<serde_json::Value>> {
    let now = now_ms();
    if w.from_ms < 0
        || w.to_ms <= w.from_ms
        || w.to_ms > w.from_ms + RETENTION
        || w.to_ms > now + 60_000
    {
        return Err(invalid());
    }
    state.database(move|db|{super::prune(db,now)?;
        let rows:Vec<serde_json::Value>=db.prepare("SELECT id,at_ms,app FROM image_replay WHERE validated=1 AND at_ms>=? AND at_ms<? ORDER BY at_ms LIMIT 256").map_err(storage_error)?.query_map([w.from_ms,w.to_ms],|r|Ok(serde_json::json!({"id":r.get::<_,String>(0)?,"at_ms":r.get::<_,i64>(1)?,"app":r.get::<_,String>(2)?}))).map_err(storage_error)?.collect::<std::result::Result<_,_>>().map_err(storage_error)?;
        Ok(Json(serde_json::json!({"images":rows,"retention_hours":24})))
    }).await
}
async fn bytes(state: AppState, id: String) -> Result<String> {
    if !valid_id(&id) {
        return Err(invalid());
    }
    state
        .database(move |db| {
            super::prune(db, now_ms())?;
            db.query_row(
                "SELECT jpeg FROM image_replay WHERE id=? AND validated=1",
                [id],
                |r| r.get(0),
            )
            .optional()
            .map_err(storage_error)?
            .ok_or(ApiError(StatusCode::NOT_FOUND, "image not available"))
        })
        .await
}
async fn read(
    State(state): State<AppState>,
    axum::extract::Path(id): axum::extract::Path<String>,
) -> Result<Json<serde_json::Value>> {
    Ok(Json(
        serde_json::json!({"jpeg_base64":bytes(state,id).await?}),
    ))
}
async fn image(
    State(state): State<AppState>,
    axum::extract::Path(id): axum::extract::Path<String>,
) -> Result<impl IntoResponse> {
    let jpeg = bytes(state, id).await?;
    let clear = base64::engine::general_purpose::STANDARD
        .decode(jpeg)
        .map_err(storage_error)?;
    Ok(([(header::CONTENT_TYPE, "image/jpeg")], clear))
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
            db.execute("DELETE FROM image_replay WHERE id=?", [id])
                .map_err(storage_error)?;
            Ok(())
        })
        .await?;
    Ok(StatusCode::NO_CONTENT)
}
// Decoder runs only in the isolated worker. Re-encode to strip metadata before exposing a thumbnail.
pub(crate) fn validate_pending(db: &mut Connection) -> Result<usize> {
    let epoch: i64 = db
        .query_row("SELECT epoch FROM journal_meta", [], |r| r.get(0))
        .map_err(storage_error)?;

    let rows: Vec<(String, String)> = db
        .prepare(
            "SELECT id,jpeg FROM image_replay WHERE validated=0 AND at_ms>? ORDER BY at_ms LIMIT 6",
        )
        .map_err(storage_error)?
        .query_map([now_ms() - DAY], |r| Ok((r.get(0)?, r.get(1)?)))
        .map_err(storage_error)?
        .collect::<std::result::Result<_, _>>()
        .map_err(storage_error)?;
    let mut count = 0;
    for (id, jpeg) in rows {
        let sanitized = crate::analysis::sanitize_jpeg(&jpeg);
        let tx = db.transaction().map_err(storage_error)?;
        let current: i64 = tx
            .query_row("SELECT epoch FROM journal_meta", [], |r| r.get(0))
            .map_err(storage_error)?;
        if current != epoch {
            continue;
        }
        if let Some(jpeg) = sanitized {
            tx.execute(
                "UPDATE image_replay SET jpeg=?,validated=1 WHERE id=? AND at_ms>?",
                params![jpeg, id, now_ms() - DAY],
            )
            .map_err(storage_error)?;
            count += 1;
        } else {
            tx.execute("DELETE FROM image_replay WHERE id=?", [id])
                .map_err(storage_error)?;
        }
        tx.commit().map_err(storage_error)?;
    }
    Ok(count)
}
