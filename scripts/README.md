# Asset maintenance

Run these scripts from the repository root. Both need Python and Pillow; the demo script also requires FFmpeg on PATH. Keep lossless artwork and original recordings as the sources, rather than re-encoding published assets.

## Logo resources

```sh
python scripts/update-logo.py
```

The script builds Android launcher/transparent resources, website icons, the README logo and promotional images. It preserves the source artwork and the existing animated `login_logo.gif`.

| Source | Outputs / purpose |
| --- | --- |
| `docs/assets/pinkcollab-logo-source.png` | Static logos and website icons with the gray/white diagonal background |
| `docs/assets/branding/mascot-source.png` | Transparent mascot and adaptive-icon foreground |
| `docs/assets/branding/empty-state-source.png` | `moon_robot.png` |
| `docs/assets/branding/disconnected-source.png` | `disconnected_robot.png` |

Do not remove the background by color: the mascot's face also contains white. Adaptive icons place the transparent mascot in the central 72dp of a 108dp canvas over a crisp 30° gray/white split.

## Visual identity

Use paper gray (`#EDEDEB`), paper white (`#FAFAF8`), light gray (`#D4D4D0`), charcoal headers (`#343433`) and controls (`#242424`). Muted rose (`#A33750`) marks small highlights; the mascot keeps its black, white and cyan details. Use flat surfaces, fine outlines, compact spacing and readable type. The 30° diagonal is the shared logo and promotional background.

OMP retains its own pink/purple/cyan mark and dark promotional panel. Do not recolor third-party brand assets to match PinkCollab. UI behavior and typography belong in the [session presentation contract](../docs/session-presentation.md).

## Promotional artwork

```sh
python scripts/update-logo.py --promotional-only
```

This rebuilds only the following images from `docs/assets/branding/`:

| Source | Generated asset | Encoding |
| --- | --- | --- |
| `session-review-source.png` | `website/assets/pinkcollab-session-review.webp` and identical `docs/assets/pinkcollab-readme.webp` | At most 1200px per side; WebP quality 82, method 6 |
| `session-review-source.png` | `website/assets/social-card.jpg` | 1200 × 630 crop; progressive JPEG quality 86 |
| `model-picker-source.png` | `website/assets/pinkcollab-model-picker.jpg` | At most 1200px per side; JPEG quality 86 |
| `provider-usage-source.png` | `website/assets/pinkcollab-provider-usage.webp` | At most 1200px per side; WebP quality 84, method 6 |

These are illustrations, not device screenshots. The current README cover comes from `prepare-demo.py`, not `pinkcollab-readme.webp`. Update the lossless source when the interface changes. The Usage artwork's provider-logo gallery is outside the illustrated app interface and does not imply every provider reports quotas.

`node .github/actions/check-website/build-social-card.mjs` invokes the same promotional generator; it needs Pillow, not a browser. Website layout and stylesheet-hash checks are in [Development](../docs/development.md#website-checks).

## App demo video

Keep the original recording outside the repository and pass its path:

```sh
python scripts/prepare-demo.py /path/to/original-recording.mp4
```

The cover crop requires a **720 × 1558** recording. The default poster frame is at 28 seconds; use `--poster-time <seconds>` for another moment. The script finds Arial Bold on Windows/macOS or DejaVu Sans Bold on Linux; use `--font /path/to/bold.ttf` if needed.

| Output | Behavior |
| --- | --- |
| `website/media/pinkcollab-demo.mp4` | H.264 CRF 27, optional source audio encoded as AAC 96 kbps, and `+faststart` metadata |
| `website/assets/pinkcollab-demo-poster.webp` | Selected frame, WebP quality 82 |
| `docs/assets/pinkcollab-demo.webp` | 1600 × 1120 lossless cover with mascot, headline and real app screen; removes system bars and preserves the screenshot's 720px width |

To rebuild only the README cover:

```sh
python scripts/prepare-demo.py /path/to/original-recording.mp4 --cover-only
```

The website uses native controls, `playsinline` and `preload="none"`, without autoplay. The README links to the website player and the same MP4; its cover links to the full-resolution image.
