#!/usr/bin/env python3
"""Actual local Stop protocol regression tests with explicit Android socket boundaries."""
import argparse,json,pathlib,subprocess
p=argparse.ArgumentParser();p.add_argument('--jdk',required=True);p.add_argument('--json-jar',required=True);a=p.parse_args()
r=pathlib.Path(__file__).resolve().parents[1];out=r/'build/control';out.mkdir(parents=True,exist_ok=True)
src=list((r/'tests/control/java').rglob('*.java'))+[r/'app/src/main/java/com/igareck/antidpi/PacketControl.java']
subprocess.run([a.jdk+'/bin/javac','-encoding','UTF-8','-cp',a.json_jar,'-d',str(out),*map(str,src)],check=True)
run=subprocess.run([a.jdk+'/bin/java','-cp',str(out)+':'+a.json_jar,'com.igareck.antidpi.ControlHarness'],check=True,text=True,capture_output=True,timeout=45)
j=json.loads(run.stdout.strip().splitlines()[-1]);(out/'result.json').write_text(json.dumps(j,indent=2)+'\n');print(json.dumps(j,indent=2))
