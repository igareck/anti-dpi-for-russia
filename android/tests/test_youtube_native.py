"""Five actual parallel SOCKS/lwIP/nfqws2 profiles, independent Ethernet/TLS peer.
Verifies mark selection, recipe function isolation, per-profile Lua pattern names,
all listeners forwarding fresh traffic, and ordinary native shutdown.
This is a host boundary test, not an Android tethering/provider measurement.
"""
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor
import argparse,json,re,socket,ssl,struct,subprocess,time
import test_nfqws2_transport as base
ROOT=Path(__file__).resolve().parents[1]

class CapturePeer(base.Peer):
 def __init__(self,*args):super().__init__(*args);self.fake_hosts={}
 def receive(self,frame):
  if len(frame)>54 and frame[23]==6:
   p=frame[14:];ih=(p[0]&15)*4;tcp=p[ih:];sport=int.from_bytes(tcp[:2],'big');data=tcp[(tcp[12]>>4)*4:]
   for host in (b'dl.google.com',b'open.spotify.com',b'max.ru',b'eh.vk.ru'):
    if host in data:self.fake_hosts.setdefault(sport,set()).add(host.decode())
  return super().receive(frame)

def run(binary,out):
 out.mkdir(parents=True,exist_ok=True);cert=out/'fixture.crt';key=out/'fixture.key'
 subprocess.run(['openssl','req','-x509','-newkey','rsa:2048','-nodes','-days','2','-subj','/CN=fixture.example','-addext','subjectAltName=DNS:fixture.example','-keyout',str(key),'-out',str(cert)],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
 server=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER);server.load_cert_chain(cert,key)
 client=ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT);client.load_verify_locations(cert)
 catalogue=json.loads((ROOT/'build/lifecycle/catalogue_commands.json').read_text());results=[]
 rows=json.loads((ROOT/'app/src/main/assets/nfqws2/catalogue.json').read_text())['ProfileRows']
 for batch in catalogue['youtube_commands']:
  folder=out/('_'.join(map(str,batch['numbers'])));folder.mkdir(exist_ok=True)
  a,b=socket.socketpair(socket.AF_UNIX,socket.SOCK_DGRAM)
  for s in (a,b):s.setsockopt(socket.SOL_SOCKET,socket.SO_RCVBUF,4*1024*1024);s.setsockopt(socket.SOL_SOCKET,socket.SO_SNDBUF,4*1024*1024)
  carrier,child=socket.socketpair(socket.AF_UNIX,socket.SOCK_STREAM)
  config=b'IGTAP4\0\1'+base.CLIENT+base.GATEWAY+base.GATEWAY+base.CM+base.GM+struct.pack('!H',1400)
  command=[str(binary) if x=='__BINARY__' else x for x in batch['command'] if not x.startswith('--igareck-socket=')]
  command.append(f'--igareck-config-fd={child.fileno()}')
  path=folder/'native.log';log=path.open('w');p=subprocess.Popen(command,pass_fds=(child.fileno(),),stdin=subprocess.PIPE,stdout=log,stderr=log)
  child.close();carrier.sendmsg([config],[(socket.SOL_SOCKET,socket.SCM_RIGHTS,struct.pack('i',b.fileno()))]);carrier.close();b.close()
  peer=CapturePeer(a,server);peer.start()
  try:
   until=time.monotonic()+8
   while '[IGARECK_LISTEN_READY]' not in path.read_text():
    if p.poll() is not None:raise RuntimeError(path.read_text()[-9000:])
    if time.monotonic()>until:raise TimeoutError('probe listeners readiness')
    time.sleep(.02)
   ports={int(n):int(port) for n,port in re.findall(r'\[IGARECK_PROBE_PORT\] profile=T(\d+) port=(\d+)',path.read_text())}
   assert set(ports)==set(batch['numbers']) and len(set(ports.values()))==len(ports)
   with ThreadPoolExecutor(max_workers=5) as pool:
    downloads=list(pool.map(lambda n:{'strategy':'T'+str(n),**base.fetch(ports[n],443,client,domain=True)},batch['numbers']))
   assert peer.failure is None,peer.failure
  finally:
   p.terminate()
   try:p.wait(timeout=5)
   except subprocess.TimeoutExpired:p.kill();p.wait()
   peer.stop_event.set();peer.join(2);a.close();log.close()
  text=path.read_text();audits={n:set() for n in batch['numbers']}
  for n,name in re.findall(r'\[IGARECK_YOUTUBE_LUA\] strategy=T(\d+) function=(\S+)',text):audits[int(n)].add(name)
  for n in batch['numbers']:
   row=next(x for x in rows if x['Number']==n)
   expected={x.split('=',1)[1].split(':',1)[0] for x in row['Args'] if x.startswith('--lua-desync=')}
   assert audits[n] and audits[n]<=expected,('cross-profile recipe contamination',n,audits[n],expected)
   if row['Init']:
    assert f'public_tls_pattern_T{n}' in ' '.join(command)
    expected_host=re.search(r'sni=([^\']+)',row['Init']).group(1)
    source_ports={int(sport) for mark,sport in re.findall(r'\[IGARECK_YOUTUBE_LUA\] strategy=T(\d+) function=\S+ source_port=(\d+)',text) if int(mark)==n}
    wire_hosts=set().union(*(peer.fake_hosts.get(p,set()) for p in source_ports))
    assert wire_hosts=={expected_host},('Lua patterns mixed between strategies',n,wire_hosts,expected_host)

  assert p.returncode==0 and 'stack traceback' not in text and 'Lua error' not in text,text[-9000:]
  results.append({'numbers':batch['numbers'],'ports':ports,'downloads':downloads,'lua_functions':{n:sorted(v) for n,v in audits.items()},'passed':True})
  print('NATIVE YOUTUBE PASS',batch['numbers'],flush=True)
 (out/'result.json').write_text(json.dumps({'passed':True,'scope':'host native parallel profiles + independent Ethernet TLS peer','batches':results},indent=2)+'\n')
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--binary',type=Path,required=True);p.add_argument('--out',type=Path,default=ROOT/'build/youtube-native');a=p.parse_args();run(a.binary.resolve(),a.out.resolve())
