#!/usr/bin/env python3
"""Generate sage brand assets from docs/assets/logo.svg.

uv run --with pillow --with cairosvg --with fonttools --with brotli scripts/generate-icons.py
"""
import io
from pathlib import Path
import xml.etree.ElementTree as ET

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


def rendered(size, source=logo):
    return cairosvg.svg2png(bytestring=source, output_width=size, output_height=size)


for filename, size in [("icon.png", 1024), ("icon-source.png", 1024), ("favicon.png", 256)]:
    (ASSETS / filename).write_bytes(rendered(size))
(ROOT / "server/web/logo.png").write_bytes(rendered(256))
# Keep the complete rounded artwork for the in-app header.
art = Image.new("RGBA", (1024, 1024), "#6F997E")
art.alpha_composite(Image.open(io.BytesIO(rendered(1024))))
(RES / "drawable-nodpi").mkdir(exist_ok=True)
art.convert("RGB").save(RES / "drawable-nodpi/ic_launcher_art.png", optimize=True)

# Android masks the icon. Keep the gradient full bleed and inset only the mark.
svg_namespace = "http://www.w3.org/2000/svg"
ET.register_namespace("", svg_namespace)
backdrop = ET.fromstring(logo)
for child in list(backdrop):
    if child.tag not in {f"{{{svg_namespace}}}defs", f"{{{svg_namespace}}}rect"}:
        backdrop.remove(child)
backdrop.find(f"{{{svg_namespace}}}rect").attrib.pop("rx", None)

mark = ET.fromstring(logo)
inset = ET.Element(f"{{{svg_namespace}}}g", {"transform": "translate(28.16 28.16) scale(.78)"})
for child in list(mark):
    mark.remove(child)
    if child.tag not in {f"{{{svg_namespace}}}defs", f"{{{svg_namespace}}}rect"}:
        inset.append(child)
mark.append(inset)
for name, layer in [("ic_launcher_backdrop.png", backdrop), ("ic_launcher_mark.png", mark)]:
    (RES / "drawable-nodpi" / name).write_bytes(rendered(1024, ET.tostring(layer)))

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
