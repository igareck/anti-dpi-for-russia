#!/usr/bin/env python3
"""Actual TapSession.open with injected Android managers, clock, frames and inventory."""
import argparse,json,pathlib,subprocess
p=argparse.ArgumentParser();p.add_argument('--jdk',required=True);p.add_argument('--android-jar',required=True);p.add_argument('--json-jar',required=True);p.add_argument('--baseline-source');a=p.parse_args()
r=pathlib.Path(__file__).resolve().parents[1];out=r/('build/tap-startup-baseline' if a.baseline_source else 'build/tap-startup');out.mkdir(parents=True,exist_ok=True)
fixtures={
'android/system/StructPollfd.java':'''package android.system;public class StructPollfd {public java.io.FileDescriptor fd;public short events,revents;}''',
'android/content/ContextWrapper.java':'''package android.content;public class ContextWrapper extends Context {public ContextWrapper(Context c){}public String getOpPackageName(){return "com.android.shell";}}''',
'android/os/SystemClock.java':'''package android.os;public class SystemClock {public static long offset;public static long elapsedRealtime(){return System.nanoTime()/1000000+offset;}}''',
'android/os/ParcelFileDescriptor.java':'''package android.os;import java.io.*;public class ParcelFileDescriptor implements Closeable {final FileInputStream input;public ParcelFileDescriptor()throws Exception{input=new FileInputStream("/dev/null");}public FileDescriptor getFileDescriptor(){try{return input.getFD();}catch(Exception e){return new FileDescriptor();}}public void close()throws IOException{input.close();com.igareck.antidpi.TapStartupHarness.closed++;}}''',
'android/os/ServiceManager.java':'''package android.os;public class ServiceManager {public static IBinder getService(String s){return new Binder();}}''',
'android/net/EthernetManager.java':'''package android.net;import java.util.concurrent.Executor;import com.igareck.antidpi.TapStartupHarness;public class EthernetManager {public interface TetheredInterfaceCallback{void onAvailable(String n);void onUnavailable();}public static class Request{public void release(){TapStartupHarness.released++;}}public void setIncludeTestInterfaces(boolean v){TapStartupHarness.visibility+=v?1:-1;}public Request requestTetheredInterface(Executor e,TetheredInterfaceCallback c){e.execute(()->c.onAvailable("testtap"+TapStartupHarness.created));return new Request();}}''',
'android/net/TetheringManager.java':'''package android.net;import android.content.Context;import android.os.IBinder;import java.util.function.Supplier;import java.util.concurrent.Executor;import com.igareck.antidpi.TapStartupHarness;public class TetheringManager {public TetheringManager(Context c,Supplier<IBinder> b){}public static class TetheringRequest{public static class Builder{public Builder(int t){}public Builder setShouldShowEntitlementUi(boolean v){return this;}public TetheringRequest build(){return new TetheringRequest();}}}public interface StartTetheringCallback {void onTetheringStarted();void onTetheringFailed(int n);}public void startTethering(TetheringRequest r,Executor e,StartTetheringCallback c){if(TapStartupHarness.denyStart){e.execute(()->c.onTetheringFailed(3));return;}if(TapStartupHarness.dropStart&&TapStartupHarness.created==1){new Thread(()->{try{Thread.sleep(30);}catch(Exception ignored){}android.os.SystemClock.offset+=16000;}).start();return;}e.execute(c::onTetheringStarted);}public void stopTethering(int t)throws Exception{TapStartupHarness.stops++;if(TapStartupHarness.failStop){TapStartupHarness.failStop=false;throw new java.io.IOException("fixture stop failed");}}public String[] getTetheredIfaces(){TapStartupHarness.readiness++;if(TapStartupHarness.getterDenied)throw new SecurityException("OEM fixture");return TapStartupHarness.hidden?new String[0]:new String[]{"testtap"+TapStartupHarness.created};}}''',
'android/system/Os.java':'''package android.system;import java.io.FileDescriptor;import com.igareck.antidpi.TapStartupHarness;public class Os {public static int write(FileDescriptor fd,byte[] b,int o,int l)throws Exception{TapStartupHarness.write(java.util.Arrays.copyOfRange(b,o,o+l));return l;}public static int poll(StructPollfd[] f,int t){if(TapStartupHarness.queue.isEmpty()){android.os.SystemClock.offset+=t;return 0;}f[0].revents=OsConstants.POLLIN;return 1;}public static int read(FileDescriptor fd,byte[] b,int o,int l){byte[] x=TapStartupHarness.queue.remove();System.arraycopy(x,0,b,o,x.length);return x.length;}}''',
'com/igareck/antidpi/TapStartupHarness.java':r'''package com.igareck.antidpi;
import android.content.Context;import android.os.*;import java.util.*;import org.json.*;
public class TapStartupHarness {
 public static int created,closed,released,stops,visibility,readiness,discovers,requests,offerAfter=1,ackAfter=1;public static boolean firstFails,never,getterDenied,hidden,failStop,wrongXid;static long offerDelay,firstDiscoverAt=-1;public static boolean dropStart,denyStart;
 public static Runnable onWrite;public static final ArrayDeque<byte[]> queue=new ArrayDeque<>();static int checks;
 public static class Tap {final ParcelFileDescriptor fd;Tap()throws Exception{fd=new ParcelFileDescriptor();}public String getInterfaceName(){return "testtap"+created;}public ParcelFileDescriptor getFileDescriptor(){return fd;}}
 public static class TestNetwork {public Tap createTapInterface()throws Exception{created++;discovers=requests=0;return new Tap();}}
 public static class C extends Context {public Object getSystemService(String s){return s.equals("test_network")?new TestNetwork():new android.net.EthernetManager();}}
 static void check(boolean ok,String s){checks++;if(!ok)throw new AssertionError(s);}
 static void reset(){created=closed=released=stops=visibility=readiness=discovers=requests=0;offerAfter=ackAfter=1;firstFails=never=getterDenied=hidden=failStop=wrongXid=false;queue.clear();onWrite=null;SystemClock.offset=0;offerDelay=0;firstDiscoverAt=-1;dropStart=denyStart=false;}
 static void u16(byte[] b,int p,int n){b[p]=(byte)(n>>8);b[p+1]=(byte)n;}
 static byte[] reply(byte[] client,int type){byte[] b=client.clone();u16(b,34,67);u16(b,36,68);int d=42;b[d]=2;System.arraycopy(new byte[]{10,1,2,3},0,b,d+16,4);System.arraycopy(new byte[]{10,1,2,1},0,b,26,4);Arrays.fill(b,d+240,b.length,(byte)0);int p=d+240;b[p++]=53;b[p++]=1;b[p++]=(byte)type;for(int opt:new int[]{54,3,6}){b[p++]=(byte)opt;b[p++]=4;System.arraycopy(new byte[]{10,1,2,1},0,b,p,4);p+=4;}b[p]=(byte)255;return b;}
 public static void write(byte[] b){
  if(onWrite!=null)onWrite.run();
  if(b[12]==8&&b[13]==6){byte[] mac={2,3,4,5,6,7};byte[] x=PacketBuilder.replyToArpRequest(b,mac,new byte[]{10,1,2,1});queue.add(x);return;}
  int type=b[284]&255;
  if(type==1){discovers++;if(firstDiscoverAt<0)firstDiscoverAt=SystemClock.elapsedRealtime();if(!never&&!(firstFails&&created==1)&&discovers>=offerAfter&&SystemClock.elapsedRealtime()-firstDiscoverAt>=offerDelay){if(wrongXid){byte[] wrong=reply(b,2);wrong[46]^=1;queue.add(wrong);}queue.add(reply(b,2));}}
  if(type==3){requests++;if(requests>=ackAfter)queue.add(reply(b,5));}
 }
 static TapSession fresh(){return new TapSession(new C(),null,()->new String[0]);}
 static JSONObject value(TapSession t)throws Exception{return new JSONObject(t.progress());}
 public static void main(String[] a)throws Exception{
  reset();offerDelay=6000;TapSession late=fresh();late.open();check(value(late).getString("status").equals("TAP_READY"),"Late local DHCP offer lost");check(discovers==7&&created==1,"Late offer unnecessarily recreated TAP");check(readiness>0,"Own tethering readiness not inspected");late.close();check(closed==1&&released==1&&visibility==0,"Successful TAP leaked resources");
  reset();hidden=true;TapSession unreported=fresh();unreported.open();check(readiness==1&&discovers==1&&value(unreported).getString("tether_state").contains("not_reported"),"Missing optional inventory blocked readiness");unreported.close();
  reset();TapSession early=fresh();early.cancel();try{early.open();throw new AssertionError("Cancelled before open");}catch(java.io.IOException expected){}check(created==0,"Cancellation before open created an interface");
  reset();ackAfter=3;wrongXid=true;TapSession ack=fresh();ack.open();check(requests==3,"DHCP REQUEST was not retransmitted");check(value(ack).getInt("dhcp_packets_received")==2,"Foreign DHCP transaction accepted");ack.close();
  reset();firstFails=true;TapSession retry=fresh();retry.open();check(created==2&&closed==1&&released==1,"Transient DHCP did not clean before fresh TAP");check(value(retry).getJSONArray("setup_attempts").length()==2,"First failure evidence was discarded");retry.close();check(closed==2&&stops==2&&visibility==0,"Fresh retry leaked its own TAP");
  reset();never=true;TapSession timeout=fresh();boolean failed=false;try{timeout.open();}catch(Exception e){failed=e.getMessage().contains("DHCP OFFER timeout");}check(failed&&created==2,"Persistent DHCP failure was hidden or unbounded");check(closed==2&&value(timeout).getBoolean("cleanup_ok"),"Persistent failure lost cleanup");
  reset();firstFails=true;failStop=true;TapSession dirty=fresh();try{dirty.open();}catch(Exception expected){}check(created==1,"Fresh TAP created despite failed cleanup");dirty.close();
  reset();getterDenied=true;TapSession oem=fresh();oem.open();check(value(oem).getString("tether_state").contains("query_unavailable"),"OEM getter denial blocked valid DHCP");oem.close();
  reset();TapSession cancel=fresh();onWrite=cancel::cancel;failed=false;try{cancel.open();}catch(Exception e){failed=e.getMessage().contains("Cancelled");}check(failed&&created==1&&closed==1,"Cancel retried setup or leaked TAP");
  reset();TapSession foreign=new TapSession(new C(),null,()->new String[]{"eth9"});failed=false;try{foreign.open();}catch(Exception e){failed=e.getMessage().contains("Existing Ethernet");}check(failed&&created==0&&stops==0,"Foreign Ethernet was modified");
  reset();dropStart=true;TapSession missing=fresh();missing.open();check(created==2&&closed==1,"Missing tethering callback did not trigger one clean retry");check(value(missing).getJSONArray("setup_attempts").getJSONObject(0).getString("result").contains("Timed out waiting for tethering start"),"Tethering timeout lost its stage");missing.close();check(closed==2&&visibility==0,"Tethering retry leaked resources");
  reset();denyStart=true;TapSession denied=fresh();failed=false;try{denied.open();}catch(Exception e){failed=e.getMessage().contains("Ethernet tethering failed");}check(failed&&created==1,"Explicit tethering denial was retried or hidden");
  System.out.println(new JSONObject().put("passed",true).put("checks",checks).put("production_tap_open_and_dhcp",true).put("android_managers_frames_clock_inventory","injected fixtures").put("physical_samsung_tested",false));
 }
}'''}
if a.baseline_source:
 name='com/igareck/antidpi/TapStartupHarness.java';s=fixtures[name];s=s[:s.index(' public static void main(')]+r''' public static void main(String[] a)throws Exception{
 reset();offerDelay=6000;created=1;TapSession tap=new TapSession(new C());
 for(String name:new String[]{"deadline","tapFd"}){java.lang.reflect.Field f=TapSession.class.getDeclaredField(name);f.setAccessible(true);f.set(tap,name.equals("deadline")?Long.MAX_VALUE:new ParcelFileDescriptor());}
 java.lang.reflect.Method provision=TapSession.class.getDeclaredMethod("provisionClient");provision.setAccessible(true);boolean reproduced=false;
 try{provision.invoke(tap);}catch(java.lang.reflect.InvocationTargetException e){reproduced=e.getCause().getMessage().contains("DHCP OFFER timeout");}finally{tap.close();}
 check(reproduced,"Original short DHCP window did not reproduce the delayed-offer failure");
 System.out.println(new JSONObject().put("passed",true).put("checks",checks).put("expected_old_timeout_reproduced",true).put("scope","Original issued TAP provisionClient; same delayed local-offer frame fixture"));
 }}''';s=s.replace('new TapSession(new C(),null,()->new String[0])','new TapSession(new C())');fixtures[name]=s
base=r/'tests/lifecycle/java';sources={str(f.relative_to(base)):f for f in (base/'android').rglob('*.java')}
for n,t in fixtures.items():f=out/'fixtures'/n;f.parent.mkdir(parents=True,exist_ok=True);f.write_text(t);sources[n]=f
src=list(sources.values())+[base/'com/igareck/antidpi/AppLog.java']+[r/'app/src/main/java/com/igareck/antidpi'/n for n in ['TapSession.java','PacketBuilder.java','AppIdentity.java']]
if a.baseline_source:src=[pathlib.Path(a.baseline_source) if f.name=='TapSession.java' else f for f in src]
cp=a.json_jar+':'+a.android_jar
subprocess.run([a.jdk+'/bin/javac','-encoding','UTF-8','-cp',cp,'-d',str(out),*map(str,src)],check=True)
run=subprocess.run([a.jdk+'/bin/java','-cp',str(out)+':'+cp,'com.igareck.antidpi.TapStartupHarness'],capture_output=True,text=True,timeout=25)
(out/'stdout.log').write_text(run.stdout);(out/'stderr.log').write_text(run.stderr)
if run.returncode:raise RuntimeError(run.stderr)
v=json.loads(run.stdout.strip().splitlines()[-1]);(out/'result.json').write_text(json.dumps(v,indent=2)+'\n');print(json.dumps(v,indent=2))
