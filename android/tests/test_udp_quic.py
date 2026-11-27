"""Actual SOCKS5 -> lwIP -> nfqws2 -> Ethernet, with independent UDP/IP peer.
Uses aioquic==1.3.0 for real TLS-authenticated HTTP/3 full-file transfers.
The Ethernet peer is a host boundary fixture, not Android NAT or a carrier DPI.
"""
from pathlib import Path
import argparse, asyncio, hashlib, json, os, select, socket, ssl, struct, subprocess, sys, threading, time
import test_nfqws2_transport as base
from aioquic.asyncio import QuicConnectionProtocol, serve
from aioquic.quic.configuration import QuicConfiguration
from aioquic.quic.connection import QuicConnection
from aioquic.h3.connection import H3_ALPN, H3Connection
from aioquic.h3.events import HeadersReceived, DataReceived

ROOT=Path(__file__).resolve().parents[1]
REMOTE=base.REMOTE
BODY=base.BODY
class PacketPeer(base.Peer):
 def __init__(self,sock,context,quic_port):
  super().__init__(sock,context);self.quic_port=quic_port;self.fragments={};self.sockets={};self.fragment_frames=0;self.udp_packets=0;self.ipid=100;self.large_replies=0
 def run(self):
  try:
   while not self.stop_event.is_set():
    ready,_,_=select.select([self.sock,*self.sockets],[],[],.01)
    for s in ready:
     if s is self.sock:self.receive(s.recv(65550))
     else:
      payload=s.recv(65535);sport,dport=self.sockets[s];self.answer(sport,dport,payload)
    for f in list(self.flows.values()):f.flush()
  except Exception as e:self.failure=e
  finally:
   for s in self.sockets:s.close()
 def answer(self,sport,dport,payload,wrong_source=False):
  src=socket.inet_aton('203.0.113.11') if wrong_source else REMOTE
  udp=bytearray(struct.pack('!HHHH',dport,sport,len(payload)+8,0)+payload)
  struct.pack_into('!H',udp,6,base.checksum(src+base.CLIENT+struct.pack('!BBH',0,17,len(udp))+udp) or 65535)
  self.ipid=(self.ipid+1)&65535
  frames=[]
  for off in range(0,len(udp),1368):
   part=udp[off:off+1368];flags=off//8 | (0x2000 if off+len(part)<len(udp) else 0)
   h=bytearray(struct.pack('!BBHHHBBH4s4s',69,0,20+len(part),self.ipid,flags,55,17,0,src,base.CLIENT))
   struct.pack_into('!H',h,10,base.checksum(h));frames.append(base.CM+base.GM+b'\x08\x00'+h+part)
  if len(frames)>1:self.large_replies+=1
  for frame in reversed(frames):self.sock.send(frame)
 def receive(self,frame):
  if len(frame)==34 and frame.startswith(b'IGTAP4'):return
  p=frame[14:]
  if len(p)<20 or p[9]!=17:
   if len(p)>=20:
    assert int.from_bytes(p[2:4],'big')==len(p) and base.checksum(p[:(p[0]&15)*4])==0, ('unexpected non-UDP frame',len(frame),frame[:128].hex())
   return super().receive(frame)
  ih=(p[0]&15)*4;size=int.from_bytes(p[2:4],'big');assert size==len(p) and size<=1400 and base.checksum(p[:ih])==0
  off_flags=int.from_bytes(p[6:8],'big');off=(off_flags&8191)*8;payload=p[ih:]
  if off_flags&0x3fff:
   self.fragment_frames+=1;k=(p[12:20],p[4:6]);r=self.fragments.setdefault(k,{'parts':{},'end':None})
   r['parts'][off]=payload
   if not off_flags&0x2000:r['end']=off+len(payload)
   if r['end'] is None:return
   pos=0;parts=[]
   while pos in r['parts']:part=r['parts'][pos];parts.append(part);pos+=len(part)
   if pos!=r['end']:return
   payload=b''.join(parts);del self.fragments[k]
  sport,dport,length,check=struct.unpack('!HHHH',payload[:8]);assert length==len(payload)
  assert not check or base.checksum(p[12:20]+struct.pack('!BBH',0,17,len(payload))+payload)==0
  data=payload[8:];self.udp_packets+=1
  if dport==53:
   answer=data[:2]+b'\x81\x80'+data[4:6]+b'\x00\x01\x00\x00\x00\x00'+data[12:]+b'\xc0\x0c\x00\x01\x00\x01\x00\x00\x00\x3c\x00\x04'+REMOTE
   packet=struct.pack('!HHHH',53,sport,len(answer)+8,0)+answer
   self.sock.send(base.ip_frame(base.GATEWAY,base.CLIENT,17,packet));self.dns_queries+=1;return
  if dport in (443,8443):
   local=next((s for s,pair in self.sockets.items() if pair==(sport,dport)),None)
   if local is None:
    local=socket.socket(socket.AF_INET,socket.SOCK_DGRAM);local.bind(('127.0.0.1',0));local.connect(('127.0.0.1',self.quic_port));self.sockets[local]=(sport,dport)
   local.send(data)
  else:self.answer(sport,dport,data)

class Fixture:
 def __init__(self,binary,q,out,context,quic_port,enabled=True,discord='D0',peer_class=PacketPeer):
  self.q=q;self.discord=discord;self.out=out;self.checks=[]
  a,b=socket.socketpair(socket.AF_UNIX,socket.SOCK_DGRAM)
  for s in (a,b):s.setsockopt(socket.SOL_SOCKET,socket.SO_RCVBUF,4*1024*1024);s.setsockopt(socket.SOL_SOCKET,socket.SO_SNDBUF,4*1024*1024)
  self.a=a;carrier,child=socket.socketpair(socket.AF_UNIX,socket.SOCK_STREAM)
  with socket.socket() as probe:probe.bind(('127.0.0.1',0));self.port=probe.getsockname()[1]
  # Commands are emitted by the production Java PacketCommand constructor.
  catalogue=json.loads((ROOT/'build/lifecycle/catalogue_commands.json').read_text())
  row=next(r for r in catalogue['discord_commands'] if r['id']=='T20' and r['q']==q and r['d']==discord) if enabled else next(r for r in catalogue['commands'] if r['id']=='T20')
  cmd=[str(binary) if x=='__BINARY__' else x for x in row['command'] if not x.startswith(('--igareck-socket=','--igareck-port=','--igareck-instance='))]
  cmd += [f'--igareck-config-fd={child.fileno()}',f'--igareck-port={self.port}','--igareck-instance=udp-fixture']
  self.logpath=out/(q+('_off' if not enabled else '')+'.log');self.log=self.logpath.open('w')
  self.process=subprocess.Popen(cmd,pass_fds=(child.fileno(),),stdin=subprocess.PIPE,stdout=self.log,stderr=self.log)
  child.close();cfg=b'IGTAP4\0\1'+base.CLIENT+base.GATEWAY+base.GATEWAY+base.CM+base.GM+struct.pack('!H',1400)
  carrier.sendmsg([cfg],[(socket.SOL_SOCKET,socket.SCM_RIGHTS,struct.pack('i',b.fileno()))]);carrier.close();b.close()
  self.peer=peer_class(a,context,quic_port);self.peer.start()
  until=time.monotonic()+8
  while '[IGARECK_LISTEN_READY]' not in self.logpath.read_text():
   if self.process.poll() is not None:raise RuntimeError(self.logpath.read_text()[-4000:])
   assert time.monotonic()<until,'startup timeout';time.sleep(.01)
 def associate(self,expected=0,request=b'\x00\x00\x00\x00',claimed_port=0):
  control=socket.create_connection(('127.0.0.1',self.port),3);control.sendall(b'\x05\x01\x00');assert base.exact(control,2)==b'\x05\x00'
  control.sendall(b'\x05\x03\x00\x01'+request+struct.pack('!H',claimed_port));reply=base.exact(control,10);assert reply[1]==expected,reply
  if expected:control.close();return None,None
  assert reply[4:8]==socket.inet_aton('127.0.0.1');relay=('127.0.0.1',int.from_bytes(reply[8:10],'big'))
  return control,relay
 def finish(self):
  self.process.terminate();self.process.wait(5);self.peer.stop_event.set();self.peer.join(2);self.a.close();self.log.close()
  text=self.logpath.read_text();assert self.process.returncode==0,text[-4000:]
  if self.peer.failure:raise self.peer.failure
  metrics=[json.loads(l.split('] ',1)[1]) for l in text.splitlines() if l.startswith('[IGARECK_NFQWS2_METRICS]')][-1]
  assert metrics['udp_associations_active']==metrics['udp_flows_active']==0,metrics
  return {'q':self.q,'d':self.discord,'metrics':metrics,'wire_ip_fragments':self.peer.fragment_frames,'large_return_datagrams':self.peer.large_replies,'checks':self.checks}

def header(port=9999,domain=None):
 return b'\0\0\0'+(b'\x03'+bytes([len(domain)])+domain.encode() if domain else b'\x01'+REMOTE)+struct.pack('!H',port)
def echo_checks(f):
 control,relay=f.associate();udp=socket.socket(socket.AF_INET,socket.SOCK_DGRAM);udp.bind(('127.0.0.1',0));udp.settimeout(3)
 try:
  for length in (0,1,1200,1372,2000,60000):
   data=(b'UDP-fixture-'+os.urandom(31))*2000;data=data[:length]
   udp.sendto(header()+data,relay);reply,_=udp.recvfrom(65535);assert reply==header()+data,(length,len(reply))
  f.checks+=['ordinary UDP 0/1/1200/1372/2000/60000 bytes; exact replies; fragmented return path']
  data=b'DNS-domain-UDP';udp.sendto(header(9998,'fixture.example')+data,relay);reply,_=udp.recvfrom(65535);assert reply==header(9998)+data
  f.checks+=['domain address resolves over TAP DNS, reply reports actual IPv4']
  rogue=socket.socket(socket.AF_INET,socket.SOCK_DGRAM);rogue.settimeout(.12);rogue.sendto(header()+b'wrong-client',relay)
  try:rogue.recvfrom(65535);raise AssertionError('unassociated sender accepted')
  except socket.timeout:pass
  rogue.close()
  for bad in [b'\0\0\x01'+header()[3:]+b'frag',b'\x01'+header()[1:]+b'bad-reserved',b'\0\0\0\x04'+b'\0'*18,b'\0\0\0\x03\xffshort']:
   udp.sendto(bad,relay)
   udp.settimeout(.1)
   try:udp.recvfrom(65535);raise AssertionError('invalid SOCKS UDP accepted')
   except socket.timeout:pass
  f.checks+=['foreign UDP sender and FRAG/RSV/IPv6/truncated headers rejected']
 finally:control.close();udp.close()
 time.sleep(.06)
 f.checks+=['closing controlling TCP disposes UDP association']
 # Several destinations and simultaneous independent associations.
 pairs=[]
 try:
  for i in range(8):
   c,relay=f.associate();s=socket.socket(socket.AF_INET,socket.SOCK_DGRAM);s.settimeout(3);pairs.append((c,s,relay))
  for i,(c,s,relay) in enumerate(pairs):s.sendto(header(10000+i)+f'flow-{i}'.encode(),relay)
  for i,(c,s,relay) in enumerate(pairs):assert s.recvfrom(65535)[0]==header(10000+i)+f'flow-{i}'.encode()
 finally:
  for c,s,_ in pairs:c.close();s.close()
 f.checks+=['eight concurrent isolated associations/destinations']

class HTTP3Server(QuicConnectionProtocol):
 def __init__(self,*a,**kw):super().__init__(*a,**kw);self.http=H3Connection(self._quic)
 def quic_event_received(self,event):
  for e in self.http.handle_event(event):
   if isinstance(e,HeadersReceived):
    self.http.send_headers(e.stream_id,[(b':status',b'200'),(b'content-length',str(len(BODY)).encode())])
    self.http.send_data(e.stream_id,BODY,end_stream=True);self.transmit()
class SOCKSTransport:
 def __init__(self,transport,relay,port):self.transport=transport;self.relay=relay;self.port=port
 def sendto(self,data,addr=None):self.transport.sendto(header(self.port)+data,self.relay)
 def get_extra_info(self,*a):return self.transport.get_extra_info(*a)
class HTTP3Client(QuicConnectionProtocol):
 def __init__(self,*a,relay,port,**kw):
  super().__init__(*a,**kw);self.http=H3Connection(self._quic);self.result=asyncio.get_running_loop().create_future();self.body=bytearray();self.relay=relay;self.port=port
 def connection_made(self,transport):super().connection_made(SOCKSTransport(transport,self.relay,self.port))
 def datagram_received(self,data,addr):
  assert data[:4]==b'\0\0\0\x01' and data[4:8]==REMOTE
  super().datagram_received(data[10:],('203.0.113.10',self.port))
 def quic_event_received(self,event):
  for e in self.http.handle_event(event):
   if isinstance(e,HeadersReceived):assert (b':status',b'200') in e.headers
   if isinstance(e,DataReceived):
    self.body.extend(e.data)
    if e.stream_ended and not self.result.done():self.result.set_result(bytes(self.body))
 def get(self):
  stream=self._quic.get_next_available_stream_id();self.http.send_headers(stream,[(b':method',b'GET'),(b':scheme',b'https'),(b':authority',b'fixture.example'),(b':path',b'/fixture')],end_stream=True);self.transmit()

async def http3_fetch(f,cert,port=443):
 control,relay=f.associate();loop=asyncio.get_running_loop()
 configuration=QuicConfiguration(is_client=True,alpn_protocols=H3_ALPN,server_name='fixture.example');configuration.load_verify_locations(cafile=str(cert))
 connection=QuicConnection(configuration=configuration)
 transport,client=await loop.create_datagram_endpoint(lambda:HTTP3Client(connection,relay=relay,port=port),local_addr=('127.0.0.1',0))
 try:
  client.connect(('203.0.113.10',port));await asyncio.wait_for(client.wait_connected(),10)
  client.get();body=await asyncio.wait_for(client.result,25)
  assert body==BODY
  return {'protocol':'HTTP/3 over genuine QUIC/TLS1.3','bytes':len(body),'sha256':hashlib.sha256(body).hexdigest(),'server_certificate_verified':True,'destination_port':port}
 finally:
  client.close()
  try:await asyncio.wait_for(client.wait_closed(),2)
  except asyncio.TimeoutError:pass
  transport.close();control.close()

async def run(binary,out,discord='D0'):
 out.mkdir(parents=True,exist_ok=True);cert=out/'fixture.crt';key=out/'fixture.key'
 subprocess.run(['openssl','req','-x509','-newkey','rsa:2048','-nodes','-days','2','-subj','/CN=fixture.example','-addext','subjectAltName=DNS:fixture.example','-keyout',str(key),'-out',str(cert)],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
 context=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER);context.load_cert_chain(cert,key)
 configuration=QuicConfiguration(is_client=False,alpn_protocols=H3_ALPN,max_data=4*1024*1024,max_stream_data=2*1024*1024);configuration.load_cert_chain(cert,key)
 server=await serve('127.0.0.1',0,configuration=configuration,create_protocol=HTTP3Server)
 quic_port=server._transport.get_extra_info('sockname')[1]
 results={'scope':'Actual host native path and nfqws2/Lua; independent Ethernet/IP peer; no Android NAT or carrier DPI certification.','profiles':[]}
 try:
  for q in ['Q1','Q2','Q3','Q4','Q5']:
   f=Fixture(binary,q,out,context,quic_port,discord=discord)
   try:
    await asyncio.to_thread(echo_checks,f)
    tls_context=ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT);tls_context.load_verify_locations(cert)
    h3,tcp=await asyncio.gather(http3_fetch(f,cert),asyncio.to_thread(base.fetch,f.port,2053,tls_context))
    f.checks+=['simultaneous TCP/TLS N20 + QUIC '+q+' transfers, both SHA-256 verified']
    if q=='Q5':h3_second=await http3_fetch(f,cert,8443)
    else:h3_second=None
   finally:value=await asyncio.to_thread(f.finish)
   value['http3']=h3;value['simultaneous_tcp_tls']=tcp
   if h3_second:value['http3_nonstandard_port']=h3_second
   assert value['metrics']['udp_lua_calls']>0 and value['metrics']['udp_generated']>0,value
   if q in ['Q1','Q2']:assert value['wire_ip_fragments']>0
   results['profiles'].append(value);(out/'result.json').write_text(json.dumps(results,indent=2)+'\n');print('UDP/H3 PASS',q,flush=True)
  f=Fixture(binary,'Q1',out,context,quic_port,enabled=False)
  try:f.associate(expected=7)
  finally:results['udp_disabled']=await asyncio.to_thread(f.finish)
 finally:server.close()
 results['passed']=True;(out/'result.json').write_text(json.dumps(results,indent=2)+'\n');return results
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--binary',type=Path,required=True);p.add_argument('--out',type=Path,default=ROOT/'build/udp-quic');p.add_argument('--discord',default='D0');a=p.parse_args()
 os.environ['ASAN_OPTIONS']=os.environ.get('ASAN_OPTIONS','')+':detect_leaks=0'
 asyncio.run(run(a.binary.resolve(),a.out.resolve(),a.discord))
