"""Prints small copies of the screenshots into the job log, where they can be read back without artifacts."""
import base64, io, sys
from PIL import Image

for path in sys.argv[1:]:
    image = Image.open(path).convert('RGB')
    image.thumbnail((720, 720))
    out = io.BytesIO()
    image.save(out, 'JPEG', quality=55)
    text = base64.b64encode(out.getvalue()).decode()
    for i in range(0, len(text), 3000):
        print(f'IMG {path} {text[i:i + 3000]}')
