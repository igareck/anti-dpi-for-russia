#!/usr/bin/env python3
import argparse,pathlib,subprocess,json
p=argparse.ArgumentParser();p.add_argument('--jdk',required=True);p.add_argument('--json-jar',required=True);p.add_argument('--baseline-source',type=pathlib.Path);a=p.parse_args()
r=pathlib.Path(__file__).resolve().parents[1];out=r/('build/discovery-baseline' if a.baseline_source else 'build/discovery-regression');out.mkdir(parents=True,exist_ok=True)
production=(a.baseline_source or r)/'app/src/main/java/com/igareck/antidpi'
fixtures=r/'tests/activation_service/java';sources=[x for x in fixtures.rglob('*.java') if x.name not in ['ActivationFixHarness.java','DiscoveryDetailHarness.java']]+[production/'AdbDiscovery.java']
sources += [x for x in [production/'AdbDiscoveryState.java'] if x.exists()]
subprocess.run([a.jdk+'/bin/javac','-encoding','UTF-8','-cp',a.json_jar,'-d',str(out),*map(str,sources)],check=True)
run=subprocess.run([a.jdk+'/bin/java','-cp',str(out)+':'+a.json_jar,'com.igareck.antidpi.DiscoveryRegressionHarness'],check=True,capture_output=True,text=True,timeout=15)
value=json.loads(run.stdout.strip().splitlines()[-1])
if a.baseline_source:
 assert value['both_ports_initially_found'] and not value['connection_port_survives_pairing_window_close'];value['expected_discovery_bug_reproduced']=True
else:assert value['passed']
(out/'result.json').write_text(json.dumps(value,indent=2)+'\n');print(json.dumps(value,indent=2))
