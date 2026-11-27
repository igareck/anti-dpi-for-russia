"""Independent Ethernet/TCP peer + Python TLS, exercising the production poll loop.
No Android TAP/tethering or carrier behaviour is simulated as a passing device test.
The peer enforces PAWS and records actual modified packet headers independently.
"""
from pathlib import Path
import argparse,hashlib,json,os,select,socket,ssl,struct,subprocess,threading,time,uuid
ROOT=Path(__file__).resolve().parents[1]
CLIENT=socket.inet_aton('10.229.0.2');GATEWAY=socket.inet_aton('10.229.0.1');REMOTE=socket.inet_aton('203.0.113.10')
CM=bytes.fromhex('021122334455');GM=bytes.fromhex('020000000001')
BODY=bytes(range(256))*4096
EXPECTED=hashlib.sha256(BODY).hexdigest()
def checksum(b):
 if len(b)&1:b+=b'\0'
 v=sum(struct.unpack('!%dH'%(len(b)//2),b));v=(v&65535)+(v>>16);v=(v&65535)+(v>>16);return (~v)&65535
def ip_frame(src,dst,protocol,body):
 h=bytearray(struct.pack('!BBHHHBBH4s4s',69,0,20+len(body),1234,0,64,protocol,0,src,dst));struct.pack_into('!H',h,10,checksum(h));return CM+GM+b'\x08\x00'+h+body
def tcp_frame(flow,flags,payload=b'',sequence=None):
 opts=b'\x01\x01\x08\x0a'+struct.pack('!II',flow.timestamp,flow.last_ts)
 if flags&2:opts=b'\x02\x04\x05\x50\x01\x03\x03\x03'+opts
 h=bytearray(struct.pack('!HHIIBBHHH',flow.remote_port,flow.client_port,flow.snext if sequence is None else sequence,flow.cnext,(20+len(opts))//4<<4,flags,65535,0,0)+opts)
 pseudo=REMOTE+CLIENT+struct.pack('!BBH',0,6,len(h)+len(payload));struct.pack_into('!H',h,16,checksum(pseudo+h+payload));return ip_frame(REMOTE,CLIENT,6,h+payload)
def read_options(b):
 ts=None;scale=0;i=0
 while i<len(b):
  k=b[i]
  if k==0:break
  if k==1:i+=1;continue
  assert i+1<len(b) and b[i+1]>=2 and i+b[i+1]<=len(b)
  if k==8:ts=struct.unpack('!II',b[i+2:i+10])[0]
  if k==3:scale=b[i+2]
  i+=b[i+1]
 return ts,scale
class Flow:
 def __init__(self,peer,sport,dport,seq,ts,scale):
  self.peer=peer;self.client_port=sport;self.remote_port=dport;self.cnext=(seq+1)&0xffffffff;self.snext=1000000+sport*100
  self.timestamp=200000;self.last_ts=ts or 0;self.scale=scale;self.window=65535;self.unacked=[];self.pending=bytearray();self.ooo={}
  self.inbio=ssl.MemoryBIO();self.outbio=ssl.MemoryBIO();self.tls=peer.context.wrap_bio(self.inbio,self.outbio,server_side=True)
  self.handshake=False;self.http=bytearray();self.responded=False;self.fin=False;self.sent_fin=False;self.syn_acked=False;self.dropped=False;self.client_dropped=False
 def send(self,frame):self.peer.sock.send(frame)
 def ack(self):self.send(tcp_frame(self,16))
 def pump_tls(self,data):
  if data:self.inbio.write(data)
  try:
   if not self.handshake:self.tls.do_handshake();self.handshake=True
   while True:
    block=self.tls.read(32768)
    if not block:break
    self.http.extend(block)
    if b'\r\n\r\n' in self.http and not self.responded:
     assert self.http.startswith(b'GET /fixture ')
     response=b'HTTP/1.1 200 OK\r\nContent-Length: 1048576\r\nConnection: close\r\n\r\n'+BODY
     while response:response=response[self.tls.write(response):]
     self.responded=True
  except (ssl.SSLWantReadError,ssl.SSLZeroReturnError):pass
  self.pending.extend(self.outbio.read())
 def flush(self):
  if not self.syn_acked:return
  inflight=sum(len(payload) for seq,payload,sent in self.unacked)
  while self.pending and inflight<min(self.window,48000):
   n=min(1360,len(self.pending),min(self.window,48000)-inflight)
   if n<=0:break
   payload=bytes(self.pending[:n]);del self.pending[:n];seq=self.snext;self.snext=(self.snext+n)&0xffffffff
   frame=tcp_frame(self,24,payload,seq);now=time.monotonic();self.unacked.append((seq,payload,now));inflight+=n
   if self.peer.loss and self.responded and not self.dropped and len(payload)>1000:
    self.dropped=True;self.peer.loss_injected+=1
   else:self.send(frame)
  now=time.monotonic()
  for i,(seq,payload,sent) in enumerate(self.unacked):
   if now-sent>.4:self.send(tcp_frame(self,24,payload,seq));self.unacked[i]=(seq,payload,now);self.peer.retransmitted+=1
  if self.responded and not self.pending and not self.unacked and not self.sent_fin:
   self.send(tcp_frame(self,17));self.snext=(self.snext+1)&0xffffffff;self.sent_fin=True
 def receive(self,seq,ack,flags,payload,ts,window):
  self.timestamp+=1
  if flags&4:return
  if flags&16:
   self.syn_acked=True;self.unacked=[p for p in self.unacked if ((p[0]+len(p[1])-ack)&0xffffffff)<0x80000000 and p[0]+len(p[1])!=ack]
  self.window=window<<self.scale
  if payload:
   if ts is not None and ((ts-self.last_ts)&0xffffffff)>=0x80000000:
    self.peer.paws_dropped+=1;self.peer.fake_payload_bytes+=len(payload)
    if b'dl.google.com' in payload:self.peer.fake_google_seen=True
    self.ack();return
   if self.peer.outgoing_loss and not self.client_dropped:
    self.client_dropped=True;self.peer.client_loss_injected+=1;return
   if ts is not None:self.last_ts=ts
   if seq==self.cnext:
    self.cnext=(self.cnext+len(payload))&0xffffffff;self.pump_tls(payload)
    while self.cnext in self.ooo:
     chunk=self.ooo.pop(self.cnext);self.cnext=(self.cnext+len(chunk))&0xffffffff;self.pump_tls(chunk)
   elif ((seq-self.cnext)&0xffffffff)<0x80000000:self.ooo[seq]=payload
   self.ack()
  if flags&1 and ((seq+len(payload))&0xffffffff)==self.cnext:self.cnext=(self.cnext+1)&0xffffffff;self.fin=True;self.ack()
  self.flush()
class Peer(threading.Thread):
 def __init__(self,sock,context,loss=False,outgoing_loss=False):
  super().__init__(daemon=True);self.sock=sock;self.context=context;self.loss=loss;self.outgoing_loss=outgoing_loss;self.flows={};self.stop_event=threading.Event();self.failure=None
  self.paws_dropped=0;self.fake_payload_bytes=0;self.fake_google_seen=False;self.loss_injected=0;self.client_loss_injected=0;self.retransmitted=0;self.dns_queries=0;self.packets=0
 def run(self):
  try:
   while not self.stop_event.is_set():
    if select.select([self.sock],[],[],.015)[0]:self.receive(self.sock.recv(65550))
    for flow in list(self.flows.values()):flow.flush()
  except Exception as e:self.failure=e
 def receive(self,frame):
  if len(frame)==34 and frame.startswith(b'IGTAP4'):return
  assert len(frame)>=34 and frame[12:14]==b'\x08\x00';p=frame[14:];ih=(p[0]&15)*4;total=struct.unpack_from('!H',p,2)[0]
  assert total==len(p) and checksum(p[:ih])==0;payload=p[ih:];self.packets+=1
  if p[9]==17:
   sport,dport,size,_=struct.unpack('!HHHH',payload[:8]);assert dport==53
   query=payload[8:];answer=query[:2]+b'\x81\x80'+query[4:6]+b'\x00\x01\x00\x00\x00\x00'+query[12:]+b'\xc0\x0c\x00\x01\x00\x01\x00\x00\x00\x3c\x00\x04'+REMOTE
   udp=struct.pack('!HHHH',53,sport,len(answer)+8,0)+answer;self.sock.send(ip_frame(GATEWAY,CLIENT,17,udp));self.dns_queries+=1;return
  assert p[9]==6
  pseudo=p[12:20]+struct.pack('!BBH',0,6,len(payload));assert checksum(pseudo+payload)==0
  sport,dport,seq,ack,offset,flags,window,_,_=struct.unpack('!HHIIBBHHH',payload[:20]);th=(offset>>4)*4;ts,scale=read_options(payload[20:th]);data=payload[th:]
  key=(sport,dport)
  if flags&2 and not flags&16:
   if key not in self.flows:self.flows[key]=Flow(self,sport,dport,seq,ts,scale)
   f=self.flows[key];f.send(tcp_frame(f,18));f.snext+=1;return
  if key in self.flows:self.flows[key].receive(seq,ack,flags,data,ts,window)
def exact(sock,n):
 out=bytearray()
 while len(out)<n:
  b=sock.recv(n-len(out))
  if not b:raise EOFError((len(out),n))
  out.extend(b)
 return bytes(out)
def fetch(port,remote_port,context,domain=False):
 s=socket.create_connection(('127.0.0.1',port),timeout=10);s.sendall(b'\x05\x01\x00');assert exact(s,2)==b'\x05\x00'
 dst=b'\x03\x0ffixture.example' if domain else b'\x01'+REMOTE
 s.sendall(b'\x05\x01\x00'+dst+struct.pack('!H',remote_port));response=exact(s,10);assert response[:2]==b'\x05\x00',response
 with context.wrap_socket(s,server_hostname='fixture.example') as tls:
  tls.settimeout(20);tls.sendall(b'GET /fixture HTTP/1.1\r\nHost: fixture.example\r\nConnection: close\r\n\r\n')
  data=bytearray()
  while b'\r\n\r\n' not in data:
   block=tls.recv(16384)
   if not block:raise EOFError('TLS closed before HTTP headers')
   data.extend(block)
  head,body=data.split(b'\r\n\r\n',1)
  while len(body)<len(BODY):
   b=tls.recv(65536)
   if not b:break
   body.extend(b)
  assert len(body)==1048576 and hashlib.sha256(body).hexdigest()==EXPECTED,(len(body),head)
  return {'port':remote_port,'domain':domain,'bytes':len(body),'sha256':EXPECTED,'tls':tls.version()}
def run(binary,profile,cert,key,out,loss=False,outgoing_loss=False,tls12=False,command_override=None,target_ports=(443,2053,8443)):
 context=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER);context.load_cert_chain(cert,key)
 if tls12:context.maximum_version=ssl.TLSVersion.TLSv1_2
 client=ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT);client.load_verify_locations(cert)
 a,b=socket.socketpair(socket.AF_UNIX,socket.SOCK_DGRAM)
 for s in (a,b):s.setsockopt(socket.SOL_SOCKET,socket.SO_RCVBUF,2*1024*1024);s.setsockopt(socket.SOL_SOCKET,socket.SO_SNDBUF,2*1024*1024)
 config=b'IGTAP4\0\1'+CLIENT+GATEWAY+GATEWAY+CM+GM+struct.pack('!H',1400);assert len(config)==34
 carrier,child_carrier=socket.socketpair(socket.AF_UNIX,socket.SOCK_STREAM)
 probe=socket.socket();probe.bind(('127.0.0.1',0));port=probe.getsockname()[1];probe.close()
 asset=ROOT/'app/src/main/assets/nfqws2'
 command=[str(binary),f'--igareck-config-fd={child_carrier.fileno()}',f'--igareck-port={port}',f'--igareck-profile={profile}','--igareck-instance=host-check','--qnum=0','--debug=0',f'--lua-init=@{asset}/zapret-lib.lua',f'--lua-init=@{asset}/zapret-antidpi.lua','--filter-tcp=443,2053,8443']
 if profile=='T0':command+=['--reasm-disable']
 if profile=='T20':
  recipe=json.loads((asset/'recipe20.json').read_text());command+=['--lua-init='+recipe['Init']]+recipe['Args']
 if command_override is not None:
  command=[str(binary) if x=='__BINARY__' else x for x in command_override]
  command=[x for x in command if not x.startswith('--igareck-port=')]
  command += [f'--igareck-config-fd={child_carrier.fileno()}',f'--igareck-port={port}']
 logpath=out/(profile+('_loss' if loss else '')+('_outloss' if outgoing_loss else '')+('_tls12' if tls12 else '')+'.log');log=logpath.open('w');process=subprocess.Popen(command,pass_fds=(child_carrier.fileno(),),stdin=subprocess.PIPE,stdout=log,stderr=log)
 child_carrier.close()
 carrier.sendmsg([config],[(socket.SOL_SOCKET,socket.SCM_RIGHTS,struct.pack('i',b.fileno()))]);carrier.close();b.close()
 peer=Peer(a,context,loss,outgoing_loss);peer.start();results=[]
 try:
  until=time.monotonic()+15
  while '[IGARECK_LISTEN_READY]' not in logpath.read_text():
   if process.poll() is not None:raise RuntimeError(logpath.read_text()[-5000:])
   if time.monotonic()>until:raise TimeoutError('native readiness')
   time.sleep(.03)
  for i,target in enumerate(target_ports):
   results.append(fetch(port,target,client,domain=i==1))
  # Check explicit command rejection and a fresh native metrics snapshot.
  with socket.create_connection(('127.0.0.1',port),timeout=3) as invalid:
   invalid.sendall(b'\x05\x01\x00');assert exact(invalid,2)==b'\x05\x00'
   invalid.sendall(b'\x05\x03\x00\x01'+REMOTE+b'\x01\xbb');assert exact(invalid,10)[1]==7
  process.stdin.write(b'snapshot\n');process.stdin.flush();until=time.monotonic()+2
  while '"event":"SNAPSHOT"' not in logpath.read_text():
   if time.monotonic()>until:raise TimeoutError('fresh metrics snapshot')
   time.sleep(.02)
  if peer.failure:raise peer.failure
 finally:
  process.terminate()
  try:process.wait(timeout=5)
  except subprocess.TimeoutExpired:process.kill();process.wait()
  time.sleep(.05);peer.stop_event.set();peer.join(2);a.close();log.close()
 logs=logpath.read_text();metrics=[json.loads(x.split('] ',1)[1]) for x in logs.splitlines() if x.startswith('[IGARECK_NFQWS2_METRICS]')]
 assert metrics and metrics[-1]['input_in']>0 and metrics[-1]['input_out']>0
 if profile=='T20':assert metrics[-1]['lua_calls']>0 and metrics[-1]['generated']>0 and peer.paws_dropped>0 and peer.fake_google_seen
 elif profile=='T0':assert metrics[-1]['generated']==0 and metrics[-1]['lua_calls']==0
 else:assert metrics[-1]['lua_calls']>0
 assert process.returncode==0 and peer.failure is None,(process.returncode,peer.failure)
 return {'profile':profile,'loss':loss,'outgoing_loss':outgoing_loss,'tls12':tls12,'downloads':results,'dns_queries':peer.dns_queries,'paws_dropped':peer.paws_dropped,
  'fd_transport':'SCM_RIGHTS socketpair with peer UID check; named Android socket not executed','fresh_metrics_snapshot':True,'udp_associate_rejected':True,
  'fake_google_seen':peer.fake_google_seen,'loss_injected':peer.loss_injected,'client_loss_injected':peer.client_loss_injected,'retransmitted':peer.retransmitted,'metrics':metrics[-1]}
if __name__=='__main__':
 parser=argparse.ArgumentParser();parser.add_argument('--binary',type=Path,default=ROOT/'build/stage/lib/host/libnfqws2.so');parser.add_argument('--out',type=Path,default=ROOT/'build/transport_checks');parser.add_argument('--loss',action='store_true');parser.add_argument('--outgoing-loss',action='store_true');parser.add_argument('--tls12',action='store_true');args=parser.parse_args()
 args.out.mkdir(parents=True,exist_ok=True);cert=args.out/'fixture.crt';key=args.out/'fixture.key'
 if not cert.exists():subprocess.run(['openssl','req','-x509','-newkey','rsa:2048','-nodes','-days','2','-subj','/CN=fixture.example','-addext','subjectAltName=DNS:fixture.example','-keyout',str(key),'-out',str(cert)],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
 results=[]
 for profile in ['T0','T20']:
  result=run(args.binary.resolve(),profile,cert,key,args.out,args.loss,args.outgoing_loss,args.tls12);results.append(result);print(json.dumps(result),flush=True)
 (args.out/'results.json').write_text(json.dumps({'scope':'host Ethernet peer, not Android tethering or carrier test','results':results},indent=2)+'\n')
