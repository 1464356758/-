"""KVM Android integration runs, with persisted evidence and strict result codes."""
from pathlib import Path
import hashlib, json, os, subprocess, time

root = Path(__file__).resolve().parents[1]
qa = root / 'qa'
qa.mkdir(exist_ok=True)
sdk = Path(os.environ['ANDROID_SDK_ROOT'])
adb = str(sdk / 'platform-tools/adb')
emu = str(sdk / 'emulator/emulator')
env = os.environ.copy()
avd_home = Path(os.environ.get('RUNNER_TEMP', '/tmp')) / 'camera-avd'
avd_home.mkdir(exist_ok=True)
env['ANDROID_AVD_HOME'] = str(avd_home)
avd = avd_home / 'Studio.avd'
avd.mkdir(exist_ok=True)
(avd_home / 'Studio.ini').write_text('avd.ini.encoding=UTF-8\npath='+str(avd)+'\ntarget=android-34\n')
(avd / 'config.ini').write_text('''avd.ini.encoding=UTF-8
avd.name=Studio
hw.cpu.arch=x86_64
hw.cpu.ncore=4
hw.ramSize=2048
vm.heapSize=512
hw.lcd.width=480
hw.lcd.height=800
hw.lcd.density=160
hw.keyboard=yes
hw.gpu.enabled=yes
hw.gpu.mode=swiftshader_indirect
hw.audioInput=no
hw.audioOutput=no
hw.camera.back=none
hw.camera.front=none
disk.dataPartition.size=2048M
image.sysdir.1=system-images/android-34/default/x86_64/
abi.type=x86_64
tag.id=default
''')

def command(args, timeout=90, required=True):
 result = subprocess.run([adb, '-s', 'emulator-5554'] + args, env=env,
                         capture_output=True, text=True, timeout=timeout)
 if required and result.returncode != 0:
  raise RuntimeError('ADB command failed: '+str(args)+'\n'+result.stdout+result.stderr)
 return result

def sha(path):
 h=hashlib.sha256()
 with path.open('rb') as f:
  while block:=f.read(1024*1024):h.update(block)
 return h.hexdigest()

summary = {'environment':{'android_api':34,'architecture':'x86_64','acceleration':'KVM'},'tests':[]}
emulator = None
with (qa / 'emulator.log').open('w') as log:
 try:
  if not Path('/dev/kvm').exists(): raise RuntimeError('KVM unavailable; do not replace native checks with a simulation')
  subprocess.run([adb,'start-server'],env=env,check=True,timeout=30)
  emulator=subprocess.Popen([emu,'-avd','Studio','-no-window','-no-audio','-no-snapshot','-no-boot-anim','-accel','on','-gpu','swiftshader_indirect','-feature','-Vulkan','-port','5554'],env=env,stdout=log,stderr=subprocess.STDOUT)
  start=time.monotonic()
  while time.monotonic()-start<300:
   if emulator.poll() is not None:raise RuntimeError('Android emulator exited during boot')
   try:
    if command(['shell','getprop','sys.boot_completed'],15,False).stdout.strip()=='1':break
   except subprocess.TimeoutExpired:pass
   time.sleep(2)
  else:raise TimeoutError('Android boot did not complete')
  summary['environment']['boot_seconds']=round(time.monotonic()-start,2)
  # Golden pair is signed with the actual delivered APK certificate. No private key is present.
  manifest=json.loads((root/'dist/manifest.json').read_text())
  for item in manifest['files']:
   path=root/item['path']
   if sha(path)!=item['sha256']:raise ValueError('Golden artifact hash mismatch: '+item['path'])
   print(command(['install','--no-streaming','-r',str(path)],180).stdout,flush=True)
  command(['shell','pm','grant','com.cameraprofile.studio','android.permission.POST_NOTIFICATIONS'])
  command(['shell','svc','bluetooth','disable'],30,False)
  for key in ['window_animation_scale','transition_animation_scale','animator_duration_scale']:
   command(['shell','settings','put','global',key,'0'])
  command(['shell','input','keyevent','224'])
  command(['shell','input','keyevent','82'])
  for name, extra, limit in [('SmokeRunner',[],480),('PickerRunner',[],300),('BackgroundRunner',['-e','tileSide','20'],1200)]:
   command(['shell','am','force-stop','com.cameraprofile.studio'])
   command(['shell','input','keyevent','224'])
   command(['shell','input','keyevent','82'])
   began=time.monotonic()
   result=command(['shell','am','instrument','-w']+extra+['com.cameraprofile.studio.tests/.'+name],limit,False)
   text=result.stdout+result.stderr
   (qa/(name+'.log')).write_text(text)
   passed=result.returncode==0 and 'INSTRUMENTATION_CODE: -1' in text and 'INSTRUMENTATION_FAILED' not in text
   entry={'runner':name,'passed':passed,'seconds':round(time.monotonic()-began,2),'adb_exit_code':result.returncode,'log':name+'.log'}
   summary['tests'].append(entry)
   (qa/'native-summary.json').write_text(json.dumps(summary,indent=2))
   print(text,flush=True)
   if not passed:raise AssertionError('Native checks failed: '+name)
  command(['pull','/sdcard/Android/data/com.cameraprofile.studio/files/',str(qa/'device-output')],90,False)
  summary['apk_sha256']=manifest['files'][0]['sha256']
  summary['all_passed']=True
 finally:
  summary.setdefault('all_passed',False)
  (qa/'native-summary.json').write_text(json.dumps(summary,indent=2))
  if emulator and emulator.poll() is None:
   emulator.terminate()
   try:emulator.wait(timeout=15)
   except subprocess.TimeoutExpired:emulator.kill()

