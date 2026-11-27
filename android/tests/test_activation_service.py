#!/usr/bin/env python3
import argparse,json,pathlib,subprocess
p=argparse.ArgumentParser();p.add_argument('--jdk',required=True);p.add_argument('--json-jar',required=True);p.add_argument('--harness',choices=['service','discovery'],default='service');a=p.parse_args()
r=pathlib.Path(__file__).resolve().parents[1];out=r/('build/discovery-detail' if a.harness=='discovery' else 'build/activation-service');out.mkdir(parents=True,exist_ok=True)
production=r/'app/src/main/java/com/igareck/antidpi'
sources=list((r/'tests/activation_service/java').rglob('*.java'))+[production/n for n in ['AdbActivationService.java','AdbDiscovery.java','AdbDiscoveryState.java','AdbPorts.java','WorkerFailure.java','WirelessDebugging.java']]
subprocess.run([a.jdk+'/bin/javac','-encoding','UTF-8','-cp',a.json_jar,'-d',str(out),*map(str,sources)],check=True)
run=subprocess.run([a.jdk+'/bin/java','-cp',str(out)+':'+a.json_jar,'com.igareck.antidpi.'+('DiscoveryDetailHarness' if a.harness=='discovery' else 'ActivationFixHarness')],capture_output=True,text=True,timeout=20)
(out/'stdout.log').write_text(run.stdout);(out/'stderr.log').write_text(run.stderr)
if run.returncode:print(run.stderr);raise SystemExit(run.returncode)
value=json.loads(run.stdout.strip().splitlines()[-1]);(out/'result.json').write_text(json.dumps(value,indent=2)+'\n');print(json.dumps(value,indent=2))
