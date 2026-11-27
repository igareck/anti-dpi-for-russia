"""Actual native SOCKS/lwIP/nfqws2 path, independent RFC-shaped UDP peers.
This verifies forwarding, recipe effects and isolation, not live Discord or carrier DPI.
"""
from pathlib import Path
import argparse,hashlib,json,socket,ssl,struct,subprocess,time
import test_udp_quic as udp
import test_nfqws2_transport as base

ROOT=Path(__file__).resolve().parents[1]
DISCOVERY=struct.pack('!HHI',1,70,0x12345678)+bytes(66)
ORDINARY=b'ordinary-voice-like-payload-untouched'
PUBLIC='203.0.113.99'

def is_stun(data):
 return len(data)==20 and data[:8]==struct.pack('!HHI',1,0,0x2112A442)

class VoicePeer(udp.PacketPeer):
 def __init__(self,*a):
  super().__init__(*a);self.wire=[];self.originals=0;self.ignored_fakes=0
 def receive(self,frame):
  p=frame[14:]
  if len(p)>=28 and p[9]==17:
   ih=(p[0]&15)*4;flags=int.from_bytes(p[6:8],'big')
   if not flags&0x3fff and len(p)>=ih+8:
    self.wire.append({'ttl':p[8],'dport':int.from_bytes(p[ih+2:ih+4],'big'),'payload':p[ih+8:]})
  super().receive(frame)
 def answer(self,sport,dport,payload,wrong_source=False):
  if payload==DISCOVERY:
   self.originals+=1
   response=struct.pack('!HHI',2,70,0x12345678)+PUBLIC.encode().ljust(64,b'\0')+struct.pack('!H',sport)
  elif is_stun(payload):
   self.originals+=1
   response=struct.pack('!HHI12sHHBBHI',0x0101,12,0x2112A442,payload[8:20],0x0020,8,0,1,
     sport^0x2112,int.from_bytes(socket.inet_aton(PUBLIC),'big')^0x2112A442)
  elif payload==ORDINARY:self.originals+=1;response=payload
  else:self.ignored_fakes+=1;return
  super().answer(sport,dport,response,wrong_source)

def main(binary,out):
 out.mkdir(parents=True,exist_ok=True)
 cert=out/'fixture.crt';key=out/'fixture.key'
 subprocess.run(['openssl','req','-x509','-newkey','rsa:2048','-nodes','-days','2','-subj','/CN=fixture.example','-addext','subjectAltName=DNS:fixture.example','-keyout',str(key),'-out',str(cert)],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
 context=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER);context.load_cert_chain(cert,key)
 result={'scope':'Host native plus independent STUN/IP-discovery peers; no Android NAT or actual Discord/provider certification','profiles':[]}
 for d in range(9):
  did='D'+str(d);folder=out/did;folder.mkdir(exist_ok=True)
  f=udp.Fixture(binary,'Q5',folder,context,0,discord=did,peer_class=VoicePeer)
  control,relay=f.associate();s=socket.socket(socket.AF_INET,socket.SOCK_DGRAM);s.bind(('127.0.0.1',0));s.settimeout(3)
  def exchange(port,data):
   s.sendto(udp.header(port)+data,relay);reply,_=s.recvfrom(65535)
   assert reply[:10]==udp.header(port),(did,reply[:10].hex())
   return reply[10:]
  try:
   response=exchange(52001,DISCOVERY)
   assert len(response)==74 and response[:8]==struct.pack('!HHI',2,70,0x12345678)
   assert response[8:72].split(b'\0')[0].decode()==PUBLIC and int.from_bytes(response[-2:],'big')>0
   for i in range(12):
    request=struct.pack('!HHI',1,0,0x2112A442)+struct.pack('!III',17,23,i)
    response=exchange(52002,request)
    assert len(response)==32 and response[:2]==b'\x01\x01' and response[8:20]==request[8:20]
    mapped=int.from_bytes(response[-4:],'big')^0x2112A442
    assert mapped==int.from_bytes(socket.inet_aton(PUBLIC),'big')
   assert exchange(52003,ORDINARY)==ORDINARY
   tls=ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT);tls.load_verify_locations(cert)
   tcp=base.fetch(f.port,2053,tls)
   # Explicitly close the control socket: native must dispose the whole association.
  finally:control.close();s.close();time.sleep(.08);value=f.finish()
  m=value['metrics']
  assert m['udp_local_rx']==m['udp_tx']==m['udp_rx']==14,(did,m)
  assert m['udp_socket_errors']==m['queue_errors']==0,(did,m)
  assert m['voice_stun_packets']==12 and m['voice_discovery_packets']==1,(did,m)
  assert m['discord_profile']==did and m['udp_assoc_closed']==1 and m['udp_assoc_empty']==0
  assert f.peer.originals==14,(did,f.peer.originals)
  fakes=[w for w in f.peer.wire if w['dport'] in (52001,52002) and w['payload']!=DISCOVERY and not is_stun(w['payload'])]
  if d==0:assert m['voice_generated']==m['voice_lua_calls']==0 and not fakes
  elif d==10:
   assert m['voice_generated']>0 and f.peer.fragment_frames>0 and not fakes
  else:
   assert m['voice_generated']==len(fakes)>0,(did,m,len(fakes))
   repeats={1:2,2:6,3:12,4:2,5:6,6:2,7:6,8:6,9:6}[d]
   assert len(fakes)==11*repeats,(did,'out-range must affect first 10 STUN + one discovery',len(fakes))
   if d in (1,2,3):assert all(w['payload']==bytes(16) for w in fakes)
   elif d in (4,5,8):assert all(w['payload']==bytes(64) for w in fakes)
   else:assert all(w['payload']==b'\x40'+bytes(619) for w in fakes), 'Pinned nfqws2 fake_default_quic is 620 bytes'
   assert all(w['ttl']==(2 if d in (8,9) else 64) for w in fakes)
   assert m['voice_lua_calls']==11
  ordinary=[w for w in f.peer.wire if w['dport']==52003]
  assert len(ordinary)==1 and ordinary[0]['payload']==ORDINARY and ordinary[0]['ttl']==64
  originals=[w for w in f.peer.wire if w['payload']==DISCOVERY or is_stun(w['payload'])]
  assert all(w['ttl']==64 for w in originals), 'Original packet TTL must remain unchanged'
  value.update(passed=True,independent_peer_originals=f.peer.originals,ignored_fakes=f.peer.ignored_fakes,tcp_tls_with_udp_association=tcp,
    fake_wire_headers=[{'ttl':w['ttl'],'payload_bytes':len(w['payload']),'payload_sha256':hashlib.sha256(w['payload']).hexdigest()} for w in fakes[:2]])
  result['profiles'].append(value)
  (out/'result.json').write_text(json.dumps(result,indent=2)+'\n')
  print('DISCORD/STUN PASS',did,'udp',m['udp_tx'],m['udp_rx'],'generated',m['voice_generated'],flush=True)
 # A negotiated but unused association must be distinguishable in diagnostics.
 folder=out/'empty';folder.mkdir(exist_ok=True)
 f=udp.Fixture(binary,'Q1',folder,context,0,discord='D0',peer_class=VoicePeer)
 control,relay=f.associate();control.close();time.sleep(.08);empty=f.finish()
 assert empty['metrics']['udp_assoc_empty']==1 and empty['metrics']['udp_local_rx']==0
 assert 'reason=tcp_control_' in (folder/'Q1.log').read_text()
 result['empty_association']=empty
 # Independent Discord switch keeps the UDP relay available with Q processing off.
 folder=out/'D1_without_Q';folder.mkdir(exist_ok=True)
 f=udp.Fixture(binary,'OFF',folder,context,0,discord='D1',peer_class=VoicePeer)
 control,relay=f.associate();client=socket.socket(socket.AF_INET,socket.SOCK_DGRAM);client.bind(('127.0.0.1',0));client.settimeout(3)
 try:
  for port,data in [(52001,DISCOVERY),(52002,struct.pack('!HHI12s',1,0,0x2112A442,b'fixture-txid')),(52003,ORDINARY)]:
   client.sendto(udp.header(port)+data,relay);reply,_=client.recvfrom(65535);assert reply[:10]==udp.header(port)
 finally:control.close();client.close();time.sleep(.08);only=f.finish()
 assert only['metrics']['quic_profile']=='OFF' and only['metrics']['voice_generated']==4 and only['metrics']['udp_tx']==only['metrics']['udp_rx']==3
 result['discord_without_quic']=only
 result['passed']=True
 (out/'result.json').write_text(json.dumps(result,indent=2)+'\n')

if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--binary',type=Path,required=True);p.add_argument('--out',type=Path,default=ROOT/'build/discord-stun');a=p.parse_args()
 main(a.binary.resolve(),a.out.resolve())
