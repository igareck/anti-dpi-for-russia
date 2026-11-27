#!/usr/bin/env python3
"""Actual nfqws2/Lua catalogue startup + selected TCP/TLS recipes on a host peer.

No Android NAT is simulated or certified. Fake rejection models are explicit:
PAWS, MD5 without a key, invalid checksum/ACK/SEQ/flags, or insufficient TTL.
"""
from pathlib import Path
import argparse,json,os,socket,struct,subprocess,time
import test_nfqws2_transport as peer
ROOT=Path(__file__).resolve().parents[1]
class CatalogueFlow(peer.Flow):
 def send(self,frame):
  # A fixed 9-hop response path makes AutoTTL inputs deterministic.
  b=bytearray(frame);b[22]=55;b[24:26]=b'\0\0';b[24:26]=struct.pack('!H',peer.checksum(b[14:34]));self.peer.sock.send(b)
 def receive(self,seq,ack,flags,payload,ts,window):
  if payload:
   if flags&2:self.peer.rejections['syn']+=1;self.ack();return
   if not flags&16:self.peer.rejections['noack']+=1;return
   old=(self.snext-ack)&0xffffffff
   if old>65535:self.peer.rejections['ack']+=1;self.ack();return
   ahead=(seq-self.cnext)&0xffffffff
   if ahead<0x80000000 and ahead>524280:self.peer.rejections['seq']+=1;self.ack();return
   behind=(self.cnext-seq)&0xffffffff
   if 0<behind<0x80000000:
    if behind>=len(payload):self.ack();return
    payload=payload[behind:];seq=self.cnext;self.peer.overlap_trimmed+=behind
  super().receive(seq,ack,flags,payload,ts,window)
  # Trim an older prefix when an out-of-order segment overlaps the new edge.
  while True:
   found=None
   for position,data in list(self.ooo.items()):
    behind=(self.cnext-position)&0xffffffff
    if behind<0x80000000:
     del self.ooo[position]
     if behind<len(data):found=(data[behind:],behind);break
   if found is None:break
   self.peer.overlap_trimmed+=found[1];super().receive(self.cnext,ack,24,found[0],ts,window)
class CataloguePeer(peer.Peer):
 def __init__(self,*a,**kw):
  super().__init__(*a,**kw);self.rejections={k:0 for k in ('ttl','checksum','md5','syn','noack','ack','seq')};self.overlap_trimmed=0
 def receive(self,frame):
  if len(frame)==34 and frame.startswith(b'IGTAP4'):return
  assert len(frame)>=34 and frame[12:14]==b'\x08\x00';p=frame[14:];ih=(p[0]&15)*4
  assert peer.checksum(p[:ih])==0
  if p[9]!=6:return super().receive(frame)
  self.packets+=1
  if p[8]<=9:self.rejections['ttl']+=1;return
  packet=p[ih:];pseudo=p[12:20]+struct.pack('!BBH',0,6,len(packet))
  if peer.checksum(pseudo+packet)!=0:self.rejections['checksum']+=1;return
  sport,dport,seq,ack,offset,flags,window,_,_=struct.unpack('!HHIIBBHHH',packet[:20]);th=(offset>>4)*4;opts=packet[20:th];ts,scale=peer.read_options(opts);data=packet[th:]
  i=0
  while i<len(opts):
   if opts[i]==0:break
   if opts[i]==1:i+=1;continue
   if opts[i]==19:self.rejections['md5']+=1;return
   i+=opts[i+1]
  key=(sport,dport)
  if key not in self.flows and flags&2 and not flags&16:
   self.flows[key]=CatalogueFlow(self,sport,dport,seq,ts,scale);f=self.flows[key];f.send(peer.tcp_frame(f,18));f.snext+=1;return
  if key in self.flows:self.flows[key].receive(seq,ack,flags,data,ts,window)
peer.Peer=CataloguePeer

def config():return b'IGTAP4\0\1'+peer.CLIENT+peer.GATEWAY+peer.GATEWAY+peer.CM+peer.GM+struct.pack('!H',1400)
def startup(binary,row,out):
 a,b=socket.socketpair(socket.AF_UNIX,socket.SOCK_DGRAM);carrier,child=socket.socketpair(socket.AF_UNIX,socket.SOCK_STREAM)
 with socket.socket() as probe:probe.bind(('127.0.0.1',0));port=probe.getsockname()[1]
 command=[str(binary) if x=='__BINARY__' else x for x in row['command'] if not x.startswith('--igareck-port=')]
 command += [f'--igareck-config-fd={child.fileno()}',f'--igareck-port={port}']
 # Assert actual global Lua functions in addition to parsing the recipe.
 command += ["--lua-init=assert(type(fake)=='function' and type(multisplit)=='function' and type(multidisorder)=='function' and type(hostfakesplit)=='function' and type(fakedsplit)=='function' and type(fakeddisorder)=='function' and type(tcpseg)=='function','catalogue function missing')"]
 logpath=out/(row['id']+'_init.log')
 with logpath.open('w') as log:
  process=subprocess.Popen(command,pass_fds=(child.fileno(),),stdin=subprocess.PIPE,stdout=log,stderr=log);child.close()
  try:
   carrier.sendmsg([config()],[(socket.SOL_SOCKET,socket.SCM_RIGHTS,struct.pack('i',b.fileno()))]);carrier.close();b.close();end=time.monotonic()+5
   while '[IGARECK_LISTEN_READY]' not in logpath.read_text():
    if process.poll() is not None:raise RuntimeError(row['id']+' startup: '+logpath.read_text()[-4000:])
    if time.monotonic()>end:raise TimeoutError(row['id']+' Lua startup')
    time.sleep(.003)
  finally:
   process.terminate()
   try:process.wait(4)
   except subprocess.TimeoutExpired:process.kill();process.wait()
   a.close()
  text=logpath.read_text();assert process.returncode==0,(row['id'],process.returncode,text[-3000:]);assert '[IGARECK_NFQWS2_CORE]' in text
 return {'id':row['id'],'recipe_sha256':row['recipe_sha256'],'lua_and_listener_ready':True,'exit_code':process.returncode}

def main():
 p=argparse.ArgumentParser();p.add_argument('--binary',type=Path,required=True);p.add_argument('--commands',type=Path,required=True);p.add_argument('--out',type=Path,default=ROOT/'build/catalogue-native');a=p.parse_args();a.out.mkdir(parents=True,exist_ok=True)
 rows=json.loads(a.commands.read_text())['commands'];by_number={r['number']:r for r in rows};result={'scope':'Actual pinned host core/Lua/lwIP. Independent TCP/TLS peer; no Android Binder/TAP/NAT or DPI effectiveness certification.','initialization':[],'tls':[],'tls_failures':[]}
 for i,row in enumerate(rows):
  result['initialization'].append(startup(a.binary.resolve(),row,a.out))
  if i%50==0:print('Initialized',i+1,'/',len(rows),flush=True)
 (a.out/'result.json').write_text(json.dumps(result,indent=2)+'\n')
 selected={20};families={};processing={}
 for r in rows:
  if r['number']:families.setdefault(r['family'],r['number']);processing.setdefault(r['processing'],r['number'])
 selected.update(families.values());selected.update(processing.values());print('TLS representatives',sorted(selected),flush=True)
 cert=a.out/'fixture.crt';key=a.out/'fixture.key'
 subprocess.run(['openssl','req','-x509','-newkey','rsa:2048','-nodes','-days','2','-subj','/CN=fixture.example','-addext','subjectAltName=DNS:fixture.example','-keyout',str(key),'-out',str(cert)],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
 for number in sorted(selected):
  row=by_number[number]
  try:
   value=peer.run(a.binary.resolve(),row['id'],cert,key,a.out,command_override=row['command'],target_ports=(443,));value['family']=row['family'];value['processing']=row['processing'];result['tls'].append(value)
   print('TLS PASS',row['id'],row['processing'],flush=True)
  except Exception as e:result['tls_failures'].append({'id':row['id'],'family':row['family'],'processing':row['processing'],'error':str(e)});print('TLS FAIL',row['id'],str(e)[:200],flush=True)
  (a.out/'result.json').write_text(json.dumps(result,indent=2)+'\n')
 # Keep a strict N20 regression with losses and nonstandard external TLS ports.
 for tls12 in (False,True):
  value=peer.run(a.binary.resolve(),'T20',cert,key,a.out,loss=not tls12,outgoing_loss=not tls12,tls12=tls12,command_override=by_number[20]['command'],target_ports=(2053,8443,44443));value['regression']=True;result['tls'].append(value)
 result['initialized_count']=len(result['initialization']);result['families_with_tls_success']=sorted({v['family'] for v in result['tls'] if 'family' in v and v['family']});result['selected_count']=len(selected)
 (a.out/'result.json').write_text(json.dumps(result,indent=2)+'\n');print('Initialization:',len(rows),'TLS:',len(result['tls']),'failures:',len(result['tls_failures']),flush=True)
 if result['tls_failures']:raise SystemExit(1)
if __name__=='__main__':main()
