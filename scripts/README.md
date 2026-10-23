# Logo resources

`update-logo.py` builds all static PinkCollab logos from
`docs/assets/pinkcollab-logo-source.png`, using Pillow:

```powershell
python scripts/update-logo.py
```

The approved source uses the light-gray/white diagonal background. Run the script
from the repository root. The source is kept unchanged; the script generates Android launcher/transparent resources,
website icons, the README logo, the social card, and the README/website promotional
images. Transparent artwork comes from the separate lossless
`docs/assets/branding/mascot-source.png`; do not extract it by background color,
because paper white also appears in the robot's face. Adaptive icons use that
mascot in the central 72dp of a 108dp canvas over a crisp 30° light-gray/white
split. The transparent `moon_robot.png` and `disconnected_robot.png` resources
come from `empty-state-source.png` and `disconnected-source.png` in the branding
directory. Their paper-gray moon/stars and muted rose connection indicator match
the UI while preserving the mascot's cyan details. The transparent animated
`login_logo.gif` already shares that mascot and is kept unchanged.

## Visual identity

Android, the website and documentation use paper gray (`#EDEDEB`), paper white
(`#FAFAF8`), light gray (`#D4D4D0`), charcoal headers (`#343433`) and controls
(`#242424`). Muted rose (`#A33750`) marks small highlights; the mascot retains
its black, white and cyan details. Use flat surfaces, fine neutral outlines,
compact spacing and small readable text rather than colored glow or glass effects.
The 30° gray/white diagonal is the shared logo and promotional background motif.
OMP keeps its own pink/purple/cyan mark and dark promotional panel. Links to OMP
use its purple accent (a darker shade on paper and a lighter shade on dark surfaces).
Do not recolor OMP's brand assets to match PinkCollab's neutral palette.

The artwork was edited with the built-in image generation tool. Its production
brief is: preserve the robot's identity, pose and cyan details; use a light-gray
and white diagonal backdrop; depict compact charcoal headers, paper-white cards,
small type and restrained rose highlights. The session banner, model picker and
usage illustration share that direction. The empty-state edit changes the yellow
moon and stars to paper white/light gray; the disconnected edit changes only the
red Wi-Fi slash to muted rose. Both keep a genuinely transparent background.

## Promotional artwork

`docs/assets/branding/session-review-source.png` is the approved promotional
artwork, including its mascot and wordmark. The script preserves its composition
and scales it proportionally to at most 1200 pixels per side, producing the same
optimized WebP for the README and website at quality 82 with encoding method 6
at a compact download size. Each run starts from the lossless source
to avoid repeated compression. Pages load only the optimized files.

`model-picker-source.png` generates the website's JPEG at quality 86.
`provider-usage-source.png` generates a WebP at quality 84 with encoding method 6
(about 46 KB), replacing the older 68 KB JPEG. Both are limited to 1200 pixels per
side and remain illustrative product views rather than device screenshots.
The Usage artwork follows the supplied current Android screenshot: white Usage
toolbar, thin outlined cards, centered percentage labels on muted green quota
bars, and masked OpenAI Codex, xAI and Workbuddy accounts. Its built-in image
generation brief is to preserve that interface and the 9%/23%/unavailable states
within a front-facing phone on the right over the gray/white diagonal background.
The left side carries the heading “Provider account quotas”, subtitle “Shared
across conversations”, and a compact gallery of OpenAI, Claude, Gemini, Grok,
DeepSeek and Qwen logos. These brand references remain outside the app interface;
they do not imply that every provider reports account quotas. The OpenAI mark
stays black, following its [brand guidance](https://openai.com/brand/).
Update the source when the interface changes; never re-encode an already
compressed website asset.

The social card uses the same artwork, cropped to 1200 × 630 and encoded as an
optimized progressive JPEG at quality 86 to keep smooth gradients and a small file.
To rebuild only the promotional images without touching Android or logo assets:

```sh
python3 scripts/update-logo.py --promotional-only
```

`node .github/actions/check-website/build-social-card.mjs` calls this same generator.
It requires Pillow and does not need a browser installation. Website layout
changes still require desktop/mobile browser review and an updated stylesheet
content hash in both HTML pages; see [development checks](../docs/development.md).

## App demo video

`prepare-demo.py` prepares the supplied 30-second Android screen recording for
the README and website. Keep the original recording outside the repository and
always encode from that original:

```powershell
python scripts/prepare-demo.py "D:/视频/PinkCollab/10月23日.mp4"
```

FFmpeg must be on PATH. The script uses Arial Bold on Windows, DejaVu Sans Bold
on Linux, or Arial Bold on macOS; pass `--font /path/to/bold.ttf` if needed.
It preserves the recording's dimensions, frame rate, duration and audio, using
H.264 at CRF 27 and AAC at 96 kbps. The original 14.4 MB recording becomes a
roughly 1.1 MB MP4 in `website/media/`, with its metadata at the front
(`+faststart`) so playback can start before the full download finishes.

The frame at 28 seconds produces a WebP poster in `website/assets/` and a
1200 × 675 README cover in `docs/assets/`, with the 30° gray/white background
and a real app frame. Pass `--poster-time` to choose another moment. The website
uses native playback controls, `playsinline` and `preload="none"`, without
autoplay. The README cover links to the website player and includes a direct
MP4 link; both surfaces share one video file.
