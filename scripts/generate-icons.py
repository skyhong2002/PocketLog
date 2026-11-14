#!/usr/bin/env python3
"""Generate brand/Android assets: uv run --with pillow scripts/generate-icons.py."""

import base64
import io
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "docs/assets"
RES = ROOT / "android/app/src/main/res"
source = Image.open(ASSETS / "icon-source.png").convert("RGBA")
# Remove only the connected, near-white exterior; retain the white phone/pocket.
mask = Image.new("RGB", source.size)
pixels = source.get_flattened_data() if hasattr(source, "get_flattened_data") else source.getdata()
mask.putdata([(255, 255, 255) if min(p[:3]) >= 244 else (0, 0, 0) for p in pixels])
for corner in [(0, 0), (source.width - 1, 0), (0, source.height - 1), (source.width - 1, source.height - 1)]:
    ImageDraw.floodfill(mask, corner, (255, 0, 0))
alpha = Image.new("L", source.size)
pixels = mask.get_flattened_data() if hasattr(mask, "get_flattened_data") else mask.getdata()
alpha.putdata([0 if p == (255, 0, 0) else 255 for p in pixels])
source.putalpha(alpha)
bounds = source.getbbox()
source = source.crop(bounds)

def resized(size):
    return source.resize((size, size), Image.Resampling.LANCZOS)

resized(1024).save(ASSETS / "icon.png", optimize=True)
resized(256).save(ASSETS / "favicon.png", optimize=True)
# The OS supplies the adaptive mask. Fill the outside corners to avoid white
# seams when an OEM uses a mask different from the rendered tile's silhouette.
art = Image.new("RGBA", (1024, 1024), "#FA856D")
art.alpha_composite(resized(1024))
folder = RES / "drawable-nodpi"
folder.mkdir(parents=True, exist_ok=True)
art.convert("RGB").save(folder / "ic_launcher_art.png", optimize=True)

buffer = io.BytesIO()
resized(128).save(buffer, format="PNG", optimize=True)
encoded = base64.b64encode(buffer.getvalue()).decode()
for theme, color in [("light", "#34303A"), ("dark", "#FFF4EE")]:
    (ASSETS / f"wordmark-{theme}.svg").write_text(
        '<svg xmlns="http://www.w3.org/2000/svg" width="360" height="80" viewBox="0 0 360 80" role="img" aria-labelledby="title">'
        '<title id="title">PocketLog</title>'
        f'<image x="2" y="8" width="64" height="64" href="data:image/png;base64,{encoded}"/>'
        f'<text x="83" y="51" fill="{color}" font-size="41" font-weight="700" font-family="Inter,Segoe UI,Arial,sans-serif" letter-spacing="-1.5">PocketLog</text></svg>\n'
    )
