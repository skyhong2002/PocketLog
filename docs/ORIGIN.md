# Origin

PocketLog grew out of the Android phone activity recorder in
https://github.com/skyhong2002/infovore (reference commit cd2bb4b).

This repository begins an independent implementation. The Gradle launcher/wrapper
and Android build configuration were copied/adapted from that repository; retain
upstream wrapper notices. Health Connect, public infovore browsing and the existing
screen collector have not been copied into this product. Existing production
services, phone history and app installation were left unchanged.

The target product is an automatic activity diary with optional context capture,
daily reflection and explicit feelings, inspired by Dayflow's usage pattern.
The name PocketLog was selected by Sky after the naming discussion. This does not
constitute trademark clearance. PocketLog is MIT-licensed, as selected by Sky;
third-party components retain their own licenses (see
[third-party notices](../THIRD_PARTY_NOTICES.md)).
