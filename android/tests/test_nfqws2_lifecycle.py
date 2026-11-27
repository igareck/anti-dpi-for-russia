"""Owned-child lifecycle/occupied-port checks, with mock TAP and real SCM_RIGHTS.
No Android framework cleanup claim is made by this host-only test.
"""
from pathlib import Path
import ctypes,json,os,socket,struct,subprocess,sys,time
from test_nfqws2_transport import ROOT,CLIENT,GATEWAY,CM,GM
OUT=ROOT/'build/nfqws2_lifecycle';OUT.mkdir(parents=True,exist_ok=True)
BINARY=Path(sys.argv[1]).resolve()
def available_port():
 with socket.socket() as s:s.bind(('127.0.0.1',0));return s.getsockname()[1]
def config():return b'IGTAP4\0\1'+CLIENT+GATEWAY+GATEWAY+CM+GM+struct.pack('!H',1400)
def command(port,fd):
 assets=ROOT/'app/src/main/assets/nfqws2'
 return [str(BINARY),f'--igareck-config-fd={fd}',f'--igareck-port={port}','--igareck-profile=N0','--igareck-instance=lifecycle',
  '--qnum=0','--debug=0','--reasm-disable',f'--lua-init=@{assets}/zapret-lib.lua',f'--lua-init=@{assets}/zapret-antidpi.lua']
def start(port,name,parent=False):
 a,b=socket.socketpair(socket.AF_UNIX,socket.SOCK_DGRAM);carrier,child_carrier=socket.socketpair()
 path=OUT/(name+'.log');log=path.open('w');cmd=command(port,child_carrier.fileno())
 if parent:
  code='import subprocess,sys,signal; p=subprocess.Popen(sys.argv[2:],pass_fds=(int(sys.argv[1]),),stdout=sys.stderr,stderr=sys.stderr); print(p.pid,flush=True); signal.pause()'
  proc=subprocess.Popen([sys.executable,'-c',code,str(child_carrier.fileno()),*cmd],pass_fds=(child_carrier.fileno(),),stdout=subprocess.PIPE,stderr=log,text=True)
  native_pid=int(proc.stdout.readline().strip())
 else:
  proc=subprocess.Popen(cmd,pass_fds=(child_carrier.fileno(),),stdin=subprocess.PIPE,stdout=log,stderr=log);native_pid=proc.pid
 child_carrier.close();carrier.sendmsg([config()],[(socket.SOL_SOCKET,socket.SCM_RIGHTS,struct.pack('i',b.fileno()))]);carrier.close();b.close()
 return proc,native_pid,a,log,path
def wait_for(path,text,timeout=6):
 until=time.monotonic()+timeout
 while text not in path.read_text():
  if time.monotonic()>until:raise AssertionError(path.read_text()[-2500:])
  time.sleep(.02)
port=available_port()
for i in range(8):
 p,pid,tap,log,path=start(port,'restart_'+str(i));wait_for(path,'[IGARECK_LISTEN_READY]')
 with socket.create_connection(('127.0.0.1',port),timeout=2) as s:s.sendall(b'\x05\x01\x00');assert s.recv(2)==b'\x05\x00'
 p.terminate();assert p.wait(5)==0;wait_for(path,'"event":"STOP"');tap.close();log.close()
with socket.socket() as held:
 held.bind(('127.0.0.1',0));held.listen(1);busy=held.getsockname()[1]
 p,pid,tap,log,path=start(busy,'occupied');assert p.wait(5)!=0;assert '[IGARECK_BIND_ERROR]' in path.read_text()
 with socket.create_connection(('127.0.0.1',busy),timeout=2) as client:accepted,_=held.accept();accepted.close()
 tap.close();log.close()
# Become a subreaper to collect our own grandchild after its direct parent dies.
assert ctypes.CDLL(None,use_errno=True).prctl(36,1,0,0,0)==0
p,pid,tap,log,path=start(port,'parent_death',parent=True);wait_for(path,'[IGARECK_LISTEN_READY]');p.kill();p.wait(5)
until=time.monotonic()+5;collected=False
while time.monotonic()<until:
 try:
  done,status=os.waitpid(pid,os.WNOHANG)
  if done:assert os.waitstatus_to_exitcode(status)==0;collected=True;break
 except ChildProcessError:
  # Some managed runners reap adopted children. The listener must still close.
  try:os.kill(pid,0)
  except ProcessLookupError:collected=True;break
 time.sleep(.03)
assert collected,'Native child survived parent death'
wait_for(path,'"event":"STOP"');tap.close();log.close()
with socket.socket() as probe:probe.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1);probe.bind(('127.0.0.1',port))
result={'scope':'host-only, mock TAP; no Android framework cleanup validation','same_port_restarts':8,'occupied_port_owner_preserved':True,'native_parent_death_exit_confirmed':True,'parent_death_listener_released':True}
(OUT/'results.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result))
