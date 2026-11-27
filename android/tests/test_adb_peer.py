#!/usr/bin/env python3
"""Production ADB client/libadb/JNI with independent OpenSSL + Python protocol peers.
Android NSD/Keystore/broadcast permissions remain on-device boundaries.
"""
import argparse,base64,ctypes,hashlib,json,pathlib,select,socket,struct,subprocess,threading,time,zipfile
from OpenSSL import SSL,crypto
from cryptography import x509
from cryptography.hazmat.primitives import hashes,serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.x509.oid import NameOID
from datetime import datetime,timedelta,timezone

p=argparse.ArgumentParser();p.add_argument('--jdk',required=True);p.add_argument('--android-jar',required=True);p.add_argument('--json-jar',required=True);p.add_argument('--host-conscrypt',required=True);a=p.parse_args()
r=pathlib.Path(__file__).resolve().parents[1];out=r/'build/adb-peer';out.mkdir(parents=True,exist_ok=True);classes=out/'classes';classes.mkdir(exist_ok=True)
with zipfile.ZipFile(r/'deps/spake2-android-2.2.1.aar') as z:(out/'spake.jar').write_bytes(z.read('classes.jar'))
production=r/'app/src/main/java/com/igareck/antidpi';fixtures=r/'tests/lifecycle/java'
src=list((r/'vendor/libadb-android/libadb/src/main/java').rglob('*.java'))+list((r/'tests/adb_peer/java').rglob('*.java'))
src += [r/'tests/activation/java/com/igareck/antidpi/AppIdentity.java',r/'tests/activation/java/com/igareck/antidpi/PacketWorker.java']+[production/n for n in ['AdbClient.java','AdbIdentity.java','IdentityEnvelope.java','AdbBootstrap.java','WorkerChannel.java']]
jars=[*r.glob('deps/bc*.jar'),*r.glob('deps/*annotation*.jar'),out/'spake.jar',pathlib.Path(a.host_conscrypt),pathlib.Path(a.json_jar),pathlib.Path(a.android_jar)];cp=':'.join(map(str,jars))
subprocess.run([a.jdk+'/bin/javac','-encoding','UTF-8','-cp',cp,'-d',str(classes),*map(str,src)],check=True)
cmd=[a.jdk+'/bin/java','--add-opens=java.base/java.net=ALL-UNNAMED','--add-opens=java.base/java.lang=ALL-UNNAMED','-Djava.library.path='+str(r/'build_host/stage/lib/host'),'-cp',str(classes)+':'+cp,'com.igareck.antidpi.AdbPeerHarness']
native=ctypes.CDLL(str(r/'build_host/stage/lib/host/libspake2.so'))
native.SPAKE2_CTX_new.argtypes=[ctypes.c_int,ctypes.c_void_p,ctypes.c_size_t,ctypes.c_void_p,ctypes.c_size_t];native.SPAKE2_CTX_new.restype=ctypes.c_void_p
native.SPAKE2_generate_msg.argtypes=[ctypes.c_void_p,ctypes.c_void_p,ctypes.POINTER(ctypes.c_size_t),ctypes.c_size_t,ctypes.c_void_p,ctypes.c_size_t];native.SPAKE2_generate_msg.restype=ctypes.c_int
native.SPAKE2_process_msg.argtypes=native.SPAKE2_generate_msg.argtypes;native.SPAKE2_process_msg.restype=ctypes.c_int
native.SPAKE2_CTX_free.argtypes=[ctypes.c_void_p]

key=rsa.generate_private_key(public_exponent=65537,key_size=2048);name=x509.Name([x509.NameAttribute(NameOID.COMMON_NAME,'Independent ADB fixture')]);now=datetime.now(timezone.utc)
cert=x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key()).serial_number(x509.random_serial_number()).not_valid_before(now-timedelta(days=1)).not_valid_after(now+timedelta(days=2)).sign(key,hashes.SHA256())
ctx=SSL.Context(SSL.TLS_SERVER_METHOD);ctx.set_min_proto_version(SSL.TLS1_3_VERSION);ctx.set_max_proto_version(SSL.TLS1_3_VERSION);ctx.use_privatekey(crypto.PKey.from_cryptography_key(key));ctx.use_certificate(crypto.X509.from_cryptography(cert));ctx.set_verify(SSL.VERIFY_PEER|SSL.VERIFY_FAIL_IF_NO_PEER_CERT,lambda *unused:True)

def retry(io,method,*args):
 until=time.monotonic()+15
 while True:
  try:return method(*args)
  except (SSL.WantReadError,SSL.WantWriteError):
   if time.monotonic()>until:raise TimeoutError('TLS fixture deadline')
   select.select([io],[io],[],.01)
def recv(io,n):
 data=b''
 while len(data)<n:
  part=retry(io,io.recv,n-len(data))
  if not part:raise EOFError('Peer closed')
  data+=part
 return data
def send(io,data):
 while data:
  n=retry(io,io.send,data);data=data[n:]
def tls(sock):
 connection=SSL.Connection(ctx,sock);connection.set_accept_state();retry(connection,connection.do_handshake);return connection
def frame(io,kind,data):send(io,struct.pack('!BBI',1,kind,len(data))+data)
def read_frame(io,kind):
 version,actual,n=struct.unpack('!BBI',recv(io,6));assert version==1 and actual==kind and n<20000;return recv(io,n)
def adb_send(io,command,arg0=0,arg1=0,data=b''):
 value=int.from_bytes(command,'little');send(io,struct.pack('<6I',value,arg0,arg1,len(data),sum(data)&0xffffffff,value^0xffffffff)+data)
def adb_recv(io):
 command,arg0,arg1,size,checksum,magic=struct.unpack('<6I',recv(io,24));assert magic==command^0xffffffff and size<=1048576
 data=recv(io,size);assert checksum==sum(data)&0xffffffff;return command.to_bytes(4,'little'),arg0,arg1,data

def pairing(sock,mode,result):
 connection=tls(sock);exported=connection.export_keying_material(b'adb-label\x00',64);password=b'123456'+exported
 bob=native.SPAKE2_CTX_new(1,b'adb pair server\x00',16,b'adb pair client\x00',16)
 assert bob
 try:
  message=ctypes.create_string_buffer(32);length=ctypes.c_size_t();assert native.SPAKE2_generate_msg(bob,message,ctypes.byref(length),32,password,len(password))==1
  theirs=read_frame(connection,0);assert len(theirs)==32
  frame(connection,0,bytes(32) if mode=='bad-point' else message.raw[:length.value])
  if mode=='bad-point':return
  shared=ctypes.create_string_buffer(64);length=ctypes.c_size_t();assert native.SPAKE2_process_msg(bob,shared,ctypes.byref(length),64,theirs,len(theirs))==1
  secret=HKDF(algorithm=hashes.SHA256(),length=16,salt=None,info=b'adb pairing_auth aes-128-gcm key').derive(shared.raw[:length.value]);encrypted=read_frame(connection,1)
  try:peer=AESGCM(secret).decrypt(bytes(12),encrypted,None)
  except Exception:
   if mode=='wrong-code':result['wrong_code_rejected']=True;return
   raise
  assert len(peer)==8192 and peer[0]==0
  public=base64.b64decode(peer[1:].split(b' ')[0]);assert len(public)==524 and int.from_bytes(public[:4],'little')==64
  modulus=int.from_bytes(public[8:264],'little');assert modulus==connection.get_peer_certificate().to_cryptography().public_key().public_numbers().n
  result['client_public_key_matches_tls_certificate']=True
  payload=b'\x01independent-adb-device-guid\x00';payload+=bytes(8192-len(payload));frame(connection,1,AESGCM(secret).encrypt(bytes(12),payload,None))
 finally:native.SPAKE2_CTX_free(bob)

def adb(sock,result,plain=False):
 command,*rest=adb_recv(sock);assert command==b'CNXN'
 if plain:
  adb_send(sock,b'CNXN',0x01000001,1048576,b'device::\x00');assert sock.recv(1)==b'';result['no_shell_stream_over_plain_adb']=True;return
 adb_send(sock,b'STLS',0x01000000);assert adb_recv(sock)[0]==b'STLS';connection=tls(sock);adb_send(connection,b'CNXN',0x01000001,1048576,b'device::features=shell_v2,cmd;\x00')
 command,local,remote,destination=adb_recv(connection);assert command==b'OPEN' and destination==b'exec:sh\x00';remote=55;adb_send(connection,b'OKAY',remote,local);script=b''
 while not script.endswith(b'wait "$!"\n'):
  command,x,y,data=adb_recv(connection);assert command==b'WRTE' and x==local and y==remote;script+=data;assert len(script)<16384;adb_send(connection,b'OKAY',remote,local)
 assert b'export CLASSPATH=' in script and b'app_process64' in script and b'bootstrap_nonce' in script
 result['fixed_exec_stream_and_stdin_bootstrap']=True;result['bootstrap_bytes']=len(script);adb_send(connection,b'WRTE',remote,local,b'IGARECK_FIXTURE_OK\n')
 for _ in range(4):
  command,x,y,data=adb_recv(connection)
  if command==b'CLSE':adb_send(connection,b'CLSE',remote,local);break

results=[]
for mode in ['pair','wrong-code','bad-point','connect','plain-rejected']:
 listener=socket.socket();listener.bind(('127.0.0.1',0));listener.listen(1);port=listener.getsockname()[1];evidence={'mode':mode};errors=[]
 def serve():
  try:
   sock,_=listener.accept();sock.settimeout(15)
   with sock:
    if mode in ['connect','plain-rejected']:adb(sock,evidence,mode=='plain-rejected')
    else:pairing(sock,mode,evidence)
  except Exception as e:errors.append(repr(e))
 thread=threading.Thread(target=serve,daemon=True);thread.start()
 run=subprocess.run(cmd+[mode,str(port),str(out/'identity')],text=True,capture_output=True,timeout=35);listener.close();thread.join(3)
 (out/(mode+'.stdout')).write_text(run.stdout);(out/(mode+'.stderr')).write_text(run.stderr)
 if run.returncode:raise RuntimeError(mode+': '+run.stderr[-5000:]+'\npeer='+str(errors))
 evidence.update(json.loads([line for line in run.stdout.splitlines() if line.startswith("{")][-1]));evidence['peer_errors']=errors
 if mode in ['pair','connect','plain-rejected'] and errors:raise RuntimeError(mode+': '+str(errors))
 if mode=='wrong-code':assert evidence.get('wrong_code_rejected')
 results.append(evidence);print('ADB PEER PASS',mode,flush=True)
result={'passed':True,'cases':results,'production_client_and_libadb':True,'rebuilt_spake2_jni':True,'independent_tls_aes_hkdf_peer':True,'host_conscrypt':'2.5.2 (Android APK uses 2.5.3)','android_pairing_ui_keystore_mdns_broadcast_unverified':True}
(out/'result.json').write_text(json.dumps(result,indent=2)+'\n')
