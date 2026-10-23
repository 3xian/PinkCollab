"""Build the approved neutral brand assets; requires Pillow.

Run from the repository root: python scripts/update-logo.py
All inputs are resolved relative to this script, including promotional artwork.
Repeated runs rebuild outputs from those inputs, never from prior outputs.
"""

import argparse
import math
from pathlib import Path

from PIL import Image, ImageDraw, ImageOps

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "docs/assets/pinkcollab-logo-source.png"
RES = ROOT / "android/app/src/main/res"
TEMPLATES = ROOT / "docs/assets/branding"
LANCZOS = Image.Resampling.LANCZOS
PAPER = "#FAFAF8"
MIST = "#D4D4D0"


def masked_tile(source: Image.Image, size: int, radius: float) -> Image.Image:
    tile = source.convert("RGBA").resize((size, size), LANCZOS)
    mask = Image.new("L", (size * 4, size * 4))
    ImageDraw.Draw(mask).rounded_rectangle(
        (0, 0, size * 4 - 1, size * 4 - 1), radius=radius * size * 4, fill=255
    )
    tile.putalpha(mask.resize((size, size), LANCZOS))
    return tile


def save(image: Image.Image, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path, optimize=True)


def promotional_images() -> None:
    # The approved artwork already includes its mascot and wordmark.
    with Image.open(TEMPLATES / "session-review-source.png") as artwork:
        photo = artwork.convert("RGB")
    # JPEG preserves smooth gradients in the 1.91:1 social crop at a small size.
    card = ImageOps.fit(photo, (1200, 630), LANCZOS)
    card.save(
        ROOT / "website/assets/social-card.jpg",
        quality=86, subsampling=0, optimize=True, progressive=True,
    )
    photo.thumbnail((1200, 1200), LANCZOS)
    photo.save(ROOT / "docs/assets/pinkcollab-readme.webp", quality=82, method=6)
    # Both surfaces use the same optimized encoding.
    (ROOT / "website/assets/pinkcollab-session-review.webp").write_bytes(
        (ROOT / "docs/assets/pinkcollab-readme.webp").read_bytes()
    )
    for name, extension, options in (
        ("model-picker", "jpg", dict(quality=86, subsampling=0, optimize=True, progressive=True)),
        ("provider-usage", "webp", dict(quality=84, method=6)),
    ):
        with Image.open(TEMPLATES / f"{name}-source.png") as artwork:
            photo = artwork.convert("RGB")
        photo.thumbnail((1200, 1200), LANCZOS)
        photo.save(ROOT / f"website/assets/pinkcollab-{name}.{extension}", **options)


def launcher_background(size: int) -> Image.Image:
    # Render the 30-degree split directly. Interpolating corner colors would
    # turn the approved crisp two-tone backdrop into a gradient.
    edge = size * 4
    background = Image.new("RGB", (edge, edge), PAPER)
    rise = math.tan(math.radians(30)) * edge / 2
    ImageDraw.Draw(background).polygon(
        [(0, 0), (edge, 0), (edge, edge / 2 - rise), (0, edge / 2 + rise)],
        fill=MIST,
    )
    return background.resize((size, size), LANCZOS)


def main() -> None:
    source = Image.open(SOURCE).convert("RGB")
    save(masked_tile(source, 512, 0.22), ROOT / "docs/assets/pinkcollab-logo.png")
    for name, size, radius in (
        ("logo-96", 96, 0.22), ("logo-144", 144, 0.22),
        ("favicon-32", 32, 0.22), ("favicon-48", 48, 0.22),
        ("apple-touch-icon", 180, 0),
    ):
        # Indexed PNGs keep the small website icons within its page-size budget.
        tile = masked_tile(source, size, radius).quantize(
            colors=256, method=Image.Quantize.FASTOCTREE
        )
        save(tile, ROOT / f"website/assets/{name}.png")
    for density, size in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)):
        save(masked_tile(source, size, 0.22), RES / f"mipmap-{density}/ic_launcher.png")
        save(masked_tile(source, size, 0.5), RES / f"mipmap-{density}/ic_launcher_round.png")

    # Keep a separate lossless transparent master. Background-color keying
    # cannot separate the mascot's white face from the new paper backdrop.
    with Image.open(TEMPLATES / "mascot-source.png") as artwork:
        character = artwork.convert("RGBA")
    save(character.resize((512, 512), LANCZOS), RES / "drawable-nodpi/pinkcollab_logo.png")

    # Adaptive icons use a 108dp canvas. Keep the complete artwork in the
    # central 72dp so launcher masks have room without cutting off the flames.
    foreground = Image.new("RGBA", (432, 432))
    foreground.alpha_composite(character.resize((288, 288), LANCZOS), (72, 72))
    save(foreground, RES / "drawable-nodpi/ic_launcher_foreground.png")
    save(launcher_background(432), RES / "drawable-nodpi/ic_launcher_background.png")
    for master, resource, size in (
        ("empty-state", "moon_robot", (500, 500)),
        ("disconnected", "disconnected_robot", (800, 614)),
    ):
        with Image.open(TEMPLATES / f"{master}-source.png") as artwork:
            illustration = ImageOps.contain(artwork.convert("RGBA"), size, LANCZOS)
        canvas = Image.new("RGBA", size)
        canvas.alpha_composite(illustration, ((size[0] - illustration.width) // 2,
                                              (size[1] - illustration.height) // 2))
        save(canvas, RES / f"drawable-nodpi/{resource}.png")
    promotional_images()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--promotional-only", action="store_true", help="Rebuild README and website promotional images only")
    args = parser.parse_args()
    if args.promotional_only:
        promotional_images()
    else:
        main()
