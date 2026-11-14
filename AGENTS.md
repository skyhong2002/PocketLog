# PocketLog engineering defaults

Read README.md and docs/SECURITY.md before changing data collection, sync or auth.

- Keep private journal content private. No implicit cloud AI, analytics, public
  projection or infovore publishing. Implement consent and pre-capture exclusion
  before introducing screenshot/text collection.
- Keep Android work bounded; no on-device inference or periodic high-frequency
  sampling without a measured battery budget and explicit product decision.
- Kotlin on Android, Rust for the service. No application `unsafe`, custom crypto,
  relaxed TLS verification, credential-bearing URLs or request/content logging.
- Retain documented quotas, request bounds, idempotency and data retention.
- Document implementation status honestly; a roadmap is not a shipped feature.
- Run Rust fmt, clippy with warnings denied, tests and cargo audit; run Android
  unit tests, assembleDebug and lintDebug. Smoke-test Docker for deployment changes.
- Never commit tokens, signing keys, real journals, caches or APK build products.
  Stage explicit source paths. PocketLog is public and MIT-licensed. Do not change
  visibility or licensing without the owner's instruction.
