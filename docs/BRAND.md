# PocketLog identity

The mark is a white pocket holding a phone on a rounded apricot/coral tile.
Its soft lighting adds depth while the single silhouette stays readable at small
sizes. The phone represents the Android activity journal; the pocket represents
keeping those daily fragments together under the owner's control.

`assets/icon-source.png` is the approved generated artwork. `assets/icon.png`
has a transparent exterior; `assets/favicon.png` is the T3 project icon. The two
wordmarks embed the same artwork for light and dark backgrounds.

Regenerate derived assets with Python and Pillow:

```sh
uv run --with pillow scripts/generate-icons.py
```

Android uses the rendered artwork with an OS-provided adaptive mask
and a separate monochrome phone/pocket vector for
themed icons. The decorative tile is not included in the monochrome silhouette.

The source was generated with built-in `image_gen`, then edited to replace the
original card with a phone. Dayflow's rounded tile and soft light were style
references; its flower symbol is not used. Development concepts in `output/`
are not production brand assets. This identity does not imply that planned
automatic capture or AI features have shipped.
