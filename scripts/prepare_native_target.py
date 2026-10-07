"""Verify the delivered APK; change only its test signature for instrumentation.

The CI-generated key is disposable and cannot sign user upgrades. No release
private key is used or stored in CI. Every ZIP entry is compared after signing.
"""
from pathlib import Path
import hashlib
import json
import os
import re
import subprocess
import zipfile

root = Path(__file__).resolve().parents[1]
qa = root / 'qa'
qa.mkdir(exist_ok=True)
bt = Path(os.environ['ANDROID_SDK_ROOT']) / 'build-tools/35.0.0'
manifest = json.loads((root / 'dist/manifest.json').read_text())
golden = root / manifest['apk']['path']
digest = hashlib.sha256(golden.read_bytes()).hexdigest()
if digest != manifest['apk']['sha256']:
    raise ValueError('Delivered APK SHA-256 mismatch')

verification = subprocess.run(
    [str(bt / 'apksigner'), 'verify', '--verbose', '--print-certs', str(golden)],
    check=True, capture_output=True, text=True).stdout
(qa / 'delivery-signature.txt').write_text(verification)
certificate = re.search(r'Signer #1 certificate SHA-256 digest: ([0-9a-f]+)', verification)
if certificate is None or certificate[1] != manifest['certificate_sha256']:
    raise ValueError('Delivered APK signing certificate mismatch')
subprocess.run([str(bt / 'zipalign'), '-c', '-P', '16', '4', str(golden)], check=True)

target = qa / 'native-target.apk'
target.unlink(missing_ok=True)
subprocess.run([
    str(bt / 'apksigner'), 'sign', '--ks', str(root / 'app/build/release.jks'),
    '--ks-pass', 'pass:camera-profile-local', '--out', str(target), str(golden)
], check=True)
subprocess.run([str(bt / 'apksigner'), 'verify', str(target)], check=True)

def payload(path):
    with zipfile.ZipFile(path) as archive:
        if archive.testzip() is not None:
            raise ValueError('APK ZIP CRC failure')
        return {name: hashlib.sha256(archive.read(name)).hexdigest()
                for name in archive.namelist() if not name.startswith('META-INF/')}

if payload(golden) != payload(target):
    raise ValueError('Test signing changed application payload')
test = root / 'tests/android/RuntimeTests.apk'
record = {
    'delivered_apk_sha256': digest,
    'delivered_certificate_sha256': certificate[1],
    'test_signature_only': True,
    'application_payload_unchanged': True,
    'files': [{'path': str(path.relative_to(root)),
               'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}
              for path in (target, test)]
}
(qa / 'native-target-manifest.json').write_text(json.dumps(record, indent=2))
print('Verified delivered APK; native test package uses identical application payload.', flush=True)
