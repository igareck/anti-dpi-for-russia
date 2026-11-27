#!/usr/bin/env python3
import argparse,pathlib,subprocess,json,xml.etree.ElementTree as ET
p=argparse.ArgumentParser();p.add_argument('--jdk',required=True);p.add_argument('--json-jar',required=True);a=p.parse_args();r=pathlib.Path(__file__).resolve().parents[1];out=r/'build/activation';out.mkdir(parents=True,exist_ok=True)
production=r/'app/src/main/java/com/igareck/antidpi';src=list((r/'tests/activation/java').rglob('*.java'))+[production/n for n in ['AdbBootstrap.java','WorkerChannel.java','IdentityEnvelope.java']]
subprocess.run([a.jdk+'/bin/javac','-encoding','UTF-8','-cp',a.json_jar,'-d',str(out),*map(str,src)],check=True)
run=subprocess.run([a.jdk+'/bin/java','-cp',str(out)+':'+a.json_jar,'com.igareck.antidpi.ActivationHarness',str(out/'bootstrap-fixture.json')],capture_output=True,text=True,check=True,timeout=20)
result=json.loads(run.stdout.strip().splitlines()[-1]);manifest=ET.parse(r/'app/src/main/AndroidManifest.xml').getroot();ns='{http://schemas.android.com/apk/res/android}';receiver=next(x for x in manifest.findall('application/receiver') if x.attrib[ns+'name'].endswith('WorkerBootstrapReceiver'));assert receiver.attrib[ns+'permission']=='android.permission.DUMP' and receiver.attrib[ns+'exported']=='true'
assert not any('shizuku' in ET.tostring(x,encoding='unicode').lower() for x in manifest)
result['bootstrap_receiver_sender_permission']='android.permission.DUMP';result['external_shizuku_manifest_entries']=False
(out/'result.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result,indent=2))
