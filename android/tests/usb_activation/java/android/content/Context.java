package android.content;
import android.app.*;import android.net.nsd.*;import android.net.*;import android.net.wifi.*;import android.content.pm.*;import java.util.*;
public class Context {
 public static final String NSD_SERVICE="nsd";public static final int MODE_PRIVATE=0;public boolean permission=true;
 public final NotificationManager notifications=new NotificationManager();public final NsdManager nsd=new NsdManager();public final ConnectivityManager cm=new ConnectivityManager();public final WifiManager wifi=new WifiManager();private final Map<String,SharedPreferences> stores=new HashMap<>();
 public Context(){wifi.station=()->cm.wifi;}
 public ContentResolver getContentResolver(){return new ContentResolver();}public Context getApplicationContext(){return this;}
 public int checkSelfPermission(String p){return permission?0:-1;}public <T>T getSystemService(Class<T> k){return k.cast(k==WifiManager.class?wifi:k==ConnectivityManager.class?cm:notifications);}public Object getSystemService(String s){return nsd;}public java.util.concurrent.Executor getMainExecutor(){return Runnable::run;}
 public PackageManager getPackageManager(){return new PackageManager();}public SharedPreferences getSharedPreferences(String n,int mode){return stores.computeIfAbsent(n,key->new Memory());}
 static class Memory implements SharedPreferences {final Map<String,Object> map=new HashMap<>();public boolean getBoolean(String k,boolean d){return (Boolean)map.getOrDefault(k,d);}public int getInt(String k,int d){return (Integer)map.getOrDefault(k,d);}public String getString(String k,String d){return (String)map.getOrDefault(k,d);}public Editor edit(){return new Editor(){public Editor putBoolean(String k,boolean v){map.put(k,v);return this;}public Editor putInt(String k,int v){map.put(k,v);return this;}public Editor putString(String k,String v){map.put(k,v);return this;}public void apply(){}public boolean commit(){return true;}};}}
}
