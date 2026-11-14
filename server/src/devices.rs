//! Owner-issued, independently expiring/revocable credentials. No bearer values in URLs or reads.
use super::*;
use rusqlite::OptionalExtension;

pub(super) fn migrate(db: &Connection, owner: &[u8; 32]) -> rusqlite::Result<()> {
    db.execute_batch("CREATE TABLE IF NOT EXISTS device_credentials(id TEXT PRIMARY KEY,label TEXT NOT NULL,scope TEXT NOT NULL,created_ms INTEGER NOT NULL,expires_ms INTEGER NOT NULL,hash BLOB NOT NULL);CREATE TABLE IF NOT EXISTS credential_owner(slot INTEGER PRIMARY KEY CHECK(slot=1),hash BLOB NOT NULL);")?;
    // Keep the credential table's six-column layout compatible with rollback
    // binaries. A trigger also cleans metadata when an older binary revokes or
    // prunes credentials, without depending on connection-wide foreign keys.
    db.execute_batch("CREATE TABLE IF NOT EXISTS device_authentication(id TEXT PRIMARY KEY,last_authenticated_ms INTEGER NOT NULL);CREATE TRIGGER IF NOT EXISTS delete_device_authentication AFTER DELETE ON device_credentials BEGIN DELETE FROM device_authentication WHERE id=OLD.id; END;")?;
    let previous: Option<Vec<u8>> = db
        .query_row("SELECT hash FROM credential_owner WHERE slot=1", [], |r| {
            r.get(0)
        })
        .optional()?;
    if previous.as_deref() != Some(owner.as_slice()) {
        db.execute("DELETE FROM device_credentials", [])?;
        db.execute("INSERT INTO credential_owner VALUES(1,?) ON CONFLICT(slot) DO UPDATE SET hash=excluded.hash",[owner.as_slice()])?;
    }
    Ok(())
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Create {
    label: String,
    scope: Scope,
    days: u16,
}
#[derive(Clone, Copy, Deserialize, Serialize)]
#[serde(rename_all = "snake_case")]
enum Scope {
    Journal,
    Collector,
}
impl Scope {
    fn name(self) -> &'static str {
        match self {
            Self::Journal => "journal",
            Self::Collector => "collector",
        }
    }
}
pub(super) fn routes() -> Router<AppState> {
    Router::new()
        .route("/v1/devices", get(list).post(create))
        .route("/v1/devices/{id}", delete(revoke))
}
pub(super) async fn authorized(
    state: &AppState,
    hash: [u8; 32],
    method: String,
    path: String,
) -> Result<bool> {
    if path.starts_with("/v1/devices") {
        return Ok(false);
    }
    state
        .database(move |db| {
            let now = now_ms();
            let minute = now - now.rem_euclid(60_000);
            let rows: Vec<(String, Vec<u8>, String)> = db
                .prepare("SELECT id,hash,scope FROM device_credentials WHERE expires_ms>?")
                .map_err(storage_error)?
                .query_map([now], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)))
                .map_err(storage_error)?
                .collect::<std::result::Result<_, _>>()
                .map_err(storage_error)?;
            let mut allowed = false;
            let mut authenticated_ids = Vec::new();
            for (id, stored, scope) in rows {
                let matches = bool::from(stored.as_slice().ct_eq(&hash));
                let permitted = scope == "journal"
                    || scope == "collector"
                        && ((method == "POST"
                            && matches!(path.as_str(), "/v1/activities" | "/v1/observations"))
                            || (method == "GET"
                                && matches!(path.as_str(), "/v1/collection" | "/v1/ai")));
                allowed |= matches && permitted;
                if matches && permitted {
                    authenticated_ids.push(id);
                }
            }
            // Diagnostic metadata never changes the authorization decision. A
            // read-only/full disk must not prevent an otherwise permitted read.
            for id in authenticated_ids {
                let _ = db.execute(
                    "INSERT INTO device_authentication(id,last_authenticated_ms) VALUES(?2,?1) ON CONFLICT(id) DO UPDATE SET last_authenticated_ms=excluded.last_authenticated_ms WHERE device_authentication.last_authenticated_ms<excluded.last_authenticated_ms",
                    params![minute, id],
                );
            }
            Ok(allowed)
        })
        .await
}
async fn list(State(state): State<AppState>) -> Result<Json<serde_json::Value>> {
    state.database(|db|{
        let rows:Vec<serde_json::Value>=db.prepare("SELECT c.id,c.label,c.scope,c.created_ms,c.expires_ms,a.last_authenticated_ms FROM device_credentials c LEFT JOIN device_authentication a ON a.id=c.id ORDER BY c.created_ms DESC LIMIT 32").map_err(storage_error)?.query_map([],|r|Ok(serde_json::json!({"id":r.get::<_,String>(0)?,"label":r.get::<_,String>(1)?,"scope":r.get::<_,String>(2)?,"created_ms":r.get::<_,i64>(3)?,"expires_ms":r.get::<_,i64>(4)?,"last_authenticated_ms":r.get::<_,Option<i64>>(5)?}))).map_err(storage_error)?.collect::<std::result::Result<_,_>>().map_err(storage_error)?;
        Ok(Json(serde_json::json!({"devices":rows})))
    }).await
}
async fn create(
    State(state): State<AppState>,
    Json(input): Json<Create>,
) -> Result<Json<serde_json::Value>> {
    if input.label.trim().is_empty()
        || input.label.chars().count() > 80
        || input.label.chars().any(char::is_control)
        || !(1..=90).contains(&input.days)
    {
        return Err(invalid());
    }
    state
        .database(move |db| {
            let tx = db.transaction().map_err(storage_error)?;
            let now = now_ms();
            tx.execute("DELETE FROM device_credentials WHERE expires_ms<=?", [now])
                .map_err(storage_error)?;
            let count: i64 = tx
                .query_row("SELECT COUNT(*) FROM device_credentials", [], |r| r.get(0))
                .map_err(storage_error)?;
            if count >= 32 {
                return Err(ApiError(
                    StatusCode::INSUFFICIENT_STORAGE,
                    "device capacity reached",
                ));
            }
            let mut secret = [0u8; 32];
            let mut identity = [0u8; 16];
            getrandom::fill(&mut secret).map_err(storage_error)?;
            getrandom::fill(&mut identity).map_err(storage_error)?;
            let token = secret
                .iter()
                .map(|b| format!("{b:02x}"))
                .collect::<String>();
            secret.fill(0);
            let id = identity
                .iter()
                .map(|b| format!("{b:02x}"))
                .collect::<String>();
            let hash: [u8; 32] = Sha256::digest(token.as_bytes()).into();
            let expires = now + i64::from(input.days) * DAY;
            tx.execute(
                "INSERT INTO device_credentials(id,label,scope,created_ms,expires_ms,hash) VALUES(?,?,?,?,?,?)",
                params![
                    id,
                    input.label,
                    input.scope.name(),
                    now,
                    expires,
                    hash.as_slice()
                ],
            )
            .map_err(storage_error)?;
            tx.commit().map_err(storage_error)?;
            Ok(Json(
                serde_json::json!({"id":id,"token":token,"scope":input.scope,"expires_ms":expires}),
            ))
        })
        .await
}
async fn revoke(
    State(state): State<AppState>,
    axum::extract::Path(id): axum::extract::Path<String>,
) -> Result<StatusCode> {
    if !valid_id(&id) {
        return Err(invalid());
    }
    state
        .database(move |db| {
            db.execute("DELETE FROM device_credentials WHERE id=?", [id])
                .map_err(storage_error)?;
            Ok(())
        })
        .await?;
    Ok(StatusCode::NO_CONTENT)
}

#[cfg(test)]
mod tests {
    use super::*;
    const OWNER: &str = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    fn insert(db: &Connection, id: &str, scope: &str, expires: i64, hash: &[u8]) {
        db.execute("INSERT INTO device_credentials(id,label,scope,created_ms,expires_ms,hash) VALUES (?1,?1,?2,0,?3,?4)", params![id,scope,expires,hash]).unwrap();
    }

    fn last(state: &AppState, id: &str) -> Option<i64> {
        state
            .db
            .lock()
            .unwrap()
            .query_row(
                "SELECT (SELECT last_authenticated_ms FROM device_authentication WHERE id=?)",
                [id],
                |r| r.get(0),
            )
            .unwrap()
    }

    #[tokio::test]
    async fn migration_preserves_legacy_key_and_starts_with_null_metadata() {
        let db = Connection::open_in_memory().unwrap();
        db.execute_batch("CREATE TABLE device_credentials(id TEXT PRIMARY KEY,label TEXT NOT NULL,scope TEXT NOT NULL,created_ms INTEGER NOT NULL,expires_ms INTEGER NOT NULL,hash BLOB NOT NULL);CREATE TABLE credential_owner(slot INTEGER PRIMARY KEY CHECK(slot=1),hash BLOB NOT NULL);").unwrap();
        let owner: [u8; 32] = Sha256::digest(OWNER.as_bytes()).into();
        db.execute(
            "INSERT INTO credential_owner VALUES(1,?)",
            [owner.as_slice()],
        )
        .unwrap();
        let hash = [7u8; 32];
        insert(&db, "legacy", "journal", now_ms() + DAY, &hash);
        let state = AppState::new(db, OWNER).unwrap();
        assert_eq!(last(&state, "legacy"), None);
        assert!(
            authorized(&state, hash, "GET".into(), "/v1/timeline".into())
                .await
                .unwrap()
        );
        assert!(last(&state, "legacy").is_some());
        let db = state.db.lock().unwrap();
        // The previous released binary uses an implicit six-value INSERT.
        db.execute(
            "INSERT INTO device_credentials VALUES('rollback','rollback','journal',0,?1,?2)",
            params![now_ms() + DAY, [8u8; 32].as_slice()],
        )
        .unwrap();
        assert_eq!(
            db.query_row(
                "SELECT COUNT(*) FROM pragma_table_info('device_credentials')",
                [],
                |r| r.get::<_, i64>(0)
            )
            .unwrap(),
            6
        );
        db.execute("DELETE FROM device_credentials WHERE id='legacy'", [])
            .unwrap();
        assert_eq!(
            db.query_row("SELECT COUNT(*) FROM device_authentication", [], |r| r
                .get::<_, i64>(0))
                .unwrap(),
            0
        );
    }

    #[tokio::test]
    async fn only_permitted_device_authentication_updates_metadata() {
        let state = AppState::new(Connection::open_in_memory().unwrap(), OWNER).unwrap();
        {
            let db = state.db.lock().unwrap();
            insert(&db, "journal", "journal", now_ms() + DAY, &[1; 32]);
            insert(&db, "collector", "collector", now_ms() + DAY, &[2; 32]);
            insert(&db, "expired", "journal", now_ms() - 1, &[3; 32]);
        }
        for (hash, method, path) in [
            ([2; 32], "GET", "/v1/timeline"),
            ([1; 32], "GET", "/v1/devices"),
            ([2; 32], "POST", "/v1/devices"),
            ([3; 32], "GET", "/v1/timeline"),
            ([9; 32], "GET", "/v1/timeline"),
        ] {
            assert!(
                !authorized(&state, hash, method.into(), path.into())
                    .await
                    .unwrap()
            );
        }
        assert_eq!(last(&state, "journal"), None);
        assert_eq!(last(&state, "collector"), None);
        assert_eq!(last(&state, "expired"), None);
        assert!(
            authorized(&state, [1; 32], "GET".into(), "/v1/timeline".into())
                .await
                .unwrap()
        );
        assert!(
            authorized(&state, [2; 32], "POST".into(), "/v1/activities".into())
                .await
                .unwrap()
        );
        assert!(last(&state, "journal").is_some());
        assert!(last(&state, "collector").is_some());
        state
            .db
            .lock()
            .unwrap()
            .execute("DELETE FROM device_credentials WHERE id='journal'", [])
            .unwrap();
        assert!(
            !authorized(&state, [1; 32], "GET".into(), "/v1/timeline".into())
                .await
                .unwrap()
        );
    }

    #[tokio::test]
    async fn minute_metadata_is_coarse_monotonic_and_best_effort() {
        let state = AppState::new(Connection::open_in_memory().unwrap(), OWNER).unwrap();
        insert(
            &state.db.lock().unwrap(),
            "journal",
            "journal",
            now_ms() + DAY,
            &[1; 32],
        );
        assert!(
            authorized(&state, [1; 32], "GET".into(), "/v1/timeline".into())
                .await
                .unwrap()
        );
        assert_eq!(last(&state, "journal").unwrap() % 60_000, 0);
        // Count actual writes so an ignored diagnostic error cannot hide an
        // unnecessary repeat update. Allow a real minute boundary during test.
        state.db.lock().unwrap().execute_batch("CREATE TABLE metadata_writes(count INTEGER); INSERT INTO metadata_writes VALUES(0); CREATE TRIGGER count_metadata AFTER UPDATE ON device_authentication BEGIN UPDATE metadata_writes SET count=count+1; END;").unwrap();
        let before = last(&state, "journal").unwrap();
        assert!(
            authorized(&state, [1; 32], "GET".into(), "/v1/timeline".into())
                .await
                .unwrap()
        );
        let writes: i64 = state
            .db
            .lock()
            .unwrap()
            .query_row("SELECT count FROM metadata_writes", [], |r| r.get(0))
            .unwrap();
        assert_eq!(writes, i64::from(last(&state, "journal").unwrap() > before));
        state.db.lock().unwrap().execute_batch("DROP TRIGGER count_metadata; DELETE FROM device_authentication; CREATE TRIGGER reject_metadata BEFORE INSERT ON device_authentication BEGIN SELECT RAISE(ABORT,'synthetic diagnostic failure'); END;").unwrap();
        assert!(
            authorized(&state, [1; 32], "GET".into(), "/v1/timeline".into())
                .await
                .unwrap()
        );
        assert_eq!(last(&state, "journal"), None);
    }
}
