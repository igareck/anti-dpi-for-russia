#!/usr/bin/env python3
"""Production USB/bootstrap and Wi-Fi activation policy with explicit Android fixtures."""
import argparse,json,pathlib,subprocess
p=argparse.ArgumentParser();p.add_argument('--jdk',required=True);p.add_argument('--json-jar',required=True);a=p.parse_args()
r=pathlib.Path(__file__).resolve().parents[1];out=r/'build/usb-activation';out.mkdir(parents=True,exist_ok=True)
base=r/'tests/activation_service/java';overrides=r/'tests/usb_activation/java'
files={str(p.relative_to(base)):p for p in base.rglob('*.java') if str(p.relative_to(base)).startswith('android/')}
files.update({str(p.relative_to(overrides)):p for p in overrides.rglob('*.java')})
prod=r/'app/src/main/java/com/igareck/antidpi'
src=list(files.values())+[prod/n for n in ['EmbeddedAdb.java','AdbBootstrap.java','AutoActivation.java','UsbActivationProvider.java','UsbCommands.java','WorkerChannel.java','WirelessDebugging.java','AdbDiscovery.java','AdbDiscoveryState.java','AdbPorts.java','WorkerFailure.java','AppIdentity.java','ActivationPermission.java']]
subprocess.run([a.jdk+'/bin/javac','-encoding','UTF-8','-cp',a.json_jar,'-d',str(out),*map(str,src)],check=True)
run=subprocess.run([a.jdk+'/bin/java','-cp',str(out)+':'+a.json_jar,'com.igareck.antidpi.UsbActivationHarness'],capture_output=True,text=True,timeout=25)
(out/'stdout.log').write_text(run.stdout);(out/'stderr.log').write_text(run.stderr)
if run.returncode:raise RuntimeError(run.stderr)
v=json.loads(run.stdout.strip().splitlines()[-1]);(out/'result.json').write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n');print(json.dumps(v,ensure_ascii=False,indent=2))
