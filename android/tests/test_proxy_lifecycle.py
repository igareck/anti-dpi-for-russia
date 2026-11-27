#!/usr/bin/env python3
"""Production Java lifecycle regression tests, explicitly using host Android/Binder fixtures."""
import argparse,hashlib,json,pathlib,subprocess
p=argparse.ArgumentParser();p.add_argument('--jdk',required=True);p.add_argument('--android-jar',required=True);p.add_argument('--json-jar',required=True);p.add_argument('--out',default='build/lifecycle');a=p.parse_args()
root=pathlib.Path(__file__).resolve().parents[1];out=(root/a.out).resolve();out.mkdir(parents=True,exist_ok=True)
native=out/'native';native.mkdir(exist_ok=True);f=native/'libnfqws2.so';f.write_text('#!/bin/sh\nexit 99\n');f.chmod(0o700)
java=pathlib.Path(a.jdk)/'bin/java';javac=pathlib.Path(a.jdk)/'bin/javac';fixtures=list((root/'tests/lifecycle/java').rglob('*.java'))
production=root/'app/src/main/java/com/igareck/antidpi'
result={'fixture_json_sha256':hashlib.sha256(pathlib.Path(a.json_jar).read_bytes()).hexdigest()}

def compile_run(source,tag):
 classes=out/tag;classes.mkdir(exist_ok=True);cp=f'{a.json_jar}:{a.android_jar}'
 actual=[source/(x+'.java') for x in ('AppIdentity','PacketBridge','ProxyService','NativePacketService','Catalogue','PacketCommand','QuicProfiles','DiscordProfiles','Profiles','ProfileFilter','ProfileStatus','PacketRecovery','StopVerifier','TcpIds','YoutubeProbe','TcpStrategyScan','WebTarget','ProxyOptions','WorkerChannel','UiLanguage')]
 use=[f for f in fixtures if f.name!="Profiles.java"]
 subprocess.run([str(javac),'-encoding','UTF-8','-source','17','-target','17','-cp',cp,'-d',str(classes),*[str(f) for f in use+actual]],check=True)
 cmd=[str(java),f'-Digareck.test.native={native}',f'-Digareck.test.assets={root / "app/src/main/assets"}','-cp',f'{classes}:{cp}','com.igareck.antidpi.'+'LifecycleHarness']
 run=subprocess.run(cmd,text=True,capture_output=True,timeout=125)
 (out/(tag+'.stdout')).write_text(run.stdout);(out/(tag+'.stderr')).write_text(run.stderr)
 if run.returncode:raise RuntimeError(f'{tag} exited {run.returncode}\n{run.stderr[-9000:]}')
 return json.loads(run.stdout.strip().splitlines()[-1])
result['current']=compile_run(production,'current')
run=subprocess.run([str(java),'-cp',f'{out / "current"}:{a.json_jar}:{a.android_jar}','com.igareck.antidpi.RecoveryHarness'],text=True,capture_output=True,check=True,timeout=45)
result['resource_recovery']=json.loads(run.stdout.strip().splitlines()[-1])
run=subprocess.run([str(java),f'-Digareck.test.native={native}',f'-Digareck.test.assets={root / "app/src/main/assets"}','-cp',f'{out / "current"}:{a.json_jar}:{a.android_jar}','com.igareck.antidpi.YoutubeLifecycleHarness'],text=True,capture_output=True,check=True,timeout=30)
result['youtube_lifecycle']=json.loads(run.stdout.strip().splitlines()[-1])

process_source=out/'process_fixture.c';process_binary=native/'process-probe/libnfqws2.so';process_binary.parent.mkdir(exist_ok=True)
process_source.write_text('#include <unistd.h>\nint main(void){for(;;)pause();}\n')
subprocess.run(['cc',str(process_source),'-o',str(process_binary)],check=True)
run=subprocess.run([str(java),'-cp',f'{out / "current"}:{a.json_jar}:{a.android_jar}','com.igareck.antidpi.ProcessIdentityHarness',str(process_binary)],text=True,capture_output=True,check=True,timeout=12)
result['process_identity']=json.loads(run.stdout.strip().splitlines()[-1])
subprocess.run([str(java),'-cp',f'{out / "current"}:{a.json_jar}:{a.android_jar}','com.igareck.antidpi.CatalogueHarness',str(root/'app/src/main/assets'),str(out/'catalogue_commands.json')],check=True)
cat=json.loads((out/'catalogue_commands.json').read_text());result['catalogue']={k:v for k,v in cat.items() if k not in ('commands','quic_commands','discord_commands','youtube_commands')}
result['production_sha256']={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in production.glob('*.java') if p.name in ('PacketBridge.java','ProxyService.java','NativePacketService.java','PacketRecovery.java','StopVerifier.java','Catalogue.java','PacketCommand.java','Profiles.java','ProfileFilter.java','ProfileStatus.java','QuicProfiles.java','DiscordProfiles.java','AppIdentity.java','YoutubeProbe.java','TcpStrategyScan.java','WebTarget.java','ProxyOptions.java','TcpIds.java','UiLanguage.java')}
(out/'result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
print(json.dumps(result,ensure_ascii=False,indent=2))
