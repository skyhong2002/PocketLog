# Third-party notices

PocketLog's original code and documentation are MIT-licensed. Dependencies and
bundled tools retain their respective licenses.

## Gradle Wrapper

`android/gradlew` and `android/gradle/wrapper/gradle-wrapper.jar` are from Gradle
9.9.1 (originally carried over from the infovore development setup at 8.11.1). Gradle is licensed under
the Apache License, Version 2.0. The launcher's original copyright and license
header is preserved. The wrapper JAR's bundled license and dependency notices are
also reproduced in [docs/licenses/gradle-wrapper-LICENSE.txt](docs/licenses/gradle-wrapper-LICENSE.txt).

Source: https://github.com/gradle/gradle/tree/v9.9.1

## infovore behavior reference

The phone journal port was independently implemented after reviewing infovore at
commit `cd2bb4b7548ca3050ca65f65c24d6890b4e6a8e6`. Behavioral references include
`ScreenLogic.kt`, `ScreenActivityService.kt`, `ObservationQueue.kt`, upload engine
and worker, and `src/dayflow/phone-{store,worker,summarize,routes}.ts`. This port
uses PocketLog's own Kotlin/Rust implementation, encrypted storage and private API;
it does not bundle infovore's TypeScript service or Android collector source.
See [port coverage](docs/INFOVORE_PORT.md) for differences.

## Dayflow model prompts

The model prompts in `server/src/prompts/dayflow/` are text copied from Dayflow
(https://github.com/JerryZLiu/Dayflow, commit
`378e5a14ebdc9ab8733843efbcc304a0b6c778ab`), Copyright (c) 2025 Jerry Liu, MIT
License; see [docs/licenses/dayflow-LICENSE.txt](docs/licenses/dayflow-LICENSE.txt).
`scripts/sync-dayflow-prompts.py` regenerates them and lists what is removed. No
other Dayflow code is bundled.

## Research references

The comparison documents link to other projects for research. They do not bundle
those projects' implementations or relicense their code. Dayflow inspired the
product direction; apart from the prompts above, PocketLog does not include Dayflow
implementation code.

## Build dependencies

Rust and Android dependencies are identified by `server/Cargo.lock` and the Android
Gradle build files. Their upstream licenses continue to apply when distributing
builds. The root MIT license does not replace those terms.

## Web fonts

The website bundles Noto Sans and Noto Sans TC variable fonts from Google Fonts,
converted from upstream TTF to WOFF2 without changing glyphs or family names.
Both retain SIL Open Font License 1.1; see
[Latin notice](server/web/fonts/notosans-OFL.txt) and
[Traditional Chinese notice](server/web/fonts/notosanstc-OFL.txt).
Sources: https://github.com/google/fonts/tree/main/ofl/notosans and
https://github.com/google/fonts/tree/main/ofl/notosanstc .
Fonts are served from the same origin; no external font request is made.
