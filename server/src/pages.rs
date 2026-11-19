//! Complete, bounded reads of derived activity cards: keyset pages and search.
//!
//! A cursor uses the last returned `(stored start_ms, id)` and a revision that
//! changes on journal deletion or replacement of capture-derived cards.
//! Raw observations and screenshots are never read here.
use super::*;

/// Rows inspected per request, including rows a filter rejects. This bounds a
/// search over a sparse match set instead of scanning the whole journal at once.
const SCAN_LIMIT: usize = 2_000;
const MAX_PAGE: usize = 500;
const DEFAULT_PAGE: usize = 200;
/// Same serialized bound as `/v1/timeline`, within the Android 512 KiB reader.
const MAX_RESPONSE_BYTES: usize = 480 * 1024;
const MAX_QUERY_CHARS: usize = 100;

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub(super) struct PageQuery {
    from_ms: i64,
    to_ms: i64,
    after_start_ms: Option<i64>,
    after_id: Option<String>,
    epoch: Option<i64>,
    limit: Option<usize>,
    q: Option<String>,
    category: Option<String>,
}

#[derive(Serialize, Debug, PartialEq)]
struct Cursor {
    after_start_ms: i64,
    after_id: String,
}

#[derive(Serialize)]
struct Page {
    activities: Vec<Activity>,
    cards: Vec<journal::Card>,
    /// Null only when every row in the window after the request cursor was inspected.
    next_cursor: Option<Cursor>,
    journal_epoch: i64,
    /// Rows inspected by this request; with a filter this can exceed returned cards.
    scanned: usize,
}

struct Filter {
    needle: Option<String>,
    category: Option<Category>,
}
impl Filter {
    fn matches(&self, card: &journal::Card) -> bool {
        self.category.as_ref().is_none_or(|c| &card.category == c)
            && self.needle.as_ref().is_none_or(|n| {
                [&card.app, &card.title, &card.summary]
                    .iter()
                    .any(|field| field.to_lowercase().contains(n.as_str()))
            })
    }
}

pub(super) async fn activities(
    State(state): State<AppState>,
    Query(p): Query<PageQuery>,
) -> Result<Json<serde_json::Value>> {
    let now = now_ms();
    let limit = p.limit.unwrap_or(DEFAULT_PAGE);
    let needle = match p.q.as_deref().map(str::trim) {
        None => None,
        Some(q) if q.is_empty() || q.chars().count() > MAX_QUERY_CHARS || q.contains('\0') => {
            return Err(invalid());
        }
        Some(q) => Some(q.to_lowercase()),
    };
    let category = match p.category {
        None => None,
        Some(id) => Some(Category::try_from(id).map_err(|_| invalid())?),
    };
    let after = match (p.after_start_ms, p.after_id) {
        (None, None) => None,
        (Some(start), Some(id)) if valid_id(&id) => Some((start, id)),
        _ => return Err(invalid()),
    };
    if p.from_ms < 0
        || p.to_ms > now + 60_000
        || p.to_ms <= p.from_ms
        || p.to_ms - p.from_ms > RETENTION
        || !(1..=MAX_PAGE).contains(&limit)
        || after.is_some() && p.epoch.is_none()
    {
        return Err(invalid());
    }
    let filter = Filter { needle, category };
    let (from, to) = (p.from_ms, p.to_ms);
    state
        .database(move |db| {
            let tx = db.transaction().map_err(storage_error)?;
            let db = &tx;
            let epoch: i64 = db
                .query_row("SELECT epoch+(SELECT revision FROM screen_projection WHERE slot=1) FROM journal_meta", [], |r| r.get(0))
                .map_err(storage_error)?;
            if p.epoch.is_some_and(|e| e != epoch) {
                return Err(ApiError(StatusCode::CONFLICT, "cursor expired"));
            }
            let (after_start, after_id) = after.unwrap_or((i64::MIN, String::new()));
            let mut statement = db
                .prepare("SELECT id, start_ms, end_ms, app, category FROM visible_journal_activities WHERE end_ms > ?1 AND start_ms < ?2 AND (start_ms, id) > (?3, ?4) ORDER BY start_ms, id LIMIT ?5")
                .map_err(storage_error)?;
            let rows = statement
                .query_map(
                    params![from, to, after_start, after_id, SCAN_LIMIT as i64],
                    |r| {
                        let category: String = r.get(4)?;
                        Ok(Activity {
                            id: r.get(0)?,
                            start_ms: r.get(1)?,
                            end_ms: r.get(2)?,
                            app: r.get(3)?,
                            category: serde_json::from_str(&category).map_err(|e| {
                                rusqlite::Error::FromSqlConversionFailure(
                                    4,
                                    rusqlite::types::Type::Text,
                                    Box::new(e),
                                )
                            })?,
                        })
                    },
                )
                .map_err(storage_error)?
                .collect::<std::result::Result<Vec<_>, _>>()
                .map_err(storage_error)?;
            let mut page = Page {
                activities: Vec::new(),
                cards: Vec::new(),
                next_cursor: None,
                journal_epoch: epoch,
                scanned: 0,
            };
            let mut last_key: Option<(i64, String)> = None;
            for stored in rows {
                if page.cards.len() == limit {
                    break;
                }
                page.scanned += 1;
                last_key = Some((stored.start_ms, stored.id.clone()));
                let clipped = Activity {
                    start_ms: stored.start_ms.max(from),
                    end_ms: stored.end_ms.min(to),
                    ..stored
                };
                let card = journal::cards(db, std::slice::from_ref(&clipped))?
                    .pop()
                    .ok_or_else(|| storage_error("missing card"))?;
                if filter.matches(&card) {
                    page.activities.push(clipped);
                    page.cards.push(card);
                }
            }
            if !last_key_is_final(db, from, to, last_key.as_ref())? {
                page.next_cursor = last_key.map(|(after_start_ms, after_id)| Cursor {
                    after_start_ms,
                    after_id,
                });
            }
            // Shorten the page rather than truncate: the cursor moves back to the last
            // card still included, so the omitted cards arrive on the next request.
            while serde_json::to_vec(&page).map_err(storage_error)?.len() > MAX_RESPONSE_BYTES
                && page.cards.len() > 1
            {
                let keep = page.cards.len() / 2;
                page.cards.truncate(keep);
                page.activities.truncate(keep);
                let last = &page.activities[keep - 1];
                let stored_start: i64 = db
                    .query_row("SELECT start_ms FROM visible_journal_activities WHERE id=?", [&last.id], |r| {
                        r.get(0)
                    })
                    .map_err(storage_error)?;
                page.next_cursor = Some(Cursor {
                    after_start_ms: stored_start,
                    after_id: last.id.clone(),
                });
            }
            serde_json::to_value(page).map(Json).map_err(storage_error)
        })
        .await
}

/// True when no stored row in the window sorts after `last` (or the window is empty).
fn last_key_is_final(
    db: &Connection,
    from: i64,
    to: i64,
    last: Option<&(i64, String)>,
) -> Result<bool> {
    let Some((start, id)) = last else {
        return Ok(true);
    };
    let more: bool = db
        .query_row(
            "SELECT EXISTS(SELECT 1 FROM visible_journal_activities WHERE end_ms > ?1 AND start_ms < ?2 AND (start_ms, id) > (?3, ?4))",
            params![from, to, start, id],
            |r| r.get(0),
        )
        .map_err(storage_error)?;
    Ok(!more)
}
