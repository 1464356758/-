"""Synthetic color checker only; contains no user photographs."""
from pathlib import Path
from PIL import Image

root = Path(__file__).resolve().parents[1]
path = root / 'tests/android/assets/user.jpg'
path.parent.mkdir(parents=True, exist_ok=True)
image = Image.new('RGB', (1152, 1536))
colors = [(180,35,35),(35,170,55),(35,70,190),(240,210,70),(220,80,170),(65,200,210)]
from PIL import ImageDraw
draw = ImageDraw.Draw(image)
for y in range(12):
 for x in range(9):
  draw.rectangle((x*128,y*128,(x+1)*128-1,(y+1)*128-1),fill=colors[(x+y)%len(colors)])
image.save(path, quality=96)
print('Synthetic fixture',path.name,'1152x1536')

