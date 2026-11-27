"""Verify the TAP and tethering service caller identity against framework fixtures."""
from pathlib import Path
import argparse,json,subprocess,hashlib,re,xml.etree.ElementTree as ET
p=argparse.ArgumentParser();p.add_argument('--jdk',type=Path,required=True);p.add_argument('--android-jar',type=Path,required=True);p.add_argument('--json-jar',type=Path,required=True);p.add_argument('--baseline-root',type=Path);a=p.parse_args()
root=Path(__file__).resolve().parents[1];out=root/'build/caller-identity';out.mkdir(parents=True,exist_ok=True)
fixtures=root/'tests/lifecycle/java';production=root/'app/src/main/java/com/igareck/antidpi'
# Binder identity is a per-thread Android boundary. Both APK UIDs differ from 2000.
binder=(fixtures/'android/os/Binder.java').read_text().replace('public static int getCallingUid(){return 2000;} public static long clearCallingIdentity(){return 0;} public static void restoreCallingIdentity(long n){}','public static final ThreadLocal<Integer> CALLER=ThreadLocal.withInitial(()->2000); public static void setCallingUid(int uid){CALLER.set(uid);} public static int getCallingUid(){return CALLER.get();} public static long clearCallingIdentity(){int previous=getCallingUid();CALLER.set(2000);return previous;} public static void restoreCallingIdentity(long n){CALLER.set((int)n);}')
assert 'ThreadLocal' in binder
replacement=out/'Binder.java';replacement.write_text(binder)
cp=str(a.json_jar.resolve())+':'+str(a.android_jar.resolve());result={}
for tag,path in ([('baseline',a.baseline_root.resolve())] if a.baseline_root else [])+[('fixed',root)]:
 src=path/'app/src/main/java/com/igareck/antidpi';classes=out/tag;classes.mkdir(exist_ok=True)
 java=[f for f in fixtures.rglob('*.java') if f.relative_to(fixtures).parts[0]=='android' and f.name!='Binder.java']+[replacement]
 java += list((root/'tests/caller/java').rglob('*.java'))+list((root/'tests/tap/java').rglob('*.java'))
 java += [fixtures/'com/igareck/antidpi'/n for n in ['AppLog.java','ITransportService.java','PacketControl.java']]
 java += [src/n for n in ['NativePacketService.java','TapSession.java','PacketBuilder.java','Catalogue.java','PacketCommand.java','QuicProfiles.java','PacketRecovery.java']]
 if (src/'StopVerifier.java').is_file():java += [src/'StopVerifier.java',src/'WorkerChannel.java']
 java += [production/'AppIdentity.java',production/'DiscordProfiles.java',production/'TcpIds.java']
 subprocess.run([str(a.jdk/'bin/javac'),'-encoding','UTF-8','-source','17','-target','17','-cp',cp,'-d',str(classes),*map(str,java)],check=True)
 run=subprocess.run([str(a.jdk/'bin/java'),'-cp',str(classes)+':'+cp,'com.igareck.antidpi.CallerIdentityHarness',tag],capture_output=True,text=True,timeout=20)
 (out/(tag+'.stdout')).write_text(run.stdout);(out/(tag+'.stderr')).write_text(run.stderr)
 if run.returncode:raise RuntimeError(tag+': '+run.stderr[-4000:])
 result[tag]=json.loads(run.stdout.strip().splitlines()[-1]);print(tag,result[tag],flush=True)
manifest=ET.parse(root/'app/src/main/AndroidManifest.xml').getroot();identity=re.search(r'PACKAGE_NAME="([^"]+)"',(production/'AppIdentity.java').read_text()).group(1)
assert manifest.attrib['package']==identity
ns='{http://schemas.android.com/apk/res/android}'
providers=manifest.findall('application/provider')
assert len(providers)==1 and providers[0].get(ns+'authorities')==identity+'.usb'
assert providers[0].get(ns+'readPermission')=='android.permission.DUMP'
receiver=next(x for x in manifest.findall('application/receiver') if x.get(ns+'name').endswith('WorkerBootstrapReceiver'))
assert receiver.get(ns+'permission')=='android.permission.DUMP'
for name in ['PacketBridge.java','NativePacketService.java','TapSession.java']:
 text=(production/name).read_text();assert 'AppIdentity.' in text;assert '"com.igareck.antidpi"' not in text
result['manifest_component_identity_matches']=True
result['production_sha256']={name:hashlib.sha256((production/name).read_bytes()).hexdigest() for name in ['NativePacketService.java','TapSession.java','PacketBridge.java','AppIdentity.java']}
(out/'result.json').write_text(json.dumps(result,indent=2)+'\n')
