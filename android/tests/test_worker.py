#!/usr/bin/env python3
"""Real Java RPC/authentication/owner lease; Android bootstrap is tested on device."""
import argparse,json,pathlib,subprocess
p=argparse.ArgumentParser();p.add_argument('--jdk',required=True);p.add_argument('--json-jar',required=True);a=p.parse_args()
r=pathlib.Path(__file__).resolve().parents[1];out=r/'build/worker';out.mkdir(parents=True,exist_ok=True)
base=r/'tests/lifecycle/java';production=r/'app/src/main/java/com/igareck/antidpi'
src=[base/'android/os'/n for n in ['IBinder.java','Binder.java','RemoteException.java']]+[base/'com/igareck/antidpi/ITransportService.java']
src += [production/n for n in ['WorkerChannel.java','PacketWorkerTransport.java']]+list((r/'tests/worker/java').rglob('*.java'))
subprocess.run([a.jdk+'/bin/javac','-encoding','UTF-8','-cp',a.json_jar,'-d',str(out),*map(str,src)],check=True)
run=subprocess.run([a.jdk+'/bin/java','-cp',str(out)+':'+a.json_jar,'com.igareck.antidpi.WorkerHarness'],text=True,capture_output=True,timeout=30)
(out/'stdout.log').write_text(run.stdout);(out/'stderr.log').write_text(run.stderr)
if run.returncode:raise RuntimeError(run.stderr)
result=json.loads(run.stdout.strip().splitlines()[-1]);(out/'result.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result,indent=2))
