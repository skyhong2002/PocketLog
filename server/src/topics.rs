//! Model-labeled keyword topics for card titles, like infovore's Dayflow topic labels.
//! Only cleaned card titles are sent: no summaries, raw text, images, app lists,
//! timestamps or identifiers. Runs only while the owner's automatic switch is on.
use super::*;
use rusqlite::OptionalExtension;

const VERSION: &str = "pocketlog-topics-v1";
pub(crate) const BATCH: usize = 12;
const ROUNDS: usize = 4;
const MAX_ATTEMPTS: i64 = 4;
const SCAN_PAGE: i64 = 500;
const SCAN_PAGES: i64 = 20;
const MIN_KEYWORD_CONFIDENCE: f64 = 0.6;

#[derive(Clone, Debug, Deserialize, Serialize, PartialEq)]
#[serde(deny_unknown_fields)]
pub(crate) struct Label {
    pub name: String,
    pub kind: Kind,
    pub confidence: f64,
}
#[derive(Clone, Copy, Debug, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub(crate) enum Kind {
    Project,
    Topic,
    Work,
    Tool,
}

pub(super) fn migrate(db: &Connection) -> rusqlite::Result<()> {
    db.execute_batch("CREATE TABLE IF NOT EXISTS title_topics(hash TEXT PRIMARY KEY,labels TEXT NOT NULL,done INTEGER NOT NULL,attempts INTEGER NOT NULL,next_ms INTEGER NOT NULL,at_ms INTEGER NOT NULL);")
}

fn secret_prefix(token: &str) -> bool {
    let lower = token.to_lowercase();
    ["sk", "ghp", "github_pat", "token", "password", "secret"]
        .iter()
        .any(|p| {
            lower
                .strip_prefix(p)
                .is_some_and(|rest| rest.starts_with('_') || rest.starts_with('-'))
        })
}
fn opaque(token: &str) -> bool {
    token.chars().count() >= 24
        && token
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '-')
}
fn domain(token: &str) -> bool {
    let parts: Vec<&str> = token
        .trim_end_matches(|c: char| ".,;:!?)".contains(c))
        .split('.')
        .collect();
    parts.len() >= 2
        && parts
            .iter()
            .all(|p| !p.is_empty() && p.chars().all(|c| c.is_ascii_alphanumeric() || c == '-'))
        && parts
            .last()
            .is_some_and(|tld| tld.len() >= 2 && tld.chars().all(|c| c.is_ascii_alphabetic()))
}
/// Removes URLs, emails, paths, domains, secret-like and long opaque tokens.
pub(crate) fn clean_title(title: &str) -> String {
    let mut out = Vec::new();
    let mut fenced = false;
    for token in title.split_whitespace() {
        if token.contains("```") {
            fenced = !fenced;
            continue;
        }
        let lower = token.to_lowercase();
        if fenced
            || lower.contains("://")
            || lower.starts_with("www.")
            || (token.contains('@') && token.contains('.'))
            || token.contains('/')
            || token.contains('\\')
            || domain(token)
            || secret_prefix(token)
            || opaque(token)
            || token.chars().any(char::is_control)
        {
            continue;
        }
        out.push(token);
    }
    out.join(" ").chars().take(600).collect()
}
pub(crate) fn hash(title: &str) -> String {
    format!(
        "{:x}",
        Sha256::digest(format!("{VERSION}\n{}", clean_title(title)))
    )
}

const GENERIC: &[&str] = &[
    "零散",
    "散落",
    "散漫",
    "分散",
    "短暫",
    "方向",
    "方式",
    "首頁",
    "頁面",
    "文章",
    "檔案",
    "消息",
    "通知",
    "進度",
    "回覆",
    "結果",
    "手機",
    "瀏覽",
    "scattered",
    "browsing",
    "screen",
    "window",
    "app",
    "apps",
    "phone",
];
/// Rejects unsafe, generic and duplicate names; returns at most four labels.
pub(crate) fn validate(labels: Vec<Label>) -> Vec<Label> {
    let mut seen = std::collections::BTreeSet::new();
    labels
        .into_iter()
        .map(|l| Label {
            name: l.name.trim().to_owned(),
            ..l
        })
        .filter(|l| {
            let key = l.name.to_lowercase();
            let unsafe_name = key.contains("http:")
                || key.contains("https:")
                || key.contains("www.")
                || l.name.contains('@')
                || l.name.contains('/')
                || l.name.contains('\\')
                || l.name.chars().any(char::is_control)
                || l.name
                    .split_whitespace()
                    .any(|t| secret_prefix(t) || opaque(t));
            (2..=60).contains(&l.name.chars().count())
                && (0.0..=1.0).contains(&l.confidence)
                && !unsafe_name
                && !GENERIC.contains(&key.as_str())
                && seen.insert(key)
        })
        .take(4)
        .collect()
}

fn labels(db: &Connection, hash: &str) -> Result<Option<Vec<Label>>> {
    db.query_row(
        "SELECT labels FROM title_topics WHERE hash=? AND done=1",
        [hash],
        |r| r.get::<_, String>(0),
    )
    .optional()
    .map_err(storage_error)?
    .map(|json| serde_json::from_str(&json).map_err(storage_error))
    .transpose()
}

/// Ranked keywords for the given titles: model labels where available, local
/// word extraction otherwise. Returns the ranking and "model", "local" or "mixed".
pub(crate) fn keywords(
    db: &Connection,
    titles: &[String],
) -> Result<(Vec<(String, usize)>, &'static str)> {
    let mut words = std::collections::BTreeMap::<String, usize>::new();
    let (mut labeled, mut local) = (0, 0);
    for title in titles {
        let unique: std::collections::BTreeSet<String> = match labels(db, &hash(title))? {
            Some(labels) => {
                labeled += 1;
                labels
                    .into_iter()
                    .filter(|l| l.kind != Kind::Tool && l.confidence >= MIN_KEYWORD_CONFIDENCE)
                    .map(|l| l.name)
                    .collect()
            }
            None => {
                local += 1;
                title
                    .split(|c: char| !c.is_alphabetic())
                    .filter(|w| (2..=12).contains(&w.chars().count()))
                    .map(str::to_lowercase)
                    .collect()
            }
        };
        for word in unique {
            *words.entry(word).or_default() += 1;
        }
    }
    let mut ranked: Vec<_> = words.into_iter().collect();
    ranked.sort_by(|a, b| b.1.cmp(&a.1).then(a.0.cmp(&b.0)));
    ranked.truncate(10);
    let mode = match (labeled, local) {
        (0, _) => "local",
        (_, 0) => "model",
        _ => "mixed",
    };
    Ok((ranked, mode))
}

fn pending(db: &Connection, now: i64) -> Result<Vec<(String, String)>> {
    let mut found = Vec::new();
    let mut seen = std::collections::BTreeSet::new();
    // Newest first, so fresh cards are labeled before the historical backlog.
    for page in 0..SCAN_PAGES {
        let titles: Vec<String> = db
            .prepare("SELECT n.title FROM notes n JOIN activities a ON a.id=n.id WHERE n.edited IN (1,2) AND n.category<>'\"idle\"' ORDER BY a.start_ms DESC,a.id LIMIT ? OFFSET ?")
            .map_err(storage_error)?
            .query_map([SCAN_PAGE, page * SCAN_PAGE], |r| r.get(0))
            .map_err(storage_error)?
            .collect::<std::result::Result<_, _>>()
            .map_err(storage_error)?;
        let last_page = (titles.len() as i64) < SCAN_PAGE;
        for title in titles {
            let hash = hash(&title);
            if clean_title(&title).is_empty() || !seen.insert(hash.clone()) {
                continue;
            }
            let ready: bool = db
                .query_row(
                    "SELECT NOT EXISTS(SELECT 1 FROM title_topics WHERE hash=? AND (done=1 OR next_ms>?))",
                    params![hash, now],
                    |r| r.get(0),
                )
                .map_err(storage_error)?;
            if ready {
                found.push((hash, title));
                if found.len() == BATCH {
                    return Ok(found);
                }
            }
        }
        if last_page {
            break;
        }
    }
    Ok(found)
}
fn known(db: &Connection) -> Result<Vec<(String, Kind)>> {
    let rows: Vec<String> = db
        .prepare("SELECT labels FROM title_topics WHERE done=1 AND labels<>'[]' ORDER BY at_ms DESC LIMIT 200")
        .map_err(storage_error)?
        .query_map([], |r| r.get(0))
        .map_err(storage_error)?
        .collect::<std::result::Result<_, _>>()
        .map_err(storage_error)?;
    let mut names = std::collections::BTreeSet::new();
    let mut known = Vec::new();
    for json in rows {
        for label in serde_json::from_str::<Vec<Label>>(&json).map_err(storage_error)? {
            if known.len() < 120 && names.insert(label.name.to_lowercase()) {
                known.push((label.name, label.kind));
            }
        }
    }
    Ok(known)
}

/// Labels a bounded number of new titles. Never runs without the owner's automatic switch.
pub(crate) fn process(db: &mut Connection, provider: Option<&analysis::Provider>) -> Result<usize> {
    let Some(provider) = provider.filter(|p| p.automatic()) else {
        return Ok(0);
    };
    let mut done = 0;
    for _ in 0..ROUNDS {
        let now = now_ms();
        let batch = pending(db, now)?;
        if batch.is_empty() {
            break;
        }
        let epoch: i64 = db
            .query_row("SELECT epoch FROM journal_meta", [], |r| r.get(0))
            .map_err(storage_error)?;
        let titles: Vec<String> = batch.iter().map(|(_, t)| clean_title(t)).collect();
        let result = provider.label_topics(&titles, &known(db)?);
        let tx = db.transaction().map_err(storage_error)?;
        let current: i64 = tx
            .query_row("SELECT epoch FROM journal_meta", [], |r| r.get(0))
            .map_err(storage_error)?;
        if current != epoch || !provider.automatic() {
            break;
        }
        match result {
            Ok(labels) => {
                for ((hash, _), labels) in batch.iter().zip(labels) {
                    tx.execute(
                        "INSERT OR REPLACE INTO title_topics VALUES(?,?,1,0,0,?)",
                        params![
                            hash,
                            serde_json::to_string(&labels).map_err(storage_error)?,
                            now
                        ],
                    )
                    .map_err(storage_error)?;
                }
                tx.commit().map_err(storage_error)?;
                done += batch.len();
            }
            Err(()) => {
                for (hash, _) in &batch {
                    let attempts: i64 = tx
                        .query_row(
                            "SELECT attempts FROM title_topics WHERE hash=?",
                            [hash],
                            |r| r.get(0),
                        )
                        .optional()
                        .map_err(storage_error)?
                        .unwrap_or(0)
                        + 1;
                    // After repeated failures keep local keywords for this title.
                    let give_up = attempts >= MAX_ATTEMPTS;
                    tx.execute(
                        "INSERT OR REPLACE INTO title_topics VALUES(?,'[]',?,?,?,?)",
                        params![
                            hash,
                            give_up,
                            attempts,
                            now + ((5 * 60_000) << attempts).min(6 * 3_600_000),
                            now
                        ],
                    )
                    .map_err(storage_error)?;
                }
                tx.commit().map_err(storage_error)?;
                break;
            }
        }
    }
    Ok(done)
}

#[cfg(test)]
mod tests {
    use super::*;
    fn label(name: &str, kind: Kind, confidence: f64) -> Label {
        Label {
            name: name.into(),
            kind,
            confidence,
        }
    }
    #[test]
    fn cleaning_removes_urls_paths_emails_domains_and_secrets() {
        assert_eq!(
            clean_title(
                "看 https://example.com/x 與 user@example.com 的 /home/me/file 在 example.org token_abc 和 abcdefghijklmnopqrstuvwxyz12 PocketLog 開發"
            ),
            "看 與 的 在 和 PocketLog 開發"
        );
        assert_eq!(hash("A  B"), hash("A B"));
    }
    #[test]
    fn validation_drops_unsafe_generic_duplicate_and_excess_labels() {
        let result = validate(vec![
            label("PocketLog", Kind::Project, 0.9),
            label("pocketlog", Kind::Project, 0.9),
            label("頁面", Kind::Topic, 0.9),
            label("https://x", Kind::Topic, 0.9),
            label("x", Kind::Topic, 0.9),
            label("口琴練習", Kind::Topic, 1.5),
            label("口琴", Kind::Topic, 0.8),
            label("YouTube", Kind::Tool, 0.9),
            label("樂譜整理", Kind::Topic, 0.7),
            label("博士申請", Kind::Topic, 0.7),
        ]);
        let names: Vec<_> = result.iter().map(|l| l.name.as_str()).collect();
        assert_eq!(names, ["PocketLog", "口琴", "YouTube", "樂譜整理"]);
    }
    #[test]
    fn keywords_prefer_model_labels_and_fall_back_locally() {
        let state = AppState::new(
            Connection::open_in_memory().unwrap(),
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        )
        .unwrap();
        let db = state.db.lock().unwrap();
        let labeled = "在 YouTube 看口琴教學".to_owned();
        db.execute(
            "INSERT INTO title_topics VALUES(?,?,1,0,0,0)",
            params![
                hash(&labeled),
                serde_json::to_string(&[
                    label("口琴", Kind::Topic, 0.9),
                    label("YouTube", Kind::Tool, 0.9),
                    label("猜測", Kind::Topic, 0.2)
                ])
                .unwrap()
            ],
        )
        .unwrap();
        let (ranked, mode) = keywords(&db, std::slice::from_ref(&labeled)).unwrap();
        assert_eq!((ranked, mode), (vec![("口琴".into(), 1)], "model"));
        let (ranked, mode) = keywords(&db, &[labeled, "Reading notes".into()]).unwrap();
        assert_eq!(mode, "mixed");
        assert_eq!(ranked.len(), 3);
        assert_eq!(keywords(&db, &["Reading".into()]).unwrap().1, "local");
        // Labels are derived from journal titles and are deleted with the journal.
        crate::journal::erase(&db).unwrap();
        let left: i64 = db
            .query_row("SELECT COUNT(*) FROM title_topics", [], |r| r.get(0))
            .unwrap();
        assert_eq!(left, 0);
    }
}
