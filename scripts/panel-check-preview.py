"""Legacy themes must remain pixel-identical after retained rendering changes."""
from pathlib import Path
for theme in range(8):
    for screen in range(6):
        name=f"theme-{theme}-screen-{screen}.ppm"
        old=Path("build/reference")/name
        new=Path("build/preview")/name
        assert old.read_bytes()==new.read_bytes(), f"Legacy UI changed: {name}"
print("All 48 legacy theme/screen images match the reference")
