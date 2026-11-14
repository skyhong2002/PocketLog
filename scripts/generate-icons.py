#!/usr/bin/env python3
"""Generate sage brand assets from docs/assets/logo.svg.

uv run --with pillow --with cairosvg --with fonttools --with brotli scripts/generate-icons.py
"""
import io
from pathlib import Path

import cairosvg
from PIL import Image
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "docs/assets"
RES = ROOT / "android/app/src/main/res"
logo = (ASSETS / "logo.svg").read_bytes()
(ROOT / "server/web/logo.svg").write_bytes(logo)


def rendered(size):
    return cairosvg.svg2png(bytestring=logo, output_width=size, output_height=size)


for filename, size in [("icon.png", 1024), ("icon-source.png", 1024), ("favicon.png", 256)]:
    (ASSETS / filename).write_bytes(rendered(size))
(ROOT / "server/web/logo.png").write_bytes(rendered(256))
# Android supplies its own mask. Fill the corner area behind the rounded tile.
art = Image.new("RGBA", (1024, 1024), "#6F997E")
art.alpha_composite(Image.open(io.BytesIO(rendered(1024))))
(RES / "drawable-nodpi").mkdir(exist_ok=True)
art.convert("RGB").save(RES / "drawable-nodpi/ic_launcher_art.png", optimize=True)

# Keep wordmarks portable while using the actual selected C font, not fallback text.
font = instantiateVariableFont(
    TTFont(ROOT / "server/web/fonts/notosans.woff2"), {"wght": 400, "wdth": 100}
)
glyphs = font.getGlyphSet()
cmap = font.getBestCmap()
scale = 41 / font["head"].unitsPerEm
x = 83
paths = []
for char in "PocketLog":
    name = cmap[ord(char)]
    pen = SVGPathPen(glyphs)
    glyphs[name].draw(TransformPen(pen, (scale, 0, 0, -scale, x, 54)))
    paths.append(pen.getCommands())
    x += font["hmtx"][name][0] * scale - 41 * 0.035
# Inline the exact vector logo at the same size as the previous documentation lockup.
inner = logo.decode().split(">", 1)[1].rsplit("</svg>", 1)[0]
for theme, color in [("light", "#303A32"), ("dark", "#F2F5EF")]:
    (ASSETS / f"wordmark-{theme}.svg").write_text(
        '<svg xmlns="http://www.w3.org/2000/svg" width="360" height="80" viewBox="0 0 360 80" role="img" aria-labelledby="title">'
        '<title id="title">PocketLog</title>'
        f'<g transform="translate(2 8) scale(.25)">{inner}</g>'
        + ''.join(f'<path fill="{color}" d="{path}"/>' for path in paths)
        + '</svg>\n'
    )
