"""Prepare the supplied screen recording for the website and README (requires FFmpeg)."""

import argparse
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parent.parent


def run(*args):
    subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", *args], check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path, help="Original screen recording; never re-encode the web copy")
    parser.add_argument("--poster-time", default="28", help="Poster frame timestamp in seconds")
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
    run("-i", str(args.input), "-map", "0:v:0", "-map", "0:a?",
        "-c:v", "libx264", "-preset", "slow", "-crf", "27", "-pix_fmt", "yuv420p",
        "-c:a", "aac", "-b:a", "96k", "-movflags", "+faststart", "-map_metadata", "-1", str(video))
    poster = assets / "pinkcollab-demo-poster.webp"
    run("-ss", args.poster_time, "-i", str(args.input), "-frames:v", "1",
        "-c:v", "libwebp", "-quality", "82", "-compression_level", "6", "-update", "1", str(poster))

    # A real frame on the same 30-degree paper-gray/white split as the site.
    # FFmpeg filter paths need their own escaping, independently of shell quoting.
    font_path = font.resolve().as_posix().replace(":", "\\:").replace("'", "'\\''")

    def text(value, size, y, color="0x242423", x=72):
        return f"drawtext=fontfile='{font_path}':text='{value}':fontsize={size}:fontcolor={color}:x={x}:y={y}"

    filters = (
        "color=c=0xEDEDEB:s=1200x675,format=gbrp,"
        "geq=r='if(lt(X+Y*0.57735,1000),237,250)':"
        "g='if(lt(X+Y*0.57735,1000),237,250)':"
        "b='if(lt(X+Y*0.57735,1000),235,248)'[paper];"
        "[0:v]scale=280:-2[screen];[1:v]scale=64:64[logo];"
        "[paper]drawbox=x=854:y=40:w=282:h=608:color=0xD4D4D0:t=fill[background];"
        "[background][screen]overlay=848:(H-h)/2[shot];"
        "[shot][logo]overlay=72:128,"
        + text("PinkCollab in action.", 44, 235) + ","
        + text("A session, from prompt to result.", 20, 306, "0x60605D") + ","
        "drawbox=x=72:y=446:w=360:h=48:color=0x242424:t=fill,"
        + text("WATCH THE 30-SECOND DEMO  >", 16, 462, "0xFAFAF8", x=92)
    )
    cover = ROOT / "docs/assets/pinkcollab-demo.webp"
    run("-ss", args.poster_time, "-i", str(args.input), "-i", str(assets / "logo-96.png"), "-filter_complex", filters,
        "-frames:v", "1", "-c:v", "libwebp", "-quality", "82", "-compression_level", "6",
        "-update", "1", str(cover))
    for path in (video, poster, cover):
        print(f"{path.relative_to(ROOT)}: {path.stat().st_size:,} bytes")


if __name__ == "__main__":
    main()
