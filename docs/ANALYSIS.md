# Private context analysis

The Compose deployment includes a separate `pocketlog-worker` process with no
owner API token. It checks for work every 60 seconds. Android app usage sessions
remain the source for app time totals. When automatic AI is enabled, the worker
creates separate timeline cards directly from uploaded screen evidence. Cards can
span app switches and do not need a matching Android usage session.

The worker groups evidence into 15-minute windows after 12 minutes of settling.
It first asks the provider for timestamped observations, then asks it to turn
them into activity cards with concrete action titles. Frame numbers determine the
time boundaries. A card cannot cross a gap of more than one minute between
captures or overlap another generated card in its window. The previous
nearby card from the same provider can inform wording across windows. Windows
retain separate cards. Raw app-only cards fully contained in a generated card
are omitted from the timeline, and raw app records remain available.

Without owner automation, the existing per-session, legacy-consent or local
text excerpt workflow still runs. Images alone have no semantic local fallback.

Since 2026-11-16 raw observations are retained, not deleted after analysis.
Analysis sets a `processed` flag; only unprocessed observations are pending.
Observations that never match an app session within 24 hours stop being pending,
but their text is kept. Activities, cards, reflections and observation text are
retained permanently. Screenshots are bounded by the owner storage budget (default
10 GiB, 1 GiB–1 TiB, `GET/PUT /v1/storage`): each worker pass, when SQLite pages in
use exceed the limit, the oldest already-analyzed screenshots are removed (text
kept) down to 95% of the limit, at most 50 batches of 200 per pass, and
`text_only_before_ms` records the boundary. The database file does not shrink;
freed pages are reused. Excerpts and raw text contain private words; they are
private journal data, not anonymized telemetry.

## Optional model

No provider is configured by default and no provider key is borrowed from infovore.
Use **Bring your own AI** in Android settings or the private website. Configure
the full model API URL, protocol (OpenAI-compatible, Anthropic Messages or Gemini generateContent), explicit model ID, API key,
JSON Schema/JSON Object compatibility mode and whether the model supports images.
Keys travel over HTTPS to the private server and live in a dedicated config volume,
not SQLite, the phone or read responses. Saving does not call a model. The explicit
connection test sends only fixed synthetic text.

Each save creates a fresh provider identity with automatic summaries off. The
owner turns them on once for that exact configuration (`PUT /v1/ai/auto`, website
or Android AI settings); the phone no longer asks for per-capture model consent. A missing key retains it only
on the same endpoint; switching endpoints clears it unless a new key is provided.
Android reads `auto_summarize` from `GET /v1/ai` and can change it with a journal
key; collector keys can only read it. Old saved per-capture grants are discarded.
Disabling writes a tombstone that overrides legacy environment configuration.
The API mounts settings read/write and the worker read-only, reloading each pass.

Legacy deployments without a settings file may still set these variables **only
on the worker** using a private Compose override after reviewing provider policy:

- `POCKETLOG_AI_ENABLED=true`
- `POCKETLOG_AI_URL`: full Chat Completions URL, for example a trusted local
  `http://127.0.0.1:11434/v1/chat/completions`, or your chosen provider's HTTPS URL.
- `POCKETLOG_AI_MODEL`: explicit model ID supporting strict JSON-schema outputs;
  image input support is also required if you consent to thumbnails.
- `POCKETLOG_AI_AUTO=true`: automatic summaries/topic labels for this provider.
- `POCKETLOG_AI_KEY_FILE`: optional mounted provider secret file, never the owner
  token, and never a URL parameter. Local services may not need a key.

Loopback above refers to the worker's own network namespace; a host model needs
an explicitly arranged local network or trusted HTTPS ingress. HTTP to remote
hosts is rejected. URL credentials, query strings, fragments and redirects are
rejected. System certificate verification remains enabled. The worker disables
proxy environment inheritance and bounds connection/request time and response
bytes. Do not put keys into Compose source files or commit a private override.

With the automatic switch on, pending evidence can be analyzed even if no app
session uploaded. Two windows are attempted per worker pass. Each window uses up
to 200 frames, 12,000 characters of screen text and six spread-out JPEGs. The
second request uses only the observations, frame offsets and one nearby
AI-generated card from the same provider. Older retained evidence is not
automatically requeued. Late evidence can refresh its window; manually corrected
windows retain their existing cards. Older per-capture grants remain limited to
the legacy per-session workflow when automation is off.
Turning the switch off advances the deletion epoch; the worker checks it
between requests and before committing. Legacy environment providers use
`POCKETLOG_AI_AUTO=true`. Thumbnails still require separate capture consent.
Input images are max
300 KiB (400,000 base64 characters); only JPEG decoding in the worker, dimensions
at most 1440 by 1440, with a 16 MiB decoder allocation budget. Invalid images are skipped. The API never
decodes images. Analysis-only raw context is never served; a separate explicit
replay opt-in permits only worker-sanitized short-term thumbnails.

The compatible request format uses `response_format: json_schema`, `strict: true`,
all fields required, and `additionalProperties: false`; see
[official OpenAI documentation](https://developers.openai.com/api/docs/guides/structured-outputs#supported-schemas).
No model is selected automatically. Provider refusal, truncation, malformed JSON,
unknown categories, excessive output lengths, network failures or HTTP errors
are attempted at most four times. Exhausted capture windows leave raw app records and prior cards in place. The legacy per-session workflow uses a local excerpt after its retries. Requests are never
logged, and prompts contain no API owner token or model tools.

The worker commits only while source records still exist in the same deletion
epoch. Owner deletion invalidates in-flight work. Manual edits always take
precedence over worker output. A model request already sent cannot be recalled
from a provider; deletion does not erase provider copies, backups or exports.
Changing providers turns automatic summaries off until the owner re-enables them
for the new identity. There is no
provider-side deletion API. Existing legacy consent is not authorization for a
new app-configured provider.

## Topic keywords

While the automatic switch is on, the worker also labels card titles (user- or
AI-written, non-idle) with at most four topics `{name, kind, confidence}`, kind
`project|topic|work|tool`, like infovore's Dayflow topic labels. Only cleaned titles
are sent: URLs, emails, paths, domains, secret-like and 24+ character opaque tokens
are removed; no summaries, raw text, images, apps, times or IDs. Batches of 12,
at most four rounds per pass, newest first; generic/unsafe labels are rejected and
four failures fall back to local extraction for that title. `GET /v1/overview`
ranks model labels (excluding tools, confidence ≥0.6) and uses local word
extraction for unlabeled titles; `keyword_mode` is `model`, `local` or `mixed`.

## Interactive questions and recaps

Native/web Chat and daily/weekly saved briefs use the configured provider only after
separate consent. Each request sends at most 100 derived activity cards / 24 KB,
never raw screen context, feelings, reflections, imported agent turns or replay
images. Chat may additionally send the last six same-provider turns / 6,000 chars;
questions/answers are saved privately for 30 days and can be deleted. Brief
schedules require independent ongoing provider-bound consent, default off, and
stop matching after settings rotate. Completed daily/weekly 04:00 boundaries use
IANA timezones including DST. The worker attempts at most two periods/pass and
three calls/period, with no historical catch-up. Journal deletion disables schedules.

Native Anthropic endpoints end in `/v1/messages`; the key uses `x-api-key` with
`anthropic-version: 2023-06-01`. Native Gemini endpoints include the exact configured
model as `/models/MODEL:generateContent`; the key uses `x-goog-api-key`, never a URL
query. Tools, refusal/block responses, incomplete output and invalid summaries fail
closed. JSON Schema/JSON Object selection applies to OpenAI-compatible endpoints;
Gemini uses JSON MIME output for summaries; Anthropic receives the bounded explicit
JSON instructions. The common TLS/redirect/size/provider-consent limits apply.
