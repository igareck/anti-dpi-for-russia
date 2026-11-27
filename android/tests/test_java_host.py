"""Production HTTP diagnostics and async log writer; host JVM, no Android UI claim."""
import argparse
import json
import pathlib
import socket
import ssl
import subprocess
import threading
import time

ROOT=pathlib.Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser()
parser.add_argument('--jdk',type=pathlib.Path,required=True)
parser.add_argument('--json-jar',type=pathlib.Path,required=True)
args=parser.parse_args();args.jdk=args.jdk.resolve();args.json_jar=args.json_jar.resolve()
out=ROOT/'build/java-host';out.mkdir(parents=True,exist_ok=True)
sources=list((ROOT/'tests/java').rglob('*.java'))
sources += [ROOT/'app/src/main/java/com/igareck/antidpi'/name for name in ['Diagnostics.java','DiskLog.java']]
subprocess.run([str(args.jdk/'bin/javac'),'-encoding','UTF-8','-source','8','-target','8','-cp',str(args.json_jar),'-d',str(out),*map(str,sources)],check=True)
classpath=str(out)+':'+str(args.json_jar)
java=[str(args.jdk/'bin/java'),'-cp',classpath]
subprocess.run(java+['com.igareck.antidpi.DiskLogHarness',str(out/'writer')],check=True)
subprocess.run(['openssl','req','-x509','-newkey','rsa:2048','-nodes','-keyout',str(out/'key.pem'),'-out',str(out/'cert.pem'),'-days','1','-subj','/CN=localhost','-addext','subjectAltName=DNS:localhost'],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
ctx=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER);ctx.load_cert_chain(out/'cert.pem',out/'key.pem')
payload=bytes(range(256))*4096
def handle(raw):
    try:
        with ctx.wrap_socket(raw,server_side=True) as tls:
            tls.settimeout(8);request=b''
            while b'\r\n\r\n' not in request:
                data=tls.recv(8192)
                if not data:return
                request+=data
            scenario=request.split()[1].decode().strip('/')
            if scenario=='no-content':tls.sendall(b'HTTP/1.1 204 No Content\r\n\r\n');return
            if scenario=='redirect':tls.sendall(b'HTTP/1.1 302 Found\r\nLocation: https://example.test\r\n\r\n');return
            if scenario=='encoding':tls.sendall(b'HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\n\r\n');return
            if scenario=='bad-chunk':tls.sendall(b'HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\nZZ\r\n');return
            if scenario=='stall':tls.sendall(b'HTTP/1.1 200 OK\r\nContent-Length: 1048576\r\n\r\n');time.sleep(12);return
            if scenario=='chunked':
                tls.sendall(b'HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n')
                for i in range(0,700000,10000):tls.sendall(b'2710;fixture=yes\r\n'+payload[i:i+10000]+b'\r\n')
                tls.sendall(b'0\r\n\r\n');return
            if scenario=='eof':tls.sendall(b'HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n'+payload[:524288]);return
            body=payload if scenario=='bulk' else payload[:32] if scenario=='small' else payload[:5000]
            length=len(body) if scenario!='short' else 1048576
            tls.sendall(b'HTTP/1.1 200 OK\r\nContent-Length: '+str(length).encode()+b'\r\n\r\n'+body)
    except OSError:raw.close()
server=socket.socket(socket.AF_INET6);server.setsockopt(socket.IPPROTO_IPV6,socket.IPV6_V6ONLY,0);server.bind(('::',0));server.listen(50)
def serve():
    while True:
        try:raw,_=server.accept()
        except OSError:return
        threading.Thread(target=handle,args=(raw,),daemon=True).start()
threading.Thread(target=serve,daemon=True).start()
# Independent SOCKS5 relay fixture, used only to exercise production Java diagnostics.
import select
proxy=socket.socket();proxy.bind(('127.0.0.1',0));proxy.listen(8);proxy_port=proxy.getsockname()[1]
def exact(stream,n):
    data=b''
    while len(data)<n:
        part=stream.recv(n-len(data))
        if not part:raise EOFError('short SOCKS fixture request')
        data+=part
    return data
def relay(raw):
    remote=None
    try:
        raw.settimeout(4);version,n=exact(raw,2);assert version==5;exact(raw,n);raw.sendall(b'\x05\x00')
        version,command,reserved,atyp=exact(raw,4);assert version==5 and command==1
        if atyp==3:host=exact(raw,exact(raw,1)[0]).decode('ascii')
        elif atyp==1:host=socket.inet_ntop(socket.AF_INET,exact(raw,4))
        elif atyp==4:host=socket.inet_ntop(socket.AF_INET6,exact(raw,16))
        else:raise ValueError('SOCKS address')
        port=int.from_bytes(exact(raw,2),'big');assert host in ('localhost','127.0.0.1','::1') and port==server.getsockname()[1]
        remote=socket.create_connection((host,port),4);raw.sendall(b'\x05\x00\x00\x01\x7f\x00\x00\x01'+port.to_bytes(2,'big'))
        raw.settimeout(None);remote.settimeout(None)
        while True:
            ready,_,_=select.select([raw,remote],[],[],20)
            if not ready:return
            for source in ready:
                data=source.recv(32768)
                if not data:return
                (remote if source is raw else raw).sendall(data)
    except (OSError,EOFError,ValueError,AssertionError):pass
    finally:
        raw.close()
        if remote:remote.close()
def serve_proxy():
    while True:
        try:raw,_=proxy.accept()
        except OSError:return
        threading.Thread(target=relay,args=(raw,),daemon=True).start()
threading.Thread(target=serve_proxy,daemon=True).start()
try:
    subprocess.run(java+['com.igareck.antidpi.DiagnosticsHarness',str(out/'cert.pem'),str(server.getsockname()[1]),str(proxy_port)],check=True,timeout=55)
finally:proxy.close();server.close()
