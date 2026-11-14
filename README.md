<div align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/assets/wordmark-dark.svg">
    <img src="docs/assets/wordmark-light.svg" alt="PocketLog" width="300">
  </picture>

  <p><strong>A private activity journal for Android.</strong></p>

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
> have initial implementations. Automatic capture, AI summaries, and daily reviews
> are planned. There is no stable release yet.

## Your Day, in Context

Two hours in a messaging app could mean planning a project, talking to friends,
or reading the news. PocketLog aims to turn those fragments into a timeline you
can review, categorize, and correct.

<p align="center">
  <img src="docs/assets/overview.svg" alt="Product direction: capture activity, understand context, and reflect on feelings. Capture and AI understanding are planned; mood check-ins have an initial implementation." width="100%">
  <br><sub>Product concept, not a screenshot of the current app.</sub>
</p>

## How Did It Feel?

Record your mood and energy alongside your activities. The goal is to notice what
restores or drains you, with space for rest, entertainment, and connection.
Feelings are self-reported; screen time alone cannot tell us how you feel.

## Features

| Feature | Status |
| --- | --- |
| Daily timeline reader | Initial implementation; requires activity data in the API |
| Mood and energy check-ins | Initial implementation |
| Private Android connection | HTTPS and Keystore-protected credentials |
| Self-hosted storage | Rust API, SQLite, Docker Compose; 30-day retention and deletion API |
| Automatic activity and context capture | Planned, with sensitive-app exclusions and battery budgets |
| AI summaries, editable categories, and daily reflection | Planned |
| Encrypted offline queue and cached review | Planned |
| Device pairing, revocation, export, and infovore integration | Planned |

See the [roadmap](docs/ROADMAP.md) for development order.

## Privacy

PocketLog separates a lightweight Android client from a server you control.
Heavier analysis is intended to run off the phone. No external AI provider,
analytics, or public journal endpoint is enabled in the current implementation.

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

Put the API behind a trusted HTTPS proxy or Tailscale Serve, then configure the app
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
