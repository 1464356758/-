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
image.resize((120,80)).save(root / 'tests/input.jpg', quality=96)
small = image.resize((120,80))
progressive = root / 'tests/progressive.jpg'
small.save(progressive, quality=96, progressive=True)
data = progressive.read_bytes()
# Place metadata after the first entropy scan, before subsequent scans.
sos = data.index(b'\xff\xda')
length = int.from_bytes(data[sos+2:sos+4], 'big')
boundary = sos+2+length
while boundary < len(data)-1:
    if data[boundary] == 255 and data[boundary+1] not in (0,255) and not 208 <= data[boundary+1] <= 215:
        break
    boundary += 1
if boundary >= len(data)-1:
    raise ValueError('Progressive fixture has no inter-scan boundary')
def segment(marker, payload):
    return bytes((255,marker)) + (len(payload)+2).to_bytes(2,'big') + payload
metadata = segment(225,b'http://ns.adobe.com/xap/1.0/\0<synthetic-test/>')
(root / 'tests/progressive-late-metadata.jpg').write_bytes(data[:boundary]+metadata+data[boundary:])
credential = segment(235,b'c2pa')
(root / 'tests/late-credential.jpg').write_bytes(data[:boundary]+credential+data[boundary:])
(root / 'tests/truncated.jpg').write_bytes(data[:-20])
print('Synthetic progressive, inter-scan metadata, credential and truncation fixtures created.')
