<div align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/assets/wordmark-dark.svg">
    <img src="docs/assets/wordmark-light.svg" alt="PocketLog" width="300">
  </picture>

  <p><strong>A private activity journal for Android and the web.</strong></p>

  <p>
    See what you did, remember how you felt, and understand your rhythm.<br>
    Inspired by Dayflow, designed around a phone's battery and your privacy.
  </p>

  <p>
    <a href="https://github.com/skyhong2002/PocketLog/actions/workflows/check.yml"><img src="https://github.com/skyhong2002/PocketLog/actions/workflows/check.yml/badge.svg?branch=main" alt="Checks"></a>
    <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-285F51" alt="MIT License"></a>
  </p>
</div>

> **Early development.** The timeline reader, mood check-ins, and self-hosted API
> now include opt-in app/context capture, encrypted offline sync, editable activity
> cards, daily reflection, shared onboarding, a private responsive website and BYO AI.
> Daily/weekly review, single-turn Chat, private focus records and explicit Agents
> summary imports are available; full Dayflow parity is not shipped. Real-device capture and
> full-day battery validation are still required. There is no stable release yet.

## Your Day, in Context

Two hours in a messaging app could mean planning a project, talking to friends,
or reading the news. PocketLog aims to turn those fragments into a timeline you
can review, categorize, and correct.

<p align="center">
  <img src="docs/assets/overview.svg" alt="Product direction: capture activity, understand context, and reflect on feelings. Optional capture, private processing, and explicit mood journaling; illustration is a product concept." width="100%">
  <br><sub>Product concept, not a screenshot of the current app.</sub>
</p>

## How Did It Feel?

Record your mood and energy alongside your activities. The goal is to notice what
restores or drains you, with space for rest, entertainment, and connection.
Feelings are self-reported; screen time alone cannot tell us how you feel.

## Features

| Feature | Status |
| --- | --- |
| Shared onboarding and website | Owner login, shared setup/goals/category presentation; separate phone permission/consent flow |
| Daily timeline and overview | Native + web hourly day/week cards, 04:00 day boundary, daily/week/month totals, top apps and keywords |
| Mood, energy, and daily reflection | Explicit check-ins, editable daily reflection; offline queue |
| Private Android connection | HTTPS, no redirects, Keystore-protected credentials |
| Self-hosted storage | Rust API + isolated worker, SQLite, Docker Compose; quotas, retention and deletion |
| App activity recording | Opt-in incremental UsageStats, pause, sensitive-app and custom exclusions |
| Text and optional image context | Separate opt-ins, explicit app allowlist, event-driven capture, secure-window gate |
| Activity cards and summaries | App-only/excerpt fallback; editable categories; app-configured OpenAI-compatible BYO model with provider-bound consent |
| Encrypted offline queue and review | AES-GCM queue/cache, bounded retention/storage, retries and quarantine |
| Chat, Flow, Agents | Explicit day/week AI questions, private focus records and chosen Codex/Claude summary import; see parity boundaries below |
| Export | Private JSON + timeline clipboard; Android document picker |
| Device pairing, import and infovore publishing | Planned; no shared database or automatic publishing |

See the [infovore port coverage](docs/INFOVORE_PORT.md), [analysis setup](docs/ANALYSIS.md),
[Dayflow feature matrix](docs/DAYFLOW_PARITY.md), [UI guidelines](docs/UI_GUIDELINES.md),
and [roadmap](docs/ROADMAP.md) for exact boundaries and remaining work.

## Privacy

PocketLog separates a lightweight Android client from a server you control.
Heavier analysis runs in a separate server worker. No external AI provider is
configured by default; model processing requires independent per-capture consent
and explicit provider setup. No native Gemini/Anthropic protocol or CLI model is
implemented; use an OpenAI-compatible endpoint. There are no analytics or public journal endpoints.

Credentials are protected by Android Keystore. The API authenticates journal
requests and limits input size, storage, and retention. The current server is
single-owner and uses one owner token; device-specific access is planned.

SQLite is not encrypted by the application: use encrypted host storage and
backups. A server that analyzes your activity can read it. This is not end-to-end
encryption against the server, and the project has not had an independent security
audit. Read the [security boundaries](docs/SECURITY.md) before deploying.

## Build From Source

Requirements: Android 14+, JDK 17+, Android SDK 36, and Docker Compose for the server.
Rust development requires stable Rust (minimum 1.90).

```sh
git clone https://github.com/skyhong2002/PocketLog.git
cd PocketLog

# Start the private development API on localhost:18748.
./scripts/init-secret.sh
docker compose up --build -d

# Build the Android app.
cd android
./gradlew assembleDebug
```

Put the service behind a trusted HTTPS proxy or Tailscale Serve. Open its HTTPS
origin in a browser for the private website and onboarding, then configure the app
with its HTTPS origin and owner token. See [setup](docs/SETUP.md) for secret
handling and deployment details. The new app does not connect to the old infovore
phone API or migrate its history.

## Research

The design draws on [Dayflow](https://github.com/JerryZLiu/Dayflow)'s activity
review, [ActivityWatch](https://github.com/ActivityWatch/aw-android)'s event
collection, and [Daily You](https://github.com/Demizo/Daily_You)'s explicit mood
journaling. Capturing a screen, understanding its contents, and reflecting on a day
are separate capabilities.

Our [11-project comparison](docs/research/comparison-audit.md) documents those
differences with source-code references, including AWARE's screenshot capture
and the distinction between desktop and mobile features. The original research
is in Traditional Chinese; an [interactive table](docs/research/comparison.html)
can be downloaded and opened locally. Findings are dated November 14, 2026;
real-device battery performance was not tested.

## Contributing

Issues and pull requests are welcome. For larger changes, open an issue to discuss
the scope first. Start with the [architecture](docs/ARCHITECTURE.md),
[roadmap](docs/ROADMAP.md), and [engineering guidelines](AGENTS.md).
See the [API reference](docs/API.md) for the current contract.

## License

PocketLog is licensed under the [MIT License](LICENSE).
Third-party components retain their own licenses; see [notices](THIRD_PARTY_NOTICES.md).
