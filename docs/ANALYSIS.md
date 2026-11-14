# Private context analysis

The Compose deployment includes a separate `pocketlog-worker` process with no
owner API token. It shares the private SQLite volume, waits for completed sessions
to settle for 12 minutes, and checks for work every 60 seconds. App-only timeline
cards work without it. With no configured provider, it writes a maximum 300-character
visible-text excerpt, labeled `local_excerpt`, and deletes consumed raw context.
Images alone have no semantic fallback; the card stays app-only in meaning.

All raw observations expire at 24 hours even if there is no matching app session,
no model, invalid images, repeated failures or abandoned jobs. Cleanup runs on API
use and each worker pass; an idle server's scheduled cleanup can lag by up to one
hour if the worker is stopped. Turning off a host also stops retention execution.
Derived cards and reflections have the journal's 30-day retention. Excerpts can
contain private words; they are private journal data, not anonymized telemetry.

## Optional model

No provider is configured by default and no provider key is borrowed from infovore.
Use **Bring your own AI** in Android settings or the private website. Configure
the full OpenAI-compatible Chat Completions URL, explicit model ID, API key,
JSON Schema/JSON Object compatibility mode and whether the model supports images.
Keys travel over HTTPS to the private server and live in a dedicated config volume,
not SQLite, the phone or read responses. Saving does not call a model. The explicit
connection test sends only fixed synthetic text.

Each save creates a fresh provider identity; each phone must explicitly renew
future-capture consent for that exact configuration. A missing key retains it only
on the same endpoint; switching endpoints clears it unless a new key is provided.
Disabling writes a tombstone that overrides legacy environment configuration.
The API mounts settings read/write and the worker read-only, reloading each pass.

Legacy deployments without a settings file may still set these variables **only
on the worker** using a private Compose override after reviewing provider policy:

- `POCKETLOG_AI_ENABLED=true`
- `POCKETLOG_AI_URL`: full Chat Completions URL, for example a trusted local
  `http://127.0.0.1:11434/v1/chat/completions`, or your chosen provider's HTTPS URL.
- `POCKETLOG_AI_MODEL`: explicit model ID supporting strict JSON-schema outputs;
  image input support is also required if you consent to thumbnails.
- `POCKETLOG_AI_KEY_FILE`: optional mounted provider secret file, never the owner
  token, and never a URL parameter. Local services may not need a key.

Loopback above refers to the worker's own network namespace; a host model needs
an explicitly arranged local network or trusted HTTPS ingress. HTTP to remote
hosts is rejected. URL credentials, query strings, fragments and redirects are
rejected. System certificate verification remains enabled. The worker disables
proxy environment inheritance and bounds connection/request time and response
bytes. Do not put keys into Compose source files or commit a private override.

Additionally enable model processing explicitly in Android settings. Every
observation carries its own consent; enabling the server later cannot authorize
old unconsented data. A group containing unconsented observations gets only the
local fallback. Thumbnails require separate capture consent. Up to six JPEGs
and 6,000 text characters (at most 24 KiB UTF-8) enter one request. Input images
are max 24 KiB; only JPEG decoding in the worker, dimensions at most 960 by 960,
with an 8 MiB decoder allocation budget. Invalid images are skipped. The API never
decodes images or serves raw context.

The compatible request format uses `response_format: json_schema`, `strict: true`,
all fields required, and `additionalProperties: false`; see
[official OpenAI documentation](https://developers.openai.com/api/docs/guides/structured-outputs#supported-schemas).
No model is selected automatically. Provider refusal, truncation, malformed JSON,
unknown categories, excessive output lengths, network failures or HTTP errors
retry at most four times before a local excerpt is saved. Requests are never
logged, and prompts contain no API owner token or model tools.

The worker commits only while source records still exist in the same deletion
epoch. Owner deletion invalidates in-flight work. Manual edits always take
precedence over worker output. A model request already sent cannot be recalled
from a provider; deletion does not erase provider copies, backups or exports.
Changing providers requires the owner to review and renew their consent; the
configuration binds observation consent to provider identity. There is no
provider-side deletion API. Existing legacy consent is not authorization for a
new app-configured provider.

## Interactive questions and recaps

Native/web Chat and web daily/weekly recaps use the configured provider only after
explicit per-request approval. The outgoing scope is derived cards only (up to 100,
24 KB), never raw screen context or feelings/reflections. Questions are independent
and replies are not stored. See [API](API.md) and [parity matrix](DAYFLOW_PARITY.md).
