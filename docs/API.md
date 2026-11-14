# PocketLog API v1

All `/v1/*` routes require `Authorization: Bearer <64-hex-owner-token>`. `/healthz`
returns only process health. Responses use `Cache-Control: no-store`; there is no
CORS allowance or public data projection. Bodies are capped at 64 KiB.

| Method | Path | Behavior |
|---|---|---|
| POST | `/v1/activities` | `{activities:[{id,start_ms,end_ms,app,category}]}`, max 100 |
| POST | `/v1/checkins` | `{id,at_ms,mood,energy}`, mood/energy 1–5 |
| GET | `/v1/timeline?from_ms=…&to_ms=…` | Up to 26 hours, clipped intervals; bounded results and `truncated` |
| DELETE | `/v1/journal` | Deletes this single owner's activities and check-ins |

Timestamps are Unix milliseconds. Clients choose their local day boundary (the
Android UI uses 04:00 and the phone's current time zone, including DST). Allowed
categories: `unsorted`, `work`, `learning`, `communication`, `entertainment`,
`life_admin`, `rest`. `app` is an application package identifier, not screen text.
Unknown fields, including screenshots, are rejected. IDs are client-generated,
stable across retries. Reusing an activity ID for different content returns 409
and rolls back the whole batch. Check-in IDs support explicit replacement.

Current policy: last 30 days only; expired data is pruned at startup, hourly and
on use. Up to 100,000 activities and 10,000 check-ins; capacity exhaustion refuses
new data without silently evicting retained records. Queries return at most 1,000
of each record type. Clients must display `truncated`, rather than interpret this
response as complete. Retention settings/pagination are future work.

SQLite is **not encrypted by this implementation**. Use an encrypted host disk,
restricted volume access and encrypted backups. Secure deletion on SSDs, backups,
and filesystem snapshots cannot be guaranteed by SQL deletion. The server owner
can read the journal. See [security boundaries](SECURITY.md).

