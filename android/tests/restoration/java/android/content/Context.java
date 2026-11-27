package android.content;
import android.app.*;import android.app.job.*;import android.net.*;import android.os.*;import java.util.*;
public class Context {
 public static final int MODE_PRIVATE=0;public static final String NSD_SERVICE="nsd";
 public static final JobScheduler jobs=new JobScheduler();public static final ConnectivityManager cm=new ConnectivityManager();public static final PowerManager power=new PowerManager();
 public static boolean permission=true,denyForeground;public static final List<Intent> starts=new ArrayList<>();
 public final NotificationManager notifications=new NotificationManager();private static final Map<String,SharedPreferences> stores=new HashMap<>();
 public static final android.net.wifi.WifiManager wifi=new android.net.wifi.WifiManager();
 public Context(){wifi.station=()->cm.wifi;}
 public Context getApplicationContext(){return this;}public int checkSelfPermission(String p){return permission?0:-1;}
 public <T>T getSystemService(Class<T> k){return k.cast(k==android.net.wifi.WifiManager.class?wifi:k==JobScheduler.class?jobs:k==ConnectivityManager.class?cm:k==PowerManager.class?power:notifications);}
 public SharedPreferences getSharedPreferences(String n,int mode){return stores.computeIfAbsent(n,key->new Memory());}
 public ComponentName startForegroundService(Intent i){if(denyForeground)throw new IllegalStateException("background denied");starts.add(i);return new ComponentName(this,i.type);}
 public ContentResolver getContentResolver(){return new ContentResolver();}
 static class Memory implements SharedPreferences {final Map<String,Object> map=new HashMap<>();public boolean getBoolean(String k,boolean d){return (Boolean)map.getOrDefault(k,d);}public int getInt(String k,int d){return (Integer)map.getOrDefault(k,d);}public String getString(String k,String d){return (String)map.getOrDefault(k,d);}public Editor edit(){return new Editor(){public Editor putBoolean(String k,boolean v){map.put(k,v);return this;}public Editor putInt(String k,int v){map.put(k,v);return this;}public Editor putString(String k,String v){map.put(k,v);return this;}public void apply(){}public boolean commit(){return true;}};}}
}
