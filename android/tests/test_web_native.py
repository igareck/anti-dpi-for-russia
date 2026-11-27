"""Production Java website probe -> actual SOCKS/lwIP/nfqws2 -> independent TLS peer.
No Internet, Android framework or provider bypass certification is implied.
"""
from pathlib import Path
import argparse,json,re,socket,ssl,struct,subprocess,time
import test_nfqws2_transport as base
ROOT=Path(__file__).resolve().parents[1]
PAGES={
 'www.youtube.com':('YOUTUBE',1,102400,'YouTube','<script>ytcfg.set({})</script>'),
 'discord.com':('DISCORD',20,102400,'Discord','<a href="https://discord.com/app">Open</a>'),
 'www.linkedin.com':('LINKEDIN',1,102400,'LinkedIn','<link href="https://static.licdn.com/style.css">'),
 'www.facebook.com':('FACEBOOK',20,1,'Facebook',''),
 'x.com':('X',1,102400,'X','<script src="https://abs.twimg.com/script.js"></script>'),
 'www.threads.com':('THREADS',20,102400,'Threads','<link rel="canonical" href="https://www.threads.com/">'),
}

class HtmlFlow(base.Flow):
 facebook_response='normal'

 def pump_tls(self,data):
  if data:self.inbio.write(data)
  try:
   if not self.handshake:self.tls.do_handshake();self.handshake=True
   while True:
    block=self.tls.read(32768)
    if not block:break
    self.http.extend(block)
    if b'\r\n\r\n' in self.http and not self.responded:
     path=self.http.split(b' ',2)[1]
     host=re.search(rb'\r\nHost: ([^\r\n]+)\r\n',self.http).group(1).decode('ascii')
     _,_,_,title,marker=PAGES[host]
     body=f'<!doctype html><html><title>{title}</title>{marker}'.encode('ascii')
     assert path in (b'/',b'/robots.txt',b'/fixture-short',b'/fixture-http-error')
     body+=b'a'*(180000 if path==b'/' else 20000)+b'</html>'
     status=b'400 Bad Request' if path==b'/fixture-http-error' else b'200 OK'
     headers=b'Content-Type: text/html\r\n'
     framed=True
     if host=='www.facebook.com':
      assert path==b'/robots.txt' and b'Accept: text/plain\r\n' in self.http
      headers=b'Content-Type: text/plain; charset=UTF-8\r\n'
      body=b'User-agent: *\nDisallow: /login/\n'
      if self.facebook_response=='generic':body=b'Generic response'
      elif self.facebook_response=='html':headers=b'Content-Type: text/html\r\n'
      elif self.facebook_response=='rejected':status=b'403 Forbidden'
      elif self.facebook_response=='redirect':
       status=b'302 Found';headers+=b'Location: https://www.facebook.com/\r\n'
      elif self.facebook_response=='chunked':
       framed=False
       headers+=b'Transfer-Encoding: chunked\r\n'
       body=('%x\r\n'%len(body)).encode()+body+b'\r\n0\r\n\r\n'
     if framed:headers+=b'Content-Length: '+str(len(body)).encode()+b'\r\n'
     response=b'HTTP/1.1 '+status+b'\r\n'+headers+b'Connection: close\r\n\r\n'+body
     while response:response=response[self.tls.write(response):]
     self.responded=True
  except (ssl.SSLWantReadError,ssl.SSLZeroReturnError):pass
  except ssl.SSLEOFError:
   # The 100 KiB probe intentionally closes before the full HTML body ends.
   # One such flow must not stop the independent multi-flow peer.
   if not self.responded:raise
  self.pending.extend(self.outbio.read())
base.Flow=HtmlFlow

def main():
 p=argparse.ArgumentParser();p.add_argument('--binary',type=Path,required=True);p.add_argument('--jdk',type=Path,required=True);p.add_argument('--json-jar',type=Path,required=True);a=p.parse_args()
 out=ROOT/'build/web-native';out.mkdir(parents=True,exist_ok=True);cert=out/'fixture.crt';key=out/'fixture.key'
 san='subjectAltName='+','.join('DNS:'+host for host in PAGES)
 subprocess.run(['openssl','req','-x509','-newkey','rsa:2048','-nodes','-days','2','-subj','/CN=discord.com','-addext',san,'-keyout',str(key),'-out',str(cert)],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
 classes=ROOT/'build/lifecycle/current';cp=f'{classes}:{a.json_jar.resolve()}'
 subprocess.run([str(a.jdk/'bin/javac'),'-encoding','UTF-8','-cp',cp,'-d',str(classes),str(ROOT/'tests/lifecycle/java/com/igareck/antidpi/WebsiteNativeClient.java')],check=True)
 context=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER);context.load_cert_chain(cert,key)
 left,right=socket.socketpair(socket.AF_UNIX,socket.SOCK_DGRAM)
 for s in (left,right):s.setsockopt(socket.SOL_SOCKET,socket.SO_RCVBUF,4*1024*1024);s.setsockopt(socket.SOL_SOCKET,socket.SO_SNDBUF,4*1024*1024)
 carrier,child=socket.socketpair(socket.AF_UNIX,socket.SOCK_STREAM)
 config=b'IGTAP4\0\1'+base.CLIENT+base.GATEWAY+base.GATEWAY+base.CM+base.GM+struct.pack('!H',1400)
 row=json.loads((ROOT/'build/lifecycle/catalogue_commands.json').read_text())['youtube_commands'][0]
 command=[str(a.binary.resolve()) if x=='__BINARY__' else x for x in row['command'] if not x.startswith('--igareck-socket=')];command.append(f'--igareck-config-fd={child.fileno()}')
 path=out/'native.log';log=path.open('w');proc=subprocess.Popen(command,pass_fds=(child.fileno(),),stdin=subprocess.PIPE,stdout=log,stderr=log);child.close();carrier.sendmsg([config],[(socket.SOL_SOCKET,socket.SCM_RIGHTS,struct.pack('i',right.fileno()))]);carrier.close();right.close()
 peer=base.Peer(left,context);peer.start();results=[]
 try:
  until=time.monotonic()+8
  while '[IGARECK_LISTEN_READY]' not in path.read_text():
   if proc.poll() is not None:raise RuntimeError(path.read_text()[-5000:])
   if time.monotonic()>until:raise TimeoutError('native readiness')
   time.sleep(.02)
  ports={int(n):int(port) for n,port in re.findall(r'\[IGARECK_PROBE_PORT\] profile=T(\d+) port=(\d+)',path.read_text())}
  for target,n,minimum,_,_ in PAGES.values():
   run=subprocess.run([str(a.jdk/'bin/java'),'-cp',cp,'com.igareck.antidpi.WebsiteNativeClient',str(ports[n]),target,str(cert),socket.inet_ntoa(base.REMOTE)],text=True,capture_output=True,timeout=15)
   if run.returncode:raise RuntimeError(run.stdout+run.stderr+str(peer.failure))
   result=json.loads(run.stdout.strip());assert result['pass'] and result['bytes']>=minimum
   if target=='FACEBOOK':
    assert result['origin_reachable'] and result['reason']=='ORIGIN_REACHABLE' and result['url'].endswith('/robots.txt'),result
   results.append({'target':target,'strategy':'T'+str(n),'result':result});print('WEB/NATIVE PASS',target,result['bytes'],flush=True)
  def probe(target, strategy, suffix):
   run=subprocess.run([str(a.jdk/'bin/java'),'-cp',cp,'com.igareck.antidpi.WebsiteNativeClient',str(ports[strategy]),
    target,str(cert),socket.inet_ntoa(base.REMOTE),suffix],check=True,text=True,capture_output=True,timeout=15)
   return json.loads(run.stdout.strip())

  case_checks={}
  cases=(
   ('short_x','X',1,'/fixture-short','COMPLETE_HTML',200,True),
   ('short_threads','THREADS',20,'/fixture-short','SHORT_COMPLETE_RESPONSE',200,False),
   ('http_400_threads','THREADS',20,'/fixture-http-error','HTTP_STATUS',400,False),
  )
  for name,target,strategy,suffix,reason,status,passed in cases:
   result=probe(target,strategy,suffix)
   assert result['pass']==passed and result['reason']==reason and result['http']==status,result
   assert result['tls'],result
   if status==200:assert 16384 < result['bytes'] < 102400,result
   else:assert result['bytes']==0 and len(result['modes'])==1,result
   case_checks[name]=result
   print('WEB/NATIVE CASE PASS',name,flush=True)

  for version,reason,status,passed in (
   ('generic','NOT_ORIGIN_TEXT',200,False),('html','NOT_ORIGIN_TEXT',200,False),
   ('rejected','HTTP_STATUS',403,False),('redirect','HTTP_STATUS',302,False),
   ('chunked','ORIGIN_REACHABLE',200,True),
  ):
   HtmlFlow.facebook_response=version
   result=probe('FACEBOOK',20,'/robots.txt')
   assert result['reason']==reason and result['http']==status and result['pass']==passed,result
   assert bool(result.get('origin_reachable'))==passed,result
   case_checks['facebook_robots_'+version]=result
   print('WEB/NATIVE CASE PASS Facebook',version,flush=True)
  result=probe('FACEBOOK',20,'/')
  assert not result['pass'] and result['reason']=='UNEXPECTED_PROBE_URL' and not result['tls'],result
  case_checks['facebook_other_path_rejected']=result
  print('WEB/NATIVE CASE PASS Facebook wrong path does not open a connection',flush=True)

  with socket.create_connection(('127.0.0.1',ports[1]),timeout=3) as local, local.makefile('rb') as replies:
   local.sendall(b'\x05\x01\x00')
   assert replies.read(2)==b'\x05\x00'
   local.sendall(b'\x05\x03\x00\x01\x00\x00\x00\x00\x00\x00')
   udp_reply=replies.read(2)
   assert udp_reply==b'\x05\x07',udp_reply
  print('WEB/NATIVE CASE PASS SOCKS UDP disabled',flush=True)
  assert peer.failure is None,peer.failure
  assert peer.dns_queries == 0, "Numeric scan unexpectedly sent UDP DNS through TAP"
 finally:
  proc.terminate()
  try:proc.wait(timeout=5)
  except subprocess.TimeoutExpired:proc.kill();proc.wait()
  peer.stop_event.set();peer.join(2);left.close();log.close()
 assert proc.returncode==0
 (out/'result.json').write_text(json.dumps({'passed':True,'tap_dns_queries':peer.dns_queries,'scope':'Production Java + native core/lwIP with independent host Ethernet/TLS peer','results':results,'case_checks':case_checks,'udp_off_socks_reply':udp_reply.hex()},indent=2)+'\n')
if __name__=='__main__':main()
