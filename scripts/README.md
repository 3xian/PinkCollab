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

`model-picker-source.png` and `provider-usage-source.png` in the same directory
generate the website's corresponding JPEGs, also limited to 1200 pixels per side
at quality 86. These are illustrative product views with sample content, not
device screenshots. Update their source files together with session artwork when
changing the visual style; never re-encode an already compressed website asset.

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
