//! Private context and user corrections. Raw context is never returned by a read route.
use super::*;

pub(super) fn routes() -> Router<AppState> {
    Router::new()
        .route("/v1/observations", post(observe))
        .route("/v1/cards", post(edit))
        .route("/v1/reflections", post(reflect))
        .route("/v1/status", get(status))
        .route("/v1/overview", get(overview))
}

pub(super) fn prune(db: &Connection, now: i64) -> Result<()> {
    db.execute("DELETE FROM observations WHERE at_ms <= ?", [now - DAY])
        .map_err(storage_error)?;
    db.execute("DELETE FROM receipts WHERE at_ms <= ?", [now - RETENTION])
        .map_err(storage_error)?;
    db.execute(
        "DELETE FROM reflections WHERE at_ms <= ?",
        [now - RETENTION],
    )
    .map_err(storage_error)?;
    db.execute(
        "DELETE FROM notes WHERE id NOT IN (SELECT id FROM activities)",
        [],
    )
    .map_err(storage_error)?;
    db.execute(
        "DELETE FROM analysis_jobs WHERE id NOT IN (SELECT id FROM activities)",
        [],
    )
    .map_err(storage_error)?;
    Ok(())
}
pub(super) fn erase(db: &Connection) -> Result<()> {
    db.execute_batch("UPDATE journal_meta SET epoch=epoch+1; DELETE FROM analysis_jobs; DELETE FROM observations; DELETE FROM receipts; DELETE FROM notes; DELETE FROM reflections;").map_err(storage_error)
}
#[derive(Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
struct Observation {
    id: String,
    at_ms: i64,
    app: String,
    text: String,
    analysis_consent: bool,
    #[serde(default)]
    external_ai_consent: bool,
    #[serde(default)]
    jpeg_base64: Option<String>,
    #[serde(default)]
    image_consent: bool,
}
async fn observe(State(state): State<AppState>, Json(o): Json<Observation>) -> Result<StatusCode> {
    let now = now_ms();
    if !valid_id(&o.id)
        || o.at_ms <= now - DAY
        || o.at_ms > now + 60_000
        || o.app.is_empty()
        || o.app.len() > 200
        || !o
            .app
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b"._-".contains(&b))
        || o.text.len() > 8000
        || o.text.chars().count() > 2000
        || o.text.contains('\0')
        || o.jpeg_base64.as_ref().is_some_and(|s| {
            !o.image_consent
                || s.is_empty()
                || s.len() > 32768
                || !s
                    .bytes()
                    .all(|b| b.is_ascii_alphanumeric() || b"+/=".contains(&b))
        })
        || !o.analysis_consent
    {
        return Err(invalid());
    }
    let hash = format!(
        "{:x}",
        Sha256::digest(serde_json::to_vec(&o).map_err(storage_error)?)
    );
    state
        .database(move |db| {
            let tx = db.transaction().map_err(storage_error)?;
            super::prune(&tx, now)?;
            let existing: Option<String> = tx
                .query_row("SELECT hash FROM receipts WHERE id=?", [&o.id], |r| {
                    r.get(0)
                })
                .optional()
                .map_err(storage_error)?;
            if let Some(existing) = existing {
                if existing != hash {
                    return Err(ApiError(
                        StatusCode::CONFLICT,
                        "observation id already used",
                    ));
                }
            } else {
                let count: i64 = tx
                    .query_row("SELECT COUNT(*) FROM observations", [], |r| r.get(0))
                    .map_err(storage_error)?;
                let receipts: i64 = tx
                    .query_row("SELECT COUNT(*) FROM receipts", [], |r| r.get(0))
                    .map_err(storage_error)?;
                if count >= 2000 || receipts >= 60_000 {
                    return Err(ApiError(
                        StatusCode::INSUFFICIENT_STORAGE,
                        "context capacity reached",
                    ));
                }
                tx.execute(
                    "INSERT INTO receipts VALUES (?,?,?)",
                    params![o.id, o.at_ms, hash],
                )
                .map_err(storage_error)?;
                tx.execute(
                    "INSERT INTO observations VALUES (?,?,?,?,?,?)",
                    params![
                        o.id,
                        o.at_ms,
                        o.app,
                        o.text,
                        if o.external_ai_consent { 2 } else { 1 },
                        o.jpeg_base64
                    ],
                )
                .map_err(storage_error)?;
            }
            tx.commit().map_err(storage_error)?;
            Ok(())
        })
        .await?;
    Ok(StatusCode::NO_CONTENT)
}
use rusqlite::OptionalExtension;
#[derive(Serialize)]
pub(super) struct Card {
    id: String,
    start_ms: i64,
    end_ms: i64,
    app: String,
    title: String,
    summary: String,
    category: Category,
    source: &'static str,
}
pub(super) fn cards(db: &Connection, activities: &[Activity]) -> Result<Vec<Card>> {
    activities
        .iter()
        .map(|a| {
            let note: Option<(String, String, String, i64)> = db
                .query_row(
                    "SELECT title,summary,category,edited FROM notes WHERE id=?",
                    [&a.id],
                    |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?)),
                )
                .optional()
                .map_err(storage_error)?;
            let (title, summary, category, source) = match note {
                Some((t, s, c, edited)) => (
                    t,
                    s,
                    serde_json::from_str(&c).map_err(storage_error)?,
                    match edited {
                        1 => "user",
                        2 => "ai",
                        _ => "local_excerpt",
                    },
                ),
                None => (a.app.clone(), String::new(), a.category.clone(), "app_only"),
            };
            Ok(Card {
                id: a.id.clone(),
                start_ms: a.start_ms,
                end_ms: a.end_ms,
                app: a.app.clone(),
                title,
                summary,
                category,
                source,
            })
        })
        .collect()
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Edit {
    id: String,
    title: String,
    summary: String,
    category: Category,
}
async fn edit(State(state): State<AppState>, Json(e): Json<Edit>) -> Result<StatusCode> {
    if !valid_id(&e.id)
        || e.title.trim().is_empty()
        || e.title.chars().count() > 200
        || e.summary.chars().count() > 1000
        || e.title.contains('\0')
        || e.summary.contains('\0')
    {
        return Err(invalid());
    }
    state.database(move |db| {
        super::prune(db,now_ms())?;
        let exists: bool = db.query_row("SELECT EXISTS(SELECT 1 FROM activities WHERE id=?)",[&e.id],|r|r.get(0)).map_err(storage_error)?;
        if !exists { return Err(ApiError(StatusCode::NOT_FOUND,"activity not found")); }
        db.execute("INSERT INTO notes VALUES (?,?,?,?,1) ON CONFLICT(id) DO UPDATE SET title=excluded.title, summary=excluded.summary,category=excluded.category,edited=1",params![e.id,e.title,e.summary,serde_json::to_string(&e.category).map_err(storage_error)?]).map_err(storage_error)?;
        Ok(())
    }).await?;
    Ok(StatusCode::NO_CONTENT)
}
#[derive(Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub(super) struct Reflection {
    id: String,
    at_ms: i64,
    text: String,
}
async fn reflect(State(state): State<AppState>, Json(r): Json<Reflection>) -> Result<StatusCode> {
    let now = now_ms();
    if !valid_id(&r.id)
        || r.at_ms <= now - RETENTION
        || r.at_ms > now + 60_000
        || r.text.chars().count() > 2000
        || r.text.contains('\0')
    {
        return Err(invalid());
    }
    state.database(move |db| {
        let tx=db.transaction().map_err(storage_error)?;
        super::prune(&tx,now)?;
        let count: i64=tx.query_row("SELECT COUNT(*) FROM reflections",[],|r|r.get(0)).map_err(storage_error)?;
        let exists: bool=tx.query_row("SELECT EXISTS(SELECT 1 FROM reflections WHERE id=?)",[&r.id],|row|row.get(0)).map_err(storage_error)?;
        if count>=1000 && !exists { return Err(ApiError(StatusCode::INSUFFICIENT_STORAGE,"reflection capacity reached")); }
        if r.text.trim().is_empty() { tx.execute("DELETE FROM reflections WHERE id=?",[&r.id]).map_err(storage_error)?; }
        else { tx.execute("INSERT INTO reflections VALUES (?,?,?) ON CONFLICT(id) DO UPDATE SET at_ms=excluded.at_ms,text=excluded.text",params![r.id,r.at_ms,r.text]).map_err(storage_error)?; }
        tx.commit().map_err(storage_error)?;
        Ok(())
    }).await?;
    Ok(StatusCode::NO_CONTENT)
}
pub(super) fn reflections(db: &Connection, from: i64, to: i64) -> Result<Vec<Reflection>> {
    db.prepare("SELECT id,at_ms,text FROM reflections WHERE at_ms>=? AND at_ms<? ORDER BY at_ms,id LIMIT 1000").map_err(storage_error)?
        .query_map([from,to],|r|Ok(Reflection {id:r.get(0)?,at_ms:r.get(1)?,text:r.get(2)?})).map_err(storage_error)?
        .collect::<std::result::Result<Vec<_>,_>>().map_err(storage_error)
}
async fn status(State(state): State<AppState>) -> Result<Json<serde_json::Value>> {
    state.database(|db| {
        super::prune(db,now_ms())?;
        let pending:i64=db.query_row("SELECT COUNT(*) FROM observations",[],|r|r.get(0)).map_err(storage_error)?;
        Ok(Json(serde_json::json!({"schema_version":1,"analysis":"local_excerpt_or_opt_in_model","external_ai_requires_consent":true,"raw_retention_hours":24,"retention_days":30,"pending_context":pending})))
    }).await
}

#[derive(Serialize)]
struct CategoryTotal {
    category: Category,
    duration_ms: i64,
}
#[derive(Serialize)]
struct AppTotal {
    app: String,
    duration_ms: i64,
}
async fn overview(
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
    state.database(move |db| {
        super::prune(db,now)?;
        let categories:Vec<CategoryTotal>=db.prepare("SELECT COALESCE(n.category,a.category),SUM(MIN(a.end_ms,?2)-MAX(a.start_ms,?1)) FROM activities a LEFT JOIN notes n ON n.id=a.id WHERE a.end_ms>?1 AND a.start_ms<?2 GROUP BY COALESCE(n.category,a.category) ORDER BY 2 DESC").map_err(storage_error)?
            .query_map([w.from_ms,w.to_ms],|r| { let category:String=r.get(0)?; Ok(CategoryTotal {category:serde_json::from_str(&category).map_err(|e|rusqlite::Error::FromSqlConversionFailure(0,rusqlite::types::Type::Text,Box::new(e)))?,duration_ms:r.get(1)?}) }).map_err(storage_error)?.collect::<std::result::Result<Vec<_>,_>>().map_err(storage_error)?;
        let apps:Vec<AppTotal>=db.prepare("SELECT app,SUM(MIN(end_ms,?2)-MAX(start_ms,?1)) FROM activities WHERE end_ms>?1 AND start_ms<?2 GROUP BY app ORDER BY 2 DESC,app LIMIT 20").map_err(storage_error)?
            .query_map([w.from_ms,w.to_ms],|r|Ok(AppTotal {app:r.get(0)?,duration_ms:r.get(1)?})).map_err(storage_error)?.collect::<std::result::Result<Vec<_>,_>>().map_err(storage_error)?;
        let (checkins,mood,energy):(i64,Option<f64>,Option<f64>)=db.query_row("SELECT COUNT(*),AVG(mood),AVG(energy) FROM checkins WHERE at_ms>=? AND at_ms<?",[w.from_ms,w.to_ms],|r|Ok((r.get(0)?,r.get(1)?,r.get(2)?))).map_err(storage_error)?;
        let titles:Vec<String>=db.prepare("SELECT n.title FROM notes n JOIN activities a ON a.id=n.id WHERE a.end_ms>? AND a.start_ms<? ORDER BY a.start_ms DESC LIMIT 1000").map_err(storage_error)?
            .query_map([w.from_ms,w.to_ms],|r|r.get(0)).map_err(storage_error)?.collect::<std::result::Result<Vec<_>,_>>().map_err(storage_error)?;
        let mut words=std::collections::BTreeMap::<String,usize>::new();
        for title in titles {
            let unique:std::collections::BTreeSet<String>=title.split(|c:char|!c.is_alphabetic()).filter(|w|(2..=12).contains(&w.chars().count())).map(str::to_lowercase).collect();
            for word in unique { *words.entry(word).or_default()+=1; }
        }
        let mut ranked:Vec<_>=words.into_iter().collect();
        ranked.sort_by(|a,b|b.1.cmp(&a.1).then(a.0.cmp(&b.0))); ranked.truncate(10);
        Ok(Json(serde_json::json!({"from_ms":w.from_ms,"to_ms":w.to_ms,"categories":categories,"apps":apps,"checkin_count":checkins,"mood_average":mood,"energy_average":energy,"title_keywords":ranked,"keywords_sample_limit":1000})))
    }).await
}
