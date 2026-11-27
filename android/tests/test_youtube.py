"""Production Java YouTube probe/scanner with a local, verified TLS/SOCKS fixture.
Requires lifecycle classes compiled by test_proxy_lifecycle.py; no YouTube access.
"""
import argparse,json,pathlib,subprocess
p=argparse.ArgumentParser();p.add_argument('--jdk',required=True);p.add_argument('--json-jar',required=True);a=p.parse_args()
r=pathlib.Path(__file__).resolve().parents[1];o=r/'build/youtube_checks';o.mkdir(parents=True,exist_ok=True);cert=o/'fixture.crt';key=o/'fixture.key';store=o/'fixture.p12'
subprocess.run(['openssl','req','-x509','-newkey','rsa:2048','-nodes','-days','2','-subj','/CN=www.youtube.com','-addext','subjectAltName=DNS:www.youtube.com,DNS:discord.com,DNS:www.discord.com,DNS:x.com','-keyout',str(key),'-out',str(cert)],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
subprocess.run(['openssl','pkcs12','-export','-in',str(cert),'-inkey',str(key),'-out',str(store),'-passout','pass:fixture-only'],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
c=r/'build/lifecycle/current';cp=f'{c}:{a.json_jar}'
subprocess.run([str(pathlib.Path(a.jdk)/'bin/javac'),'-encoding','UTF-8','-cp',cp,'-d',str(c),str(r/'tests/lifecycle/java/com/igareck/antidpi/YoutubeHarness.java'),str(r/'tests/lifecycle/java/com/igareck/antidpi/DiscordTcpHarness.java')],check=True)
s=subprocess.run([str(pathlib.Path(a.jdk)/'bin/java'),'-cp',cp,'com.igareck.antidpi.YoutubeHarness',str(store)],text=True,capture_output=True,timeout=40)
(o/'stdout.log').write_text(s.stdout);(o/'stderr.log').write_text(s.stderr)
if s.returncode:raise RuntimeError(s.stderr)
result=json.loads(s.stdout.strip().splitlines()[-1]);(o/'result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n');print('YOUTUBE PASS',result['checks'],'checks; max parallel',result['max_parallel'])

s=subprocess.run([str(pathlib.Path(a.jdk)/'bin/java'),'-cp',cp,'com.igareck.antidpi.DiscordTcpHarness',str(store)],text=True,capture_output=True,timeout=40)
(o/'discord-stdout.log').write_text(s.stdout);(o/'discord-stderr.log').write_text(s.stderr)
if s.returncode:raise RuntimeError(s.stderr)
result=json.loads(s.stdout.strip().splitlines()[-1]);(o/'discord-result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n');print('DISCORD TCP PASS',result['checks'],'checks; max parallel',result['max_parallel'])

# The lifecycle compile uses a resolver fixture. Test the real resolver in its
# own class directory so the service harness cannot accidentally use public DNS.
doh_classes=o/'doh-classes';doh_classes.mkdir(exist_ok=True)
production=r/'app/src/main/java/com/igareck/antidpi'
resolver=[production/n for n in ('DohMessage.java','ScanDoh.java')]
fixture=r/'tests/doh/java/com/igareck/antidpi/DohHarness.java'
subprocess.run([str(pathlib.Path(a.jdk)/'bin/javac'),'-encoding','UTF-8','-cp',cp,'-d',str(doh_classes),*map(str,resolver),str(fixture)],check=True)
s=subprocess.run([str(pathlib.Path(a.jdk)/'bin/java'),'-cp',f'{doh_classes}:{cp}','com.igareck.antidpi.DohHarness',str(store)],text=True,capture_output=True,timeout=30)
if s.returncode:raise RuntimeError(s.stderr)
result=json.loads(s.stdout.strip().splitlines()[-1]);(o/'doh-result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n');print('DOH PASS',result['checks'],'checks')
