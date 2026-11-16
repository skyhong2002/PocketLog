//! Versioned deterministic classification benchmark (#96).
//!
//! Runs every synthetic sample in `fixtures/classification-v*.json` through the
//! production classifier via `POST /v1/workspace/preview`, once per rule set, and
//! compares accuracy with the recorded baseline. A change in either direction
//! fails, so improvements and regressions are both reviewed and re-recorded.
//! No model is called; the optional model path needs explicit owner consent and
//! is not part of CI. Print the report with `cargo test --test
//! classification_benchmark -- --nocapture`.
use axum::{
    Router,
    body::Body,
    http::{Request, StatusCode},
};
use http_body_util::BodyExt;
use pocketlog_server::{AppState, router};
use serde_json::{Value, json};
use std::collections::BTreeMap;
use tower::ServiceExt;

const TOKEN: &str = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
const FIXTURE: &str = include_str!("fixtures/classification-v1.json");

async fn call(app: &Router, method: &str, path: &str, body: Value) -> (StatusCode, Value) {
    let response = app
        .clone()
        .oneshot(
            Request::builder()
                .method(method)
                .uri(path)
                .header("content-type", "application/json")
                .header("authorization", format!("Bearer {TOKEN}"))
                .body(Body::from(body.to_string()))
                .unwrap(),
        )
        .await
        .unwrap();
    let status = response.status();
    let bytes = response.into_body().collect().await.unwrap().to_bytes();
    (
        status,
        serde_json::from_slice(&bytes).unwrap_or(Value::Null),
    )
}

#[derive(Default)]
struct Score {
    correct: usize,
    total: usize,
    by_kind: BTreeMap<String, (usize, usize)>,
    confusion: BTreeMap<(String, String), usize>,
    misses: Vec<String>,
}

#[tokio::test]
async fn deterministic_rules_match_the_recorded_baseline() {
    let fixture: Value = serde_json::from_str(FIXTURE).unwrap();
    assert_eq!(fixture["version"], 1);
    let samples = fixture["samples"].as_array().unwrap();
    let mut ids: Vec<&str> = samples.iter().map(|s| s["id"].as_str().unwrap()).collect();
    ids.sort();
    ids.dedup();
    assert_eq!(ids.len(), samples.len(), "sample IDs are unique");
    let app =
        router(AppState::new(rusqlite::Connection::open_in_memory().unwrap(), TOKEN).unwrap());
    let (status, base_workspace) = call(&app, "GET", "/v1/workspace", Value::Null).await;
    assert_eq!(status, StatusCode::OK);
    let mut report = String::new();
    let mut results = Vec::new();
    for (name, rules) in fixture["rule_sets"].as_object().unwrap() {
        let mut workspace = base_workspace.clone();
        workspace["rules"] = rules.clone();
        let mut score = Score::default();
        let started = std::time::Instant::now();
        for sample in samples {
            let edited = sample["edited"].as_bool().unwrap();
            let (status, result) = call(
                &app,
                "POST",
                "/v1/workspace/preview",
                json!({"workspace":workspace,"sample":{
                    "app":sample["app"],"title":sample["title"],
                    "category":sample["base"],"edited":edited}}),
            )
            .await;
            assert_eq!(status, StatusCode::OK, "{}: {result}", sample["id"]);
            let got = result["category"].as_str().unwrap().to_owned();
            let expected = sample["expected"].as_str().unwrap().to_owned();
            // Invariants that hold for every rule set, independent of accuracy.
            if edited {
                assert_eq!(
                    got, sample["base"],
                    "manual edits always win: {}",
                    sample["id"]
                );
                assert_eq!(result["manual_override"], true);
                assert!(result["matched_rule_id"].is_null());
            }
            if let Some(rule) = result["matched_rule_id"].as_str() {
                let first = rules.as_array().unwrap().iter().find(|r| {
                    r["enabled"] != false
                        && r["app"].as_str().is_none_or(|a| a == sample["app"])
                        && r["title_contains"].as_str().is_none_or(|t| {
                            sample["title"]
                                .as_str()
                                .unwrap()
                                .to_lowercase()
                                .contains(&t.to_lowercase())
                        })
                });
                assert_eq!(
                    first.unwrap()["id"],
                    rule,
                    "first matching rule wins: {}",
                    sample["id"]
                );
            }
            let kind = sample["kind"].as_str().unwrap().to_owned();
            let entry = score.by_kind.entry(kind).or_default();
            entry.1 += 1;
            score.total += 1;
            if got == expected {
                score.correct += 1;
                entry.0 += 1;
            } else {
                *score
                    .confusion
                    .entry((expected.clone(), got.clone()))
                    .or_default() += 1;
                score.misses.push(format!(
                    "{} (expected {expected}, got {got})",
                    sample["id"].as_str().unwrap()
                ));
            }
        }
        let elapsed = started.elapsed();
        report.push_str(&format!(
            "\nrule set {name:>8}: {}/{} correct ({:.0}%), {} previews in {:.1} ms\n",
            score.correct,
            score.total,
            100.0 * score.correct as f64 / score.total as f64,
            score.total,
            elapsed.as_secs_f64() * 1000.0
        ));
        for (kind, (ok, n)) in &score.by_kind {
            report.push_str(&format!("  {kind:<24} {ok}/{n}\n"));
        }
        for ((expected, got), n) in &score.confusion {
            report.push_str(&format!("  confusion {expected} -> {got}: {n}\n"));
        }
        for miss in &score.misses {
            report.push_str(&format!("  miss {miss}\n"));
        }
        let recorded = &fixture["baseline"][name];
        results.push((
            name.clone(),
            (recorded["correct"].as_u64(), recorded["total"].as_u64()),
            (Some(score.correct as u64), Some(score.total as u64)),
        ));
    }
    println!("{report}");
    for (name, recorded, measured) in results {
        assert_eq!(
            recorded, measured,
            "baseline for rule set {name} changed; review and re-record it in the fixture{report}"
        );
    }
}
