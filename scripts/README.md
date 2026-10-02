# Logo resources

`update-logo.py` builds all static PinkCollab logos from
`docs/assets/pinkcollab-logo-source.png`, using Pillow and NumPy:

```powershell
python scripts/update-logo.py
```

Replace the source PNG, then run the script from the repository root. The source
is kept unchanged; the script generates Android launcher/transparent resources,
website icons, the README logo, the social card, and the README/website promotional images. It does not touch
`moon_robot.png` or `login_logo.gif`.

`docs/assets/branding/social-card-base.png` is a lossless template without the
robot logo. Logo centers, sizes and the phone icon's rotation are specified in
`promotional_images()`; each run starts from this template so old logo edges
cannot accumulate.

`docs/assets/branding/session-review-source.png` is the approved promotional
artwork, including its mascot and wordmark. The script preserves its composition
and scales it proportionally to at most 1200 pixels per side, producing the same
optimized WebP for the README and website at quality 82 with encoding method 6
(about 63 KiB for the current artwork). Each run starts from the lossless source
to avoid repeated compression. Pages load only the optimized files.
