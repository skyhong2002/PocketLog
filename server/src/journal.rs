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
    crate::conversations::prune(db, now)?;
    db.execute("DELETE FROM image_replay WHERE at_ms<=?", [now - DAY])
        .map_err(storage_error)?;
    db.execute(
        "DELETE FROM agent_sessions WHERE at_ms<=?",
        [now - RETENTION],
    )
    .map_err(storage_error)?;
    // Recent session updates cannot extend the lifetime of older imported turns.
    db.execute("UPDATE agent_sessions SET json=json_set(json,'$.revision',revision+1,'$.turns',json((SELECT json_group_array(json(value)) FROM json_each(agent_sessions.json,'$.turns') WHERE json_extract(value,'$.at_ms')>?1))),revision=revision+1 WHERE EXISTS(SELECT 1 FROM json_each(agent_sessions.json,'$.turns') WHERE json_extract(value,'$.at_ms')<=?1)",[now-RETENTION]).map_err(storage_error)?;
    db.execute("DELETE FROM briefs WHERE at_ms<=?", [now - RETENTION])
        .map_err(storage_error)?;
    db.execute(
        "DELETE FROM brief_attempts WHERE at_ms<=?",
        [now - RETENTION],
    )
    .map_err(storage_error)?;
    db.execute(
        "DELETE FROM focus_session WHERE at_ms<=?",
        [now - RETENTION],
    )
    .map_err(storage_error)?;
    db.execute(
        "DELETE FROM review_records WHERE at_ms <= ?",
        [now - RETENTION],
    )
    .map_err(storage_error)?;
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
    db.execute_batch(
        "DELETE FROM image_replay; DELETE FROM agent_sessions; DELETE FROM briefs; DELETE FROM brief_attempts; UPDATE brief_schedule SET revision=revision+1,json=json_set(json,'$.enabled',json('false'),'$.revision',revision+1); DELETE FROM focus_session; DELETE FROM chat_turns; DELETE FROM chat_threads;",
    )
    .map_err(storage_error)?;
    db.execute("DELETE FROM review_records", [])
        .map_err(storage_error)?;
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
    #[serde(default, skip_serializing_if = "is_false")]
    replay_consent: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    provider_id: Option<String>,
}
fn is_false(value: &bool) -> bool {
    !*value
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
        || o.provider_id
            .as_ref()
            .is_some_and(|id| id.len() != 64 || !id.bytes().all(|b| b.is_ascii_hexdigit()))
        || o.replay_consent && (!o.image_consent || o.jpeg_base64.is_none())
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
            crate::collection::ensure_running(&tx)?;
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
                if o.replay_consent {
                    crate::replay::insert(
                        &tx,
                        &o.id,
                        o.at_ms,
                        &o.app,
                        o.jpeg_base64.as_deref().ok_or_else(invalid)?,
                    )?;
                }
                tx.execute(
                    "INSERT INTO receipts VALUES (?,?,?)",
                    params![o.id, o.at_ms, hash],
                )
                .map_err(storage_error)?;
                tx.execute(
                    "INSERT INTO observations VALUES (?,?,?,?,?,?,?)",
                    params![
                        o.id,
                        o.at_ms,
                        o.app,
                        o.text,
                        if o.external_ai_consent { 2 } else { 1 },
                        o.jpeg_base64,
                        o.provider_id
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
    // Matching raw context remains on the server; this is neither a worker
    // execution state nor permission to send context to an external provider.
    pending_context: bool,
}
pub(super) fn cards(db: &Connection, activities: &[Activity]) -> Result<Vec<Card>> {
    let preferences = workspace::load(db)?;
    activities
        .iter()
        .map(|a| {
            // Timeline activities may be clipped to the requested window. Match
            // pending raw evidence against the stored activity's full interval.
            let pending_context = db
                .query_row(
                    "SELECT EXISTS(SELECT 1 FROM activities a JOIN observations o ON o.app=a.app AND o.at_ms>=a.start_ms AND o.at_ms<a.end_ms WHERE a.id=?)",
                    [&a.id],
                    |r| r.get(0),
                )
                .map_err(storage_error)?;
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
            let category = preferences.classify(category, &a.app, &title, source == "user");
            Ok(Card {
                id: a.id.clone(),
                start_ms: a.start_ms,
                end_ms: a.end_ms,
                app: a.app.clone(),
                title,
                summary,
                category,
                source,
                pending_context,
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
        workspace::ensure_category(db, &e.category)?;
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
        let preferences=workspace::load(db)?;
        let mut totals=std::collections::BTreeMap::<String,i64>::new();
        let mut stmt=db.prepare("SELECT COALESCE(n.category,a.category),a.app,COALESCE(n.title,a.app),COALESCE(n.edited,0),MIN(a.end_ms,?2)-MAX(a.start_ms,?1) FROM activities a LEFT JOIN notes n ON n.id=a.id WHERE a.end_ms>?1 AND a.start_ms<?2 LIMIT 100000").map_err(storage_error)?;
        let rows=stmt.query_map([w.from_ms,w.to_ms],|r|Ok((r.get::<_,String>(0)?,r.get::<_,String>(1)?,r.get::<_,String>(2)?,r.get::<_,i64>(3)?,r.get::<_,i64>(4)?))).map_err(storage_error)?;
        for row in rows {
            let (category,app,title,edited,duration)=row.map_err(storage_error)?;
            let category=preferences.classify(serde_json::from_str(&category).map_err(storage_error)?,&app,&title,edited==1);
            *totals.entry(serde_json::to_string(&category).map_err(storage_error)?).or_default()+=duration;
        }
        let mut categories:Vec<CategoryTotal>=totals.into_iter().map(|(category,duration_ms)|Ok(CategoryTotal{category:serde_json::from_str(&category).map_err(storage_error)?,duration_ms})).collect::<Result<_>>()?;
        categories.sort_by_key(|a| std::cmp::Reverse(a.duration_ms));
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

#[cfg(test)]
mod pending_context_tests {
    use super::*;

    fn setup() -> (AppState, Activity) {
        let state = AppState::new(
            Connection::open_in_memory().unwrap(),
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        )
        .unwrap();
        let activity = Activity {
            id: "synthetic-activity".into(),
            start_ms: 100,
            end_ms: 200,
            app: "org.example.reader".into(),
            category: Category::Learning,
        };
        state
            .db
            .lock()
            .unwrap()
            .execute(
                "INSERT INTO activities VALUES (?,?,?,?,?)",
                params![
                    activity.id,
                    activity.start_ms,
                    activity.end_ms,
                    activity.app,
                    "\"learning\""
                ],
            )
            .unwrap();
        (state, activity)
    }

    fn observe(db: &Connection, id: &str, at: i64, app: &str) {
        db.execute(
            "INSERT INTO observations VALUES (?,?,?,'synthetic private evidence',1,NULL,NULL)",
            params![id, at, app],
        )
        .unwrap();
    }

    #[test]
    fn pending_matches_app_and_half_open_stored_interval() {
        for (at, app, expected) in [
            (99, "org.example.reader", false),
            (100, "org.example.reader", true),
            (150, "org.example.reader", true),
            (199, "org.example.reader", true),
            (200, "org.example.reader", false),
            (150, "org.example.other", false),
        ] {
            let (state, activity) = setup();
            let db = state.db.lock().unwrap();
            let initial = cards(&db, std::slice::from_ref(&activity)).unwrap();
            assert!(!initial[0].pending_context);
            assert_eq!(initial[0].source, "app_only");
            observe(&db, "synthetic-observation", at, app);
            let result = cards(&db, &[activity]).unwrap();
            assert_eq!(result[0].pending_context, expected);
            assert_eq!(result[0].source, "app_only");
            let json = serde_json::to_value(&result[0]).unwrap();
            assert_eq!(json["pending_context"], expected);
            assert!(!json.to_string().contains("synthetic private evidence"));
        }
    }

    #[test]
    fn clipped_card_uses_full_stored_activity_bounds() {
        let (state, mut activity) = setup();
        let db = state.db.lock().unwrap();
        activity.start_ms = 140;
        activity.end_ms = 160;
        for at in [110, 190] {
            observe(&db, "synthetic-observation", at, &activity.app);
            let result = cards(&db, std::slice::from_ref(&activity)).unwrap();
            assert!(result[0].pending_context);
            assert_eq!((result[0].start_ms, result[0].end_ms), (140, 160));
            db.execute("DELETE FROM observations", []).unwrap();
        }
    }

    #[test]
    fn late_context_preserves_note_source_and_tracks_remaining_raw_evidence() {
        for (edited, source) in [(0, "local_excerpt"), (1, "user"), (2, "ai")] {
            let (state, activity) = setup();
            let db = state.db.lock().unwrap();
            db.execute(
                "INSERT INTO notes VALUES (?,'Synthetic title','Synthetic summary','\"learning\"',?)",
                params![activity.id, edited],
            )
            .unwrap();
            assert!(!cards(&db, std::slice::from_ref(&activity)).unwrap()[0].pending_context);
            for index in 0..13 {
                observe(
                    &db,
                    &format!("synthetic-{index}"),
                    110 + index,
                    &activity.app,
                );
            }
            let result = cards(&db, std::slice::from_ref(&activity)).unwrap();
            assert!(result[0].pending_context);
            assert_eq!(result[0].source, source);
            assert_eq!(result[0].title, "Synthetic title");
            // A worker batch consumes at most twelve observations. Remaining
            // evidence must keep the flag set even after a note was committed.
            db.execute("DELETE FROM observations WHERE at_ms<122", [])
                .unwrap();
            assert!(cards(&db, std::slice::from_ref(&activity)).unwrap()[0].pending_context);
            db.execute("DELETE FROM observations", []).unwrap();
            let result = cards(&db, &[activity]).unwrap();
            assert!(!result[0].pending_context);
            assert_eq!(result[0].source, source);
        }
    }
}
