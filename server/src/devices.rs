//! Owner-issued, independently expiring/revocable credentials. No bearer values in URLs or reads.
use super::*;
use rusqlite::OptionalExtension;

pub(super) fn migrate(db: &Connection, owner: &[u8; 32]) -> rusqlite::Result<()> {
    db.execute_batch("CREATE TABLE IF NOT EXISTS device_credentials(id TEXT PRIMARY KEY,label TEXT NOT NULL,scope TEXT NOT NULL,created_ms INTEGER NOT NULL,expires_ms INTEGER NOT NULL,hash BLOB NOT NULL);CREATE TABLE IF NOT EXISTS credential_owner(slot INTEGER PRIMARY KEY CHECK(slot=1),hash BLOB NOT NULL);")?;
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
            let rows: Vec<(Vec<u8>, String)> = db
                .prepare("SELECT hash,scope FROM device_credentials WHERE expires_ms>?")
                .map_err(storage_error)?
                .query_map([now_ms()], |r| Ok((r.get(0)?, r.get(1)?)))
                .map_err(storage_error)?
                .collect::<std::result::Result<_, _>>()
                .map_err(storage_error)?;
            let mut allowed = false;
            for (stored, scope) in rows {
                let matches = bool::from(stored.as_slice().ct_eq(&hash));
                let permitted = scope == "journal"
                    || scope == "collector"
                        && ((method == "POST"
                            && matches!(path.as_str(), "/v1/activities" | "/v1/observations"))
                            || (method == "GET"
                                && matches!(path.as_str(), "/v1/collection" | "/v1/ai")));
                allowed |= matches && permitted;
            }
            Ok(allowed)
        })
        .await
}
async fn list(State(state): State<AppState>) -> Result<Json<serde_json::Value>> {
    state.database(|db|{
        let rows:Vec<serde_json::Value>=db.prepare("SELECT id,label,scope,created_ms,expires_ms FROM device_credentials ORDER BY created_ms DESC LIMIT 32").map_err(storage_error)?.query_map([],|r|Ok(serde_json::json!({"id":r.get::<_,String>(0)?,"label":r.get::<_,String>(1)?,"scope":r.get::<_,String>(2)?,"created_ms":r.get::<_,i64>(3)?,"expires_ms":r.get::<_,i64>(4)?}))).map_err(storage_error)?.collect::<std::result::Result<_,_>>().map_err(storage_error)?;
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
                "INSERT INTO device_credentials VALUES(?,?,?,?,?,?)",
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
