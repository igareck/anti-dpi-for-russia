#!/usr/bin/env python3
"""Actual production WorkerSession against real host process groups and sessions.
The host uses util-linux setsid as the explicit Android Toybox boundary.
"""
import argparse,json,os,pathlib,shlex,signal,subprocess,time
p=argparse.ArgumentParser();p.add_argument('--jdk',required=True);p.add_argument('--jni-include',required=True);p.add_argument('--json-jar',required=True);a=p.parse_args()
r=pathlib.Path(__file__).resolve().parents[1];out=r/'build/worker-session';out.mkdir(parents=True,exist_ok=True)
include=pathlib.Path(a.jni_include);subprocess.run(['cc','-shared','-fPIC','-I'+str(include),'-I'+str(include/'linux'),str(r/'tests/session/session_fixture.c'),'-o',str(out/'libsession_fixture.so')],check=True)
src=list((r/'tests/session/java').rglob('*.java'))+[r/'app/src/main/java/com/igareck/antidpi/WorkerSession.java']
subprocess.run([a.jdk+'/bin/javac','-cp',a.json_jar,'-d',str(out),*map(str,src)],check=True)
base=[a.jdk+'/bin/java','-Djava.library.path='+str(out),'-cp',str(out)+':'+a.json_jar,'com.igareck.antidpi.SessionHarness']
cases=[]
for name,mode,new_session in [('legacy-session-leader','old',True),('already-detached','new',True),('ordinary-child','new',False)]:
 run=subprocess.run(base+[mode],capture_output=True,text=True,start_new_session=new_session,check=True,timeout=10);value=json.loads(run.stdout.strip().splitlines()[-1]);
 if mode=='old':assert value['legacy_rejected'] and value['errno']==1
 else:assert value['detached_and_idempotent']
 cases.append({'name':name,**value})
run=subprocess.run(['/usr/bin/setsid','-w',*base,'new'],preexec_fn=os.setpgrp,capture_output=True,text=True,check=True,timeout=10);value=json.loads(run.stdout.strip().splitlines()[-1]);assert value['detached_and_idempotent'];cases.append({'name':'group-leader-through-wrapper',**value})
# Execute the actual generated bootstrap with explicit host path/UID substitutions.
fixture=json.loads((r/'build/activation/bootstrap-fixture.json').read_text());script=fixture['script'];report=out/'detached-report.json';report.unlink(missing_ok=True)
prefix='/system/bin/toybox setsid -w /system/bin/app_process64 /system/bin --nice-name=com.igareck.antidpi:packet_worker com.igareck.antidpi.PacketWorker'
assert prefix in script
command=[base[0],'-Dsession.report='+str(report),*base[1:],'linger']
script=script.replace(prefix,shlex.join(['/usr/bin/setsid','-w',*command])).replace('/system/bin/toybox','/usr/bin/setsid').replace('if [ "$(id -u)" != 2000 ];','if false;')
owner=subprocess.Popen(['/bin/sh'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,start_new_session=True,text=True)
pid=None
try:
 owner.stdin.write(script);owner.stdin.flush();until=time.monotonic()+10
 while not report.exists():
  if time.monotonic()>until:raise AssertionError('Detached script did not start worker')
  time.sleep(.02)
 value=json.loads(report.read_text());pid=value['pid'];assert value['stdio_detached'];owner.stdin.close();owner.stdout.close();owner.stderr.close();os.kill(owner.pid,signal.SIGHUP);owner.wait(timeout=5);time.sleep(.2)
 stat=pathlib.Path('/proc/'+str(pid)+'/stat').read_text();fields=stat[stat.rfind(')')+2:].split();assert fields[0]!='Z' and int(fields[2])==pid and int(fields[3])==pid
 assert not (r/'SHOULD_NOT_EXIST').exists()
 cases.append({'name':'generated-script-survives-launcher-hangup','passed':True,'own_pgrp_and_sid':True,'stdio_detached':True,'stdin_quote_injection_absent':True})
finally:
 if owner.poll() is None:owner.kill();owner.wait()
 if pid:
  try:os.kill(pid,signal.SIGTERM)
  except ProcessLookupError:pass
value={'passed':True,'cases':cases,'production_worker_session':True,'real_kernel_setsid_and_proc':True,'scope':'Linux host, JNI adapter executes real setsid; util-linux setsid substitutes Android Toybox; no physical Android app_process claim'}
(out/'result.json').write_text(json.dumps(value,indent=2)+'\n');print(json.dumps(value,indent=2))
