//! Owner storage budget for retained raw context. Text is kept permanently;
//! when the journal exceeds the budget the oldest screenshots are removed first.
use super::*;

pub(crate) const GIB: i64 = 1024 * 1024 * 1024;
const DEFAULT_LIMIT: i64 = 10 * GIB;
const MIN_LIMIT: i64 = GIB;
const MAX_LIMIT: i64 = 1024 * GIB;
// Evict below the limit so a full journal does not churn on every capture.
const TARGET_PERCENT: i64 = 95;
const EVICTION_BATCH: i64 = 200;
const MAX_BATCHES_PER_PASS: usize = 50;

pub(super) fn migrate(db: &Connection) -> rusqlite::Result<()> {
    let columns: Vec<String> = db
        .prepare("PRAGMA table_info(observations)")?
        .query_map([], |r| r.get::<_, String>(1))?
        .collect::<std::result::Result<_, _>>()?;
    if !columns.iter().any(|c| c == "processed") {
        // Existing rows predate retention: every stored row still awaits analysis.
        db.execute(
            "ALTER TABLE observations ADD COLUMN processed INTEGER NOT NULL DEFAULT 0",
            [],
        )?;
    }
    if !columns.iter().any(|c| c == "jpeg_bytes") {
        db.execute("ALTER TABLE observations ADD COLUMN jpeg_bytes INTEGER", [])?;
        db.execute(
            "UPDATE observations SET jpeg_bytes=LENGTH(jpeg_base64) WHERE jpeg_base64 IS NOT NULL",
            [],
        )?;
    }
    if !columns.iter().any(|c| c == "protected_screen") {
        // A secure/DRM window (e.g. a video app) yielded text but no pixels.
        db.execute(
            "ALTER TABLE observations ADD COLUMN protected_screen INTEGER NOT NULL DEFAULT 0",
            [],
        )?;
    }
    db.execute_batch(
        "CREATE INDEX IF NOT EXISTS observations_pending ON observations(processed,at_ms);
         CREATE INDEX IF NOT EXISTS observations_images ON observations(at_ms,jpeg_bytes) WHERE jpeg_bytes IS NOT NULL;
         CREATE TABLE IF NOT EXISTS storage_settings(slot INTEGER PRIMARY KEY CHECK(slot=1),revision INTEGER NOT NULL,limit_bytes INTEGER NOT NULL,text_only_before_ms INTEGER);",
    )?;
    db.execute(
        "INSERT OR IGNORE INTO storage_settings VALUES(1,0,?,NULL)",
        [DEFAULT_LIMIT],
    )?;
    Ok(())
}

#[derive(Serialize)]
pub(crate) struct Usage {
    revision: i64,
    limit_bytes: i64,
    used_bytes: i64,
    image_bytes: i64,
    image_count: i64,
    text_only_before_ms: Option<i64>,
    min_limit_bytes: i64,
    max_limit_bytes: i64,
    default_limit_bytes: i64,
}

fn used_bytes(db: &Connection) -> Result<i64> {
    // Pages in use, excluding freed pages that SQLite reuses for new rows.
    db.query_row(
        "SELECT (p.page_count-f.freelist_count)*s.page_size FROM pragma_page_count() p,pragma_freelist_count() f,pragma_page_size() s",
        [],
        |r| r.get(0),
    )
    .map_err(storage_error)
}

pub(crate) fn usage(db: &Connection) -> Result<Usage> {
    let (revision, limit_bytes, text_only_before_ms) = db
        .query_row(
            "SELECT revision,limit_bytes,text_only_before_ms FROM storage_settings WHERE slot=1",
            [],
            |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
        )
        .map_err(storage_error)?;
    let (image_count, image_bytes) = db
        .query_row(
            "SELECT COUNT(*),COALESCE(SUM(jpeg_bytes),0) FROM observations WHERE jpeg_bytes IS NOT NULL",
            [],
            |r| Ok((r.get(0)?, r.get(1)?)),
        )
        .map_err(storage_error)?;
    Ok(Usage {
        revision,
        limit_bytes,
        used_bytes: used_bytes(db)?,
        image_bytes,
        image_count,
        text_only_before_ms,
        min_limit_bytes: MIN_LIMIT,
        max_limit_bytes: MAX_LIMIT,
        default_limit_bytes: DEFAULT_LIMIT,
    })
}

/// Removes the oldest already-analyzed screenshots until usage is below the budget.
/// Observation text, activities and notes are never removed here.
pub(crate) fn enforce(db: &Connection) -> Result<usize> {
    let limit: i64 = db
        .query_row(
            "SELECT limit_bytes FROM storage_settings WHERE slot=1",
            [],
            |r| r.get(0),
        )
        .map_err(storage_error)?;
    let target = limit / 100 * TARGET_PERCENT;
    let mut used = used_bytes(db)?;
    if used <= limit {
        return Ok(0);
    }
    let mut removed = 0;
    for _ in 0..MAX_BATCHES_PER_PASS {
        if used <= target {
            break;
        }
        let rows: Vec<(String, i64, i64)> = db
            .prepare("SELECT id,at_ms,jpeg_bytes FROM observations WHERE jpeg_bytes IS NOT NULL AND processed=1 ORDER BY at_ms LIMIT ?")
            .map_err(storage_error)?
            .query_map([EVICTION_BATCH], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)))
            .map_err(storage_error)?
            .collect::<std::result::Result<_, _>>()
            .map_err(storage_error)?;
        if rows.is_empty() {
            break;
        }
        let tx = db.unchecked_transaction().map_err(storage_error)?;
        let mut newest = 0;
        let mut freed = 0;
        for (id, at, bytes) in rows {
            if used - freed <= target {
                break;
            }
            tx.execute(
                "UPDATE observations SET jpeg_base64=NULL,jpeg_bytes=NULL WHERE id=?",
                [&id],
            )
            .map_err(storage_error)?;
            newest = newest.max(at);
            freed += bytes;
            removed += 1;
        }
        tx.execute(
            "UPDATE storage_settings SET text_only_before_ms=MAX(COALESCE(text_only_before_ms,0),?) WHERE slot=1",
            [newest + 1],
        )
        .map_err(storage_error)?;
        tx.commit().map_err(storage_error)?;
        let measured = used_bytes(db)?;
        // Page accounting lags row sizes; never loop forever on an estimate.
        used = if measured < used {
            measured
        } else {
            used - freed
        };
    }
    Ok(removed)
}

pub(super) fn routes() -> Router<AppState> {
    Router::new().route("/v1/storage", get(read).put(save))
}
async fn read(State(state): State<AppState>) -> Result<Json<Usage>> {
    state.database(|db| Ok(Json(usage(db)?))).await
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Change {
    revision: i64,
    limit_bytes: i64,
}
async fn save(State(state): State<AppState>, Json(change): Json<Change>) -> Result<Json<Usage>> {
    if !(MIN_LIMIT..=MAX_LIMIT).contains(&change.limit_bytes) {
        return Err(invalid());
    }
    state
        .database(move |db| {
            let tx = db.transaction().map_err(storage_error)?;
            let changed = tx
                .execute(
                    "UPDATE storage_settings SET revision=revision+1,limit_bytes=? WHERE slot=1 AND revision=?",
                    params![change.limit_bytes, change.revision],
                )
                .map_err(storage_error)?;
            if changed == 0 {
                return Err(ApiError(
                    StatusCode::CONFLICT,
                    "storage settings changed; reload",
                ));
            }
            tx.commit().map_err(storage_error)?;
            Ok(Json(usage(db)?))
        })
        .await
}

#[cfg(test)]
mod tests {
    use super::*;
    fn database() -> Connection {
        let state = AppState::new(
            Connection::open_in_memory().unwrap(),
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        )
        .unwrap();
        Arc::try_unwrap(state.db)
            .ok()
            .unwrap()
            .into_inner()
            .unwrap()
    }
    fn insert(db: &Connection, id: &str, at: i64, processed: bool, bytes: usize) {
        let jpeg = "A".repeat(bytes);
        db.execute(
            "INSERT INTO observations(id,at_ms,app,text,consent,jpeg_base64,provider_id,processed,jpeg_bytes) VALUES(?,?,'org.example.app','synthetic text',1,?,NULL,?,?)",
            params![id, at, jpeg, processed, bytes as i64],
        )
        .unwrap();
    }
    #[test]
    fn default_budget_is_ten_gib_and_changes_require_current_revision() {
        let db = database();
        let usage = usage(&db).unwrap();
        assert_eq!(usage.limit_bytes, 10 * GIB);
        assert_eq!(usage.revision, 0);
        assert_eq!(
            db.execute(
                "UPDATE storage_settings SET revision=revision+1,limit_bytes=? WHERE slot=1 AND revision=?",
                params![2 * GIB, 7],
            )
            .unwrap(),
            0
        );
    }
    #[test]
    fn over_budget_removes_oldest_analyzed_images_and_keeps_text() {
        let db = database();
        for i in 0..40 {
            insert(&db, &format!("old-{i}"), 1_000 + i, true, 64 * 1024);
        }
        insert(&db, "pending", 5_000, false, 64 * 1024);
        // A tiny synthetic budget forces eviction of every analyzed image.
        db.execute("UPDATE storage_settings SET limit_bytes=1 WHERE slot=1", [])
            .unwrap();
        assert!(enforce(&db).unwrap() > 0);
        let (texts, images): (i64, i64) = db
            .query_row(
                "SELECT COUNT(*),COUNT(jpeg_base64) FROM observations",
                [],
                |r| Ok((r.get(0)?, r.get(1)?)),
            )
            .unwrap();
        assert_eq!(texts, 41);
        // Unanalyzed evidence keeps its image until the worker has used it.
        assert_eq!(images, 1);
        let before: Option<i64> = db
            .query_row(
                "SELECT text_only_before_ms FROM storage_settings",
                [],
                |r| r.get(0),
            )
            .unwrap();
        assert_eq!(before, Some(1_040));
    }
    #[test]
    fn under_budget_keeps_every_image() {
        let db = database();
        insert(&db, "kept", 1_000, true, 1024);
        assert_eq!(enforce(&db).unwrap(), 0);
        assert_eq!(usage(&db).unwrap().image_count, 1);
    }
}
