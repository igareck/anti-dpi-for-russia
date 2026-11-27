#!/usr/bin/env python3
"""Production station detection; the old implementation is an explicit negative control."""
import argparse,json,pathlib,subprocess
p=argparse.ArgumentParser();p.add_argument('--jdk',required=True);p.add_argument('--json-jar',required=True);p.add_argument('--baseline-source');a=p.parse_args()
r=pathlib.Path(__file__).resolve().parents[1];out=r/('build/wifi-station-baseline' if a.baseline_source else 'build/wifi-station');out.mkdir(parents=True,exist_ok=True)
base=r/'tests/activation_service/java';shared=r/'tests/usb_activation/java'
files={str(f.relative_to(base)):f for f in (base/'android').rglob('*.java')};files.update({str(f.relative_to(shared)):f for f in (shared/'android').rglob('*.java')})
stubs={
'com/igareck/antidpi/EmbeddedAdb.java':'package com.igareck.antidpi;final class EmbeddedAdb {static boolean paired(){return true;}}',
'com/igareck/antidpi/ActivationPermission.java':'package com.igareck.antidpi;final class ActivationPermission {static final String WRITE="android.permission.WRITE_SECURE_SETTINGS";}',
'com/igareck/antidpi/WifiStationHarness.java':'''package com.igareck.antidpi;import android.content.Context;import android.os.Build;import org.json.*;public class WifiStationHarness {static int checks;static void check(boolean v,String s){checks++;if(!v)throw new AssertionError(s);}public static void main(String[] a)throws Exception{Context c=new Context();c.cm.wifi=true;c.wifi.station=()->false;
''' + ('''check(AutoActivation.wifiConnected(c),"Old Wi-Fi capability false positive not reproduced");System.out.println(new JSONObject().put("passed",true).put("checks",checks).put("expected_old_false_positive_reproduced",true));}}
''' if a.baseline_source else '''for(int sdk=30;sdk<=35;sdk++){Build.VERSION.SDK_INT=sdk;c.wifi.enabled=true;c.wifi.station=()->false;check(!AutoActivation.wifiConnected(c),"Disconnected/own hotspot transport accepted as external Wi-Fi SDK"+sdk);c.wifi.enabled=false;check(!AutoActivation.wifiConnected(c),"Radio off accepted SDK"+sdk);c.wifi.enabled=true;c.wifi.station=()->true;check(AutoActivation.wifiConnected(c),"Connected station rejected without SSID/location SDK"+sdk);check(c.wifi.getConnectionInfo().getNetworkId()==-1,"Fixture did not redact location identifiers");c.cm.wifi=false;check(!AutoActivation.wifiConnected(c),"Stale station info without physical Wi-Fi accepted");c.cm.wifi=true;}
 check(new JSONObject(AutoActivation.snapshot(c)).getString("wifi_station_state").equals("COMPLETED"),"Station state missing from diagnostic export");System.out.println(new JSONObject().put("passed",true).put("checks",checks).put("production_wifi_detection",true).put("android_boundaries","explicit station/capability fixtures; SDK30-35; location identifiers redacted").put("physical_samsung_tested",false));}}
''')}
for n,s in stubs.items():f=out/'fixtures'/n;f.parent.mkdir(parents=True,exist_ok=True);f.write_text(s);files[n]=f
prod=r/'app/src/main/java/com/igareck/antidpi';src=list(files.values())+[shared/'com/igareck/antidpi/AppLog.java',prod/'WirelessDebugging.java',pathlib.Path(a.baseline_source) if a.baseline_source else prod/'AutoActivation.java']
subprocess.run([a.jdk+'/bin/javac','-encoding','UTF-8','-cp',a.json_jar,'-d',str(out),*map(str,src)],check=True)
run=subprocess.run([a.jdk+'/bin/java','-cp',str(out)+':'+a.json_jar,'com.igareck.antidpi.WifiStationHarness'],text=True,capture_output=True,timeout=10)
(out/'stdout.log').write_text(run.stdout);(out/'stderr.log').write_text(run.stderr)
if run.returncode:raise RuntimeError(run.stderr)
v=json.loads(run.stdout.strip().splitlines()[-1]);(out/'result.json').write_text(json.dumps(v,indent=2)+'\n');print(json.dumps(v,indent=2))
