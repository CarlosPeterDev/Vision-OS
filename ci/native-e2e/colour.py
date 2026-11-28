"""Share of pure red or green pixels in the middle of a screenshot."""
import sys
from PIL import Image

image = Image.open(sys.argv[1]).convert('RGB')
w, h = image.size
middle = image.crop((int(w * 0.40), int(h * 0.35), int(w * 0.60), int(h * 0.65)))
pixels = list(middle.getdata())
if sys.argv[2] == 'red':
    hits = sum(1 for r, g, b in pixels if r > 190 and g < 70 and b < 70)
else:
    hits = sum(1 for r, g, b in pixels if g > 190 and r < 70 and b < 70)
share = hits / len(pixels)
print(f'{sys.argv[2]} share {share:.3f}')
sys.exit(0 if share > 0.30 else 1)
