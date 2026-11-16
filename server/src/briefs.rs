//! Explicit derived-card briefs. Scheduled cloud processing requires separate provider-bound opt-in.
use super::*;
use chrono::{Datelike, TimeZone, Timelike};
use rusqlite::OptionalExtension;

pub(super) fn migrate(db: &Connection) -> rusqlite::Result<()> {
    db.execute_batch("CREATE TABLE IF NOT EXISTS briefs(id TEXT PRIMARY KEY,at_ms INTEGER NOT NULL,from_ms INTEGER NOT NULL,to_ms INTEGER NOT NULL,kind TEXT NOT NULL,provider_id TEXT NOT NULL,text TEXT NOT NULL);
    CREATE TABLE IF NOT EXISTS brief_schedule(slot INTEGER PRIMARY KEY CHECK(slot=1),revision INTEGER NOT NULL,json TEXT NOT NULL);
    CREATE TABLE IF NOT EXISTS brief_attempts(id TEXT PRIMARY KEY,at_ms INTEGER NOT NULL,attempts INTEGER NOT NULL,next_ms INTEGER NOT NULL);")
}
#[derive(Clone, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
struct Schedule {
    revision: i64,
    enabled: bool,
    provider_id: String,
    timezone: String,
    minute_of_day: u16,
    daily: bool,
    weekly: bool,
}
impl Default for Schedule {
    fn default() -> Self {
        Self {
            revision: 0,
            enabled: false,
            provider_id: String::new(),
            timezone: "Asia/Taipei".into(),
            minute_of_day: 540,
            daily: true,
            weekly: true,
        }
    }
}
fn schedule(db: &Connection) -> Result<Schedule> {
    let json: Option<String> = db
        .query_row("SELECT json FROM brief_schedule WHERE slot=1", [], |r| {
            r.get(0)
        })
        .optional()
        .map_err(storage_error)?;
    json.map(|s| serde_json::from_str(&s).map_err(storage_error))
        .unwrap_or_else(|| Ok(Schedule::default()))
}
#[derive(Clone, Deserialize)]
#[serde(deny_unknown_fields)]
struct Generate {
    id: String,
    from_ms: i64,
    to_ms: i64,
    kind: Kind,
    provider_id: String,
    consent: bool,
}
#[derive(Clone, Copy, Deserialize, Serialize)]
#[serde(rename_all = "snake_case")]
enum Kind {
    Daily,
    Weekly,
}
impl Kind {
    fn name(self) -> &'static str {
        match self {
            Self::Daily => "daily",
            Self::Weekly => "weekly",
        }
    }
}
fn evidence(db: &Connection, from: i64, to: i64) -> Result<String> {
    let mut stmt=db.prepare("SELECT a.app,MAX(a.start_ms,?1),MIN(a.end_ms,?2),COALESCE(n.title,''),COALESCE(n.summary,'') FROM activities a LEFT JOIN notes n ON n.id=a.id WHERE a.end_ms>?1 AND a.start_ms<?2 ORDER BY a.start_ms LIMIT 100").map_err(storage_error)?;
    let mut rows:Vec<serde_json::Value>=stmt.query_map([from,to],|r|Ok(serde_json::json!({"app":r.get::<_,String>(0)?,"start_ms":r.get::<_,i64>(1)?,"end_ms":r.get::<_,i64>(2)?,"title":r.get::<_,String>(3)?,"summary":r.get::<_,String>(4)?}))).map_err(storage_error)?.collect::<std::result::Result<_,_>>().map_err(storage_error)?;
    let mut text = serde_json::to_string(&rows).map_err(storage_error)?;
    while text.len() > 24000 {
        rows.pop();
        text = serde_json::to_string(&rows).map_err(storage_error)?;
    }
    Ok(text)
}
fn question(kind: Kind) -> &'static str {
    match kind {
        Kind::Daily => {
            "整理這一天的活動重點、已知進展與值得自行回顧的問題。依時間引用證據，註明資料缺口；不要推論心情或把休息當成失敗。"
        }
        Kind::Weekly => {
            "整理這週的主要活動、專案進展、重複出現的節奏與可自行思考的下週方向。依日期與時間引用證據，註明資料缺口；不要推論心情。"
        }
    }
}
fn stored(db: &Connection, id: &str) -> Result<Option<serde_json::Value>> {
    db.query_row("SELECT id,at_ms,from_ms,to_ms,kind,provider_id,text FROM briefs WHERE id=?",[id],|r|Ok(serde_json::json!({"id":r.get::<_,String>(0)?,"at_ms":r.get::<_,i64>(1)?,"from_ms":r.get::<_,i64>(2)?,"to_ms":r.get::<_,i64>(3)?,"kind":r.get::<_,String>(4)?,"provider_id":r.get::<_,String>(5)?,"text":r.get::<_,String>(6)?}))).optional().map_err(storage_error)
}
fn save(db: &Connection, q: &Generate, text: &str) -> Result<()> {
    let count: i64 = db
        .query_row("SELECT COUNT(*) FROM briefs WHERE id!=?", [&q.id], |r| {
            r.get(0)
        })
        .map_err(storage_error)?;
    if count >= 100 {
        return Err(ApiError(
            StatusCode::INSUFFICIENT_STORAGE,
            "brief capacity reached",
        ));
    }
    db.execute(
        "INSERT INTO briefs VALUES(?,?,?,?,?,?,?)",
        params![
            q.id,
            now_ms(),
            q.from_ms,
            q.to_ms,
            q.kind.name(),
            q.provider_id,
            text
        ],
    )
    .map_err(storage_error)?;
    Ok(())
}
pub(super) fn routes() -> Router<AppState> {
    Router::new()
        .route("/v1/briefs", get(list).post(generate))
        .route(
            "/v1/briefs/schedule",
            get(read_schedule).put(update_schedule),
        )
        .route("/v1/briefs/{id}", delete(remove))
}
async fn read_schedule(State(state): State<AppState>) -> Result<Json<Schedule>> {
    state.database(|db| Ok(Json(schedule(db)?))).await
}
async fn update_schedule(
    State(state): State<AppState>,
    Json(mut next): Json<Schedule>,
) -> Result<Json<Schedule>> {
    if next.timezone.parse::<chrono_tz::Tz>().is_err()
        || !(240..1440).contains(&next.minute_of_day)
        || next.enabled
            && (!next.daily && !next.weekly
                || !state
                    .ai_settings
                    .as_ref()
                    .is_some_and(|s| s.matches(&next.provider_id)))
    {
        return Err(invalid());
    }
    state.database(move|db|{let tx=db.transaction().map_err(storage_error)?;
        if schedule(&tx)?.revision!=next.revision {return Err(ApiError(StatusCode::CONFLICT,"schedule changed; reload"));}
        next.revision=next.revision.checked_add(1).ok_or_else(invalid)?;
        tx.execute("INSERT INTO brief_schedule VALUES(1,?,?) ON CONFLICT(slot) DO UPDATE SET revision=excluded.revision,json=excluded.json",params![next.revision,serde_json::to_string(&next).map_err(storage_error)?]).map_err(storage_error)?;
        tx.execute("UPDATE journal_meta SET epoch=epoch+1",[]).map_err(storage_error)?;
        tx.commit().map_err(storage_error)?;Ok(Json(next))
    }).await
}
async fn list(
    State(state): State<AppState>,
    Query(w): Query<Window>,
) -> Result<Json<serde_json::Value>> {
    if w.from_ms < 0
        || w.to_ms > w.from_ms + RETENTION
        || w.to_ms <= w.from_ms
        || w.to_ms > now_ms() + 60_000
    {
        return Err(invalid());
    }
    state.database(move|db|{super::prune(db,now_ms())?;
        let ids:Vec<String>=db.prepare("SELECT id FROM briefs WHERE to_ms>? AND from_ms<? ORDER BY at_ms DESC LIMIT 100").map_err(storage_error)?.query_map([w.from_ms,w.to_ms],|r|r.get(0)).map_err(storage_error)?.collect::<std::result::Result<_,_>>().map_err(storage_error)?;
        let mut rows=ids.iter().map(|id|stored(db,id)).collect::<Result<Vec<_>>>()?;let mut truncated=false;
        while serde_json::to_vec(&rows).map_err(storage_error)?.len()>460*1024 {rows.pop();truncated=true;}
        Ok(Json(serde_json::json!({"briefs":rows,"truncated":truncated})))
    }).await
}
async fn generate(
    State(state): State<AppState>,
    Json(q): Json<Generate>,
) -> Result<Json<serde_json::Value>> {
    let now = now_ms();
    if !q.consent
        || !valid_id(&q.id)
        || q.from_ms < 0
        || q.to_ms > now + 60_000
        || q.to_ms <= q.from_ms
        || q.to_ms - q.from_ms
            > match q.kind {
                Kind::Daily => 26 * 3_600_000,
                Kind::Weekly => 7 * DAY + 3_600_000,
            }
    {
        return Err(invalid());
    }
    let store = state
        .ai_settings
        .clone()
        .filter(|s| s.matches(&q.provider_id))
        .ok_or(ApiError(
            StatusCode::CONFLICT,
            "provider changed; renew consent",
        ))?;
    let _permit = state
        .ai_probe_slot
        .clone()
        .try_acquire_owned()
        .map_err(|_| ApiError(StatusCode::TOO_MANY_REQUESTS, "model busy"))?;
    let copy = q.clone();
    let (cards, epoch, prior) = state
        .database(move |db| {
            super::prune(db, now)?;
            Ok((
                evidence(db, copy.from_ms, copy.to_ms)?,
                db.query_row("SELECT epoch FROM journal_meta", [], |r| r.get::<_, i64>(0))
                    .map_err(storage_error)?,
                stored(db, &copy.id)?,
            ))
        })
        .await?;
    if let Some(prior) = prior {
        if prior["from_ms"] != q.from_ms
            || prior["to_ms"] != q.to_ms
            || prior["kind"] != q.kind.name()
            || prior["provider_id"] != q.provider_id
        {
            return Err(ApiError(StatusCode::CONFLICT, "brief id already used"));
        }
        return Ok(Json(prior));
    }
    let copy = q.clone();
    let text = tokio::task::spawn_blocking(move || {
        let provider = analysis::Provider::from_store(&store)
            .map_err(storage_error)?
            .ok_or_else(invalid)?;
        provider
            .answer(&copy.provider_id, question(copy.kind), &cards, &[])
            .map_err(|_| ApiError(StatusCode::BAD_GATEWAY, "model request failed"))
    })
    .await
    .map_err(storage_error)??;
    state
        .database(move |db| {
            let tx = db.transaction().map_err(storage_error)?;
            let current: i64 = tx
                .query_row("SELECT epoch FROM journal_meta", [], |r| r.get(0))
                .map_err(storage_error)?;
            if epoch != current {
                return Err(ApiError(
                    StatusCode::CONFLICT,
                    "journal or provider changed",
                ));
            }
            save(&tx, &q, &text)?;
            let result = stored(&tx, &q.id)?.ok_or_else(invalid)?;
            tx.commit().map_err(storage_error)?;
            Ok(Json(result))
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
            let tx = db.transaction().map_err(storage_error)?;
            tx.execute("DELETE FROM briefs WHERE id=?", [id])
                .map_err(storage_error)?;
            tx.execute("UPDATE journal_meta SET epoch=epoch+1", [])
                .map_err(storage_error)?;
            tx.commit().map_err(storage_error)?;
            Ok(())
        })
        .await?;
    Ok(StatusCode::NO_CONTENT)
}
fn due(s: &Schedule, now: i64) -> Vec<(Kind, i64, i64)> {
    let Ok(zone) = s.timezone.parse::<chrono_tz::Tz>() else {
        return Vec::new();
    };
    let Some(local) = zone.timestamp_millis_opt(now).single() else {
        return Vec::new();
    };
    if local.hour() * 60 + local.minute() < u32::from(s.minute_of_day) {
        return Vec::new();
    }
    let date = local.date_naive();
    let Some(end) = date
        .and_hms_opt(4, 0, 0)
        .and_then(|d| zone.from_local_datetime(&d).earliest())
    else {
        return Vec::new();
    };
    let mut periods = Vec::new();
    if s.daily
        && let Some(start) = date
            .pred_opt()
            .and_then(|d| d.and_hms_opt(4, 0, 0))
            .and_then(|d| zone.from_local_datetime(&d).earliest())
    {
        periods.push((
            Kind::Daily,
            start.timestamp_millis(),
            end.timestamp_millis(),
        ));
    }
    if s.weekly {
        let monday =
            date - chrono::Duration::days(i64::from(date.weekday().num_days_from_monday()));
        if let (Some(a), Some(b)) = (
            (monday - chrono::Duration::days(7))
                .and_hms_opt(4, 0, 0)
                .and_then(|d| zone.from_local_datetime(&d).earliest()),
            monday
                .and_hms_opt(4, 0, 0)
                .and_then(|d| zone.from_local_datetime(&d).earliest()),
        ) {
            periods.push((Kind::Weekly, a.timestamp_millis(), b.timestamp_millis()));
        }
    }
    periods
}
// Called once per worker pass: at most two calls, three durable attempts per period,
// no historical catch-up, no raw context, and no sending after provider/consent changes.
pub(crate) fn process(
    db: &mut Connection,
    provider: Option<&analysis::Provider>,
) -> std::result::Result<usize, &'static str> {
    let mut run = || -> Result<usize> {
        let now = now_ms();
        super::prune(db, now)?;
        let settings = schedule(db)?;
        if !settings.enabled {
            return Ok(0);
        }
        let count: i64 = db
            .query_row("SELECT COUNT(*) FROM briefs", [], |r| r.get(0))
            .map_err(storage_error)?;
        if count >= 100 {
            return Ok(0);
        }
        let Some(provider) =
            provider.filter(|p| p.identity() == Some(settings.provider_id.as_str()))
        else {
            return Ok(0);
        };
        let mut count = 0;
        for (kind, from, to) in due(&settings, now) {
            let id = format!(
                "brief-{:x}",
                Sha256::digest(format!(
                    "{}:{from}:{to}:{}",
                    kind.name(),
                    settings.provider_id
                ))
            );
            if stored(db, &id)?.is_some() {
                continue;
            }
            let prior: Option<(i64, i64)> = db
                .query_row(
                    "SELECT attempts,next_ms FROM brief_attempts WHERE id=?",
                    [&id],
                    |r| Ok((r.get(0)?, r.get(1)?)),
                )
                .optional()
                .map_err(storage_error)?;
            if prior.is_some_and(|(attempt, next)| attempt >= 3 || next > now) {
                continue;
            }
            let tx = db.transaction().map_err(storage_error)?;
            let epoch: i64 = tx
                .query_row("SELECT epoch FROM journal_meta", [], |r| r.get(0))
                .map_err(storage_error)?;
            let cards = evidence(&tx, from, to)?;
            if cards == "[]" {
                continue;
            }
            tx.execute("INSERT INTO brief_attempts VALUES(?,?,1,?) ON CONFLICT(id) DO UPDATE SET attempts=attempts+1,next_ms=excluded.next_ms",params![id,now,now+15*60_000]).map_err(storage_error)?;
            tx.commit().map_err(storage_error)?;
            let Ok(text) = provider.answer(&settings.provider_id, question(kind), &cards, &[])
            else {
                continue;
            };
            let tx = db.transaction().map_err(storage_error)?;
            let current: i64 = tx
                .query_row("SELECT epoch FROM journal_meta", [], |r| r.get(0))
                .map_err(storage_error)?;
            if current != epoch || schedule(&tx)?.revision != settings.revision {
                continue;
            }
            save(
                &tx,
                &Generate {
                    id,
                    from_ms: from,
                    to_ms: to,
                    kind,
                    provider_id: settings.provider_id.clone(),
                    consent: true,
                },
                &text,
            )?;
            tx.commit().map_err(storage_error)?;
            count += 1;
        }
        Ok(count)
    };
    run().map_err(|_| "brief processing failed")
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn schedules_follow_timezone_dst_and_never_include_unfinished_periods() {
        let mut s = Schedule {
            enabled: true,
            timezone: "America/New_York".into(),
            ..Schedule::default()
        };
        let t = chrono::DateTime::parse_from_rfc3339("2026-03-08T14:00:00Z")
            .unwrap()
            .timestamp_millis();
        let p = due(&s, t);
        assert_eq!(p[0].2 - p[0].1, 23 * 3_600_000);
        assert!(p.iter().all(|(_, a, b)| a < b && *b <= t));
        s.minute_of_day = 660;
        assert!(due(&s, t).is_empty());
        s.timezone = "Asia/Taipei".into();
        s.minute_of_day = 540;
        assert_eq!(due(&s, t)[0].2 - due(&s, t)[0].1, DAY);
    }
}
