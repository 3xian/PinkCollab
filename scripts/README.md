# Logo resources

`update-logo.py` builds all static PinkCollab logos from
`docs/assets/pinkcollab-logo-source.png`, using Pillow and NumPy:

```powershell
python scripts/update-logo.py
```

Replace the source PNG, then run the script from the repository root. The source
is kept unchanged; the script generates Android launcher/transparent resources,
website icons, the README logo, the social card, and the README/website promotional
images. It does not touch `moon_robot.png` or `login_logo.gif`.

`docs/assets/branding/session-review-source.png` is the approved promotional
artwork, including its mascot and wordmark. The script preserves its composition
and scales it proportionally to at most 1200 pixels per side, producing the same
optimized WebP for the README and website at quality 82 with encoding method 6
(about 63 KiB for the current artwork). Each run starts from the lossless source
to avoid repeated compression. Pages load only the optimized files.

The social card uses the same artwork, cropped to 1200 × 630 and encoded as an
optimized progressive JPEG at quality 86 to keep smooth gradients and a small file.
To rebuild only the promotional images without touching Android or logo assets:

```sh
python3 scripts/update-logo.py --promotional-only
```

`node .github/actions/check-website/build-social-card.mjs` calls this same generator.
It requires Pillow and NumPy, and does not need a browser installation.
