# Architecture decision 001: a small phone client and a private server

Status: accepted as the initial direction, with implementation stages below.

## Product boundary

PocketLog is a private activity journal, not a screen-time productivity score.
The goal is a Dayflow-like chronological review with editable categories, optional
context, explicit feelings, and reflection on what replenishes or drains energy.
Screen-derived descriptions are uncertain observations. Feelings are self-reported;
AI must not diagnose mental health or equate entertainment with unhealthy behavior.

## Responsibilities

| Component | Technology | Responsibility |
|---|---|---|
| Android | Kotlin, platform APIs; Compose UI planned | Consent, low-cost sampling, pre-capture exclusions, encrypted bounded queue, sync, cached timeline, check-ins |
| API/storage | Rust, Axum, Tokio, SQLite | Authentication, validation, limits, retention, private history and derived records |
| Analysis worker (planned) | Separate Rust process and configurable inference provider | Deduplication, segmentation, classification, summaries and daily reflection |
| Deployment | Docker Compose, HTTPS proxy/Tailscale | Single-owner service, persistent encrypted host storage, explicit ingress |
| infovore (planned) | Restricted outbound connector | Owner-approved projection only; never direct access to PocketLog's DB |

The API must remain responsive without an AI model. A worker may read only the
jobs it needs; it will not have administrator credentials or arbitrary tools.
A local model can run on the user's computer/NAS/GPU server as a separate process.
External AI is an independent opt-in, naming the provider and exactly what leaves
the private server. Provider keys stay server-side. No default provider exists.

## Deployment choices

Recommended full mode: one self-hosted backend per person, on their own computer,
NAS, or VPS. A server is a process, not necessarily a separately rented machine.
Docker makes setup reproducible; it does not make a host confidential or secure
by itself. Start with SQLite and one service; split API/worker when inference is
added. Add PostgreSQL or a broker only when real concurrency needs justify it.

A future lightweight local mode should support app durations, rules, check-ins,
and cached review without a server. It should not promise automatic semantic
understanding of all activity. On-device models may become an explicit future
option, gated by capability, charging state and measured cost. Do not silently
fall back to cloud processing. The current development client requires a server.

Do not force users to configure every detail. Offer a recommended private-server
profile, local-only basics later, and advanced storage/provider settings with safe
defaults. A centrally hosted offering would require a new multi-tenant threat
model, independent device identities, account recovery, abuse limits and audits.

## Data lifecycle (target, not all implemented)

1. System events establish app sessions. UsageStats collection must be incremental
   and measured; Android does not provide a free continuous push feed for all app
   transitions. Accessibility/MediaProjection are distinct permissions with OS and
   distribution constraints; avoid assuming invisible unlimited capture is possible.
2. Additional context capture is explicit opt-in. Exclude password, payment,
   authentication and selected private apps before reading/storing the content.
   Respect secure windows; no bypass. Screen-off/locked states stop capture.
3. Event-based sampling, debouncing, low-resolution context and adaptive budgets
   replace fixed high-frequency screenshots. Image encoding, radio wakeups and
   accessibility traversal also cost battery, even with no on-phone AI.
4. Phone queues are bounded and encrypted with a Keystore-protected key. Initial
   target for context is max 24 h / 32 MiB, whichever comes first, with visible
   drop/backlog status. Acknowledged uploads are removed. No such queue is shipped yet.
5. Upload in batches with stable IDs, exponential backoff and explicit Wi-Fi /
   metered / charging preferences. WorkManager timing is inexact; review freshness
   and battery cost need a measured tradeoff. Do not promise five-minute delivery.
6. Server raw context has a short TTL (target <=24 h) and is removed after derived
   cards commit. Summaries and optional user notes have independent retention.
   Current service stores only app intervals/check-ins for 30 days; no raw context.
7. Phone caches derived records with a size/time budget and supports offline
   reading. Small structured summaries need not consume excessive phone storage;
   screenshots and indefinite retention are the main avoidable growth sources.
8. Deletion must cover source events, derived cards, embeddings, jobs, caches and
   integrations. Backup expiration is a separate documented boundary.

## Security and language choices

Kotlin is the native Android choice: platform permissions, Keystore and background
work integrate directly, with JVM memory safety. Use Compose for the product UI
when the core sync contract stabilizes; the initial native Views UI is deliberately
small. No WebView or JavaScript bridge is needed for private history.

Rust is selected for the network/processing service: memory safety, explicit
ownership, strong schemas and a small deployable binary. `unsafe` is forbidden in
our application code. SQLite and cryptographic dependencies may contain native or
unsafe code: memory safety is not a claim about the whole dependency graph.

Use established TLS/AES-GCM implementations. Never invent cryptography. Language
choice does not solve excessive permissions, leaking logs, unsafe prompts, tokens
or retention. These are explicit design constraints and tests.

Server analysis requires plaintext inside the trusted compute boundary. TLS in
transit + encrypted disks at rest is not end-to-end encryption against the server.
A future untrusted storage mode would need client-side encryption and computation
on a trusted client/worker, with honest functionality limits.

## Migration

Keep infovore running. Build a versioned private PocketLog API and native client
first. Port and review its collector/queue in separate changes, including measured
battery tests and sensitive-app exclusion tests. Import existing history only by
explicit export/import with dry-run validation. Replace infovore's phone source
with an opt-in connector after parity; no shared tables or shared owner tokens.
