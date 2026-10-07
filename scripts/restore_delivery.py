"""Reassemble the signed delivery; verify all parts and the final SHA-256."""
from pathlib import Path
import gzip
import hashlib
import json
import os

root = Path(__file__).resolve().parents[1]
manifest = json.loads((root / 'dist/bootstrap/manifest.json').read_text())
packed = bytearray()
for part in manifest['parts']:
    data = (root / part['path']).read_bytes()
    if len(data) != part['size'] or hashlib.sha256(data).hexdigest() != part['sha256']:
        raise ValueError('Delivery part integrity failure')
    packed.extend(data)
data = gzip.decompress(packed)
if hashlib.sha256(data).hexdigest() != manifest['apk_sha256']:
    raise ValueError('Reassembled APK integrity failure')
final = root / 'dist/CameraProfileStudio-2.1.apk'
temporary = final.with_suffix('.apk.tmp')
temporary.write_bytes(data)
os.replace(temporary, final)
print('Reassembled signed 2.1 APK; SHA-256 verified.')
