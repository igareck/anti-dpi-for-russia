#!/usr/bin/env python3
"""Test actual TAP cleanup retry semantics with fault-injected Android boundaries."""
import argparse,json,pathlib,subprocess
p=argparse.ArgumentParser();p.add_argument('--jdk',required=True);p.add_argument('--android-jar',required=True);p.add_argument('--json-jar',required=True);a=p.parse_args()
root=pathlib.Path(__file__).resolve().parents[1];out=root/'build/tap-cleanup';out.mkdir(parents=True,exist_ok=True)
fixtures=root/'tests/lifecycle/java';sources=[f for f in fixtures.rglob('*.java') if f.relative_to(fixtures).parts[0]=='android']
sources+=list((root/'tests/tap/java').rglob('*.java'))+[fixtures/'com/igareck/antidpi/AppLog.java']
sources += [root/'app/src/main/java/com/igareck/antidpi'/name for name in ['TapSession.java','PacketBuilder.java','AppIdentity.java']]
classpath=f'{a.json_jar}:{a.android_jar}';subprocess.run([a.jdk+'/bin/javac','-encoding','UTF-8','-source','17','-target','17','-cp',classpath,'-d',str(out),*map(str,sources)],check=True)
r=subprocess.run([a.jdk+'/bin/java','-cp',f'{out}:{classpath}','com.igareck.antidpi.TapCleanupHarness'],check=True,capture_output=True,text=True)
value=json.loads(r.stdout.strip().splitlines()[-1]);(out/'result.json').write_text(json.dumps(value,indent=2)+'\n');print(json.dumps(value,indent=2))
