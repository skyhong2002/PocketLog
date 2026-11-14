//! Owner-only durable dialogue. No model tools; replay IDs prevent duplicate paid requests.
use super::*;
use axum::extract::Path;
use rusqlite::OptionalExtension;
pub(super) fn migrate(db: &Connection) -> rusqlite::Result<()> {
    db.execute_batch("CREATE TABLE IF NOT EXISTS chat_threads(id TEXT PRIMARY KEY,at_ms INTEGER NOT NULL,title TEXT NOT NULL,provider_id TEXT NOT NULL);CREATE TABLE IF NOT EXISTS chat_turns(id TEXT PRIMARY KEY,thread_id TEXT NOT NULL,at_ms INTEGER NOT NULL,question TEXT NOT NULL,answer TEXT NOT NULL,from_ms INTEGER NOT NULL,to_ms INTEGER NOT NULL);CREATE INDEX IF NOT EXISTS chat_turns_thread ON chat_turns(thread_id,at_ms,id);")
}
pub(super) fn prune(db: &Connection, now: i64) -> Result<()> {
    db.execute("DELETE FROM chat_turns WHERE at_ms<=?", [now - RETENTION])
        .map_err(storage_error)?;
    db.execute(
        "DELETE FROM chat_threads WHERE id NOT IN (SELECT thread_id FROM chat_turns)",
        [],
    )
    .map_err(storage_error)?;
    Ok(())
}
pub(super) fn routes() -> Router<AppState> {
    Router::new()
        .route("/v1/conversations", get(list))
        .route("/v1/conversations/{id}", get(read).delete(remove))
}
async fn list(State(state): State<AppState>) -> Result<Json<serde_json::Value>> {
    state.database(|db|{super::prune(db,now_ms())?;
        let rows:Vec<serde_json::Value>=db.prepare("SELECT id,at_ms,title,provider_id FROM chat_threads ORDER BY at_ms DESC LIMIT 100").map_err(storage_error)?.query_map([],|r|Ok(serde_json::json!({"id":r.get::<_,String>(0)?,"at_ms":r.get::<_,i64>(1)?,"title":r.get::<_,String>(2)?,"provider_id":r.get::<_,String>(3)?}))).map_err(storage_error)?.collect::<std::result::Result<_,_>>().map_err(storage_error)?;
        Ok(Json(serde_json::json!({"conversations":rows})))
    }).await
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Page {
    before_ms: Option<i64>,
    before_id: Option<String>,
}
async fn read(
    State(state): State<AppState>,
    Path(id): Path<String>,
    Query(page): Query<Page>,
) -> Result<Json<serde_json::Value>> {
    if !valid_id(&id)
        || page.before_id.as_ref().is_some_and(|s| !valid_id(s))
        || page.before_id.is_some() && page.before_ms.is_none()
    {
        return Err(invalid());
    }
    state.database(move|db|{
        super::prune(db,now_ms())?;
        let provider:Option<String>=db.query_row("SELECT provider_id FROM chat_threads WHERE id=?",[&id],|r|r.get(0)).optional().map_err(storage_error)?;
        let provider=provider.ok_or(ApiError(StatusCode::NOT_FOUND,"conversation not found"))?;
        let mut rows:Vec<serde_json::Value>=db.prepare("SELECT id,at_ms,question,answer,from_ms,to_ms FROM chat_turns WHERE thread_id=?1 AND (at_ms<?2 OR (at_ms=?2 AND id<?3)) ORDER BY at_ms DESC,id DESC LIMIT 51").map_err(storage_error)?.query_map(params![id,page.before_ms.unwrap_or(i64::MAX),page.before_id.unwrap_or_default()],|r|Ok(serde_json::json!({"id":r.get::<_,String>(0)?,"at_ms":r.get::<_,i64>(1)?,"question":r.get::<_,String>(2)?,"answer":r.get::<_,String>(3)?,"from_ms":r.get::<_,i64>(4)?,"to_ms":r.get::<_,i64>(5)?}))).map_err(storage_error)?.collect::<std::result::Result<_,_>>().map_err(storage_error)?;
        let mut truncated=rows.len()>50;rows.truncate(50);
        while serde_json::to_vec(&rows).map_err(storage_error)?.len()>460*1024 {rows.pop();truncated=true;}
        rows.reverse();let cursor=rows.first().map(|r|serde_json::json!({"before_ms":r["at_ms"],"before_id":r["id"]}));Ok(Json(serde_json::json!({"id":id,"provider_id":provider,"turns":rows,"truncated":truncated,"next_cursor":if truncated {cursor} else {None}})))
    }).await
}
async fn remove(State(state): State<AppState>, Path(id): Path<String>) -> Result<StatusCode> {
    if !valid_id(&id) {
        return Err(invalid());
    }
    state
        .database(move |db| {
            let tx = db.transaction().map_err(storage_error)?;
            tx.execute("DELETE FROM chat_turns WHERE thread_id=?", [&id])
                .map_err(storage_error)?;
            tx.execute("DELETE FROM chat_threads WHERE id=?", [id])
                .map_err(storage_error)?;
            tx.execute("UPDATE journal_meta SET epoch=epoch+1", [])
                .map_err(storage_error)?;
            tx.commit().map_err(storage_error)?;
            Ok(())
        })
        .await?;
    Ok(StatusCode::NO_CONTENT)
}
// Context is restricted to the same provider identity. Never silently send old dialogue to a new provider.
pub(super) async fn context(
    state: &AppState,
    thread: Option<String>,
    request: Option<String>,
    provider: String,
    question: String,
    from: i64,
    to: i64,
) -> Result<(Vec<serde_json::Value>, Option<String>)> {
    state.database(move|db|{
        super::prune(db,now_ms())?;
        let Some(thread)=thread else{return Ok((Vec::new(),None));};
        let identity:Option<String>=db.query_row("SELECT provider_id FROM chat_threads WHERE id=?",[&thread],|r|r.get(0)).optional().map_err(storage_error)?;
        if identity.as_ref().is_some_and(|id|id!=&provider){return Err(ApiError(StatusCode::CONFLICT,"start a new conversation for this provider"));}
        if let Some(request)=request {
            let prior:Option<(String,String,String,i64,i64)>=db.query_row("SELECT thread_id,question,answer,from_ms,to_ms FROM chat_turns WHERE id=?",[request],|r|Ok((r.get(0)?,r.get(1)?,r.get(2)?,r.get(3)?,r.get(4)?))).optional().map_err(storage_error)?;
            if let Some((id,q,answer,a,b))=prior {
                if id!=thread || q!=question || a!=from || b!=to{return Err(ApiError(StatusCode::CONFLICT,"request id already used"));}
                return Ok((Vec::new(),Some(answer)));
            }
        }
        let count:i64=db.query_row("SELECT COUNT(*) FROM chat_turns",[],|r|r.get(0)).map_err(storage_error)?;
        let threads:i64=db.query_row("SELECT COUNT(*) FROM chat_threads WHERE id!=?",[&thread],|r|r.get(0)).map_err(storage_error)?;
        if count>=500 || threads>=100 {return Err(ApiError(StatusCode::INSUFFICIENT_STORAGE,"conversation capacity reached"));}
        let mut turns:Vec<(String,String)>=db.prepare("SELECT question,answer FROM chat_turns WHERE thread_id=? ORDER BY at_ms DESC,id DESC LIMIT 6").map_err(storage_error)?.query_map([thread],|r|Ok((r.get(0)?,r.get(1)?))).map_err(storage_error)?.collect::<std::result::Result<_,_>>().map_err(storage_error)?;
        turns.reverse();let mut messages=Vec::new();let mut chars=0;
        for (q,a) in turns.into_iter().rev(){let len=q.chars().count()+a.chars().count();if chars+len>6000 {break;}chars+=len;messages.push((q,a));}
        messages.reverse();Ok((messages.into_iter().flat_map(|(q,a)|[serde_json::json!({"role":"user","content":q}),serde_json::json!({"role":"assistant","content":a})]).collect(),None))
    }).await
}
#[allow(clippy::too_many_arguments)]
pub(super) fn save_turn(
    db: &mut Connection,
    thread: Option<String>,
    request: Option<String>,
    provider: &str,
    question: &str,
    answer: &str,
    from: i64,
    to: i64,
) -> Result<()> {
    let (Some(thread), Some(request)) = (thread, request) else {
        return Ok(());
    };
    let tx = db.transaction().map_err(storage_error)?;
    let count: i64 = tx
        .query_row("SELECT COUNT(*) FROM chat_turns", [], |r| r.get(0))
        .map_err(storage_error)?;
    let threads: i64 = tx
        .query_row(
            "SELECT COUNT(*) FROM chat_threads WHERE id!=?",
            [&thread],
            |r| r.get(0),
        )
        .map_err(storage_error)?;
    if count >= 500 || threads >= 100 {
        return Err(ApiError(
            StatusCode::INSUFFICIENT_STORAGE,
            "conversation capacity reached",
        ));
    }
    let now = now_ms();
    tx.execute("INSERT INTO chat_threads VALUES(?,?,?,?) ON CONFLICT(id) DO UPDATE SET at_ms=excluded.at_ms",params![thread,now,question.chars().take(60).collect::<String>(),provider]).map_err(storage_error)?;
    tx.execute(
        "INSERT INTO chat_turns VALUES(?,?,?,?,?,?,?)",
        params![request, thread, now, question, answer, from, to],
    )
    .map_err(storage_error)?;
    tx.commit().map_err(storage_error)?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    const TOKEN: &str = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    #[tokio::test]
    async fn history_is_provider_bound_replay_safe_bounded_and_erased() {
        let state = AppState::new(Connection::open_in_memory().unwrap(), TOKEN).unwrap();
        state
            .database(|db| {
                for n in 0..8 {
                    save_turn(
                        db,
                        Some("thread".into()),
                        Some(format!("turn-{n}")),
                        "provider",
                        "synthetic question",
                        &"x".repeat(2000),
                        1,
                        2,
                    )?;
                }
                Ok(())
            })
            .await
            .unwrap();
        let (history, replay) = context(
            &state,
            Some("thread".into()),
            Some("new".into()),
            "provider".into(),
            "next".into(),
            1,
            2,
        )
        .await
        .unwrap();
        assert!(!history.is_empty());
        assert!(history.len() <= 12);
        assert!(replay.is_none());
        assert!(
            history
                .iter()
                .map(|m| m["content"].as_str().unwrap().chars().count())
                .sum::<usize>()
                <= 6000
        );
        let (_, replay) = context(
            &state,
            Some("thread".into()),
            Some("turn-0".into()),
            "provider".into(),
            "synthetic question".into(),
            1,
            2,
        )
        .await
        .unwrap();
        assert!(replay.is_some());
        assert!(
            context(
                &state,
                Some("thread".into()),
                Some("turn-0".into()),
                "provider".into(),
                "different".into(),
                1,
                2
            )
            .await
            .is_err()
        );
        assert!(
            context(
                &state,
                Some("thread".into()),
                Some("new".into()),
                "new-provider".into(),
                "next".into(),
                1,
                2
            )
            .await
            .is_err()
        );
        state
            .database(|db| {
                crate::journal::erase(db)?;
                let count: i64 = db
                    .query_row("SELECT COUNT(*) FROM chat_turns", [], |r| r.get(0))
                    .map_err(storage_error)?;
                assert_eq!(count, 0);
                Ok(())
            })
            .await
            .unwrap();
    }
}
