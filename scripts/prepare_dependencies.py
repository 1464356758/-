"""Download pinned public upstream dependencies; never handles signing secrets."""
from pathlib import Path
import hashlib, io, json, urllib.request, zipfile

root = Path(__file__).resolve().parents[1]
sources = json.loads((root / 'dependency-source.json').read_text())
sources.append({
 'id':'ESRGAN',
 'url':'https://storage.googleapis.com/download.tensorflow.org/models/tflite/esrgan/ESRGAN.tflite',
 'sha256':'1a380d3744103e11ef343534aaff54815cae40769dcd00c023652a7e5bc47f4b'
})
for source in sources:
 with urllib.request.urlopen(source['url'], timeout=90) as response:
  data = response.read(32 * 1024 * 1024 + 1)
 if len(data) > 32 * 1024 * 1024: raise ValueError('Dependency too large')
 if hashlib.sha256(data).hexdigest() != source['sha256']:
  raise ValueError('Dependency SHA-256 mismatch: ' + source['id'])
 if source['id'] == 'ESRGAN':
  (root / 'app/assets/ESRGAN.tflite').write_bytes(data)
 else:
  folder = root / 'app/vendor' / source['id']
  folder.mkdir(parents=True, exist_ok=True)
  with zipfile.ZipFile(io.BytesIO(data)) as archive:
   if archive.testzip(): raise ValueError('Dependency ZIP CRC failure')
   for name in archive.namelist():
    if name == 'classes.jar' or (name.startswith('jni/') and name.endswith('.so')):
     path = folder / name
     path.parent.mkdir(parents=True, exist_ok=True)
     path.write_bytes(archive.read(name))
 print('VERIFIED', source['id'], source['sha256'], flush=True)

