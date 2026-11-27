#!/usr/bin/env python3
"""Exercise production recovery policy, boot receiver, jobs and bounded foreground lifecycle."""
import argparse,json,pathlib,subprocess,xml.etree.ElementTree as ET
p=argparse.ArgumentParser();p.add_argument('--jdk',required=True);p.add_argument('--json-jar',required=True);a=p.parse_args()
r=pathlib.Path(__file__).resolve().parents[1];out=r/'build/restoration';out.mkdir(parents=True,exist_ok=True)
base=r/'tests/activation_service/java';files={str(f.relative_to(base)):f for f in (base/'android').rglob('*.java')}
shared=r/'tests/usb_activation/java'
for name in ['android/content/SharedPreferences.java','android/os/SystemClock.java','android/provider/Settings.java']:files[name]=shared/name
files.update({str(f.relative_to(shared)):f for f in (shared/'android/net/wifi').glob('*.java')})
new=r/'tests/restoration/java';files.update({str(f.relative_to(new)):f for f in new.rglob('*.java')})
prod=r/'app/src/main/java/com/igareck/antidpi';src=list(files.values())+[base/'com/igareck/antidpi/UiLanguage.java']+[prod/n for n in ['AutoActivation.java','ServiceRestoration.java','RestorationService.java','RestorationJobService.java','RestorationReceiver.java']]
subprocess.run([a.jdk+'/bin/javac','-encoding','UTF-8','-cp',a.json_jar,'-d',str(out),*map(str,src)],check=True)
run=subprocess.run([a.jdk+'/bin/java','-cp',str(out)+':'+a.json_jar,'com.igareck.antidpi.RestorationHarness'],capture_output=True,text=True,timeout=25)
(out/'stdout.log').write_text(run.stdout);(out/'stderr.log').write_text(run.stderr)
if run.returncode:raise RuntimeError(run.stderr)
v=json.loads(run.stdout.strip().splitlines()[-1]);manifest=ET.parse(r/'app/src/main/AndroidManifest.xml').getroot();ns='{http://schemas.android.com/apk/res/android}'
assert any(x.get(ns+'name')=='android.permission.RECEIVE_BOOT_COMPLETED' for x in manifest.findall('uses-permission'))
recv=next(x for x in manifest.findall('application/receiver') if x.get(ns+'name').endswith('RestorationReceiver'));assert recv.get(ns+'exported')=='false' and recv.get(ns+'directBootAware') is None
job=next(x for x in manifest.findall('application/service') if x.get(ns+'name').endswith('RestorationJobService'));assert job.get(ns+'permission')=='android.permission.BIND_JOB_SERVICE'
fg=next(x for x in manifest.findall('application/service') if x.get(ns+'name').endswith('RestorationService'));assert fg.get(ns+'exported')=='false' and fg.get(ns+'foregroundServiceType')=='specialUse'
v['manifest_checks']=4;(out/'result.json').write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n');print(json.dumps(v,ensure_ascii=False,indent=2))
