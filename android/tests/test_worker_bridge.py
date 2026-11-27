#!/usr/bin/env python3
"""Real Bridge/RPC path, with Android bootstrap and TAP explicitly simulated."""
import argparse,json,pathlib,subprocess
p=argparse.ArgumentParser();p.add_argument('--jdk',required=True);p.add_argument('--android-jar',required=True);p.add_argument('--json-jar',required=True);a=p.parse_args()
r=pathlib.Path(__file__).resolve().parents[1];out=r/'build/worker_bridge';out.mkdir(parents=True,exist_ok=True)
base=r/'tests/lifecycle/java';production=r/'app/src/main/java/com/igareck/antidpi'
src=[p for p in (base/'android').rglob('*.java')]+[base/'rikka/shizuku/Shizuku.java']
src += [base/'com/igareck/antidpi'/n for n in ['ITransportService.java','IWorkerLauncher.java','PacketLauncher.java','PacketControl.java','AppLog.java','FakeTransport.java','TapSession.java','EmbeddedAdb.java','AutoActivation.java','ServiceRestoration.java']]
src += [production/n for n in ['WorkerChannel.java','PacketWorkerTransport.java','PacketBridge.java','AppIdentity.java','PacketRecovery.java','StopVerifier.java','Catalogue.java','QuicProfiles.java','DiscordProfiles.java','TcpIds.java']]+list((r/'tests/worker_bridge/java').rglob('*.java'))
cp=a.json_jar+':'+a.android_jar
subprocess.run([a.jdk+'/bin/javac','-encoding','UTF-8','-cp',cp,'-d',str(out),*map(str,src)],check=True)
run=subprocess.run([a.jdk+'/bin/java','-cp',str(out)+':'+cp,'com.igareck.antidpi.WorkerBridgeHarness'],text=True,capture_output=True,timeout=30)
(out/'stdout.log').write_text(run.stdout);(out/'stderr.log').write_text(run.stderr)
if run.returncode or not run.stdout.strip():raise RuntimeError(run.stderr)
result=json.loads(run.stdout.strip().splitlines()[-1]);(out/'result.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result,indent=2))
