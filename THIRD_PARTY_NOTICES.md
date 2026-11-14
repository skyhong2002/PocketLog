# Third-party notices

PocketLog's original code and documentation are MIT-licensed. Dependencies and
bundled tools retain their respective licenses.

## Gradle Wrapper

`android/gradlew` and `android/gradle/wrapper/gradle-wrapper.jar` are from Gradle
8.11.1, carried over from the infovore development setup. Gradle is licensed under
the Apache License, Version 2.0. The launcher's original copyright and license
header is preserved. The wrapper JAR's bundled license and dependency notices are
also reproduced in [docs/licenses/gradle-wrapper-LICENSE.txt](docs/licenses/gradle-wrapper-LICENSE.txt).

Source: https://github.com/gradle/gradle/tree/v8.11.1

## Research references

The comparison documents link to other projects for research. They do not bundle
those projects' implementations or relicense their code. Dayflow inspired the
product direction; PocketLog does not include Dayflow implementation code.

## Build dependencies

Rust and Android dependencies are identified by `server/Cargo.lock` and the Android
Gradle build files. Their upstream licenses continue to apply when distributing
builds. The root MIT license does not replace those terms.
