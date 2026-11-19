# Classification benchmark

A versioned, synthetic quality baseline for PocketLog's deterministic
classification (issue #96). It contains no journal data.

- Fixture: [`server/tests/fixtures/classification-v1.json`](../server/tests/fixtures/classification-v1.json)
- Runner: [`server/tests/classification_benchmark.rs`](../server/tests/classification_benchmark.rs)
  (part of `cargo test`; print the report with
  `cargo test --test classification_benchmark -- --nocapture`)

## How classification actually works

The benchmark matches production, which matters for what rules can see:

1. Android uploads every app session as `unsorted`; the phone never guesses.
2. Without a model, the worker keeps that category and titles the card with the
   **package name** (`local_excerpt`); excerpt text goes into the summary only.
3. An owner-enabled model may return a title, summary and one category (`ai`).
4. Owner rules (`/v1/workspace` `rules`, first enabled match wins, app exact,
   title substring case-insensitive) then override the stored category.
5. A user edit (`source: user`) wins over rules and is never overwritten by the
   worker (`analysis.rs` tests `fallback_consumes_raw_context_…` and
   `late_local_context_preserves_model_and_user_notes_…`, API test
   `custom_categories_rules_preserve_replays_and_manual_edits`).

Each sample records `kind`, `source`, `app`, the card `title` it would really
have, the stored `base` category, the human-expected category and a note. The
runner sends every sample through `POST /v1/workspace/preview`, which uses the
same classifier as the timeline, once per rule set. It also asserts two
invariants on every sample: manual edits always win, and the reported rule is the
first enabled match. Accuracy must equal the recorded baseline; any change, up or
down, fails until reviewed and re-recorded, so a fix cannot silently overfit.

## Baseline v1 (2026-11-17)

31 samples. Model path **not** evaluated (it needs the owner's explicit consent
and a configured provider; no samples were sent to any model).

| Rule set | Correct | Notes |
| --- | --- | --- |
| `none` (default workspace) | 6 / 31 (19%) | Only manual edits and the two genuinely unsorted samples are right |
| `starter` (16 enabled app/title rules + 1 disabled) | 25 / 31 (81%) | All app-only, idle, excerpt, conflict-order and manual-edit samples right |

Per kind with `starter`: app_only 9/9, idle 2/2, excerpt 2/2, rule_conflict 3/3,
manual_edit 4/4, cross_activity 1/1, multilingual 2/3, unsorted 1/2,
same_app_different_work **1/5**. Preview latency is about 0.3 ms per sample
in-process (31 previews ≈ 10 ms, 2-CPU container); classification cost is not a
bottleneck.

Remaining misses and what they mean:

| Sample | Expected → got | Cause |
| --- | --- | --- |
| `le-chrome-docs`, `le-chrome-shop` | learning / life_admin → unsorted | Same browser, different work; a `local_excerpt` card's title is the package, so no title rule can tell them apart |
| `le-youtube-lecture` | learning → entertainment | Same as above; the package-level YouTube rule applies |
| `ai-docs-recipe` | life_admin → work | An app-wide Docs rule overrides a model title that described a personal list |
| `ai-chrome-news-ja` | news_reading → unsorted | Substring rules are per language; `ニュース` matches neither `新聞` nor `news` |
| `ai-maps` | life_admin → unsorted | No rule for the app; unsorted is safer than a guess |

Conclusions: app rules alone handle single-purpose apps completely. Multi-purpose
apps (browser, video, documents) cannot be classified deterministically from
package names, and title rules only help once a model or the owner has written a
real title. Candidate follow-ups, each needing its own decision and regression
sample rather than special-casing this fixture:

- Let rules optionally match the card **summary** (the local excerpt), not only
  the title, so local-only setups can separate browser/video uses without a model.
- Show, in the rule editor, when an app-wide rule would override existing
  model-titled cards (the `ai-docs-recipe` case).

Adding samples: append to the fixture (keep IDs unique, synthetic only), run the
benchmark, review every changed miss and record the new baseline. Bump the file
version when the meaning of fields changes.
