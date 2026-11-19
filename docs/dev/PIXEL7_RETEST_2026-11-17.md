# Pixel 7 retest — 2026-11-17

Physical Pixel 7 (Android 16, crDroid), isolated **PocketLog Dev** sandbox
(`tw.skyhong.pocketlog.sandbox`, versionCode 36) built with AGP 9.5.1 / Gradle
9.9.1 / Kotlin 2.4.21 from PR #111. The owner app was not opened, replaced or
granted anything. Disposable API/worker containers were recreated on the image
built from the API-contract branch (same synthetic volumes, same random owner
key); only the fixed-content synthetic fixture app generated activity. The phone
stayed USB-powered and unlocked throughout, so nothing here is battery evidence.

## Expired device key (#94)

The previous one-day journal key expired while the phone was idle. On launch
the sandbox showed "伺服器拒絕這把金鑰…" and the timeline banner "伺服器曾拒絕這把金鑰，
手機已停止擷取", i.e. capture stopped instead of silently continuing. Using
**處理連線 → 清除並重新設定** and a new three-day journal key, pairing succeeded.
After the reset, app-usage recording was **off** again: a reset does not restore
consent. (The password manager offered to save the key field; declined.)

## Back navigation after the AGP 9 change (PR #111)

Back now goes only through `OnBackInvokedDispatcher`. On Android 16, both the
Back key and the edge-swipe gesture returned 每週 → 時間軸 and 設定 → 時間軸, and
from 時間軸 the gesture left the app, as documented. Android 14/15 (which rely on
the new manifest opt-in) were **not** available to test.

## Usage-access revoke → restore (#108)

Recording consent on at `1794849707755`. Each phase used the fixture for ~45 s;
timestamps are the device clock in Unix ms.

| Phase | Usage access | PocketLog saw it? | Fixture window | Uploaded? |
| --- | --- | --- | --- | --- |
| P1 | granted | — | 1794849715292–1794849760787 | no (see below) |
| revoke in Android settings | denied at 1794849788023 | no: reopening returned to the Settings activity on PocketLog's task, so `MainActivity` never resumed | — | — |
| P2 | denied | **no** | 1794849798232–1794849843749 | no |
| PocketLog resumed, showed 未授予 | denied | yes at 1794849871015 | — | — |
| P2b | denied | **yes** | 1794849879631–1794849925083 | **no** ✅ |
| restore, PocketLog resumed, showed 已授予 | granted at 1794849946162 | yes at 1794849954566 | — | — |
| P3 | granted | yes | 1794849961581–1794850007030 | **yes** ✅ `1794849962269–1794850007731` (45 s) |

After the 15-minute scan interval, one manual sync uploaded exactly one record:
the P3 session. No activity from any denied interval reached the server, and the
post-restore activity did. This is the physical acceptance the fix in PR #109
was waiting for.

Observed side effect, not a regression of #108: P1 happened **with** consent but
had not been scanned yet when the revoke was observed, and the observed boundary
reset the cursor, so P1 was discarded too. At most one scan interval (15 min) of
consented activity before an observed revoke can be lost this way. This errs
toward not collecting; whether to scan the granted interval before saving the
denied boundary is an owner decision.

P2 was not uploaded either, but only because the later observed denial reset the
cursor. A revoke and restore that both happen while PocketLog never runs remain
unobservable (see SECURITY.md); this run does not claim that case.

## Not done here

- #107 lock-screen test with the accessibility service re-enabled: needs the
  owner to unlock the phone; not attempted.
- #95 battery/Doze and the #94 24-hour run: need an unplugged, scheduled period.
