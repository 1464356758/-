"""Prepare the freshly compiled production APK for native tests; CI key is disposable."""
from pathlib import Path
import hashlib, json, os, shutil, subprocess, zipfile
root = Path(__file__).resolve().parents[1]
qa = root / 'qa'; qa.mkdir(exist_ok=True)
bt = Path(os.environ['ANDROID_SDK_ROOT']) / 'build-tools/35.0.0'
built = root / 'app/CameraProfileStudio-2.2.apk'
verification = subprocess.run([str(bt/'apksigner'),'verify','--verbose','--print-certs',str(built)],check=True,capture_output=True,text=True).stdout
(qa/'test-signature.txt').write_text(verification)
subprocess.run([str(bt/'zipalign'),'-c','-P','16','4',str(built)],check=True)
def payload(path):
 with zipfile.ZipFile(path) as z:
  if z.testzip() is not None: raise ValueError('APK CRC failure')
  return {n:hashlib.sha256(z.read(n)).hexdigest() for n in z.namelist() if not n.startswith('META-INF/') and not n.endswith('/')}
unsigned = root/'app/build/aligned.apk'
if payload(unsigned)!=payload(built): raise ValueError('CI signing changed application content')
target=qa/'native-target.apk'; shutil.copyfile(built,target)
test=root/'tests/android/RuntimeTests.apk'
record={'tested_apk_sha256':hashlib.sha256(built.read_bytes()).hexdigest(),
 'source_commit':os.environ.get('GITHUB_SHA','local'), 'test_signature_only':True,
 'application_payload_unchanged':True,'application_payload':payload(unsigned),
 'files':[{'path':str(p.relative_to(root)),'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} for p in (target,test)]}
(qa/'native-target-manifest.json').write_text(json.dumps(record,indent=2))
folder=root/'delivery-build'; folder.mkdir(exist_ok=True)
shutil.copyfile(unsigned,folder/'CameraProfileStudio-2.2-unsigned.apk')
shutil.copyfile(bt/'lib/apksigner.jar',folder/'apksigner.jar')
record['signing_tool_sha256']=hashlib.sha256((folder/'apksigner.jar').read_bytes()).hexdigest()
(folder/'manifest.json').write_text(json.dumps(record,indent=2))
print('Fresh 2.2 source built, aligned and tested with disposable CI signature.')
