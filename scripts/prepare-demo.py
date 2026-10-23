"""Prepare the screen recording and README poster (requires FFmpeg and Pillow)."""

import argparse
from io import BytesIO
from pathlib import Path
import shutil
import subprocess

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parent.parent


def run(*args):
    subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", *args], check=True)


def build_cover(recording: Path, timestamp: str, font: Path, output: Path):
    frame = subprocess.run(
        ["ffmpeg", "-hide_banner", "-loglevel", "error", "-ss", timestamp,
         "-i", str(recording), "-frames:v", "1", "-f", "image2pipe", "-c:v", "png", "-"],
        check=True, capture_output=True,
    )
    with Image.open(BytesIO(frame.stdout)) as image:
        screen = image.convert("RGB")
    # This recording is 720 x 1558. Remove Android's system bars, then let
    # the device extend beyond the poster's bottom edge without shrinking text.
    if screen.size != (720, 1558):
        raise ValueError("The poster crop requires a 720 x 1558 screen recording")
    screen = screen.crop((0, 82, 720, 1474))

    canvas = Image.new("RGB", (1600, 1120), "#FAFAF8")
    draw = ImageDraw.Draw(canvas)
    draw.polygon([(0, 0), (1600, 0), (1600, 80), (0, 1004)], fill="#EDEDEB")

    def text(position, value, size, color="#242424", spacing=8):
        draw.multiline_text(position, value, font=ImageFont.truetype(str(font), size),
                            fill=color, spacing=spacing)

    with Image.open(ROOT / "docs/assets/branding/mascot-source.png") as image:
        mascot = image.convert("RGBA")
    mascot.thumbnail((112, 112), Image.Resampling.LANCZOS)
    canvas.paste(mascot, (64, 60), mascot)
    text((188, 94), "PinkCollab", 38)
    text((72, 258), "OMP, WHEREVER YOU ARE.", 20, "#60605D")
    text((64, 324), "Your OMP.\nAnywhere.", 112, spacing=0)
    draw.rectangle((72, 628, 136, 633), fill="#A33750")
    text((72, 676), "Pick up the conversation.\nKeep the work moving.", 30, "#60605D", spacing=16)

    for left, right, label in ((72, 263, "ANDROID APP"), (279, 472, "SELF-HOSTED")):
        draw.rounded_rectangle((left, 916, right, 962), radius=23, outline="#B9B9B4", width=1)
        text((left + 22, 930), label, 18)
    text((72, 1020), "Powered by Oh My Pi", 22, "#60605D")

    # A restrained device frame and shadow give the real screenshot presence.
    # Its upper corners are rounded; the lower portion continues off-canvas.
    shadow = Image.new("RGBA", canvas.size)
    ImageDraw.Draw(shadow).rounded_rectangle((806, 114, 1562, 1542), radius=48,
                                            fill=(36, 36, 36, 52))
    canvas = Image.alpha_composite(canvas.convert("RGBA"), shadow.filter(ImageFilter.GaussianBlur(20)))
    phone = Image.new("RGBA", (744, 1416))
    ImageDraw.Draw(phone).rounded_rectangle((0, 0, 743, 1415), radius=42, fill="#343433")
    mask = Image.new("L", screen.size)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, 719, 1391), radius=30, fill=255)
    phone.paste(screen, (12, 12), mask)
    canvas.alpha_composite(phone, (808, 92))
    canvas.convert("RGB").save(output, lossless=True, method=6)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path, help="Original screen recording; never re-encode the web copy")
    parser.add_argument("--poster-time", default="28", help="Poster frame timestamp in seconds")
    parser.add_argument("--cover-only", action="store_true", help="Rebuild only the README cover")
    parser.add_argument("--font", type=Path, help="Bold TrueType font for the README cover")
    args = parser.parse_args()
    if not args.input.is_file():
        parser.error("Input recording does not exist")
    if not shutil.which("ffmpeg"):
        parser.error("FFmpeg must be on PATH")
    fonts = [args.font] if args.font else [
        Path("C:/Windows/Fonts/arialbd.ttf"),
        Path("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"),
        Path("/System/Library/Fonts/Supplemental/Arial Bold.ttf"),
    ]
    font = next((path for path in fonts if path.is_file()), None)
    if font is None:
        parser.error("Pass --font with a bold TrueType font")

    assets = ROOT / "website/assets"
    media = ROOT / "website/media"
    media.mkdir(exist_ok=True)
    video = media / "pinkcollab-demo.mp4"
    if args.input.resolve() == video.resolve():
        parser.error("Use the original recording, not the published web copy")
    poster = assets / "pinkcollab-demo-poster.webp"
    if not args.cover_only:
        run("-i", str(args.input), "-map", "0:v:0", "-map", "0:a?",
            "-c:v", "libx264", "-preset", "slow", "-crf", "27", "-pix_fmt", "yuv420p",
            "-c:a", "aac", "-b:a", "96k", "-movflags", "+faststart", "-map_metadata", "-1", str(video))
        run("-ss", args.poster_time, "-i", str(args.input), "-frames:v", "1",
            "-c:v", "libwebp", "-quality", "82", "-compression_level", "6", "-update", "1", str(poster))

    cover = ROOT / "docs/assets/pinkcollab-demo.webp"
    build_cover(args.input, args.poster_time, font, cover)
    for path in ((cover,) if args.cover_only else (video, poster, cover)):
        print(f"{path.relative_to(ROOT)}: {path.stat().st_size:,} bytes")


if __name__ == "__main__":
    main()
