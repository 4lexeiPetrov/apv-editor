"""Generate macOS-style squircle-masked icons + .icns from a square source PNG.

Run from composeApp/:
    python3 launcher/make_icons.py
"""
from pathlib import Path
import subprocess
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "launcher/source-512.png"  # unmasked square art — source of truth
OUT_ICONS = ROOT / "src/desktopMain/resources/icons"
ICONSET = ROOT / "launcher/AppIcon.iconset"
ICNS = ROOT / "launcher/AppIcon.icns"
ICO = ROOT / "launcher/AppIcon.ico"

# Windows .ico is unmasked square art — Windows does not apply a system squircle
# to app icons the way macOS does, so we skip the superellipse mask here.
ICO_SIZES = [(16, 16), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)]

# macOS Big Sur+ icon geometry: artwork sits inside a squircle that occupies
# ~80% of the canvas; the surrounding ~10% on each side is transparent padding.
ART_FRACTION = 0.80
SUPERELLIPSE_N = 5.0  # Apple's continuous-corner mask is ~n=5 superellipse

def squircle_mask(size: int, n: float = SUPERELLIPSE_N) -> Image.Image:
    """Anti-aliased superellipse mask at 4x supersample."""
    s = size * 4
    mask = Image.new("L", (s, s), 0)
    px = mask.load()
    half = s / 2.0
    for y in range(s):
        ny = abs(y - half + 0.5) / half
        ny_n = ny ** n
        if ny_n >= 1.0:
            continue
        for x in range(s):
            nx = abs(x - half + 0.5) / half
            if nx ** n + ny_n <= 1.0:
                px[x, y] = 255
    return mask.resize((size, size), Image.LANCZOS)

def render(size: int, src: Image.Image, mask_cache: dict) -> Image.Image:
    """Render `src` centered inside a squircle on a transparent `size`x`size` canvas."""
    art_size = int(round(size * ART_FRACTION))
    art = src.resize((art_size, art_size), Image.LANCZOS).convert("RGBA")
    if art_size not in mask_cache:
        mask_cache[art_size] = squircle_mask(art_size)
    art.putalpha(mask_cache[art_size])
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    offset = (size - art_size) // 2
    canvas.paste(art, (offset, offset), art)
    return canvas

def main() -> None:
    src = Image.open(SRC).convert("RGBA")
    cache: dict = {}

    # Runtime PNGs used by the Compose window icon.
    for px in (16, 32, 192, 512):
        render(px, src, cache).save(OUT_ICONS / f"icon-{px}.png", optimize=True)
        print(f"wrote {OUT_ICONS / f'icon-{px}.png'}")

    # Build .iconset for iconutil.
    ICONSET.mkdir(parents=True, exist_ok=True)
    for icon_size, suffix in [
        (16, "16x16"), (32, "16x16@2x"),
        (32, "32x32"), (64, "32x32@2x"),
        (128, "128x128"), (256, "128x128@2x"),
        (256, "256x256"), (512, "256x256@2x"),
        (512, "512x512"),
    ]:
        render(icon_size, src, cache).save(ICONSET / f"icon_{suffix}.png", optimize=True)

    subprocess.run(["iconutil", "-c", "icns", str(ICONSET), "-o", str(ICNS)], check=True)
    print(f"wrote {ICNS}")

    src.save(ICO, format="ICO", sizes=ICO_SIZES)
    print(f"wrote {ICO}")

if __name__ == "__main__":
    main()
