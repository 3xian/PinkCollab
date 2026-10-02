# Logo resources

`update-logo.py` builds all static PinkCollab logos from
`docs/assets/pinkcollab-logo-source.png`, using Pillow and NumPy:

```powershell
python scripts/update-logo.py
```

Replace the source PNG, then run the script from the repository root. The source
is kept unchanged; the script generates Android launcher/transparent resources,
website icons, the README logo, and both promotional images. It does not touch
`moon_robot.png` or `login_logo.gif`.

`docs/assets/branding/social-card-base.png` and `readme-base.png` are lossless
templates without the robot logo. The README template retains its rounded purple
badge background and photo. Logo centers, sizes and the phone icon's rotation are
specified in `promotional_images()`; each run starts from these templates so old
logo edges and repeated JPEG compression cannot accumulate. Edit these templates
when changing promotional layouts.
